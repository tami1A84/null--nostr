'use client'

import { useState, useEffect } from 'react'

const ZAP_PRESETS = [21, 100, 500, 1000, 5000, 10000]

export default function ZapSettings() {
  const [defaultZap, setDefaultZap] = useState(21)
  const [showZapInput, setShowZapInput] = useState(false)
  const [customZap, setCustomZap] = useState('')

  useEffect(() => {
    const saved = localStorage.getItem('defaultZapAmount')
    if (saved) {
      setDefaultZap(parseInt(saved, 10))
    }
  }, [])

  const handleSetDefaultZap = (amount) => {
    setDefaultZap(amount)
    localStorage.setItem('defaultZapAmount', amount.toString())
    setShowZapInput(false)
  }

  const handleCustomZap = () => {
    const amount = parseInt(customZap, 10)
    if (amount > 0) {
      handleSetDefaultZap(amount)
      setCustomZap('')
    }
  }

  return (
    <div className="space-y-4">
      <div className="p-4 bg-[var(--bg-secondary)] rounded-2xl">
        <h3 className="text-lg font-semibold text-[var(--text-primary)] mb-4">デフォルトZap金額</h3>
        <div className="grid grid-cols-3 gap-2 mb-3">
          {ZAP_PRESETS.map(amount => (
            <button
              key={amount}
              onClick={() => handleSetDefaultZap(amount)}
              className={`py-2.5 px-3 rounded-xl text-sm font-medium transition-all ${
                defaultZap === amount
                  ? 'bg-[var(--line-green)] text-white'
                  : 'bg-[var(--bg-tertiary)] text-[var(--text-primary)] hover:bg-[var(--border-color)]'
              }`}
            >
              {amount}
            </button>
          ))}
        </div>

        {showZapInput ? (
          <div className="flex gap-2">
            <input
              type="number"
              value={customZap}
              onChange={(e) => setCustomZap(e.target.value)}
              placeholder="カスタム金額"
              className="flex-1 input-line text-sm"
              min="1"
            />
            <button
              onClick={handleCustomZap}
              className="btn-line text-sm px-4"
            >
              設定
            </button>
            <button
              onClick={() => setShowZapInput(false)}
              className="btn-secondary text-sm px-3"
            >
              <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <line x1="18" y1="6" x2="6" y2="18"/>
                <line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>
        ) : (
          <button
            onClick={() => setShowZapInput(true)}
            className="w-full py-2 text-sm text-[var(--line-green)] hover:underline"
          >
            カスタム金額を設定
          </button>
        )}
      </div>
      <p className="text-xs text-[var(--text-tertiary)] px-2">
        現在の設定: {defaultZap} sats
      </p>
    </div>
  )
}
