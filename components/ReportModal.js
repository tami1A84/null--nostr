'use client'

import { useState } from 'react'

// NIP-56 Report Types
const REPORT_TYPES = [
  { value: 'spam', label: 'スパム', description: '宣伝目的の迷惑投稿' },
  { value: 'nudity', label: 'ヌード・性的コンテンツ', description: '露骨な性的コンテンツ' },
  { value: 'profanity', label: 'ヘイトスピーチ', description: '差別的・攻撃的な表現' },
  { value: 'illegal', label: '違法コンテンツ', description: '法律に違反する可能性のある内容' },
  { value: 'impersonation', label: 'なりすまし', description: '他人になりすましている' },
  { value: 'malware', label: 'マルウェア', description: '悪意のあるリンクやファイル' },
  { value: 'other', label: 'その他', description: '上記以外の問題' }
]

export default function ReportModal({
  isOpen,
  onClose,
  onReport,
  targetEvent,
  targetPubkey
}) {
  const [selectedType, setSelectedType] = useState(null)
  const [additionalInfo, setAdditionalInfo] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)

  if (!isOpen) return null

  const handleSubmit = async () => {
    if (!selectedType) return

    setIsSubmitting(true)
    try {
      await onReport({
        eventId: targetEvent?.id,
        pubkey: targetPubkey,
        reportType: selectedType,
        content: additionalInfo
      })
      onClose()
    } catch (e) {
      console.error('Report failed:', e)
      alert('通報に失敗しました')
    } finally {
      setIsSubmitting(false)
    }
  }

  const handleBackdropClick = (e) => {
    if (e.target === e.currentTarget) {
      onClose()
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center modal-overlay bg-black/40 backdrop-blur-sm"
      onClick={handleBackdropClick}
      role="dialog"
      aria-modal="true"
    >
      <div
        className="w-full max-w-md mx-4 bg-[var(--bg-primary)] rounded-2xl overflow-hidden animate-scaleIn shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-4 py-3 border-b border-[var(--border-color)]">
          <h2 className="text-lg font-semibold text-[var(--text-primary)]">
            投稿を通報
          </h2>
          <button
            onClick={onClose}
            className="p-1 text-[var(--text-tertiary)] hover:text-[var(--text-primary)] transition-colors"
          >
            <svg className="w-6 h-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M18 6L6 18M6 6l12 12" />
            </svg>
          </button>
        </div>

        {/* Content */}
        <div className="p-4 max-h-[70vh] overflow-y-auto">
          <p className="text-sm text-[var(--text-secondary)] mb-4">
            この投稿を通報する理由を選択してください。通報はNIP-56プロトコルに基づいてリレーに送信されます。
          </p>

          {/* Report type selection */}
          <div className="space-y-2">
            {REPORT_TYPES.map((type) => (
              <button
                key={type.value}
                onClick={() => setSelectedType(type.value)}
                className={`w-full text-left p-3 rounded-lg border transition-colors ${
                  selectedType === type.value
                    ? 'border-red-500 bg-red-500/10'
                    : 'border-[var(--border-color)] hover:bg-[var(--bg-secondary)]'
                }`}
              >
                <div className="flex items-center gap-3">
                  <div className={`w-4 h-4 rounded-full border-2 flex items-center justify-center ${
                    selectedType === type.value ? 'border-red-500' : 'border-[var(--text-tertiary)]'
                  }`}>
                    {selectedType === type.value && (
                      <div className="w-2 h-2 rounded-full bg-red-500" />
                    )}
                  </div>
                  <div>
                    <p className="font-medium text-[var(--text-primary)]">{type.label}</p>
                    <p className="text-xs text-[var(--text-tertiary)]">{type.description}</p>
                  </div>
                </div>
              </button>
            ))}
          </div>

          {/* Additional info */}
          {selectedType && (
            <div className="mt-4">
              <label className="block text-sm font-medium text-[var(--text-secondary)] mb-2">
                追加情報（任意）
              </label>
              <textarea
                value={additionalInfo}
                onChange={(e) => setAdditionalInfo(e.target.value)}
                placeholder="通報の詳細を入力..."
                className="w-full h-24 px-3 py-2 rounded-lg border border-[var(--border-color)] bg-[var(--bg-secondary)] text-[var(--text-primary)] placeholder:text-[var(--text-tertiary)] resize-none focus:outline-none focus:ring-2 focus:ring-red-500/50"
                maxLength={500}
              />
              <p className="text-xs text-[var(--text-tertiary)] mt-1 text-right">
                {additionalInfo.length}/500
              </p>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex gap-3 px-4 py-3 border-t border-[var(--border-color)]">
          <button
            onClick={onClose}
            className="flex-1 px-4 py-2 rounded-lg border border-[var(--border-color)] text-[var(--text-primary)] hover:bg-[var(--bg-secondary)] transition-colors"
          >
            キャンセル
          </button>
          <button
            onClick={handleSubmit}
            disabled={!selectedType || isSubmitting}
            className="flex-1 px-4 py-2 rounded-lg bg-red-500 text-white font-medium hover:bg-red-600 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {isSubmitting ? '送信中...' : '通報する'}
          </button>
        </div>
      </div>
    </div>
  )
}
