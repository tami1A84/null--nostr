'use client'

import React from 'react'

interface LoadingStateProps {
  message?: string
  className?: string
}

/**
 * Nintendo-style friendly loading state
 * Shows skeleton loading with encouraging message
 */
export function LoadingState({ message = '読み込んでいます...', className }: LoadingStateProps) {
  return (
    <div className={`divide-y divide-[var(--border-color)] animate-fadeIn ${className || ''}`}>
      {[1, 2, 3, 4, 5].map((i) => (
        <div key={i} className="skeleton-post" style={{ animationDelay: `${i * 50}ms` }}>
          <div className="skeleton-avatar skeleton-friendly" />
          <div className="skeleton-content">
            <div className="skeleton-line skeleton-line-short skeleton-friendly" />
            <div className="skeleton-line skeleton-line-full skeleton-friendly" />
            <div className="skeleton-line skeleton-line-medium skeleton-friendly" />
          </div>
        </div>
      ))}
      <LoadingMessage message={message} />
    </div>
  )
}

interface LoadingMessageProps {
  message?: string
  className?: string
}

/**
 * Loading message with animated dots
 */
export function LoadingMessage({ message = '読み込んでいます...', className }: LoadingMessageProps) {
  return (
    <div className={`loading-friendly py-4 ${className || ''}`}>
      <div className="loading-dots">
        <div className="loading-dot" />
        <div className="loading-dot" />
        <div className="loading-dot" />
      </div>
      <p className="loading-text">{message}</p>
    </div>
  )
}

interface SkeletonPostProps {
  count?: number
  className?: string
}

/**
 * Skeleton loading for post items
 */
export function SkeletonPosts({ count = 3, className }: SkeletonPostProps) {
  return (
    <div className={`divide-y divide-[var(--border-color)] ${className || ''}`}>
      {Array.from({ length: count }, (_, i) => (
        <div key={i} className="p-4">
          <div className="flex items-start gap-3">
            <div className="w-10 h-10 rounded-full skeleton" />
            <div className="flex-1">
              <div className="skeleton h-4 w-24 rounded mb-2" />
              <div className="skeleton h-4 w-full rounded mb-1" />
              <div className="skeleton h-4 w-2/3 rounded" />
            </div>
          </div>
        </div>
      ))}
    </div>
  )
}

export default LoadingState
