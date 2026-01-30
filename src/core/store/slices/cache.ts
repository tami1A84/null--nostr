/**
 * Cache Slice
 *
 * Manages profile and follow list caching with LRU-like behavior
 *
 * @module core/store/slices/cache
 */

import type { StateCreator } from 'zustand'
import type {
  Store,
  CacheState,
  CacheActions,
  Profile,
} from '../types'
import { MAX_CACHED_PROFILES, PROFILE_CACHE_TTL } from '../types'

/**
 * Initial cache state
 */
export const initialCacheState: CacheState = {
  profiles: {},
  followLists: {},
}

/**
 * Evict oldest profiles if cache exceeds limit
 */
function evictOldestProfiles(
  profiles: Record<string, Profile>,
  maxSize: number
): Record<string, Profile> {
  const entries = Object.entries(profiles)
  if (entries.length <= maxSize) {
    return profiles
  }

  // Sort by fetchedAt (oldest first)
  entries.sort((a, b) => a[1].fetchedAt - b[1].fetchedAt)

  // Keep only the newest entries
  const toKeep = entries.slice(entries.length - maxSize)
  const result: Record<string, Profile> = {}
  for (const [key, value] of toKeep) {
    result[key] = value
  }
  return result
}

/**
 * Check if a profile is stale (older than TTL)
 */
function isProfileStale(profile: Profile): boolean {
  return Date.now() - profile.fetchedAt > PROFILE_CACHE_TTL
}

/**
 * Create cache slice
 */
export const createCacheSlice: StateCreator<
  Store,
  [['zustand/immer', never]],
  [],
  CacheState & CacheActions
> = (set, get) => ({
  ...initialCacheState,

  setProfile: (profile: Profile) => {
    set((state) => {
      // Add or update profile
      state.profiles[profile.pubkey] = {
        ...profile,
        fetchedAt: profile.fetchedAt || Date.now(),
      }

      // Evict old profiles if over limit
      if (Object.keys(state.profiles).length > MAX_CACHED_PROFILES) {
        state.profiles = evictOldestProfiles(
          state.profiles,
          MAX_CACHED_PROFILES
        )
      }
    })
  },

  getProfile: (pubkey: string) => {
    const profile = get().profiles[pubkey]
    if (!profile) return undefined

    // Check if stale
    if (isProfileStale(profile)) {
      return undefined
    }

    return profile
  },

  setFollowList: (pubkey: string, follows: string[]) => {
    set((state) => {
      state.followLists[pubkey] = follows
    })
  },

  getFollowList: (pubkey: string) => {
    return get().followLists[pubkey]
  },

  clearProfiles: () => {
    set((state) => {
      state.profiles = {}
    })
  },

  clearAllCache: () => {
    set((state) => {
      state.profiles = {}
      state.followLists = {}
    })
  },
})
