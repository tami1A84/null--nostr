'use client'

import { useState, useEffect, useCallback, useMemo } from 'react'
import { nip19, getEventHash } from 'nostr-tools'
import {
  parseProfile,
  signEventNip07,
  publishEvent,
  getDefaultRelay,
  shortenPubkey
} from '@/lib/nostr'
import {
  getWotScore,
  getWotScoresBatch,
  getVoteWeightFromWot
} from '@/lib/wot'
import {
  countLeadingZeroBits,
  getEventDifficulty,
  mineEvent,
  getVoteWeightFromPow,
  getCombinedVoteWeight,
  DIFFICULTY_LEVELS,
  getDifficultyLevel
} from '@/lib/pow'

// Polis-compatible Nostr event kinds
// These follow the PLURALITY_PROPOSAL.md specification
const KIND_POLIS_TOPIC = 1080      // Conversation/Topic creation
const KIND_POLIS_STATEMENT = 1081  // Statement submission
const KIND_POLIS_OPINION = 1082    // Opinion vote (agree/disagree/pass)

// Polis vote values (matching original Polis)
const VOTE_AGREE = -1
const VOTE_DISAGREE = 1
const VOTE_PASS = 0

// Relays for Polis events
const POLIS_RELAYS = [
  'wss://yabu.me',
  'wss://relay-jp.nostr.wirednet.jp'
]

// Fast fetch with short timeout (same pattern as SchedulerApp)
async function fastFetch(filter, relays, timeoutMs = 5000) {
  const results = []
  const seen = new Set()

  const fetchFromRelay = (relayUrl) => {
    return new Promise((resolve) => {
      try {
        const ws = new WebSocket(relayUrl)
        const subId = Math.random().toString(36).slice(2)
        let resolved = false

        const timeout = setTimeout(() => {
          if (!resolved) {
            resolved = true
            try { ws.close() } catch(e) {}
            resolve([])
          }
        }, timeoutMs)

        ws.onopen = () => {
          ws.send(JSON.stringify(['REQ', subId, filter]))
        }

        ws.onmessage = (msg) => {
          try {
            const data = JSON.parse(msg.data)
            if (data[0] === 'EVENT' && data[2]) {
              const event = data[2]
              if (!seen.has(event.id)) {
                seen.add(event.id)
                results.push(event)
              }
            } else if (data[0] === 'EOSE') {
              clearTimeout(timeout)
              if (!resolved) {
                resolved = true
                try { ws.close() } catch (closeErr) {}
                resolve(results)
              }
            }
          } catch (parseErr) {
            console.warn('Failed to parse relay message:', parseErr.message)
          }
        }

        ws.onerror = () => {
          clearTimeout(timeout)
          if (!resolved) {
            resolved = true
            resolve([])
          }
        }

        ws.onclose = () => {
          clearTimeout(timeout)
          if (!resolved) {
            resolved = true
            resolve([])
          }
        }
      } catch (e) {
        resolve([])
      }
    })
  }

  await Promise.all(relays.map(r => fetchFromRelay(r)))
  return results
}

// Generate unique ID
const generateId = () => Math.random().toString(36).substring(2, 15) + Date.now().toString(36)

// ============================================================================
// PCA Implementation (Power Iteration Method - matching Polis math)
// ============================================================================

/**
 * Power iteration PCA for opinion clustering
 * This matches the Polis math/pca.clj implementation
 */
function powerIterationPCA(matrix, nComponents = 2, maxIterations = 100, tolerance = 1e-6) {
  if (!matrix || matrix.length === 0 || matrix[0].length === 0) {
    return { components: [], projections: [] }
  }

  const n = matrix.length
  const m = matrix[0].length

  // Center the data (subtract mean from each column)
  const means = new Array(m).fill(0)
  for (let j = 0; j < m; j++) {
    let sum = 0
    let count = 0
    for (let i = 0; i < n; i++) {
      if (matrix[i][j] !== null && matrix[i][j] !== undefined) {
        sum += matrix[i][j]
        count++
      }
    }
    means[j] = count > 0 ? sum / count : 0
  }

  const centered = matrix.map(row =>
    row.map((val, j) => (val !== null && val !== undefined) ? val - means[j] : 0)
  )

  const components = []
  let residual = centered.map(row => [...row])

  for (let comp = 0; comp < nComponents; comp++) {
    // Initialize random vector
    let vector = new Array(m).fill(0).map(() => Math.random() - 0.5)
    let norm = Math.sqrt(vector.reduce((s, v) => s + v * v, 0))
    vector = vector.map(v => v / norm)

    // Power iteration
    for (let iter = 0; iter < maxIterations; iter++) {
      // Multiply: residual^T * residual * vector
      const temp = new Array(n).fill(0)
      for (let i = 0; i < n; i++) {
        for (let j = 0; j < m; j++) {
          temp[i] += residual[i][j] * vector[j]
        }
      }

      const newVector = new Array(m).fill(0)
      for (let j = 0; j < m; j++) {
        for (let i = 0; i < n; i++) {
          newVector[j] += residual[i][j] * temp[i]
        }
      }

      // Normalize
      norm = Math.sqrt(newVector.reduce((s, v) => s + v * v, 0))
      if (norm < tolerance) break

      const normalized = newVector.map(v => v / norm)

      // Check convergence
      const diff = vector.reduce((s, v, i) => s + Math.abs(v - normalized[i]), 0)
      vector = normalized

      if (diff < tolerance) break
    }

    components.push(vector)

    // Deflate: remove this component from residual
    const projections = residual.map(row =>
      row.reduce((s, v, j) => s + v * vector[j], 0)
    )

    for (let i = 0; i < n; i++) {
      for (let j = 0; j < m; j++) {
        residual[i][j] -= projections[i] * vector[j]
      }
    }
  }

  // Project original data onto components
  const projections = centered.map(row =>
    components.map(comp =>
      row.reduce((s, v, j) => s + v * comp[j], 0)
    )
  )

  return { components, projections, means }
}

/**
 * K-means clustering
 */
function kMeansClustering(points, k = 3, maxIterations = 50) {
  if (!points || points.length === 0) return { clusters: [], centroids: [] }
  if (points.length < k) k = points.length

  const n = points.length
  const dim = points[0].length

  // Initialize centroids randomly from points
  const indices = [...Array(n).keys()]
  for (let i = indices.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[indices[i], indices[j]] = [indices[j], indices[i]]
  }
  let centroids = indices.slice(0, k).map(i => [...points[i]])

  let clusters = new Array(n).fill(0)

  for (let iter = 0; iter < maxIterations; iter++) {
    // Assign points to nearest centroid
    const newClusters = points.map(point => {
      let minDist = Infinity
      let minIdx = 0
      for (let c = 0; c < k; c++) {
        const dist = point.reduce((s, v, d) => s + Math.pow(v - centroids[c][d], 2), 0)
        if (dist < minDist) {
          minDist = dist
          minIdx = c
        }
      }
      return minIdx
    })

    // Check convergence
    if (newClusters.every((c, i) => c === clusters[i])) break
    clusters = newClusters

    // Update centroids
    for (let c = 0; c < k; c++) {
      const clusterPoints = points.filter((_, i) => clusters[i] === c)
      if (clusterPoints.length > 0) {
        centroids[c] = new Array(dim).fill(0)
        for (const point of clusterPoints) {
          for (let d = 0; d < dim; d++) {
            centroids[c][d] += point[d] / clusterPoints.length
          }
        }
      }
    }
  }

  return { clusters, centroids }
}

// ============================================================================
// UI Components
// ============================================================================

// Cluster colors
const CLUSTER_COLORS = [
  { bg: 'bg-blue-500', text: 'text-blue-500', light: 'bg-blue-100 dark:bg-blue-900/30' },
  { bg: 'bg-green-500', text: 'text-green-500', light: 'bg-green-100 dark:bg-green-900/30' },
  { bg: 'bg-purple-500', text: 'text-purple-500', light: 'bg-purple-100 dark:bg-purple-900/30' },
  { bg: 'bg-orange-500', text: 'text-orange-500', light: 'bg-orange-100 dark:bg-orange-900/30' },
  { bg: 'bg-pink-500', text: 'text-pink-500', light: 'bg-pink-100 dark:bg-pink-900/30' },
]

/**
 * Opinion Scatter Plot - 2D visualization of opinion clusters
 */
function OpinionScatterPlot({ projections, clusters, participants, currentUserIndex }) {
  if (!projections || projections.length === 0) {
    return (
      <div className="h-64 flex items-center justify-center text-[var(--text-tertiary)]">
        投票データが不足しています
      </div>
    )
  }

  // Find bounds
  const xs = projections.map(p => p[0] || 0)
  const ys = projections.map(p => p[1] || 0)
  const minX = Math.min(...xs), maxX = Math.max(...xs)
  const minY = Math.min(...ys), maxY = Math.max(...ys)
  const rangeX = maxX - minX || 1
  const rangeY = maxY - minY || 1

  // Scale to SVG coordinates
  const padding = 20
  const width = 300
  const height = 200

  const scaleX = (x) => padding + ((x - minX) / rangeX) * (width - 2 * padding)
  const scaleY = (y) => padding + ((y - minY) / rangeY) * (height - 2 * padding)

  return (
    <svg viewBox={`0 0 ${width} ${height}`} className="w-full h-64">
      {/* Grid lines */}
      <line x1={width/2} y1={padding} x2={width/2} y2={height-padding}
            stroke="var(--border-color)" strokeDasharray="4"/>
      <line x1={padding} y1={height/2} x2={width-padding} y2={height/2}
            stroke="var(--border-color)" strokeDasharray="4"/>

      {/* Points */}
      {projections.map((point, i) => {
        const cluster = clusters[i] || 0
        const color = CLUSTER_COLORS[cluster % CLUSTER_COLORS.length]
        const isCurrentUser = i === currentUserIndex

        return (
          <g key={i}>
            <circle
              cx={scaleX(point[0] || 0)}
              cy={scaleY(point[1] || 0)}
              r={isCurrentUser ? 8 : 5}
              className={`${color.bg} ${isCurrentUser ? 'stroke-2 stroke-white' : ''}`}
              opacity={isCurrentUser ? 1 : 0.7}
            />
            {isCurrentUser && (
              <text
                x={scaleX(point[0] || 0)}
                y={scaleY(point[1] || 0) - 12}
                textAnchor="middle"
                className="text-xs fill-current text-[var(--text-primary)]"
              >
                あなた
              </text>
            )}
          </g>
        )
      })}
    </svg>
  )
}

/**
 * Consensus Highlight - Shows statements with high agreement across clusters
 */
function ConsensusHighlight({ statements, opinions, clusters }) {
  const consensusStatements = useMemo(() => {
    if (!statements || !opinions || statements.length === 0) return []

    // Find statements where majority of all clusters agree
    return statements.map(statement => {
      const statementOpinions = opinions.filter(o =>
        o.tags.find(t => t[0] === 'e' && t[1] === statement.id)
      )

      const agrees = statementOpinions.filter(o =>
        o.tags.find(t => t[0] === 'opinion' && t[1] === 'agree')
      ).length
      const total = statementOpinions.length
      const agreeRate = total > 0 ? agrees / total : 0

      return { statement, agreeRate, total }
    }).filter(s => s.agreeRate >= 0.7 && s.total >= 3)
      .sort((a, b) => b.agreeRate - a.agreeRate)
      .slice(0, 5)
  }, [statements, opinions])

  if (consensusStatements.length === 0) {
    return null
  }

  return (
    <div className="bg-green-50 dark:bg-green-900/20 rounded-xl p-4 mt-4">
      <h3 className="font-semibold text-green-700 dark:text-green-400 mb-2 flex items-center gap-2">
        <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <path d="M22 11.08V12a10 10 0 11-5.93-9.14"/>
          <polyline points="22 4 12 14.01 9 11.01"/>
        </svg>
        合意点
      </h3>
      <ul className="space-y-2">
        {consensusStatements.map(({ statement, agreeRate, total }) => (
          <li key={statement.id} className="text-sm">
            <span className="text-[var(--text-primary)]">{statement.content}</span>
            <span className="ml-2 text-green-600 dark:text-green-400">
              ({Math.round(agreeRate * 100)}% 賛成, {total}票)
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}

/**
 * Cluster Summary - Shows representative opinions per cluster
 */
function ClusterSummary({ statements, opinions, clusters, participants }) {
  const clusterSummaries = useMemo(() => {
    if (!clusters || !statements || !opinions) return []

    const uniqueClusters = [...new Set(clusters)]
    return uniqueClusters.map(clusterId => {
      const clusterParticipants = participants.filter((_, i) => clusters[i] === clusterId)
      const clusterPubkeys = new Set(clusterParticipants.map(p => p.pubkey))

      // Find statements this cluster strongly agrees/disagrees with
      const statementScores = statements.map(statement => {
        const clusterOpinions = opinions.filter(o => {
          const isForStatement = o.tags.find(t => t[0] === 'e' && t[1] === statement.id)
          return isForStatement && clusterPubkeys.has(o.pubkey)
        })

        const agrees = clusterOpinions.filter(o =>
          o.tags.find(t => t[0] === 'opinion' && t[1] === 'agree')
        ).length
        const disagrees = clusterOpinions.filter(o =>
          o.tags.find(t => t[0] === 'opinion' && t[1] === 'disagree')
        ).length

        return {
          statement,
          score: agrees - disagrees,
          total: agrees + disagrees
        }
      }).filter(s => s.total > 0)
        .sort((a, b) => Math.abs(b.score) - Math.abs(a.score))

      return {
        clusterId,
        size: clusterParticipants.length,
        topAgree: statementScores.filter(s => s.score > 0).slice(0, 2),
        topDisagree: statementScores.filter(s => s.score < 0).slice(0, 2)
      }
    })
  }, [statements, opinions, clusters, participants])

  if (clusterSummaries.length === 0) return null

  return (
    <div className="space-y-3 mt-4">
      <h3 className="font-semibold text-[var(--text-primary)]">グループ別の傾向</h3>
      {clusterSummaries.map(summary => {
        const color = CLUSTER_COLORS[summary.clusterId % CLUSTER_COLORS.length]
        return (
          <div key={summary.clusterId} className={`${color.light} rounded-xl p-3`}>
            <div className="flex items-center gap-2 mb-2">
              <div className={`w-3 h-3 rounded-full ${color.bg}`}/>
              <span className="font-medium text-[var(--text-primary)]">
                グループ {summary.clusterId + 1}
              </span>
              <span className="text-xs text-[var(--text-tertiary)]">
                ({summary.size}人)
              </span>
            </div>
            {summary.topAgree.length > 0 && (
              <div className="text-xs text-green-600 dark:text-green-400 mb-1">
                賛成傾向: {summary.topAgree.map(s => s.statement.content.slice(0, 30)).join(', ')}
              </div>
            )}
            {summary.topDisagree.length > 0 && (
              <div className="text-xs text-red-600 dark:text-red-400">
                反対傾向: {summary.topDisagree.map(s => s.statement.content.slice(0, 30)).join(', ')}
              </div>
            )}
          </div>
        )
      })}
    </div>
  )
}

/**
 * WoT Score Badge - Shows Web of Trust score
 */
function WotScoreBadge({ score, loading }) {
  if (loading) {
    return (
      <span className="px-2 py-0.5 rounded-full bg-[var(--bg-tertiary)] text-xs text-[var(--text-tertiary)]">
        WoT...
      </span>
    )
  }

  if (score === undefined || score === null) return null

  let bgColor, textColor, label
  if (score === Infinity) {
    bgColor = 'bg-blue-100 dark:bg-blue-900/30'
    textColor = 'text-blue-600 dark:text-blue-400'
    label = 'あなた'
  } else if (score >= 5) {
    bgColor = 'bg-green-100 dark:bg-green-900/30'
    textColor = 'text-green-600 dark:text-green-400'
    label = `WoT +${score}`
  } else if (score >= 1) {
    bgColor = 'bg-yellow-100 dark:bg-yellow-900/30'
    textColor = 'text-yellow-600 dark:text-yellow-400'
    label = `WoT +${score}`
  } else if (score === 0) {
    bgColor = 'bg-gray-100 dark:bg-gray-900/30'
    textColor = 'text-gray-600 dark:text-gray-400'
    label = 'WoT 0'
  } else {
    bgColor = 'bg-red-100 dark:bg-red-900/30'
    textColor = 'text-red-600 dark:text-red-400'
    label = `WoT ${score}`
  }

  return (
    <span className={`px-2 py-0.5 rounded-full text-xs ${bgColor} ${textColor}`}>
      {label}
    </span>
  )
}

/**
 * PoW Difficulty Selector - Choose vote weight via Proof of Work
 */
function PowDifficultySelector({ selectedLevel, onSelect, mining, miningProgress }) {
  return (
    <div className="bg-[var(--bg-secondary)] rounded-xl p-3 mb-4">
      <div className="flex items-center justify-between mb-2">
        <span className="text-sm font-medium text-[var(--text-primary)]">
          投票の重み（Quadratic Voting）
        </span>
        <span className="text-xs text-[var(--text-tertiary)]">
          PoW難易度で重みを増加
        </span>
      </div>
      <div className="flex gap-2">
        {DIFFICULTY_LEVELS.map((level, idx) => (
          <button
            key={idx}
            onClick={() => onSelect(level)}
            disabled={mining}
            className={`flex-1 py-2 px-1 rounded-lg text-xs transition-colors ${
              selectedLevel?.minDifficulty === level.minDifficulty
                ? 'bg-[var(--line-green)] text-white'
                : 'bg-[var(--bg-primary)] text-[var(--text-secondary)] hover:bg-[var(--bg-tertiary)]'
            } ${mining ? 'opacity-50' : ''}`}
          >
            <div className="font-medium">{level.votes}票</div>
            <div className="text-[10px] opacity-70">{level.label}</div>
          </button>
        ))}
      </div>
      {mining && (
        <div className="mt-2 text-xs text-[var(--text-tertiary)] text-center">
          マイニング中... {miningProgress}
        </div>
      )}
    </div>
  )
}

/**
 * Statement Voting Card - Polis-style voting interface with PoW/WoT
 */
function StatementVotingCard({ statement, onVote, myVote, authorProfile, isVoting, authorWotScore, wotLoading, enablePow = false }) {
  const [selectedPowLevel, setSelectedPowLevel] = useState(DIFFICULTY_LEVELS[0])
  const [mining, setMining] = useState(false)
  const [miningProgress, setMiningProgress] = useState('')

  const voteLabel = myVote === 'agree' ? '賛成済み' : myVote === 'disagree' ? '反対済み' : myVote === 'pass' ? 'パス済み' : null

  const handleVote = async (opinion) => {
    if (selectedPowLevel.minDifficulty > 0) {
      setMining(true)
      setMiningProgress('開始中...')
    }
    await onVote(opinion, selectedPowLevel.minDifficulty, (iteration, hashRate) => {
      setMiningProgress(`${Math.floor(iteration / 1000)}k試行 (${Math.floor(hashRate / 1000)}kH/s)`)
    })
    setMining(false)
    setMiningProgress('')
  }

  return (
    <div className="bg-[var(--bg-primary)] rounded-2xl shadow-lg p-5 border border-[var(--border-color)]">
      <div className="flex items-start gap-3 mb-4">
        {authorProfile?.picture ? (
          <img
            src={authorProfile.picture}
            alt=""
            className="w-10 h-10 rounded-full object-cover"
          />
        ) : (
          <div className="w-10 h-10 rounded-full bg-[var(--bg-tertiary)] flex items-center justify-center">
            <svg className="w-5 h-5 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="currentColor">
              <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/>
            </svg>
          </div>
        )}
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <p className="font-medium text-[var(--text-primary)] text-sm">
              {authorProfile?.name || shortenPubkey(statement.pubkey)}
            </p>
            <WotScoreBadge score={authorWotScore} loading={wotLoading} />
          </div>
          <p className="text-xs text-[var(--text-tertiary)]">
            {new Date(statement.created_at * 1000).toLocaleDateString('ja-JP')}
          </p>
        </div>
      </div>

      <p className="text-[var(--text-primary)] text-lg mb-4 leading-relaxed">
        {statement.content}
      </p>

      {myVote ? (
        <div className="text-center py-3 bg-[var(--bg-secondary)] rounded-xl">
          <span className="text-[var(--text-secondary)]">{voteLabel}</span>
        </div>
      ) : (
        <>
          {enablePow && (
            <PowDifficultySelector
              selectedLevel={selectedPowLevel}
              onSelect={setSelectedPowLevel}
              mining={mining}
              miningProgress={miningProgress}
            />
          )}
          <div className="flex gap-3">
            <button
              onClick={() => handleVote('agree')}
              disabled={isVoting || mining}
              className="flex-1 py-3 rounded-xl bg-green-500 hover:bg-green-600 text-white font-medium transition-colors disabled:opacity-50"
            >
              賛成{selectedPowLevel.votes > 1 ? ` (${selectedPowLevel.votes}票)` : ''}
            </button>
            <button
              onClick={() => handleVote('pass')}
              disabled={isVoting || mining}
              className="flex-1 py-3 rounded-xl bg-gray-400 hover:bg-gray-500 text-white font-medium transition-colors disabled:opacity-50"
            >
              パス
            </button>
            <button
              onClick={() => handleVote('disagree')}
              disabled={isVoting || mining}
              className="flex-1 py-3 rounded-xl bg-red-500 hover:bg-red-600 text-white font-medium transition-colors disabled:opacity-50"
            >
              反対{selectedPowLevel.votes > 1 ? ` (${selectedPowLevel.votes}票)` : ''}
            </button>
          </div>
        </>
      )}
    </div>
  )
}

/**
 * Create Topic Form
 */
function CreateTopicForm({ pubkey, onCreated, onCancel }) {
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [creating, setCreating] = useState(false)

  const handleCreate = async () => {
    if (!title.trim()) {
      alert('トピックタイトルを入力してください')
      return
    }

    setCreating(true)
    try {
      const dTag = generateId()

      const event = {
        kind: KIND_POLIS_TOPIC,
        created_at: Math.floor(Date.now() / 1000),
        tags: [
          ['d', dTag],
          ['title', title.trim()],
          ['polis', 'topic'],
          ['client', 'nurunuru-polis']
        ],
        content: description || '',
        pubkey
      }

      const signed = await signEventNip07(event)
      await publishEvent(signed, POLIS_RELAYS)

      alert('トピックを作成しました！')
      onCreated?.(signed)
    } catch (e) {
      console.error('Failed to create topic:', e)
      alert('トピックの作成に失敗しました: ' + e.message)
    } finally {
      setCreating(false)
    }
  }

  return (
    <div className="space-y-4">
      <div>
        <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1">
          トピックタイトル *
        </label>
        <input
          type="text"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="例: SNSにおける実名制について"
          className="w-full px-4 py-3 rounded-xl bg-[var(--bg-primary)] border border-[var(--border-color)] text-[var(--text-primary)] placeholder-[var(--text-tertiary)]"
        />
      </div>

      <div>
        <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1">
          説明（任意）
        </label>
        <textarea
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="このトピックについて詳しく説明してください..."
          rows={3}
          className="w-full px-4 py-3 rounded-xl bg-[var(--bg-primary)] border border-[var(--border-color)] text-[var(--text-primary)] placeholder-[var(--text-tertiary)] resize-none"
        />
      </div>

      <div className="flex gap-3">
        <button
          onClick={onCancel}
          className="flex-1 py-3 rounded-xl border border-[var(--border-color)] text-[var(--text-secondary)] font-medium"
        >
          キャンセル
        </button>
        <button
          onClick={handleCreate}
          disabled={creating || !title.trim()}
          className="flex-1 py-3 rounded-xl bg-[var(--line-green)] text-white font-medium disabled:opacity-50"
        >
          {creating ? '作成中...' : '作成'}
        </button>
      </div>
    </div>
  )
}

/**
 * Add Statement Form
 */
function AddStatementForm({ topicId, pubkey, onCreated }) {
  const [content, setContent] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const handleSubmit = async () => {
    if (!content.trim()) return

    setSubmitting(true)
    try {
      const event = {
        kind: KIND_POLIS_STATEMENT,
        created_at: Math.floor(Date.now() / 1000),
        tags: [
          ['e', topicId, '', 'root'],
          ['polis', 'statement'],
          ['client', 'nurunuru-polis']
        ],
        content: content.trim(),
        pubkey
      }

      const signed = await signEventNip07(event)
      await publishEvent(signed, POLIS_RELAYS)

      setContent('')
      onCreated?.(signed)
    } catch (e) {
      console.error('Failed to submit statement:', e)
      alert('意見の投稿に失敗しました: ' + e.message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="bg-[var(--bg-secondary)] rounded-xl p-4">
      <textarea
        value={content}
        onChange={(e) => setContent(e.target.value)}
        placeholder="新しい意見を追加..."
        rows={2}
        className="w-full px-3 py-2 rounded-lg bg-[var(--bg-primary)] border border-[var(--border-color)] text-[var(--text-primary)] placeholder-[var(--text-tertiary)] resize-none text-sm"
      />
      <div className="flex justify-end mt-2">
        <button
          onClick={handleSubmit}
          disabled={submitting || !content.trim()}
          className="px-4 py-2 rounded-lg bg-[var(--line-green)] text-white text-sm font-medium disabled:opacity-50"
        >
          {submitting ? '投稿中...' : '意見を追加'}
        </button>
      </div>
    </div>
  )
}

/**
 * Topic View - Main conversation view
 */
function TopicView({ topic, pubkey, onBack, profiles }) {
  const [statements, setStatements] = useState([])
  const [opinions, setOpinions] = useState([])
  const [currentIndex, setCurrentIndex] = useState(0)
  const [isVoting, setIsVoting] = useState(false)
  const [loading, setLoading] = useState(true)
  const [showStats, setShowStats] = useState(false)
  const [showAddStatement, setShowAddStatement] = useState(false)
  const [enablePow, setEnablePow] = useState(false)

  // WoT scores for statement authors
  const [wotScores, setWotScores] = useState({})
  const [wotLoading, setWotLoading] = useState(false)

  // Fetch statements and opinions
  const fetchData = useCallback(async () => {
    setLoading(true)
    try {
      // Fetch statements
      const statementsFilter = {
        kinds: [KIND_POLIS_STATEMENT],
        '#e': [topic.id]
      }
      const fetchedStatements = await fastFetch(statementsFilter, POLIS_RELAYS)
      setStatements(fetchedStatements.sort((a, b) => a.created_at - b.created_at))

      // Fetch all opinions
      const opinionsFilter = {
        kinds: [KIND_POLIS_OPINION],
        '#e': [topic.id]
      }
      const fetchedOpinions = await fastFetch(opinionsFilter, POLIS_RELAYS)
      setOpinions(fetchedOpinions)
    } catch (e) {
      console.error('Failed to fetch data:', e)
    } finally {
      setLoading(false)
    }
  }, [topic.id])

  useEffect(() => {
    fetchData()
  }, [fetchData])

  // Fetch WoT scores for statement authors
  useEffect(() => {
    if (!pubkey || statements.length === 0) return

    const fetchWotScores = async () => {
      setWotLoading(true)
      try {
        const authorPubkeys = [...new Set(statements.map(s => s.pubkey))]
        const scores = await getWotScoresBatch(pubkey, authorPubkeys, POLIS_RELAYS)
        const scoreMap = {}
        scores.forEach((data, pk) => {
          scoreMap[pk] = data.score
        })
        setWotScores(scoreMap)
      } catch (e) {
        console.error('Failed to fetch WoT scores:', e)
      } finally {
        setWotLoading(false)
      }
    }

    fetchWotScores()
  }, [pubkey, statements])

  // Get my votes
  const myVotes = useMemo(() => {
    const votes = {}
    opinions
      .filter(o => o.pubkey === pubkey)
      .forEach(o => {
        const statementId = o.tags.find(t => t[0] === 'e' && t[1] !== topic.id)?.[1]
        const opinion = o.tags.find(t => t[0] === 'opinion')?.[1]
        if (statementId && opinion) {
          if (!votes[statementId] || o.created_at > votes[statementId].created_at) {
            votes[statementId] = { opinion, created_at: o.created_at }
          }
        }
      })
    return Object.fromEntries(
      Object.entries(votes).map(([k, v]) => [k, v.opinion])
    )
  }, [opinions, pubkey, topic.id])

  // Get unvoted statements
  const unvotedStatements = useMemo(() =>
    statements.filter(s => !myVotes[s.id]),
    [statements, myVotes]
  )

  // Calculate clustering
  const clusteringData = useMemo(() => {
    if (statements.length < 2 || opinions.length < 5) {
      return null
    }

    // Build vote matrix: participants x statements
    const participantMap = new Map()
    opinions.forEach(o => {
      if (!participantMap.has(o.pubkey)) {
        participantMap.set(o.pubkey, { pubkey: o.pubkey, votes: {} })
      }
      const statementId = o.tags.find(t => t[0] === 'e' && t[1] !== topic.id)?.[1]
      const opinion = o.tags.find(t => t[0] === 'opinion')?.[1]
      if (statementId && opinion) {
        const voteValue = opinion === 'agree' ? VOTE_AGREE :
                         opinion === 'disagree' ? VOTE_DISAGREE : VOTE_PASS
        participantMap.get(o.pubkey).votes[statementId] = voteValue
      }
    })

    const participants = Array.from(participantMap.values())
    const statementIds = statements.map(s => s.id)

    // Create matrix
    const matrix = participants.map(p =>
      statementIds.map(sId => p.votes[sId] ?? null)
    )

    // Filter out participants with too few votes
    const validIndices = matrix
      .map((row, i) => ({ i, count: row.filter(v => v !== null).length }))
      .filter(x => x.count >= 2)
      .map(x => x.i)

    if (validIndices.length < 3) return null

    const filteredMatrix = validIndices.map(i => matrix[i])
    const filteredParticipants = validIndices.map(i => participants[i])

    // Run PCA
    const { projections } = powerIterationPCA(filteredMatrix, 2)

    // Run clustering
    const k = Math.min(3, Math.floor(filteredParticipants.length / 2))
    const { clusters } = kMeansClustering(projections, k)

    // Find current user index
    const currentUserIndex = filteredParticipants.findIndex(p => p.pubkey === pubkey)

    return {
      projections,
      clusters,
      participants: filteredParticipants,
      currentUserIndex
    }
  }, [statements, opinions, pubkey, topic.id])

  // Handle vote with optional PoW
  const handleVote = async (opinion, targetDifficulty = 0, onProgress = null) => {
    if (currentIndex >= unvotedStatements.length) return

    const statement = unvotedStatements[currentIndex]
    setIsVoting(true)

    try {
      let event = {
        kind: KIND_POLIS_OPINION,
        created_at: Math.floor(Date.now() / 1000),
        tags: [
          ['e', statement.id],
          ['e', topic.id, '', 'root'],
          ['opinion', opinion],
          ['polis', 'opinion'],
          ['client', 'nurunuru-polis']
        ],
        content: '',
        pubkey
      }

      // If PoW is requested, mine the event
      if (targetDifficulty > 0) {
        event = await mineEvent(event, targetDifficulty, {
          maxIterations: 50_000_000,
          onProgress
        })
      }

      const signed = await signEventNip07(event)
      await publishEvent(signed, POLIS_RELAYS)

      // Update local state
      setOpinions(prev => [...prev, signed])
      setCurrentIndex(prev => prev + 1)
    } catch (e) {
      console.error('Failed to vote:', e)
      if (e.message === 'Mining aborted') {
        alert('マイニングがキャンセルされました')
      } else {
        alert('投票に失敗しました: ' + e.message)
      }
    } finally {
      setIsVoting(false)
    }
  }

  const topicTitle = topic.tags.find(t => t[0] === 'title')?.[1] || 'トピック'
  const votedCount = Object.keys(myVotes).length
  const totalCount = statements.length

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="animate-spin rounded-full h-8 w-8 border-2 border-[var(--line-green)] border-t-transparent"/>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center gap-3">
        <button
          onClick={onBack}
          className="p-2 rounded-full hover:bg-[var(--bg-tertiary)] transition-colors"
        >
          <svg className="w-5 h-5 text-[var(--text-secondary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M19 12H5M12 19l-7-7 7-7"/>
          </svg>
        </button>
        <div className="flex-1 min-w-0">
          <h2 className="font-semibold text-[var(--text-primary)] truncate">{topicTitle}</h2>
          <p className="text-xs text-[var(--text-tertiary)]">
            {totalCount}件の意見 · {votedCount}件投票済み
          </p>
        </div>
      </div>

      {/* Topic description */}
      {topic.content && (
        <p className="text-sm text-[var(--text-secondary)] bg-[var(--bg-secondary)] rounded-xl p-3">
          {topic.content}
        </p>
      )}

      {/* Progress bar */}
      <div className="bg-[var(--bg-secondary)] rounded-full h-2 overflow-hidden">
        <div
          className="bg-[var(--line-green)] h-full transition-all duration-300"
          style={{ width: `${totalCount > 0 ? (votedCount / totalCount) * 100 : 0}%` }}
        />
      </div>

      {/* Tab buttons */}
      <div className="flex gap-2">
        <button
          onClick={() => setShowStats(false)}
          className={`flex-1 py-2 rounded-xl text-sm font-medium transition-colors ${
            !showStats
              ? 'bg-[var(--line-green)] text-white'
              : 'bg-[var(--bg-secondary)] text-[var(--text-secondary)]'
          }`}
        >
          投票する
        </button>
        <button
          onClick={() => setShowStats(true)}
          className={`flex-1 py-2 rounded-xl text-sm font-medium transition-colors ${
            showStats
              ? 'bg-[var(--line-green)] text-white'
              : 'bg-[var(--bg-secondary)] text-[var(--text-secondary)]'
          }`}
        >
          統計を見る
        </button>
      </div>

      {showStats ? (
        /* Stats view */
        <div className="space-y-4">
          {clusteringData ? (
            <>
              <div className="bg-[var(--bg-secondary)] rounded-2xl p-4">
                <h3 className="font-semibold text-[var(--text-primary)] mb-3">意見マップ</h3>
                <OpinionScatterPlot
                  projections={clusteringData.projections}
                  clusters={clusteringData.clusters}
                  participants={clusteringData.participants}
                  currentUserIndex={clusteringData.currentUserIndex}
                />
              </div>

              <ClusterSummary
                statements={statements}
                opinions={opinions}
                clusters={clusteringData.clusters}
                participants={clusteringData.participants}
              />

              <ConsensusHighlight
                statements={statements}
                opinions={opinions}
                clusters={clusteringData.clusters}
              />
            </>
          ) : (
            <div className="bg-[var(--bg-secondary)] rounded-2xl p-6 text-center">
              <svg className="w-12 h-12 mx-auto mb-3 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                <path d="M9 19c-5 1.5-5-2.5-7-3m14 6v-3.87a3.37 3.37 0 00-.94-2.61c3.14-.35 6.44-1.54 6.44-7A5.44 5.44 0 0020 4.77 5.07 5.07 0 0019.91 1S18.73.65 16 2.48a13.38 13.38 0 00-7 0C6.27.65 5.09 1 5.09 1A5.07 5.07 0 005 4.77a5.44 5.44 0 00-1.5 3.78c0 5.42 3.3 6.61 6.44 7A3.37 3.37 0 009 18.13V22"/>
              </svg>
              <p className="text-[var(--text-secondary)]">
                クラスタリングには最低5票と2件以上の意見が必要です
              </p>
              <p className="text-sm text-[var(--text-tertiary)] mt-1">
                現在: {opinions.length}票 / {statements.length}件の意見
              </p>
            </div>
          )}
        </div>
      ) : (
        /* Voting view */
        <div className="space-y-4">
          {/* PoW Toggle */}
          <div className="flex items-center justify-between bg-[var(--bg-secondary)] rounded-xl p-3">
            <div>
              <span className="text-sm font-medium text-[var(--text-primary)]">Quadratic Voting (PoW)</span>
              <p className="text-xs text-[var(--text-tertiary)]">計算で投票の重みを増加</p>
            </div>
            <button
              onClick={() => setEnablePow(!enablePow)}
              className={`w-12 h-6 rounded-full transition-colors ${enablePow ? 'bg-[var(--line-green)]' : 'bg-[var(--bg-tertiary)]'}`}
            >
              <div className={`w-5 h-5 rounded-full bg-white shadow transition-transform ${enablePow ? 'translate-x-6' : 'translate-x-0.5'}`}/>
            </button>
          </div>

          {unvotedStatements.length > 0 && currentIndex < unvotedStatements.length ? (
            <StatementVotingCard
              statement={unvotedStatements[currentIndex]}
              onVote={handleVote}
              myVote={null}
              authorProfile={profiles?.[unvotedStatements[currentIndex].pubkey]}
              isVoting={isVoting}
              authorWotScore={wotScores[unvotedStatements[currentIndex].pubkey]}
              wotLoading={wotLoading}
              enablePow={enablePow}
            />
          ) : (
            <div className="bg-[var(--bg-secondary)] rounded-2xl p-6 text-center">
              <svg className="w-12 h-12 mx-auto mb-3 text-[var(--line-green)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M22 11.08V12a10 10 0 11-5.93-9.14"/>
                <polyline points="22 4 12 14.01 9 11.01"/>
              </svg>
              <p className="text-[var(--text-primary)] font-medium">全ての意見に投票しました！</p>
              <p className="text-sm text-[var(--text-tertiary)] mt-1">新しい意見を追加してみましょう</p>
            </div>
          )}

          {/* Add statement section */}
          <button
            onClick={() => setShowAddStatement(!showAddStatement)}
            className="w-full py-3 rounded-xl border-2 border-dashed border-[var(--border-color)] text-[var(--text-secondary)] hover:border-[var(--line-green)] hover:text-[var(--line-green)] transition-colors"
          >
            + 新しい意見を追加
          </button>

          {showAddStatement && (
            <AddStatementForm
              topicId={topic.id}
              pubkey={pubkey}
              onCreated={(statement) => {
                setStatements(prev => [...prev, statement])
                setShowAddStatement(false)
              }}
            />
          )}
        </div>
      )}
    </div>
  )
}

/**
 * Topic List Item
 */
function TopicListItem({ topic, onClick, participantCount, statementCount }) {
  const title = topic.tags.find(t => t[0] === 'title')?.[1] || 'トピック'

  return (
    <button
      onClick={onClick}
      className="w-full text-left bg-[var(--bg-primary)] rounded-xl p-4 border border-[var(--border-color)] hover:border-[var(--line-green)] transition-colors"
    >
      <h3 className="font-semibold text-[var(--text-primary)] mb-1">{title}</h3>
      {topic.content && (
        <p className="text-sm text-[var(--text-secondary)] line-clamp-2 mb-2">{topic.content}</p>
      )}
      <div className="flex gap-4 text-xs text-[var(--text-tertiary)]">
        <span>{statementCount}件の意見</span>
        <span>{participantCount}人が参加</span>
        <span>{new Date(topic.created_at * 1000).toLocaleDateString('ja-JP')}</span>
      </div>
    </button>
  )
}

// ============================================================================
// Main PolisApp Component
// ============================================================================

export default function PolisApp({ pubkey }) {
  const [view, setView] = useState('list') // 'list' | 'create' | 'topic'
  const [topics, setTopics] = useState([])
  const [selectedTopic, setSelectedTopic] = useState(null)
  const [loading, setLoading] = useState(true)
  const [profiles, setProfiles] = useState({})
  const [topicStats, setTopicStats] = useState({})
  const [searchQuery, setSearchQuery] = useState('')

  // Fetch topics
  const fetchTopics = useCallback(async () => {
    setLoading(true)
    try {
      const filter = {
        kinds: [KIND_POLIS_TOPIC],
        limit: 50
      }
      const fetchedTopics = await fastFetch(filter, POLIS_RELAYS)
      const sorted = fetchedTopics.sort((a, b) => b.created_at - a.created_at)
      setTopics(sorted)

      // Fetch stats for each topic
      const stats = {}
      for (const topic of sorted.slice(0, 10)) {
        const statementsFilter = { kinds: [KIND_POLIS_STATEMENT], '#e': [topic.id] }
        const opinionsFilter = { kinds: [KIND_POLIS_OPINION], '#e': [topic.id] }

        const [statements, opinions] = await Promise.all([
          fastFetch(statementsFilter, POLIS_RELAYS, 3000),
          fastFetch(opinionsFilter, POLIS_RELAYS, 3000)
        ])

        const participantSet = new Set(opinions.map(o => o.pubkey))
        stats[topic.id] = {
          statements: statements.length,
          participants: participantSet.size
        }
      }
      setTopicStats(stats)

      // Fetch profiles
      const pubkeys = [...new Set(sorted.map(t => t.pubkey))]
      const profileFilter = { kinds: [0], authors: pubkeys.slice(0, 20) }
      const profileEvents = await fastFetch(profileFilter, POLIS_RELAYS, 3000)

      const profileMap = {}
      for (const event of profileEvents) {
        try {
          profileMap[event.pubkey] = JSON.parse(event.content)
        } catch (e) {}
      }
      setProfiles(profileMap)
    } catch (e) {
      console.error('Failed to fetch topics:', e)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchTopics()
  }, [fetchTopics])

  // Filter topics by search
  const filteredTopics = useMemo(() => {
    if (!searchQuery.trim()) return topics
    const q = searchQuery.toLowerCase()
    return topics.filter(t => {
      const title = t.tags.find(tag => tag[0] === 'title')?.[1] || ''
      return title.toLowerCase().includes(q) || t.content?.toLowerCase().includes(q)
    })
  }, [topics, searchQuery])

  if (view === 'create') {
    return (
      <div className="space-y-4">
        <div className="flex items-center gap-3 mb-4">
          <button
            onClick={() => setView('list')}
            className="p-2 rounded-full hover:bg-[var(--bg-tertiary)] transition-colors"
          >
            <svg className="w-5 h-5 text-[var(--text-secondary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M19 12H5M12 19l-7-7 7-7"/>
            </svg>
          </button>
          <h2 className="font-semibold text-[var(--text-primary)]">新しいトピックを作成</h2>
        </div>

        <CreateTopicForm
          pubkey={pubkey}
          onCreated={(topic) => {
            setTopics(prev => [topic, ...prev])
            setView('list')
          }}
          onCancel={() => setView('list')}
        />
      </div>
    )
  }

  if (view === 'topic' && selectedTopic) {
    return (
      <TopicView
        topic={selectedTopic}
        pubkey={pubkey}
        profiles={profiles}
        onBack={() => {
          setSelectedTopic(null)
          setView('list')
          fetchTopics() // Refresh stats
        }}
      />
    )
  }

  // List view
  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold text-[var(--text-primary)]">Polis</h2>
          <p className="text-xs text-[var(--text-tertiary)]">意見収集 & 合意形成</p>
        </div>
        <button
          onClick={() => setView('create')}
          className="px-4 py-2 rounded-xl bg-[var(--line-green)] text-white text-sm font-medium"
        >
          + 新規トピック
        </button>
      </div>

      {/* Search */}
      <div className="relative">
        <svg className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <circle cx="11" cy="11" r="8"/>
          <path d="M21 21l-4.35-4.35"/>
        </svg>
        <input
          type="text"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          placeholder="トピックを検索..."
          className="w-full pl-10 pr-4 py-2 rounded-xl bg-[var(--bg-secondary)] border border-[var(--border-color)] text-[var(--text-primary)] placeholder-[var(--text-tertiary)] text-sm"
        />
      </div>

      {/* Topics list */}
      {loading ? (
        <div className="flex items-center justify-center h-32">
          <div className="animate-spin rounded-full h-8 w-8 border-2 border-[var(--line-green)] border-t-transparent"/>
        </div>
      ) : filteredTopics.length > 0 ? (
        <div className="space-y-3">
          {filteredTopics.map(topic => (
            <TopicListItem
              key={topic.id}
              topic={topic}
              onClick={() => {
                setSelectedTopic(topic)
                setView('topic')
              }}
              statementCount={topicStats[topic.id]?.statements || 0}
              participantCount={topicStats[topic.id]?.participants || 0}
            />
          ))}
        </div>
      ) : (
        <div className="text-center py-12">
          <svg className="w-16 h-16 mx-auto mb-4 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
            <path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z"/>
          </svg>
          <p className="text-[var(--text-secondary)]">
            {searchQuery ? '該当するトピックがありません' : 'トピックがありません'}
          </p>
          <p className="text-sm text-[var(--text-tertiary)] mt-1">
            新しいトピックを作成して議論を始めましょう
          </p>
        </div>
      )}

      {/* Info section */}
      <div className="bg-[var(--bg-secondary)] rounded-xl p-4 mt-6">
        <h3 className="font-semibold text-[var(--text-primary)] text-sm mb-2">Polisとは</h3>
        <p className="text-xs text-[var(--text-secondary)] leading-relaxed">
          Polisは意見の類似性に基づいて参加者をグループ化し、対立点だけでなく「合意点」を発見するツールです。
          台湾のvTaiwanで実際に政策立案に活用されました。
        </p>
        <div className="flex flex-wrap gap-2 mt-3">
          <span className="px-2 py-1 bg-[var(--bg-tertiary)] rounded-full text-xs text-[var(--text-tertiary)]">
            #意見収集
          </span>
          <span className="px-2 py-1 bg-[var(--bg-tertiary)] rounded-full text-xs text-[var(--text-tertiary)]">
            #合意形成
          </span>
          <span className="px-2 py-1 bg-[var(--bg-tertiary)] rounded-full text-xs text-[var(--text-tertiary)]">
            #クラスタリング
          </span>
        </div>
      </div>
    </div>
  )
}
