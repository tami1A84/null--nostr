'use client'

type TimelineMode = 'global' | 'following'

interface TimelineEmptyProps {
  mode: TimelineMode
  hasFollows: boolean
  hasError?: boolean
  onRetry?: () => void
}

export default function TimelineEmpty({ mode, hasFollows, hasError = false, onRetry }: TimelineEmptyProps) {
  return (
    <div className="error-friendly animate-fadeIn">
      <div className="error-friendly-icon">
        {hasError ? (
          /* Connection error - friendly icon */
          <svg className="w-10 h-10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
            <circle cx="12" cy="12" r="10"/>
            <path d="M8 14s1.5 2 4 2 4-2 4-2"/>
            <line x1="9" y1="9" x2="9.01" y2="9"/>
            <line x1="15" y1="9" x2="15.01" y2="9"/>
          </svg>
        ) : mode === 'following' ? (
          <svg className="w-10 h-10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
            <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/>
            <circle cx="9" cy="7" r="4"/>
            <path d="M23 21v-2a4 4 0 0 0-3-3.87"/>
            <path d="M16 3.13a4 4 0 0 1 0 7.75"/>
          </svg>
        ) : (
          <svg className="w-10 h-10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
            <rect x="3" y="3" width="18" height="18" rx="2" ry="2"/>
            <line x1="3" y1="9" x2="21" y2="9"/>
            <line x1="9" y1="21" x2="9" y2="9"/>
          </svg>
        )}
      </div>

      {/* Encouraging message */}
      <h2 className="error-friendly-title">
        {hasError
          ? 'うまく接続できませんでした'
          : mode === 'following'
            ? (!hasFollows ? 'まだ誰もフォローしていません' : 'まだ投稿がないようです')
            : 'まだ投稿がありません'}
      </h2>

      <p className="error-friendly-message">
        {hasError
          ? '通信状態を確認して、もう一度お試しください'
          : mode === 'following'
            ? (!hasFollows ? '素敵な人を見つけてフォローしてみましょう' : 'しばらくお待ちいただくか、更新してみてください')
            : '新しい投稿がまもなく届くかもしれません'}
      </p>

      {hasError && onRetry ? (
        <button
          onClick={onRetry}
          className="retry-button mt-4"
        >
          <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <polyline points="23 4 23 10 17 10"/>
            <path d="M20.49 15a9 9 0 11-2.12-9.36L23 10"/>
          </svg>
          もう一度試す
        </button>
      ) : mode === 'following' && !hasFollows && (
        <div className="error-friendly-hint mt-4">
          💡 プロフィールページでユーザーをフォローしてみましょう
        </div>
      )}
    </div>
  )
}

// Desktop version with simpler styling
export function TimelineEmptyDesktop({ hasFollows }: { hasFollows: boolean }) {
  return (
    <div className="px-4 py-16 text-center">
      <p className="text-[var(--text-tertiary)]">
        {!hasFollows ? 'まだ誰もフォローしていません' : 'フォロー中のユーザーの投稿がありません'}
      </p>
      {!hasFollows && (
        <p className="text-xs text-[var(--text-tertiary)] mt-2">
          プロフィールページでユーザーをフォローしてみましょう
        </p>
      )}
    </div>
  )
}

// Global timeline empty state for desktop
export function TimelineEmptyGlobal() {
  return (
    <div className="empty-friendly">
      <div className="empty-friendly-icon">📭</div>
      <p className="empty-friendly-text">まだ投稿がありません<br/>新しい投稿がまもなく届くかもしれません</p>
    </div>
  )
}
