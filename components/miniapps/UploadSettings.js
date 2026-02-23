'use client'

import { useState, useEffect } from 'react'
import { getUploadServer, setUploadServer } from '@/lib/nostr'

const UPLOAD_SERVERS = [
  { id: 'nostr.build', name: 'nostr.build', url: 'nostr.build' },
  { id: 'share.yabu.me', name: 'やぶみ', url: 'share.yabu.me' },
  { id: 'blossom.nostr', name: 'Blossom (nostr.build)', url: 'https://blossom.nostr.build' },
]

export default function UploadSettings() {
  const [uploadServerState, setUploadServerState] = useState('nostr.build')
  const [customBlossomUrl, setCustomBlossomUrl] = useState('')

  useEffect(() => {
    setUploadServerState(getUploadServer())
  }, [])

  const handleSelectUploadServer = (server) => {
    setUploadServerState(server)
    setUploadServer(server)
  }

  const handleSetCustomBlossom = () => {
    const url = customBlossomUrl.trim()
    if (url && url.startsWith('https://')) {
      handleSelectUploadServer(url)
      setCustomBlossomUrl('')
    }
  }

  return (
    <div className="space-y-4">
      <div className="p-4 bg-[var(--bg-secondary)] rounded-2xl">
        <h3 className="text-lg font-semibold text-[var(--text-primary)] mb-4">画像アップロード</h3>
        <p className="text-xs text-[var(--text-tertiary)] mb-4">
          プロフィール画像のアップロード先
        </p>

        <div className="space-y-2 mb-6">
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

        <div className="space-y-2">
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
      </div>
      <p className="text-xs text-[var(--text-tertiary)] px-2">
        現在: {uploadServerState}
      </p>
    </div>
  )
}
