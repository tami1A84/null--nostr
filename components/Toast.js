'use client'

import { useState, useEffect, useCallback, createContext, useContext } from 'react'

/**
 * Toast Notification System
 *
 * Nintendo-style: やさしい通知表示
 * - 強い警告色を避ける
 * - 励ましのメッセージ
 * - 邪魔にならない控えめなデザイン
 */

// Toast Context
const ToastContext = createContext(null)

// Toast types
export const TOAST_TYPES = {
  SUCCESS: 'success',
  INFO: 'info',
  WARNING: 'warning',
  ERROR: 'error'
}

// Default durations (ms)
const DURATIONS = {
  short: 2000,
  normal: 3500,
  long: 5000
}

/**
 * Toast Provider Component
 */
export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([])

  const addToast = useCallback((message, options = {}) => {
    const {
      type = TOAST_TYPES.INFO,
      duration = DURATIONS.normal,
      icon = null,
      action = null
    } = options

    const id = Date.now() + Math.random()
    const toast = { id, message, type, icon, action }

    setToasts(prev => [...prev, toast])

    // Auto dismiss
    if (duration > 0) {
      setTimeout(() => {
        setToasts(prev => prev.filter(t => t.id !== id))
      }, duration)
    }

    return id
  }, [])

  const removeToast = useCallback((id) => {
    setToasts(prev => prev.filter(t => t.id !== id))
  }, [])

  // Shorthand methods
  const success = useCallback((message, options = {}) => {
    return addToast(message, { ...options, type: TOAST_TYPES.SUCCESS })
  }, [addToast])

  const info = useCallback((message, options = {}) => {
    return addToast(message, { ...options, type: TOAST_TYPES.INFO })
  }, [addToast])

  const warning = useCallback((message, options = {}) => {
    return addToast(message, { ...options, type: TOAST_TYPES.WARNING })
  }, [addToast])

  const error = useCallback((message, options = {}) => {
    return addToast(message, { ...options, type: TOAST_TYPES.ERROR })
  }, [addToast])

  return (
    <ToastContext.Provider value={{ addToast, removeToast, success, info, warning, error }}>
      {children}
      <ToastContainer toasts={toasts} onDismiss={removeToast} />
    </ToastContext.Provider>
  )
}

/**
 * Hook to use toast notifications
 */
export function useToast() {
  const context = useContext(ToastContext)
  if (!context) {
    // Return no-op functions when outside provider (for SSR compatibility)
    return {
      addToast: () => {},
      removeToast: () => {},
      success: () => {},
      info: () => {},
      warning: () => {},
      error: () => {}
    }
  }
  return context
}

/**
 * Toast Container Component
 */
function ToastContainer({ toasts, onDismiss }) {
  if (toasts.length === 0) return null

  return (
    <div
      className="fixed bottom-24 left-1/2 -translate-x-1/2 z-[100] flex flex-col gap-2 w-full max-w-sm px-4 pointer-events-none lg:bottom-8"
      aria-live="polite"
      aria-atomic="true"
    >
      {toasts.map((toast) => (
        <ToastItem
          key={toast.id}
          toast={toast}
          onDismiss={() => onDismiss(toast.id)}
        />
      ))}
    </div>
  )
}

/**
 * Individual Toast Item
 */
function ToastItem({ toast, onDismiss }) {
  const [isExiting, setIsExiting] = useState(false)

  const handleDismiss = () => {
    setIsExiting(true)
    setTimeout(onDismiss, 200)
  }

  // Get icon based on type
  const getIcon = () => {
    if (toast.icon) return toast.icon

    switch (toast.type) {
      case TOAST_TYPES.SUCCESS:
        return (
          <svg className="w-5 h-5 text-[var(--color-success)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M22 11.08V12a10 10 0 11-5.93-9.14"/>
            <polyline points="22 4 12 14.01 9 11.01"/>
          </svg>
        )
      case TOAST_TYPES.WARNING:
        return (
          <svg className="w-5 h-5 text-[var(--color-warning)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <circle cx="12" cy="12" r="10"/>
            <line x1="12" y1="8" x2="12" y2="12"/>
            <line x1="12" y1="16" x2="12.01" y2="16"/>
          </svg>
        )
      case TOAST_TYPES.ERROR:
        return (
          <svg className="w-5 h-5 text-[var(--color-error)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <circle cx="12" cy="12" r="10"/>
            <path d="M8 14s1.5 2 4 2 4-2 4-2"/>
            <line x1="9" y1="9" x2="9.01" y2="9"/>
            <line x1="15" y1="9" x2="15.01" y2="9"/>
          </svg>
        )
      case TOAST_TYPES.INFO:
      default:
        return (
          <svg className="w-5 h-5 text-[var(--color-info)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <circle cx="12" cy="12" r="10"/>
            <line x1="12" y1="16" x2="12" y2="12"/>
            <line x1="12" y1="8" x2="12.01" y2="8"/>
          </svg>
        )
    }
  }

  return (
    <div
      className={`toast-friendly ${toast.type} pointer-events-auto ${
        isExiting ? 'opacity-0 translate-y-2 transition-all duration-200' : ''
      }`}
      role="alert"
    >
      <span className="flex-shrink-0">{getIcon()}</span>
      <span className="flex-1 text-sm">{toast.message}</span>
      {toast.action && (
        <button
          onClick={() => {
            toast.action.onClick?.()
            handleDismiss()
          }}
          className="text-[var(--line-green)] text-sm font-medium hover:underline flex-shrink-0"
        >
          {toast.action.label}
        </button>
      )}
      <button
        onClick={handleDismiss}
        className="p-1 hover:bg-[var(--bg-secondary)] rounded-full transition-colors flex-shrink-0"
        aria-label="閉じる"
      >
        <svg className="w-4 h-4 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <line x1="18" y1="6" x2="6" y2="18"/>
          <line x1="6" y1="6" x2="18" y2="18"/>
        </svg>
      </button>
    </div>
  )
}

/**
 * 成功メッセージ（プリセット）
 */
export const TOAST_MESSAGES = {
  // 投稿関連
  POST_SUCCESS: '投稿しました',
  POST_DELETED: '投稿を削除しました',
  POST_FAILED: '投稿できませんでした。もう一度お試しください',

  // リアクション関連
  LIKE_SUCCESS: 'いいねしました',
  UNLIKE_SUCCESS: 'いいねを取り消しました',
  REPOST_SUCCESS: 'リポストしました',
  UNREPOST_SUCCESS: 'リポストを取り消しました',
  ZAP_SUCCESS: 'Zapを送りました',
  ZAP_FAILED: 'Zapを送れませんでした',

  // ユーザー関連
  FOLLOW_SUCCESS: 'フォローしました',
  UNFOLLOW_SUCCESS: 'フォロー解除しました',
  MUTE_SUCCESS: 'ミュートしました',
  UNMUTE_SUCCESS: 'ミュート解除しました',
  PROFILE_UPDATED: 'プロフィールを更新しました',

  // 接続関連
  CONNECTED: '接続しました',
  DISCONNECTED: '接続が切れました',
  RECONNECTING: '再接続中...',
  OFFLINE: 'オフラインです',
  SLOW_CONNECTION: '通信が遅いようです',

  // コピー関連
  COPIED: 'コピーしました',
  COPY_FAILED: 'コピーできませんでした',

  // 一般
  SAVED: '保存しました',
  LOADING: '読み込み中...',
  ERROR_GENERIC: '問題が発生しました。もう一度お試しください',
}

export default Toast
