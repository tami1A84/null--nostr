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
  setAutoSignEnabled
} from '@/lib/nostr'
import { clearBadgeCache } from './BadgeDisplay'
import SchedulerApp from './SchedulerApp'

// Nosskey Settings Component
function NosskeySettings({ pubkey }) {
  const [showSettings, setShowSettings] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [exportedNsec, setExportedNsec] = useState(null)
  const [copied, setCopied] = useState(false)
  const [autoSign, setAutoSign] = useState(true)
  const [hasExportedKey, setHasExportedKey] = useState(false)

  // Load settings
  useEffect(() => {
    if (typeof window !== 'undefined') {
      if (window.nostrPrivateKey) {
        setHasExportedKey(true)
      }
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
      
      // Store the private key for auto-signing
      window.nostrPrivateKey = privateKeyHex
      setHasExportedKey(true)
      
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

// Default zap amounts for quick select
const ZAP_PRESETS = [21, 100, 500, 1000, 5000, 10000]

// Popular relay list for search
const KNOWN_RELAYS = [
  { url: 'wss://yabu.me', name: 'やぶみ (デフォルト)', region: 'JP' },
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
  { id: 'share.yabu.me', name: 'やぶみ', url: 'share.yabu.me' },
  { id: 'blossom.nostr', name: 'Blossom (nostr.build)', url: 'https://blossom.nostr.build' },
]

export default function MiniAppTab({ pubkey, onLogout }) {
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
  const [showScheduler, setShowScheduler] = useState(false)
  const [showZapSettings, setShowZapSettings] = useState(false)
  const [showMuteSettings, setShowMuteSettings] = useState(false)
  const [showBadgeSettings, setShowBadgeSettings] = useState(false)

  // Badge settings
  const [profileBadges, setProfileBadges] = useState([])
  const [awardedBadges, setAwardedBadges] = useState([])
  const [loadingBadges, setLoadingBadges] = useState(false)
  const [removingBadge, setRemovingBadge] = useState(null)
  const [addingBadge, setAddingBadge] = useState(null)

  // Emoji settings
  const [showEmojiSettings, setShowEmojiSettings] = useState(false)
  const [userEmojis, setUserEmojis] = useState([])
  const [userEmojiSets, setUserEmojiSets] = useState([])
  const [loadingEmojis, setLoadingEmojis] = useState(false)
  const [removingEmoji, setRemovingEmoji] = useState(null)
  const [emojiSetSearch, setEmojiSetSearch] = useState('')
  const [searchedEmojiSets, setSearchedEmojiSets] = useState([])
  const [searchingEmoji, setSearchingEmoji] = useState(false)
  const [addingEmojiSet, setAddingEmojiSet] = useState(null)

  // My Mini Apps state
  const [favoriteApps, setFavoriteApps] = useState([])
  const [showMyApps, setShowMyApps] = useState(true)
  const [externalAppUrl, setExternalAppUrl] = useState('')
  const [externalAppName, setExternalAppName] = useState('')
  const [draggedIndex, setDraggedIndex] = useState(null)

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

    // Load favorite mini apps
    const savedFavorites = localStorage.getItem('favoriteMiniApps')
    if (savedFavorites) {
      try {
        setFavoriteApps(JSON.parse(savedFavorites))
      } catch (e) {
        console.error('Failed to load favorite apps:', e)
      }
    }

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
          [getDefaultRelay()]
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
    } finally {
      setRemoving(null)
    }
  }

  // Badge management
  const loadBadges = async () => {
    if (!pubkey) return
    setLoadingBadges(true)
    
    try {
      const currentRelay = getDefaultRelay()
      // Use multiple relays for better coverage (Japanese relays first)
      const relays = [currentRelay]
      const extraRelays = ['wss://yabu.me', 'wss://relay-jp.nostr.wirednet.jp', 'wss://r.kojira.io', 'wss://relay.nostr.band', 'wss://nos.lol']
      const allRelays = [...new Set([currentRelay, ...extraRelays])]
      
      // Load current profile badges (kind 30008)
      const profileBadgeEvents = await fetchEvents({
        kinds: [30008],
        authors: [pubkey],
        '#d': ['profile_badges'],
        limit: 1
      }, relays)
      
      const currentBadges = []
      if (profileBadgeEvents.length > 0) {
        const tags = profileBadgeEvents[0].tags
        const seenRefs = new Set() // Track unique badge refs
        
        for (let i = 0; i < tags.length; i++) {
          if (tags[i][0] === 'a' && tags[i][1]?.startsWith('30009:')) {
            const ref = tags[i][1]
            if (!seenRefs.has(ref)) {
              seenRefs.add(ref)
              const eTag = tags[i + 1]?.[0] === 'e' ? tags[i + 1][1] : null
              currentBadges.push({ ref, awardEventId: eTag })
            }
          }
        }
      }
      
      // Fetch badge definitions for profile badges (try multiple relays)
      for (const badge of currentBadges) {
        const parts = badge.ref.split(':')
        if (parts.length >= 3) {
          const [, creator, ...dTagParts] = parts
          const dTag = dTagParts.join(':')
          
          // Set default values first
          badge.name = dTag
          badge.image = ''
          badge.description = ''
          
          // Try fetching from all relays
          for (const relay of allRelays) {
            try {
              const defEvents = await fetchEvents({
                kinds: [30009],
                authors: [creator],
                '#d': [dTag],
                limit: 1
              }, [relay])
              
              if (defEvents.length > 0) {
                const event = defEvents[0]
                badge.name = event.tags.find(t => t[0] === 'name')?.[1] || dTag
                badge.image = event.tags.find(t => t[0] === 'thumb')?.[1] || 
                              event.tags.find(t => t[0] === 'image')?.[1] || ''
                badge.description = event.tags.find(t => t[0] === 'description')?.[1] || ''
                break // Found it, stop searching
              }
            } catch (e) {
              // Continue to next relay
            }
          }
        }
      }
      
      setProfileBadges(currentBadges)
      
      // Load awarded badges (kind 8) from multiple relays
      let allAwardEvents = []
      for (const relay of allRelays.slice(0, 3)) { // Limit to first 3 relays for speed
        try {
          const events = await fetchEvents({
            kinds: [8],
            '#p': [pubkey],
            limit: 50
          }, [relay])
          allAwardEvents = [...allAwardEvents, ...events]
        } catch (e) {
          // Continue with other relays
        }
      }
      
      // Deduplicate by event id
      const awardEventsMap = new Map()
      for (const event of allAwardEvents) {
        if (!awardEventsMap.has(event.id)) {
          awardEventsMap.set(event.id, event)
        }
      }
      const awardEvents = Array.from(awardEventsMap.values())
      
      console.log('Found award events:', awardEvents.length, 'from relays:', allRelays.slice(0, 3).join(', '))
      
      const awarded = []
      const seenAwards = new Set()
      // Also skip refs that are already in profile
      for (const b of currentBadges) {
        seenAwards.add(b.ref)
      }
      
      for (const event of awardEvents) {
        const aTag = event.tags.find(t => t[0] === 'a' && t[1]?.startsWith('30009:'))
        if (aTag) {
          const ref = aTag[1]
          // Prevent duplicate awards
          if (seenAwards.has(ref)) continue
          seenAwards.add(ref)
          
          const parts = ref.split(':')
          const dTag = parts.length >= 3 ? parts.slice(2).join(':') : 'バッジ'
          const badge = { ref, awardEventId: event.id, name: dTag, image: '', description: '' }
          
          if (parts.length >= 3) {
            const [, creator] = parts
            
            // Try fetching from all relays
            for (const relay of allRelays) {
              try {
                const defEvents = await fetchEvents({
                  kinds: [30009],
                  authors: [creator],
                  '#d': [dTag],
                  limit: 1
                }, [relay])
                
                if (defEvents.length > 0) {
                  const defEvent = defEvents[0]
                  badge.name = defEvent.tags.find(t => t[0] === 'name')?.[1] || dTag
                  badge.image = defEvent.tags.find(t => t[0] === 'thumb')?.[1] || 
                                defEvent.tags.find(t => t[0] === 'image')?.[1] || ''
                  badge.description = defEvent.tags.find(t => t[0] === 'description')?.[1] || ''
                  break // Found it, stop searching
                }
              } catch (e) {
                // Continue to next relay
              }
            }
          }
          
          awarded.push(badge)
        }
      }
      
      console.log('Awarded badges:', awarded.length)
      setAwardedBadges(awarded)
    } catch (e) {
      console.error('Failed to load badges:', e)
    } finally {
      setLoadingBadges(false)
    }
  }
  
  const handleAddBadgeToProfile = async (badge) => {
    if (!pubkey || addingBadge || profileBadges.length >= 3) return
    
    // Check if already in profile (by ref)
    if (profileBadges.some(b => b.ref === badge.ref)) return
    
    setAddingBadge(badge.ref)
    
    try {
      const { signEventNip07, publishEvent, createEventTemplate } = await import('@/lib/nostr')
      
      // Build new badge list
      const newBadges = [...profileBadges, badge]
      const tags = [['d', 'profile_badges']]
      
      for (const b of newBadges) {
        tags.push(['a', b.ref])
        if (b.awardEventId) {
          tags.push(['e', b.awardEventId])
        }
      }
      
      const event = createEventTemplate(30008, '')
      event.pubkey = pubkey
      event.tags = tags
      
      const signedEvent = await signEventNip07(event)
      await publishEvent(signedEvent)
      
      // Clear badge cache so it reloads with new data
      clearBadgeCache(pubkey)
      
      setProfileBadges(newBadges)
      setAwardedBadges(prev => prev.filter(b => b.ref !== badge.ref))
    } catch (e) {
      console.error('Failed to add badge:', e)
    } finally {
      setAddingBadge(null)
    }
  }
  
  const handleRemoveBadgeFromProfile = async (badge) => {
    if (!pubkey || removingBadge) return
    setRemovingBadge(badge.ref)
    
    try {
      const { signEventNip07, publishEvent, createEventTemplate } = await import('@/lib/nostr')
      
      // Remove badge by ref (not by index to avoid removing duplicates)
      const newBadges = profileBadges.filter(b => b.ref !== badge.ref)
      const tags = [['d', 'profile_badges']]
      
      for (const b of newBadges) {
        tags.push(['a', b.ref])
        if (b.awardEventId) {
          tags.push(['e', b.awardEventId])
        }
      }
      
      const event = createEventTemplate(30008, '')
      event.pubkey = pubkey
      event.tags = tags
      
      const signedEvent = await signEventNip07(event)
      await publishEvent(signedEvent)
      
      // Clear badge cache so it reloads with new data
      clearBadgeCache(pubkey)
      
      setProfileBadges(newBadges)
      // Add back to awarded badges
      setAwardedBadges(prev => [...prev, badge])
    } catch (e) {
      console.error('Failed to remove badge:', e)
    } finally {
      setRemovingBadge(null)
    }
  }

  // Emoji set management
  const loadUserEmojis = async () => {
    if (!pubkey) return
    setLoadingEmojis(true)
    
    try {
      const relays = [getDefaultRelay()]
      
      // Fetch user's emoji list (kind 10030)
      const events = await fetchEvents({
        kinds: [10030],
        authors: [pubkey],
        limit: 1
      }, relays)

      const individualEmojis = []
      const setPointers = []

      if (events.length > 0) {
        const tags = events[0].tags
        
        for (const tag of tags) {
          if (tag[0] === 'emoji' && tag[1] && tag[2]) {
            individualEmojis.push({ shortcode: tag[1], url: tag[2] })
          } else if (tag[0] === 'a' && tag[1]?.startsWith('30030:')) {
            setPointers.push(tag[1])
          }
        }
      }

      // Load emoji set details
      const loadedSets = []
      for (const pointer of setPointers) {
        const parts = pointer.split(':')
        if (parts.length >= 3) {
          const [, author, ...dTagParts] = parts
          const dTag = dTagParts.join(':')
          
          try {
            const setEvents = await fetchEvents({
              kinds: [30030],
              authors: [author],
              '#d': [dTag],
              limit: 1
            }, relays)
            
            if (setEvents.length > 0) {
              const setEvent = setEvents[0]
              const setName = setEvent.tags.find(t => t[0] === 'title')?.[1] || dTag
              const emojiCount = setEvent.tags.filter(t => t[0] === 'emoji').length
              
              loadedSets.push({
                pointer,
                name: setName,
                author,
                dTag,
                emojiCount
              })
            }
          } catch (e) {
            console.error('Failed to load emoji set:', e)
          }
        }
      }

      setUserEmojis(individualEmojis)
      setUserEmojiSets(loadedSets)
    } catch (e) {
      console.error('Failed to load user emojis:', e)
    } finally {
      setLoadingEmojis(false)
    }
  }

  const searchEmojiSets = async () => {
    if (!emojiSetSearch.trim()) {
      setSearchedEmojiSets([])
      return
    }
    
    setSearchingEmoji(true)
    try {
      const relays = [getDefaultRelay()]
      
      // Search for emoji sets
      const events = await fetchEvents({
        kinds: [30030],
        limit: 20
      }, relays)
      
      const search = emojiSetSearch.toLowerCase()
      const results = events
        .filter(e => {
          const title = e.tags.find(t => t[0] === 'title')?.[1] || ''
          const dTag = e.tags.find(t => t[0] === 'd')?.[1] || ''
          return title.toLowerCase().includes(search) || dTag.toLowerCase().includes(search)
        })
        .map(e => {
          const dTag = e.tags.find(t => t[0] === 'd')?.[1] || ''
          const title = e.tags.find(t => t[0] === 'title')?.[1] || dTag
          const emojiCount = e.tags.filter(t => t[0] === 'emoji').length
          const pointer = `30030:${e.pubkey}:${dTag}`
          
          return {
            pointer,
            name: title,
            author: e.pubkey,
            dTag,
            emojiCount,
            emojis: e.tags
              .filter(t => t[0] === 'emoji' && t[1] && t[2])
              .slice(0, 5)
              .map(t => ({ shortcode: t[1], url: t[2] }))
          }
        })
        .filter(s => s.emojiCount > 0)
        .filter(s => !userEmojiSets.some(us => us.pointer === s.pointer))
      
      setSearchedEmojiSets(results)
    } catch (e) {
      console.error('Failed to search emoji sets:', e)
    } finally {
      setSearchingEmoji(false)
    }
  }

  const handleAddEmojiSet = async (set) => {
    if (!pubkey || addingEmojiSet) return
    setAddingEmojiSet(set.pointer)
    
    try {
      const { signEventNip07, publishEvent, createEventTemplate } = await import('@/lib/nostr')
      
      // Build new emoji list
      const tags = []
      
      // Add existing emojis
      for (const emoji of userEmojis) {
        tags.push(['emoji', emoji.shortcode, emoji.url])
      }
      
      // Add existing sets
      for (const s of userEmojiSets) {
        tags.push(['a', s.pointer])
      }
      
      // Add new set
      tags.push(['a', set.pointer])
      
      const event = createEventTemplate(10030, '')
      event.pubkey = pubkey
      event.tags = tags
      
      const signedEvent = await signEventNip07(event)
      await publishEvent(signedEvent)
      
      setUserEmojiSets(prev => [...prev, set])
      setSearchedEmojiSets(prev => prev.filter(s => s.pointer !== set.pointer))
    } catch (e) {
      console.error('Failed to add emoji set:', e)
    } finally {
      setAddingEmojiSet(null)
    }
  }

  const handleRemoveEmojiSet = async (set) => {
    if (!pubkey || removingEmoji) return
    setRemovingEmoji(set.pointer)
    
    try {
      const { signEventNip07, publishEvent, createEventTemplate } = await import('@/lib/nostr')
      
      // Build new emoji list without the removed set
      const tags = []
      
      // Add existing emojis
      for (const emoji of userEmojis) {
        tags.push(['emoji', emoji.shortcode, emoji.url])
      }
      
      // Add remaining sets
      for (const s of userEmojiSets) {
        if (s.pointer !== set.pointer) {
          tags.push(['a', s.pointer])
        }
      }
      
      const event = createEventTemplate(10030, '')
      event.pubkey = pubkey
      event.tags = tags
      
      const signedEvent = await signEventNip07(event)
      await publishEvent(signedEvent)
      
      setUserEmojiSets(prev => prev.filter(s => s.pointer !== set.pointer))
    } catch (e) {
      console.error('Failed to remove emoji set:', e)
    } finally {
      setRemovingEmoji(null)
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

  // My Mini Apps management
  const saveFavoriteApps = (apps) => {
    setFavoriteApps(apps)
    localStorage.setItem('favoriteMiniApps', JSON.stringify(apps))
  }

  const handleAddToFavorites = (appId, appName, appType = 'internal') => {
    const newApp = { id: appId, name: appName, type: appType }
    if (!favoriteApps.some(app => app.id === appId)) {
      saveFavoriteApps([...favoriteApps, newApp])
    }
  }

  const handleRemoveFromFavorites = (appId) => {
    saveFavoriteApps(favoriteApps.filter(app => app.id !== appId))
  }

  const handleAddExternalApp = () => {
    const url = externalAppUrl.trim()
    if (url && url.startsWith('http')) {
      const appId = 'external_' + Date.now()
      // Use user-provided name, or fallback to hostname
      const appName = externalAppName.trim() || new URL(url).hostname
      const newApp = { id: appId, name: appName, type: 'external', url }
      saveFavoriteApps([...favoriteApps, newApp])
      setExternalAppUrl('')
      setExternalAppName('')
    }
  }

  const handleDragStart = (index) => {
    setDraggedIndex(index)
  }

  const handleDragOver = (e) => {
    e.preventDefault()
  }

  const handleDrop = (dropIndex) => {
    if (draggedIndex === null) return
    const newApps = [...favoriteApps]
    const [removed] = newApps.splice(draggedIndex, 1)
    newApps.splice(dropIndex, 0, removed)
    saveFavoriteApps(newApps)
    setDraggedIndex(null)
  }

  const handleLogoutClick = () => {
    if (onLogout && confirm('ログアウトしますか？')) {
      onLogout()
    }
  }

  const handleFavoriteAppClick = (app) => {
    // For external apps, open in new tab
    if (app.type === 'external' && app.url) {
      window.open(app.url, '_blank', 'noopener,noreferrer')
      return
    }

    // For internal apps, expand the corresponding section
    switch (app.id) {
      case 'scheduler':
        setShowScheduler(true)
        // Scroll to scheduler section
        setTimeout(() => {
          document.querySelector('#scheduler-section')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
        }, 100)
        break
      case 'zap':
        setShowZapSettings(true)
        setTimeout(() => {
          document.querySelector('#zap-section')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
        }, 100)
        break
      case 'relay':
        setShowRelaySettings(true)
        setTimeout(() => {
          document.querySelector('#relay-section')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
        }, 100)
        break
      case 'upload':
        setShowUploadSettings(true)
        setTimeout(() => {
          document.querySelector('#upload-section')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
        }, 100)
        break
      case 'mute':
        setShowMuteSettings(true)
        setTimeout(() => {
          document.querySelector('#mute-section')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
        }, 100)
        break
      case 'badge':
        setShowBadgeSettings(true)
        // Load badges if not already loaded
        if (profileBadges.length === 0 && awardedBadges.length === 0 && !loadingBadges) {
          loadBadges()
        }
        setTimeout(() => {
          document.querySelector('#badge-section')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
        }, 100)
        break
      case 'emoji':
        setShowEmojiSettings(true)
        // Load emojis if not already loaded
        if (userEmojis.length === 0 && userEmojiSets.length === 0 && !loadingEmojis) {
          loadUserEmojis()
        }
        setTimeout(() => {
          document.querySelector('#emoji-section')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
        }, 100)
        break
      default:
        break
    }
  }

  // Available mini apps that can be added to favorites
  const availableMiniApps = [
    { id: 'scheduler', name: '調整くん' },
    { id: 'zap', name: 'Zap設定' },
    { id: 'relay', name: 'リレー設定' },
    { id: 'upload', name: 'アップロード設定' },
    { id: 'mute', name: 'ミュートリスト' },
    { id: 'badge', name: 'プロフィールバッジ' },
    { id: 'emoji', name: 'カスタム絵文字' },
  ].filter(app => !favoriteApps.some(fav => fav.id === app.id))

  return (
    <div className="min-h-screen pb-20">
      {/* Header */}
      <header className="sticky top-0 z-40 header-blur border-b border-[var(--border-color)]">
        <div className="flex items-center justify-center px-4 h-12">
          <h1 className="text-lg font-semibold text-[var(--text-primary)]">ミニアプリ</h1>
        </div>
      </header>

      <div className="p-4 space-y-4">
        {/* Login Method Display with Logout Button */}
        <section className="bg-[var(--bg-secondary)] rounded-2xl p-4">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-full bg-[var(--line-green)] flex items-center justify-center flex-shrink-0">
              {getLoginMethod() === 'nosskey' ? (
                <svg className="w-5 h-5 text-white" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M12 2a4 4 0 014 4v2h2a2 2 0 012 2v10a2 2 0 01-2 2H6a2 2 0 01-2-2V10a2 2 0 012-2h2V6a4 4 0 014-4z"/>
                  <circle cx="12" cy="15" r="1"/>
                </svg>
              ) : getLoginMethod() === 'extension' ? (
                <svg className="w-5 h-5 text-white" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/>
                  <path d="M7 11V7a5 5 0 0110 0v4"/>
                </svg>
              ) : getLoginMethod() === 'readOnly' ? (
                <svg className="w-5 h-5 text-white" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
                  <circle cx="12" cy="12" r="3"/>
                </svg>
              ) : getLoginMethod() === 'local' ? (
                <svg className="w-5 h-5 text-white" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 11-7.778 7.778 5.5 5.5 0 017.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4"/>
                </svg>
              ) : (
                <svg className="w-5 h-5 text-white" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <circle cx="12" cy="12" r="10"/>
                  <path d="M8 12l2 2 4-4"/>
                </svg>
              )}
            </div>
            <div className="flex-1 min-w-0">
              <p className="font-medium text-[var(--text-primary)]">
                {getLoginMethod() === 'nosskey' ? 'パスキーでログイン中' :
                 getLoginMethod() === 'extension' ? '拡張機能でログイン中' :
                 getLoginMethod() === 'readOnly' ? '読み取り専用モード' :
                 getLoginMethod() === 'local' ? 'ローカルキーでログイン中' :
                 getLoginMethod() === 'connect' ? 'Nostr Connectでログイン中' :
                 'ログイン中'}
              </p>
              <p className="text-xs text-[var(--text-tertiary)]">
                {getLoginMethod() === 'nosskey' ? 'Face ID / Touch ID / Windows Hello' :
                 getLoginMethod() === 'extension' ? 'Alby / nos2x' :
                 getLoginMethod() === 'readOnly' ? '投稿・署名はできません' :
                 getLoginMethod() === 'local' ? 'ブラウザに秘密鍵を保存' :
                 getLoginMethod() === 'connect' ? 'nsec.app / リモート署名' :
                 ''}
              </p>
            </div>
            <button
              onClick={handleLogoutClick}
              className="px-3 py-1.5 text-xs bg-red-100 dark:bg-red-900/30 text-red-600 dark:text-red-400 rounded-full hover:bg-red-200 dark:hover:bg-red-900/50 transition-colors flex-shrink-0"
            >
              ログアウト
            </button>
          </div>
        </section>

        {/* Nosskey Settings - only show when logged in with Nosskey */}
        {getLoginMethod() === 'nosskey' && (
          <NosskeySettings pubkey={pubkey} />
        )}

        {/* My Mini Apps Section */}
        <section className="bg-[var(--bg-secondary)] rounded-2xl p-4">
          <button
            onClick={() => setShowMyApps(!showMyApps)}
            className="w-full flex items-center justify-between"
          >
            <div className="flex items-center gap-2">
              <svg className="w-5 h-5 text-[var(--text-secondary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
                <path d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"/>
              </svg>
              <h2 className="font-semibold text-[var(--text-primary)]">マイミニアプリ</h2>
              {favoriteApps.length > 0 && (
                <span className="text-sm text-[var(--text-tertiary)]">({favoriteApps.length})</span>
              )}
            </div>
            <svg className={`w-5 h-5 text-[var(--text-tertiary)] transition-transform ${showMyApps ? 'rotate-180' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="6 9 12 15 18 9"/>
            </svg>
          </button>

          {showMyApps && (
            <div className="mt-4 space-y-4">
              {/* Favorited Apps */}
              {favoriteApps.length > 0 && (
                <div>
                  <h3 className="text-sm font-medium text-[var(--text-secondary)] mb-2">お気に入りアプリ</h3>
                  <div className="space-y-2">
                    {favoriteApps.map((app, index) => (
                      <div
                        key={app.id}
                        draggable
                        onDragStart={() => handleDragStart(index)}
                        onDragOver={handleDragOver}
                        onDrop={() => handleDrop(index)}
                        className="flex items-center justify-between p-3 bg-[var(--bg-tertiary)] rounded-xl hover:bg-[var(--border-color)] transition-colors"
                      >
                        <div
                          className="flex items-center gap-3 flex-1 cursor-pointer"
                          onClick={() => handleFavoriteAppClick(app)}
                        >
                          <svg className="w-4 h-4 text-[var(--text-tertiary)] cursor-move" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                            <line x1="3" y1="9" x2="21" y2="9"/>
                            <line x1="3" y1="15" x2="21" y2="15"/>
                          </svg>
                          <span className="text-sm text-[var(--text-primary)]">{app.name}</span>
                          {app.type === 'external' && (
                            <span className="text-xs px-2 py-0.5 bg-blue-100 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400 rounded-full">外部</span>
                          )}
                        </div>
                        <button
                          onClick={(e) => {
                            e.stopPropagation()
                            handleRemoveFromFavorites(app.id)
                          }}
                          className="text-xs text-red-400 hover:text-red-500 flex-shrink-0"
                        >
                          <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                            <line x1="18" y1="6" x2="6" y2="18"/>
                            <line x1="6" y1="6" x2="18" y2="18"/>
                          </svg>
                        </button>
                      </div>
                    ))}
                  </div>
                  <p className="text-xs text-[var(--text-tertiary)] mt-2">ドラッグして並び替えができます</p>
                </div>
              )}

              {/* Add Nurunuru Mini App */}
              {availableMiniApps.length > 0 && (
                <div>
                  <h3 className="text-sm font-medium text-[var(--text-secondary)] mb-2">ぬるぬるミニアプリを追加</h3>
                  <div className="flex flex-wrap gap-2">
                    {availableMiniApps.map(app => (
                      <button
                        key={app.id}
                        onClick={() => handleAddToFavorites(app.id, app.name)}
                        className="px-3 py-1.5 text-sm bg-[var(--bg-tertiary)] text-[var(--text-primary)] rounded-full hover:bg-[var(--line-green)] hover:text-white transition-colors"
                      >
                        + {app.name}
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {/* Add External Mini App */}
              <div>
                <h3 className="text-sm font-medium text-[var(--text-secondary)] mb-2">外部ミニアプリを追加</h3>
                <div className="space-y-2">
                  <input
                    type="text"
                    value={externalAppName}
                    onChange={(e) => setExternalAppName(e.target.value)}
                    placeholder="アプリ名(例:おいくらサッツ)"
                    className="w-full input-line text-sm"
                  />
                  <div className="flex gap-2">
                    <input
                      type="url"
                      value={externalAppUrl}
                      onChange={(e) => setExternalAppUrl(e.target.value)}
                      placeholder="https://..."
                      className="flex-1 input-line text-sm"
                    />
                    <button
                      onClick={handleAddExternalApp}
                      disabled={!externalAppUrl.trim().startsWith('http')}
                      className="btn-line text-sm px-3 disabled:opacity-50"
                    >
                      追加
                    </button>
                  </div>
                </div>
                <p className="text-xs text-[var(--text-tertiary)] mt-1">名前とURLを入力して外部のミニアプリを追加できます</p>
              </div>

              {favoriteApps.length === 0 && (
                <div className="py-6 text-center text-[var(--text-tertiary)]">
                  <p className="text-sm">お気に入りのミニアプリはありません</p>
                  <p className="text-xs mt-1">上から追加してください</p>
                </div>
              )}
            </div>
          )}
        </section>

        {/* Default Zap Amount Setting */}
        <section id="zap-section" className="bg-[var(--bg-secondary)] rounded-2xl p-4">
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
        <section id="relay-section" className="bg-[var(--bg-secondary)] rounded-2xl p-4">
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
        <section id="upload-section" className="bg-[var(--bg-secondary)] rounded-2xl p-4">
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
        <section id="mute-section" className="bg-[var(--bg-secondary)] rounded-2xl p-4">
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

        {/* Emoji Settings */}
        <section id="emoji-section" className="bg-[var(--bg-secondary)] rounded-2xl p-4">
          <button
            onClick={() => {
              setShowEmojiSettings(!showEmojiSettings)
              if (!showEmojiSettings && userEmojis.length === 0 && userEmojiSets.length === 0) {
                loadUserEmojis()
              }
            }}
            className="w-full flex items-center justify-between"
          >
            <div className="flex items-center gap-2">
              <svg className="w-5 h-5 text-[var(--text-secondary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
                <circle cx="12" cy="12" r="10"/>
                <path d="M8 14s1.5 2 4 2 4-2 4-2"/>
                <line x1="9" y1="9" x2="9.01" y2="9"/>
                <line x1="15" y1="9" x2="15.01" y2="9"/>
              </svg>
              <h2 className="font-semibold text-[var(--text-primary)]">カスタム絵文字</h2>
              {(userEmojis.length > 0 || userEmojiSets.length > 0) && (
                <span className="text-sm text-[var(--text-tertiary)]">
                  ({userEmojiSets.length}セット)
                </span>
              )}
            </div>
            <svg className={`w-5 h-5 text-[var(--text-tertiary)] transition-transform ${showEmojiSettings ? 'rotate-180' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="6 9 12 15 18 9"/>
            </svg>
          </button>
          
          {showEmojiSettings && (
            <div className="mt-4 space-y-4">
              {loadingEmojis ? (
                <div className="py-6 text-center text-[var(--text-tertiary)]">
                  <p className="text-sm">読み込み中...</p>
                </div>
              ) : (
                <>
                  {/* Current Emoji Sets */}
                  {userEmojiSets.length > 0 && (
                    <div>
                      <h3 className="text-sm font-medium text-[var(--text-secondary)] mb-2">
                        登録済み絵文字セット
                      </h3>
                      <div className="space-y-2">
                        {userEmojiSets.map((set, i) => (
                          <div key={set.pointer} className="flex items-center justify-between p-2 bg-[var(--bg-tertiary)] rounded-xl">
                            <div className="flex items-center gap-2 min-w-0">
                              <div className="w-8 h-8 rounded bg-[var(--bg-secondary)] flex items-center justify-center flex-shrink-0">
                                <svg className="w-5 h-5 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                                  <path d="M20.59 13.41l-7.17 7.17a2 2 0 01-2.83 0L2 12V2h10l8.59 8.59a2 2 0 010 2.82z"/>
                                  <line x1="7" y1="7" x2="7.01" y2="7"/>
                                </svg>
                              </div>
                              <div className="min-w-0">
                                <span className="text-sm text-[var(--text-primary)] truncate block">
                                  {set.name}
                                </span>
                                <span className="text-xs text-[var(--text-tertiary)]">
                                  {set.emojiCount}個の絵文字
                                </span>
                              </div>
                            </div>
                            <button
                              onClick={() => handleRemoveEmojiSet(set)}
                              disabled={removingEmoji === set.pointer}
                              className="text-xs text-red-500 hover:underline disabled:opacity-50 px-2 flex-shrink-0"
                            >
                              {removingEmoji === set.pointer ? '...' : '削除'}
                            </button>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* Search Emoji Sets */}
                  <div>
                    <h3 className="text-sm font-medium text-[var(--text-secondary)] mb-2">
                      絵文字セットを追加
                    </h3>
                    <div className="flex gap-2">
                      <input
                        type="text"
                        value={emojiSetSearch}
                        onChange={(e) => setEmojiSetSearch(e.target.value)}
                        onKeyDown={(e) => e.key === 'Enter' && searchEmojiSets()}
                        placeholder="セット名で検索..."
                        className="flex-1 input-line text-sm"
                      />
                      <button
                        onClick={searchEmojiSets}
                        disabled={searchingEmoji}
                        className="px-3 py-2 bg-[var(--line-green)] text-white rounded-lg text-sm disabled:opacity-50"
                      >
                        {searchingEmoji ? '...' : '検索'}
                      </button>
                    </div>
                    
                    {/* Search Results */}
                    {searchedEmojiSets.length > 0 && (
                      <div className="mt-3 space-y-2">
                        {searchedEmojiSets.map((set) => (
                          <div key={set.pointer} className="p-3 bg-[var(--bg-primary)] rounded-xl border border-[var(--border-color)]">
                            <div className="flex items-center justify-between mb-2">
                              <span className="text-sm font-medium text-[var(--text-primary)]">
                                {set.name}
                              </span>
                              <button
                                onClick={() => handleAddEmojiSet(set)}
                                disabled={addingEmojiSet === set.pointer}
                                className="text-xs text-[var(--line-green)] hover:underline disabled:opacity-50"
                              >
                                {addingEmojiSet === set.pointer ? '追加中...' : '追加'}
                              </button>
                            </div>
                            <p className="text-xs text-[var(--text-tertiary)] mb-2">
                              {set.emojiCount}個の絵文字
                            </p>
                            {/* Preview emojis */}
                            {set.emojis && set.emojis.length > 0 && (
                              <div className="flex gap-1">
                                {set.emojis.map((emoji, j) => (
                                  <img
                                    key={j}
                                    src={emoji.url}
                                    alt={emoji.shortcode}
                                    className="w-6 h-6 object-contain"
                                    title={`:${emoji.shortcode}:`}
                                  />
                                ))}
                                {set.emojiCount > 5 && (
                                  <span className="text-xs text-[var(--text-tertiary)] self-center">
                                    +{set.emojiCount - 5}
                                  </span>
                                )}
                              </div>
                            )}
                          </div>
                        ))}
                      </div>
                    )}
                  </div>

                  {/* Empty state */}
                  {userEmojiSets.length === 0 && userEmojis.length === 0 && searchedEmojiSets.length === 0 && (
                    <div className="py-4 text-center text-[var(--text-tertiary)]">
                      <p className="text-sm">登録済みの絵文字セットはありません</p>
                      <p className="text-xs mt-1">上の検索から絵文字セットを追加できます</p>
                    </div>
                  )}
                </>
              )}
            </div>
          )}
        </section>

        {/* Badge Settings */}
        <section id="badge-section" className="bg-[var(--bg-secondary)] rounded-2xl p-4">
          <button
            onClick={() => {
              setShowBadgeSettings(!showBadgeSettings)
              if (!showBadgeSettings && profileBadges.length === 0 && awardedBadges.length === 0) {
                loadBadges()
              }
            }}
            className="w-full flex items-center justify-between"
          >
            <div className="flex items-center gap-2">
              <svg className="w-5 h-5 text-[var(--text-secondary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
                <circle cx="12" cy="8" r="6"/>
                <path d="M12 14v8"/>
                <path d="M9 18l3 3 3-3"/>
              </svg>
              <h2 className="font-semibold text-[var(--text-primary)]">プロフィールバッジ</h2>
              {profileBadges.length > 0 && (
                <span className="text-sm text-[var(--text-tertiary)]">
                  ({profileBadges.length}/3)
                </span>
              )}
            </div>
            <svg className={`w-5 h-5 text-[var(--text-tertiary)] transition-transform ${showBadgeSettings ? 'rotate-180' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="6 9 12 15 18 9"/>
            </svg>
          </button>

          {showBadgeSettings && (
            <div className="mt-4">
              {loadingBadges ? (
                <div className="py-8 text-center text-[var(--text-tertiary)]">
                  読み込み中...
                </div>
              ) : (
                <div className="space-y-4">
                  {/* Current Profile Badges */}
                  <div>
                    <h3 className="text-sm font-medium text-[var(--text-secondary)] mb-2">
                      表示中のバッジ (最大3つ)
                    </h3>
                    {profileBadges.length > 0 ? (
                      <div className="space-y-2">
                        {profileBadges.map((badge, i) => (
                          <div key={`${badge.ref}-${i}`} className="flex items-center justify-between p-2 bg-[var(--bg-tertiary)] rounded-xl">
                            <div className="flex items-center gap-2 min-w-0">
                              {badge.image ? (
                                <img 
                                  src={badge.image} 
                                  alt="" 
                                  className="w-8 h-8 rounded object-contain flex-shrink-0"
                                  referrerPolicy="no-referrer"
                                  onError={(e) => {
                                    e.target.style.display = 'none'
                                    e.target.nextSibling?.classList?.remove('hidden')
                                  }}
                                />
                              ) : null}
                              <div className={`w-8 h-8 rounded bg-[var(--bg-secondary)] flex items-center justify-center flex-shrink-0 ${badge.image ? 'hidden' : ''}`}>
                                <svg className="w-5 h-5 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                                  <circle cx="12" cy="8" r="6"/>
                                  <path d="M12 14v8"/>
                                  <path d="M9 18l3 3 3-3"/>
                                </svg>
                              </div>
                              <div className="min-w-0">
                                <span className="text-sm text-[var(--text-primary)] truncate block">
                                  {badge.name || 'バッジ'}
                                </span>
                                {badge.description && (
                                  <span className="text-xs text-[var(--text-tertiary)] truncate block">
                                    {badge.description}
                                  </span>
                                )}
                              </div>
                            </div>
                            <button
                              onClick={() => handleRemoveBadgeFromProfile(badge)}
                              disabled={removingBadge === badge.ref}
                              className="text-xs text-red-400 hover:underline disabled:opacity-50 px-2 flex-shrink-0"
                            >
                              {removingBadge === badge.ref ? '...' : '削除'}
                            </button>
                          </div>
                        ))}
                      </div>
                    ) : (
                      <p className="text-sm text-[var(--text-tertiary)] py-2">
                        表示中のバッジはありません
                      </p>
                    )}
                  </div>

                  {/* Available Badges */}
                  {awardedBadges.length > 0 && (
                    <div>
                      <h3 className="text-sm font-medium text-[var(--text-secondary)] mb-2">
                        獲得済みバッジ
                      </h3>
                      <div className="space-y-2">
                        {awardedBadges.map((badge, i) => (
                          <div key={`${badge.ref}-${i}`} className="flex items-center justify-between p-2 bg-[var(--bg-tertiary)] rounded-xl">
                            <div className="flex items-center gap-2 min-w-0">
                              {badge.image ? (
                                <img 
                                  src={badge.image} 
                                  alt="" 
                                  className="w-8 h-8 rounded object-contain flex-shrink-0"
                                  referrerPolicy="no-referrer"
                                  onError={(e) => {
                                    e.target.style.display = 'none'
                                    e.target.nextSibling?.classList?.remove('hidden')
                                  }}
                                />
                              ) : null}
                              <div className={`w-8 h-8 rounded bg-[var(--bg-secondary)] flex items-center justify-center flex-shrink-0 ${badge.image ? 'hidden' : ''}`}>
                                <svg className="w-5 h-5 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                                  <circle cx="12" cy="8" r="6"/>
                                  <path d="M12 14v8"/>
                                  <path d="M9 18l3 3 3-3"/>
                                </svg>
                              </div>
                              <div className="min-w-0">
                                <span className="text-sm text-[var(--text-primary)] truncate block">
                                  {badge.name || 'バッジ'}
                                </span>
                                {badge.description && (
                                  <span className="text-xs text-[var(--text-tertiary)] truncate block">
                                    {badge.description}
                                  </span>
                                )}
                              </div>
                            </div>
                            <button
                              onClick={() => handleAddBadgeToProfile(badge)}
                              disabled={addingBadge === badge.ref || profileBadges.length >= 3}
                              className="text-xs text-[var(--line-green)] hover:underline disabled:opacity-50 px-2 flex-shrink-0"
                            >
                              {addingBadge === badge.ref ? '...' : '追加'}
                            </button>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {profileBadges.length === 0 && awardedBadges.length === 0 && (
                    <div className="py-6 text-center text-[var(--text-tertiary)]">
                      <p className="text-sm">獲得したバッジはありません</p>
                    </div>
                  )}
                </div>
              )}
            </div>
          )}
        </section>

        {/* Scheduler Mini App - 調整くん (Collapsible, at bottom) */}
        <section id="scheduler-section" className="bg-[var(--bg-secondary)] rounded-2xl p-4">
          <button
            onClick={() => setShowScheduler(!showScheduler)}
            className="w-full flex items-center justify-between"
          >
            <div className="flex items-center gap-2">
              <svg className="w-5 h-5 text-[var(--text-secondary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
                <rect x="3" y="4" width="18" height="18" rx="2" ry="2"/>
                <line x1="16" y1="2" x2="16" y2="6"/>
                <line x1="8" y1="2" x2="8" y2="6"/>
                <line x1="3" y1="10" x2="21" y2="10"/>
              </svg>
              <h2 className="font-semibold text-[var(--text-primary)]">調整くん</h2>
            </div>
            <svg className={`w-5 h-5 text-[var(--text-tertiary)] transition-transform ${showScheduler ? 'rotate-180' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="6 9 12 15 18 9"/>
            </svg>
          </button>

          {showScheduler && (
            <div className="mt-4">
              <SchedulerApp pubkey={pubkey} />
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
