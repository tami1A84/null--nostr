'use client'

import { useState, useEffect } from 'react'
import { createPortal } from 'react-dom'
import { shortenPubkey, formatTimestamp } from '@/lib/nostr'

// Birdwatch context types
const CONTEXT_TYPES = [
  { value: 'misleading', label: '誤解を招く情報', icon: '!' },
  { value: 'missing_context', label: '背景情報が不足', icon: '?' },
  { value: 'factual_error', label: '事実誤認', icon: 'x' },
  { value: 'outdated', label: '古い情報', icon: 'o' },
  { value: 'satire', label: '風刺・ジョーク', icon: 's' }
]

export default function BirdwatchModal({
  isOpen,
  onClose,
  onSubmit,
  targetEvent,
  existingNotes = [],
  profiles = {},
  myPubkey
}) {
  const [contextType, setContextType] = useState(null)
  const [noteContent, setNoteContent] = useState('')
  const [sourceUrl, setSourceUrl] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [showExisting, setShowExisting] = useState(true)
  const [mounted, setMounted] = useState(false)

  // Check if user already submitted a note for this post
  const hasUserNote = existingNotes.some(note => note.pubkey === myPubkey)

  useEffect(() => {
    setMounted(true)
    return () => setMounted(false)
  }, [])

  useEffect(() => {
    if (isOpen) {
      // Reset form when modal opens
      setContextType(null)
      setNoteContent('')
      setSourceUrl('')
    }
  }, [isOpen])

  if (!isOpen || !mounted) return null

  const handleSubmit = async () => {
    if (!contextType || !noteContent.trim()) return

    setIsSubmitting(true)
    try {
      await onSubmit({
        eventId: targetEvent?.id,
        pubkey: targetEvent?.pubkey,
        contextType,
        content: noteContent.trim(),
        sourceUrl: sourceUrl.trim()
      })
      onClose()
    } catch (e) {
      console.error('Birdwatch submit failed:', e)
      alert('コンテキストの追加に失敗しました')
    } finally {
      setIsSubmitting(false)
    }
  }

  const handleBackdropClick = (e) => {
    if (e.target === e.currentTarget) {
      onClose()
    }
  }

  const modalContent = (
    <div
      className="fixed inset-0 z-[9999] flex items-center justify-center bg-black/70"
      onClick={handleBackdropClick}
      role="dialog"
      aria-modal="true"
      style={{ isolation: 'isolate' }}
    >
      <div
        className="w-full max-w-lg mx-4 rounded-2xl overflow-hidden animate-scaleIn shadow-2xl max-h-[90vh] flex flex-col border border-[var(--border-color)]"
        style={{ backgroundColor: 'var(--bg-primary)' }}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-4 py-3 border-b border-[var(--border-color)] flex-shrink-0">
          <div className="flex items-center gap-2">
            <svg className="w-5 h-5 text-blue-500" viewBox="0 0 24 24" fill="currentColor">
              <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/>
            </svg>
            <h2 className="text-lg font-semibold text-[var(--text-primary)]">
              Birdwatch
            </h2>
          </div>
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
        <div className="p-4 overflow-y-auto flex-1">
          {/* Existing notes section */}
          {existingNotes.length > 0 && (
            <div className="mb-4">
              <button
                onClick={() => setShowExisting(!showExisting)}
                className="flex items-center gap-2 text-sm font-medium text-[var(--text-secondary)] mb-2"
              >
                <svg
                  className={`w-4 h-4 transition-transform ${showExisting ? 'rotate-90' : ''}`}
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                >
                  <path d="M9 18l6-6-6-6"/>
                </svg>
                既存のコンテキスト ({existingNotes.length}件)
              </button>

              {showExisting && (
                <div className="space-y-2 border border-[var(--border-color)] rounded-lg p-3 bg-[var(--bg-secondary)]">
                  {existingNotes.slice(0, 3).map((note, index) => {
                    const profile = profiles[note.pubkey]
                    return (
                      <div key={note.id || index} className="text-sm">
                        <div className="flex items-center gap-2 mb-1">
                          <span className="font-medium text-[var(--text-primary)]">
                            {profile?.name || shortenPubkey(note.pubkey, 6)}
                          </span>
                          <span className="text-xs text-[var(--text-tertiary)]">
                            {formatTimestamp(note.created_at)}
                          </span>
                        </div>
                        <p className="text-[var(--text-secondary)] line-clamp-2">
                          {note.content}
                        </p>
                      </div>
                    )
                  })}
                  {existingNotes.length > 3 && (
                    <p className="text-xs text-[var(--text-tertiary)]">
                      +{existingNotes.length - 3}件のコンテキスト
                    </p>
                  )}
                </div>
              )}
            </div>
          )}

          {/* User already submitted warning */}
          {hasUserNote && (
            <div className="mb-4 p-3 rounded-lg bg-yellow-500/10 border border-yellow-500/30">
              <div className="flex items-center gap-2 text-yellow-600 dark:text-yellow-400">
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/>
                  <line x1="12" y1="9" x2="12" y2="13"/>
                  <line x1="12" y1="17" x2="12.01" y2="17"/>
                </svg>
                <span className="text-sm font-medium">
                  この投稿には既にコンテキストを追加しています
                </span>
              </div>
            </div>
          )}

          <p className="text-sm text-[var(--text-secondary)] mb-4">
            この投稿に追加のコンテキストを提供してください。正確で役立つ情報を共有することで、他のユーザーの理解を助けることができます。
          </p>

          {/* Context type selection */}
          <div className="mb-4">
            <label className="block text-sm font-medium text-[var(--text-secondary)] mb-2">
              コンテキストの種類
            </label>
            <div className="grid grid-cols-2 gap-2">
              {CONTEXT_TYPES.map((type) => (
                <button
                  key={type.value}
                  onClick={() => setContextType(type.value)}
                  className={`text-left p-2 rounded-lg border transition-colors ${
                    contextType === type.value
                      ? 'border-blue-500 bg-blue-500/10'
                      : 'border-[var(--border-color)] hover:bg-[var(--bg-secondary)]'
                  }`}
                >
                  <div className="flex items-center gap-2">
                    <span className={`w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold ${
                      contextType === type.value
                        ? 'bg-blue-500 text-white'
                        : 'bg-[var(--bg-tertiary)] text-[var(--text-tertiary)]'
                    }`}>
                      {type.icon}
                    </span>
                    <span className="text-sm text-[var(--text-primary)]">{type.label}</span>
                  </div>
                </button>
              ))}
            </div>
          </div>

          {/* Note content */}
          <div className="mb-4">
            <label className="block text-sm font-medium text-[var(--text-secondary)] mb-2">
              コンテキストの内容 <span className="text-red-500">*</span>
            </label>
            <textarea
              value={noteContent}
              onChange={(e) => setNoteContent(e.target.value)}
              placeholder="この投稿に関する追加情報を入力してください..."
              className="w-full h-32 px-3 py-2 rounded-lg border border-[var(--border-color)] bg-[var(--bg-secondary)] text-[var(--text-primary)] placeholder:text-[var(--text-tertiary)] resize-none focus:outline-none focus:ring-2 focus:ring-blue-500/50"
              maxLength={1000}
            />
            <p className="text-xs text-[var(--text-tertiary)] mt-1 text-right">
              {noteContent.length}/1000
            </p>
          </div>

          {/* Source URL */}
          <div>
            <label className="block text-sm font-medium text-[var(--text-secondary)] mb-2">
              ソースURL（任意）
            </label>
            <input
              type="url"
              value={sourceUrl}
              onChange={(e) => setSourceUrl(e.target.value)}
              placeholder="https://example.com/source"
              className="w-full px-3 py-2 rounded-lg border border-[var(--border-color)] bg-[var(--bg-secondary)] text-[var(--text-primary)] placeholder:text-[var(--text-tertiary)] focus:outline-none focus:ring-2 focus:ring-blue-500/50"
            />
            <p className="text-xs text-[var(--text-tertiary)] mt-1">
              情報源へのリンクがあれば追加してください
            </p>
          </div>
        </div>

        {/* Footer */}
        <div className="flex gap-3 px-4 py-3 border-t border-[var(--border-color)] flex-shrink-0">
          <button
            onClick={onClose}
            className="flex-1 px-4 py-2 rounded-lg border border-[var(--border-color)] text-[var(--text-primary)] hover:bg-[var(--bg-secondary)] transition-colors"
          >
            キャンセル
          </button>
          <button
            onClick={handleSubmit}
            disabled={!contextType || !noteContent.trim() || isSubmitting || hasUserNote}
            className="flex-1 px-4 py-2 rounded-lg bg-blue-500 text-white font-medium hover:bg-blue-600 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {isSubmitting ? '送信中...' : 'コンテキストを追加'}
          </button>
        </div>
      </div>
    </div>
  )

  return createPortal(modalContent, document.body)
}
