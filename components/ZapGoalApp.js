'use client'

import { useState, useEffect, useCallback, useRef } from 'react'
import { nip19 } from 'nostr-tools'
import {
  parseProfile,
  signEventNip07,
  publishEvent,
  deleteEvent,
  getDefaultRelay,
  shortenPubkey,
  fetchLightningInvoice,
  uploadImage,
  FALLBACK_RELAYS
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

// Parse amount from tag - stored in sats
function parseAmount(amountStr) {
  const amount = parseInt(amountStr)
  if (isNaN(amount)) return 0
  return amount // sats
}

// Format sats amount (input is sats)
function formatSats(sats) {
  if (sats >= 1000000) {
    return `${(sats / 1000000).toFixed(2)}M`
  } else if (sats >= 1000) {
    return `${(sats / 1000).toFixed(1)}K`
  }
  return sats.toLocaleString()
}

// Copy to clipboard helper
async function copyToClipboard(text) {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    const textarea = document.createElement('textarea')
    textarea.value = text
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
    return true
  }
}

// Create Zap Goal Form Component
function CreateGoalForm({ pubkey, onCreated, onCancel }) {
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [targetAmount, setTargetAmount] = useState('')
  const [closedAtDate, setClosedAtDate] = useState('')
  const [closedAtTime, setClosedAtTime] = useState('23:59')
  const [imageUrl, setImageUrl] = useState('')
  const [creating, setCreating] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState(null)
  const fileInputRef = useRef(null)

  const handleImageUpload = async (e) => {
    const file = e.target.files?.[0]
    if (!file) return

    setUploading(true)
    setError(null)

    try {
      const url = await uploadImage(file)
      setImageUrl(url)
    } catch (e) {
      console.error('Failed to upload image:', e)
      setError('画像のアップロードに失敗しました')
    } finally {
      setUploading(false)
    }
  }

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
        ['amount', String(amount)], // Store in sats
        ['relays', ...ZAP_GOAL_RELAYS]
      ]

      if (closedAtDate) {
        const dateTimeStr = closedAtTime ? `${closedAtDate}T${closedAtTime}` : `${closedAtDate}T23:59`
        const closedAtTimestamp = Math.floor(new Date(dateTimeStr).getTime() / 1000)
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
        <div className="flex gap-2">
          <input
            type="date"
            value={closedAtDate}
            onChange={(e) => setClosedAtDate(e.target.value)}
            className="flex-1 px-3 py-2 bg-[var(--bg-tertiary)] text-[var(--text-primary)] rounded-lg border border-[var(--border-color)] focus:outline-none focus:border-[var(--line-green)]"
            disabled={creating}
          />
          <input
            type="time"
            value={closedAtTime}
            onChange={(e) => setClosedAtTime(e.target.value)}
            className="w-24 px-2 py-2 bg-[var(--bg-tertiary)] text-[var(--text-primary)] rounded-lg border border-[var(--border-color)] focus:outline-none focus:border-[var(--line-green)]"
            disabled={creating}
          />
        </div>
      </div>

      <div>
        <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1">
          画像（オプション）
        </label>
        <div className="space-y-2">
          <div className="flex gap-2">
            <input
              type="url"
              value={imageUrl}
              onChange={(e) => setImageUrl(e.target.value)}
              placeholder="https://..."
              className="flex-1 min-w-0 px-3 py-2 bg-[var(--bg-tertiary)] text-[var(--text-primary)] rounded-lg border border-[var(--border-color)] focus:outline-none focus:border-[var(--line-green)] text-sm"
              disabled={creating || uploading}
            />
            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              disabled={creating || uploading}
              className="px-3 py-2 bg-[var(--bg-tertiary)] text-[var(--text-secondary)] rounded-lg border border-[var(--border-color)] hover:bg-[var(--line-green)] hover:text-white transition-colors shrink-0"
            >
              {uploading ? '...' : (
                <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <rect x="3" y="3" width="18" height="18" rx="2" ry="2"/>
                  <circle cx="8.5" cy="8.5" r="1.5"/>
                  <polyline points="21,15 16,10 5,21"/>
                </svg>
              )}
            </button>
            <input
              ref={fileInputRef}
              type="file"
              accept="image/*"
              onChange={handleImageUpload}
              className="hidden"
            />
          </div>
          {imageUrl && (
            <img
              src={imageUrl}
              alt="プレビュー"
              className="w-full h-32 object-cover rounded-lg"
              onError={(e) => { e.target.style.display = 'none' }}
            />
          )}
        </div>
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
          disabled={creating || uploading}
          className="flex-1 py-2 px-4 bg-[var(--line-green)] text-white rounded-lg hover:opacity-80 transition-opacity disabled:opacity-50"
        >
          {creating ? '作成中...' : '作成'}
        </button>
      </div>
    </form>
  )
}

// Goal Card Component
function GoalCard({ goal, zapReceipts, profiles, onSelect, onDelete, isOwn }) {
  const amountTag = goal.tags.find(t => t[0] === 'amount')
  const targetSats = amountTag ? parseAmount(amountTag[1]) : 0

  const closedAtTag = goal.tags.find(t => t[0] === 'closed_at')
  const closedAt = closedAtTag ? parseInt(closedAtTag[1]) : null
  const isExpired = closedAt && closedAt < Math.floor(Date.now() / 1000)

  const imageTag = goal.tags.find(t => t[0] === 'image')
  const imageUrl = imageTag ? imageTag[1] : null

  const summaryTag = goal.tags.find(t => t[0] === 'summary')
  const summary = summaryTag ? summaryTag[1] : null

  // Calculate received amount from zap receipts (msats -> sats)
  const receivedSats = Math.floor(zapReceipts
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
    }, 0) / 1000)

  const progress = targetSats > 0 ? Math.min((receivedSats / targetSats) * 100, 100) : 0

  const profile = profiles[goal.pubkey]
  const displayName = profile?.name || profile?.display_name || shortenPubkey(goal.pubkey)

  const handleDelete = async (e) => {
    e.stopPropagation()
    if (!confirm('このZap Goalを削除しますか？')) return
    onDelete(goal.id)
  }

  return (
    <div
      onClick={() => onSelect(goal)}
      className="bg-[var(--bg-tertiary)] rounded-xl p-4 cursor-pointer hover:opacity-90 transition-opacity relative"
    >
      {isOwn && (
        <button
          onClick={handleDelete}
          className="absolute top-2 right-2 p-1.5 bg-red-500/20 text-red-400 rounded-full hover:bg-red-500/40 transition-colors"
          title="削除"
        >
          <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M3 6h18M19 6v14a2 2 0 01-2 2H7a2 2 0 01-2-2V6M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2"/>
          </svg>
        </button>
      )}

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

      <div className="flex items-start justify-between mb-2 pr-8">
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
            {formatSats(receivedSats)} / {formatSats(targetSats)} sats
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

// Goal Detail Modal Component with Zap support
function GoalDetailModal({ goal, zapReceipts, profiles, onClose, onShareToTimeline, onDelete, isOwn, myPubkey }) {
  const [zapAmount, setZapAmount] = useState('100')
  const [zapping, setZapping] = useState(false)
  const [copied, setCopied] = useState(false)

  const amountTag = goal.tags.find(t => t[0] === 'amount')
  const targetSats = amountTag ? parseAmount(amountTag[1]) : 0

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

  // Calculate received amount (msats -> sats)
  const receivedSats = Math.floor(goalZaps.reduce((sum, zap) => {
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
  }, 0) / 1000)

  const progress = targetSats > 0 ? Math.min((receivedSats / targetSats) * 100, 100) : 0

  const profile = profiles[goal.pubkey]
  const displayName = profile?.name || profile?.display_name || shortenPubkey(goal.pubkey)

  // Generate nevent for sharing
  const nevent = nip19.neventEncode({
    id: goal.id,
    relays: relays.slice(0, 2),
    author: goal.pubkey
  })

  const handleCopyNevent = async () => {
    const success = await copyToClipboard(nevent)
    if (success) {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    }
  }

  const handleShareToTimeline = () => {
    onShareToTimeline(`nostr:${nevent}`)
    onClose()
  }

  const handleZap = async () => {
    const amount = parseInt(zapAmount)
    if (!amount || amount < 1) {
      alert('有効な金額を入力してください')
      return
    }

    const lud16 = profile?.lud16 || profile?.lud06
    if (!lud16) {
      alert('このGoalの作成者はLightningアドレスを設定していません')
      return
    }

    try {
      setZapping(true)
      const result = await fetchLightningInvoice(lud16, amount, `Zap Goal支援: ${goal.content}`)

      if (result.invoice) {
        const copied = await copyToClipboard(result.invoice)
        if (copied) {
          alert(`⚡ ${amount} sats のインボイスをコピーしました！\n\nお好きなLightningウォレットで支払いできます。`)
        } else {
          prompt('インボイスをコピーしてください:', result.invoice)
        }
      }
    } catch (e) {
      console.error('Failed to create invoice:', e)
      alert(`インボイスの作成に失敗しました: ${e.message}`)
    } finally {
      setZapping(false)
    }
  }

  const handleDelete = async () => {
    if (!confirm('このZap Goalを削除しますか？')) return
    onDelete(goal.id)
    onClose()
  }

  const hasLightningAddress = profile?.lud16 || profile?.lud06

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4" onClick={onClose}>
      <div
        className="bg-[var(--bg-secondary)] rounded-2xl max-w-lg w-full max-h-[85vh] overflow-y-auto"
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

        <div className="p-4 space-y-4 pb-6">
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
            <p className="text-[var(--text-secondary)] whitespace-pre-wrap break-words">
              {summary}
            </p>
          )}

          <div>
            <div className="flex justify-between text-sm mb-2">
              <span className="text-[var(--text-secondary)]">
                {formatSats(receivedSats)} / {formatSats(targetSats)} sats
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

          {/* Zap Support Section */}
          {!isExpired && (
            <div className="bg-[var(--bg-tertiary)] rounded-xl p-3 space-y-3">
              <h3 className="font-semibold text-[var(--text-primary)] flex items-center gap-2">
                <svg className="w-5 h-5 text-yellow-500" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z"/>
                </svg>
                Zap支援
              </h3>
              {hasLightningAddress ? (
                <>
                  <div className="flex gap-2">
                    <input
                      type="number"
                      value={zapAmount}
                      onChange={(e) => setZapAmount(e.target.value)}
                      placeholder="金額 (sats)"
                      min="1"
                      className="flex-1 px-3 py-2 bg-[var(--bg-secondary)] text-[var(--text-primary)] rounded-lg border border-[var(--border-color)] focus:outline-none focus:border-[var(--line-green)]"
                      disabled={zapping}
                    />
                    <button
                      onClick={handleZap}
                      disabled={zapping}
                      className="px-4 py-2 bg-yellow-500 text-white rounded-lg hover:opacity-80 transition-opacity disabled:opacity-50 font-semibold"
                    >
                      {zapping ? '...' : '⚡ Zap'}
                    </button>
                  </div>
                  <div className="flex gap-2 flex-wrap">
                    {[21, 100, 500, 1000].map(amount => (
                      <button
                        key={amount}
                        onClick={() => setZapAmount(String(amount))}
                        className="px-3 py-1 text-sm bg-[var(--bg-secondary)] text-[var(--text-secondary)] rounded-full hover:bg-[var(--line-green)] hover:text-white transition-colors"
                      >
                        {amount} sats
                      </button>
                    ))}
                  </div>
                </>
              ) : (
                <p className="text-sm text-[var(--text-tertiary)]">
                  この目標の作成者はLightningアドレスを設定していないため、Zapを送れません。
                </p>
              )}
            </div>
          )}

          {goalZaps.length > 0 && (
            <div>
              <h3 className="font-semibold text-[var(--text-primary)] mb-2">
                Zap履歴 ({goalZaps.length}件)
              </h3>
              <div className="space-y-2 max-h-40 overflow-y-auto">
                {goalZaps.map(zap => {
                  const descTag = zap.tags.find(t => t[0] === 'description')
                  let zapAmtSats = 0
                  let zapperPubkey = null
                  if (descTag) {
                    try {
                      const desc = JSON.parse(descTag[1])
                      const amountTag = desc.tags?.find(t => t[0] === 'amount')
                      if (amountTag) {
                        zapAmtSats = Math.floor(parseInt(amountTag[1]) / 1000) // msats -> sats
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
                      <span className="text-[var(--line-green)]">{formatSats(zapAmtSats)} sats</span>
                    </div>
                  )
                })}
              </div>
            </div>
          )}

          <div className="space-y-2">
            <button
              onClick={handleShareToTimeline}
              className="w-full py-2 px-4 bg-[var(--line-green)] text-white rounded-lg hover:opacity-80 transition-opacity"
            >
              タイムラインに共有
            </button>
            <button
              onClick={handleCopyNevent}
              className="w-full py-2 px-4 bg-[var(--bg-tertiary)] text-[var(--text-primary)] rounded-lg hover:opacity-80 transition-opacity text-sm"
            >
              {copied ? 'コピーしました!' : 'イベントIDをコピー (nevent)'}
            </button>
            {isOwn && (
              <button
                onClick={handleDelete}
                className="w-full py-2 px-4 bg-red-500/20 text-red-400 rounded-lg hover:bg-red-500/30 transition-opacity text-sm"
              >
                削除
              </button>
            )}
            <button
              onClick={onClose}
              className="w-full py-2 px-4 bg-[var(--bg-tertiary)] text-[var(--text-secondary)] rounded-lg hover:opacity-80 transition-opacity"
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
export default function ZapGoalApp({ pubkey, onShareToTimeline }) {
  const [goals, setGoals] = useState([])
  const [zapReceipts, setZapReceipts] = useState([])
  const [profiles, setProfiles] = useState({})
  const [loading, setLoading] = useState(false)
  const [showCreateForm, setShowCreateForm] = useState(false)
  const [selectedGoal, setSelectedGoal] = useState(null)
  const [searchQuery, setSearchQuery] = useState('')
  const [searching, setSearching] = useState(false)

  // Load only user's own goals
  const loadGoals = useCallback(async () => {
    if (!pubkey) return
    setLoading(true)
    try {
      // Fetch only user's zap goals
      const goalFilter = {
        kinds: [KIND_ZAP_GOAL],
        authors: [pubkey],
        limit: 50
      }

      const fetchedGoals = await fastFetch(goalFilter, ZAP_GOAL_RELAYS)

      // Sort by created_at descending
      fetchedGoals.sort((a, b) => b.created_at - a.created_at)
      setGoals(fetchedGoals)

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

        if (zapperPubkeys.size > 0) {
          const zapperProfileFilter = {
            kinds: [0],
            authors: [...zapperPubkeys]
          }
          const zapperProfileEvents = await fastFetch(zapperProfileFilter, ZAP_GOAL_RELAYS)

          const profileMap = {}
          zapperProfileEvents.forEach(event => {
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
      }
    } catch (e) {
      console.error('Failed to load zap goals:', e)
    } finally {
      setLoading(false)
    }
  }, [pubkey])

  useEffect(() => {
    loadGoals()
  }, [loadGoals])

  const handleGoalCreated = (newGoal) => {
    setGoals(prev => [newGoal, ...prev])
    setShowCreateForm(false)
  }

  const handleDeleteGoal = async (goalId) => {
    try {
      const result = await deleteEvent(goalId)
      if (result.success) {
        setGoals(prev => prev.filter(g => g.id !== goalId))
      } else {
        alert('削除に失敗しました')
      }
    } catch (e) {
      console.error('Failed to delete goal:', e)
      alert('削除に失敗しました: ' + e.message)
    }
  }

  // Search for a goal by nevent/note/hex ID
  const handleSearch = async () => {
    if (!searchQuery.trim()) return

    setSearching(true)

    try {
      let eventId = null
      let relayHints = []

      const query = searchQuery.trim()

      // Try to decode nevent
      if (query.startsWith('nevent1')) {
        try {
          const decoded = nip19.decode(query)
          if (decoded.type === 'nevent') {
            eventId = decoded.data.id
            relayHints = decoded.data.relays || []
          }
        } catch {}
      }
      // Try to decode note
      else if (query.startsWith('note1')) {
        try {
          const decoded = nip19.decode(query)
          if (decoded.type === 'note') {
            eventId = decoded.data
          }
        } catch {}
      }
      // Assume hex ID
      else if (/^[a-f0-9]{64}$/i.test(query)) {
        eventId = query.toLowerCase()
      }

      if (!eventId) {
        alert('有効なイベントIDを入力してください（nevent, note, またはhex形式）')
        return
      }

      // Fetch the goal event
      const searchRelays = relayHints.length > 0
        ? [...new Set([...relayHints, ...ZAP_GOAL_RELAYS])]
        : ZAP_GOAL_RELAYS

      const goalFilter = {
        ids: [eventId]
      }

      const foundGoals = await fastFetch(goalFilter, searchRelays)

      if (foundGoals.length === 0) {
        alert('イベントが見つかりませんでした')
        return
      }

      const foundGoal = foundGoals[0]

      // Check if it's a Zap Goal
      if (foundGoal.kind !== KIND_ZAP_GOAL) {
        alert('このイベントはZap Goalではありません')
        return
      }

      // Fetch zap receipts for this goal
      const zapFilter = {
        kinds: [KIND_ZAP_RECEIPT],
        '#e': [foundGoal.id],
        limit: 100
      }
      const zaps = await fastFetch(zapFilter, searchRelays)

      // Merge zap receipts
      setZapReceipts(prev => {
        const existing = new Set(prev.map(z => z.id))
        const newZaps = zaps.filter(z => !existing.has(z.id))
        return [...prev, ...newZaps]
      })

      // Fetch profile of goal creator with full data from multiple relays
      const profileFilter = {
        kinds: [0],
        authors: [foundGoal.pubkey]
      }
      // Use more relays for profile fetching to ensure we get lud16/lud06
      const profileRelays = [...new Set([...searchRelays, ...FALLBACK_RELAYS])]
      const profileEvents = await fastFetch(profileFilter, profileRelays)

      if (profileEvents.length > 0) {
        const latestProfile = profileEvents.reduce((latest, event) =>
          !latest || event.created_at > latest.created_at ? event : latest
        , null)

        if (latestProfile) {
          try {
            const profileData = JSON.parse(latestProfile.content)
            setProfiles(prev => ({
              ...prev,
              [foundGoal.pubkey]: {
                ...profileData,
                created_at: latestProfile.created_at
              }
            }))
          } catch {}
        }
      }

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

      if (zapperPubkeys.size > 0) {
        const zapperProfileFilter = {
          kinds: [0],
          authors: [...zapperPubkeys]
        }
        const zapperProfileEvents = await fastFetch(zapperProfileFilter, searchRelays)

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

      setSelectedGoal(foundGoal)
    } catch (e) {
      console.error('Search failed:', e)
      alert('検索に失敗しました')
    } finally {
      setSearching(false)
    }
  }

  const handleShareToTimelineLocal = (nevent) => {
    if (onShareToTimeline) {
      onShareToTimeline(nevent)
    }
  }

  return (
    <div className="space-y-4">
      {/* Search Section */}
      <div className="bg-[var(--bg-tertiary)] rounded-xl p-3">
        <div className="flex gap-2">
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="nevent/note/hexで検索..."
            className="flex-1 min-w-0 px-3 py-2 bg-[var(--bg-secondary)] text-[var(--text-primary)] rounded-lg border border-[var(--border-color)] focus:outline-none focus:border-[var(--line-green)] text-sm"
            onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
          />
          <button
            onClick={handleSearch}
            disabled={searching || !searchQuery.trim()}
            className="px-3 py-2 bg-[var(--line-green)] text-white rounded-lg hover:opacity-80 transition-opacity disabled:opacity-50 text-sm shrink-0"
          >
            {searching ? '...' : '検索'}
          </button>
        </div>
      </div>

      {/* Header */}
      <div className="flex items-center justify-between">
        <h3 className="font-semibold text-[var(--text-primary)]">自分のZap Goal</h3>
        <button
          onClick={() => setShowCreateForm(true)}
          className="px-4 py-2 bg-[var(--line-green)] text-white rounded-lg hover:opacity-80 transition-opacity text-sm"
        >
          + 新規作成
        </button>
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
      {!loading && goals.length === 0 && (
        <div className="text-center py-8 text-[var(--text-secondary)]">
          Zap Goalはまだありません
        </div>
      )}

      <div className="grid gap-4">
        {goals.map(goal => (
          <GoalCard
            key={goal.id}
            goal={goal}
            zapReceipts={zapReceipts}
            profiles={profiles}
            onSelect={setSelectedGoal}
            onDelete={handleDeleteGoal}
            isOwn={true}
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
          onShareToTimeline={handleShareToTimelineLocal}
          onDelete={handleDeleteGoal}
          isOwn={selectedGoal.pubkey === pubkey}
          myPubkey={pubkey}
        />
      )}
    </div>
  )
}

// Export for use in TimelineTab
export { KIND_ZAP_GOAL, ZAP_GOAL_RELAYS, fastFetch, formatSats, formatDate, parseAmount }
