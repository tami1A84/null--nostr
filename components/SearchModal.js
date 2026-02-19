'use client'

import { useState, useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import {
  searchNotes,
  parseProfile,
  fetchEvents,
  shortenPubkey,
  signEventNip07,
  createEventTemplate,
  publishEvent,
  fetchLightningInvoice,
  getWriteRelays,
  reportEvent,
  createBirdwatchLabel,
  fetchBirdwatchLabels,
  rateBirdwatchLabel,
  addToMuteList,
  deleteEvent,
  parseNostrLink,
  resolveNip05,
  RELAYS
} from '@/lib/nostr'
import PostItem from './PostItem'
import LongFormPostItem from './LongFormPostItem'
import { NOSTR_KINDS } from '@/lib/constants'

export default function SearchModal({ pubkey, onClose, onViewProfile, initialQuery = '' }) {
  const [query, setQuery] = useState(initialQuery)
  const [results, setResults] = useState([])
  const [profiles, setProfiles] = useState({})
  const [searching, setSearching] = useState(false)
  const [hasSearched, setHasSearched] = useState(false)
  const [recentSearches, setRecentSearches] = useState([])
  const [reactions, setReactions] = useState({})
  const [userReactions, setUserReactions] = useState(new Set())
  const [userReposts, setUserReposts] = useState(new Set())
  const [likeAnimating, setLikeAnimating] = useState(null)
  const [zapAnimating, setZapAnimating] = useState(null)
  const [birdwatchLabels, setBirdwatchLabels] = useState({})
  const [mounted, setMounted] = useState(false)
  const inputRef = useRef(null)
  const modalRef = useRef(null)

  useEffect(() => {
    setMounted(true)
    return () => setMounted(false)
  }, [])

  // Auto-search when initialQuery is provided
  useEffect(() => {
    if (initialQuery && mounted) {
      handleSearch(initialQuery)
    }
  }, [initialQuery, mounted])

  useEffect(() => {
    // Load recent searches from localStorage
    const saved = localStorage.getItem('recentSearches')
    if (saved) {
      try {
        setRecentSearches(JSON.parse(saved).slice(0, 5))
      } catch (e) {
        console.warn('Failed to parse recent searches:', e.message)
        localStorage.removeItem('recentSearches')
      }
    }

    // Focus input on mount (only if no initialQuery)
    if (!initialQuery) {
      inputRef.current?.focus()
    }
    
    // Handle click outside on desktop
    const handleClickOutside = (e) => {
      if (modalRef.current && !modalRef.current.contains(e.target)) {
        onClose()
      }
    }
    
    // Only add listener on desktop
    if (window.innerWidth >= 1024) {
      document.addEventListener('mousedown', handleClickOutside)
      return () => document.removeEventListener('mousedown', handleClickOutside)
    }
  }, [])

  const saveRecentSearch = (q) => {
    const updated = [q, ...recentSearches.filter(s => s !== q)].slice(0, 5)
    setRecentSearches(updated)
    localStorage.setItem('recentSearches', JSON.stringify(updated))
  }

  const removeRecentSearch = (searchToRemove) => {
    const updated = recentSearches.filter(s => s !== searchToRemove)
    setRecentSearches(updated)
    localStorage.setItem('recentSearches', JSON.stringify(updated))
  }

  // Check if query is a hex pubkey (64 character hex string)
  const isHexPubkey = (q) => /^[0-9a-fA-F]{64}$/.test(q)

  // Check if query is a hex event ID (64 character hex string)
  const isHexEventId = (q) => /^[0-9a-fA-F]{64}$/.test(q)

  // Check if query looks like a NIP-05 identifier (user@domain.tld or domain.tld)
  const isNip05 = (q) => {
    // user@domain.tld format
    if (/^[a-zA-Z0-9._-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/.test(q)) return true
    // domain.tld format (will be treated as _@domain.tld)
    if (/^[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/.test(q) && !q.includes(' ')) return true
    return false
  }

  const handleSearch = async (searchQuery = query) => {
    if (!searchQuery.trim()) return

    const trimmedQuery = searchQuery.trim()
    setSearching(true)
    setHasSearched(true)
    saveRecentSearch(trimmedQuery)

    try {
      // Check for special search patterns

      // 1. Check for bech32 encoded Nostr identifiers (npub, nprofile, note, nevent, naddr)
      const parsed = parseNostrLink(trimmedQuery)
      if (parsed) {
        if (parsed.type === 'npub' || parsed.type === 'nprofile') {
          // Navigate to user profile
          setSearching(false)
          onClose()
          onViewProfile?.(parsed.pubkey)
          return
        } else if (parsed.type === 'note' || parsed.type === 'nevent') {
          // Fetch single event
          const eventId = parsed.id
          const events = await fetchEvents(
            { ids: [eventId], limit: 1 },
            parsed.relays?.length ? parsed.relays : RELAYS
          )
          if (events.length > 0) {
            setResults(events)
          } else {
            setResults([])
          }
          setSearching(false)
          // Continue to fetch profiles for the result
          if (events.length > 0) {
            const authors = [...new Set(events.map(n => n.pubkey))]
            const profileEvents = await fetchEvents(
              { kinds: [0], authors, limit: authors.length },
              RELAYS
            )
            const profileMap = {}
            profileEvents.forEach(e => {
              profileMap[e.pubkey] = parseProfile(e)
            })
            setProfiles(profileMap)
          }
          return
        } else if (parsed.type === 'naddr') {
          // Fetch replaceable event by naddr
          const events = await fetchEvents(
            {
              kinds: [parsed.kind],
              authors: [parsed.pubkey],
              '#d': [parsed.identifier],
              limit: 1
            },
            parsed.relays?.length ? parsed.relays : RELAYS
          )
          if (events.length > 0) {
            setResults(events)
            // Fetch profile
            const profileEvents = await fetchEvents(
              { kinds: [0], authors: [parsed.pubkey], limit: 1 },
              RELAYS
            )
            const profileMap = {}
            profileEvents.forEach(e => {
              profileMap[e.pubkey] = parseProfile(e)
            })
            setProfiles(profileMap)
          } else {
            setResults([])
          }
          setSearching(false)
          return
        }
      }

      // 2. Check for hex pubkey (64 character hex)
      if (isHexPubkey(trimmedQuery)) {
        // Could be pubkey or event ID, try fetching as event first
        const events = await fetchEvents(
          { ids: [trimmedQuery], limit: 1 },
          RELAYS
        )
        if (events.length > 0) {
          // It's an event ID
          setResults(events)
          const authors = [...new Set(events.map(n => n.pubkey))]
          const profileEvents = await fetchEvents(
            { kinds: [0], authors, limit: authors.length },
            RELAYS
          )
          const profileMap = {}
          profileEvents.forEach(e => {
            profileMap[e.pubkey] = parseProfile(e)
          })
          setProfiles(profileMap)
          setSearching(false)
          return
        }
        // Not an event ID, treat as pubkey and go to profile
        setSearching(false)
        onClose()
        onViewProfile?.(trimmedQuery.toLowerCase())
        return
      }

      // 3. Check for NIP-05 identifier
      if (isNip05(trimmedQuery)) {
        const resolvedPubkey = await resolveNip05(trimmedQuery)
        if (resolvedPubkey) {
          setSearching(false)
          onClose()
          onViewProfile?.(resolvedPubkey)
          return
        }
        // NIP-05 resolution failed, fall through to text search
      }

      // 4. Default: Full-text search — try Rust engine API first, fall back to JS
      let notes = null
      try {
        const res = await fetch(`/api/search?q=${encodeURIComponent(query)}&limit=50`)
        if (res.ok) {
          const data = await res.json()
          if (data.results && data.source !== 'fallback') {
            notes = data.results
          }
        }
      } catch (e) {
        // API unavailable, fall back to JS below
      }

      if (!notes) {
        notes = await searchNotes(searchQuery, { limit: 50 })
      }
      setResults(notes)

      // Fetch profiles for results
      if (notes.length > 0) {
        const authors = [...new Set(notes.map(n => n.pubkey))]
        const profileEvents = await fetchEvents(
          { kinds: [0], authors, limit: authors.length },
          RELAYS
        )
        const profileMap = {}
        profileEvents.forEach(e => {
          profileMap[e.pubkey] = parseProfile(e)
        })
        setProfiles(profileMap)

        // Fetch reactions
        if (pubkey) {
          const eventIds = notes.map(n => n.id)
          const [reactionEvents, myRepostEvents] = await Promise.all([
            fetchEvents({ kinds: [7], '#e': eventIds, limit: 500 }, RELAYS),
            fetchEvents({ kinds: [6], authors: [pubkey], limit: 100 }, RELAYS)
          ])
          
          const reactionCounts = {}
          const myReactions = new Set()
          
          for (const event of reactionEvents) {
            const targetId = event.tags.find(t => t[0] === 'e')?.[1]
            if (targetId) {
              reactionCounts[targetId] = (reactionCounts[targetId] || 0) + 1
              if (event.pubkey === pubkey) {
                myReactions.add(targetId)
              }
            }
          }

          const myReposts = new Set()
          for (const repost of myRepostEvents) {
            const targetId = repost.tags.find(t => t[0] === 'e')?.[1]
            if (targetId) myReposts.add(targetId)
          }

          setReactions(reactionCounts)
          setUserReactions(myReactions)
          setUserReposts(myReposts)

          // Fetch Birdwatch labels in background
          try {
            const labels = await fetchBirdwatchLabels(eventIds)
            if (Object.keys(labels).length > 0) {
              setBirdwatchLabels(labels)
            }
          } catch (e) {
            console.error('Failed to fetch Birdwatch labels:', e)
          }
        }
      }
    } catch (e) {
      console.error('Search error:', e)
    } finally {
      setSearching(false)
    }
  }

  // NIP-56: Report handler
  const handleReport = async (reportData) => {
    if (!pubkey) return
    try {
      await reportEvent(reportData)
      alert('通報を送信しました')
    } catch (e) {
      console.error('Failed to report:', e)
      throw e
    }
  }

  // NIP-32: Birdwatch handler
  const handleBirdwatch = async (birdwatchData) => {
    if (!pubkey) return
    try {
      const result = await createBirdwatchLabel(birdwatchData)
      if (result.success && result.event) {
        setBirdwatchLabels(prev => ({
          ...prev,
          [birdwatchData.eventId]: [
            ...(prev[birdwatchData.eventId] || []),
            result.event
          ]
        }))
      }
      alert('コンテキストを追加しました')
    } catch (e) {
      console.error('Failed to create Birdwatch label:', e)
      throw e
    }
  }

  // NIP-32: Birdwatch rate handler
  const handleBirdwatchRate = async (labelEventId, rating) => {
    if (!pubkey) return
    try {
      await rateBirdwatchLabel(labelEventId, rating)
    } catch (e) {
      console.error('Failed to rate Birdwatch label:', e)
      throw e
    }
  }

  // Mute handler
  const handleMute = async (targetPubkey) => {
    if (!pubkey) return
    try {
      await addToMuteList(pubkey, 'pubkey', targetPubkey)
      // Remove posts by muted user from results
      setResults(prev => prev.filter(p => p.pubkey !== targetPubkey))
      alert('ミュートしました')
    } catch (e) {
      console.error('Failed to mute:', e)
    }
  }

  // Delete handler
  const handleDelete = async (eventId) => {
    if (!confirm('この投稿を削除しますか？')) return

    try {
      const result = await deleteEvent(eventId)
      if (result.success) {
        setResults(prev => prev.filter(p => p.id !== eventId))
      }
    } catch (e) {
      console.error('Failed to delete:', e)
      alert('削除に失敗しました')
    }
  }

  const handleKeyDown = (e) => {
    if (e.key === 'Enter') {
      handleSearch()
    }
  }

  const clearRecentSearches = () => {
    setRecentSearches([])
    localStorage.removeItem('recentSearches')
  }

  // Like handler (supports custom emoji reactions: NIP-25 + NIP-30)
  const handleLike = async (post, emoji = null) => {
    if (!pubkey || userReactions.has(post.id)) return

    setLikeAnimating(post.id)

    try {
      let content = '+'
      const tags = [
        ['e', post.id],
        ['p', post.pubkey]
      ]

      if (emoji) {
        if (emoji.type === 'custom') {
          content = `:${emoji.shortcode}:`
          tags.push(['emoji', emoji.shortcode, emoji.url])
        } else if (emoji.type === 'unicode') {
          content = emoji.content
        }
      }

      const template = createEventTemplate(7, content, tags)
      const signed = await signEventNip07(template)
      await publishEvent(signed, getWriteRelays())

      setUserReactions(prev => new Set([...prev, post.id]))
      setReactions(prev => ({
        ...prev,
        [post.id]: (prev[post.id] || 0) + 1
      }))
    } catch (e) {
      console.error('Failed to like:', e)
    } finally {
      setTimeout(() => setLikeAnimating(null), 300)
    }
  }

  // Repost handler
  const handleRepost = async (post) => {
    if (!pubkey) return
    if (userReposts.has(post.id)) return

    try {
      const template = createEventTemplate(6, JSON.stringify(post), [
        ['e', post.id, ''],
        ['p', post.pubkey]
      ])
      const signed = await signEventNip07(template)
      await publishEvent(signed, getWriteRelays())
      
      setUserReposts(prev => new Set([...prev, post.id]))
    } catch (e) {
      console.error('Failed to repost:', e)
    }
  }

  // Zap handler
  const handleZap = async (post) => {
    if (!pubkey) return
    
    setZapAnimating(post.id)
    
    try {
      const profile = profiles[post.pubkey]
      if (!profile?.lud16) {
        alert('このユーザーはLightning Addressを設定していません')
        return
      }

      const amount = parseInt(localStorage.getItem('defaultZapAmount') || '21', 10)
      const invoice = await fetchLightningInvoice(profile.lud16, amount * 1000)
      
      if (invoice && window.webln) {
        await window.webln.enable()
        await window.webln.sendPayment(invoice)
        alert(`⚡ ${amount} sats を送信しました！`)
      } else if (invoice) {
        await navigator.clipboard.writeText(invoice)
        alert('Lightning Invoiceをコピーしました。ウォレットで支払ってください。')
      }
    } catch (e) {
      console.error('Failed to zap:', e)
    } finally {
      setTimeout(() => setZapAnimating(null), 300)
    }
  }

  const modalContent = (
    <>
      {/* Desktop: Modal overlay */}
      <div className="hidden lg:block fixed inset-0 z-40 bg-black/40 backdrop-blur-sm" onClick={onClose} />
      
      {/* Mobile: Modal with lower z-index than BottomNav (z-50) */}
      <div className="lg:hidden fixed inset-x-0 top-0 bottom-16 z-30 bg-[var(--bg-primary)] flex flex-col">
        {/* Header with search bar */}
        <header className="sticky top-0 z-10 header-blur border-b border-[var(--border-color)] flex-shrink-0">
          <div className="flex items-center gap-2 px-3 h-14">
            <button
              onClick={onClose}
              className="w-10 h-10 flex items-center justify-center action-btn flex-shrink-0"
            >
              <svg className="w-6 h-6 text-[var(--text-primary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <line x1="18" y1="6" x2="6" y2="18"/>
                <line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
            
            <div className="flex-1 relative">
              <div className="absolute left-3 top-1/2 -translate-y-1/2">
                <svg className="w-5 h-5 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <circle cx="11" cy="11" r="8"/>
                  <line x1="21" y1="21" x2="16.65" y2="16.65"/>
                </svg>
              </div>
              <input
                ref={inputRef}
                type="text"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder="キーワード / npub / NIP-05 / note"
                className="w-full h-10 pl-10 pr-10 rounded-full bg-[var(--bg-secondary)] text-[var(--text-primary)] placeholder-[var(--text-tertiary)] outline-none text-sm border border-transparent focus:border-[var(--line-green)] transition-colors"
              />
              {query && (
                <button
                  onClick={() => setQuery('')}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-[var(--text-tertiary)] hover:text-[var(--text-secondary)]"
                >
                  <svg className="w-5 h-5" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M12 2C6.47 2 2 6.47 2 12s4.47 10 10 10 10-4.47 10-10S17.53 2 12 2zm5 13.59L15.59 17 12 13.41 8.41 17 7 15.59 10.59 12 7 8.41 8.41 7 12 10.59 15.59 7 17 8.41 13.41 12 17 15.59z"/>
                  </svg>
                </button>
              )}
            </div>

            <button
              onClick={() => handleSearch()}
              disabled={!query.trim() || searching}
              className="px-4 py-2 rounded-full bg-[var(--line-green)] text-white text-sm font-semibold disabled:opacity-50 flex-shrink-0"
            >
              {searching ? '...' : '検索'}
            </button>
          </div>
        </header>

        {/* Mobile Content */}
        <div className="flex-1 overflow-y-auto">
          {renderContent()}
        </div>
      </div>
      
      {/* Desktop: Modal Container */}
      <div 
        ref={modalRef}
        className="hidden lg:flex fixed z-40 bg-[var(--bg-primary)] flex-col
          lg:top-1/2 lg:left-1/2 lg:-translate-x-1/2 lg:-translate-y-1/2 
          lg:w-full lg:max-w-2xl lg:h-[80vh] lg:max-h-[700px] lg:rounded-2xl lg:shadow-2xl lg:border lg:border-[var(--border-color)]"
      >
        {/* Header with search bar */}
        <header className="sticky top-0 z-40 header-blur border-b border-[var(--border-color)] flex-shrink-0 lg:rounded-t-2xl">
          <div className="flex items-center gap-2 px-4 h-16">
            <button
              onClick={onClose}
              className="w-10 h-10 flex items-center justify-center action-btn flex-shrink-0 hover:bg-[var(--bg-tertiary)] rounded-full"
            >
              <svg className="w-6 h-6 text-[var(--text-primary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <line x1="18" y1="6" x2="6" y2="18"/>
                <line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
            
            <div className="flex-1 relative">
              <div className="absolute left-4 top-1/2 -translate-y-1/2">
                <svg className="w-5 h-5 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <circle cx="11" cy="11" r="8"/>
                  <line x1="21" y1="21" x2="16.65" y2="16.65"/>
                </svg>
              </div>
              <input
                type="text"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder="キーワード / npub / NIP-05 / note"
                className="w-full h-12 pl-12 pr-10 rounded-full bg-[var(--bg-secondary)] text-[var(--text-primary)] placeholder-[var(--text-tertiary)] outline-none text-base border border-transparent focus:border-[var(--line-green)] transition-colors"
              />
              {query && (
                <button
                  onClick={() => setQuery('')}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-[var(--text-tertiary)] hover:text-[var(--text-secondary)]"
                >
                  <svg className="w-5 h-5" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M12 2C6.47 2 2 6.47 2 12s4.47 10 10 10 10-4.47 10-10S17.53 2 12 2zm5 13.59L15.59 17 12 13.41 8.41 17 7 15.59 10.59 12 7 8.41 8.41 7 12 10.59 15.59 7 17 8.41 13.41 12 17 15.59z"/>
                  </svg>
                </button>
              )}
            </div>

            <button
              onClick={() => handleSearch()}
              disabled={!query.trim() || searching}
              className="px-6 py-2.5 rounded-full bg-[var(--line-green)] text-white text-base font-semibold disabled:opacity-50 flex-shrink-0 hover:bg-[var(--line-green-dark)] transition-colors"
            >
              {searching ? '...' : '検索'}
            </button>
          </div>
        </header>

        {/* Desktop Content */}
        <div className="flex-1 overflow-y-auto rounded-b-2xl">
          {renderContent()}
        </div>
      </div>
    </>
  )

  // Content renderer (shared between mobile and desktop)
  function renderContent() {
    if (!hasSearched) {
      return (
        <div className="p-4">
          {/* Recent Searches */}
          {recentSearches.length > 0 && (
            <section>
              <div className="flex items-center justify-between mb-3">
                <h2 className="text-sm font-semibold text-[var(--text-secondary)]">最近の検索</h2>
                <button
                  onClick={clearRecentSearches}
                  className="text-xs text-[var(--text-tertiary)]"
                >
                  すべてクリア
                </button>
              </div>
              <div className="flex flex-wrap gap-2">
                {recentSearches.map((search, i) => (
                  <div
                    key={i}
                    className="flex items-center gap-1 px-3 py-1.5 rounded-full bg-[var(--bg-secondary)] text-sm max-w-[200px] min-w-0"
                  >
                    <button
                      onClick={() => { setQuery(search); handleSearch(search); }}
                      className="text-[var(--text-primary)] hover:text-[var(--line-green)] truncate min-w-0"
                      title={search}
                    >
                      {search}
                    </button>
                    <button
                      onClick={() => removeRecentSearch(search)}
                      className="text-[var(--text-tertiary)] hover:text-red-500 ml-1"
                    >
                      <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                        <line x1="18" y1="6" x2="6" y2="18"/>
                        <line x1="6" y1="6" x2="18" y2="18"/>
                      </svg>
                    </button>
                  </div>
                ))}
              </div>
            </section>
          )}

          {/* Empty state */}
          {recentSearches.length === 0 && (
            <div className="flex flex-col items-center justify-center py-16 px-4">
              <div className="w-16 h-16 mb-4 rounded-full bg-[var(--bg-secondary)] flex items-center justify-center">
                <svg className="w-8 h-8 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                  <circle cx="11" cy="11" r="8"/>
                  <line x1="21" y1="21" x2="16.65" y2="16.65"/>
                </svg>
              </div>
              <p className="text-[var(--text-secondary)] text-center text-sm">
                キーワード、npub、note、NIP-05で検索
              </p>
            </div>
          )}
        </div>
      )
    }
    
    if (searching) {
      return (
        <div className="flex flex-col items-center justify-center py-16">
          <div className="w-10 h-10 border-3 border-[var(--bg-tertiary)] border-t-[var(--line-green)] rounded-full animate-spin mb-3" />
          <p className="text-[var(--text-secondary)] text-sm">検索中...</p>
        </div>
      )
    }
    
    if (results.length === 0) {
      return (
        <div className="flex flex-col items-center justify-center py-16 px-4">
          <div className="w-16 h-16 mb-4 rounded-full bg-[var(--bg-secondary)] flex items-center justify-center">
            <svg className="w-8 h-8 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
              <circle cx="11" cy="11" r="8"/>
              <line x1="21" y1="21" x2="16.65" y2="16.65"/>
            </svg>
          </div>
          <p className="text-[var(--text-secondary)] text-center text-sm">
            「{query}」に一致する結果が見つかりませんでした
          </p>
        </div>
      )
    }
    
    return (
      <div className="divide-y divide-[var(--border-color)]">
        <div className="px-4 py-2 bg-[var(--bg-secondary)]">
          <p className="text-xs text-[var(--text-secondary)]">
            <span className="font-semibold text-[var(--text-primary)]">{results.length}</span> 件の結果
          </p>
        </div>
        {results.map(post => {
          const ItemComponent = post.kind === NOSTR_KINDS.LONG_FORM ? LongFormPostItem : PostItem
          return (
            <ItemComponent
              key={post.id}
              post={post}
              profile={profiles[post.pubkey]}
              profiles={profiles}
              showActions={true}
              likeCount={reactions[post.id] || 0}
              hasLiked={userReactions.has(post.id)}
              hasReposted={userReposts.has(post.id)}
              isLiking={likeAnimating === post.id}
              isZapping={zapAnimating === post.id}
              onLike={handleLike}
              onRepost={handleRepost}
              onZap={handleZap}
              onAvatarClick={(pk) => onViewProfile?.(pk)}
              onMute={handleMute}
              onDelete={handleDelete}
              onReport={handleReport}
              onBirdwatch={handleBirdwatch}
              onBirdwatchRate={handleBirdwatchRate}
              birdwatchNotes={birdwatchLabels[post.id] || []}
              myPubkey={pubkey}
              isOwnPost={post.pubkey === pubkey}
            />
          )
        })}
      </div>
    )
  }

  // Use portal to render outside parent hierarchy
  if (!mounted) return null
  return createPortal(modalContent, document.body)
}
