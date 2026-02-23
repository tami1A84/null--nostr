'use client'

import { useState, useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import {
  fetchEvents,
  parseProfile,
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

      // 1. Fetch custom emoji reactions targeting user
      const reactionEvents = await fetchEvents({
        kinds: [NOSTR_KINDS.REACTION],
        '#p': [pubkey],
        limit: 50
      }, relays)

      // Filter for reactions with custom emojis (NIP-30)
      const customEmojiReactions = reactionEvents.filter(event =>
        event.tags.some(t => t[0] === 'emoji')
      )

      if (customEmojiReactions.length === 0) {
        setNotifications([])
        setLoading(false)
        return
      }

      // 2. Collect unique event IDs for original posts and pubkeys for reactors
      const originalEventIds = [...new Set(customEmojiReactions.map(e => {
        const eTag = e.tags.find(t => t[0] === 'e')
        return eTag ? eTag[1] : null
      }).filter(Boolean))]

      const reactorPubkeys = [...new Set(customEmojiReactions.map(e => e.pubkey))]

      // 3. Fetch original posts and profiles in parallel
      const [postEvents, profileEvents] = await Promise.all([
        fetchEvents({ ids: originalEventIds }, relays),
        fetchEvents({ kinds: [0], authors: reactorPubkeys }, relays)
      ])

      // Map original posts
      const postMap = {}
      postEvents.forEach(e => {
        postMap[e.id] = e
      })

      // Map profiles
      const profileMap = {}
      profileEvents.forEach(e => {
        profileMap[e.pubkey] = parseProfile(e)
      })

      setOriginalPosts(postMap)
      setProfiles(profileMap)
      setNotifications(customEmojiReactions)
    } catch (e) {
      console.error('Failed to load notifications:', e)
    } finally {
      setLoading(false)
    }
  }

  const renderNotificationItem = (notification) => {
    const reactor = profiles[notification.pubkey] || { pubkey: notification.pubkey }
    const emojiTag = notification.tags.find(t => t[0] === 'emoji')
    const shortcode = emojiTag?.[1] || ''
    const emojiUrl = emojiTag?.[2] || ''

    const eTag = notification.tags.find(t => t[0] === 'e')
    const originalEventId = eTag?.[1]
    const originalPost = originalPosts[originalEventId]

    return (
      <div key={notification.id} className="p-4 border-b border-[var(--border-color)] hover:bg-[var(--bg-secondary)]/30 transition-colors">
        <div className="flex items-start gap-3">
          {/* Reactor Avatar */}
          <button
            onClick={() => { onClose(); onViewProfile(notification.pubkey); }}
            className="w-10 h-10 rounded-full overflow-hidden bg-[var(--bg-tertiary)] flex-shrink-0"
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

          <div className="flex-1 min-w-0">
            <div className="flex items-center justify-between mb-1">
              <span className="font-bold text-[var(--text-primary)] truncate">
                {reactor.name || shortenPubkey(notification.pubkey, 6)}
              </span>
              <span className="text-xs text-[var(--text-tertiary)] flex-shrink-0">
                {formatTimestamp(notification.created_at)}
              </span>
            </div>

            <div className="flex items-center gap-2 mb-2">
              <span className="text-sm text-[var(--text-secondary)]">リアクションしました</span>
              {emojiUrl && (
                <img
                  src={getImageUrl(emojiUrl)}
                  alt={shortcode}
                  title={`:${shortcode}:`}
                  className="w-6 h-6 object-contain"
                />
              )}
            </div>

            {/* Snippet of original post */}
            {originalPost ? (
              <div className="p-2 bg-[var(--bg-secondary)] rounded-lg text-sm text-[var(--text-tertiary)] line-clamp-2 italic">
                {originalPost.content}
              </div>
            ) : (
              <div className="text-xs text-[var(--text-tertiary)] italic">
                元の投稿が見つかりませんでした
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
