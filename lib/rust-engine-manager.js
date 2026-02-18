/**
 * Rust engine singleton manager (server-side only).
 *
 * Manages a single NuruNuruNapi instance that is created once,
 * connected to relays, and reused across API requests.
 *
 * The engine uses a server-side key (not the user's key) for
 * relay connections. Publishing still happens from the browser.
 */

const { getEngine } = require('./rust-bridge')

let instance = null
let initPromise = null
let currentLoginPubkey = null

/**
 * Generate a random 32-byte hex string for server engine initialization.
 * This key is only used for relay connections, not for signing user events.
 */
function generateServerKey() {
  const crypto = require('node:crypto')
  return crypto.randomBytes(32).toString('hex')
}

/**
 * Get or create the singleton engine instance.
 * First call initializes the engine and connects to relays.
 * Subsequent calls return the cached instance immediately.
 *
 * @returns {Promise<object|null>} NuruNuruNapi instance, or null if unavailable
 */
async function getOrCreateEngine() {
  if (instance) return instance

  // Prevent concurrent initialization
  if (initPromise) return initPromise

  initPromise = (async () => {
    const mod = getEngine()
    if (!mod) {
      console.log('[engine-manager] Rust engine not available')
      initPromise = null
      return null
    }

    try {
      const serverKey = process.env.NURUNURU_SERVER_KEY || generateServerKey()
      const dbPath = process.env.NURUNURU_DB_PATH || './nurunuru-db'

      console.log('[engine-manager] Creating engine instance...')
      instance = await mod.NuruNuruNapi.create(serverKey, dbPath)

      console.log('[engine-manager] Connecting to relays...')
      await instance.connect()

      console.log('[engine-manager] Engine ready')
      return instance
    } catch (e) {
      console.error('[engine-manager] Failed to initialize:', e.message)
      instance = null
      initPromise = null
      return null
    }
  })()

  return initPromise
}

/**
 * Login as a user (loads their follow/mute lists for recommendations).
 * Only re-logins if the pubkey has changed since last call.
 *
 * @param {string} pubkey - User's public key hex
 * @returns {Promise<object|null>} Engine instance, or null if unavailable
 */
async function loginUser(pubkey) {
  const engine = await getOrCreateEngine()
  if (!engine) return null

  if (currentLoginPubkey !== pubkey) {
    try {
      console.log('[engine-manager] Logging in user:', pubkey.slice(0, 8) + '...')
      await engine.login(pubkey)
      currentLoginPubkey = pubkey
    } catch (e) {
      console.error('[engine-manager] Login failed:', e.message)
      // Continue without login — feed will still work, just without personalization
    }
  }

  return engine
}

/**
 * Get the current login pubkey (if any).
 */
function getCurrentLoginPubkey() {
  return currentLoginPubkey
}

// ──────────────────────────────────────────────
// Relay management helpers (Step 4: リレー接続移行)
// ──────────────────────────────────────────────

/**
 * Get the current relay list with connection status from the Rust engine.
 *
 * @returns {Promise<Array<{url: string, status: string, connected: boolean}>|null>}
 */
async function getRelayList() {
  const engine = await getOrCreateEngine()
  if (!engine) return null

  try {
    return await engine.getRelayList()
  } catch (e) {
    console.error('[engine-manager] getRelayList failed:', e.message)
    return null
  }
}

/**
 * Add a relay to the Rust engine and immediately connect.
 *
 * @param {string} url - wss:// relay URL
 * @returns {Promise<boolean>} true on success, false if engine unavailable
 */
async function addRelay(url) {
  const engine = await getOrCreateEngine()
  if (!engine) return false

  try {
    await engine.addRelay(url)
    return true
  } catch (e) {
    console.error('[engine-manager] addRelay failed:', e.message)
    return false
  }
}

/**
 * Remove a relay from the Rust engine.
 *
 * @param {string} url - wss:// relay URL
 * @returns {Promise<boolean>} true on success, false if engine unavailable
 */
async function removeRelay(url) {
  const engine = await getOrCreateEngine()
  if (!engine) return false

  try {
    await engine.removeRelay(url)
    return true
  } catch (e) {
    console.error('[engine-manager] removeRelay failed:', e.message)
    return false
  }
}

/**
 * Reconnect to all relays (disconnect then connect).
 *
 * @returns {Promise<boolean>} true on success, false if engine unavailable
 */
async function reconnectRelays() {
  const engine = await getOrCreateEngine()
  if (!engine) return false

  try {
    await engine.reconnect()
    console.log('[engine-manager] Relays reconnected')
    return true
  } catch (e) {
    console.error('[engine-manager] reconnectRelays failed:', e.message)
    return false
  }
}

// ──────────────────────────────────────────────
// Social list helpers (Step 6: フォロー/ミュートリスト管理)
// ──────────────────────────────────────────────

/**
 * Fetch follow list (kind 3, NIP-02) for a pubkey via Rust engine.
 * Returns an array of followed pubkey hex strings.
 *
 * @param {string} pubkey - User's public key hex
 * @returns {Promise<string[]|null>} Array of followed pubkeys, or null if unavailable
 */
async function getFollowList(pubkey) {
  const engine = await getOrCreateEngine()
  if (!engine) return null

  try {
    return await engine.fetchFollowList(pubkey)
  } catch (e) {
    console.error('[engine-manager] getFollowList failed:', e.message)
    return null
  }
}

/**
 * Fetch mute list (kind 10000, NIP-51) for a pubkey via Rust engine.
 * Returns an array of muted pubkey hex strings (p-tags only).
 *
 * Note: For the full mute structure including eventIds, hashtags, and words,
 * use GET /api/social/mutes which also queries nostrdb for the full event.
 *
 * @param {string} pubkey - User's public key hex
 * @returns {Promise<string[]|null>} Array of muted pubkeys, or null if unavailable
 */
async function getMuteList(pubkey) {
  const engine = await getOrCreateEngine()
  if (!engine) return null

  try {
    return await engine.fetchMuteList(pubkey)
  } catch (e) {
    console.error('[engine-manager] getMuteList failed:', e.message)
    return null
  }
}

// ──────────────────────────────────────────────
// DM helpers (Step 7: DM 取得・検索の API 化)
// ──────────────────────────────────────────────

/**
 * Fetch NIP-17 gift-wrapped DM events (kind 1059) for a pubkey.
 * Queries nostrdb first; falls back to relay fetch via Rust engine.
 *
 * Returns raw gift wrap events — decryption must happen in the browser.
 *
 * @param {string} pubkey - User's hex pubkey (recipient)
 * @param {number|null} since - Unix timestamp lower bound (optional)
 * @param {number} limit - Max events to return (default 50)
 * @returns {Promise<object[]|null>} Array of gift wrap events, or null if unavailable
 */
async function fetchDms(pubkey, since = null, limit = 50) {
  // Try nostrdb first
  const engine = await getOrCreateEngine()
  if (!engine) return null

  try {
    const localFilter = JSON.stringify({
      kinds: [1059],
      '#p': [pubkey],
      ...(since ? { since } : {}),
      limit,
    })
    const localJsons = await engine.queryLocal(localFilter)
    if (localJsons && localJsons.length > 0) {
      return localJsons.map(j => JSON.parse(j))
    }
  } catch (e) {
    console.warn('[engine-manager] fetchDms queryLocal failed:', e.message)
  }

  // Fall back to relay fetch (requires login)
  try {
    await loginUser(pubkey)
    const eventJsons = await engine.fetchDms(since ? since * 1.0 : null, limit)
    return eventJsons.map(j => JSON.parse(j))
  } catch (e) {
    console.error('[engine-manager] fetchDms relay failed:', e.message)
    return null
  }
}

/**
 * Full-text search via NIP-50, proxied through the Rust engine's relay pool.
 *
 * @param {string} query - Search query string
 * @param {number} limit - Max results (default 50)
 * @returns {Promise<object[]|null>} Array of matching events, or null if unavailable
 */
async function searchEvents(query, limit = 50) {
  const engine = await getOrCreateEngine()
  if (!engine) return null

  try {
    const eventJsons = await engine.search(query, limit)
    return eventJsons.map(j => JSON.parse(j))
  } catch (e) {
    console.error('[engine-manager] searchEvents failed:', e.message)
    return null
  }
}

module.exports = {
  getOrCreateEngine,
  loginUser,
  getCurrentLoginPubkey,
  getRelayList,
  addRelay,
  removeRelay,
  reconnectRelays,
  getFollowList,
  getMuteList,
  fetchDms,
  searchEvents,
}
