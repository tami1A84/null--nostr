'use client'

import React from 'react'

/**
 * Error Boundary Component
 *
 * Nintendo-style: 失敗しても責められているように感じない設計
 * - 強い赤色を避ける
 * - 励ましのメッセージ
 * - 何度でも試せる安心感
 *
 * @class ErrorBoundary
 * @extends {React.Component}
 */

// 励ましのメッセージリスト
const ENCOURAGING_MESSAGES = [
  'ちょっと休憩してから、もう一度試してみましょう',
  '大丈夫、もう一度試せばきっとうまくいきます',
  '一時的な問題かもしれません。少し待ってからお試しください',
  'アプリを更新中かもしれません。少々お待ちください',
]

// ヒントメッセージ
const HELPFUL_HINTS = [
  'インターネット接続を確認してみてください',
  'アプリを再起動すると解決することがあります',
  'しばらく待ってから再試行してみてください',
]

class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props)
    this.state = {
      hasError: false,
      error: null,
      errorInfo: null,
      messageIndex: 0,
      hintIndex: 0
    }
  }

  static getDerivedStateFromError(error) {
    // Update state so the next render shows the fallback UI
    return {
      hasError: true,
      error,
      messageIndex: Math.floor(Math.random() * ENCOURAGING_MESSAGES.length),
      hintIndex: Math.floor(Math.random() * HELPFUL_HINTS.length)
    }
  }

  componentDidCatch(error, errorInfo) {
    // Log error details for debugging
    console.error('ErrorBoundary caught an error:', error, errorInfo)
    this.setState({ errorInfo })

    // You could also send this to an error reporting service
    // logErrorToService(error, errorInfo)
  }

  handleRetry = () => {
    this.setState({ hasError: false, error: null, errorInfo: null })
  }

  handleReload = () => {
    window.location.reload()
  }

  render() {
    if (this.state.hasError) {
      // Custom fallback UI if provided
      if (this.props.fallback) {
        return this.props.fallback
      }

      const encouragingMessage = ENCOURAGING_MESSAGES[this.state.messageIndex]
      const helpfulHint = HELPFUL_HINTS[this.state.hintIndex]

      // Default fallback UI - Nintendo-style friendly design
      return (
        <div className="min-h-screen flex flex-col items-center justify-center p-6 bg-[var(--bg-primary)]">
          <div className="w-full max-w-md text-center animate-fadeIn">
            {/* Friendly icon - 優しい青色、警告感を軽減 */}
            <div className="error-friendly-icon">
              <svg
                className="w-10 h-10"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.5"
                strokeLinecap="round"
                strokeLinejoin="round"
                aria-hidden="true"
              >
                <circle cx="12" cy="12" r="10" />
                <path d="M8 14s1.5 2 4 2 4-2 4-2" />
                <line x1="9" y1="9" x2="9.01" y2="9" />
                <line x1="15" y1="9" x2="15.01" y2="9" />
              </svg>
            </div>

            <h1 className="error-friendly-title">
              うまくいきませんでした
            </h1>
            <p className="error-friendly-message">
              {encouragingMessage}
            </p>

            {/* Helpful hint - 柔らかい背景色 */}
            <div className="error-friendly-hint">
              💡 {helpfulHint}
            </div>

            {/* Error details (development only) */}
            {process.env.NODE_ENV === 'development' && this.state.error && (
              <details className="mb-6 text-left">
                <summary className="text-xs text-[var(--text-tertiary)] cursor-pointer hover:text-[var(--text-secondary)] mb-2">
                  開発者向け情報を表示
                </summary>
                <div className="p-3 bg-[var(--bg-secondary)] rounded-lg">
                  <p className="text-xs font-mono text-[var(--color-error)] break-all">
                    {this.state.error.toString()}
                  </p>
                  {this.state.errorInfo?.componentStack && (
                    <pre className="mt-2 text-xs font-mono text-[var(--text-tertiary)] overflow-x-auto whitespace-pre-wrap max-h-32 overflow-y-auto">
                      {this.state.errorInfo.componentStack}
                    </pre>
                  )}
                </div>
              </details>
            )}

            <div className="flex gap-3 justify-center">
              <button
                onClick={this.handleRetry}
                className="retry-button"
                aria-label="もう一度試す"
              >
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <polyline points="23 4 23 10 17 10"/>
                  <path d="M20.49 15a9 9 0 11-2.12-9.36L23 10"/>
                </svg>
                もう一度試す
              </button>
              <button
                onClick={this.handleReload}
                className="btn-line px-4 py-2"
                aria-label="最初からやり直す"
              >
                最初からやり直す
              </button>
            </div>

            {/* 安心メッセージ */}
            <p className="mt-6 text-xs text-[var(--text-tertiary)]">
              ご不便をおかけして申し訳ありません
            </p>
          </div>
        </div>
      )
    }

    return this.props.children
  }
}

/**
 * 軽量なエラー表示コンポーネント
 * インライン表示用
 */
export function InlineError({ message, onRetry }) {
  return (
    <div className="p-4 text-center">
      <p className="text-sm text-[var(--text-secondary)] mb-3">
        {message || '読み込めませんでした'}
      </p>
      {onRetry && (
        <button
          onClick={onRetry}
          className="retry-button text-sm"
        >
          <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <polyline points="23 4 23 10 17 10"/>
            <path d="M20.49 15a9 9 0 11-2.12-9.36L23 10"/>
          </svg>
          再試行
        </button>
      )}
    </div>
  )
}

/**
 * ネットワークエラー用の専用コンポーネント
 */
export function NetworkError({ onRetry }) {
  return (
    <div className="error-friendly p-6">
      <div className="error-friendly-icon">
        <svg className="w-10 h-10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
          <path d="M1 1l22 22M16.72 11.06A10.94 10.94 0 0119 12.55M5 12.55a10.94 10.94 0 015.17-2.39M10.71 5.05A16 16 0 0122.58 9M1.42 9a15.91 15.91 0 014.7-2.88M8.53 16.11a6 6 0 016.95 0M12 20h.01"/>
        </svg>
      </div>
      <h2 className="error-friendly-title">接続できません</h2>
      <p className="error-friendly-message">
        インターネット接続を確認して、もう一度お試しください
      </p>
      <button onClick={onRetry} className="btn-line mt-4">
        再接続する
      </button>
    </div>
  )
}

/**
 * Functional wrapper for ErrorBoundary with hooks support
 *
 * @param {Object} props
 * @param {React.ReactNode} props.children - Child components to wrap
 * @param {React.ReactNode} [props.fallback] - Custom fallback UI
 * @returns {JSX.Element}
 */
export function ErrorBoundaryWrapper({ children, fallback }) {
  return (
    <ErrorBoundary fallback={fallback}>
      {children}
    </ErrorBoundary>
  )
}

/**
 * HOC to wrap a component with ErrorBoundary
 *
 * @param {React.ComponentType} Component - Component to wrap
 * @param {React.ReactNode} [fallback] - Custom fallback UI
 * @returns {React.ComponentType}
 */
export function withErrorBoundary(Component, fallback) {
  return function WrappedComponent(props) {
    return (
      <ErrorBoundary fallback={fallback}>
        <Component {...props} />
      </ErrorBoundary>
    )
  }
}

export default ErrorBoundary
