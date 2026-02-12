'use client'

import { useState, useEffect, useRef } from 'react'
import { fetchEvents, getDefaultRelay } from '@/lib/nostr'
import { getCachedEmoji, setCachedEmoji } from '@/lib/cache'
import { getImageUrl } from '@/lib/imageUtils'

const KIND_EMOJI_LIST = 10030
const KIND_EMOJI_SET = 30030

// Frequently used Unicode emojis for quick reactions
const QUICK_EMOJIS = [
  { char: '\u2764\uFE0F', label: 'heart' },
  { char: '\uD83D\uDE02', label: 'joy' },
  { char: '\uD83D\uDE0D', label: 'heart_eyes' },
  { char: '\uD83D\uDE31', label: 'scream' },
  { char: '\uD83D\uDE22', label: 'cry' },
  { char: '\uD83D\uDE4F', label: 'pray' },
  { char: '\uD83D\uDD25', label: 'fire' },
  { char: '\uD83C\uDF89', label: 'tada' },
]

export default function ReactionEmojiPicker({ pubkey, onSelect, onClose, anchorRef }) {
  const [emojis, setEmojis] = useState([])
  const [emojiSets, setEmojiSets] = useState([])
  const [loading, setLoading] = useState(true)
  const [searchQuery, setSearchQuery] = useState('')
  const [activeTab, setActiveTab] = useState('all')
  const pickerRef = useRef(null)
  const [position, setPosition] = useState({ bottom: true })

  // Calculate position relative to anchor
  useEffect(() => {
    if (anchorRef?.current && pickerRef.current) {
      const anchorRect = anchorRef.current.getBoundingClientRect()
      const pickerHeight = 320
      const spaceBelow = window.innerHeight - anchorRect.bottom
      const spaceAbove = anchorRect.top

      setPosition({
        bottom: spaceBelow < pickerHeight && spaceAbove > spaceBelow
      })
    }
  }, [anchorRef])

  // Close on outside click
  useEffect(() => {
    const handleClickOutside = (e) => {
      if (pickerRef.current && !pickerRef.current.contains(e.target)) {
        onClose()
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    document.addEventListener('touchstart', handleClickOutside)
    return () => {
      document.removeEventListener('mousedown', handleClickOutside)
      document.removeEventListener('touchstart', handleClickOutside)
    }
  }, [onClose])

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

  const handleUnicodeSelect = (emoji) => {
    onSelect({ type: 'unicode', content: emoji.char })
  }

  const handleCustomSelect = (emoji) => {
    onSelect({ type: 'custom', shortcode: emoji.shortcode, url: emoji.url })
  }

  const tabs = [
    { id: 'all', name: '\u3059\u3079\u3066' },
    ...(emojis.length > 0 ? [{ id: 'user', name: '\u500B\u5225' }] : []),
    ...emojiSets.map(s => ({ id: s.name, name: s.name }))
  ]

  const hasCustomEmojis = allCustomEmojis.length > 0

  return (
    <div
      ref={pickerRef}
      className={`absolute z-50 ${position.bottom ? 'bottom-full mb-2' : 'top-full mt-2'} left-0 w-72 bg-[var(--bg-primary)] border border-[var(--border-color)] rounded-xl shadow-lg overflow-hidden`}
      onClick={(e) => e.stopPropagation()}
    >
      {/* Quick Unicode Emojis */}
      <div className="p-2 border-b border-[var(--border-color)]">
        <div className="grid grid-cols-8 gap-1">
          {QUICK_EMOJIS.map((emoji) => (
            <button
              key={emoji.label}
              onClick={() => handleUnicodeSelect(emoji)}
              className="aspect-square flex items-center justify-center rounded-lg hover:bg-[var(--bg-tertiary)] transition-colors text-lg"
              title={emoji.label}
            >
              {emoji.char}
            </button>
          ))}
        </div>
      </div>

      {/* Custom Emoji Section */}
      {pubkey && (
        <>
          {/* Search & tabs header */}
          <div className="p-2 border-b border-[var(--border-color)]">
            <div className="flex items-center gap-2">
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder={'\u30AB\u30B9\u30BF\u30E0\u7D75\u6587\u5B57\u3092\u691C\u7D22...'}
                className="flex-1 px-2.5 py-1 bg-[var(--bg-secondary)] rounded-lg text-xs text-[var(--text-primary)] outline-none"
              />
            </div>
          </div>

          {/* Tabs */}
          {!searchQuery && tabs.length > 1 && (
            <div className="flex gap-1 px-2 py-1.5 border-b border-[var(--border-color)] overflow-x-auto">
              {tabs.map(tab => (
                <button
                  key={tab.id}
                  onClick={() => setActiveTab(tab.id)}
                  className={`px-2 py-0.5 text-xs rounded-full whitespace-nowrap ${
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
          <div className="p-2 overflow-y-auto" style={{ maxHeight: '140px' }}>
            {loading ? (
              <div className="text-center py-2 text-[var(--text-tertiary)] text-xs">
                {'\u8AAD\u307F\u8FBC\u307F\u4E2D...'}
              </div>
            ) : !hasCustomEmojis ? (
              <div className="text-center py-2 text-[var(--text-tertiary)] text-xs">
                {'\u30AB\u30B9\u30BF\u30E0\u7D75\u6587\u5B57\u304C\u3042\u308A\u307E\u305B\u3093'}
              </div>
            ) : filteredEmojis.length === 0 ? (
              <div className="text-center py-2 text-[var(--text-tertiary)] text-xs">
                {'\u8A72\u5F53\u3059\u308B\u7D75\u6587\u5B57\u304C\u3042\u308A\u307E\u305B\u3093'}
              </div>
            ) : (
              <div className="grid grid-cols-8 gap-1">
                {filteredEmojis.map((emoji, i) => (
                  <button
                    key={`${emoji.shortcode}-${i}`}
                    onClick={() => handleCustomSelect(emoji)}
                    className="aspect-square p-0.5 rounded-lg hover:bg-[var(--bg-tertiary)] transition-colors"
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
    </div>
  )
}
