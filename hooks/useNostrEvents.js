'use client'

import { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import { fetchEvents, subscribeToEvents, getDefaultRelay, FALLBACK_RELAYS } from '../lib/nostr'

/**
 * Custom hook for fetching Nostr events with caching and pagination
 *
 * @param {Object} filter - Nostr filter
 * @param {Object} options - Options
 * @returns {Object} Events state and controls
 */
export function useNostrEvents(filter, options = {}) {
  const {
    enabled = true,
    relays = [getDefaultRelay(), ...FALLBACK_RELAYS],
    initialData = [],
    sortBy = 'created_at',
    sortOrder = 'desc',
    dedupe = true,
    limit = 50,
  } = options

  const [events, setEvents] = useState(initialData)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const [hasMore, setHasMore] = useState(true)

  const isMountedRef = useRef(true)
  const seenIdsRef = useRef(new Set(initialData.map(e => e.id)))
  const oldestEventRef = useRef(null)

  // Fetch events
  const fetchData = useCallback(async (filterOverride = null) => {
    if (!enabled || !filter) return []

    setLoading(true)
    setError(null)

    try {
      const currentFilter = filterOverride || { ...filter, limit }
      const fetched = await fetchEvents(currentFilter, relays)

      if (!isMountedRef.current) return fetched

      // Deduplicate
      let newEvents = fetched
      if (dedupe) {
        newEvents = fetched.filter(e => !seenIdsRef.current.has(e.id))
        newEvents.forEach(e => seenIdsRef.current.add(e.id))
      }

      // Sort
      newEvents.sort((a, b) => {
        const aVal = a[sortBy]
        const bVal = b[sortBy]
        return sortOrder === 'desc' ? bVal - aVal : aVal - bVal
      })

      // Track oldest event for pagination
      if (newEvents.length > 0) {
        const oldest = newEvents[newEvents.length - 1]
        if (!oldestEventRef.current || oldest.created_at < oldestEventRef.current.created_at) {
          oldestEventRef.current = oldest
        }
      }

      // Check if there might be more
      setHasMore(fetched.length >= limit)

      setEvents(prev => {
        const combined = [...prev, ...newEvents]
        // Remove duplicates and sort
        const unique = Array.from(
          new Map(combined.map(e => [e.id, e])).values()
        )
        unique.sort((a, b) => {
          const aVal = a[sortBy]
          const bVal = b[sortBy]
          return sortOrder === 'desc' ? bVal - aVal : aVal - bVal
        })
        return unique
      })

      setLoading(false)
      return newEvents
    } catch (e) {
      if (isMountedRef.current) {
        setError(e.message)
        setLoading(false)
      }
      return []
    }
  }, [enabled, filter, relays, limit, dedupe, sortBy, sortOrder])

  // Load more (pagination)
  const loadMore = useCallback(async () => {
    if (!hasMore || loading || !oldestEventRef.current) return []

    const paginatedFilter = {
      ...filter,
      until: oldestEventRef.current.created_at - 1,
      limit,
    }

    return fetchData(paginatedFilter)
  }, [filter, limit, hasMore, loading, fetchData])

  // Refresh (fetch new events)
  const refresh = useCallback(async () => {
    const newestEvent = events[0]
    if (!newestEvent) {
      return fetchData()
    }

    const refreshFilter = {
      ...filter,
      since: newestEvent.created_at,
      limit,
    }

    const newEvents = await fetchData(refreshFilter)
    return newEvents
  }, [filter, events, limit, fetchData])

  // Reset and refetch
  const reset = useCallback(() => {
    setEvents([])
    seenIdsRef.current.clear()
    oldestEventRef.current = null
    setHasMore(true)
    return fetchData()
  }, [fetchData])

  // Initial fetch
  useEffect(() => {
    isMountedRef.current = true

    if (enabled && filter) {
      fetchData()
    }

    return () => {
      isMountedRef.current = false
    }
  }, [enabled, filter, fetchData])

  // Add event manually (for real-time updates)
  const addEvent = useCallback((event) => {
    if (dedupe && seenIdsRef.current.has(event.id)) return false

    seenIdsRef.current.add(event.id)
    setEvents(prev => {
      const updated = [event, ...prev]
      updated.sort((a, b) => {
        const aVal = a[sortBy]
        const bVal = b[sortBy]
        return sortOrder === 'desc' ? bVal - aVal : aVal - bVal
      })
      return updated
    })
    return true
  }, [dedupe, sortBy, sortOrder])

  // Remove event
  const removeEvent = useCallback((eventId) => {
    seenIdsRef.current.delete(eventId)
    setEvents(prev => prev.filter(e => e.id !== eventId))
  }, [])

  return {
    events,
    loading,
    error,
    hasMore,

    // Actions
    fetchData,
    loadMore,
    refresh,
    reset,
    addEvent,
    removeEvent,

    // Stats
    count: events.length,
    isEmpty: events.length === 0 && !loading,
  }
}

/**
 * Hook for timeline with real-time updates
 */
export function useTimeline(options = {}) {
  const {
    pubkey,
    followList = [],
    mode = 'global', // 'global' | 'following' | 'user'
    since = Math.floor(Date.now() / 1000) - 3600, // Last hour
    limit = 50,
    realtime = true,
    ...restOptions
  } = options

  // Build filter based on mode
  const filter = useMemo(() => {
    const baseFilter = {
      kinds: [1],
      since,
      limit,
    }

    if (mode === 'following' && followList.length > 0) {
      return { ...baseFilter, authors: followList }
    }

    if (mode === 'user' && pubkey) {
      return { ...baseFilter, authors: [pubkey] }
    }

    return baseFilter
  }, [mode, pubkey, followList, since, limit])

  const eventsHook = useNostrEvents(filter, {
    enabled: mode !== 'following' || followList.length > 0,
    ...restOptions,
  })

  // Real-time subscription for new events
  const subscriptionRef = useRef(null)

  useEffect(() => {
    if (!realtime) return

    // Subscribe to new events
    const realtimeFilter = {
      ...filter,
      since: Math.floor(Date.now() / 1000),
      limit: undefined, // No limit for realtime
    }

    subscriptionRef.current = subscribeToEvents(
      realtimeFilter,
      restOptions.relays || [getDefaultRelay()],
      (event) => {
        eventsHook.addEvent(event)
      }
    )

    return () => {
      if (subscriptionRef.current) {
        subscriptionRef.current.close()
      }
    }
  }, [filter, realtime, restOptions.relays])

  return eventsHook
}

/**
 * Hook for fetching reactions for a set of events
 */
export function useReactions(eventIds, options = {}) {
  const { enabled = true, ...restOptions } = options

  const filter = useMemo(() => {
    if (!eventIds || eventIds.length === 0) return null
    return {
      kinds: [7], // Reactions
      '#e': eventIds,
    }
  }, [eventIds])

  const { events: reactions, ...rest } = useNostrEvents(filter, {
    enabled: enabled && !!filter,
    ...restOptions,
  })

  // Group reactions by event
  const reactionsByEvent = useMemo(() => {
    const grouped = {}
    for (const reaction of reactions) {
      const eventTag = reaction.tags.find(t => t[0] === 'e')
      if (eventTag) {
        const eventId = eventTag[1]
        if (!grouped[eventId]) grouped[eventId] = []
        grouped[eventId].push(reaction)
      }
    }
    return grouped
  }, [reactions])

  return {
    reactions,
    reactionsByEvent,
    ...rest,
  }
}

/**
 * Hook for fetching reposts for a set of events
 */
export function useReposts(eventIds, options = {}) {
  const { enabled = true, ...restOptions } = options

  const filter = useMemo(() => {
    if (!eventIds || eventIds.length === 0) return null
    return {
      kinds: [6], // Reposts
      '#e': eventIds,
    }
  }, [eventIds])

  const { events: reposts, ...rest } = useNostrEvents(filter, {
    enabled: enabled && !!filter,
    ...restOptions,
  })

  // Group reposts by event
  const repostsByEvent = useMemo(() => {
    const grouped = {}
    for (const repost of reposts) {
      const eventTag = repost.tags.find(t => t[0] === 'e')
      if (eventTag) {
        const eventId = eventTag[1]
        if (!grouped[eventId]) grouped[eventId] = []
        grouped[eventId].push(repost)
      }
    }
    return grouped
  }, [reposts])

  return {
    reposts,
    repostsByEvent,
    ...rest,
  }
}

export default useNostrEvents
