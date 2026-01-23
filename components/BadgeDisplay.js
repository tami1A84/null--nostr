'use client'

import { useState, useEffect } from 'react'
import { fetchEvents, getDefaultRelay } from '@/lib/nostr'

const KIND_PROFILE_BADGES = 30008
const KIND_BADGE_DEFINITION = 30009

// Memory cache for profile badges
const badgeCache = new Map()

// Cache version - incremented when cache is cleared to force re-renders
let cacheVersion = 0

// LocalStorage cache key prefix
const BADGE_CACHE_PREFIX = 'badge_cache_'
const BADGE_CACHE_TTL = 1000 * 60 * 30 // 30 minutes

// Subscribers for cache updates
const cacheSubscribers = new Set()

function notifyCacheUpdate() {
  cacheVersion++
  cacheSubscribers.forEach(callback => callback(cacheVersion))
}

// Get cached badges from localStorage
function getCachedBadges(pubkey) {
  if (typeof window === 'undefined') return null
  try {
    const cached = localStorage.getItem(BADGE_CACHE_PREFIX + pubkey)
    if (cached) {
      const { badges, timestamp } = JSON.parse(cached)
      if (Date.now() - timestamp < BADGE_CACHE_TTL) {
        return badges
      }
    }
  } catch (e) {
    console.warn('Failed to load cached badges:', e.message)
  }
  return null
}

// Save badges to localStorage
function saveCachedBadges(pubkey, badges) {
  if (typeof window === 'undefined') return
  try {
    localStorage.setItem(BADGE_CACHE_PREFIX + pubkey, JSON.stringify({
      badges,
      timestamp: Date.now()
    }))
  } catch (e) {
    // localStorage might be full, log but don't throw
    console.warn('Failed to cache badges:', e.message)
  }
}

// Badge image component with error handling
function BadgeImage({ badge }) {
  const [error, setError] = useState(false)
  const [loaded, setLoaded] = useState(false)

  if (error || !badge.image) {
    // Fallback: show first letter of badge name
    return (
      <div 
        className="w-4 h-4 rounded-sm bg-[var(--bg-tertiary)] flex items-center justify-center text-[8px] text-[var(--text-tertiary)] font-medium flex-shrink-0"
        title={badge.name}
      >
        {badge.name?.charAt(0) || '?'}
      </div>
    )
  }

  return (
    <img
      src={badge.image}
      alt={badge.name}
      title={badge.name}
      className={`w-4 h-4 rounded-sm object-contain flex-shrink-0 ${loaded ? '' : 'opacity-0'}`}
      loading="lazy"
      referrerPolicy="no-referrer"
      onError={() => setError(true)}
      onLoad={() => setLoaded(true)}
    />
  )
}

export default function BadgeDisplay({ pubkey, maxBadges = 3 }) {
  const [badges, setBadges] = useState([])
  const [loading, setLoading] = useState(true)
  const [version, setVersion] = useState(cacheVersion)

  // Subscribe to cache updates
  useEffect(() => {
    const handleCacheUpdate = (newVersion) => {
      setVersion(newVersion)
    }
    cacheSubscribers.add(handleCacheUpdate)
    return () => cacheSubscribers.delete(handleCacheUpdate)
  }, [])

  useEffect(() => {
    if (!pubkey) {
      setLoading(false)
      return
    }
    
    // Check memory cache first (but respect version changes)
    if (badgeCache.has(pubkey) && version === cacheVersion) {
      setBadges(badgeCache.get(pubkey))
      setLoading(false)
      return
    }
    
    // Check localStorage cache
    const cached = getCachedBadges(pubkey)
    if (cached && version === cacheVersion) {
      setBadges(cached)
      badgeCache.set(pubkey, cached)
      setLoading(false)
      return
    }
    
    loadBadges()
  }, [pubkey, version])

  const loadBadges = async () => {
    try {
      const currentRelay = getDefaultRelay()
      // Use multiple relays for better badge definition coverage
      const extraRelays = ['wss://yabu.me', 'wss://relay.damus.io', 'wss://nos.lol']
      const allRelays = [...new Set([currentRelay, ...extraRelays])]
      
      // Fetch profile badges (kind 30008) - try multiple relays
      let profileEvents = []
      for (const relay of [currentRelay, ...extraRelays.slice(0, 2)]) {
        try {
          const events = await fetchEvents({
            kinds: [KIND_PROFILE_BADGES],
            authors: [pubkey],
            '#d': ['profile_badges'],
            limit: 1
          }, [relay])
          
          if (events.length > 0) {
            // Use the most recent event
            if (profileEvents.length === 0 || events[0].created_at > profileEvents[0].created_at) {
              profileEvents = events
            }
          }
        } catch (e) {
          // Continue with other relays
        }
      }

      if (profileEvents.length === 0) {
        badgeCache.set(pubkey, [])
        saveCachedBadges(pubkey, [])
        setBadges([])
        setLoading(false)
        return
      }

      // Parse badge references (avoid duplicates)
      const badgeRefs = []
      const seenRefs = new Set()
      const tags = profileEvents[0].tags
      
      for (let i = 0; i < tags.length; i++) {
        if (tags[i][0] === 'a' && tags[i][1]?.startsWith('30009:')) {
          const ref = tags[i][1]
          if (!seenRefs.has(ref)) {
            seenRefs.add(ref)
            badgeRefs.push(ref)
            if (badgeRefs.length >= maxBadges) break
          }
        }
      }

      if (badgeRefs.length === 0) {
        badgeCache.set(pubkey, [])
        saveCachedBadges(pubkey, [])
        setBadges([])
        setLoading(false)
        return
      }

      // Fetch badge definitions - try multiple relays for each badge
      const badgePromises = badgeRefs.map(async (ref) => {
        const parts = ref.split(':')
        if (parts.length >= 3) {
          const [kind, creator, ...dTagParts] = parts
          const dTag = dTagParts.join(':')
          
          // Try each relay until we find the badge definition
          for (const relay of allRelays) {
            try {
              const events = await fetchEvents({
                kinds: [parseInt(kind)],
                authors: [creator],
                '#d': [dTag],
                limit: 1
              }, [relay])
              
              if (events.length > 0) {
                const event = events[0]
                const thumbTag = event.tags.find(t => t[0] === 'thumb')
                const imageTag = event.tags.find(t => t[0] === 'image')
                const nameTag = event.tags.find(t => t[0] === 'name')
                
                return {
                  ref,
                  name: nameTag?.[1] || dTag,
                  image: thumbTag?.[1] || imageTag?.[1] || ''
                }
              }
            } catch (e) {
              // Continue to next relay
            }
          }
          
          // Return badge with default values if not found on any relay
          return {
            ref,
            name: dTag,
            image: ''
          }
        }
        return null
      })

      const results = await Promise.all(badgePromises)
      const badgeList = results.filter(b => b !== null)

      badgeCache.set(pubkey, badgeList)
      saveCachedBadges(pubkey, badgeList)
      setBadges(badgeList)
    } catch (e) {
      console.error('Failed to load badges:', e)
      badgeCache.set(pubkey, [])
      saveCachedBadges(pubkey, [])
    } finally {
      setLoading(false)
    }
  }

  if (loading || badges.length === 0) return null

  return (
    <div className="flex items-center gap-0.5 flex-shrink-0">
      {badges.slice(0, maxBadges).map((badge, i) => (
        <BadgeImage key={badge.ref || i} badge={badge} />
      ))}
    </div>
  )
}

// Export function to clear badge cache (useful when badges are updated)
export function clearBadgeCache(pubkey) {
  if (pubkey) {
    badgeCache.delete(pubkey)
    if (typeof window !== 'undefined') {
      localStorage.removeItem(BADGE_CACHE_PREFIX + pubkey)
    }
  } else {
    badgeCache.clear()
  }
  // Notify all BadgeDisplay components to reload
  notifyCacheUpdate()
}
