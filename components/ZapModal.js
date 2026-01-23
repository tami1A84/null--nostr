'use client'

import { useState } from 'react'
import { shortenPubkey, fetchLightningInvoice, copyToClipboard } from '@/lib/nostr'

/**
 * Zap Modal Component
 *
 * Modal for sending Lightning Network tips (Zaps) with custom amounts.
 *
 * @param {Object} props
 * @param {Object} props.event - The Nostr event being zapped
 * @param {Object} props.profile - Profile of the event author
 * @param {Function} props.onClose - Close handler
 * @param {number} [props.defaultAmount=21] - Default zap amount in sats
 * @returns {JSX.Element}
 */
export default function ZapModal({ event, profile, onClose, defaultAmount = 21 }) {
  const [zapAmount, setZapAmount] = useState(defaultAmount.toString())
  const [zapComment, setZapComment] = useState('')
  const [zapping, setZapping] = useState(false)

  const handleSendZap = async () => {
    if (zapping) return

    const amount = parseInt(zapAmount, 10)
    if (!amount || amount < 1) {
      alert('有効な金額を入力してください')
      return
    }

    if (!profile?.lud16) {
      alert('この投稿者はLightningアドレスを設定していません')
      return
    }

    try {
      setZapping(true)
      const result = await fetchLightningInvoice(profile.lud16, amount, zapComment)

      if (result.invoice) {
        const copied = await copyToClipboard(result.invoice)
        onClose()
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

  const handleBackdropClick = (e) => {
    if (e.target === e.currentTarget) {
      onClose()
    }
  }

  const presetAmounts = [21, 100, 500, 1000, 5000]

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center modal-overlay"
      onClick={handleBackdropClick}
      role="dialog"
      aria-modal="true"
      aria-labelledby="zap-modal-title"
    >
      <div
        className="w-full max-w-sm mx-4 bg-[var(--bg-primary)] rounded-2xl overflow-hidden animate-scaleIn"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="p-4 border-b border-[var(--border-color)]">
          <div className="flex items-center justify-between">
            <h3 id="zap-modal-title" className="text-lg font-bold text-[var(--text-primary)]">
              ⚡ Zap送信
            </h3>
            <button
              onClick={onClose}
              className="text-[var(--text-tertiary)] action-btn p-1"
              aria-label="閉じる"
            >
              <svg className="w-6 h-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </button>
          </div>
          <p className="text-sm text-[var(--text-secondary)] mt-1">
            {profile?.name || shortenPubkey(event.pubkey)}
          </p>
        </div>

        {/* Content */}
        <div className="p-4 space-y-4">
          {/* Amount input */}
          <div>
            <label
              htmlFor="zap-amount"
              className="block text-sm font-medium text-[var(--text-secondary)] mb-1.5"
            >
              金額 (sats)
            </label>
            <input
              id="zap-amount"
              type="number"
              value={zapAmount}
              onChange={(e) => setZapAmount(e.target.value)}
              className="input-line"
              placeholder="21"
              min="1"
              aria-describedby="zap-amount-desc"
            />
            <p id="zap-amount-desc" className="sr-only">
              送信するsatoshiの金額を入力してください
            </p>
          </div>

          {/* Preset buttons */}
          <div className="flex flex-wrap gap-2" role="group" aria-label="プリセット金額">
            {presetAmounts.map((amount) => (
              <button
                key={amount}
                onClick={() => setZapAmount(amount.toString())}
                className={`px-3 py-1 rounded-full text-sm transition-colors ${
                  zapAmount === amount.toString()
                    ? 'bg-yellow-500 text-black'
                    : 'bg-[var(--bg-secondary)] text-[var(--text-primary)] hover:bg-[var(--bg-tertiary)]'
                }`}
                aria-pressed={zapAmount === amount.toString()}
              >
                ⚡{amount}
              </button>
            ))}
          </div>

          {/* Comment input */}
          <div>
            <label
              htmlFor="zap-comment"
              className="block text-sm font-medium text-[var(--text-secondary)] mb-1.5"
            >
              コメント (任意)
            </label>
            <input
              id="zap-comment"
              type="text"
              value={zapComment}
              onChange={(e) => setZapComment(e.target.value)}
              className="input-line"
              placeholder="Zap!"
              maxLength={100}
            />
          </div>
        </div>

        {/* Footer */}
        <div className="p-4 border-t border-[var(--border-color)]">
          <button
            onClick={handleSendZap}
            disabled={zapping || !zapAmount}
            className="w-full btn-line py-3 disabled:opacity-50"
            aria-busy={zapping}
          >
            {zapping ? '作成中...' : `⚡ ${zapAmount || 0} sats のインボイスを作成`}
          </button>
        </div>
      </div>
    </div>
  )
}
