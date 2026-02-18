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

module.exports = {
  getOrCreateEngine,
  loginUser,
  getCurrentLoginPubkey,
  getRelayList,
  addRelay,
  removeRelay,
  reconnectRelays,
}
