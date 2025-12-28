/**
 * WebSocket Connection Manager for Nostr
 *
 * Provides robust connection management with:
 * - Singleton pool management
 * - Request queuing with global throttling
 * - Exponential backoff retry logic
 * - Connection health monitoring
 * - Rate limiting to prevent relay bans
 */

import { SimplePool } from 'nostr-tools/pool'

// Configuration
const CONFIG = {
  // Maximum concurrent requests across all operations
  maxConcurrentRequests: 5,

  // Maximum concurrent requests per relay
  maxRequestsPerRelay: 3,

  // Request timeout in ms
  requestTimeout: 15000,

  // EOSE (End of Stored Events) timeout
  eoseTimeout: 15000,

  // Pool idle timeout before reset (3 minutes)
  poolIdleTimeout: 180000,

  // Health check interval (60 seconds)
  healthCheckInterval: 60000,

  // Retry configuration
  retry: {
    maxAttempts: 3,
    baseDelay: 500,
    maxDelay: 10000,
    jitter: 0.3 // 30% random jitter
  },

  // Rate limiting (requests per second per relay)
  rateLimit: {
    requestsPerSecond: 10,
    burstSize: 20
  }
}

// Connection state
let pool = null
let poolLastUsed = Date.now()
let healthCheckTimer = null
let isClosing = false

// Request queue state
const requestQueue = {
  pending: 0,
  queue: [],
  relayPending: new Map(), // Track pending per relay
}

// Rate limiter state per relay
const rateLimiters = new Map()

/**
 * Token bucket rate limiter for each relay
 */
class TokenBucket {
  constructor(tokensPerSecond, bucketSize) {
    this.tokensPerSecond = tokensPerSecond
    this.bucketSize = bucketSize
    this.tokens = bucketSize
    this.lastRefill = Date.now()
  }

  refill() {
    const now = Date.now()
    const elapsed = (now - this.lastRefill) / 1000
    this.tokens = Math.min(this.bucketSize, this.tokens + elapsed * this.tokensPerSecond)
    this.lastRefill = now
  }

  tryConsume(count = 1) {
    this.refill()
    if (this.tokens >= count) {
      this.tokens -= count
      return true
    }
    return false
  }

  getWaitTime(count = 1) {
    this.refill()
    if (this.tokens >= count) return 0
    const needed = count - this.tokens
    return (needed / this.tokensPerSecond) * 1000
  }
}

/**
 * Get or create rate limiter for a relay
 */
function getRateLimiter(relay) {
  if (!rateLimiters.has(relay)) {
    rateLimiters.set(relay, new TokenBucket(
      CONFIG.rateLimit.requestsPerSecond,
      CONFIG.rateLimit.burstSize
    ))
  }
  return rateLimiters.get(relay)
}

/**
 * Wait for rate limit to allow request
 */
async function waitForRateLimit(relay) {
  const limiter = getRateLimiter(relay)
  const waitTime = limiter.getWaitTime()

  if (waitTime > 0) {
    await new Promise(resolve => setTimeout(resolve, waitTime))
  }

  limiter.tryConsume()
}

/**
 * Calculate delay with exponential backoff and jitter
 */
function calculateBackoffDelay(attempt) {
  const { baseDelay, maxDelay, jitter } = CONFIG.retry

  // Exponential backoff: baseDelay * 2^attempt
  let delay = baseDelay * Math.pow(2, attempt)

  // Cap at max delay
  delay = Math.min(delay, maxDelay)

  // Add jitter
  const jitterAmount = delay * jitter * (Math.random() * 2 - 1)
  delay += jitterAmount

  return Math.max(0, delay)
}

/**
 * Acquire slot from request queue
 */
async function acquireSlot(relay) {
  // Check global limit
  while (requestQueue.pending >= CONFIG.maxConcurrentRequests) {
    await new Promise(resolve => {
      requestQueue.queue.push(resolve)
    })
  }

  // Check per-relay limit
  const relayPending = requestQueue.relayPending.get(relay) || 0
  if (relayPending >= CONFIG.maxRequestsPerRelay) {
    // Wait a bit and retry
    await new Promise(resolve => setTimeout(resolve, 100))
    return acquireSlot(relay)
  }

  // Wait for rate limit
  await waitForRateLimit(relay)

  // Acquire slot
  requestQueue.pending++
  requestQueue.relayPending.set(relay, (requestQueue.relayPending.get(relay) || 0) + 1)
}

/**
 * Release slot back to request queue
 */
function releaseSlot(relay) {
  requestQueue.pending = Math.max(0, requestQueue.pending - 1)

  const relayPending = requestQueue.relayPending.get(relay) || 0
  requestQueue.relayPending.set(relay, Math.max(0, relayPending - 1))

  // Wake up next waiting request
  if (requestQueue.queue.length > 0) {
    const next = requestQueue.queue.shift()
    next()
  }
}

/**
 * Get or create the pool singleton
 */
export function getPool() {
  if (!pool || isClosing) {
    pool = new SimplePool({
      enablePing: true,
      enableReconnect: true,
      eoseSubTimeout: CONFIG.eoseTimeout,
      getTimeout: CONFIG.requestTimeout,
    })
    isClosing = false
    startHealthCheck()
  }
  poolLastUsed = Date.now()
  return pool
}

/**
 * Reset the connection pool
 */
export async function resetPool() {
  if (pool) {
    isClosing = true
    try {
      // Close all relay connections
      pool.close([])
    } catch (e) {
      console.warn('Pool close error (ignored):', e)
    }
    pool = null
  }

  // Clear pending requests
  requestQueue.pending = 0
  requestQueue.queue.forEach(resolve => resolve())
  requestQueue.queue = []
  requestQueue.relayPending.clear()

  // Reset rate limiters
  rateLimiters.clear()

  // Create new pool
  isClosing = false
  return getPool()
}

/**
 * Start health check timer
 */
function startHealthCheck() {
  if (healthCheckTimer) {
    clearInterval(healthCheckTimer)
  }

  if (typeof window === 'undefined') return

  healthCheckTimer = setInterval(() => {
    // Reset pool if idle for too long
    if (pool && Date.now() - poolLastUsed > CONFIG.poolIdleTimeout) {
      console.log('[ConnectionManager] Auto-resetting idle pool')
      resetPool()
    }

    // Log connection stats in development
    if (process.env.NODE_ENV === 'development') {
      console.log('[ConnectionManager] Stats:', {
        pending: requestQueue.pending,
        queueLength: requestQueue.queue.length,
        relayPending: Object.fromEntries(requestQueue.relayPending),
      })
    }
  }, CONFIG.healthCheckInterval)
}

/**
 * Execute a query with retry logic
 */
export async function executeWithRetry(queryFn, relays, options = {}) {
  const {
    maxAttempts = CONFIG.retry.maxAttempts,
    signal, // AbortSignal for cancellation
  } = options

  const primaryRelay = relays[0]
  let lastError = null
  let currentRelayIndex = 0

  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    // Check for cancellation
    if (signal?.aborted) {
      throw new DOMException('Request cancelled', 'AbortError')
    }

    const relay = relays[currentRelayIndex % relays.length]

    try {
      // Acquire slot with rate limiting
      await acquireSlot(relay)

      try {
        // Execute the query with timeout
        const result = await Promise.race([
          queryFn([relay]),
          new Promise((_, reject) => {
            const timeoutId = setTimeout(
              () => reject(new Error('Request timeout')),
              CONFIG.requestTimeout
            )

            // Clear timeout on abort
            signal?.addEventListener('abort', () => {
              clearTimeout(timeoutId)
              reject(new DOMException('Request cancelled', 'AbortError'))
            })
          })
        ])

        return result
      } finally {
        releaseSlot(relay)
      }
    } catch (e) {
      lastError = e

      // Don't retry on abort
      if (e.name === 'AbortError') {
        throw e
      }

      console.warn(
        `[ConnectionManager] Request failed on ${relay} (attempt ${attempt + 1}/${maxAttempts}):`,
        e.message
      )

      // Try next relay
      currentRelayIndex++

      // Wait before retry with exponential backoff
      if (attempt < maxAttempts - 1) {
        const delay = calculateBackoffDelay(attempt)
        console.log(`[ConnectionManager] Retrying in ${Math.round(delay)}ms on ${relays[currentRelayIndex % relays.length]}...`)
        await new Promise(resolve => setTimeout(resolve, delay))

        // Reset pool on repeated failures
        if (attempt >= 1) {
          console.log('[ConnectionManager] Resetting pool due to repeated failures')
          await resetPool()
        }
      }
    }
  }

  throw lastError || new Error('All retry attempts exhausted')
}

/**
 * Fetch events with automatic retry and throttling
 */
export async function fetchEventsManaged(filter, relays, options = {}) {
  const p = getPool()

  const result = await executeWithRetry(
    async (currentRelays) => {
      const events = await p.querySync(currentRelays, filter)
      return events.sort((a, b) => b.created_at - a.created_at)
    },
    relays,
    options
  )

  return result
}

/**
 * Subscribe to events with proper lifecycle management
 */
export function subscribeManaged(filter, relays, callbacks, options = {}) {
  const p = getPool()
  const { onEvent, onEose, onError } = callbacks

  let isActive = true
  let sub = null

  const filters = Array.isArray(filter) ? filter : [filter]

  try {
    sub = p.subscribeMany(relays, filters, {
      onevent: (event) => {
        if (isActive && onEvent) {
          onEvent(event)
        }
      },
      oneose: () => {
        if (isActive && onEose) {
          onEose()
        }
      },
      onclose: () => {
        isActive = false
      }
    })
  } catch (e) {
    if (onError) onError(e)
    throw e
  }

  // Return subscription handle with cleanup
  return {
    close: () => {
      isActive = false
      if (sub) {
        try {
          sub.close()
        } catch (e) {
          console.warn('Subscription close error:', e)
        }
        sub = null
      }
    },
    isActive: () => isActive
  }
}

/**
 * Publish event with retry
 */
export async function publishManaged(event, relays, options = {}) {
  const p = getPool()

  return executeWithRetry(
    async (currentRelays) => {
      const promises = p.publish(currentRelays, event)
      await Promise.any(promises)
      return true
    },
    relays,
    options
  )
}

/**
 * Batch fetch with controlled concurrency
 */
export async function batchFetchManaged(queries, relays, options = {}) {
  const { concurrency = 3 } = options
  const results = []
  const p = getPool()

  // Process in batches
  for (let i = 0; i < queries.length; i += concurrency) {
    const batch = queries.slice(i, i + concurrency)

    const batchResults = await Promise.allSettled(
      batch.map(query =>
        executeWithRetry(
          async (currentRelays) => p.querySync(currentRelays, query),
          relays,
          options
        )
      )
    )

    results.push(...batchResults)
  }

  return results
}

/**
 * Get connection statistics
 */
export function getConnectionStats() {
  return {
    pending: requestQueue.pending,
    queueLength: requestQueue.queue.length,
    relayPending: Object.fromEntries(requestQueue.relayPending),
    rateLimiters: rateLimiters.size,
    poolActive: pool !== null && !isClosing,
    lastUsed: poolLastUsed,
    idleTime: Date.now() - poolLastUsed,
  }
}

/**
 * Cleanup - call when app is unmounting
 */
export function cleanup() {
  if (healthCheckTimer) {
    clearInterval(healthCheckTimer)
    healthCheckTimer = null
  }

  if (pool) {
    try {
      pool.close([])
    } catch (e) {
      // Ignore
    }
    pool = null
  }

  // Clear all state
  requestQueue.pending = 0
  requestQueue.queue = []
  requestQueue.relayPending.clear()
  rateLimiters.clear()
}

// Export config for testing/debugging
export { CONFIG as CONNECTION_CONFIG }
