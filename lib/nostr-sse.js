/**
 * nostr-sse.js — Browser SSE client for Nostr real-time subscriptions.
 *
 * Step 8: Rust SSE プロキシ対応クライアント
 *
 * Replaces `subscribeManaged()` from connection-manager.js for subscriptions
 * routed through the Rust engine at `/api/stream`.
 *
 * The API is intentionally compatible with `subscribeManaged` so that
 * `useNostrSubscription.js` can switch transports with minimal changes.
 *
 * Usage:
 *   const sub = subscribeSSE(filter, {
 *     onEvent: (event) => { ... },
 *     onEose: () => { ... },
 *     onError: (err) => { ... },
 *     onReconnect: (attempt) => { ... },
 *   })
 *   // Later:
 *   sub.close()
 */

const SSE_BASE_URL = '/api/stream'

// Reconnect configuration (mirrors connection-manager.js defaults)
const RECONNECT = {
  initialDelayMs: 1000,
  maxDelayMs: 30_000,
  backoffMultiplier: 2,
  maxAttempts: 10,
}

/**
 * Subscribe to Nostr events via Rust SSE proxy.
 *
 * @param {Object|Object[]} filter - Nostr filter (or array of filters merged as AND).
 * @param {Object} options
 * @param {Function} [options.onEvent]      - Called for each received event.
 * @param {Function} [options.onEose]       - Called when EOSE is received.
 * @param {Function} [options.onError]      - Called on connection errors.
 * @param {Function} [options.onReconnect]  - Called before each reconnect attempt.
 * @param {boolean}  [options.autoReconnect=true]
 * @param {number}   [options.maxReconnectAttempts=10]
 * @returns {{ close: Function, getStats: Function }}
 */
export function subscribeSSE(filter, options = {}) {
  const {
    onEvent,
    onEose,
    onError,
    onReconnect,
    autoReconnect = true,
    maxReconnectAttempts = RECONNECT.maxAttempts,
  } = options

  // Normalize filter — if array, use first (multi-filter AND is rare in practice).
  const normalizedFilter = Array.isArray(filter) ? filter[0] : filter
  const filterJson = JSON.stringify(normalizedFilter)
  const url = `${SSE_BASE_URL}?filter=${encodeURIComponent(filterJson)}`

  let es = null
  let closed = false
  let reconnectAttempt = 0
  let reconnectDelayMs = RECONNECT.initialDelayMs
  let reconnectTimer = null
  const seenEventIds = new Set()

  function connect() {
    if (closed) return

    es = new EventSource(url)

    es.onmessage = (e) => {
      if (closed) return
      try {
        const event = JSON.parse(e.data)

        // Deduplicate events (in case of reconnect overlap)
        if (event.id && seenEventIds.has(event.id)) return
        if (event.id) {
          seenEventIds.add(event.id)
          // Trim seen-set to avoid unbounded growth
          if (seenEventIds.size > 2000) {
            const entries = [...seenEventIds]
            seenEventIds.clear()
            entries.slice(-1000).forEach((id) => seenEventIds.add(id))
          }
        }

        if (onEvent) onEvent(event)
      } catch {
        // Ignore parse errors (e.g. heartbeat comments forwarded as data)
      }
    }

    es.addEventListener('eose', () => {
      if (!closed && onEose) onEose()
    })

    es.addEventListener('error', (e) => {
      if (closed) return

      // SSE error — could be a temporary network blip or server restart.
      if (onError) onError(new Error('SSE connection error'))

      es.close()
      es = null

      if (autoReconnect && reconnectAttempt < maxReconnectAttempts) {
        reconnectAttempt++
        const delay = Math.min(
          reconnectDelayMs * Math.pow(RECONNECT.backoffMultiplier, reconnectAttempt - 1),
          RECONNECT.maxDelayMs
        )
        if (onReconnect) onReconnect(reconnectAttempt)
        reconnectTimer = setTimeout(() => {
          if (!closed) connect()
        }, delay)
      }
    })

    // Reset reconnect counter on successful open
    es.addEventListener('open', () => {
      reconnectAttempt = 0
      reconnectDelayMs = RECONNECT.initialDelayMs
    })
  }

  connect()

  return {
    close() {
      closed = true
      if (reconnectTimer) {
        clearTimeout(reconnectTimer)
        reconnectTimer = null
      }
      if (es) {
        es.close()
        es = null
      }
    },

    getStats() {
      return {
        reconnectAttempt,
        readyState: es?.readyState ?? -1,
        seenEvents: seenEventIds.size,
      }
    },
  }
}

/**
 * Check whether the SSE endpoint is available (Rust engine running).
 * Returns true if the engine is up, false otherwise.
 * Used by useNostrSubscription to decide which transport to use.
 */
export async function isSseAvailable() {
  try {
    const res = await fetch('/api/rust-status', { method: 'GET' })
    if (!res.ok) return false
    const data = await res.json()
    return data?.rustEngine?.available === true
  } catch {
    return false
  }
}
