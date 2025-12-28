// Cache utility for PWA optimization
// Uses localStorage with expiration

const CACHE_PREFIX = 'nurunuru_cache_'

// Cache durations (in milliseconds)
export const CACHE_DURATION = {
  PROFILE: 5 * 60 * 1000,      // 5 minutes for profiles
  MUTE_LIST: 10 * 60 * 1000,   // 10 minutes for mute list
  FOLLOW_LIST: 10 * 60 * 1000, // 10 minutes for follow list
  EMOJI: 30 * 60 * 1000,       // 30 minutes for custom emoji
  TIMELINE: 30 * 1000,         // 30 seconds for timeline (for quick restore)
  SHORT: 60 * 1000,            // 1 minute
}

// Get cached data with expiration check
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
    console.error('Cache get error:', e)
    return null
  }
}

// Set cached data with expiration
export function setCache(key, data, duration = CACHE_DURATION.SHORT) {
  if (typeof window === 'undefined') return
  
  try {
    const item = {
      data,
      expiry: Date.now() + duration
    }
    localStorage.setItem(CACHE_PREFIX + key, JSON.stringify(item))
  } catch (e) {
    // localStorage might be full, try to clear old caches
    console.error('Cache set error:', e)
    clearExpiredCache()
  }
}

// Remove specific cache
export function removeCache(key) {
  if (typeof window === 'undefined') return
  localStorage.removeItem(CACHE_PREFIX + key)
}

// Clear all expired caches
export function clearExpiredCache() {
  if (typeof window === 'undefined') return
  
  const now = Date.now()
  const keysToRemove = []
  
  for (let i = 0; i < localStorage.length; i++) {
    const key = localStorage.key(i)
    if (key?.startsWith(CACHE_PREFIX)) {
      try {
        const item = JSON.parse(localStorage.getItem(key))
        if (item.expiry && now > item.expiry) {
          keysToRemove.push(key)
        }
      } catch (e) {
        keysToRemove.push(key)
      }
    }
  }
  
  keysToRemove.forEach(key => localStorage.removeItem(key))
}

// Profile cache helpers
export function getCachedProfile(pubkey) {
  return getCache(`profile_${pubkey}`)
}

export function setCachedProfile(pubkey, profile) {
  setCache(`profile_${pubkey}`, profile, CACHE_DURATION.PROFILE)
}

export function getCachedProfiles() {
  const profiles = {}
  if (typeof window === 'undefined') return profiles
  
  for (let i = 0; i < localStorage.length; i++) {
    const key = localStorage.key(i)
    if (key?.startsWith(CACHE_PREFIX + 'profile_')) {
      const pubkey = key.replace(CACHE_PREFIX + 'profile_', '')
      const profile = getCache(`profile_${pubkey}`)
      if (profile) {
        profiles[pubkey] = profile
      }
    }
  }
  return profiles
}

// Mute list cache
export function getCachedMuteList(pubkey) {
  return getCache(`mutelist_${pubkey}`)
}

export function setCachedMuteList(pubkey, muteList) {
  setCache(`mutelist_${pubkey}`, muteList, CACHE_DURATION.MUTE_LIST)
}

// Follow list cache
export function getCachedFollowList(pubkey) {
  return getCache(`followlist_${pubkey}`)
}

export function setCachedFollowList(pubkey, followList) {
  setCache(`followlist_${pubkey}`, followList, CACHE_DURATION.FOLLOW_LIST)
}

// Custom emoji cache
export function getCachedEmoji(pubkey) {
  return getCache(`emoji_${pubkey}`)
}

export function setCachedEmoji(pubkey, emojiData) {
  setCache(`emoji_${pubkey}`, emojiData, CACHE_DURATION.EMOJI)
}

export function clearCachedEmoji(pubkey) {
  removeCache(`emoji_${pubkey}`)
}

// Timeline cache (for instant restore)
export function getCachedTimeline() {
  return getCache('timeline_events')
}

export function setCachedTimeline(events) {
  // Only cache first 20 events for quick restore
  setCache('timeline_events', events.slice(0, 20), CACHE_DURATION.TIMELINE)
}

// Prefetch state management
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

// Initialize cache on app start - clear expired entries
export function initCache() {
  clearExpiredCache()
}
