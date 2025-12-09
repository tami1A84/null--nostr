'use client'

import { useState, useEffect, useRef } from 'react'
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
  RELAYS
} from '@/lib/nostr'
import PostItem from './PostItem'

export default function SearchModal({ pubkey, onClose, onViewProfile }) {
  const [query, setQuery] = useState('')
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
  const inputRef = useRef(null)

  useEffect(() => {
    // Load recent searches from localStorage
    const saved = localStorage.getItem('recentSearches')
    if (saved) {
      try {
        setRecentSearches(JSON.parse(saved).slice(0, 5))
      } catch (e) {}
    }

    // Focus input on mount
    inputRef.current?.focus()
  }, [])

  const saveRecentSearch = (q) => {
    const updated = [q, ...recentSearches.filter(s => s !== q)].slice(0, 5)
    setRecentSearches(updated)
    localStorage.setItem('recentSearches', JSON.stringify(updated))
  }

  const handleSearch = async (searchQuery = query) => {
    if (!searchQuery.trim()) return

    setSearching(true)
    setHasSearched(true)
    saveRecentSearch(searchQuery.trim())

    try {
      const notes = await searchNotes(searchQuery, { limit: 50 })
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
        }
      }
    } catch (e) {
      console.error('Search error:', e)
    } finally {
      setSearching(false)
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

  // Like handler
  const handleLike = async (post) => {
    if (!pubkey || userReactions.has(post.id)) return
    
    setLikeAnimating(post.id)
    
    try {
      const template = createEventTemplate(7, '+', [
        ['e', post.id],
        ['p', post.pubkey]
      ])
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
    if (userReposts.has(post.id)) {
      alert('既にリポストしています')
      return
    }

    try {
      const template = createEventTemplate(6, JSON.stringify(post), [
        ['e', post.id, ''],
        ['p', post.pubkey]
      ])
      const signed = await signEventNip07(template)
      await publishEvent(signed, getWriteRelays())
      
      setUserReposts(prev => new Set([...prev, post.id]))
      alert('リポストしました！')
    } catch (e) {
      console.error('Failed to repost:', e)
      alert('リポストに失敗しました')
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

  return (
    <div className="fixed inset-0 z-50 bg-[var(--bg-primary)]">
      {/* Header with search bar */}
      <header className="sticky top-0 z-40 header-blur border-b border-[var(--border-color)]">
        <div className="flex items-center gap-2 px-3 h-14">
          <button
            onClick={onClose}
            className="w-10 h-10 flex items-center justify-center action-btn flex-shrink-0"
          >
            <svg className="w-6 h-6 text-[var(--text-primary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <line x1="19" y1="12" x2="5" y2="12"/>
              <polyline points="12 19 5 12 12 5"/>
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
              placeholder="キーワードを検索"
              className="w-full h-10 pl-10 pr-10 rounded-full bg-[var(--bg-secondary)] text-[var(--text-primary)] placeholder-[var(--text-tertiary)] outline-none text-sm border border-transparent focus:border-[var(--line-green)] transition-colors"
            />
            {query && (
              <button
                onClick={() => setQuery('')}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-[var(--text-tertiary)]"
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

      {/* Content */}
      <div className="h-[calc(100vh-56px)] overflow-y-auto pb-20">
        {!hasSearched ? (
          /* Pre-search content - Recent searches only */
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
                    クリア
                  </button>
                </div>
                <div className="flex flex-wrap gap-2">
                  {recentSearches.map((search, i) => (
                    <button
                      key={i}
                      onClick={() => { setQuery(search); handleSearch(search); }}
                      className="px-3 py-1.5 rounded-full bg-[var(--bg-secondary)] text-sm text-[var(--text-primary)] hover:bg-[var(--bg-tertiary)]"
                    >
                      {search}
                    </button>
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
                  キーワードを入力して検索
                </p>
              </div>
            )}
          </div>
        ) : searching ? (
          /* Loading */
          <div className="flex flex-col items-center justify-center py-16">
            <div className="w-10 h-10 border-3 border-[var(--bg-tertiary)] border-t-[var(--line-green)] rounded-full animate-spin mb-3" />
            <p className="text-[var(--text-secondary)] text-sm">検索中...</p>
          </div>
        ) : (
          /* Post Results */
          results.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-16 px-4">
              <div className="w-16 h-16 mb-4 rounded-full bg-[var(--bg-secondary)] flex items-center justify-center">
                <svg className="w-8 h-8 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                  <circle cx="11" cy="11" r="8"/>
                  <line x1="21" y1="21" x2="16.65" y2="16.65"/>
                </svg>
              </div>
              <p className="text-[var(--text-secondary)] text-center text-sm">
                「{query}」に一致する投稿が見つかりませんでした
              </p>
            </div>
          ) : (
            <div className="divide-y divide-[var(--border-color)]">
              <div className="px-4 py-2 bg-[var(--bg-secondary)]">
                <p className="text-xs text-[var(--text-secondary)]">
                  <span className="font-semibold text-[var(--text-primary)]">{results.length}</span> 件の結果
                </p>
              </div>
              {results.map(post => (
                <PostItem
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
                />
              ))}
            </div>
          )
        )}
      </div>
    </div>
  )
}
