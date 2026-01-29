'use client'

/**
 * SkeletonLoader Component
 *
 * Nintendo-style: 読み込み中も退屈させない、やさしいローディング表示
 * 低帯域環境(1.5Mbps)でもストレスなく待てるように設計
 */

/**
 * 基本的なスケルトン要素
 */
export function Skeleton({ className = '', style = {} }) {
  return (
    <div
      className={`skeleton-friendly ${className}`}
      style={style}
    />
  )
}

/**
 * ポスト/ノートのスケルトン
 */
export function PostSkeleton({ count = 1 }) {
  return (
    <>
      {Array.from({ length: count }).map((_, i) => (
        <div key={i} className="skeleton-post border-b border-[var(--border-color)]">
          <div className="skeleton-avatar skeleton-friendly" />
          <div className="skeleton-content">
            <div className="skeleton-line skeleton-line-short skeleton-friendly" />
            <div className="skeleton-line skeleton-line-full skeleton-friendly" />
            <div className="skeleton-line skeleton-line-medium skeleton-friendly" />
          </div>
        </div>
      ))}
    </>
  )
}

/**
 * プロフィールのスケルトン
 */
export function ProfileSkeleton() {
  return (
    <div className="p-4">
      <div className="flex items-center gap-3">
        <div className="w-16 h-16 rounded-full skeleton-friendly" />
        <div className="flex-1">
          <div className="h-4 w-24 skeleton-friendly mb-2" />
          <div className="h-3 w-32 skeleton-friendly" />
        </div>
      </div>
      <div className="mt-4 space-y-2">
        <div className="h-3 skeleton-friendly" />
        <div className="h-3 w-3/4 skeleton-friendly" />
      </div>
    </div>
  )
}

/**
 * 画像のスケルトン
 */
export function ImageSkeleton({ aspectRatio = '16/9', className = '' }) {
  return (
    <div
      className={`skeleton-friendly rounded-lg ${className}`}
      style={{ aspectRatio }}
    />
  )
}

/**
 * メッセージ/チャットのスケルトン
 */
export function MessageSkeleton({ count = 3, align = 'left' }) {
  return (
    <div className="p-4 space-y-3">
      {Array.from({ length: count }).map((_, i) => (
        <div
          key={i}
          className={`flex ${align === 'right' || i % 2 === 1 ? 'justify-end' : 'justify-start'}`}
        >
          <div
            className={`skeleton-friendly rounded-2xl ${
              align === 'right' || i % 2 === 1 ? 'rounded-br-sm' : 'rounded-bl-sm'
            }`}
            style={{
              width: `${50 + Math.random() * 30}%`,
              height: '40px'
            }}
          />
        </div>
      ))}
    </div>
  )
}

/**
 * リストアイテムのスケルトン
 */
export function ListItemSkeleton({ count = 5 }) {
  return (
    <>
      {Array.from({ length: count }).map((_, i) => (
        <div key={i} className="p-4 flex items-center gap-3 border-b border-[var(--border-color)]">
          <div className="w-10 h-10 rounded-full skeleton-friendly" />
          <div className="flex-1">
            <div className="h-3 w-1/3 skeleton-friendly mb-2" />
            <div className="h-3 w-2/3 skeleton-friendly" />
          </div>
        </div>
      ))}
    </>
  )
}

/**
 * やさしいローディングインジケーター
 * Nintendo-style: 待っている間も楽しく
 */
export function FriendlyLoading({
  message = '読み込み中...',
  hint = null,
  showDots = true
}) {
  return (
    <div className="loading-friendly">
      {showDots && (
        <div className="loading-dots">
          <div className="loading-dot" />
          <div className="loading-dot" />
          <div className="loading-dot" />
        </div>
      )}
      <p className="loading-text">{message}</p>
      {hint && (
        <p className="text-xs text-[var(--text-tertiary)] mt-2">{hint}</p>
      )}
    </div>
  )
}

/**
 * 空の状態表示
 * Nintendo-style: 励ましのメッセージ
 */
export function EmptyState({
  icon = '📭',
  title = 'まだ何もありません',
  message = '',
  action = null
}) {
  return (
    <div className="empty-friendly">
      <div className="empty-friendly-icon">{icon}</div>
      <h3 className="text-base font-medium text-[var(--text-primary)] mb-1">{title}</h3>
      {message && <p className="empty-friendly-text">{message}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  )
}

/**
 * プログレスバー
 */
export function ProgressBar({ progress = 0, showPercentage = false }) {
  return (
    <div className="progress-friendly">
      <div
        className="progress-friendly-bar"
        style={{ width: `${Math.min(100, Math.max(0, progress))}%` }}
      />
      {showPercentage && (
        <span className="text-xs text-[var(--text-tertiary)] mt-1 block text-center">
          {Math.round(progress)}%
        </span>
      )}
    </div>
  )
}

/**
 * オフラインバナー
 */
export function OfflineBanner({ onRetry }) {
  return (
    <div className="offline-banner flex items-center justify-center gap-2">
      <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
        <path d="M1 1l22 22M16.72 11.06A10.94 10.94 0 0119 12.55M5 12.55a10.94 10.94 0 015.17-2.39M10.71 5.05A16 16 0 0122.58 9M1.42 9a15.91 15.91 0 014.7-2.88M8.53 16.11a6 6 0 016.95 0M12 20h.01"/>
      </svg>
      <span>オフラインです</span>
      {onRetry && (
        <button
          onClick={onRetry}
          className="ml-2 underline hover:no-underline"
        >
          再接続
        </button>
      )}
    </div>
  )
}

/**
 * 低帯域警告
 */
export function SlowConnectionBanner({ onDismiss }) {
  return (
    <div className="bg-[var(--color-warning-soft)] text-[var(--color-warning)] px-3 py-2 text-xs flex items-center justify-between">
      <span className="flex items-center gap-2">
        <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <circle cx="12" cy="12" r="10"/>
          <line x1="12" y1="8" x2="12" y2="12"/>
          <line x1="12" y1="16" x2="12.01" y2="16"/>
        </svg>
        通信が遅いようです。画像を節約モードで表示しています
      </span>
      {onDismiss && (
        <button onClick={onDismiss} className="p-1 hover:opacity-70">
          <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <line x1="18" y1="6" x2="6" y2="18"/>
            <line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>
      )}
    </div>
  )
}

/**
 * タイムラインの初期ローディング
 */
export function TimelineLoadingSkeleton() {
  return (
    <div className="animate-fadeIn">
      <PostSkeleton count={5} />
      <FriendlyLoading
        message="タイムラインを読み込んでいます"
        hint="もう少しお待ちください"
      />
    </div>
  )
}

export default {
  Skeleton,
  PostSkeleton,
  ProfileSkeleton,
  ImageSkeleton,
  MessageSkeleton,
  ListItemSkeleton,
  FriendlyLoading,
  EmptyState,
  ProgressBar,
  OfflineBanner,
  SlowConnectionBanner,
  TimelineLoadingSkeleton
}
