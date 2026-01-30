'use client'

import React from 'react'

type ErrorType = 'connection' | 'empty' | 'noFollows' | 'generic'

interface ErrorStateProps {
  type?: ErrorType
  title?: string
  message?: string
  hint?: string
  onRetry?: () => void
  retryLabel?: string
  className?: string
}

/**
 * Nintendo-style friendly error state
 * Encourages users without blame
 */
export function ErrorState({
  type = 'generic',
  title,
  message,
  hint,
  onRetry,
  retryLabel = 'もう一度試す',
  className
}: ErrorStateProps) {
  const config = getErrorConfig(type)

  return (
    <div className={`error-friendly animate-fadeIn ${className || ''}`}>
      <div className="error-friendly-icon">
        {config.icon}
      </div>
      <h2 className="error-friendly-title">
        {title || config.title}
      </h2>
      <p className="error-friendly-message">
        {message || config.message}
      </p>
      {onRetry && (
        <button
          onClick={onRetry}
          className="retry-button mt-4"
        >
          <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <polyline points="23 4 23 10 17 10"/>
            <path d="M20.49 15a9 9 0 11-2.12-9.36L23 10"/>
          </svg>
          {retryLabel}
        </button>
      )}
      {hint && (
        <div className="error-friendly-hint mt-4">
          {hint}
        </div>
      )}
    </div>
  )
}

function getErrorConfig(type: ErrorType) {
  switch (type) {
    case 'connection':
      return {
        icon: (
          <svg className="w-10 h-10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
            <circle cx="12" cy="12" r="10"/>
            <path d="M8 14s1.5 2 4 2 4-2 4-2"/>
            <line x1="9" y1="9" x2="9.01" y2="9"/>
            <line x1="15" y1="9" x2="15.01" y2="9"/>
          </svg>
        ),
        title: 'うまく接続できませんでした',
        message: '通信状態を確認して、もう一度お試しください'
      }
    case 'empty':
      return {
        icon: (
          <svg className="w-10 h-10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
            <rect x="3" y="3" width="18" height="18" rx="2" ry="2"/>
            <line x1="3" y1="9" x2="21" y2="9"/>
            <line x1="9" y1="21" x2="9" y2="9"/>
          </svg>
        ),
        title: 'まだ投稿がありません',
        message: '新しい投稿がまもなく届くかもしれません'
      }
    case 'noFollows':
      return {
        icon: (
          <svg className="w-10 h-10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
            <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/>
            <circle cx="9" cy="7" r="4"/>
            <path d="M23 21v-2a4 4 0 0 0-3-3.87"/>
            <path d="M16 3.13a4 4 0 0 1 0 7.75"/>
          </svg>
        ),
        title: 'まだ誰もフォローしていません',
        message: '素敵な人を見つけてフォローしてみましょう'
      }
    default:
      return {
        icon: (
          <svg className="w-10 h-10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
            <circle cx="12" cy="12" r="10"/>
            <line x1="12" y1="8" x2="12" y2="12"/>
            <line x1="12" y1="16" x2="12.01" y2="16"/>
          </svg>
        ),
        title: '問題が発生しました',
        message: 'しばらくしてからもう一度お試しください'
      }
  }
}

interface EmptyStateProps {
  icon?: React.ReactNode
  title: string
  message?: string
  action?: React.ReactNode
  className?: string
}

/**
 * Simple empty state display
 */
export function EmptyState({ icon, title, message, action, className }: EmptyStateProps) {
  return (
    <div className={`empty-friendly ${className || ''}`}>
      {icon && <div className="empty-friendly-icon">{icon}</div>}
      <p className="empty-friendly-text">
        {title}
        {message && <><br/>{message}</>}
      </p>
      {action}
    </div>
  )
}

export default ErrorState
