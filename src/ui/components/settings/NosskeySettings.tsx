'use client'

import { useState, useEffect } from 'react'
import { nip19 } from 'nostr-tools'

interface NosskeySettingsProps {
  pubkey: string
}

export default function NosskeySettings({ pubkey }: NosskeySettingsProps) {
  const [showSettings, setShowSettings] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [exportedNsec, setExportedNsec] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const [autoSign, setAutoSign] = useState(true)
  const [hasExportedKey, setHasExportedKey] = useState(false)

  useEffect(() => {
    if (typeof window !== 'undefined') {
      if ((window as any).nostrPrivateKey) {
        setHasExportedKey(true)
      }
      const savedAutoSign = localStorage.getItem('nurunuru_auto_sign')
      setAutoSign(savedAutoSign !== 'false')
    }
  }, [])

  const hexToBytes = (hex: string): Uint8Array => {
    const bytes = new Uint8Array(hex.length / 2)
    for (let i = 0; i < hex.length; i += 2) {
      bytes[i / 2] = parseInt(hex.substr(i, 2), 16)
    }
    return bytes
  }

  const handleAutoSignChange = (enabled: boolean) => {
    setAutoSign(enabled)
    localStorage.setItem('nurunuru_auto_sign', enabled ? 'true' : 'false')
  }

  const handleExportKey = async () => {
    if (!(window as any).nosskeyManager) {
      alert('Nosskeyマネージャーが見つかりません。再ログインしてください。')
      return
    }

    setExporting(true)
    try {
      const manager = (window as any).nosskeyManager
      const keyInfo = manager.getCurrentKeyInfo()

      if (!keyInfo) {
        throw new Error('鍵情報が見つかりません。再ログインしてください。')
      }

      manager.setCacheOptions({ enabled: true, timeoutMs: 3600000 })
      const privateKeyHex = await manager.exportNostrKey(keyInfo)

      if (!privateKeyHex) {
        throw new Error('秘密鍵を取得できませんでした')
      }

      const nsec = nip19.nsecEncode(hexToBytes(privateKeyHex))
      setExportedNsec(nsec)
      ;(window as any).nostrPrivateKey = privateKeyHex
      setHasExportedKey(true)

    } catch (e: any) {
      console.error('Export error:', e)
      if (e.name === 'NotAllowedError') {
        alert('パスキー認証がキャンセルされました')
      } else {
        alert('秘密鍵のエクスポートに失敗しました: ' + e.message)
      }
    } finally {
      setExporting(false)
    }
  }

  const handleCopyNsec = async () => {
    if (!exportedNsec) return
    try {
      await navigator.clipboard.writeText(exportedNsec)
      setCopied(true)
      setTimeout(() => setCopied(false), 3000)
    } catch (e) {
      const textarea = document.createElement('textarea')
      textarea.value = exportedNsec
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
      setCopied(true)
      setTimeout(() => setCopied(false), 3000)
    }
  }

  const handleCloseExport = () => {
    setExportedNsec(null)
    setCopied(false)
  }

  return (
    <section className="bg-[var(--bg-secondary)] rounded-2xl p-4">
      <button
        onClick={() => setShowSettings(!showSettings)}
        className="w-full flex items-center justify-between"
      >
        <div className="flex items-center gap-2">
          <svg className="w-5 h-5 text-[var(--text-secondary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
            <path d="M12 2a4 4 0 014 4v2h2a2 2 0 012 2v10a2 2 0 01-2 2H6a2 2 0 01-2-2V10a2 2 0 012-2h2V6a4 4 0 014-4z"/>
            <circle cx="12" cy="15" r="1"/>
          </svg>
          <h2 className="font-semibold text-[var(--text-primary)]">パスキー設定</h2>
        </div>
        <svg className={`w-5 h-5 text-[var(--text-tertiary)] transition-transform ${showSettings ? 'rotate-180' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <polyline points="6 9 12 15 18 9"/>
        </svg>
      </button>

      {showSettings && (
        <div className="mt-4 space-y-4">
          {/* Auto Sign Setting */}
          <div className="bg-[var(--bg-tertiary)] p-3 rounded-xl">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm font-medium text-[var(--text-primary)]">自動署名</p>
                <p className="text-xs text-[var(--text-tertiary)]">
                  {hasExportedKey
                    ? (autoSign ? '投稿時に生体認証なし' : '毎回生体認証を要求')
                    : '秘密鍵をエクスポートすると有効化'}
                </p>
              </div>
              <button
                onClick={() => handleAutoSignChange(!autoSign)}
                disabled={!hasExportedKey}
                className={`relative w-12 h-6 rounded-full transition-colors ${
                  autoSign && hasExportedKey ? 'bg-[var(--line-green)]' : 'bg-[var(--border-color)]'
                } ${!hasExportedKey ? 'opacity-50' : ''}`}
              >
                <span className={`absolute top-0.5 left-0.5 w-5 h-5 bg-white rounded-full shadow transition-transform ${
                  autoSign && hasExportedKey ? 'translate-x-6' : 'translate-x-0'
                }`} />
              </button>
            </div>
          </div>

          {/* Export Key */}
          {!exportedNsec ? (
            <div>
              <p className="text-xs text-[var(--text-tertiary)] mb-3">
                秘密鍵をエクスポートして他のアプリで使用できます。
              </p>
              <button
                onClick={handleExportKey}
                disabled={exporting}
                className="w-full py-3 text-sm disabled:opacity-50 btn-secondary"
              >
                {exporting ? (
                  <span className="flex items-center justify-center gap-2">
                    <svg className="w-4 h-4 animate-spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <circle cx="12" cy="12" r="10" strokeOpacity="0.25"/>
                      <path d="M12 2a10 10 0 019.5 7" strokeLinecap="round"/>
                    </svg>
                    認証中...
                  </span>
                ) : (
                  <span className="flex items-center justify-center gap-2">
                    <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/>
                      <polyline points="17 8 12 3 7 8"/>
                      <line x1="12" y1="3" x2="12" y2="15"/>
                    </svg>
                    秘密鍵を表示
                  </span>
                )}
              </button>
            </div>
          ) : (
            <div className="space-y-3">
              <div className="bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 p-3 rounded-xl">
                <p className="text-xs text-red-600 dark:text-red-400 font-medium">
                  ⚠️ 秘密鍵は他人に見せないでください
                </p>
              </div>

              <div className="bg-[var(--bg-tertiary)] p-3 rounded-xl">
                <p className="text-xs text-[var(--text-tertiary)] mb-2">秘密鍵 (nsec)</p>
                <div className="bg-[var(--bg-primary)] p-2 rounded-lg border border-[var(--border-color)]">
                  <p className="text-xs font-mono text-[var(--text-primary)] break-all select-all">
                    {exportedNsec}
                  </p>
                </div>
              </div>

              <div className="flex gap-2">
                <button
                  onClick={handleCopyNsec}
                  className={`flex-1 py-2 rounded-lg text-sm font-medium transition-all ${
                    copied
                      ? 'bg-[var(--line-green)] text-white'
                      : 'btn-secondary'
                  }`}
                >
                  {copied ? '✓ コピーしました' : 'コピー'}
                </button>
                <button
                  onClick={handleCloseExport}
                  className="flex-1 btn-secondary py-2 text-sm"
                >
                  閉じる
                </button>
              </div>
            </div>
          )}
        </div>
      )}
    </section>
  )
}
