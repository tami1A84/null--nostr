'use client'

import React from 'react'
import type { LoginMethod } from '../../types'

interface AccountSectionProps {
  loginMethod: LoginMethod | null
  onLogout: () => void
}

/**
 * Account section showing login method and logout button
 */
export function AccountSection({ loginMethod, onLogout }: AccountSectionProps) {
  const handleLogoutClick = () => {
    if (confirm('ログアウトしますか？')) {
      onLogout()
    }
  }

  const { icon, title, description } = getLoginMethodInfo(loginMethod)

  return (
    <section className="bg-[var(--bg-secondary)] rounded-2xl p-4">
      <div className="flex items-center gap-3">
        <div className="w-10 h-10 rounded-full bg-[var(--line-green)] flex items-center justify-center flex-shrink-0">
          {icon}
        </div>
        <div className="flex-1 min-w-0">
          <p className="font-medium text-[var(--text-primary)]">{title}</p>
          <p className="text-xs text-[var(--text-tertiary)]">{description}</p>
        </div>
        <button
          onClick={handleLogoutClick}
          className="px-3 py-1.5 text-xs bg-red-100 dark:bg-red-900/30 text-red-600 dark:text-red-400 rounded-full hover:bg-red-200 dark:hover:bg-red-900/50 transition-colors flex-shrink-0"
        >
          ログアウト
        </button>
      </div>
    </section>
  )
}

function getLoginMethodInfo(method: LoginMethod | null) {
  switch (method) {
    case 'nosskey':
      return {
        icon: <PasskeyIcon className="w-5 h-5 text-white" />,
        title: 'パスキーでログイン中',
        description: 'Face ID / Touch ID / Windows Hello'
      }
    case 'extension':
      return {
        icon: <ExtensionIcon className="w-5 h-5 text-white" />,
        title: '拡張機能でログイン中',
        description: 'Alby / nos2x'
      }
    case 'readOnly':
      return {
        icon: <ViewIcon className="w-5 h-5 text-white" />,
        title: '読み取り専用モード',
        description: '投稿・署名はできません'
      }
    case 'local':
      return {
        icon: <KeyIcon className="w-5 h-5 text-white" />,
        title: 'ローカルキーでログイン中',
        description: 'ブラウザに秘密鍵を保存'
      }
    case 'connect':
      return {
        icon: <ConnectIcon className="w-5 h-5 text-white" />,
        title: 'Nostr Connectでログイン中',
        description: 'Nostr Connect / リモート署名'
      }
    case 'amber':
      return {
        icon: <AmberIcon className="w-5 h-5 text-white" />,
        title: 'Amberでログイン中',
        description: 'Android署名アプリ'
      }
    default:
      return {
        icon: <CheckIcon className="w-5 h-5 text-white" />,
        title: 'ログイン中',
        description: ''
      }
  }
}

// Icons
function PasskeyIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 2a4 4 0 014 4v2h2a2 2 0 012 2v10a2 2 0 01-2 2H6a2 2 0 01-2-2V10a2 2 0 012-2h2V6a4 4 0 014-4z" />
      <circle cx="12" cy="15" r="1" />
    </svg>
  )
}

function ExtensionIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
      <path d="M7 11V7a5 5 0 0110 0v4" />
    </svg>
  )
}

function ViewIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
      <circle cx="12" cy="12" r="3" />
    </svg>
  )
}

function KeyIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 11-7.778 7.778 5.5 5.5 0 017.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4" />
    </svg>
  )
}

function ConnectIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" />
      <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />
    </svg>
  )
}

function AmberIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <rect x="5" y="2" width="14" height="20" rx="2" ry="2" />
      <line x1="12" y1="18" x2="12.01" y2="18" />
    </svg>
  )
}

function CheckIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="10" />
      <path d="M8 12l2 2 4-4" />
    </svg>
  )
}

export default AccountSection
