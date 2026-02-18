'use client'

import { useState, useEffect, useCallback, useRef } from 'react'
import {
  fetchProfileCached,
  fetchProfilesBatch,
  getAllCachedProfiles,
  parseProfile,
} from '../lib/nostr'
import { getCachedProfile, setCachedProfile } from '../lib/cache'

/**
 * Pending profile requests for deduplication
 */
const pendingProfileRequests = new Map()

/**
 * Fetch a single profile via the Rust-backed /api/profile/[pubkey] endpoint.
 * Falls back to the existing JS fetchProfileCached if the API is unavailable.
 *
 * @param {string} pubkey - 64-char hex public key
 * @param {boolean} forceRefresh - Skip client-side localStorage cache
 * @returns {Promise<Object|null>} Profile object or null
 */
async function fetchProfileViaApi(pubkey, forceRefresh = false) {
  // Check client-side cache first (unless force refresh)
  if (!forceRefresh) {
    const cached = getCachedProfile(pubkey)
    if (cached) return cached
  }

  try {
    const res = await fetch(`/api/profile/${pubkey}`)
    if (!res.ok) throw new Error(`HTTP ${res.status}`)

    const { profile, source } = await res.json()

    if (source === 'fallback' || !profile) {
      // Rust engine unavailable — fall back to JS WebSocket fetch
      return fetchProfileCached(pubkey, forceRefresh)
    }

    // Cache the result in localStorage for future renders
    setCachedProfile(pubkey, profile)
    return profile
  } catch {
    // Network error or unexpected response — fall back to JS
    return fetchProfileCached(pubkey, forceRefresh)
  }
}

/**
 * Batch-fetch profiles via the Rust-backed /api/profile/batch endpoint.
 * Falls back to the existing JS fetchProfilesBatch if the API is unavailable.
 *
 * @param {string[]} pubkeys - Array of 64-char hex public keys
 * @param {boolean} forceRefresh - Skip client-side localStorage cache
 * @returns {Promise<Object>} Map of { [pubkey]: profile }
 */
async function fetchProfilesBatchViaApi(pubkeys, forceRefresh = false) {
  if (!pubkeys || pubkeys.length === 0) return {}

  // Separate cached vs uncached pubkeys
  const uncached = forceRefresh ? pubkeys : pubkeys.filter(pk => !getCachedProfile(pk))

  // All cached — return from localStorage
  if (uncached.length === 0) {
    const result = {}
    for (const pk of pubkeys) {
      const profile = getCachedProfile(pk)
      if (profile) result[pk] = profile
    }
    return result
  }

  try {
    const res = await fetch('/api/profile/batch', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pubkeys: uncached }),
    })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)

    const { profiles, source } = await res.json()

    if (source === 'fallback' || !profiles) {
      // Rust engine unavailable — fall back to JS WebSocket fetch
      return fetchProfilesBatch(pubkeys, forceRefresh)
    }

    // Cache newly fetched profiles
    for (const [pk, profile] of Object.entries(profiles)) {
      setCachedProfile(pk, profile)
    }

    // Merge with already-cached profiles
    const result = {}
    for (const pk of pubkeys) {
      const fromCache = getCachedProfile(pk)
      if (fromCache) result[pk] = fromCache
      else if (profiles[pk]) result[pk] = profiles[pk]
    }
    return result
  } catch {
    // Network error or unexpected response — fall back to JS
    return fetchProfilesBatch(pubkeys, forceRefresh)
  }
}

/**
 * Custom hook for fetching a single profile with caching
 *
 * @param {string} pubkey - Public key to fetch profile for
 * @param {Object} options - Options
 * @returns {Object} Profile state
 */
export function useProfile(pubkey, options = {}) {
  const { enabled = true, forceRefresh = false } = options

  const [profile, setProfile] = useState(() => {
    // Try to get from cache immediately
    if (pubkey && typeof window !== 'undefined') {
      return getCachedProfile(pubkey) || null
    }
    return null
  })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  const isMountedRef = useRef(true)

  const fetchProfile = useCallback(async () => {
    if (!enabled || !pubkey) return

    // Check cache first (unless force refresh)
    if (!forceRefresh) {
      const cached = getCachedProfile(pubkey)
      if (cached) {
        setProfile(cached)
        return
      }
    }

    // Check if there's a pending request for this pubkey
    const pending = pendingProfileRequests.get(pubkey)
    if (pending) {
      try {
        const result = await pending
        if (isMountedRef.current) {
          setProfile(result)
        }
        return
      } catch (e) {
        // Continue to fetch
      }
    }

    setLoading(true)
    setError(null)

    const promise = fetchProfileViaApi(pubkey, forceRefresh)
    pendingProfileRequests.set(pubkey, promise)

    try {
      const result = await promise
      if (isMountedRef.current) {
        setProfile(result)
        setLoading(false)
      }
    } catch (e) {
      if (isMountedRef.current) {
        setError(e.message)
        setLoading(false)
      }
    } finally {
      pendingProfileRequests.delete(pubkey)
    }
  }, [pubkey, enabled, forceRefresh])

  useEffect(() => {
    isMountedRef.current = true
    fetchProfile()

    return () => {
      isMountedRef.current = false
    }
  }, [fetchProfile])

  const refetch = useCallback(() => {
    return fetchProfileViaApi(pubkey, true).then(result => {
      if (isMountedRef.current) {
        setProfile(result)
      }
      return result
    })
  }, [pubkey])

  return {
    profile,
    loading,
    error,
    refetch,
    // Computed values
    displayName: profile?.displayName || profile?.name || '',
    picture: profile?.picture || '',
    nip05: profile?.nip05 || '',
  }
}

/**
 * Custom hook for fetching multiple profiles with batching
 *
 * @param {string[]} pubkeys - Array of public keys
 * @param {Object} options - Options
 * @returns {Object} Profiles state
 */
export function useProfiles(pubkeys, options = {}) {
  const { enabled = true, forceRefresh = false } = options

  const [profiles, setProfiles] = useState(() => {
    // Initialize from cache
    if (typeof window !== 'undefined') {
      const cached = {}
      const allCached = getAllCachedProfiles()
      for (const pubkey of pubkeys || []) {
        if (allCached[pubkey]) {
          cached[pubkey] = allCached[pubkey]
        }
      }
      return cached
    }
    return {}
  })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  const isMountedRef = useRef(true)
  const prevPubkeysRef = useRef(null)

  const fetchProfiles = useCallback(async () => {
    if (!enabled || !pubkeys || pubkeys.length === 0) return

    // Check which pubkeys we need to fetch
    const uncached = forceRefresh
      ? pubkeys
      : pubkeys.filter(pk => !getCachedProfile(pk))

    if (uncached.length === 0) {
      // All cached, just update state
      const cached = {}
      for (const pubkey of pubkeys) {
        const profile = getCachedProfile(pubkey)
        if (profile) cached[pubkey] = profile
      }
      setProfiles(cached)
      return
    }

    setLoading(true)
    setError(null)

    try {
      const fetched = await fetchProfilesBatchViaApi(uncached, forceRefresh)
      if (isMountedRef.current) {
        setProfiles(prev => ({
          ...prev,
          ...fetched,
        }))
        setLoading(false)
      }
    } catch (e) {
      if (isMountedRef.current) {
        setError(e.message)
        setLoading(false)
      }
    }
  }, [pubkeys, enabled, forceRefresh])

  useEffect(() => {
    isMountedRef.current = true

    // Only fetch if pubkeys changed
    const pubkeysKey = pubkeys?.join(',') || ''
    if (pubkeysKey !== prevPubkeysRef.current) {
      prevPubkeysRef.current = pubkeysKey
      fetchProfiles()
    }

    return () => {
      isMountedRef.current = false
    }
  }, [fetchProfiles, pubkeys])

  const refetch = useCallback(() => {
    return fetchProfilesBatchViaApi(pubkeys, true).then(result => {
      if (isMountedRef.current) {
        setProfiles(result)
      }
      return result
    })
  }, [pubkeys])

  const getProfile = useCallback((pubkey) => {
    return profiles[pubkey] || null
  }, [profiles])

  return {
    profiles,
    loading,
    error,
    refetch,
    getProfile,
    // Stats
    loadedCount: Object.keys(profiles).length,
    totalCount: pubkeys?.length || 0,
  }
}

/**
 * Hook for managing profile updates in real-time
 */
export function useProfileUpdater() {
  const updateProfile = useCallback((pubkey, profileEvent) => {
    const parsed = parseProfile(profileEvent)
    if (parsed) {
      setCachedProfile(pubkey, parsed)
    }
    return parsed
  }, [])

  const invalidateProfile = useCallback((pubkey) => {
    // Force refetch on next access by removing from cache
    if (typeof window !== 'undefined') {
      localStorage.removeItem(`nurunuru_cache_profile_${pubkey}`)
    }
  }, [])

  return {
    updateProfile,
    invalidateProfile,
  }
}

export default useProfile
