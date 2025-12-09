'use client'

import { useState, useEffect } from 'react'
import { hasNip07, getNip07PublicKey, savePubkey } from '@/lib/nostr'

export default function LoginScreen({ onLogin }) {
  const [checking, setChecking] = useState(true)
  const [hasExtension, setHasExtension] = useState(false)
  const [connecting, setConnecting] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    // Wait a bit for extension to load
    const timer = setTimeout(() => {
      setHasExtension(hasNip07())
      setChecking(false)
    }, 500)
    return () => clearTimeout(timer)
  }, [])

  const handleConnect = async () => {
    setError('')
    setConnecting(true)
    
    try {
      const pubkey = await getNip07PublicKey()
      savePubkey(pubkey)
      onLogin(pubkey)
    } catch (e) {
      setError(e.message || '接続に失敗しました')
    } finally {
      setConnecting(false)
    }
  }

  return (
    <div className="min-h-screen flex flex-col bg-[var(--bg-primary)]">
      {/* Header space */}
      <div className="flex-1 flex flex-col items-center justify-center px-6 py-12">
        {/* Logo */}
        <div className="mb-8 text-center animate-fadeIn">
          <div className="w-32 h-32 mx-auto mb-6 rounded-2xl overflow-hidden shadow-lg">
            <img 
              src="/favicon-512.png" 
              alt="ぬるぬる" 
              className="w-full h-full object-cover"
            />
          </div>
          <h1 className="text-4xl font-bold text-[var(--text-primary)] tracking-tight">
            ぬるぬる
          </h1>
        </div>

        {/* Status area */}
        <div className="w-full max-w-sm space-y-4">
          {checking ? (
            <div className="text-center animate-fadeIn">
              <div className="w-8 h-8 border-2 border-[var(--line-green)] border-t-transparent rounded-full animate-spin mx-auto mb-3"/>
              <p className="text-[var(--text-secondary)] text-sm">確認中...</p>
            </div>
          ) : hasExtension ? (
            <div className="animate-slideUp">
              <button
                onClick={handleConnect}
                disabled={connecting}
                className="w-full btn-line text-base py-4 disabled:opacity-50"
              >
                {connecting ? (
                  <span className="flex items-center justify-center gap-2">
                    <svg className="w-5 h-5 animate-spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <circle cx="12" cy="12" r="10" strokeOpacity="0.25"/>
                      <path d="M12 2a10 10 0 019.5 7" strokeLinecap="round"/>
                    </svg>
                    接続中...
                  </span>
                ) : (
                  <span className="flex items-center justify-center gap-2">
                    <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/>
                      <path d="M7 11V7a5 5 0 0110 0v4"/>
                    </svg>
                    拡張機能でログイン
                  </span>
                )}
              </button>
              
              {error && (
                <div className="mt-4 p-3 rounded-lg bg-red-50 dark:bg-red-900/20 animate-scaleIn">
                  <p className="text-sm text-red-600 dark:text-red-400 text-center">{error}</p>
                </div>
              )}
              
              <p className="mt-4 text-center text-xs text-[var(--text-tertiary)]">
                Alby、nos2x等の拡張機能が必要です
              </p>
            </div>
          ) : (
            <div className="animate-slideUp">
              <div className="card p-6 text-center">
                <div className="w-16 h-16 mx-auto mb-4 rounded-full bg-[var(--bg-tertiary)] flex items-center justify-center">
                  <svg className="w-8 h-8 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/>
                    <line x1="12" y1="9" x2="12" y2="13"/>
                    <line x1="12" y1="17" x2="12.01" y2="17"/>
                  </svg>
                </div>
                <h3 className="font-semibold text-[var(--text-primary)] mb-2">
                  拡張機能が見つかりません
                </h3>
                <p className="text-sm text-[var(--text-secondary)] mb-4">
                  Nostr拡張機能をインストールしてください
                </p>
                <div className="space-y-2">
                  <a
                    href="https://getalby.com"
                    target="_blank"
                    rel="noopener noreferrer"
                    className="block w-full btn-secondary text-sm py-3"
                  >
                    Alby を入手
                  </a>
                  <a
                    href="https://github.com/nickreynolds/nos2x"
                    target="_blank"
                    rel="noopener noreferrer"
                    className="block w-full btn-secondary text-sm py-3"
                  >
                    nos2x を入手
                  </a>
                </div>
              </div>
              
              <button
                onClick={() => setHasExtension(hasNip07())}
                className="w-full mt-4 text-[var(--text-secondary)] text-sm py-2"
              >
                再確認する
              </button>
            </div>
          )}
        </div>
      </div>

      {/* Footer */}
      <div className="py-6 text-center animate-fadeIn">
        <p className="text-xs text-[var(--text-tertiary)]">
          Powered by Nostr
        </p>
      </div>
    </div>
  )
}
