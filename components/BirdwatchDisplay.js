'use client'

import { useState } from 'react'
import { shortenPubkey, formatTimestamp } from '@/lib/nostr'

// Context type labels
const CONTEXT_TYPE_LABELS = {
  misleading: '誤解を招く情報',
  missing_context: '背景情報が不足',
  factual_error: '事実誤認',
  outdated: '古い情報',
  satire: '風刺・ジョーク'
}

export default function BirdwatchDisplay({
  notes = [],
  profiles = {},
  onRate,
  compact = false
}) {
  const [isExpanded, setIsExpanded] = useState(false)
  const [ratedNotes, setRatedNotes] = useState({})

  if (!notes || notes.length === 0) return null

  // Sort notes by created_at (newest first), then take top notes
  const sortedNotes = [...notes].sort((a, b) => b.created_at - a.created_at)
  const displayNotes = isExpanded ? sortedNotes : sortedNotes.slice(0, 1)
  const hasMore = sortedNotes.length > 1

  const handleRate = async (noteId, rating) => {
    if (ratedNotes[noteId]) return

    try {
      await onRate?.(noteId, rating)
      setRatedNotes(prev => ({ ...prev, [noteId]: rating }))
    } catch (e) {
      console.error('Rating failed:', e)
    }
  }

  // Extract context type from note tags
  const getContextType = (note) => {
    const labelTag = note.tags?.find(t => t[0] === 'l' && t[2] === 'birdwatch')
    return labelTag?.[1] || 'missing_context'
  }

  // Extract source URL from note content
  const extractSourceUrl = (content) => {
    const urlMatch = content.match(/https?:\/\/[^\s]+/)
    return urlMatch?.[0]
  }

  return (
    <div className="mt-2 border border-[var(--border-color)] rounded-lg bg-[var(--bg-secondary)]/50 overflow-hidden">
      {/* Header */}
      <div className="flex items-center gap-2 px-3 py-2 border-b border-[var(--border-color)] bg-blue-500/5">
        <svg className="w-4 h-4 text-blue-500" viewBox="0 0 24 24" fill="currentColor">
          <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/>
        </svg>
        <span className="text-sm font-medium text-blue-500">
          読者からの追加情報
        </span>
        {hasMore && !isExpanded && (
          <span className="text-xs text-[var(--text-tertiary)] ml-auto">
            +{sortedNotes.length - 1}件
          </span>
        )}
      </div>

      {/* Notes */}
      <div className="divide-y divide-[var(--border-color)]">
        {displayNotes.map((note, index) => {
          const profile = profiles[note.pubkey]
          const contextType = getContextType(note)
          const sourceUrl = extractSourceUrl(note.content)
          const contentWithoutUrl = note.content.replace(/https?:\/\/[^\s]+/g, '').trim()
          const isRated = ratedNotes[note.id]

          return (
            <div key={note.id || index} className="px-3 py-2">
              {/* Context type badge */}
              {contextType && CONTEXT_TYPE_LABELS[contextType] && (
                <div className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-blue-500/10 text-blue-500 text-xs font-medium mb-2">
                  {CONTEXT_TYPE_LABELS[contextType]}
                </div>
              )}

              {/* Note content */}
              <p className="text-sm text-[var(--text-primary)] whitespace-pre-wrap break-words mb-2">
                {compact && contentWithoutUrl.length > 200
                  ? contentWithoutUrl.slice(0, 200) + '...'
                  : contentWithoutUrl}
              </p>

              {/* Source URL */}
              {sourceUrl && (
                <a
                  href={sourceUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-1 text-xs text-blue-500 hover:underline mb-2"
                  onClick={(e) => e.stopPropagation()}
                >
                  <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <path d="M18 13v6a2 2 0 01-2 2H5a2 2 0 01-2-2V8a2 2 0 012-2h6"/>
                    <polyline points="15 3 21 3 21 9"/>
                    <line x1="10" y1="14" x2="21" y2="3"/>
                  </svg>
                  ソースを表示
                </a>
              )}

              {/* Author and rating */}
              <div className="flex items-center justify-between text-xs text-[var(--text-tertiary)]">
                <div className="flex items-center gap-2">
                  <span>{profile?.name || shortenPubkey(note.pubkey, 6)}</span>
                  <span>·</span>
                  <span>{formatTimestamp(note.created_at)}</span>
                </div>

                {/* Rating buttons */}
                {onRate && !isRated && (
                  <div className="flex items-center gap-2">
                    <span>役に立ちましたか？</span>
                    <button
                      onClick={(e) => {
                        e.stopPropagation()
                        handleRate(note.id, 'helpful')
                      }}
                      className="px-2 py-1 rounded border border-[var(--border-color)] hover:bg-green-500/10 hover:border-green-500 hover:text-green-500 transition-colors"
                    >
                      はい
                    </button>
                    <button
                      onClick={(e) => {
                        e.stopPropagation()
                        handleRate(note.id, 'not_helpful')
                      }}
                      className="px-2 py-1 rounded border border-[var(--border-color)] hover:bg-red-500/10 hover:border-red-500 hover:text-red-500 transition-colors"
                    >
                      いいえ
                    </button>
                  </div>
                )}

                {isRated && (
                  <span className="text-[var(--line-green)]">
                    評価済み
                  </span>
                )}
              </div>
            </div>
          )
        })}
      </div>

      {/* Expand/Collapse button */}
      {hasMore && (
        <button
          onClick={(e) => {
            e.stopPropagation()
            setIsExpanded(!isExpanded)
          }}
          className="w-full px-3 py-2 text-xs text-blue-500 hover:bg-blue-500/5 transition-colors border-t border-[var(--border-color)]"
        >
          {isExpanded ? '折りたたむ' : `他 ${sortedNotes.length - 1}件のコンテキストを表示`}
        </button>
      )}
    </div>
  )
}
