'use client'

interface TimelineLoadingProps {
  count?: number
  message?: string
}

export default function TimelineLoading({ count = 5, message = '読み込んでいます...' }: TimelineLoadingProps) {
  return (
    <div className="divide-y divide-[var(--border-color)] animate-fadeIn">
      {Array.from({ length: count }, (_, i) => i + 1).map((i) => (
        <div key={i} className="skeleton-post" style={{ animationDelay: `${i * 50}ms` }}>
          <div className="skeleton-avatar skeleton-friendly" />
          <div className="skeleton-content">
            <div className="skeleton-line skeleton-line-short skeleton-friendly" />
            <div className="skeleton-line skeleton-line-full skeleton-friendly" />
            <div className="skeleton-line skeleton-line-medium skeleton-friendly" />
          </div>
        </div>
      ))}
      {/* Encouraging message */}
      <div className="loading-friendly py-4">
        <div className="loading-dots">
          <div className="loading-dot" />
          <div className="loading-dot" />
          <div className="loading-dot" />
        </div>
        <p className="loading-text">{message}</p>
      </div>
    </div>
  )
}

// Compact loading for "load more" scenarios
export function TimelineLoadingMore({ message = 'もっと読み込んでいます...' }: { message?: string }) {
  return (
    <div className="loading-friendly py-4">
      <div className="loading-dots">
        <div className="loading-dot" />
        <div className="loading-dot" />
        <div className="loading-dot" />
      </div>
      <p className="loading-text">{message}</p>
    </div>
  )
}
