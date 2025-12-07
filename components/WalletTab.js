'use client'

import { useState, useEffect } from 'react'
import { parseNWCUrl, saveNWC, loadNWC, clearNWC } from '@/lib/nostr'

export default function WalletTab({ nwcUrl, onNWCChange }) {
  const [inputUrl, setInputUrl] = useState('')
  const [isConnected, setIsConnected] = useState(false)
  const [connectionInfo, setConnectionInfo] = useState(null)
  const [showInput, setShowInput] = useState(false)

  useEffect(() => {
    if (nwcUrl) {
      const info = parseNWCUrl(nwcUrl)
      if (info) {
        setIsConnected(true)
        setConnectionInfo(info)
      }
    }
  }, [nwcUrl])

  const handleConnect = () => {
    const trimmedUrl = inputUrl.trim()
    
    if (!trimmedUrl.startsWith('nostr+walletconnect://')) {
      alert('無効なNWC URLです')
      return
    }

    const info = parseNWCUrl(trimmedUrl)
    if (!info) {
      alert('NWC URLの解析に失敗しました')
      return
    }

    saveNWC(trimmedUrl)
    setIsConnected(true)
    setConnectionInfo(info)
    setShowInput(false)
    onNWCChange?.(trimmedUrl)
  }

  const handleDisconnect = () => {
    clearNWC()
    setIsConnected(false)
    setConnectionInfo(null)
    setInputUrl('')
    onNWCChange?.(null)
  }

  return (
    <div className="min-h-screen pb-16">
      {/* Header */}
      <header className="sticky top-0 z-40 header-blur border-b border-[var(--border-color)]">
        <div className="flex items-center justify-between px-4 h-12">
          <h1 className="text-lg font-semibold text-[var(--text-primary)]">ウォレット</h1>
        </div>
      </header>

      <div className="p-4">
        {/* NWC Card */}
        <div className="card p-5 mb-5 animate-fadeIn">
          <div className="flex items-center gap-4 mb-4">
            <div className="w-14 h-14 rounded-2xl bg-gradient-to-br from-yellow-400 to-orange-500 flex items-center justify-center shadow-lg shadow-orange-500/20">
              <svg className="w-7 h-7 text-white" viewBox="0 0 24 24" fill="currentColor">
                <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
              </svg>
            </div>
            <div>
              <h2 className="font-bold text-[var(--text-primary)]">Nostr Wallet Connect</h2>
              <p className="text-sm text-[var(--text-secondary)]">Lightning でZapを送信</p>
            </div>
          </div>
          <p className="text-sm text-[var(--text-secondary)] leading-relaxed">
            NWCでLightningウォレットを接続すると、タイムラインの投稿にZapを送れます。
          </p>
        </div>

        {/* Connection Status */}
        {isConnected ? (
          <div className="card p-5 mb-5 animate-scaleIn">
            <div className="flex items-center justify-between mb-4">
              <div className="flex items-center gap-2">
                <div className="w-2.5 h-2.5 rounded-full bg-green-500"/>
                <span className="font-semibold text-[var(--text-primary)]">接続中</span>
              </div>
              <button
                onClick={handleDisconnect}
                className="text-sm text-red-500 action-btn"
              >
                切断
              </button>
            </div>

            <div className="space-y-3 text-sm">
              <div className="flex justify-between items-start">
                <span className="text-[var(--text-secondary)]">Wallet</span>
                <span className="text-[var(--text-primary)] font-mono text-xs truncate max-w-[180px]">
                  {connectionInfo?.walletPubkey?.slice(0, 12)}...
                </span>
              </div>
              <div className="flex justify-between items-start">
                <span className="text-[var(--text-secondary)]">Relay</span>
                <span className="text-[var(--text-primary)] font-mono text-xs truncate max-w-[180px]">
                  {connectionInfo?.relay}
                </span>
              </div>
            </div>
          </div>
        ) : (
          <>
            {!showInput ? (
              <button
                onClick={() => setShowInput(true)}
                className="w-full card p-4 flex items-center gap-4 list-item animate-fadeIn"
              >
                <div className="w-12 h-12 rounded-xl bg-[var(--line-green)]/10 flex items-center justify-center">
                  <svg className="w-6 h-6 text-[var(--line-green)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M10 13a5 5 0 007.54.54l3-3a5 5 0 00-7.07-7.07l-1.72 1.71"/>
                    <path d="M14 11a5 5 0 00-7.54-.54l-3 3a5 5 0 007.07 7.07l1.71-1.71"/>
                  </svg>
                </div>
                <div className="flex-1 text-left">
                  <h3 className="font-semibold text-[var(--text-primary)]">ウォレットを接続</h3>
                  <p className="text-sm text-[var(--text-secondary)]">NWC URLを入力</p>
                </div>
                <svg className="w-5 h-5 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <polyline points="9 18 15 12 9 6"/>
                </svg>
              </button>
            ) : (
              <div className="card p-5 animate-scaleIn">
                <h3 className="font-semibold text-[var(--text-primary)] mb-4">NWC URLを入力</h3>
                <textarea
                  value={inputUrl}
                  onChange={(e) => setInputUrl(e.target.value)}
                  className="input-line resize-none h-24 mb-4 font-mono text-xs"
                  placeholder="nostr+walletconnect://..."
                />
                <div className="flex gap-3">
                  <button
                    onClick={() => setShowInput(false)}
                    className="flex-1 btn-secondary"
                  >
                    キャンセル
                  </button>
                  <button
                    onClick={handleConnect}
                    className="flex-1 btn-line"
                  >
                    接続
                  </button>
                </div>
              </div>
            )}
          </>
        )}

        {/* Supported Wallets */}
        <div className="mt-6">
          <h3 className="text-sm font-semibold text-[var(--text-secondary)] mb-3 px-1">対応ウォレット</h3>
          <div className="grid grid-cols-2 gap-3">
            {[
              { name: 'Alby', color: '#FFD93D', url: 'https://getalby.com' },
              { name: 'Mutiny', color: '#E53E3E', url: 'https://mutinywallet.com' },
              { name: 'Primal', color: '#8B5CF6', url: 'https://primal.net' },
              { name: 'Coinos', color: '#3B82F6', url: 'https://coinos.io' },
            ].map((wallet, index) => (
              <a
                key={wallet.name}
                href={wallet.url}
                target="_blank"
                rel="noopener noreferrer"
                className="card p-4 flex items-center gap-3 list-item animate-fadeIn"
                style={{ animationDelay: `${index * 50}ms` }}
              >
                <div 
                  className="w-10 h-10 rounded-xl flex items-center justify-center"
                  style={{ backgroundColor: wallet.color + '20' }}
                >
                  <svg className="w-5 h-5" style={{ color: wallet.color }} viewBox="0 0 24 24" fill="currentColor">
                    <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
                  </svg>
                </div>
                <span className="text-sm font-medium text-[var(--text-primary)]">{wallet.name}</span>
              </a>
            ))}
          </div>
        </div>

        {/* Help */}
        <div className="mt-6 p-4 rounded-xl bg-[var(--bg-secondary)] animate-fadeIn stagger-3">
          <h4 className="font-semibold text-[var(--text-primary)] mb-3 flex items-center gap-2">
            <svg className="w-5 h-5 text-[var(--line-green)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="12" cy="12" r="10"/>
              <path d="M9.09 9a3 3 0 015.83 1c0 2-3 3-3 3"/>
              <line x1="12" y1="17" x2="12.01" y2="17"/>
            </svg>
            接続方法
          </h4>
          <ol className="text-sm text-[var(--text-secondary)] space-y-2">
            <li className="flex gap-2">
              <span className="text-[var(--line-green)] font-semibold">1.</span>
              対応ウォレットでNWC接続を作成
            </li>
            <li className="flex gap-2">
              <span className="text-[var(--line-green)] font-semibold">2.</span>
              生成されたURLをコピー
            </li>
            <li className="flex gap-2">
              <span className="text-[var(--line-green)] font-semibold">3.</span>
              上の「接続」から貼り付け
            </li>
          </ol>
        </div>
      </div>
    </div>
  )
}
