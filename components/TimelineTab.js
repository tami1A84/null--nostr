'use client'

import { useState, useEffect, useCallback, useRef, useImperativeHandle, forwardRef } from 'react'
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
  addToMuteList,
  fetchMuteList,
  fetchLightningInvoice,
  copyToClipboard,
  unlikeEvent,
  unrepostEvent,
  deleteEvent,
  getReadRelays,
  getWriteRelays,
  RELAYS
} from '@/lib/nostr'
import PostItem from './PostItem'
import UserProfileView from './UserProfileView'
import SearchModal from './SearchModal'

const TimelineTab = forwardRef(function TimelineTab({ pubkey }, ref) {
  const [posts, setPosts] = useState([])
  const [profiles, setProfiles] = useState({})
  const [loading, setLoading] = useState(true)
  const [reactions, setReactions] = useState({})
  const [userReactions, setUserReactions] = useState(new Set())
  const [userReposts, setUserReposts] = useState(new Set())
  const [userReactionIds, setUserReactionIds] = useState({}) // eventId -> reactionEventId
  const [userRepostIds, setUserRepostIds] = useState({}) // eventId -> repostEventId
  const [zapAnimating, setZapAnimating] = useState(null)
  const [likeAnimating, setLikeAnimating] = useState(null)
  const [showPostModal, setShowPostModal] = useState(false)
  const [newPost, setNewPost] = useState('')
  const [posting, setPosting] = useState(false)
  const [viewingProfile, setViewingProfile] = useState(null)
  const [mutedPubkeys, setMutedPubkeys] = useState(new Set())
  const [showZapModal, setShowZapModal] = useState(null)
  const [zapAmount, setZapAmount] = useState('')
  const [zapComment, setZapComment] = useState('')
  const [zapping, setZapping] = useState(false)
  const [showSearch, setShowSearch] = useState(false)
  const subRef = useRef(null)
  const longPressTimerRef = useRef(null)

  // Expose refresh function to parent
  useImperativeHandle(ref, () => ({
    refresh: loadTimeline
  }))

  // Lock body scroll when modal is open
  useEffect(() => {
    if (showPostModal || viewingProfile || showZapModal || showSearch) {
      document.body.style.overflow = 'hidden'
    } else {
      document.body.style.overflow = ''
    }
    return () => {
      document.body.style.overflow = ''
    }
  }, [showPostModal, viewingProfile, showZapModal, showSearch])

  useEffect(() => {
    loadTimeline()
    loadMuteList()
    return () => {
      if (subRef.current) {
        subRef.current.close()
      }
    }
  }, [])

  const loadMuteList = async () => {
    if (!pubkey) return
    try {
      const muteList = await fetchMuteList(pubkey)
      setMutedPubkeys(new Set(muteList.pubkeys))
    } catch (e) {
      console.error('Failed to load mute list:', e)
    }
  }

  const handleAvatarClick = (targetPubkey, profile) => {
    if (targetPubkey !== pubkey) {
      setViewingProfile(targetPubkey)
    }
  }

  const handleMute = async (targetPubkey) => {
    if (!pubkey) return
    try {
      await addToMuteList(pubkey, 'pubkey', targetPubkey)
      setMutedPubkeys(prev => new Set([...prev, targetPubkey]))
      alert('ミュートしました')
    } catch (e) {
      console.error('Failed to mute:', e)
      alert('ミュートに失敗しました')
    }
  }

  const loadTimeline = async () => {
    setLoading(true)
    const readRelays = getReadRelays()
    
    try {
      // Fetch notes and reposts in parallel for faster loading
      const [notes, reposts] = await Promise.all([
        fetchEvents({ kinds: [1], limit: 50 }, readRelays),
        fetchEvents({ kinds: [6], limit: 30 }, readRelays)
      ])

      // Parse reposted events
      const repostData = []
      const originalAuthors = new Set()

      for (const repost of reposts) {
        try {
          if (repost.content) {
            const originalEvent = JSON.parse(repost.content)
            originalAuthors.add(originalEvent.pubkey)
            repostData.push({
              ...originalEvent,
              _repostedBy: repost.pubkey,
              _repostTime: repost.created_at,
              _isRepost: true,
              _repostId: repost.id
            })
          }
        } catch (e) {
          // Skip invalid reposts
        }
      }

      // Combine and sort
      const allPosts = [...notes, ...repostData].sort((a, b) => {
        const timeA = a._repostTime || a.created_at
        const timeB = b._repostTime || b.created_at
        return timeB - timeA
      })

      setPosts(allPosts)
      
      // Get unique authors
      const authors = new Set()
      allPosts.forEach(p => {
        authors.add(p.pubkey)
        if (p._repostedBy) authors.add(p._repostedBy)
      })
      originalAuthors.forEach(a => authors.add(a))
      
      // Fetch profiles
      if (authors.size > 0) {
        const profileEvents = await fetchEvents(
          { kinds: [0], authors: Array.from(authors), limit: authors.size },
          RELAYS
        )
        
        const profileMap = {}
        for (const event of profileEvents) {
          profileMap[event.pubkey] = parseProfile(event)
        }
        setProfiles(profileMap)
      }

      // Fetch reactions
      if (pubkey && allPosts.length > 0) {
        const eventIds = allPosts.map(p => p.id)
        const [reactionEvents, myRepostEvents] = await Promise.all([
          fetchEvents({ kinds: [7], '#e': eventIds, limit: 500 }, readRelays),
          fetchEvents({ kinds: [6], authors: [pubkey], limit: 100 }, readRelays)
        ])
        
        const reactionCounts = {}
        const myReactions = new Set()
        const myReactionIds = {} // eventId -> reactionEventId
        
        for (const event of reactionEvents) {
          const targetId = event.tags.find(t => t[0] === 'e')?.[1]
          if (targetId) {
            reactionCounts[targetId] = (reactionCounts[targetId] || 0) + 1
            if (event.pubkey === pubkey) {
              myReactions.add(targetId)
              myReactionIds[targetId] = event.id
            }
          }
        }

        const myReposts = new Set()
        const myRepostIdsMap = {} // eventId -> repostEventId
        for (const repost of myRepostEvents) {
          const targetId = repost.tags.find(t => t[0] === 'e')?.[1]
          if (targetId) {
            myReposts.add(targetId)
            myRepostIdsMap[targetId] = repost.id
          }
        }

        setReactions(reactionCounts)
        setUserReactions(myReactions)
        setUserReposts(myReposts)
        setUserReactionIds(myReactionIds)
        setUserRepostIds(myRepostIdsMap)
      }
    } catch (e) {
      console.error('Failed to load timeline:', e)
    } finally {
      setLoading(false)
    }
  }

  const handleLike = async (event) => {
    if (!pubkey || !hasNip07() || userReactions.has(event.id)) return

    setLikeAnimating(event.id)
    setTimeout(() => setLikeAnimating(null), 300)

    try {
      const reactionEvent = createEventTemplate(7, '+', [
        ['e', event.id],
        ['p', event.pubkey]
      ])
      reactionEvent.pubkey = pubkey
      
      const signed = await signEventNip07(reactionEvent)
      const success = await publishEvent(signed)

      if (success) {
        setUserReactions(prev => new Set([...prev, event.id]))
        setUserReactionIds(prev => ({ ...prev, [event.id]: signed.id }))
        setReactions(prev => ({
          ...prev,
          [event.id]: (prev[event.id] || 0) + 1
        }))
      }
    } catch (e) {
      console.error('Failed to like:', e)
    }
  }

  const handleUnlike = async (event, reactionEventId) => {
    if (!pubkey || !hasNip07() || !userReactions.has(event.id)) return

    try {
      const result = await unlikeEvent(reactionEventId)
      
      if (result.success) {
        setUserReactions(prev => {
          const newSet = new Set(prev)
          newSet.delete(event.id)
          return newSet
        })
        setUserReactionIds(prev => {
          const newIds = { ...prev }
          delete newIds[event.id]
          return newIds
        })
        setReactions(prev => ({
          ...prev,
          [event.id]: Math.max(0, (prev[event.id] || 1) - 1)
        }))
      }
    } catch (e) {
      console.error('Failed to unlike:', e)
    }
  }

  const handleRepost = async (event) => {
    if (!pubkey || !hasNip07() || userReposts.has(event.id)) return

    try {
      const repostEvent = createEventTemplate(6, JSON.stringify(event), [
        ['e', event.id, '', 'mention'],
        ['p', event.pubkey]
      ])
      repostEvent.pubkey = pubkey
      
      const signed = await signEventNip07(repostEvent)
      const success = await publishEvent(signed)
      
      if (success) {
        setUserReposts(prev => new Set([...prev, event.id]))
        setUserRepostIds(prev => ({ ...prev, [event.id]: signed.id }))
      }
    } catch (e) {
      console.error('Failed to repost:', e)
    }
  }

  const handleUnrepost = async (event, repostEventId) => {
    if (!pubkey || !hasNip07() || !userReposts.has(event.id)) return

    try {
      const result = await unrepostEvent(repostEventId)
      
      if (result.success) {
        setUserReposts(prev => {
          const newSet = new Set(prev)
          newSet.delete(event.id)
          return newSet
        })
        setUserRepostIds(prev => {
          const newIds = { ...prev }
          delete newIds[event.id]
          return newIds
        })
      }
    } catch (e) {
      console.error('Failed to unrepost:', e)
    }
  }

  const handleDelete = async (eventId) => {
    if (!confirm('この投稿を削除しますか？')) return
    
    try {
      const result = await deleteEvent(eventId)
      if (result.success) {
        // Remove from posts list
        setPosts(prev => prev.filter(p => p.id !== eventId && p._repostId !== eventId))
      }
    } catch (e) {
      console.error('Failed to delete:', e)
      alert('削除に失敗しました')
    }
  }

  // Get default zap amount from localStorage
  const getDefaultZapAmount = () => {
    if (typeof window !== 'undefined') {
      const saved = localStorage.getItem('defaultZapAmount')
      return saved ? parseInt(saved, 10) : 21
    }
    return 21
  }

  // Quick zap (single tap)
  const handleZap = async (event) => {
    const profile = profiles[event.pubkey]
    if (!profile?.lud16) {
      alert('この投稿者はLightningアドレスを設定していません')
      return
    }

    setZapAnimating(event.id)
    setTimeout(() => setZapAnimating(null), 600)

    const amount = getDefaultZapAmount()

    try {
      setZapping(true)
      const result = await fetchLightningInvoice(profile.lud16, amount)
      
      if (result.invoice) {
        const copied = await copyToClipboard(result.invoice)
        if (copied) {
          alert(`⚡ ${amount} sats のインボイスをコピーしました！\n\nお好きなLightningウォレットで支払いできます。`)
        } else {
          // Fallback: show invoice for manual copy
          prompt('インボイスをコピーしてください:', result.invoice)
        }
      }
    } catch (e) {
      console.error('Failed to create invoice:', e)
      alert(`インボイスの作成に失敗しました: ${e.message}`)
    } finally {
      setZapping(false)
    }
  }

  // Long press to open zap modal
  const handleZapLongPressStart = (event) => {
    longPressTimerRef.current = setTimeout(() => {
      const profile = profiles[event.pubkey]
      if (!profile?.lud16) {
        alert('この投稿者はLightningアドレスを設定していません')
        return
      }
      setShowZapModal({ event, profile })
      setZapAmount(getDefaultZapAmount().toString())
      setZapComment('')
    }, 500)
  }

  const handleZapLongPressEnd = () => {
    if (longPressTimerRef.current) {
      clearTimeout(longPressTimerRef.current)
      longPressTimerRef.current = null
    }
  }

  // Send custom zap from modal
  const handleSendCustomZap = async () => {
    if (!showZapModal || zapping) return

    const amount = parseInt(zapAmount, 10)
    if (!amount || amount < 1) {
      alert('有効な金額を入力してください')
      return
    }

    try {
      setZapping(true)
      const result = await fetchLightningInvoice(
        showZapModal.profile.lud16, 
        amount, 
        zapComment
      )
      
      if (result.invoice) {
        const copied = await copyToClipboard(result.invoice)
        setShowZapModal(null)
        if (copied) {
          alert(`⚡ ${amount} sats のインボイスをコピーしました！\n\nお好きなLightningウォレットで支払いできます。`)
        } else {
          prompt('インボイスをコピーしてください:', result.invoice)
        }
      }
    } catch (e) {
      console.error('Failed to create invoice:', e)
      alert(`インボイスの作成に失敗しました: ${e.message}`)
    } finally {
      setZapping(false)
    }
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

  return (
    <div className="min-h-screen pb-16">
      {/* Header */}
      <header className="sticky top-0 z-40 header-blur border-b border-[var(--border-color)]">
        <div className="flex items-center justify-between px-4 h-12">
          <h1 className="text-lg font-semibold text-[var(--text-primary)]">タイムライン</h1>
          <button
            onClick={() => setShowSearch(true)}
            className="text-[var(--text-secondary)] action-btn p-2"
          >
            <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="11" cy="11" r="8"/>
              <line x1="21" y1="21" x2="16.65" y2="16.65"/>
            </svg>
          </button>
        </div>
      </header>

      {/* Search Modal */}
      {showSearch && (
        <SearchModal
          pubkey={pubkey}
          onClose={() => setShowSearch(false)}
          onViewProfile={(pk) => {
            setShowSearch(false)
            setViewingProfile(pk)
          }}
        />
      )}

      {/* Post Modal */}
      {showPostModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setShowPostModal(false)}>
          <div 
            className="w-full h-full sm:h-auto sm:max-w-lg bg-[var(--bg-primary)] sm:rounded-2xl flex flex-col overflow-hidden animate-scaleIn"
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
            <div className="flex-1 p-4">
              <textarea
                value={newPost}
                onChange={(e) => setNewPost(e.target.value)}
                className="w-full h-full min-h-[200px] bg-transparent resize-none text-[var(--text-primary)] placeholder-[var(--text-tertiary)] outline-none text-base"
                placeholder="いまどうしてる？"
                autoFocus
              />
            </div>
          </div>
        </div>
      )}

      {/* Zap Modal */}
      {showZapModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setShowZapModal(null)}>
          <div 
            className="w-full max-w-sm mx-4 bg-[var(--bg-primary)] rounded-2xl overflow-hidden animate-scaleIn"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="p-4 border-b border-[var(--border-color)]">
              <div className="flex items-center justify-between">
                <h3 className="text-lg font-bold text-[var(--text-primary)]">⚡ Zap送信</h3>
                <button onClick={() => setShowZapModal(null)} className="text-[var(--text-tertiary)] action-btn p-1">
                  <svg className="w-6 h-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <line x1="18" y1="6" x2="6" y2="18"/>
                    <line x1="6" y1="6" x2="18" y2="18"/>
                  </svg>
                </button>
              </div>
              <p className="text-sm text-[var(--text-secondary)] mt-1">
                {showZapModal.profile.name || shortenPubkey(showZapModal.event.pubkey)}
              </p>
            </div>

            <div className="p-4 space-y-4">
              <div>
                <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1.5">金額 (sats)</label>
                <input
                  type="number"
                  value={zapAmount}
                  onChange={(e) => setZapAmount(e.target.value)}
                  className="input-line"
                  placeholder="21"
                  min="1"
                />
              </div>

              <div className="flex flex-wrap gap-2">
                {[21, 100, 500, 1000, 5000].map(amount => (
                  <button
                    key={amount}
                    onClick={() => setZapAmount(amount.toString())}
                    className={`px-3 py-1 rounded-full text-sm ${
                      zapAmount === amount.toString()
                        ? 'bg-yellow-500 text-black'
                        : 'bg-[var(--bg-secondary)] text-[var(--text-primary)]'
                    }`}
                  >
                    ⚡{amount}
                  </button>
                ))}
              </div>

              <div>
                <label className="block text-sm font-medium text-[var(--text-secondary)] mb-1.5">コメント (任意)</label>
                <input
                  type="text"
                  value={zapComment}
                  onChange={(e) => setZapComment(e.target.value)}
                  className="input-line"
                  placeholder="Zap!"
                  maxLength={100}
                />
              </div>
            </div>

            <div className="p-4 border-t border-[var(--border-color)]">
              <button
                onClick={handleSendCustomZap}
                disabled={zapping || !zapAmount}
                className="w-full btn-line py-3 disabled:opacity-50"
              >
                {zapping ? '作成中...' : `⚡ ${zapAmount || 0} sats のインボイスを作成`}
              </button>
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
            {posts
              .filter(post => !mutedPubkeys.has(post.pubkey))
              .map((post, index) => {
              const profile = profiles[post.pubkey]
              const likeCount = reactions[post.id] || 0
              const hasLiked = userReactions.has(post.id)
              const hasReposted = userReposts.has(post.id)
              const isZapping = zapAnimating === post.id
              const isLiking = likeAnimating === post.id

              return (
                <div 
                  key={post._repostId || post.id} 
                  className="animate-fadeIn"
                  style={{ animationDelay: `${Math.min(index * 30, 300)}ms` }}
                >
                  <PostItem
                    post={post}
                    profile={profile}
                    profiles={profiles}
                    likeCount={likeCount}
                    hasLiked={hasLiked}
                    hasReposted={hasReposted}
                    myReactionId={userReactionIds[post.id]}
                    myRepostId={userRepostIds[post.id]}
                    isLiking={isLiking}
                    isZapping={isZapping}
                    onLike={handleLike}
                    onUnlike={handleUnlike}
                    onRepost={handleRepost}
                    onUnrepost={handleUnrepost}
                    onZap={handleZap}
                    onZapLongPress={handleZapLongPressStart}
                    onZapLongPressEnd={handleZapLongPressEnd}
                    onAvatarClick={handleAvatarClick}
                    onMute={handleMute}
                    onDelete={handleDelete}
                    isOwnPost={post.pubkey === pubkey}
                    isRepost={post._isRepost}
                    repostedBy={post._repostedBy ? profiles[post._repostedBy] || { pubkey: post._repostedBy } : null}
                  />
                </div>
              )
            })}
          </div>
        )}
      </div>

      {/* User Profile View */}
      {viewingProfile && (
        <UserProfileView
          targetPubkey={viewingProfile}
          myPubkey={pubkey}
          onClose={() => setViewingProfile(null)}
          onMute={(targetPubkey) => {
            setMutedPubkeys(prev => new Set([...prev, targetPubkey]))
          }}
        />
      )}
    </div>
  )
})

export default TimelineTab
