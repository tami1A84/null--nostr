'use client'

import { useState, useEffect } from 'react'
import {
  fetchEvents,
  getDefaultRelay,
  createEventTemplate,
  signEventNip07,
  publishEvent
} from '@/lib/nostr'

export default function EmojiSettings({ pubkey }) {
  const [userEmojis, setUserEmojis] = useState([])
  const [userEmojiSets, setUserEmojiSets] = useState([])
  const [loadingEmojis, setLoadingEmojis] = useState(false)
  const [removingEmoji, setRemovingEmoji] = useState(null)
  const [emojiSetSearch, setEmojiSetSearch] = useState('')
  const [searchedEmojiSets, setSearchedEmojiSets] = useState([])
  const [searchingEmoji, setSearchingEmoji] = useState(false)
  const [addingEmojiSet, setAddingEmojiSet] = useState(null)

  useEffect(() => {
    if (pubkey) {
      loadUserEmojis()
    }
  }, [pubkey])

  const loadUserEmojis = async () => {
    setLoadingEmojis(true)
    try {
      const relays = [getDefaultRelay()]
      const events = await fetchEvents({ kinds: [10030], authors: [pubkey], limit: 1 }, relays)

      const individualEmojis = []
      const setPointers = []

      if (events.length > 0) {
        for (const tag of events[0].tags) {
          if (tag[0] === 'emoji' && tag[1] && tag[2]) {
            individualEmojis.push({ shortcode: tag[1], url: tag[2] })
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
          const setEvents = await fetchEvents({ kinds: [30030], authors: [author], '#d': [dTag], limit: 1 }, relays)
          if (setEvents.length > 0) {
            const setEvent = setEvents[0]
            loadedSets.push({
              pointer,
              name: setEvent.tags.find(t => t[0] === 'title')?.[1] || dTag,
              author,
              dTag,
              emojiCount: setEvent.tags.filter(t => t[0] === 'emoji').length
            })
          }
        }
      }

      setUserEmojis(individualEmojis)
      setUserEmojiSets(loadedSets)
    } catch (e) {
      console.error(e)
    } finally {
      setLoadingEmojis(false)
    }
  }

  const searchEmojiSets = async () => {
    if (!emojiSetSearch.trim()) return
    setSearchingEmoji(true)
    try {
      const relays = [getDefaultRelay()]
      const events = await fetchEvents({ kinds: [30030], limit: 20 }, relays)
      const search = emojiSetSearch.toLowerCase()
      const results = events
        .filter(e => {
          const title = e.tags.find(t => t[0] === 'title')?.[1] || ''
          const dTag = e.tags.find(t => t[0] === 'd')?.[1] || ''
          return title.toLowerCase().includes(search) || dTag.toLowerCase().includes(search)
        })
        .map(e => {
          const dTag = e.tags.find(t => t[0] === 'd')?.[1] || ''
          return {
            pointer: `30030:${e.pubkey}:${dTag}`,
            name: e.tags.find(t => t[0] === 'title')?.[1] || dTag,
            author: e.pubkey,
            dTag,
            emojiCount: e.tags.filter(t => t[0] === 'emoji').length,
            emojis: e.tags.filter(t => t[0] === 'emoji').slice(0, 5).map(t => ({ shortcode: t[1], url: t[2] }))
          }
        })
        .filter(s => s.emojiCount > 0 && !userEmojiSets.some(us => us.pointer === s.pointer))
      setSearchedEmojiSets(results)
    } catch (e) {
      console.error(e)
    } finally {
      setSearchingEmoji(false)
    }
  }

  const handleAddEmojiSet = async (set) => {
    if (!pubkey || addingEmojiSet) return
    setAddingEmojiSet(set.pointer)
    try {
      const tags = []
      for (const emoji of userEmojis) tags.push(['emoji', emoji.shortcode, emoji.url])
      for (const s of userEmojiSets) tags.push(['a', s.pointer])
      tags.push(['a', set.pointer])

      const event = createEventTemplate(10030, '')
      event.pubkey = pubkey
      event.tags = tags
      const signedEvent = await signEventNip07(event)
      await publishEvent(signedEvent)
      setUserEmojiSets(prev => [...prev, set])
      setSearchedEmojiSets(prev => prev.filter(s => s.pointer !== set.pointer))
    } catch (e) {
      console.error(e)
    } finally {
      setAddingEmojiSet(null)
    }
  }

  const handleRemoveEmojiSet = async (set) => {
    if (!pubkey || removingEmoji) return
    setRemovingEmoji(set.pointer)
    try {
      const tags = []
      for (const emoji of userEmojis) tags.push(['emoji', emoji.shortcode, emoji.url])
      for (const s of userEmojiSets) if (s.pointer !== set.pointer) tags.push(['a', s.pointer])

      const event = createEventTemplate(10030, '')
      event.pubkey = pubkey
      event.tags = tags
      const signedEvent = await signEventNip07(event)
      await publishEvent(signedEvent)
      setUserEmojiSets(prev => prev.filter(s => s.pointer !== set.pointer))
    } catch (e) {
      console.error(e)
    } finally {
      setRemovingEmoji(null)
    }
  }

  return (
    <div className="space-y-4">
      <div className="p-4 bg-[var(--bg-secondary)] rounded-2xl">
        <h3 className="text-lg font-semibold text-[var(--text-primary)] mb-4">カスタム絵文字</h3>
        {loadingEmojis ? (
          <div className="py-6 text-center text-[var(--text-tertiary)]">読み込み中...</div>
        ) : (
          <div className="space-y-6">
            {userEmojiSets.length > 0 && (
              <div>
                <h4 className="text-sm font-medium text-[var(--text-secondary)] mb-2">登録済みセット</h4>
                <div className="space-y-2">
                  {userEmojiSets.map(set => (
                    <div key={set.pointer} className="flex items-center justify-between p-2 bg-[var(--bg-tertiary)] rounded-xl">
                      <div className="min-w-0">
                        <span className="text-sm text-[var(--text-primary)] truncate block">{set.name}</span>
                        <span className="text-xs text-[var(--text-tertiary)]">{set.emojiCount}個の絵文字</span>
                      </div>
                      <button onClick={() => handleRemoveEmojiSet(set)} disabled={removingEmoji === set.pointer} className="text-xs text-red-500 px-2">
                        {removingEmoji === set.pointer ? '...' : '削除'}
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            )}

            <div>
              <h4 className="text-sm font-medium text-[var(--text-secondary)] mb-2">新しいセットを追加</h4>
              <div className="flex gap-2 mb-3">
                <input
                  type="text"
                  value={emojiSetSearch}
                  onChange={(e) => setEmojiSetSearch(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && searchEmojiSets()}
                  placeholder="検索..."
                  className="flex-1 input-line text-sm"
                />
                <button onClick={searchEmojiSets} disabled={searchingEmoji} className="px-3 py-2 bg-[var(--line-green)] text-white rounded-lg text-sm">
                  {searchingEmoji ? '...' : '検索'}
                </button>
              </div>
              <div className="space-y-2">
                {searchedEmojiSets.map(set => (
                  <div key={set.pointer} className="p-3 bg-[var(--bg-primary)] rounded-xl border border-[var(--border-color)]">
                    <div className="flex items-center justify-between mb-2">
                      <span className="text-sm font-medium text-[var(--text-primary)]">{set.name}</span>
                      <button onClick={() => handleAddEmojiSet(set)} disabled={addingEmojiSet === set.pointer} className="text-xs text-[var(--line-green)]">
                        {addingEmojiSet === set.pointer ? '追加中...' : '追加'}
                      </button>
                    </div>
                    <div className="flex gap-1">
                      {set.emojis.map((emoji, j) => (
                        <img key={j} src={emoji.url} alt="" className="w-6 h-6 object-contain" />
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
