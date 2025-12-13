'use client'

import { useState, useEffect } from 'react'
import { nip19 } from 'nostr-tools'
import {
  fetchMuteList,
  removeFromMuteList,
  fetchEvents,
  parseProfile,
  shortenPubkey,
  getDefaultRelay,
  setDefaultRelay,
  getUploadServer,
  setUploadServer,
  getLoginMethod,
  getAutoSignEnabled,
  setAutoSignEnabled,
  RELAYS
} from '@/lib/nostr'

// Nosskey Settings Component
function NosskeySettings({ pubkey }) {
  const [showSettings, setShowSettings] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [exportedNsec, setExportedNsec] = useState(null)
  const [copied, setCopied] = useState(false)
  const [dmEnabled, setDmEnabled] = useState(false)
  const [autoSign, setAutoSign] = useState(true)

  // Check if DM is already enabled and load settings
  useEffect(() => {
    if (typeof window !== 'undefined') {
      if (window.nostrPrivateKey) {
        setDmEnabled(true)
      }
      // Load auto-sign setting
      const savedAutoSign = localStorage.getItem('nurunuru_auto_sign')
      setAutoSign(savedAutoSign !== 'false')
    }
  }, [])

  const hexToBytes = (hex) => {
    const bytes = new Uint8Array(hex.length / 2)
    for (let i = 0; i < hex.length; i += 2) {
      bytes[i / 2] = parseInt(hex.substr(i, 2), 16)
    }
    return bytes
  }

  const handleAutoSignChange = (enabled) => {
    setAutoSign(enabled)
    localStorage.setItem('nurunuru_auto_sign', enabled ? 'true' : 'false')
  }

  const handleExportKey = async () => {
    if (!window.nosskeyManager) {
      alert('Nosskeyマネージャーが見つかりません。再ログインしてください。')
      return
    }
    
    setExporting(true)
    try {
      const manager = window.nosskeyManager
      const keyInfo = manager.getCurrentKeyInfo()
      
      if (!keyInfo) {
        throw new Error('鍵情報が見つかりません。再ログインしてください。')
      }
      
      // Enable cache before export
      manager.setCacheOptions({ enabled: true, timeoutMs: 3600000 })
      
      // Export - don't pass credentialId, let SDK handle it from keyInfo
      const privateKeyHex = await manager.exportNostrKey(keyInfo)
      
      if (!privateKeyHex) {
        throw new Error('秘密鍵を取得できませんでした')
      }
      
      const nsec = nip19.nsecEncode(hexToBytes(privateKeyHex))
      setExportedNsec(nsec)
      
      // Store the private key for DM functionality and auto-signing
      window.nostrPrivateKey = privateKeyHex
      setDmEnabled(true)
      
    } catch (e) {
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
          {dmEnabled && (
            <span className="px-2 py-0.5 text-xs bg-emerald-100 dark:bg-emerald-900/40 text-emerald-600 dark:text-emerald-400 rounded-full font-medium">
              DM有効
            </span>
          )}
        </div>
        <svg className={`w-5 h-5 text-[var(--text-tertiary)] transition-transform ${showSettings ? 'rotate-180' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <polyline points="6 9 12 15 18 9"/>
        </svg>
      </button>

      {showSettings && (
        <div className="mt-4 space-y-4">
          {/* DM Status */}
          {dmEnabled && (
            <div className="bg-emerald-50 dark:bg-emerald-900/30 border border-emerald-200 dark:border-emerald-800 p-3 rounded-xl">
              <p className="text-sm text-emerald-700 dark:text-emerald-300 font-medium flex items-center gap-2">
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M22 11.08V12a10 10 0 11-5.93-9.14" strokeLinecap="round"/>
                  <polyline points="22 4 12 14.01 9 11.01" strokeLinecap="round" strokeLinejoin="round"/>
                </svg>
                DM・自動署名が有効です
              </p>
              <p className="text-xs text-emerald-600 dark:text-emerald-400 mt-1">
                投稿時に生体認証は不要です
              </p>
            </div>
          )}

          {/* Auto Sign Setting */}
          <div className="bg-[var(--bg-tertiary)] p-3 rounded-xl">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm font-medium text-[var(--text-primary)]">自動署名</p>
                <p className="text-xs text-[var(--text-tertiary)]">
                  {autoSign ? '投稿時に生体認証なし' : '毎回生体認証を要求'}
                </p>
              </div>
              <button
                onClick={() => handleAutoSignChange(!autoSign)}
                className={`relative w-12 h-6 rounded-full transition-colors ${
                  autoSign ? 'bg-[var(--line-green)]' : 'bg-[var(--border-color)]'
                }`}
              >
                <span className={`absolute top-0.5 left-0.5 w-5 h-5 bg-white rounded-full shadow transition-transform ${
                  autoSign ? 'translate-x-6' : 'translate-x-0'
                }`} />
              </button>
            </div>
            {!dmEnabled && autoSign && (
              <p className="text-xs text-yellow-600 dark:text-yellow-400 mt-2">
                ⚠️ 自動署名を有効にするには秘密鍵をエクスポートしてください
              </p>
            )}
          </div>

          {/* Export Key */}
          {!exportedNsec ? (
            <div>
              <p className="text-xs text-[var(--text-tertiary)] mb-3">
                {dmEnabled 
                  ? '秘密鍵をコピーして他のアプリで使用できます。'
                  : '秘密鍵をエクスポートするとDM機能と自動署名が有効になります。'}
              </p>
              <button
                onClick={handleExportKey}
                disabled={exporting}
                className={`w-full py-3 text-sm disabled:opacity-50 ${dmEnabled ? 'btn-secondary' : 'btn-line'}`}
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
                    {dmEnabled ? '秘密鍵を表示' : '秘密鍵をエクスポート（DM有効化）'}
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

          {/* Info */}
          {!dmEnabled && (
            <div className="bg-[var(--bg-tertiary)] p-3 rounded-xl">
              <p className="text-xs text-[var(--text-tertiary)]">
                💡 秘密鍵をエクスポートするとDM機能が有効になり、投稿時の再認証も不要になります。
              </p>
            </div>
          )}
        </div>
      )}
    </section>
  )
}

// Default zap amounts for quick select
const ZAP_PRESETS = [21, 100, 500, 1000, 5000, 10000]

// Popular relay list for search
const KNOWN_RELAYS = [
  { url: 'wss://yabu.me', name: 'やぶみ (日本)', region: 'JP' },
  { url: 'wss://relay-jp.nostr.wirednet.jp', name: 'WiredNet JP', region: 'JP' },
  { url: 'wss://r.kojira.io', name: 'Kojira', region: 'JP' },
  { url: 'wss://nos.lol', name: 'nos.lol', region: 'Global' },
  { url: 'wss://relay.damus.io', name: 'Damus', region: 'Global' },
  { url: 'wss://relay.nostr.band', name: 'nostr.band', region: 'Global' },
  { url: 'wss://relay.snort.social', name: 'Snort', region: 'Global' },
  { url: 'wss://nostr.wine', name: 'nostr.wine (有料)', region: 'Global' },
  { url: 'wss://relay.nostr.bg', name: 'nostr.bg', region: 'EU' },
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
  
  // Relay settings - simplified to single relay
  const [currentRelay, setCurrentRelay] = useState('wss://yabu.me')
  const [showRelaySettings, setShowRelaySettings] = useState(false)
  const [relaySearch, setRelaySearch] = useState('')
  const [customRelayUrl, setCustomRelayUrl] = useState('')
  
  // Upload server settings
  const [uploadServerState, setUploadServerState] = useState('nostr.build')
  const [customBlossomUrl, setCustomBlossomUrl] = useState('')
  const [showUploadSettings, setShowUploadSettings] = useState(false)
  
  // Collapsed section states
  const [showZapSettings, setShowZapSettings] = useState(false)
  const [showMuteSettings, setShowMuteSettings] = useState(false)

  useEffect(() => {
    // Load saved default zap from localStorage
    const saved = localStorage.getItem('defaultZapAmount')
    if (saved) {
      setDefaultZap(parseInt(saved, 10))
    }

    // Load relay setting
    setCurrentRelay(getDefaultRelay())
    
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

  // Single relay management
  const handleChangeRelay = (relayUrl) => {
    setCurrentRelay(relayUrl)
    setDefaultRelay(relayUrl)
    setRelaySearch('')
  }

  const handleSetCustomRelay = () => {
    const url = customRelayUrl.trim()
    if (url && url.startsWith('wss://')) {
      handleChangeRelay(url)
      setCustomRelayUrl('')
    }
  }

  const getFilteredRelays = () => {
    if (!relaySearch.trim()) return KNOWN_RELAYS
    const search = relaySearch.toLowerCase()
    return KNOWN_RELAYS.filter(r => 
      r.url.toLowerCase().includes(search) || 
      r.name.toLowerCase().includes(search) ||
      r.region.toLowerCase().includes(search)
    )
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
          <h1 className="text-lg font-semibold text-[var(--text-primary)]">ミニアプリ</h1>
        </div>
      </header>

      <div className="p-4 space-y-4">
        {/* Login Method Display */}
        <section className="bg-[var(--bg-secondary)] rounded-2xl p-4">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-full bg-[var(--line-green)] flex items-center justify-center">
              {getLoginMethod() === 'nosskey' ? (
                <svg className="w-5 h-5 text-white" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M12 2a4 4 0 014 4v2h2a2 2 0 012 2v10a2 2 0 01-2 2H6a2 2 0 01-2-2V10a2 2 0 012-2h2V6a4 4 0 014-4z"/>
                  <circle cx="12" cy="15" r="1"/>
                </svg>
              ) : (
                <svg className="w-5 h-5 text-white" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/>
                  <path d="M7 11V7a5 5 0 0110 0v4"/>
                </svg>
              )}
            </div>
            <div className="flex-1">
              <p className="font-medium text-[var(--text-primary)]">
                {getLoginMethod() === 'nosskey' ? 'パスキーでログイン中' : '拡張機能でログイン中'}
              </p>
              <p className="text-xs text-[var(--text-tertiary)]">
                {getLoginMethod() === 'nosskey' ? 'Face ID / Touch ID / Windows Hello' : 'NIP-07 (Alby, nos2x等)'}
              </p>
            </div>
            {getLoginMethod() === 'nosskey' && (
              <span className="px-2 py-1 text-xs bg-blue-100 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400 rounded-full">
                新機能
              </span>
            )}
          </div>
          {getLoginMethod() === 'nosskey' && (
            typeof window !== 'undefined' && window.nostrPrivateKey ? (
              <p className="mt-3 text-xs text-emerald-700 dark:text-emerald-300 bg-emerald-50 dark:bg-emerald-900/30 p-2 rounded-lg flex items-center gap-1 font-medium">
                <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M22 11.08V12a10 10 0 11-5.93-9.14" strokeLinecap="round"/>
                  <polyline points="22 4 12 14.01 9 11.01" strokeLinecap="round" strokeLinejoin="round"/>
                </svg>
                DM機能が有効です
              </p>
            ) : (
              <p className="mt-3 text-xs text-[var(--text-tertiary)] bg-[var(--bg-tertiary)] p-2 rounded-lg">
                ⚠️ DM機能を使用するには「パスキー設定」から秘密鍵をエクスポートしてください
              </p>
            )
          )}
        </section>

        {/* Nosskey Settings - only show when logged in with Nosskey */}
        {getLoginMethod() === 'nosskey' && (
          <NosskeySettings pubkey={pubkey} />
        )}

        {/* Default Zap Amount Setting */}
        <section className="bg-[var(--bg-secondary)] rounded-2xl p-4">
          <button
            onClick={() => setShowZapSettings(!showZapSettings)}
            className="w-full flex items-center justify-between"
          >
            <div className="flex items-center gap-2">
              <svg className="w-5 h-5 text-[var(--text-secondary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
              </svg>
              <h2 className="font-semibold text-[var(--text-primary)]">デフォルトZap金額</h2>
              <span className="text-sm text-[var(--text-tertiary)]">({defaultZap} sats)</span>
            </div>
            <svg className={`w-5 h-5 text-[var(--text-tertiary)] transition-transform ${showZapSettings ? 'rotate-180' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="6 9 12 15 18 9"/>
            </svg>
          </button>

          {showZapSettings && (
            <div className="mt-4">
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
            </div>
          )}
        </section>

        {/* Relay Settings - Simplified */}
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
              <h2 className="font-semibold text-[var(--text-primary)]">リレー</h2>
              <span className="text-xs text-[var(--text-tertiary)] truncate max-w-[120px]">{currentRelay.replace('wss://', '')}</span>
            </div>
            <svg className={`w-5 h-5 text-[var(--text-tertiary)] transition-transform ${showRelaySettings ? 'rotate-180' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="6 9 12 15 18 9"/>
            </svg>
          </button>

          {showRelaySettings && (
            <div className="mt-4 space-y-4">
              {/* Current Relay Display */}
              <div className="p-3 bg-[var(--line-green)] bg-opacity-10 rounded-xl border border-[var(--line-green)]">
                <p className="text-xs text-[var(--text-tertiary)] mb-1">使用中のリレー</p>
                <p className="text-sm font-medium text-[var(--text-primary)]">{currentRelay}</p>
              </div>

              {/* Search Relays */}
              <div>
                <p className="text-sm font-medium text-[var(--text-secondary)] mb-2">リレーを検索・変更</p>
                <input
                  type="text"
                  value={relaySearch}
                  onChange={(e) => setRelaySearch(e.target.value)}
                  placeholder="リレー名や地域で検索..."
                  className="w-full input-line text-sm mb-3"
                />
                
                <div className="space-y-2 max-h-48 overflow-y-auto">
                  {getFilteredRelays().map(relay => (
                    <div 
                      key={relay.url} 
                      className={`flex items-center justify-between p-2 rounded-xl transition-colors ${
                        currentRelay === relay.url 
                          ? 'bg-[var(--line-green)] bg-opacity-20' 
                          : 'bg-[var(--bg-tertiary)]'
                      }`}
                    >
                      <div className="flex-1 min-w-0">
                        <p className="text-sm text-[var(--text-primary)] truncate">{relay.name}</p>
                        <p className="text-xs text-[var(--text-tertiary)] truncate">{relay.url}</p>
                      </div>
                      <span className="text-xs text-[var(--text-tertiary)] mx-2">{relay.region}</span>
                      {currentRelay === relay.url ? (
                        <span className="text-xs text-[var(--line-green)] font-medium px-2">使用中</span>
                      ) : (
                        <button
                          onClick={() => handleChangeRelay(relay.url)}
                          className="btn-line text-xs px-3 py-1"
                        >
                          変更
                        </button>
                      )}
                    </div>
                  ))}
                </div>
              </div>

              {/* Custom Relay URL */}
              <div>
                <p className="text-xs text-[var(--text-tertiary)] mb-2">カスタムリレー</p>
                <div className="flex gap-2">
                  <input
                    type="text"
                    value={customRelayUrl}
                    onChange={(e) => setCustomRelayUrl(e.target.value)}
                    placeholder="wss://..."
                    className="flex-1 input-line text-sm"
                  />
                  <button
                    onClick={handleSetCustomRelay}
                    disabled={!customRelayUrl.trim().startsWith('wss://')}
                    className="btn-line text-sm px-3 disabled:opacity-50"
                  >
                    設定
                  </button>
                </div>
              </div>

              {/* Reset button */}
              <button
                onClick={() => handleChangeRelay('wss://yabu.me')}
                className="w-full py-2 text-sm text-[var(--text-tertiary)] hover:text-[var(--text-secondary)]"
              >
                デフォルト (yabu.me) に戻す
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
          <button
            onClick={() => setShowMuteSettings(!showMuteSettings)}
            className="w-full flex items-center justify-between"
          >
            <div className="flex items-center gap-2">
              <svg className="w-5 h-5 text-[var(--text-secondary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
                <circle cx="12" cy="12" r="10"/>
                <line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/>
              </svg>
              <h2 className="font-semibold text-[var(--text-primary)]">ミュートリスト</h2>
              {(muteList.pubkeys.length + muteList.hashtags.length + muteList.words.length) > 0 && (
                <span className="text-sm text-[var(--text-tertiary)]">
                  ({muteList.pubkeys.length + muteList.hashtags.length + muteList.words.length}件)
                </span>
              )}
            </div>
            <svg className={`w-5 h-5 text-[var(--text-tertiary)] transition-transform ${showMuteSettings ? 'rotate-180' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="6 9 12 15 18 9"/>
            </svg>
          </button>

          {showMuteSettings && (
            <div className="mt-4">
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
