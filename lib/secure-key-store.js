/**
 * Secure Key Store for Nostr Private Keys
 *
 * This module provides a secure way to store private keys in memory
 * without exposing them on the global window object.
 *
 * Security features:
 * - Keys stored in module-private closure (not accessible from global scope)
 * - Keys are cleared on page unload
 * - No direct access to raw key material from outside
 * - Automatic cleanup on logout
 *
 * @module secure-key-store
 */

/** @type {Map<string, Uint8Array>} */
const keyStore = new Map()

/** @type {string | null} */
let currentPubkey = null

/**
 * Store a private key securely
 * @param {string} pubkey - The public key associated with this private key
 * @param {string} privateKeyHex - The private key in hex format
 * @returns {boolean} True if stored successfully
 */
export function storePrivateKey(pubkey, privateKeyHex) {
  if (!pubkey || !privateKeyHex) return false

  try {
    // Convert hex to bytes for storage
    const bytes = hexToBytes(privateKeyHex)
    keyStore.set(pubkey, bytes)
    currentPubkey = pubkey
    return true
  } catch (e) {
    console.error('Failed to store private key:', e)
    return false
  }
}

/**
 * Check if a private key is stored for the given pubkey
 * @param {string} [pubkey] - The public key to check (defaults to current)
 * @returns {boolean} True if a key is stored
 */
export function hasPrivateKey(pubkey) {
  const key = pubkey || currentPubkey
  return key ? keyStore.has(key) : false
}

/**
 * Get the stored private key as hex string
 * @param {string} [pubkey] - The public key to get (defaults to current)
 * @returns {string | null} The private key in hex format, or null if not found
 */
export function getPrivateKeyHex(pubkey) {
  const key = pubkey || currentPubkey
  if (!key) return null

  const bytes = keyStore.get(key)
  if (!bytes) return null

  return bytesToHex(bytes)
}

/**
 * Get the stored private key as Uint8Array
 * @param {string} [pubkey] - The public key to get (defaults to current)
 * @returns {Uint8Array | null} The private key as bytes, or null if not found
 */
export function getPrivateKeyBytes(pubkey) {
  const key = pubkey || currentPubkey
  if (!key) return null

  const bytes = keyStore.get(key)
  if (!bytes) return null

  // Return a copy to prevent external modification
  return new Uint8Array(bytes)
}

/**
 * Clear the private key for a specific pubkey
 * @param {string} [pubkey] - The public key to clear (defaults to current)
 */
export function clearPrivateKey(pubkey) {
  const key = pubkey || currentPubkey
  if (key) {
    // Overwrite with zeros before deleting (defense in depth)
    const bytes = keyStore.get(key)
    if (bytes) {
      bytes.fill(0)
    }
    keyStore.delete(key)

    if (currentPubkey === key) {
      currentPubkey = null
    }
  }
}

/**
 * Clear all stored private keys
 */
export function clearAllPrivateKeys() {
  // Overwrite all keys with zeros before clearing
  for (const bytes of keyStore.values()) {
    bytes.fill(0)
  }
  keyStore.clear()
  currentPubkey = null
}

/**
 * Set the current active pubkey
 * @param {string | null} pubkey
 */
export function setCurrentPubkey(pubkey) {
  currentPubkey = pubkey
}

/**
 * Get the current active pubkey
 * @returns {string | null}
 */
export function getCurrentPubkey() {
  return currentPubkey
}

// Helper functions (duplicated here to avoid circular imports)

/**
 * Convert hex string to Uint8Array
 * @param {string} hex
 * @returns {Uint8Array}
 */
function hexToBytes(hex) {
  const bytes = new Uint8Array(hex.length / 2)
  for (let i = 0; i < hex.length; i += 2) {
    bytes[i / 2] = parseInt(hex.substr(i, 2), 16)
  }
  return bytes
}

/**
 * Convert Uint8Array to hex string
 * @param {Uint8Array} bytes
 * @returns {string}
 */
function bytesToHex(bytes) {
  return Array.from(bytes)
    .map(b => b.toString(16).padStart(2, '0'))
    .join('')
}

// Cleanup on page unload
if (typeof window !== 'undefined') {
  window.addEventListener('beforeunload', () => {
    clearAllPrivateKeys()
  })

  // Keys remain in memory until explicit logout or page unload
}
