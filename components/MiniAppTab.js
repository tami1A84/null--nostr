'use client'

import { useState, useEffect } from 'react'
import {
  fetchMuteList,
  removeFromMuteList,
  fetchEvents,
  parseProfile,
  shortenPubkey,
  getReadRelays,
  getWriteRelays,
  setReadRelays,
  setWriteRelays,
  getUploadServer,
  setUploadServer,
  RELAYS
} from '@/lib/nostr'

// Default zap amounts for quick select
const ZAP_PRESETS = [21, 100, 500, 1000, 5000, 10000]

// Default relay presets
const DEFAULT_READ_RELAY = 'wss://yabu.me'
const DEFAULT_WRITE_RELAYS = [
  'wss://relay-jp.nostr.wirednet.jp',
  'wss://yabu.me',
  'wss://r.kojira.io'
]

// Upload server presets
const UPLOAD_SERVERS = [
  { id: 'nostr.build', name: 'nostr.build', url: 'nostr.build' },
  { id: 'blossom.primal', name: 'Blossom (Primal)', url: 'https://blossom.primal.net' },
  { id: 'blossom.nostr', name: 'Blossom (nostr.build)', url: 'https://blossom.nostr.build' },
]

export default function MiniAppTab({ pubkey }) {
  const [defaultZap, setDefaultZap] = useState(21)
  const [muteList, setMuteList] = useState({ pubkeys: [], eventIds: [], hashtags: [], words: [] })
  const [mutedProfiles, setMutedProfiles] = useState({})
  const [loading, setLoading] = useState(true)
  const [removing, setRemoving] = useState(null)
  const [showZapInput, setShowZapInput] = useState(false)
  const [customZap, setCustomZap] = useState('')
  
  // Relay settings
  const [readRelays, setReadRelaysState] = useState([])
  const [writeRelays, setWriteRelaysState] = useState([])
  const [newReadRelay, setNewReadRelay] = useState('')
  const [newWriteRelay, setNewWriteRelay] = useState('')
  const [showRelaySettings, setShowRelaySettings] = useState(false)
  
  // Upload server settings
  const [uploadServerState, setUploadServerState] = useState('nostr.build')
  const [customBlossomUrl, setCustomBlossomUrl] = useState('')
  const [showUploadSettings, setShowUploadSettings] = useState(false)

  useEffect(() => {
    // Load saved default zap from localStorage
    const saved = localStorage.getItem('defaultZapAmount')
    if (saved) {
      setDefaultZap(parseInt(saved, 10))
    }

    // Load relay settings
    setReadRelaysState(getReadRelays())
    setWriteRelaysState(getWriteRelays())
    
    // Load upload server setting
    setUploadServerState(getUploadServer())

    if (pubkey) {
      loadMuteList()
    } else {
      setLoading(false)
    }
  }, [pubkey])

  const loadMuteList = async () => {
    setLoading(true)
    try {
      const list = await fetchMuteList(pubkey)
      setMuteList(list)

      // Fetch profiles for muted pubkeys
      if (list.pubkeys.length > 0) {
        const profileEvents = await fetchEvents(
          { kinds: [0], authors: list.pubkeys, limit: list.pubkeys.length },
          RELAYS
        )
        const profiles = {}
        for (const event of profileEvents) {
          profiles[event.pubkey] = parseProfile(event)
        }
        setMutedProfiles(profiles)
      }
    } catch (e) {
      console.error('Failed to load mute list:', e)
    } finally {
      setLoading(false)
    }
  }

  const handleSetDefaultZap = (amount) => {
    setDefaultZap(amount)
    localStorage.setItem('defaultZapAmount', amount.toString())
    setShowZapInput(false)
  }

  const handleCustomZap = () => {
    const amount = parseInt(customZap, 10)
    if (amount > 0) {
      handleSetDefaultZap(amount)
      setCustomZap('')
    }
  }

  const handleUnmute = async (type, value) => {
    if (!pubkey || removing) return
    setRemoving(value)

    try {
      await removeFromMuteList(pubkey, type, value)
      // Update local state
      if (type === 'pubkey') {
        setMuteList(prev => ({
          ...prev,
          pubkeys: prev.pubkeys.filter(p => p !== value)
        }))
      } else if (type === 'hashtag') {
        setMuteList(prev => ({
          ...prev,
          hashtags: prev.hashtags.filter(h => h !== value)
        }))
      } else if (type === 'word') {
        setMuteList(prev => ({
          ...prev,
          words: prev.words.filter(w => w !== value)
        }))
      }
    } catch (e) {
      console.error('Failed to unmute:', e)
      alert('ミュート解除に失敗しました')
    } finally {
      setRemoving(null)
    }
  }

  // Relay management functions
  const handleAddReadRelay = () => {
    const relay = newReadRelay.trim()
    if (relay && relay.startsWith('wss://') && !readRelays.includes(relay)) {
      const updated = [...readRelays, relay]
      setReadRelaysState(updated)
      setReadRelays(updated)
      setNewReadRelay('')
    }
  }

  const handleRemoveReadRelay = (relay) => {
    const updated = readRelays.filter(r => r !== relay)
    setReadRelaysState(updated)
    setReadRelays(updated)
  }

  const handleAddWriteRelay = () => {
    const relay = newWriteRelay.trim()
    if (relay && relay.startsWith('wss://') && !writeRelays.includes(relay)) {
      const updated = [...writeRelays, relay]
      setWriteRelaysState(updated)
      setWriteRelays(updated)
      setNewWriteRelay('')
    }
  }

  const handleRemoveWriteRelay = (relay) => {
    const updated = writeRelays.filter(r => r !== relay)
    setWriteRelaysState(updated)
    setWriteRelays(updated)
  }

  const handleResetRelays = () => {
    setReadRelaysState([DEFAULT_READ_RELAY])
    setWriteRelaysState(DEFAULT_WRITE_RELAYS)
    setReadRelays([DEFAULT_READ_RELAY])
    setWriteRelays(DEFAULT_WRITE_RELAYS)
  }

  const handleSelectUploadServer = (server) => {
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

  return (
    <div className="min-h-screen pb-20">
      {/* Header */}
      <header className="sticky top-0 z-40 header-blur border-b border-[var(--border-color)]">
        <div className="flex items-center justify-center px-4 h-12">
          <h1 className="text-lg font-semibold text-[var(--text-primary)]">設定</h1>
        </div>
      </header>

      <div className="p-4 space-y-4">
        {/* Default Zap Amount Setting */}
        <section className="bg-[var(--bg-secondary)] rounded-2xl p-4">
          <div className="flex items-center gap-2 mb-4">
            <svg className="w-5 h-5 text-[var(--text-secondary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
              <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
            </svg>
            <h2 className="font-semibold text-[var(--text-primary)]">デフォルトZap金額</h2>
          </div>

          <div className="grid grid-cols-3 gap-2 mb-3">
            {ZAP_PRESETS.map(amount => (
              <button
                key={amount}
                onClick={() => handleSetDefaultZap(amount)}
                className={`py-2.5 px-3 rounded-xl text-sm font-medium transition-all ${
                  defaultZap === amount
                    ? 'bg-[var(--line-green)] text-white'
                    : 'bg-[var(--bg-tertiary)] text-[var(--text-primary)] hover:bg-[var(--border-color)]'
                }`}
              >
                {amount}
              </button>
            ))}
          </div>

          {showZapInput ? (
            <div className="flex gap-2">
              <input
                type="number"
                value={customZap}
                onChange={(e) => setCustomZap(e.target.value)}
                placeholder="カスタム金額"
                className="flex-1 input-line text-sm"
                min="1"
              />
              <button
                onClick={handleCustomZap}
                className="btn-line text-sm px-4"
              >
                設定
              </button>
              <button
                onClick={() => setShowZapInput(false)}
                className="btn-secondary text-sm px-3"
              >
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <line x1="18" y1="6" x2="6" y2="18"/>
                  <line x1="6" y1="6" x2="18" y2="18"/>
                </svg>
              </button>
            </div>
          ) : (
            <button
              onClick={() => setShowZapInput(true)}
              className="w-full py-2 text-sm text-[var(--line-green)] hover:underline"
            >
              カスタム金額を設定
            </button>
          )}

          <p className="text-xs text-[var(--text-tertiary)] mt-3 text-center">
            現在の設定: {defaultZap} sats
          </p>
        </section>

        {/* Relay Settings */}
        <section className="bg-[var(--bg-secondary)] rounded-2xl p-4">
          <button
            onClick={() => setShowRelaySettings(!showRelaySettings)}
            className="w-full flex items-center justify-between"
          >
            <div className="flex items-center gap-2">
              <svg className="w-5 h-5 text-[var(--text-secondary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
                <circle cx="12" cy="12" r="3"/>
                <path d="M12 2v4m0 12v4M2 12h4m12 0h4"/>
                <circle cx="12" cy="12" r="8" strokeDasharray="4 2"/>
              </svg>
              <h2 className="font-semibold text-[var(--text-primary)]">リレー設定</h2>
            </div>
            <svg className={`w-5 h-5 text-[var(--text-tertiary)] transition-transform ${showRelaySettings ? 'rotate-180' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="6 9 12 15 18 9"/>
            </svg>
          </button>

          {showRelaySettings && (
            <div className="mt-4 space-y-4">
              {/* Read Relays */}
              <div>
                <div className="flex items-center gap-2 text-sm font-medium text-[var(--text-secondary)] mb-2">
                  <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/>
                    <polyline points="7 10 12 15 17 10"/>
                    <line x1="12" y1="15" x2="12" y2="3"/>
                  </svg>
                  <span>タイムライン用リレー</span>
                </div>
                <p className="text-xs text-[var(--text-tertiary)] mb-2">
                  高速読み込み用（1つ推奨）
                </p>
                <div className="space-y-2">
                  {readRelays.map(relay => (
                    <div key={relay} className="flex items-center gap-2 p-2 bg-[var(--bg-tertiary)] rounded-xl">
                      <span className="flex-1 text-sm text-[var(--text-primary)] truncate">{relay}</span>
                      <button
                        onClick={() => handleRemoveReadRelay(relay)}
                        className="text-red-400 hover:text-red-500 p-1"
                      >
                        <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                          <line x1="18" y1="6" x2="6" y2="18"/>
                          <line x1="6" y1="6" x2="18" y2="18"/>
                        </svg>
                      </button>
                    </div>
                  ))}
                  <div className="flex gap-2">
                    <input
                      type="text"
                      value={newReadRelay}
                      onChange={(e) => setNewReadRelay(e.target.value)}
                      placeholder="wss://..."
                      className="flex-1 input-line text-sm"
                    />
                    <button
                      onClick={handleAddReadRelay}
                      className="btn-line text-sm px-3"
                    >
                      追加
                    </button>
                  </div>
                </div>
              </div>

              {/* Write Relays */}
              <div>
                <div className="flex items-center gap-2 text-sm font-medium text-[var(--text-secondary)] mb-2">
                  <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/>
                    <polyline points="17 8 12 3 7 8"/>
                    <line x1="12" y1="3" x2="12" y2="15"/>
                  </svg>
                  <span>バックアップ/書き込み用リレー</span>
                </div>
                <p className="text-xs text-[var(--text-tertiary)] mb-2">
                  投稿・プロフィール保存時に使用
                </p>
                <div className="space-y-2">
                  {writeRelays.map(relay => (
                    <div key={relay} className="flex items-center gap-2 p-2 bg-[var(--bg-tertiary)] rounded-xl">
                      <span className="flex-1 text-sm text-[var(--text-primary)] truncate">{relay}</span>
                      <button
                        onClick={() => handleRemoveWriteRelay(relay)}
                        className="text-red-400 hover:text-red-500 p-1"
                      >
                        <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                          <line x1="18" y1="6" x2="6" y2="18"/>
                          <line x1="6" y1="6" x2="18" y2="18"/>
                        </svg>
                      </button>
                    </div>
                  ))}
                  <div className="flex gap-2">
                    <input
                      type="text"
                      value={newWriteRelay}
                      onChange={(e) => setNewWriteRelay(e.target.value)}
                      placeholder="wss://..."
                      className="flex-1 input-line text-sm"
                    />
                    <button
                      onClick={handleAddWriteRelay}
                      className="btn-line text-sm px-3"
                    >
                      追加
                    </button>
                  </div>
                </div>
              </div>

              {/* Reset button */}
              <button
                onClick={handleResetRelays}
                className="w-full py-2 text-sm text-[var(--text-tertiary)] hover:text-[var(--text-secondary)]"
              >
                デフォルトに戻す
              </button>
            </div>
          )}
        </section>

        {/* Upload Server Settings */}
        <section className="bg-[var(--bg-secondary)] rounded-2xl p-4">
          <button
            onClick={() => setShowUploadSettings(!showUploadSettings)}
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
            <svg className={`w-5 h-5 text-[var(--text-tertiary)] transition-transform ${showUploadSettings ? 'rotate-180' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="6 9 12 15 18 9"/>
            </svg>
          </button>

          {showUploadSettings && (
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

        {/* Mute List Management */}
        <section className="bg-[var(--bg-secondary)] rounded-2xl p-4">
          <div className="flex items-center gap-2 mb-4">
            <svg className="w-5 h-5 text-[var(--text-secondary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
              <circle cx="12" cy="12" r="10"/>
              <line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/>
            </svg>
            <h2 className="font-semibold text-[var(--text-primary)]">ミュートリスト</h2>
          </div>

          {loading ? (
            <div className="py-8 text-center text-[var(--text-tertiary)]">
              読み込み中...
            </div>
          ) : (
            <div className="space-y-4">
              {/* Muted Users */}
              {muteList.pubkeys.length > 0 && (
                <div>
                  <h3 className="text-sm font-medium text-[var(--text-secondary)] mb-2">ミュートしたユーザー</h3>
                  <div className="space-y-2">
                    {muteList.pubkeys.map(pk => {
                      const profile = mutedProfiles[pk]
                      return (
                        <div key={pk} className="flex items-center justify-between p-2 bg-[var(--bg-tertiary)] rounded-xl">
                          <div className="flex items-center gap-2 min-w-0">
                            <div className="w-8 h-8 rounded-full overflow-hidden bg-[var(--bg-primary)] flex-shrink-0">
                              {profile?.picture ? (
                                <img src={profile.picture} alt="" className="w-full h-full object-cover" />
                              ) : (
                                <div className="w-full h-full flex items-center justify-center">
                                  <svg className="w-4 h-4 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="currentColor">
                                    <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/>
                                  </svg>
                                </div>
                              )}
                            </div>
                            <span className="text-sm text-[var(--text-primary)] truncate">
                              {profile?.name || shortenPubkey(pk, 8)}
                            </span>
                          </div>
                          <button
                            onClick={() => handleUnmute('pubkey', pk)}
                            disabled={removing === pk}
                            className="text-xs text-red-400 hover:underline disabled:opacity-50 px-2"
                          >
                            {removing === pk ? '...' : '解除'}
                          </button>
                        </div>
                      )
                    })}
                  </div>
                </div>
              )}

              {/* Muted Hashtags */}
              {muteList.hashtags.length > 0 && (
                <div>
                  <h3 className="text-sm font-medium text-[var(--text-secondary)] mb-2">ミュートしたハッシュタグ</h3>
                  <div className="flex flex-wrap gap-2">
                    {muteList.hashtags.map(tag => (
                      <div key={tag} className="flex items-center gap-1 px-3 py-1.5 bg-[var(--bg-tertiary)] rounded-full">
                        <span className="text-sm text-[var(--text-primary)]">#{tag}</span>
                        <button
                          onClick={() => handleUnmute('hashtag', tag)}
                          disabled={removing === tag}
                          className="text-red-400 hover:text-red-500 disabled:opacity-50 ml-1"
                        >
                          <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                            <line x1="18" y1="6" x2="6" y2="18"/>
                            <line x1="6" y1="6" x2="18" y2="18"/>
                          </svg>
                        </button>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Muted Words */}
              {muteList.words.length > 0 && (
                <div>
                  <h3 className="text-sm font-medium text-[var(--text-secondary)] mb-2">ミュートしたワード</h3>
                  <div className="flex flex-wrap gap-2">
                    {muteList.words.map(word => (
                      <div key={word} className="flex items-center gap-1 px-3 py-1.5 bg-[var(--bg-tertiary)] rounded-full">
                        <span className="text-sm text-[var(--text-primary)]">{word}</span>
                        <button
                          onClick={() => handleUnmute('word', word)}
                          disabled={removing === word}
                          className="text-red-400 hover:text-red-500 disabled:opacity-50 ml-1"
                        >
                          <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                            <line x1="18" y1="6" x2="6" y2="18"/>
                            <line x1="6" y1="6" x2="18" y2="18"/>
                          </svg>
                        </button>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Empty state */}
              {muteList.pubkeys.length === 0 && muteList.hashtags.length === 0 && muteList.words.length === 0 && (
                <div className="py-6 text-center text-[var(--text-tertiary)]">
                  <p className="text-sm">ミュートしているユーザーやワードはありません</p>
                </div>
              )}
            </div>
          )}
        </section>

        {/* Open Source Link */}
        <section className="text-center py-4">
          <a
            href="https://github.com/tami1A84/null--nostr"
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-2 text-sm text-[var(--text-tertiary)] hover:text-[var(--text-secondary)]"
          >
            <svg className="w-5 h-5" viewBox="0 0 24 24" fill="currentColor">
              <path d="M12 0c-6.626 0-12 5.373-12 12 0 5.302 3.438 9.8 8.207 11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23.957-.266 1.983-.399 3.003-.404 1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222v3.293c0 .319.192.694.801.576 4.765-1.589 8.199-6.086 8.199-11.386 0-6.627-5.373-12-12-12z"/>
            </svg>
            <span>ソースコード（GitHub）</span>
          </a>
        </section>
      </div>
    </div>
  )
}
