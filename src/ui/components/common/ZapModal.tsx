'use client'

import React, { useState } from 'react'
import type { Profile } from '../../types'
import type { Event as NostrEvent } from 'nostr-tools'

interface ZapModalProps {
  event: NostrEvent
  profile: Profile
  defaultAmount?: number
  onClose: () => void
  onSend: (amount: number, comment: string) => Promise<void>
  sending?: boolean
}

const ZAP_PRESETS = [21, 100, 500, 1000, 5000]

/**
 * Modal for sending custom zap amounts
 */
export function ZapModal({
  event,
  profile,
  defaultAmount = 21,
  onClose,
  onSend,
  sending = false
}: ZapModalProps) {
  const [amount, setAmount] = useState(defaultAmount.toString())
  const [comment, setComment] = useState('')

  const handleSend = async () => {
    const numAmount = parseInt(amount, 10)
    if (!numAmount || numAmount < 1) {
      alert('有効な金額を入力してください')
      return
    }
    await onSend(numAmount, comment)
  }

  const displayName = profile.name || profile.display_name || shortenPubkey(event.pubkey)

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center modal-overlay"
      onClick={onClose}
    >
      <div
        className="w-full max-w-sm mx-4 bg-[var(--bg-primary)] rounded-2xl overflow-hidden animate-scaleIn"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="p-4 border-b border-[var(--border-color)]">
          <div className="flex items-center justify-between">
            <h3 className="text-lg font-bold text-[var(--text-primary)]">⚡ Zap送信</h3>
            <button
              onClick={onClose}
              className="text-[var(--text-tertiary)] action-btn p-1"
            >
              <CloseIcon className="w-6 h-6" />
            </button>
          </div>
          <p className="text-sm text-[var(--text-secondary)] mt-1">
            {displayName}
          </p>
        </div>

        {/* Body */}
        <div className="p-4 space-y-4">
          {/* Amount Input */}
          <div>
            <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1.5">
              金額 (sats)
            </label>
            <input
              type="number"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              className="input-line"
              placeholder="21"
              min="1"
            />
          </div>

          {/* Preset Buttons */}
          <div className="flex flex-wrap gap-2">
            {ZAP_PRESETS.map((preset) => (
              <button
                key={preset}
                onClick={() => setAmount(preset.toString())}
                className={`px-3 py-1 rounded-full text-sm ${
                  amount === preset.toString()
                    ? 'bg-yellow-500 text-black'
                    : 'bg-[var(--bg-secondary)] text-[var(--text-primary)]'
                }`}
              >
                ⚡{preset}
              </button>
            ))}
          </div>

          {/* Comment Input */}
          <div>
            <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1.5">
              コメント (任意)
            </label>
            <input
              type="text"
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              className="input-line"
              placeholder="Zap!"
              maxLength={100}
            />
          </div>
        </div>

        {/* Footer */}
        <div className="p-4 border-t border-[var(--border-color)]">
          <button
            onClick={handleSend}
            disabled={sending || !amount}
            className="w-full btn-line py-3 disabled:opacity-50"
          >
            {sending ? '作成中...' : `⚡ ${amount || 0} sats のインボイスを作成`}
          </button>
        </div>
      </div>
    </div>
  )
}

function CloseIcon({ className }: { className?: string }) {
  return (
    <svg
      className={className}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      <line x1="18" y1="6" x2="6" y2="18" />
      <line x1="6" y1="6" x2="18" y2="18" />
    </svg>
  )
}

function shortenPubkey(pubkey: string): string {
  if (!pubkey) return ''
  return `${pubkey.slice(0, 8)}...${pubkey.slice(-4)}`
}

export default ZapModal
