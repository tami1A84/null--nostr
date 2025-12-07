'use client'

import { useState, useEffect, useCallback, useRef } from 'react'
import { nip19 } from 'nostr-tools'
import {
  fetchEvents,
  parseProfile,
  signEventNip07,
  createEventTemplate,
  publishEvent,
  subscribeToEvents,
  shortenPubkey,
  formatTimestamp,
  hasNip07,
  DEFAULT_RELAY,
  RELAYS
} from '@/lib/nostr'

export default function TimelineTab({ pubkey, nwcUrl, onZap }) {
  const [posts, setPosts] = useState([])
  const [profiles, setProfiles] = useState({})
  const [loading, setLoading] = useState(true)
  const [reactions, setReactions] = useState({})
  const [userReactions, setUserReactions] = useState(new Set())
  const [userReposts, setUserReposts] = useState(new Set())
  const [zapAnimating, setZapAnimating] = useState(null)
  const [likeAnimating, setLikeAnimating] = useState(null)
  const [showPostModal, setShowPostModal] = useState(false)
  const [newPost, setNewPost] = useState('')
  const [posting, setPosting] = useState(false)
  const subRef = useRef(null)

  useEffect(() => {
    loadTimeline()
    return () => {
      if (subRef.current) {
        subRef.current.close()
      }
    }
  }, [])

  const loadTimeline = async () => {
    setLoading(true)
    
    try {
      // Fetch kind 1 events from yabu.me
      const events = await fetchEvents(
        { kinds: [1], limit: 50 },
        [DEFAULT_RELAY]
      )

      setPosts(events)

      // Fetch profiles for authors
      const authorPubkeys = [...new Set(events.map(e => e.pubkey))]
      if (authorPubkeys.length > 0) {
        const profileEvents = await fetchEvents(
          { kinds: [0], authors: authorPubkeys },
          RELAYS
        )

        const profileMap = {}
        for (const event of profileEvents) {
          const p = parseProfile(event)
          if (p) profileMap[event.pubkey] = p
        }
        setProfiles(profileMap)
      }

      // Fetch reactions
      const eventIds = events.map(e => e.id)
      if (eventIds.length > 0) {
        const reactionEvents = await fetchEvents(
          { kinds: [7], '#e': eventIds },
          [DEFAULT_RELAY]
        )

        const reactionCounts = {}
        for (const r of reactionEvents) {
          const eid = r.tags.find(t => t[0] === 'e')?.[1]
          if (eid) {
            reactionCounts[eid] = (reactionCounts[eid] || 0) + 1
            if (pubkey && r.pubkey === pubkey) {
              setUserReactions(prev => new Set([...prev, eid]))
            }
          }
        }
        setReactions(reactionCounts)
      }

      // Subscribe to new events
      subscribeToNewPosts()
    } catch (e) {
      console.error('Failed to load timeline:', e)
    } finally {
      setLoading(false)
    }
  }

  const subscribeToNewPosts = () => {
    subRef.current = subscribeToEvents(
      { kinds: [1], since: Math.floor(Date.now() / 1000) },
      [DEFAULT_RELAY],
      async (event) => {
        setPosts(prev => {
          if (prev.some(p => p.id === event.id)) return prev
          return [event, ...prev].slice(0, 100)
        })

        if (!profiles[event.pubkey]) {
          const profileEvents = await fetchEvents(
            { kinds: [0], authors: [event.pubkey], limit: 1 },
            RELAYS
          )

          if (profileEvents.length > 0) {
            const p = parseProfile(profileEvents[0])
            if (p) {
              setProfiles(prev => ({ ...prev, [event.pubkey]: p }))
            }
          }
        }
      },
      () => {}
    )
  }

  const handleLike = async (event) => {
    if (!pubkey || !hasNip07() || userReactions.has(event.id)) return

    setLikeAnimating(event.id)
    setTimeout(() => setLikeAnimating(null), 400)

    try {
      const reactionEvent = createEventTemplate(7, '+', [
        ['e', event.id],
        ['p', event.pubkey]
      ])
      reactionEvent.pubkey = pubkey
      
      const signed = await signEventNip07(reactionEvent)
      const success = await publishEvent(signed, [DEFAULT_RELAY])
      
      if (success) {
        setUserReactions(prev => new Set([...prev, event.id]))
        setReactions(prev => ({
          ...prev,
          [event.id]: (prev[event.id] || 0) + 1
        }))
      }
    } catch (e) {
      console.error('Failed to like:', e)
    }
  }

  const handleRepost = async (event) => {
    if (!pubkey || !hasNip07() || userReposts.has(event.id)) return

    try {
      const repostEvent = createEventTemplate(6, JSON.stringify(event), [
        ['e', event.id, DEFAULT_RELAY],
        ['p', event.pubkey]
      ])
      repostEvent.pubkey = pubkey
      
      const signed = await signEventNip07(repostEvent)
      const success = await publishEvent(signed, [DEFAULT_RELAY])
      
      if (success) {
        setUserReposts(prev => new Set([...prev, event.id]))
      }
    } catch (e) {
      console.error('Failed to repost:', e)
    }
  }

  const handleZap = async (event) => {
    if (!nwcUrl) {
      onZap?.()
      return
    }

    setZapAnimating(event.id)
    setTimeout(() => setZapAnimating(null), 300)

    const profile = profiles[event.pubkey]
    if (!profile?.lud16) {
      alert('この投稿者はLightningアドレスを設定していません')
      return
    }

    alert(`⚡ Zap送信\n\n対象: ${profile.name || shortenPubkey(event.pubkey)}\nLN: ${profile.lud16}`)
  }

  const handlePost = async () => {
    if (!newPost.trim() || !pubkey || !hasNip07()) return
    setPosting(true)

    try {
      const event = createEventTemplate(1, newPost.trim())
      event.pubkey = pubkey
      
      const signed = await signEventNip07(event)
      const success = await publishEvent(signed)
      
      if (success) {
        setPosts([signed, ...posts])
        setNewPost('')
        setShowPostModal(false)
      }
    } catch (e) {
      console.error('Failed to post:', e)
      alert('投稿に失敗しました')
    } finally {
      setPosting(false)
    }
  }

  const renderContent = (content) => {
    const urlRegex = /(https?:\/\/[^\s]+)/gi
    const parts = content.split(urlRegex)
    
    return parts.map((part, i) => {
      if (part.match(/^https?:\/\/.*\.(jpg|jpeg|png|gif|webp)(\?.*)?$/i)) {
        return (
          <img 
            key={i} 
            src={part} 
            alt="" 
            className="mt-2 rounded-lg max-h-72 w-full object-cover"
            loading="lazy"
          />
        )
      }
      if (part.match(/^https?:\/\//)) {
        return (
          <a 
            key={i} 
            href={part} 
            target="_blank" 
            rel="noopener noreferrer"
            className="text-[var(--line-green)] hover:underline break-all"
          >
            {part.length > 40 ? part.slice(0, 40) + '...' : part}
          </a>
        )
      }
      return <span key={i}>{part}</span>
    })
  }

  return (
    <div className="min-h-screen pb-16">
      {/* Header */}
      <header className="sticky top-0 z-40 header-blur border-b border-[var(--border-color)]">
        <div className="flex items-center justify-between px-4 h-12">
          <h1 className="text-lg font-semibold text-[var(--text-primary)]">タイムライン</h1>
          <button
            onClick={loadTimeline}
            className="text-[var(--text-secondary)] action-btn p-2"
          >
            <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="23 4 23 10 17 10"/>
              <path d="M20.49 15a9 9 0 11-2.12-9.36L23 10"/>
            </svg>
          </button>
        </div>
      </header>

      {/* Post Modal */}
      {showPostModal && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center modal-overlay" onClick={() => setShowPostModal(false)}>
          <div 
            className="w-full sm:max-w-lg bg-[var(--bg-primary)] rounded-t-2xl sm:rounded-2xl animate-slideUp"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between p-4 border-b border-[var(--border-color)]">
              <button onClick={() => setShowPostModal(false)} className="text-[var(--text-secondary)] action-btn">
                キャンセル
              </button>
              <span className="font-semibold text-[var(--text-primary)]">新規投稿</span>
              <button
                onClick={handlePost}
                disabled={posting || !newPost.trim()}
                className="btn-line text-sm py-1.5 px-4 disabled:opacity-50"
              >
                {posting ? '...' : '投稿'}
              </button>
            </div>
            <div className="p-4">
              <textarea
                value={newPost}
                onChange={(e) => setNewPost(e.target.value)}
                className="w-full bg-transparent resize-none text-[var(--text-primary)] placeholder-[var(--text-tertiary)] outline-none text-base"
                placeholder="いまどうしてる？"
                rows={5}
                autoFocus
              />
            </div>
          </div>
        </div>
      )}

      {/* FAB - Post Button */}
      <button
        onClick={() => setShowPostModal(true)}
        className="fab"
      >
        <svg className="w-6 h-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
          <line x1="12" y1="5" x2="12" y2="19"/>
          <line x1="5" y1="12" x2="19" y2="12"/>
        </svg>
      </button>

      {/* Timeline */}
      <div>
        {loading ? (
          <div className="divide-y divide-[var(--border-color)]">
            {[1, 2, 3, 4].map((i) => (
              <div key={i} className="p-4">
                <div className="flex items-start gap-3">
                  <div className="w-10 h-10 rounded-full skeleton" />
                  <div className="flex-1">
                    <div className="skeleton h-4 w-24 rounded mb-2" />
                    <div className="skeleton h-4 w-full rounded mb-1" />
                    <div className="skeleton h-4 w-2/3 rounded" />
                  </div>
                </div>
              </div>
            ))}
          </div>
        ) : posts.length === 0 ? (
          <div className="px-4 py-16 text-center">
            <div className="w-20 h-20 mx-auto mb-4 rounded-full bg-[var(--bg-secondary)] flex items-center justify-center">
              <svg className="w-10 h-10 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                <rect x="3" y="3" width="18" height="18" rx="2" ry="2"/>
                <line x1="3" y1="9" x2="21" y2="9"/>
                <line x1="9" y1="21" x2="9" y2="9"/>
              </svg>
            </div>
            <p className="text-[var(--text-secondary)]">投稿がありません</p>
          </div>
        ) : (
          <div className="divide-y divide-[var(--border-color)]">
            {posts.map((post, index) => {
              const profile = profiles[post.pubkey]
              const likeCount = reactions[post.id] || 0
              const hasLiked = userReactions.has(post.id)
              const hasReposted = userReposts.has(post.id)
              const isZapping = zapAnimating === post.id
              const isLiking = likeAnimating === post.id

              return (
                <article 
                  key={post.id} 
                  className="animate-fadeIn"
                  style={{ animationDelay: `${Math.min(index * 30, 300)}ms` }}
                >
                  <div className="px-4 py-3">
                    <div className="flex items-start gap-3">
                      <div className="w-10 h-10 rounded-full overflow-hidden bg-[var(--bg-tertiary)] flex-shrink-0">
                        {profile?.picture ? (
                          <img 
                            src={profile.picture} 
                            alt="" 
                            className="w-full h-full object-cover"
                          />
                        ) : (
                          <div className="w-full h-full flex items-center justify-center">
                            <svg className="w-5 h-5 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="currentColor">
                              <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/>
                            </svg>
                          </div>
                        )}
                      </div>
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-1.5 mb-0.5">
                          <span className="font-semibold text-[var(--text-primary)] text-sm truncate">
                            {profile?.name || 'Anonymous'}
                          </span>
                          <span className="text-xs text-[var(--text-tertiary)]">
                            · {formatTimestamp(post.created_at)}
                          </span>
                        </div>
                        
                        <div className="text-[var(--text-primary)] text-sm whitespace-pre-wrap break-words">
                          {renderContent(post.content)}
                        </div>
                        
                        {/* Actions */}
                        <div className="flex items-center gap-8 mt-3">
                          {/* Like - Thumbs Up */}
                          <button
                            onClick={() => handleLike(post)}
                            className={`action-btn flex items-center gap-1.5 text-sm ${
                              hasLiked ? 'text-[var(--line-green)]' : 'text-[var(--text-tertiary)]'
                            } ${isLiking ? 'like-animation' : ''}`}
                          >
                            <svg className="w-5 h-5" viewBox="0 0 24 24" fill={hasLiked ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.8">
                              <path d="M14 9V5a3 3 0 00-3-3l-4 9v11h11.28a2 2 0 002-1.7l1.38-9a2 2 0 00-2-2.3H14zM7 22H4a2 2 0 01-2-2v-7a2 2 0 012-2h3"/>
                            </svg>
                            {likeCount > 0 && <span>{likeCount}</span>}
                          </button>

                          {/* Repost */}
                          <button
                            onClick={() => handleRepost(post)}
                            className={`action-btn flex items-center gap-1.5 text-sm ${
                              hasReposted ? 'text-green-500' : 'text-[var(--text-tertiary)]'
                            }`}
                          >
                            <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                              <polyline points="17 1 21 5 17 9"/>
                              <path d="M3 11V9a4 4 0 014-4h14"/>
                              <polyline points="7 23 3 19 7 15"/>
                              <path d="M21 13v2a4 4 0 01-4 4H3"/>
                            </svg>
                          </button>

                          {/* Zap */}
                          <button
                            onClick={() => handleZap(post)}
                            className={`action-btn flex items-center gap-1.5 text-sm text-[var(--text-tertiary)] ${
                              isZapping ? 'zap-animation text-yellow-500' : ''
                            }`}
                          >
                            <svg className="w-5 h-5" viewBox="0 0 24 24" fill={isZapping ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                              <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
                            </svg>
                          </button>
                        </div>
                      </div>
                    </div>
                  </div>
                </article>
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}
