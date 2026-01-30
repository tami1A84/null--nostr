'use client'

import { useState, useEffect } from 'react'
import { getUploadServer, setUploadServer } from '@/lib/nostr'

const UPLOAD_SERVERS = [
  { id: 'nostr.build', name: 'nostr.build', url: 'nostr.build' },
  { id: 'share.yabu.me', name: 'やぶみ', url: 'share.yabu.me' },
  { id: 'blossom.nostr', name: 'Blossom (nostr.build)', url: 'https://blossom.nostr.build' },
]

interface UploadSectionProps {
  expanded?: boolean
  onToggle?: () => void
}

export default function UploadSection({ expanded = false, onToggle }: UploadSectionProps) {
  const [showSettings, setShowSettings] = useState(expanded)
  const [uploadServerState, setUploadServerState] = useState('nostr.build')
  const [customBlossomUrl, setCustomBlossomUrl] = useState('')

  useEffect(() => {
    setUploadServerState(getUploadServer())
  }, [])

  const handleSelectUploadServer = (server: string) => {
    setUploadServerState(server)
    setUploadServer(server)
  }

  const handleSetCustomBlossom = () => {
    const url = customBlossomUrl.trim()
    if (url && url.startsWith('https://')) {
      setUploadServerState(url)
      setUploadServer(url)
      setCustomBlossomUrl('')
    }
  }

  const handleToggle = () => {
    setShowSettings(!showSettings)
    onToggle?.()
  }

  return (
    <section id="upload-section" className="bg-[var(--bg-secondary)] rounded-2xl p-4">
      <button
        onClick={handleToggle}
        className="w-full flex items-center justify-between"
      >
        <div className="flex items-center gap-2">
          <svg className="w-5 h-5 text-[var(--text-secondary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
            <rect x="3" y="3" width="18" height="18" rx="2" ry="2"/>
            <circle cx="8.5" cy="8.5" r="1.5"/>
            <polyline points="21 15 16 10 5 21"/>
          </svg>
          <h2 className="font-semibold text-[var(--text-primary)]">画像アップロード</h2>
        </div>
        <svg className={`w-5 h-5 text-[var(--text-tertiary)] transition-transform ${showSettings ? 'rotate-180' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <polyline points="6 9 12 15 18 9"/>
        </svg>
      </button>

      {showSettings && (
        <div className="mt-4 space-y-3">
          <p className="text-xs text-[var(--text-tertiary)]">
            プロフィール画像のアップロード先
          </p>

          {/* Preset servers */}
          <div className="space-y-2">
            {UPLOAD_SERVERS.map(server => (
              <button
                key={server.id}
                onClick={() => handleSelectUploadServer(server.url)}
                className={`w-full flex items-center justify-between p-3 rounded-xl transition-all ${
                  uploadServerState === server.url
                    ? 'bg-[var(--line-green)] text-white'
                    : 'bg-[var(--bg-tertiary)] text-[var(--text-primary)]'
                }`}
              >
                <span className="text-sm font-medium">{server.name}</span>
                {uploadServerState === server.url && (
                  <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <polyline points="20 6 9 17 4 12"/>
                  </svg>
                )}
              </button>
            ))}
          </div>

          {/* Custom Blossom URL */}
          <div className="pt-2">
            <p className="text-xs text-[var(--text-tertiary)] mb-2">
              カスタムBlossomサーバー
            </p>
            <div className="flex gap-2">
              <input
                type="text"
                value={customBlossomUrl}
                onChange={(e) => setCustomBlossomUrl(e.target.value)}
                placeholder="https://..."
                className="flex-1 input-line text-sm"
              />
              <button
                onClick={handleSetCustomBlossom}
                className="btn-line text-sm px-3"
              >
                設定
              </button>
            </div>
          </div>

          <p className="text-xs text-[var(--text-tertiary)] pt-2">
            現在: {uploadServerState}
          </p>
        </div>
      )}
    </section>
  )
}
