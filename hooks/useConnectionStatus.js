'use client'

import { useState, useEffect, useCallback, useRef } from 'react'
import {
  getConnectionStats,
  getRelayHealth,
  getConnectionState,
  resetPool,
} from '../lib/connection-manager'
import { getDefaultRelay, FALLBACK_RELAYS } from '../lib/nostr'
import { CONNECTION_STATE, WS_CONFIG } from '../lib/constants'

/**
 * Custom hook for monitoring WebSocket connection status
 *
 * Features:
 * - Real-time connection state tracking
 * - Relay health monitoring
 * - Automatic refresh
 * - Manual reconnection controls
 *
 * @param {Object} options - Options
 * @returns {Object} Connection status and controls
 */
export function useConnectionStatus(options = {}) {
  const {
    refreshInterval = WS_CONFIG.healthCheckInterval,
    autoRefresh = true,
  } = options

  const [stats, setStats] = useState(() => getConnectionStats())
  const [relayHealth, setRelayHealth] = useState({})
  const [lastRefresh, setLastRefresh] = useState(Date.now())

  const intervalRef = useRef(null)
  const isMountedRef = useRef(true)

  const refresh = useCallback(() => {
    if (!isMountedRef.current) return

    const newStats = getConnectionStats()
    setStats(newStats)
    setLastRefresh(Date.now())

    // Update relay health for all configured relays
    const relays = [getDefaultRelay(), ...FALLBACK_RELAYS]
    const health = {}
    for (const relay of relays) {
      health[relay] = getRelayHealth(relay)
    }
    setRelayHealth(health)
  }, [])

  useEffect(() => {
    isMountedRef.current = true
    refresh()

    if (autoRefresh && refreshInterval > 0) {
      intervalRef.current = setInterval(refresh, refreshInterval)
    }

    return () => {
      isMountedRef.current = false
      if (intervalRef.current) {
        clearInterval(intervalRef.current)
      }
    }
  }, [refresh, autoRefresh, refreshInterval])

  const reconnect = useCallback(async () => {
    try {
      await resetPool()
      refresh()
      return true
    } catch (e) {
      console.error('Reconnection failed:', e)
      return false
    }
  }, [refresh])

  // Computed values
  const isConnected = stats.poolActive && stats.state === CONNECTION_STATE.CONNECTED
  const isReconnecting = stats.state === CONNECTION_STATE.RECONNECTING
  const hasErrors = stats.state === CONNECTION_STATE.ERROR || Object.keys(stats.failedRelays).length > 0
  const healthyRelayCount = Object.values(relayHealth).filter(h => h.status === 'healthy').length
  const totalRelayCount = Object.keys(relayHealth).length

  return {
    // Raw stats
    stats,
    relayHealth,
    lastRefresh,

    // Computed state
    isConnected,
    isReconnecting,
    hasErrors,
    connectionState: stats.state,

    // Relay info
    healthyRelayCount,
    totalRelayCount,
    healthRatio: totalRelayCount > 0 ? healthyRelayCount / totalRelayCount : 0,

    // Queue info
    pendingRequests: stats.pending,
    queuedRequests: stats.queueLength,

    // Controls
    refresh,
    reconnect,
  }
}

/**
 * Hook for monitoring a specific relay's health
 */
export function useRelayHealth(relayUrl) {
  const [health, setHealth] = useState(() => getRelayHealth(relayUrl))

  useEffect(() => {
    const interval = setInterval(() => {
      setHealth(getRelayHealth(relayUrl))
    }, 5000)

    return () => clearInterval(interval)
  }, [relayUrl])

  return {
    ...health,
    isHealthy: health.status === 'healthy',
    isInCooldown: health.status === 'cooldown',
    isDisabled: health.status === 'disabled',
  }
}

/**
 * Hook for network online/offline detection
 */
export function useNetworkStatus() {
  const [isOnline, setIsOnline] = useState(
    typeof navigator !== 'undefined' ? navigator.onLine : true
  )
  const [wasOffline, setWasOffline] = useState(false)

  useEffect(() => {
    const handleOnline = () => {
      setIsOnline(true)
      if (wasOffline) {
        // Trigger reconnection when coming back online
        resetPool()
      }
    }

    const handleOffline = () => {
      setIsOnline(false)
      setWasOffline(true)
    }

    window.addEventListener('online', handleOnline)
    window.addEventListener('offline', handleOffline)

    return () => {
      window.removeEventListener('online', handleOnline)
      window.removeEventListener('offline', handleOffline)
    }
  }, [wasOffline])

  return {
    isOnline,
    wasOffline,
  }
}

/**
 * Combined hook for full connection monitoring
 */
export function useFullConnectionStatus(options = {}) {
  const connection = useConnectionStatus(options)
  const network = useNetworkStatus()

  return {
    ...connection,
    ...network,
    // Overall status
    isFullyConnected: network.isOnline && connection.isConnected,
    statusMessage: !network.isOnline
      ? 'オフライン'
      : connection.isReconnecting
      ? '再接続中...'
      : connection.hasErrors
      ? '接続に問題があります'
      : connection.isConnected
      ? '接続中'
      : '切断',
  }
}

export default useConnectionStatus
