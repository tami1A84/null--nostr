/**
 * Cache Utility for PWA Optimization
 *
 * Features:
 * - localStorage with expiration
 * - In-memory LRU cache for hot data
 * - Indexed lookups for profiles
 * - Automatic cleanup of expired entries
 * - Memory-efficient storage
 */

import { CACHE_CONFIG } from './constants'

const { prefix: CACHE_PREFIX, durations: CACHE_DURATION, maxEntries } = CACHE_CONFIG

// ============================================
// In-Memory LRU Cache
// ============================================

/**
 * Simple LRU Cache implementation for hot data
 */
class LRUCache {
  constructor(maxSize = 100) {
    this.maxSize = maxSize
    this.cache = new Map()
  }

  get(key) {
    if (!this.cache.has(key)) return undefined

    // Move to end (most recently used)
    const value = this.cache.get(key)
    this.cache.delete(key)
    this.cache.set(key, value)
    return value
  }

  set(key, value) {
    // Delete if exists (to update order)
    if (this.cache.has(key)) {
      this.cache.delete(key)
    } else if (this.cache.size >= this.maxSize) {
      // Delete oldest (first item)
      const firstKey = this.cache.keys().next().value
      this.cache.delete(firstKey)
    }
    this.cache.set(key, value)
  }

  has(key) {
    return this.cache.has(key)
  }

  delete(key) {
    return this.cache.delete(key)
  }

  clear() {
    this.cache.clear()
  }

  get size() {
    return this.cache.size
  }

  keys() {
    return Array.from(this.cache.keys())
  }

  values() {
    return Array.from(this.cache.values())
  }

  entries() {
    return Array.from(this.cache.entries())
  }
}

// Hot caches (in-memory for fast access)
const profileCache = new LRUCache(maxEntries.profiles)
const timelineCache = new LRUCache(maxEntries.timeline)

// Profile index for fast lookups
let profileIndex = new Set()
let profileIndexLastUpdate = 0

// ============================================
// LocalStorage Cache Operations
// ============================================

/**
 * Get cached data with expiration check
 */
export function getCache(key) {
  if (typeof window === 'undefined') return null

  try {
    const item = localStorage.getItem(CACHE_PREFIX + key)
    if (!item) return null

    const { data, expiry } = JSON.parse(item)
    if (Date.now() > expiry) {
      localStorage.removeItem(CACHE_PREFIX + key)
      return null
    }
    return data
  } catch (e) {
    // Corrupted cache entry, remove it
    try {
      localStorage.removeItem(CACHE_PREFIX + key)
    } catch {}
    return null
  }
}

/**
 * Set cached data with expiration
 */
export function setCache(key, data, duration = CACHE_DURATION.short) {
  if (typeof window === 'undefined') return false

  try {
    const item = {
      data,
      expiry: Date.now() + duration
    }
    localStorage.setItem(CACHE_PREFIX + key, JSON.stringify(item))
    return true
  } catch (e) {
    // localStorage might be full, try to clear old caches
    console.error('Cache set error:', e)
    clearExpiredCache()
    // Try once more
    try {
      const item = {
        data,
        expiry: Date.now() + duration
      }
      localStorage.setItem(CACHE_PREFIX + key, JSON.stringify(item))
      return true
    } catch {
      return false
    }
  }
}

/**
 * Remove specific cache
 */
export function removeCache(key) {
  if (typeof window === 'undefined') return
  try {
    localStorage.removeItem(CACHE_PREFIX + key)
  } catch {}
}

/**
 * Clear all expired caches (optimized)
 */
export function clearExpiredCache() {
  if (typeof window === 'undefined') return

  const now = Date.now()
  const keysToRemove = []

  try {
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i)
      if (key?.startsWith(CACHE_PREFIX)) {
        try {
          const item = JSON.parse(localStorage.getItem(key))
          if (item.expiry && now > item.expiry) {
            keysToRemove.push(key)
          }
        } catch {
          // Corrupted entry, remove it
          keysToRemove.push(key)
        }
      }
    }

    for (const key of keysToRemove) {
      localStorage.removeItem(key)
    }
  } catch (e) {
    console.error('Cache cleanup error:', e)
  }

  return keysToRemove.length
}

/**
 * Get cache statistics
 */
export function getCacheStats() {
  if (typeof window === 'undefined') return null

  let totalSize = 0
  let entryCount = 0
  let expiredCount = 0
  const now = Date.now()

  try {
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i)
      if (key?.startsWith(CACHE_PREFIX)) {
        entryCount++
        const value = localStorage.getItem(key)
        totalSize += (key.length + value.length) * 2 // UTF-16

        try {
          const item = JSON.parse(value)
          if (item.expiry && now > item.expiry) {
            expiredCount++
          }
        } catch {}
      }
    }
  } catch {}

  return {
    entryCount,
    expiredCount,
    totalSize,
    totalSizeKB: Math.round(totalSize / 1024),
    memoryProfileCount: profileCache.size,
    memoryTimelineCount: timelineCache.size,
  }
}

// ============================================
// Profile Cache (with hot cache)
// ============================================

/**
 * Get cached profile (checks memory first, then localStorage)
 */
export function getCachedProfile(pubkey) {
  // Check memory cache first
  const memoryCached = profileCache.get(pubkey)
  if (memoryCached) return memoryCached

  // Check localStorage
  const stored = getCache(`profile_${pubkey}`)
  if (stored) {
    // Populate memory cache
    profileCache.set(pubkey, stored)
  }
  return stored
}

/**
 * Set cached profile (both memory and localStorage)
 */
export function setCachedProfile(pubkey, profile) {
  // Update memory cache
  profileCache.set(pubkey, profile)
  // Update localStorage
  setCache(`profile_${pubkey}`, profile, CACHE_DURATION.profile)
  // Update index
  profileIndex.add(pubkey)
}

/**
 * Get all cached profiles using index (optimized)
 */
export function getCachedProfiles() {
  const profiles = {}
  if (typeof window === 'undefined') return profiles

  // Refresh index if stale
  const now = Date.now()
  if (now - profileIndexLastUpdate > CACHE_CONFIG.indexRefreshInterval) {
    refreshProfileIndex()
  }

  // Use index for fast lookup
  for (const pubkey of profileIndex) {
    const profile = getCachedProfile(pubkey)
    if (profile) {
      profiles[pubkey] = profile
    }
  }

  return profiles
}

/**
 * Refresh the profile index from localStorage
 */
function refreshProfileIndex() {
  if (typeof window === 'undefined') return

  const newIndex = new Set()
  const prefix = CACHE_PREFIX + 'profile_'

  try {
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i)
      if (key?.startsWith(prefix)) {
        const pubkey = key.slice(prefix.length)
        newIndex.add(pubkey)
      }
    }
  } catch {}

  profileIndex = newIndex
  profileIndexLastUpdate = Date.now()
}

/**
 * Clear profile cache
 */
export function clearProfileCache(pubkey) {
  if (pubkey) {
    profileCache.delete(pubkey)
    removeCache(`profile_${pubkey}`)
    profileIndex.delete(pubkey)
  } else {
    // Clear all profiles
    profileCache.clear()
    profileIndex.clear()

    if (typeof window !== 'undefined') {
      const prefix = CACHE_PREFIX + 'profile_'
      const keysToRemove = []
      for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i)
        if (key?.startsWith(prefix)) {
          keysToRemove.push(key)
        }
      }
      keysToRemove.forEach(k => localStorage.removeItem(k))
    }
  }
}

// ============================================
// Mute List Cache
// ============================================

export function getCachedMuteList(pubkey) {
  return getCache(`mutelist_${pubkey}`)
}

export function setCachedMuteList(pubkey, muteList) {
  setCache(`mutelist_${pubkey}`, muteList, CACHE_DURATION.muteList)
}

// ============================================
// Follow List Cache
// ============================================

export function getCachedFollowList(pubkey) {
  return getCache(`followlist_${pubkey}`)
}

export function setCachedFollowList(pubkey, followList) {
  setCache(`followlist_${pubkey}`, followList, CACHE_DURATION.followList)
}

// ============================================
// Custom Emoji Cache
// ============================================

export function getCachedEmoji(pubkey) {
  return getCache(`emoji_${pubkey}`)
}

export function setCachedEmoji(pubkey, emojiData) {
  setCache(`emoji_${pubkey}`, emojiData, CACHE_DURATION.emoji)
}

export function clearCachedEmoji(pubkey) {
  removeCache(`emoji_${pubkey}`)
}

// ============================================
// Timeline Cache (with hot cache)
// ============================================

export function getCachedTimeline() {
  // Check memory first
  const memoryCached = timelineCache.get('events')
  if (memoryCached) return memoryCached

  return getCache('timeline_events')
}

export function setCachedTimeline(events) {
  // Only cache first 20 events for quick restore
  const toCache = events.slice(0, 20)
  timelineCache.set('events', toCache)
  setCache('timeline_events', toCache, CACHE_DURATION.timeline)
}

// ============================================
// Prefetch State Management
// ============================================

const prefetchState = {
  homeLoaded: false,
  talkLoaded: false,
  profilesLoading: false,
  muteListLoading: false,
}

export function getPrefetchState() {
  return { ...prefetchState }
}

export function setPrefetchState(key, value) {
  prefetchState[key] = value
}

// ============================================
// Initialization
// ============================================

/**
 * Initialize cache on app start
 */
export function initCache() {
  // Clear expired entries
  const cleared = clearExpiredCache()
  if (cleared > 0 && process.env.NODE_ENV === 'development') {
    console.log(`[Cache] Cleared ${cleared} expired entries`)
  }

  // Build profile index
  refreshProfileIndex()

  // Log stats in development
  if (process.env.NODE_ENV === 'development') {
    const stats = getCacheStats()
    console.log('[Cache] Initialized:', stats)
  }
}

// ============================================
// Export for backwards compatibility
// ============================================

export { CACHE_DURATION }
