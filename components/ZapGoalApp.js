'use client'

import { useState, useEffect, useCallback } from 'react'
import { nip19 } from 'nostr-tools'
import {
  parseProfile,
  signEventNip07,
  publishEvent,
  getDefaultRelay,
  shortenPubkey
} from '@/lib/nostr'

// NIP-75 Zap Goal event kind
const KIND_ZAP_GOAL = 9041
const KIND_ZAP_RECEIPT = 9735

// Relays for Zap Goals
const ZAP_GOAL_RELAYS = [
  'wss://yabu.me',
  'wss://relay-jp.nostr.wirednet.jp',
  'wss://relay.nostr.band'
]

// Fast fetch with short timeout
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

// Format date for display
function formatDate(timestamp) {
  const date = new Date(timestamp * 1000)
  return date.toLocaleDateString('ja-JP', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
}

// Format sats amount
function formatSats(msats) {
  const sats = Math.floor(msats / 1000)
  if (sats >= 1000000) {
    return `${(sats / 1000000).toFixed(2)}M`
  } else if (sats >= 1000) {
    return `${(sats / 1000).toFixed(1)}K`
  }
  return sats.toLocaleString()
}

// Create Zap Goal Form Component
function CreateGoalForm({ pubkey, onCreated, onCancel }) {
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [targetAmount, setTargetAmount] = useState('')
  const [closedAt, setClosedAt] = useState('')
  const [imageUrl, setImageUrl] = useState('')
  const [creating, setCreating] = useState(false)
  const [error, setError] = useState(null)

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError(null)

    if (!title.trim()) {
      setError('タイトルを入力してください')
      return
    }

    const amount = parseInt(targetAmount)
    if (!amount || amount <= 0) {
      setError('目標金額を正しく入力してください')
      return
    }

    setCreating(true)

    try {
      const tags = [
        ['amount', String(amount * 1000)], // Convert sats to msats
        ['relays', ...ZAP_GOAL_RELAYS]
      ]

      if (closedAt) {
        const closedAtTimestamp = Math.floor(new Date(closedAt).getTime() / 1000)
        tags.push(['closed_at', String(closedAtTimestamp)])
      }

      if (imageUrl.trim()) {
        tags.push(['image', imageUrl.trim()])
      }

      if (description.trim()) {
        tags.push(['summary', description.trim()])
      }

      const event = {
        kind: KIND_ZAP_GOAL,
        pubkey: pubkey,
        created_at: Math.floor(Date.now() / 1000),
        tags: tags,
        content: title.trim()
      }

      const signedEvent = await signEventNip07(event)
      if (!signedEvent) {
        throw new Error('イベントの署名に失敗しました')
      }

      await publishEvent(signedEvent, ZAP_GOAL_RELAYS)

      onCreated(signedEvent)
    } catch (e) {
      console.error('Failed to create zap goal:', e)
      setError(e.message || 'Zap Goalの作成に失敗しました')
    } finally {
      setCreating(false)
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div>
        <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1">
          タイトル *
        </label>
        <input
          type="text"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="例: Nostrasiaへの旅費"
          className="w-full px-3 py-2 bg-[var(--bg-tertiary)] text-[var(--text-primary)] rounded-lg border border-[var(--border-color)] focus:outline-none focus:border-[var(--line-green)]"
          disabled={creating}
        />
      </div>

      <div>
        <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1">
          目標金額 (sats) *
        </label>
        <input
          type="number"
          value={targetAmount}
          onChange={(e) => setTargetAmount(e.target.value)}
          placeholder="例: 100000"
          min="1"
          className="w-full px-3 py-2 bg-[var(--bg-tertiary)] text-[var(--text-primary)] rounded-lg border border-[var(--border-color)] focus:outline-none focus:border-[var(--line-green)]"
          disabled={creating}
        />
      </div>

      <div>
        <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1">
          説明
        </label>
        <textarea
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="目標の詳細説明..."
          rows={3}
          className="w-full px-3 py-2 bg-[var(--bg-tertiary)] text-[var(--text-primary)] rounded-lg border border-[var(--border-color)] focus:outline-none focus:border-[var(--line-green)] resize-none"
          disabled={creating}
        />
      </div>

      <div>
        <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1">
          期限（オプション）
        </label>
        <input
          type="datetime-local"
          value={closedAt}
          onChange={(e) => setClosedAt(e.target.value)}
          className="w-full px-3 py-2 bg-[var(--bg-tertiary)] text-[var(--text-primary)] rounded-lg border border-[var(--border-color)] focus:outline-none focus:border-[var(--line-green)]"
          disabled={creating}
        />
      </div>

      <div>
        <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1">
          画像URL（オプション）
        </label>
        <input
          type="url"
          value={imageUrl}
          onChange={(e) => setImageUrl(e.target.value)}
          placeholder="https://..."
          className="w-full px-3 py-2 bg-[var(--bg-tertiary)] text-[var(--text-primary)] rounded-lg border border-[var(--border-color)] focus:outline-none focus:border-[var(--line-green)]"
          disabled={creating}
        />
      </div>

      {error && (
        <p className="text-red-500 text-sm">{error}</p>
      )}

      <div className="flex gap-2">
        <button
          type="button"
          onClick={onCancel}
          disabled={creating}
          className="flex-1 py-2 px-4 bg-[var(--bg-tertiary)] text-[var(--text-primary)] rounded-lg hover:opacity-80 transition-opacity disabled:opacity-50"
        >
          キャンセル
        </button>
        <button
          type="submit"
          disabled={creating}
          className="flex-1 py-2 px-4 bg-[var(--line-green)] text-white rounded-lg hover:opacity-80 transition-opacity disabled:opacity-50"
        >
          {creating ? '作成中...' : '作成'}
        </button>
      </div>
    </form>
  )
}

// Goal Card Component
function GoalCard({ goal, zapReceipts, profiles, onSelect }) {
  const amountTag = goal.tags.find(t => t[0] === 'amount')
  const targetMsats = amountTag ? parseInt(amountTag[1]) : 0
  const targetSats = targetMsats / 1000

  const closedAtTag = goal.tags.find(t => t[0] === 'closed_at')
  const closedAt = closedAtTag ? parseInt(closedAtTag[1]) : null
  const isExpired = closedAt && closedAt < Math.floor(Date.now() / 1000)

  const imageTag = goal.tags.find(t => t[0] === 'image')
  const imageUrl = imageTag ? imageTag[1] : null

  const summaryTag = goal.tags.find(t => t[0] === 'summary')
  const summary = summaryTag ? summaryTag[1] : null

  // Calculate received amount from zap receipts
  const receivedMsats = zapReceipts
    .filter(zap => {
      const goalTag = zap.tags.find(t => t[0] === 'e' && t[1] === goal.id)
      return goalTag != null
    })
    .reduce((sum, zap) => {
      const descTag = zap.tags.find(t => t[0] === 'description')
      if (descTag) {
        try {
          const desc = JSON.parse(descTag[1])
          const amountTag = desc.tags?.find(t => t[0] === 'amount')
          if (amountTag) {
            return sum + parseInt(amountTag[1])
          }
        } catch {}
      }
      return sum
    }, 0)

  const receivedSats = receivedMsats / 1000
  const progress = targetSats > 0 ? Math.min((receivedSats / targetSats) * 100, 100) : 0

  const profile = profiles[goal.pubkey]
  const displayName = profile?.name || profile?.display_name || shortenPubkey(goal.pubkey)

  return (
    <div
      onClick={() => onSelect(goal)}
      className="bg-[var(--bg-tertiary)] rounded-xl p-4 cursor-pointer hover:opacity-90 transition-opacity"
    >
      {imageUrl && (
        <div className="mb-3 rounded-lg overflow-hidden">
          <img
            src={imageUrl}
            alt={goal.content}
            className="w-full h-32 object-cover"
            onError={(e) => { e.target.style.display = 'none' }}
          />
        </div>
      )}

      <div className="flex items-start justify-between mb-2">
        <h3 className="font-semibold text-[var(--text-primary)] line-clamp-2">
          {goal.content}
        </h3>
        {isExpired && (
          <span className="text-xs bg-red-500/20 text-red-400 px-2 py-0.5 rounded-full ml-2 shrink-0">
            終了
          </span>
        )}
      </div>

      {summary && (
        <p className="text-sm text-[var(--text-secondary)] mb-3 line-clamp-2">
          {summary}
        </p>
      )}

      <div className="mb-3">
        <div className="flex justify-between text-sm mb-1">
          <span className="text-[var(--text-secondary)]">
            {formatSats(receivedMsats)} / {formatSats(targetMsats)} sats
          </span>
          <span className="text-[var(--line-green)]">
            {progress.toFixed(1)}%
          </span>
        </div>
        <div className="h-2 bg-[var(--bg-secondary)] rounded-full overflow-hidden">
          <div
            className="h-full bg-[var(--line-green)] rounded-full transition-all duration-300"
            style={{ width: `${progress}%` }}
          />
        </div>
      </div>

      <div className="flex items-center justify-between text-xs text-[var(--text-tertiary)]">
        <span className="flex items-center gap-1">
          {profile?.picture && (
            <img
              src={profile.picture}
              alt=""
              className="w-4 h-4 rounded-full"
              onError={(e) => { e.target.style.display = 'none' }}
            />
          )}
          {displayName}
        </span>
        <span>{formatDate(goal.created_at)}</span>
      </div>

      {closedAt && !isExpired && (
        <div className="mt-2 text-xs text-[var(--text-tertiary)]">
          期限: {formatDate(closedAt)}
        </div>
      )}
    </div>
  )
}

// Goal Detail Modal Component
function GoalDetailModal({ goal, zapReceipts, profiles, onClose }) {
  const amountTag = goal.tags.find(t => t[0] === 'amount')
  const targetMsats = amountTag ? parseInt(amountTag[1]) : 0

  const closedAtTag = goal.tags.find(t => t[0] === 'closed_at')
  const closedAt = closedAtTag ? parseInt(closedAtTag[1]) : null
  const isExpired = closedAt && closedAt < Math.floor(Date.now() / 1000)

  const imageTag = goal.tags.find(t => t[0] === 'image')
  const imageUrl = imageTag ? imageTag[1] : null

  const summaryTag = goal.tags.find(t => t[0] === 'summary')
  const summary = summaryTag ? summaryTag[1] : null

  const relaysTag = goal.tags.find(t => t[0] === 'relays')
  const relays = relaysTag ? relaysTag.slice(1) : ZAP_GOAL_RELAYS

  // Filter zap receipts for this goal
  const goalZaps = zapReceipts.filter(zap => {
    const goalTag = zap.tags.find(t => t[0] === 'e' && t[1] === goal.id)
    return goalTag != null
  })

  const receivedMsats = goalZaps.reduce((sum, zap) => {
    const descTag = zap.tags.find(t => t[0] === 'description')
    if (descTag) {
      try {
        const desc = JSON.parse(descTag[1])
        const amountTag = desc.tags?.find(t => t[0] === 'amount')
        if (amountTag) {
          return sum + parseInt(amountTag[1])
        }
      } catch {}
    }
    return sum
  }, 0)

  const progress = targetMsats > 0 ? Math.min((receivedMsats / targetMsats) * 100, 100) : 0

  const profile = profiles[goal.pubkey]
  const displayName = profile?.name || profile?.display_name || shortenPubkey(goal.pubkey)

  // Generate nevent for sharing
  const nevent = nip19.neventEncode({
    id: goal.id,
    relays: relays.slice(0, 2),
    author: goal.pubkey
  })

  const handleCopyNevent = async () => {
    try {
      await navigator.clipboard.writeText(nevent)
      alert('コピーしました')
    } catch {
      const textarea = document.createElement('textarea')
      textarea.value = nevent
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
      alert('コピーしました')
    }
  }

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4" onClick={onClose}>
      <div
        className="bg-[var(--bg-secondary)] rounded-2xl max-w-lg w-full max-h-[90vh] overflow-y-auto"
        onClick={e => e.stopPropagation()}
      >
        {imageUrl && (
          <div className="rounded-t-2xl overflow-hidden">
            <img
              src={imageUrl}
              alt={goal.content}
              className="w-full h-48 object-cover"
              onError={(e) => { e.target.style.display = 'none' }}
            />
          </div>
        )}

        <div className="p-4 space-y-4">
          <div className="flex items-start justify-between">
            <h2 className="text-xl font-bold text-[var(--text-primary)]">
              {goal.content}
            </h2>
            {isExpired && (
              <span className="text-sm bg-red-500/20 text-red-400 px-3 py-1 rounded-full ml-2 shrink-0">
                終了
              </span>
            )}
          </div>

          {summary && (
            <p className="text-[var(--text-secondary)]">
              {summary}
            </p>
          )}

          <div>
            <div className="flex justify-between text-sm mb-2">
              <span className="text-[var(--text-secondary)]">
                {formatSats(receivedMsats)} / {formatSats(targetMsats)} sats
              </span>
              <span className="text-[var(--line-green)] font-semibold">
                {progress.toFixed(1)}%
              </span>
            </div>
            <div className="h-3 bg-[var(--bg-tertiary)] rounded-full overflow-hidden">
              <div
                className="h-full bg-[var(--line-green)] rounded-full transition-all duration-300"
                style={{ width: `${progress}%` }}
              />
            </div>
          </div>

          <div className="flex items-center gap-2 text-sm text-[var(--text-secondary)]">
            {profile?.picture && (
              <img
                src={profile.picture}
                alt=""
                className="w-6 h-6 rounded-full"
                onError={(e) => { e.target.style.display = 'none' }}
              />
            )}
            <span>作成者: {displayName}</span>
          </div>

          <div className="text-sm text-[var(--text-tertiary)] space-y-1">
            <div>作成日時: {formatDate(goal.created_at)}</div>
            {closedAt && (
              <div>期限: {formatDate(closedAt)}</div>
            )}
          </div>

          {goalZaps.length > 0 && (
            <div>
              <h3 className="font-semibold text-[var(--text-primary)] mb-2">
                Zap履歴 ({goalZaps.length}件)
              </h3>
              <div className="space-y-2 max-h-40 overflow-y-auto">
                {goalZaps.map(zap => {
                  const descTag = zap.tags.find(t => t[0] === 'description')
                  let zapAmount = 0
                  let zapperPubkey = null
                  if (descTag) {
                    try {
                      const desc = JSON.parse(descTag[1])
                      const amountTag = desc.tags?.find(t => t[0] === 'amount')
                      if (amountTag) {
                        zapAmount = parseInt(amountTag[1])
                      }
                      zapperPubkey = desc.pubkey
                    } catch {}
                  }
                  const zapperProfile = zapperPubkey ? profiles[zapperPubkey] : null
                  const zapperName = zapperProfile?.name || zapperProfile?.display_name ||
                    (zapperPubkey ? shortenPubkey(zapperPubkey) : '匿名')

                  return (
                    <div key={zap.id} className="flex items-center justify-between text-sm bg-[var(--bg-tertiary)] p-2 rounded-lg">
                      <span className="text-[var(--text-secondary)]">{zapperName}</span>
                      <span className="text-[var(--line-green)]">{formatSats(zapAmount)} sats</span>
                    </div>
                  )
                })}
              </div>
            </div>
          )}

          <div className="space-y-2">
            <button
              onClick={handleCopyNevent}
              className="w-full py-2 px-4 bg-[var(--bg-tertiary)] text-[var(--text-primary)] rounded-lg hover:opacity-80 transition-opacity text-sm"
            >
              イベントIDをコピー (nevent)
            </button>
            <button
              onClick={onClose}
              className="w-full py-2 px-4 bg-[var(--line-green)] text-white rounded-lg hover:opacity-80 transition-opacity"
            >
              閉じる
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

// Main ZapGoalApp Component
export default function ZapGoalApp({ pubkey }) {
  const [goals, setGoals] = useState([])
  const [zapReceipts, setZapReceipts] = useState([])
  const [profiles, setProfiles] = useState({})
  const [loading, setLoading] = useState(false)
  const [showCreateForm, setShowCreateForm] = useState(false)
  const [selectedGoal, setSelectedGoal] = useState(null)
  const [viewMode, setViewMode] = useState('all') // 'all', 'mine', 'active'

  const loadGoals = useCallback(async () => {
    setLoading(true)
    try {
      // Fetch all zap goals
      const goalFilter = {
        kinds: [KIND_ZAP_GOAL],
        limit: 100
      }

      const fetchedGoals = await fastFetch(goalFilter, ZAP_GOAL_RELAYS)

      // Sort by created_at descending
      fetchedGoals.sort((a, b) => b.created_at - a.created_at)
      setGoals(fetchedGoals)

      // Collect pubkeys to fetch profiles
      const pubkeys = [...new Set(fetchedGoals.map(g => g.pubkey))]

      // Fetch profiles
      if (pubkeys.length > 0) {
        const profileFilter = {
          kinds: [0],
          authors: pubkeys
        }
        const profileEvents = await fastFetch(profileFilter, ZAP_GOAL_RELAYS)

        const profileMap = {}
        profileEvents.forEach(event => {
          if (!profileMap[event.pubkey] || event.created_at > profileMap[event.pubkey].created_at) {
            try {
              profileMap[event.pubkey] = {
                ...JSON.parse(event.content),
                created_at: event.created_at
              }
            } catch {}
          }
        })
        setProfiles(profileMap)
      }

      // Fetch zap receipts for these goals
      const goalIds = fetchedGoals.map(g => g.id)
      if (goalIds.length > 0) {
        const zapFilter = {
          kinds: [KIND_ZAP_RECEIPT],
          '#e': goalIds,
          limit: 500
        }
        const zaps = await fastFetch(zapFilter, ZAP_GOAL_RELAYS)
        setZapReceipts(zaps)

        // Fetch profiles of zappers
        const zapperPubkeys = new Set()
        zaps.forEach(zap => {
          const descTag = zap.tags.find(t => t[0] === 'description')
          if (descTag) {
            try {
              const desc = JSON.parse(descTag[1])
              if (desc.pubkey) {
                zapperPubkeys.add(desc.pubkey)
              }
            } catch {}
          }
        })

        const newPubkeys = [...zapperPubkeys].filter(pk => !profiles[pk])
        if (newPubkeys.length > 0) {
          const zapperProfileFilter = {
            kinds: [0],
            authors: newPubkeys
          }
          const zapperProfileEvents = await fastFetch(zapperProfileFilter, ZAP_GOAL_RELAYS)

          setProfiles(prev => {
            const updated = { ...prev }
            zapperProfileEvents.forEach(event => {
              if (!updated[event.pubkey] || event.created_at > updated[event.pubkey].created_at) {
                try {
                  updated[event.pubkey] = {
                    ...JSON.parse(event.content),
                    created_at: event.created_at
                  }
                } catch {}
              }
            })
            return updated
          })
        }
      }
    } catch (e) {
      console.error('Failed to load zap goals:', e)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadGoals()
  }, [loadGoals])

  const handleGoalCreated = (newGoal) => {
    setGoals(prev => [newGoal, ...prev])
    setShowCreateForm(false)
  }

  const filteredGoals = goals.filter(goal => {
    const closedAtTag = goal.tags.find(t => t[0] === 'closed_at')
    const closedAt = closedAtTag ? parseInt(closedAtTag[1]) : null
    const isExpired = closedAt && closedAt < Math.floor(Date.now() / 1000)

    switch (viewMode) {
      case 'mine':
        return goal.pubkey === pubkey
      case 'active':
        return !isExpired
      default:
        return true
    }
  })

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex gap-2">
          <button
            onClick={() => setViewMode('all')}
            className={`px-3 py-1 text-sm rounded-full transition-colors ${
              viewMode === 'all'
                ? 'bg-[var(--line-green)] text-white'
                : 'bg-[var(--bg-tertiary)] text-[var(--text-secondary)]'
            }`}
          >
            すべて
          </button>
          <button
            onClick={() => setViewMode('active')}
            className={`px-3 py-1 text-sm rounded-full transition-colors ${
              viewMode === 'active'
                ? 'bg-[var(--line-green)] text-white'
                : 'bg-[var(--bg-tertiary)] text-[var(--text-secondary)]'
            }`}
          >
            進行中
          </button>
          <button
            onClick={() => setViewMode('mine')}
            className={`px-3 py-1 text-sm rounded-full transition-colors ${
              viewMode === 'mine'
                ? 'bg-[var(--line-green)] text-white'
                : 'bg-[var(--bg-tertiary)] text-[var(--text-secondary)]'
            }`}
          >
            自分の目標
          </button>
        </div>
        <div className="flex gap-2">
          <button
            onClick={loadGoals}
            disabled={loading}
            className="p-2 bg-[var(--bg-tertiary)] text-[var(--text-secondary)] rounded-lg hover:opacity-80 transition-opacity disabled:opacity-50"
            title="更新"
          >
            <svg className={`w-5 h-5 ${loading ? 'animate-spin' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M23 4v6h-6M1 20v-6h6"/>
              <path d="M3.51 9a9 9 0 0114.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0020.49 15"/>
            </svg>
          </button>
          <button
            onClick={() => setShowCreateForm(true)}
            className="px-4 py-2 bg-[var(--line-green)] text-white rounded-lg hover:opacity-80 transition-opacity"
          >
            + 新規作成
          </button>
        </div>
      </div>

      {/* Create Form */}
      {showCreateForm && (
        <div className="bg-[var(--bg-tertiary)] rounded-xl p-4">
          <h3 className="font-semibold text-[var(--text-primary)] mb-4">新しいZap Goalを作成</h3>
          <CreateGoalForm
            pubkey={pubkey}
            onCreated={handleGoalCreated}
            onCancel={() => setShowCreateForm(false)}
          />
        </div>
      )}

      {/* Loading */}
      {loading && goals.length === 0 && (
        <div className="text-center py-8 text-[var(--text-secondary)]">
          読み込み中...
        </div>
      )}

      {/* Goals Grid */}
      {!loading && filteredGoals.length === 0 && (
        <div className="text-center py-8 text-[var(--text-secondary)]">
          {viewMode === 'mine' ? '自分のZap Goalはまだありません' : 'Zap Goalが見つかりません'}
        </div>
      )}

      <div className="grid gap-4 sm:grid-cols-2">
        {filteredGoals.map(goal => (
          <GoalCard
            key={goal.id}
            goal={goal}
            zapReceipts={zapReceipts}
            profiles={profiles}
            onSelect={setSelectedGoal}
          />
        ))}
      </div>

      {/* Goal Detail Modal */}
      {selectedGoal && (
        <GoalDetailModal
          goal={selectedGoal}
          zapReceipts={zapReceipts}
          profiles={profiles}
          onClose={() => setSelectedGoal(null)}
        />
      )}
    </div>
  )
}
