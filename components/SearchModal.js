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
  const [mounted, setMounted] = useState(false)
  const inputRef = useRef(null)
  const modalRef = useRef(null)

  useEffect(() => {
    setMounted(true)
    return () => setMounted(false)
  }, [])

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
                placeholder="キーワードを検索"
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
                placeholder="キーワードを検索"
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
                    className="flex items-center gap-1 px-3 py-1.5 rounded-full bg-[var(--bg-secondary)] text-sm"
                  >
                    <button
                      onClick={() => { setQuery(search); handleSearch(search); }}
                      className="text-[var(--text-primary)] hover:text-[var(--line-green)]"
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
                キーワードを入力して検索
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
            「{query}」に一致する投稿が見つかりませんでした
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
  }

  // Use portal to render outside parent hierarchy
  if (!mounted) return null
  return createPortal(modalContent, document.body)
}
