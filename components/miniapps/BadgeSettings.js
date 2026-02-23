'use client'

import { useState, useEffect } from 'react'
import {
  fetchEvents,
  getDefaultRelay,
  createEventTemplate,
  signEventNip07,
  publishEvent
} from '@/lib/nostr'
import { clearBadgeCache } from '../BadgeDisplay'

export default function BadgeSettings({ pubkey }) {
  const [profileBadges, setProfileBadges] = useState([])
  const [awardedBadges, setAwardedBadges] = useState([])
  const [loadingBadges, setLoadingBadges] = useState(false)
  const [removingBadge, setRemovingBadge] = useState(null)
  const [addingBadge, setAddingBadge] = useState(null)

  useEffect(() => {
    if (pubkey) {
      loadBadges()
    }
  }, [pubkey])

  const loadBadges = async () => {
    setLoadingBadges(true)
    try {
      const relays = [getDefaultRelay()]
      const extraRelays = ['wss://yabu.me', 'wss://relay-jp.nostr.wirednet.jp', 'wss://r.kojira.io', 'wss://nos.lol']
      const allRelays = [...new Set([relays[0], ...extraRelays])]

      const profileBadgeEvents = await fetchEvents({
        kinds: [30008], authors: [pubkey], '#d': ['profile_badges'], limit: 1
      }, relays)

      const currentBadges = []
      if (profileBadgeEvents.length > 0) {
        const tags = profileBadgeEvents[0].tags
        const seenRefs = new Set()
        for (let i = 0; i < tags.length; i++) {
          if (tags[i][0] === 'a' && tags[i][1]?.startsWith('30009:')) {
            const ref = tags[i][1]
            if (!seenRefs.has(ref)) {
              seenRefs.add(ref)
              const eTag = tags[i + 1]?.[0] === 'e' ? tags[i + 1][1] : null
              currentBadges.push({ ref, awardEventId: eTag })
            }
          }
        }
      }

      for (const badge of currentBadges) {
        const parts = badge.ref.split(':')
        if (parts.length >= 3) {
          const [, creator, ...dTagParts] = parts
          const dTag = dTagParts.join(':')
          badge.name = dTag
          for (const relay of allRelays) {
            const defEvents = await fetchEvents({ kinds: [30009], authors: [creator], '#d': [dTag], limit: 1 }, [relay])
            if (defEvents.length > 0) {
              const event = defEvents[0]
              badge.name = event.tags.find(t => t[0] === 'name')?.[1] || dTag
              badge.image = event.tags.find(t => t[0] === 'thumb')?.[1] || event.tags.find(t => t[0] === 'image')?.[1] || ''
              badge.description = event.tags.find(t => t[0] === 'description')?.[1] || ''
              break
            }
          }
        }
      }
      setProfileBadges(currentBadges)

      let allAwardEvents = []
      for (const relay of allRelays.slice(0, 3)) {
        try {
          const events = await fetchEvents({ kinds: [8], '#p': [pubkey], limit: 50 }, [relay])
          allAwardEvents = [...allAwardEvents, ...events]
        } catch {}
      }
      const awardEventsMap = new Map()
      for (const event of allAwardEvents) if (!awardEventsMap.has(event.id)) awardEventsMap.set(event.id, event)

      const awarded = []
      const seenAwards = new Set()
      for (const b of currentBadges) seenAwards.add(b.ref)

      for (const event of awardEventsMap.values()) {
        const aTag = event.tags.find(t => t[0] === 'a' && t[1]?.startsWith('30009:'))
        if (aTag) {
          const ref = aTag[1]
          if (seenAwards.has(ref)) continue
          seenAwards.add(ref)
          const parts = ref.split(':')
          const dTag = parts.length >= 3 ? parts.slice(2).join(':') : 'バッジ'
          const badge = { ref, awardEventId: event.id, name: dTag, image: '', description: '' }
          if (parts.length >= 3) {
            const [, creator] = parts
            for (const relay of allRelays) {
              const defEvents = await fetchEvents({ kinds: [30009], authors: [creator], '#d': [dTag], limit: 1 }, [relay])
              if (defEvents.length > 0) {
                const defEvent = defEvents[0]
                badge.name = defEvent.tags.find(t => t[0] === 'name')?.[1] || dTag
                badge.image = defEvent.tags.find(t => t[0] === 'thumb')?.[1] || defEvent.tags.find(t => t[0] === 'image')?.[1] || ''
                badge.description = defEvent.tags.find(t => t[0] === 'description')?.[1] || ''
                break
              }
            }
          }
          awarded.push(badge)
        }
      }
      setAwardedBadges(awarded)
    } catch (e) {
      console.error(e)
    } finally {
      setLoadingBadges(false)
    }
  }

  const handleAddBadgeToProfile = async (badge) => {
    if (!pubkey || addingBadge || profileBadges.length >= 3) return
    setAddingBadge(badge.ref)
    try {
      const newBadges = [...profileBadges, badge]
      const tags = [['d', 'profile_badges']]
      for (const b of newBadges) {
        tags.push(['a', b.ref])
        if (b.awardEventId) tags.push(['e', b.awardEventId])
      }
      const event = createEventTemplate(30008, '')
      event.pubkey = pubkey
      event.tags = tags
      const signedEvent = await signEventNip07(event)
      await publishEvent(signedEvent)
      clearBadgeCache(pubkey)
      setProfileBadges(newBadges)
      setAwardedBadges(prev => prev.filter(b => b.ref !== badge.ref))
    } catch (e) {
      console.error(e)
    } finally {
      setAddingBadge(null)
    }
  }

  const handleRemoveBadgeFromProfile = async (badge) => {
    if (!pubkey || removingBadge) return
    setRemovingBadge(badge.ref)
    try {
      const newBadges = profileBadges.filter(b => b.ref !== badge.ref)
      const tags = [['d', 'profile_badges']]
      for (const b of newBadges) {
        tags.push(['a', b.ref])
        if (b.awardEventId) tags.push(['e', b.awardEventId])
      }
      const event = createEventTemplate(30008, '')
      event.pubkey = pubkey
      event.tags = tags
      const signedEvent = await signEventNip07(event)
      await publishEvent(signedEvent)
      clearBadgeCache(pubkey)
      setProfileBadges(newBadges)
      setAwardedBadges(prev => [...prev, badge])
    } catch (e) {
      console.error(e)
    } finally {
      setRemovingBadge(null)
    }
  }

  return (
    <div className="space-y-4">
      <div className="p-4 bg-[var(--bg-secondary)] rounded-2xl">
        <h3 className="text-lg font-semibold text-[var(--text-primary)] mb-4">プロフィールバッジ</h3>
        {loadingBadges ? (
          <div className="py-8 text-center text-[var(--text-tertiary)]">読み込み中...</div>
        ) : (
          <div className="space-y-6">
            <div>
              <h4 className="text-sm font-medium text-[var(--text-secondary)] mb-2">表示中のバッジ (最大3つ)</h4>
              {profileBadges.length > 0 ? (
                <div className="space-y-2">
                  {profileBadges.map((badge, i) => (
                    <div key={i} className="flex items-center justify-between p-2 bg-[var(--bg-tertiary)] rounded-xl">
                      <div className="flex items-center gap-2 min-w-0">
                        {badge.image && <img src={badge.image} alt="" className="w-8 h-8 rounded object-contain" />}
                        <div className="min-w-0">
                          <span className="text-sm text-[var(--text-primary)] truncate block">{badge.name}</span>
                        </div>
                      </div>
                      <button onClick={() => handleRemoveBadgeFromProfile(badge)} disabled={removingBadge === badge.ref} className="text-xs text-red-400 px-2">
                        {removingBadge === badge.ref ? '...' : '削除'}
                      </button>
                    </div>
                  ))}
                </div>
              ) : <p className="text-sm text-[var(--text-tertiary)]">設定されていません</p>}
            </div>

            {awardedBadges.length > 0 && (
              <div>
                <h4 className="text-sm font-medium text-[var(--text-secondary)] mb-2">獲得済みバッジ</h4>
                <div className="space-y-2">
                  {awardedBadges.map((badge, i) => (
                    <div key={i} className="flex items-center justify-between p-2 bg-[var(--bg-tertiary)] rounded-xl">
                      <div className="flex items-center gap-2 min-w-0">
                        {badge.image && <img src={badge.image} alt="" className="w-8 h-8 rounded object-contain" />}
                        <div className="min-w-0">
                          <span className="text-sm text-[var(--text-primary)] truncate block">{badge.name}</span>
                        </div>
                      </div>
                      <button onClick={() => handleAddBadgeToProfile(badge)} disabled={addingBadge === badge.ref || profileBadges.length >= 3} className="text-xs text-[var(--line-green)] px-2">
                        {addingBadge === badge.ref ? '...' : '追加'}
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
