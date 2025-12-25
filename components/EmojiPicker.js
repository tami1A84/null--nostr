'use client'

import { useState, useEffect } from 'react'
import { fetchEvents, getDefaultRelay } from '@/lib/nostr'

const KIND_EMOJI_LIST = 10030
const KIND_EMOJI_SET = 30030

export default function EmojiPicker({ pubkey, onSelect, onClose }) {
  const [emojis, setEmojis] = useState([])
  const [emojiSets, setEmojiSets] = useState([])
  const [loading, setLoading] = useState(true)
  const [searchQuery, setSearchQuery] = useState('')
  const [activeTab, setActiveTab] = useState('all')

  useEffect(() => {
    if (pubkey) {
      loadEmojis()
    }
  }, [pubkey])

  const loadEmojis = async () => {
    setLoading(true)
    let retries = 2
    
    while (retries >= 0) {
      try {
        const relays = [getDefaultRelay()]
        
        // Fetch user's emoji list (kind 10030)
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
            // Individual emoji tags
            if (tag[0] === 'emoji' && tag[1] && tag[2]) {
              individualEmojis.push({ 
                shortcode: tag[1], 
                url: tag[2],
                source: 'user'
              })
            }
            // Emoji set references (a tags pointing to kind:30030)
            else if (tag[0] === 'a' && tag[1]?.startsWith('30030:')) {
              setPointers.push(tag[1])
            }
          }
        }

        // Load emoji sets
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
                
                const setEmojis = setEvent.tags
                  .filter(t => t[0] === 'emoji' && t[1] && t[2])
                  .map(t => ({
                    shortcode: t[1],
                    url: t[2],
                    source: setName
                  }))
                
                if (setEmojis.length > 0) {
                  loadedSets.push({
                    name: setName,
                    emojis: setEmojis,
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
        setLoading(false)
        return
      } catch (e) {
        console.error('Failed to load emojis:', e, 'Retries left:', retries)
        retries--
        if (retries >= 0) {
          await new Promise(r => setTimeout(r, 500))
        }
      }
    }
    setLoading(false)
  }

  // Combine all emojis
  const allEmojis = [
    ...emojis,
    ...emojiSets.flatMap(set => set.emojis)
  ]

  // Filter emojis
  const filteredEmojis = searchQuery
    ? allEmojis.filter(e => e.shortcode.toLowerCase().includes(searchQuery.toLowerCase()))
    : activeTab === 'all' 
      ? allEmojis 
      : activeTab === 'user'
        ? emojis
        : emojiSets.find(s => s.name === activeTab)?.emojis || []

  const handleSelect = (emoji) => {
    onSelect(emoji)
  }

  const tabs = [
    { id: 'all', name: 'すべて' },
    ...(emojis.length > 0 ? [{ id: 'user', name: '個別' }] : []),
    ...emojiSets.map(s => ({ id: s.name, name: s.name }))
  ]

  return (
    <div className="bg-[var(--bg-secondary)] border border-[var(--border-color)] rounded-xl max-h-64 overflow-hidden">
      {/* Search */}
      <div className="p-2 border-b border-[var(--border-color)] flex items-center gap-2">
        <input
          type="text"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          placeholder="絵文字を検索..."
          className="flex-1 px-3 py-1.5 bg-[var(--bg-primary)] rounded-lg text-sm text-[var(--text-primary)] outline-none"
        />
        <button
          onClick={onClose}
          className="p-1.5 text-[var(--text-tertiary)] hover:text-[var(--text-primary)]"
        >
          <svg className="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <line x1="18" y1="6" x2="6" y2="18"/>
            <line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>
      </div>
      
      {/* Tabs (show if multiple sources) */}
      {!searchQuery && tabs.length > 1 && (
        <div className="flex gap-1 p-2 border-b border-[var(--border-color)] overflow-x-auto">
          {tabs.map(tab => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`px-2 py-1 text-xs rounded-full whitespace-nowrap ${
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
      
      {/* Emoji Grid */}
      <div className="p-2 overflow-y-auto" style={{ maxHeight: '160px' }}>
        {loading ? (
          <div className="text-center py-3 text-[var(--text-tertiary)] text-sm">
            読み込み中...
          </div>
        ) : allEmojis.length === 0 ? (
          <div className="text-center py-3 text-[var(--text-tertiary)] text-sm">
            <p>カスタム絵文字がありません</p>
            <p className="mt-1 text-xs">ミニアプリから絵文字セットを追加できます</p>
          </div>
        ) : filteredEmojis.length === 0 ? (
          <div className="text-center py-3 text-[var(--text-tertiary)] text-sm">
            該当する絵文字がありません
          </div>
        ) : (
          <div className="grid grid-cols-8 gap-1">
            {filteredEmojis.map((emoji, i) => (
              <button
                key={`${emoji.shortcode}-${i}`}
                onClick={() => handleSelect(emoji)}
                className="aspect-square p-1 rounded-lg hover:bg-[var(--bg-tertiary)] transition-colors"
                title={`:${emoji.shortcode}: (${emoji.source})`}
              >
                <img 
                  src={emoji.url} 
                  alt={emoji.shortcode} 
                  className="w-full h-full object-contain"
                  loading="lazy"
                />
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
