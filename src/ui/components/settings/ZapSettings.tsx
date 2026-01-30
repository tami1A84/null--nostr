'use client'

import React, { useState } from 'react'

const ZAP_PRESETS = [21, 100, 500, 1000, 5000, 10000]

interface ZapSettingsProps {
  defaultAmount: number
  onAmountChange: (amount: number) => void
  expanded?: boolean
  onToggle?: () => void
}

/**
 * Zap amount settings component
 */
export function ZapSettings({
  defaultAmount,
  onAmountChange,
  expanded = false,
  onToggle
}: ZapSettingsProps) {
  const [showCustomInput, setShowCustomInput] = useState(false)
  const [customAmount, setCustomAmount] = useState('')

  const handleCustomSubmit = () => {
    const amount = parseInt(customAmount, 10)
    if (amount > 0) {
      onAmountChange(amount)
      setCustomAmount('')
      setShowCustomInput(false)
    }
  }

  return (
    <section id="zap-section" className="bg-[var(--bg-secondary)] rounded-2xl p-4">
      <button
        onClick={onToggle}
        className="w-full flex items-center justify-between"
      >
        <div className="flex items-center gap-2">
          <ZapIcon className="w-5 h-5 text-[var(--text-secondary)]" />
          <h2 className="font-semibold text-[var(--text-primary)]">デフォルトZap金額</h2>
          <span className="text-sm text-[var(--text-tertiary)]">({defaultAmount} sats)</span>
        </div>
        <ChevronIcon className={`w-5 h-5 text-[var(--text-tertiary)] transition-transform ${expanded ? 'rotate-180' : ''}`} />
      </button>

      {expanded && (
        <div className="mt-4">
          <div className="grid grid-cols-3 gap-2 mb-3">
            {ZAP_PRESETS.map(amount => (
              <button
                key={amount}
                onClick={() => onAmountChange(amount)}
                className={`py-2.5 px-3 rounded-xl text-sm font-medium transition-all ${
                  defaultAmount === amount
                    ? 'bg-[var(--line-green)] text-white'
                    : 'bg-[var(--bg-tertiary)] text-[var(--text-primary)] hover:bg-[var(--border-color)]'
                }`}
              >
                {amount}
              </button>
            ))}
          </div>

          {showCustomInput ? (
            <div className="flex gap-2">
              <input
                type="number"
                value={customAmount}
                onChange={(e) => setCustomAmount(e.target.value)}
                placeholder="カスタム金額"
                className="flex-1 input-line text-sm"
                min="1"
              />
              <button
                onClick={handleCustomSubmit}
                className="btn-line text-sm px-4"
              >
                設定
              </button>
              <button
                onClick={() => setShowCustomInput(false)}
                className="btn-secondary text-sm px-3"
              >
                <CloseIcon className="w-4 h-4" />
              </button>
            </div>
          ) : (
            <button
              onClick={() => setShowCustomInput(true)}
              className="w-full py-2 text-sm text-[var(--line-green)] hover:underline"
            >
              カスタム金額を設定
            </button>
          )}
        </div>
      )}
    </section>
  )
}

// Icons
function ZapIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" />
    </svg>
  )
}

function ChevronIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <polyline points="6 9 12 15 18 9" />
    </svg>
  )
}

function CloseIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <line x1="18" y1="6" x2="6" y2="18" />
      <line x1="6" y1="6" x2="18" y2="18" />
    </svg>
  )
}

export default ZapSettings
