'use client'

import { useState, useEffect, useCallback, useRef } from 'react'
import {
  fetchProfileCached,
  fetchProfilesBatch,
  getAllCachedProfiles,
  parseProfile,
} from '../lib/nostr'
import { getCachedProfile, setCachedProfile, clearProfileCache } from '../lib/cache'

/**
 * Pending profile requests for deduplication
 */
const pendingProfileRequests = new Map()

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

    const promise = fetchProfileCached(pubkey, forceRefresh)
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
    return fetchProfileCached(pubkey, true).then(result => {
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
      const fetched = await fetchProfilesBatch(uncached, forceRefresh)
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
    return fetchProfilesBatch(pubkeys, true).then(result => {
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
    clearProfileCache(pubkey)
  }, [])

  return {
    updateProfile,
    invalidateProfile,
  }
}

export default useProfile
