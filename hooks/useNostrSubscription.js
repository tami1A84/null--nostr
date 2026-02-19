'use client'

import { useEffect, useRef, useCallback, useState } from 'react'
import { subscribeManaged, getConnectionState } from '../lib/connection-manager'
import { getDefaultRelay, FALLBACK_RELAYS } from '../lib/nostr'
import { CONNECTION_STATE } from '../lib/constants'

/**
 * Custom hook for managing Nostr subscriptions with auto-cleanup.
 *
 * Uses WebSocket via nostr-tools SimplePool (direct relay connection).
 *
 * @param {Object} filter - Nostr filter object
 * @param {Object} options - Subscription options
 * @returns {Object} Subscription state and controls
 */
export function useNostrSubscription(filter, options = {}) {
  const {
    enabled = true,
    relays = [getDefaultRelay(), ...FALLBACK_RELAYS],
    autoReconnect = true,
    onEvent,
    onEose,
    onError,
    onReconnect,
    dedupe = true,
  } = options

  const [connectionState, setConnectionState] = useState(CONNECTION_STATE.DISCONNECTED)
  const [eventCount, setEventCount] = useState(0)
  const [lastEvent, setLastEvent] = useState(null)
  const [eoseReceived, setEoseReceived] = useState(false)

  const subscriptionRef = useRef(null)
  const seenEventsRef = useRef(new Set())
  const isMountedRef = useRef(true)

  // Use refs for callbacks to avoid re-subscribing when callbacks change
  const onEventRef = useRef(onEvent)
  const onEoseRef = useRef(onEose)
  const onErrorRef = useRef(onError)
  const onReconnectRef = useRef(onReconnect)

  useEffect(() => {
    onEventRef.current = onEvent
    onEoseRef.current = onEose
    onErrorRef.current = onError
    onReconnectRef.current = onReconnect
  })

  // Cleanup function
  const cleanup = useCallback(() => {
    if (subscriptionRef.current) {
      subscriptionRef.current.close()
      subscriptionRef.current = null
    }
    seenEventsRef.current.clear()
  }, [])

  // Common event handler (deduplication + state updates)
  const handleEvent = useCallback((event) => {
    if (!isMountedRef.current) return
    if (dedupe && seenEventsRef.current.has(event.id)) return

    seenEventsRef.current.add(event.id)
    if (seenEventsRef.current.size > 1000) {
      const entries = Array.from(seenEventsRef.current)
      seenEventsRef.current = new Set(entries.slice(-500))
    }

    setEventCount((prev) => prev + 1)
    setLastEvent(event)
    setConnectionState(CONNECTION_STATE.CONNECTED)

    if (onEventRef.current) onEventRef.current(event)
  }, [dedupe])

  // WebSocket subscription
  const subscribe = useCallback(() => {
    if (!enabled || !filter) return

    cleanup()
    setConnectionState(CONNECTION_STATE.CONNECTING)
    setEoseReceived(false)

    subscriptionRef.current = subscribeManaged(
      filter,
      relays,
      {
        onEvent: handleEvent,
        onEose: () => {
          if (!isMountedRef.current) return
          setEoseReceived(true)
          if (onEoseRef.current) onEoseRef.current()
        },
        onError: (error) => {
          if (!isMountedRef.current) return
          setConnectionState(CONNECTION_STATE.ERROR)
          if (onErrorRef.current) onErrorRef.current(error)
        },
        onReconnect: (attempt) => {
          if (!isMountedRef.current) return
          setConnectionState(CONNECTION_STATE.RECONNECTING)
          if (onReconnectRef.current) onReconnectRef.current(attempt)
        },
      },
      { autoReconnect }
    )

    setConnectionState(CONNECTION_STATE.CONNECTED)
  }, [enabled, filter, relays, autoReconnect, handleEvent, cleanup])

  // Setup subscription on mount/filter change
  useEffect(() => {
    isMountedRef.current = true
    subscribe()

    return () => {
      isMountedRef.current = false
      cleanup()
    }
  }, [subscribe, cleanup])

  // Manual controls
  const pause = useCallback(() => {
    cleanup()
    setConnectionState(CONNECTION_STATE.DISCONNECTED)
  }, [cleanup])

  const resume = useCallback(() => {
    subscribe()
  }, [subscribe])

  const reset = useCallback(() => {
    setEventCount(0)
    setLastEvent(null)
    setEoseReceived(false)
    seenEventsRef.current.clear()
    subscribe()
  }, [subscribe])

  return {
    // State
    connectionState,
    isConnected: connectionState === CONNECTION_STATE.CONNECTED,
    isReconnecting: connectionState === CONNECTION_STATE.RECONNECTING,
    eventCount,
    lastEvent,
    eoseReceived,
    activeTransport: 'websocket',

    // Controls
    pause,
    resume,
    reset,
    cleanup,

    // Stats
    getStats: () => subscriptionRef.current?.getStats?.() || null,
  }
}

/**
 * Hook for subscribing to real-time timeline events
 */
export function useTimelineSubscription(options = {}) {
  const {
    since = Math.floor(Date.now() / 1000) - 300, // Last 5 minutes
    limit = 100,
    kinds = [1],
    ...restOptions
  } = options

  const filter = {
    kinds,
    since,
    limit,
  }

  return useNostrSubscription(filter, restOptions)
}

/**
 * Hook for subscribing to a user's events
 */
export function useUserEventsSubscription(pubkey, options = {}) {
  const {
    kinds = [1, 6, 7],
    since,
    limit = 50,
    ...restOptions
  } = options

  const filter = pubkey
    ? {
        kinds,
        authors: [pubkey],
        ...(since && { since }),
        limit,
      }
    : null

  return useNostrSubscription(filter, {
    enabled: !!pubkey,
    ...restOptions,
  })
}

/**
 * Hook for subscribing to notifications (mentions, reactions, reposts)
 */
export function useNotificationsSubscription(pubkey, options = {}) {
  const {
    since = Math.floor(Date.now() / 1000) - 86400, // Last 24 hours
    limit = 100,
    ...restOptions
  } = options

  const filter = pubkey
    ? {
        kinds: [1, 6, 7, 9735], // mentions, reposts, reactions, zaps
        '#p': [pubkey],
        since,
        limit,
      }
    : null

  return useNostrSubscription(filter, {
    enabled: !!pubkey,
    ...restOptions,
  })
}

export default useNostrSubscription
