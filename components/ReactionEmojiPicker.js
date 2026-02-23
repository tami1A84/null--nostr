'use client'

import { useState, useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import { fetchEvents, getDefaultRelay } from '@/lib/nostr'
import { getCachedEmoji, setCachedEmoji } from '@/lib/cache'
import { getImageUrl } from '@/lib/imageUtils'

const KIND_EMOJI_LIST = 10030
const KIND_EMOJI_SET = 30030

export default function ReactionEmojiPicker({ pubkey, onSelect, onClose }) {
  const [emojis, setEmojis] = useState([])
  const [emojiSets, setEmojiSets] = useState([])
  const [loading, setLoading] = useState(true)
  const [searchQuery, setSearchQuery] = useState('')
  const [activeTab, setActiveTab] = useState('all')
  const [mounted, setMounted] = useState(false)

  useEffect(() => {
    setMounted(true)
    document.body.style.overflow = 'hidden'
    return () => { document.body.style.overflow = '' }
  }, [])

  // Load custom emojis
  useEffect(() => {
    if (pubkey) {
      const cached = getCachedEmoji(pubkey)
      if (cached) {
        setEmojis(cached.emojis || [])
        setEmojiSets(cached.emojiSets || [])
        setLoading(false)
        return
      }
      loadEmojis()
    } else {
      setLoading(false)
    }
  }, [pubkey])

  const loadEmojis = async () => {
    setLoading(true)
    try {
      const relays = [getDefaultRelay()]

      const events = await fetchEvents({
        kinds: [KIND_EMOJI_LIST],
        authors: [pubkey],
        limit: 1
      }, relays)

      const individualEmojis = []
      const setPointers = []

      if (events.length > 0) {
        const tags = events[0].tags
        for (const tag of tags) {
          if (tag[0] === 'emoji' && tag[1] && tag[2]) {
            individualEmojis.push({
              shortcode: tag[1],
              url: tag[2],
              source: 'user'
            })
          } else if (tag[0] === 'a' && tag[1]?.startsWith('30030:')) {
            setPointers.push(tag[1])
          }
        }
      }

      const loadedSets = []
      for (const pointer of setPointers) {
        const parts = pointer.split(':')
        if (parts.length >= 3) {
          const [, author, ...dTagParts] = parts
          const dTag = dTagParts.join(':')

          try {
            const setEvents = await fetchEvents({
              kinds: [KIND_EMOJI_SET],
              authors: [author],
              '#d': [dTag],
              limit: 1
            }, relays)

            if (setEvents.length > 0) {
              const setEvent = setEvents[0]
              const setName = setEvent.tags.find(t => t[0] === 'title')?.[1] ||
                setEvent.tags.find(t => t[0] === 'd')?.[1] ||
                'Emoji Set'

              const setEmojisArr = setEvent.tags
                .filter(t => t[0] === 'emoji' && t[1] && t[2])
                .map(t => ({
                  shortcode: t[1],
                  url: t[2],
                  source: setName
                }))

              if (setEmojisArr.length > 0) {
                loadedSets.push({
                  name: setName,
                  emojis: setEmojisArr,
                  pointer
                })
              }
            }
          } catch (e) {
            console.error('Failed to load emoji set:', e)
          }
        }
      }

      setEmojis(individualEmojis)
      setEmojiSets(loadedSets)
      setCachedEmoji(pubkey, { emojis: individualEmojis, emojiSets: loadedSets })
    } catch (e) {
      console.error('Failed to load emojis:', e)
    }
    setLoading(false)
  }

  const allCustomEmojis = [
    ...emojis,
    ...emojiSets.flatMap(set => set.emojis)
  ]

  const filteredEmojis = searchQuery
    ? allCustomEmojis.filter(e => e.shortcode.toLowerCase().includes(searchQuery.toLowerCase()))
    : activeTab === 'all'
      ? allCustomEmojis
      : activeTab === 'user'
        ? emojis
        : emojiSets.find(s => s.name === activeTab)?.emojis || []

  const handleCustomSelect = (emoji) => {
    onSelect({ type: 'custom', shortcode: emoji.shortcode, url: emoji.url })
  }

  const tabs = [
    { id: 'all', name: '\u3059\u3079\u3066' },
    ...(emojis.length > 0 ? [{ id: 'user', name: '\u500B\u5225' }] : []),
    ...emojiSets.map(s => ({ id: s.name, name: s.name }))
  ]

  const hasCustomEmojis = allCustomEmojis.length > 0

  if (!mounted) return null

  return createPortal(
    <div className="fixed inset-0 z-[70] flex items-end justify-center" onClick={onClose}>
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/40" />

      {/* Bottom sheet */}
      <div
        className="relative w-full max-w-lg bg-[var(--bg-primary)] rounded-t-2xl shadow-2xl overflow-hidden animate-bottomSheetUp"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Handle bar */}
        <div className="flex justify-center pt-3 pb-1">
          <div className="w-10 h-1 rounded-full bg-[var(--text-tertiary)] opacity-40" />
        </div>

        {/* Header */}
        <div className="flex items-center justify-between px-4 pb-2">
          <span className="text-sm font-semibold text-[var(--text-primary)]">リアクション</span>
          <button
            onClick={onClose}
            className="p-1.5 text-[var(--text-tertiary)] hover:text-[var(--text-primary)]"
          >
            <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <line x1="18" y1="6" x2="6" y2="18"/>
              <line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>

        {/* Custom Emoji Section */}
        {pubkey && (
          <>
            {/* Search */}
            <div className="px-4 py-2">
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="カスタム絵文字を検索..."
                className="w-full px-3 py-2 bg-[var(--bg-secondary)] rounded-lg text-sm text-[var(--text-primary)] outline-none"
              />
            </div>

            {/* Tabs */}
            {!searchQuery && tabs.length > 1 && (
              <div className="flex gap-1.5 px-4 pb-2 overflow-x-auto">
                {tabs.map(tab => (
                  <button
                    key={tab.id}
                    onClick={() => setActiveTab(tab.id)}
                    className={`px-3 py-1 text-xs rounded-full whitespace-nowrap ${
                      activeTab === tab.id
                        ? 'bg-[var(--line-green)] text-white'
                        : 'bg-[var(--bg-tertiary)] text-[var(--text-secondary)]'
                    }`}
                  >
                    {tab.name}
                  </button>
                ))}
              </div>
            )}

            {/* Custom Emoji Grid */}
            <div className="px-4 pb-4 overflow-y-auto" style={{ maxHeight: '200px' }}>
              {loading ? (
                <div className="text-center py-4 text-[var(--text-tertiary)] text-sm">
                  読み込み中...
                </div>
              ) : !hasCustomEmojis ? (
                <div className="text-center py-4 text-[var(--text-tertiary)] text-sm">
                  <p>カスタム絵文字がありません</p>
                  <p className="mt-1 text-xs">ミニアプリから絵文字セットを追加できます</p>
                </div>
              ) : filteredEmojis.length === 0 ? (
                <div className="text-center py-4 text-[var(--text-tertiary)] text-sm">
                  該当する絵文字がありません
                </div>
              ) : (
                <div className="grid grid-cols-8 gap-1.5">
                  {filteredEmojis.map((emoji, i) => (
                    <button
                      key={`${emoji.shortcode}-${i}`}
                      onClick={() => handleCustomSelect(emoji)}
                      className="aspect-square p-1 rounded-xl hover:bg-[var(--bg-tertiary)] active:scale-90 transition-all"
                      title={`:${emoji.shortcode}: (${emoji.source})`}
                    >
                      <img
                        src={getImageUrl(emoji.url)}
                        alt={emoji.shortcode}
                        className="w-full h-full object-contain"
                        loading="lazy"
                      />
                    </button>
                  ))}
                </div>
              )}
            </div>
          </>
        )}

        {/* Safe area bottom padding */}
        <div className="h-[env(safe-area-inset-bottom,0px)]" />
      </div>
    </div>,
    document.body
  )
}
