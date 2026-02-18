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

module.exports = { getOrCreateEngine, loginUser, getCurrentLoginPubkey }
