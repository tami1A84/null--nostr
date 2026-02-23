'use client'

import { useState, useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import {
  fetchEvents,
  parseProfile,
  parseZap,
  fetchMutualFollowsCached,
  fetchProfilesBatch,
  shortenPubkey,
  formatTimestamp,
  RELAYS,
  getDefaultRelay
} from '@/lib/nostr'
import { getImageUrl } from '@/lib/imageUtils'
import { NOSTR_KINDS } from '@/lib/constants'

export default function NotificationModal({ pubkey, onClose, onViewProfile }) {
  const [notifications, setNotifications] = useState([])
  const [profiles, setProfiles] = useState({})
  const [originalPosts, setOriginalPosts] = useState({})
  const [loading, setLoading] = useState(true)
  const [mounted, setMounted] = useState(false)
  const modalRef = useRef(null)

  useEffect(() => {
    setMounted(true)
    return () => setMounted(false)
  }, [])

  useEffect(() => {
    if (pubkey && mounted) {
      loadNotifications()
    }

    const handleClickOutside = (e) => {
      if (modalRef.current && !modalRef.current.contains(e.target)) {
        onClose()
      }
    }

    if (window.innerWidth >= 1024) {
      document.addEventListener('mousedown', handleClickOutside)
      return () => document.removeEventListener('mousedown', handleClickOutside)
    }
  }, [pubkey, mounted])

  const loadNotifications = async () => {
    setLoading(true)
    try {
      const relays = [getDefaultRelay()]

      // 1. Fetch custom emoji reactions and Zaps targeting user in parallel
      const [reactionEvents, zapEvents, mutualFollowPubkeys] = await Promise.all([
        fetchEvents({
          kinds: [NOSTR_KINDS.REACTION],
          '#p': [pubkey],
          limit: 50
        }, relays),
        fetchEvents({
          kinds: [NOSTR_KINDS.ZAP],
          '#p': [pubkey],
          limit: 50
        }, relays),
        fetchMutualFollowsCached(pubkey, relays)
      ])

      // Filter for reactions with custom emojis (NIP-30)
      const customEmojiReactions = reactionEvents.filter(event =>
        event.tags.some(t => t[0] === 'emoji')
      )

      // Parse Zaps
      const parsedZaps = zapEvents.map(parseZap).filter(Boolean)

      // 2. Collect unique event IDs for original posts and pubkeys for reactors
      const originalEventIds = [
        ...new Set([
          ...customEmojiReactions.map(e => {
            const eTag = e.tags.find(t => t[0] === 'e')
            return eTag ? eTag[1] : null
          }),
          ...parsedZaps.map(z => z.targetEventId)
        ].filter(Boolean))
      ]

      const reactorPubkeys = [
        ...new Set([
          ...customEmojiReactions.map(e => e.pubkey),
          ...parsedZaps.map(z => z.pubkey),
          ...mutualFollowPubkeys
        ])
      ]

      // 3. Fetch original posts and profiles in parallel
      const [postEvents, profileMap] = await Promise.all([
        fetchEvents({ ids: originalEventIds }, relays),
        fetchProfilesBatch(reactorPubkeys)
      ])

      // Map original posts
      const postMap = {}
      postEvents.forEach(e => {
        postMap[e.id] = e
      })

      // Handle Birthdays for mutual follows
      const birthdayNotifications = []
      const today = new Date()
      const todayMonthDay = `${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`

      mutualFollowPubkeys.forEach(pk => {
        const profile = profileMap[pk]
        if (profile?.birthday) {
          // Normalize birthday to MM-DD
          const bMatch = profile.birthday.match(/(\d{2})-(\d{2})$/)
          if (bMatch) {
            const bMonthDay = bMatch[0]
            if (bMonthDay === todayMonthDay) {
              birthdayNotifications.push({
                id: `birthday-${pk}-${todayMonthDay}`,
                pubkey: pk,
                type: 'birthday',
                created_at: Math.floor(today.getTime() / 1000)
              })
            }
          }
        }
      })

      // Combine and sort notifications
      const combinedNotifications = [
        ...customEmojiReactions.map(e => ({ ...e, type: 'reaction' })),
        ...parsedZaps.map(z => ({ ...z, type: 'zap' })),
        ...birthdayNotifications
      ].sort((a, b) => b.created_at - a.created_at)

      setOriginalPosts(postMap)
      setProfiles(profileMap)
      setNotifications(combinedNotifications)
    } catch (e) {
      console.error('Failed to load notifications:', e)
    } finally {
      setLoading(false)
    }
  }

  const renderNotificationItem = (notification) => {
    const reactor = profiles[notification.pubkey] || { pubkey: notification.pubkey }

    // Determine target post for Reaction and Zap
    let originalPost = null
    if (notification.type === 'reaction') {
      const eTag = notification.tags.find(t => t[0] === 'e')
      originalPost = originalPosts[eTag?.[1]]
    } else if (notification.type === 'zap') {
      originalPost = originalPosts[notification.targetEventId]
    }

    return (
      <div key={notification.id} className="p-4 border-b border-[var(--border-color)] hover:bg-[var(--bg-secondary)]/30 transition-colors">
        <div className="flex items-start gap-3">
          {/* Reactor Avatar */}
          <div className="relative flex-shrink-0">
            <button
              onClick={() => { onClose(); onViewProfile(notification.pubkey); }}
              className="w-10 h-10 rounded-full overflow-hidden bg-[var(--bg-tertiary)] block"
            >
              {reactor.picture ? (
                <img
                  src={getImageUrl(reactor.picture)}
                  alt=""
                  className="w-full h-full object-cover"
                  referrerPolicy="no-referrer"
                />
              ) : (
                <div className="w-full h-full flex items-center justify-center">
                  <svg className="w-6 h-6 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/>
                  </svg>
                </div>
              )}
            </button>

            {/* Type Icon Overlay */}
            <div className={`absolute -bottom-1 -right-1 w-5 h-5 rounded-full flex items-center justify-center border-2 border-[var(--bg-primary)] ${
              notification.type === 'reaction' ? 'bg-pink-500' :
              notification.type === 'zap' ? 'bg-yellow-500' : 'bg-red-400'
            }`}>
              {notification.type === 'reaction' ? (
                <svg className="w-2.5 h-2.5 text-white" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/>
                </svg>
              ) : notification.type === 'zap' ? (
                <svg className="w-3 h-3 text-white" viewBox="0 0 24 24" fill="currentColor">
                  <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
                </svg>
              ) : (
                <svg className="w-3 h-3 text-white" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                  <path d="M20 21v-8a2 2 0 00-2-2H6a2 2 0 00-2 2v8"/>
                  <path d="M4 16s.5-1 2-1 2.5 2 4 2 2.5-2 4-2 2.5 2 4 2 2-1 2-1"/>
                  <path d="M2 21h20"/>
                  <path d="M7 8v2"/>
                  <path d="M12 8v2"/>
                  <path d="M17 8v2"/>
                </svg>
              )}
            </div>
          </div>

          <div className="flex-1 min-w-0">
            <div className="flex items-center justify-between mb-0.5">
              <span className="font-bold text-[var(--text-primary)] truncate">
                {reactor.name || shortenPubkey(notification.pubkey, 6)}
              </span>
              <span className="text-[10px] text-[var(--text-tertiary)] flex-shrink-0">
                {formatTimestamp(notification.created_at)}
              </span>
            </div>

            {/* Notification Content */}
            {notification.type === 'reaction' ? (
              <div className="flex items-center gap-1.5 mb-2">
                <span className="text-sm text-[var(--text-secondary)]">リアクションしました</span>
                {(() => {
                  const emojiTag = notification.tags.find(t => t[0] === 'emoji')
                  const emojiUrl = emojiTag?.[2]
                  return emojiUrl && (
                    <img
                      src={getImageUrl(emojiUrl)}
                      alt=""
                      className="w-5 h-5 object-contain"
                    />
                  )
                })()}
              </div>
            ) : notification.type === 'zap' ? (
              <div className="mb-2">
                <div className="flex items-center gap-1.5 text-sm text-[var(--text-secondary)]">
                  <span className="font-bold text-yellow-500">⚡ {notification.amount} sats</span>
                  <span>Zapしました</span>
                </div>
                {notification.comment && (
                  <p className="text-sm text-[var(--text-primary)] mt-1 bg-[var(--bg-secondary)] px-2 py-1 rounded-md inline-block">
                    {notification.comment}
                  </p>
                )}
              </div>
            ) : (
              <div className="mb-2">
                <p className="text-sm text-[var(--text-secondary)] leading-relaxed">
                  今日{new Date().getMonth() + 1}月{new Date().getDate()}日は<span className="font-bold text-[var(--text-primary)]">{reactor.name || shortenPubkey(notification.pubkey, 6)}</span>の誕生日です。一緒にお祝いしましょう。
                </p>
              </div>
            )}

            {/* Original Post Snippet (for Reaction and Zap) */}
            {(notification.type === 'reaction' || notification.type === 'zap') && (
              originalPost ? (
                <div className="p-2 bg-[var(--bg-secondary)]/50 border-l-2 border-[var(--border-color)] rounded-r-lg text-xs text-[var(--text-tertiary)] line-clamp-2">
                  {originalPost.content}
                </div>
              ) : (
                <div className="text-[10px] text-[var(--text-tertiary)] italic">
                  元の投稿が見つかりませんでした
                </div>
              )
            )}

            {/* Birthday secondary text */}
            {notification.type === 'birthday' && (
              <div className="text-[10px] text-[var(--text-tertiary)] uppercase tracking-wider">
                誕生日
              </div>
            )}
          </div>
        </div>
      </div>
    )
  }

  if (!mounted) return null

  const modalContent = (
    <>
      {/* Desktop: Modal overlay */}
      <div className="hidden lg:block fixed inset-0 z-40 bg-black/40 backdrop-blur-sm" onClick={onClose} />

      {/* Mobile: Modal container */}
      <div className="lg:hidden fixed inset-x-0 top-0 bottom-16 z-30 bg-[var(--bg-primary)] flex flex-col">
        <header className="sticky top-0 z-10 header-blur border-b border-[var(--border-color)] flex-shrink-0">
          <div className="flex items-center justify-between px-4 h-14">
            <button onClick={onClose} className="p-2 -ml-2 text-[var(--text-primary)]">
              <svg className="w-6 h-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <line x1="18" y1="6" x2="6" y2="18"/>
                <line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
            <h2 className="font-bold text-[var(--text-primary)]">通知</h2>
            <div className="w-10" /> {/* Spacer */}
          </div>
        </header>

        <div className="flex-1 overflow-y-auto">
          {loading ? (
            <div className="flex flex-col items-center justify-center py-20">
              <div className="w-8 h-8 border-2 border-[var(--bg-tertiary)] border-t-[var(--line-green)] rounded-full animate-spin" />
            </div>
          ) : notifications.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-20 px-4 text-center">
              <div className="w-16 h-16 bg-[var(--bg-secondary)] rounded-full flex items-center justify-center mb-4">
                <svg className="w-8 h-8 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                  <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/>
                  <path d="M13.73 21a2 2 0 0 1-3.46 0"/>
                </svg>
              </div>
              <p className="text-[var(--text-secondary)]">通知はまだありません</p>
            </div>
          ) : (
            notifications.map(renderNotificationItem)
          )}
        </div>
      </div>

      {/* Desktop: Modal container */}
      <div
        ref={modalRef}
        className="hidden lg:flex fixed z-40 bg-[var(--bg-primary)] flex-col
          lg:top-1/2 lg:left-1/2 lg:-translate-x-1/2 lg:-translate-y-1/2
          lg:w-full lg:max-w-lg lg:h-[80vh] lg:max-h-[600px] lg:rounded-2xl lg:shadow-2xl lg:border lg:border-[var(--border-color)]"
      >
        <header className="sticky top-0 z-40 header-blur border-b border-[var(--border-color)] flex-shrink-0 lg:rounded-t-2xl">
          <div className="flex items-center justify-between px-4 h-14">
            <h2 className="font-bold text-[var(--text-primary)]">通知</h2>
            <button onClick={onClose} className="p-2 -mr-2 text-[var(--text-tertiary)] hover:text-[var(--text-primary)] transition-colors">
              <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <line x1="18" y1="6" x2="6" y2="18"/>
                <line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>
        </header>

        <div className="flex-1 overflow-y-auto">
          {loading ? (
            <div className="flex flex-col items-center justify-center py-20">
              <div className="w-8 h-8 border-2 border-[var(--bg-tertiary)] border-t-[var(--line-green)] rounded-full animate-spin" />
            </div>
          ) : notifications.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-20 px-4 text-center">
              <div className="w-16 h-16 bg-[var(--bg-secondary)] rounded-full flex items-center justify-center mb-4">
                <svg className="w-8 h-8 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                  <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/>
                  <path d="M13.73 21a2 2 0 0 1-3.46 0"/>
                </svg>
              </div>
              <p className="text-[var(--text-secondary)]">通知はまだありません</p>
            </div>
          ) : (
            notifications.map(renderNotificationItem)
          )}
        </div>
      </div>
    </>
  )

  return createPortal(modalContent, document.body)
}
