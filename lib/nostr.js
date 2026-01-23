/**
 * Nostr Protocol Library
 *
 * Core functionality for Nostr protocol operations including:
 * - Event creation, signing, and publishing
 * - Profile and follow list management
 * - Encrypted DMs (NIP-17)
 * - Mute lists (NIP-51)
 * - Lightning Zaps (NIP-57)
 *
 * @module nostr
 */

import {
  generateSecretKey,
  getPublicKey,
  finalizeEvent,
  nip19,
  nip44
} from 'nostr-tools'
import {
  getCachedProfile,
  setCachedProfile,
  getCachedProfiles,
  getCachedMuteList,
  setCachedMuteList,
  getCachedFollowList,
  setCachedFollowList
} from './cache'
import { NOSTR_KINDS, ERROR_MESSAGES, UI_CONFIG, CACHE_CONFIG } from './constants'
import {
  getPool as getManagedPool,
  resetPool as resetManagedPool,
  fetchEventsManaged,
  subscribeManaged,
  publishManaged,
  batchFetchManaged,
  getConnectionStats,
  cleanup as cleanupConnectionManager
} from './connection-manager'
import {
  storePrivateKey,
  hasPrivateKey,
  getPrivateKeyHex,
  getPrivateKeyBytes,
  clearPrivateKey,
  clearAllPrivateKeys,
  setCurrentPubkey,
  getCurrentPubkey
} from './secure-key-store'

// Environment-based configuration with fallbacks
const ENV_DEFAULT_RELAY = process.env.NEXT_PUBLIC_DEFAULT_RELAY
const ENV_SEARCH_RELAY = process.env.NEXT_PUBLIC_SEARCH_RELAY
const ENV_FALLBACK_RELAYS = process.env.NEXT_PUBLIC_FALLBACK_RELAYS

/**
 * Default relay for all operations
 * @type {string}
 */
export const DEFAULT_RELAY = ENV_DEFAULT_RELAY || 'wss://yabu.me'

/**
 * Fallback relays when primary fails (Japanese relays + relay.damus.io only)
 * @type {string[]}
 */
export const FALLBACK_RELAYS = ENV_FALLBACK_RELAYS
  ? ENV_FALLBACK_RELAYS.split(',').map(r => r.trim())
  : [
      'wss://relay-jp.nostr.wirednet.jp',
      'wss://r.kojira.io',
      'wss://relay.damus.io'
    ]

/**
 * Search relay (NIP-50)
 * @type {string}
 */
export const SEARCH_RELAY = ENV_SEARCH_RELAY || 'wss://search.nos.today'

// Validate relay URL - filter out invalid/inaccessible relays
export function isValidRelayUrl(url) {
  if (!url || typeof url !== 'string') return false
  
  try {
    const parsed = new URL(url)
    
    // Must be wss:// (secure WebSocket) - ws:// is blocked by most browsers on HTTPS pages
    if (parsed.protocol !== 'wss:') return false
    
    // Exclude Tor onion addresses (not accessible without Tor browser)
    if (parsed.hostname.endsWith('.onion')) return false
    
    // Exclude localhost/local addresses (unless in development)
    if (parsed.hostname === 'localhost' || parsed.hostname === '127.0.0.1') {
      return typeof window !== 'undefined' && window.location.hostname === 'localhost'
    }
    
    return true
  } catch {
    return false
  }
}

// Filter relay list to only valid URLs
export function filterValidRelays(relays) {
  if (!Array.isArray(relays)) return [getDefaultRelay()]
  const valid = relays.filter(isValidRelayUrl)
  return valid.length > 0 ? valid : [getDefaultRelay()]
}

/**
 * Get single default relay from localStorage
 * @returns {string} The default relay URL
 */
export function getDefaultRelay() {
  if (typeof window !== 'undefined') {
    const saved = localStorage.getItem('defaultRelay')
    if (saved) return saved
  }
  return DEFAULT_RELAY
}

/**
 * Set the default relay
 * @param {string} relay - The relay URL to set as default
 */
export function setDefaultRelay(relay) {
  if (typeof window !== 'undefined') {
    localStorage.setItem('defaultRelay', relay)
  }
}

/**
 * Get read relays array
 * @returns {string[]} Array of relay URLs for reading
 */
export function getReadRelays() {
  return [getDefaultRelay()]
}

/**
 * Get write relays array
 * @returns {string[]} Array of relay URLs for writing
 */
export function getWriteRelays() {
  return [getDefaultRelay()]
}

// Export for backwards compatibility
export const RELAYS = [DEFAULT_RELAY]

/**
 * Get the connection pool singleton
 * @returns {SimplePool} The managed connection pool
 */
export function getPool() {
  return getManagedPool()
}

/**
 * Reset the connection pool (useful for reconnection)
 * @returns {SimplePool} A new connection pool
 */
export function resetPool() {
  return resetManagedPool()
}

// Export connection stats for debugging
export { getConnectionStats }

/**
 * Cleanup function for app unmount
 * Closes all connections and clears private keys
 */
export function cleanupNostr() {
  cleanupConnectionManager()
  clearAllPrivateKeys()
}

/**
 * Check if NIP-07 extension is available
 * @returns {boolean} True if NIP-07 extension is present
 */
export function hasNip07() {
  return typeof window !== 'undefined' && window.nostr !== undefined
}

/**
 * Check if any signing method is available
 * @returns {boolean} True if signing is possible
 */
export function canSign() {
  // Private key stored securely
  if (hasStoredPrivateKey()) return true
  // Nosskey manager available
  if (hasNosskey()) return true
  // NIP-07 extension
  if (hasNip07()) return true
  return false
}

/**
 * Get public key via NIP-07
 * @returns {Promise<string>} The public key in hex format
 * @throws {Error} If NIP-07 extension is not available
 */
export async function getNip07PublicKey() {
  if (!hasNip07()) {
    throw new Error(ERROR_MESSAGES.nip07NotFound)
  }
  try {
    const pubkey = await window.nostr.getPublicKey()
    return pubkey
  } catch (e) {
    throw new Error(ERROR_MESSAGES.publicKeyFailed)
  }
}

/**
 * Get public key (works with any login method)
 * @returns {Promise<string>} The public key in hex format
 * @throws {Error} If no public key can be obtained
 */
export async function getPublicKeyAny() {
  // First check stored pubkey
  const storedPubkey = loadPubkey()
  if (storedPubkey) {
    return storedPubkey
  }

  // Try Nosskey
  if (hasNosskey()) {
    try {
      return await window.nosskeyManager.getPublicKey()
    } catch (e) {
      console.error('Nosskey getPublicKey failed:', e)
    }
  }

  // Try NIP-07
  if (hasNip07()) {
    try {
      return await window.nostr.getPublicKey()
    } catch (e) {
      console.error('NIP-07 getPublicKey failed:', e)
    }
  }

  throw new Error('公開鍵を取得できません')
}

/**
 * Get the current login method
 * @returns {string} The login method ('nip07', 'nosskey', 'bunker', 'amber', etc.)
 */
export function getLoginMethod() {
  if (typeof window !== 'undefined') {
    return localStorage.getItem('nurunuru_login_method') || 'nip07'
  }
  return 'nip07'
}

/**
 * Check if Nosskey is available
 * @returns {boolean} True if Nosskey manager is present
 */
export function hasNosskey() {
  return typeof window !== 'undefined' && window.nosskeyManager !== undefined
}

/**
 * Check if we have private key stored securely
 * @returns {boolean} True if a private key is stored
 */
export function hasStoredPrivateKey() {
  return hasPrivateKey()
}

/**
 * Store a private key securely
 * @param {string} pubkey - The public key associated with the private key
 * @param {string} privateKeyHex - The private key in hex format
 * @returns {boolean} True if stored successfully
 */
export function setStoredPrivateKey(pubkey, privateKeyHex) {
  return storePrivateKey(pubkey, privateKeyHex)
}

/**
 * Clear the stored private key
 * @param {string} [pubkey] - The public key to clear (defaults to current)
 */
export function clearStoredPrivateKey(pubkey) {
  clearPrivateKey(pubkey)
}

// Get auto sign setting
export function getAutoSignEnabled() {
  if (typeof window !== 'undefined') {
    return localStorage.getItem('nurunuru_auto_sign') !== 'false'
  }
  return true
}

export function setAutoSignEnabled(enabled) {
  if (typeof window !== 'undefined') {
    localStorage.setItem('nurunuru_auto_sign', enabled ? 'true' : 'false')
  }
}

/**
 * Sign an event using the best available method
 * Priority: Stored private key > Nosskey > Amber > Bunker > NIP-07
 *
 * @param {Object} event - The unsigned Nostr event
 * @returns {Promise<Object>} The signed event
 * @throws {Error} If signing fails
 */
export async function signEventNip07(event) {
  const loginMethod = getLoginMethod()

  // If we have a private key stored securely, use it (fast, no re-auth)
  if (hasStoredPrivateKey() && getAutoSignEnabled()) {
    try {
      const secretKey = getPrivateKeyBytes()
      if (secretKey) {
        const signedEvent = finalizeEvent(event, secretKey)
        return signedEvent
      }
    } catch (e) {
      console.error('Private key signing failed:', e)
      // Fall through to try other methods
    }
  }
  
  // Try Nosskey if that's the login method (requires passkey auth each time)
  if (loginMethod === 'nosskey' && hasNosskey()) {
    try {
      const signedEvent = await window.nosskeyManager.signEvent(event)
      return signedEvent
    } catch (e) {
      console.error('Nosskey signing failed:', e)
      throw new Error(ERROR_MESSAGES.passkeySigningFailed)
    }
  }

  // Try Amber (NIP-55) signing for Android
  if (loginMethod === 'amber') {
    try {
      const signedEvent = await signEventAmber(event)
      return signedEvent
    } catch (e) {
      console.error('Amber signing failed:', e)
      throw new Error(ERROR_MESSAGES.amberSigningFailed)
    }
  }

  // Try bunker signer if available
  if (loginMethod === 'bunker' && window.bunkerSigner) {
    try {
      const signedEvent = await window.bunkerSigner.signEvent(event)
      return signedEvent
    } catch (e) {
      console.error('Bunker signing failed:', e)
      throw new Error(ERROR_MESSAGES.bunkerSigningFailed)
    }
  }

  // Fallback to NIP-07 (this also works for bunker via window.nostr)
  if (!hasNip07()) {
    throw new Error(ERROR_MESSAGES.noSigningMethod)
  }
  try {
    const signedEvent = await window.nostr.signEvent(event)
    return signedEvent
  } catch (e) {
    throw new Error(ERROR_MESSAGES.signingFailed)
  }
}

// Sign event via Amber (NIP-55) for Android
export async function signEventAmber(event) {
  // First try to use window.nostr directly (Amber injects this in WebView)
  if (window.nostr && typeof window.nostr.signEvent === 'function') {
    try {
      const signedEvent = await window.nostr.signEvent(event)
      return signedEvent
    } catch (e) {
      console.log('window.nostr.signEvent failed, trying intent:', e)
    }
  }
  
  // Fallback to intent-based signing
  return new Promise((resolve, reject) => {
    // Create unique ID for this signing request
    const requestId = Date.now().toString()
    
    // Store the event and callback info
    sessionStorage.setItem(`amber_sign_${requestId}`, JSON.stringify(event))
    sessionStorage.setItem('amber_sign_pending', requestId)
    
    // Create the intent URL
    const eventJson = encodeURIComponent(JSON.stringify(event))
    const callbackUrl = encodeURIComponent(window.location.origin + '/?amber_sign_callback=' + requestId)
    
    // Build intent URL for Amber
    const intentUrl = `intent:#Intent;` +
      `scheme=nostrsigner;` +
      `S.type=sign_event;` +
      `S.event=${eventJson};` +
      `S.callbackUrl=${callbackUrl};` +
      `S.compressionType=none;` +
      `S.returnType=event;` +
      `package=com.greenart7c3.nostrsigner;end`
    
    // Set up timeout
    const timeout = setTimeout(() => {
      sessionStorage.removeItem(`amber_sign_${requestId}`)
      sessionStorage.removeItem('amber_sign_pending')
      reject(new Error('Amberからの応答がタイムアウトしました'))
    }, 60000) // 60 second timeout
    
    // Store timeout ID so it can be cleared on callback
    window.amberSignTimeout = timeout
    window.amberSignResolve = resolve
    window.amberSignReject = reject
    
    // Open Amber
    window.location.href = intentUrl
  })
}

/**
 * Handle Amber sign callback (call this from page component on mount)
 *
 * Processes the callback URL parameters from Amber signing
 * and resolves/rejects any pending signing promise.
 *
 * @returns {Object|null} The signed event, or null if no callback
 */
export function handleAmberSignCallback() {
  if (typeof window === 'undefined') return null

  const urlParams = new URLSearchParams(window.location.search)
  const callbackParam = urlParams.get('amber_sign_callback')
  const signature = urlParams.get('signature')
  const eventParam = urlParams.get('event')

  if (!callbackParam || (!signature && !eventParam)) {
    return null
  }

  // Clear timeout first
  if (window.amberSignTimeout) {
    clearTimeout(window.amberSignTimeout)
    window.amberSignTimeout = null
  }

  // Helper to cleanup state
  const cleanup = (requestId) => {
    if (requestId) {
      sessionStorage.removeItem(`amber_sign_${requestId}`)
    }
    sessionStorage.removeItem('amber_sign_pending')
    window.history.replaceState({}, '', window.location.pathname)
    window.amberSignResolve = null
    window.amberSignReject = null
  }

  // Helper to reject with error
  const rejectWithError = (message) => {
    console.error('Amber callback error:', message)
    if (window.amberSignReject) {
      window.amberSignReject(new Error(message))
    }
    cleanup(callbackParam)
    return null
  }

  try {
    let signedEvent = null

    if (eventParam) {
      // Full signed event returned
      try {
        signedEvent = JSON.parse(decodeURIComponent(eventParam))
      } catch (parseError) {
        return rejectWithError(`イベントのパースに失敗しました: ${parseError.message}`)
      }

      // Validate the event structure
      if (!signedEvent || !signedEvent.sig || !signedEvent.id) {
        return rejectWithError('Amberから無効なイベントが返されました')
      }
    } else if (signature) {
      // Just signature returned - need to reconstruct event
      const pendingRequestId = sessionStorage.getItem('amber_sign_pending')

      if (!pendingRequestId) {
        return rejectWithError('保留中の署名リクエストが見つかりません')
      }

      const storedEventJson = sessionStorage.getItem(`amber_sign_${pendingRequestId}`)
      if (!storedEventJson) {
        return rejectWithError('元のイベントデータが見つかりません')
      }

      let originalEvent
      try {
        originalEvent = JSON.parse(storedEventJson)
      } catch (parseError) {
        return rejectWithError(`保存されたイベントのパースに失敗しました: ${parseError.message}`)
      }

      // Validate original event has required fields
      if (!originalEvent || !originalEvent.kind === undefined) {
        return rejectWithError('保存されたイベントが無効です')
      }

      signedEvent = {
        ...originalEvent,
        sig: signature
      }
    }

    // Clean up session storage and URL
    cleanup(callbackParam)

    // Resolve the promise if it exists
    if (window.amberSignResolve && signedEvent) {
      window.amberSignResolve(signedEvent)
    }

    return signedEvent
  } catch (e) {
    return rejectWithError(`予期しないエラー: ${e.message}`)
  }
}

/**
 * Encrypt message using NIP-44 or NIP-07 extension
 * @param {string} pubkey - Recipient's public key
 * @param {string} plaintext - Message to encrypt
 * @returns {Promise<string>} Encrypted ciphertext
 * @throws {Error} If encryption fails
 */
export async function encryptNip44(pubkey, plaintext) {
  // If we have a private key stored securely, use it directly
  if (hasStoredPrivateKey()) {
    try {
      const secretKey = getPrivateKeyBytes()
      if (secretKey) {
        const conversationKey = nip44.v2.utils.getConversationKey(secretKey, pubkey)
        return nip44.v2.encrypt(plaintext, conversationKey)
      }
    } catch (e) {
      console.error('Private key encryption failed:', e)
      throw new Error('暗号化に失敗しました: ' + e.message)
    }
  }

  // Fall back to NIP-07
  if (!hasNip07()) {
    throw new Error('暗号化機能が利用できません。ミニアプリ画面で秘密鍵をエクスポートしてください。')
  }
  try {
    if (window.nostr.nip44?.encrypt) {
      return await window.nostr.nip44.encrypt(pubkey, plaintext)
    }
    // Fallback to NIP-04 if NIP-44 not supported
    if (window.nostr.nip04?.encrypt) {
      return await window.nostr.nip04.encrypt(pubkey, plaintext)
    }
    throw new Error('暗号化がサポートされていません')
  } catch (e) {
    throw new Error('暗号化に失敗しました: ' + e.message)
  }
}

/**
 * Decrypt message using NIP-44 or NIP-07 extension
 * @param {string} pubkey - Sender's public key
 * @param {string} ciphertext - Encrypted message
 * @returns {Promise<string>} Decrypted plaintext
 * @throws {Error} If decryption fails
 */
export async function decryptNip44(pubkey, ciphertext) {
  const storedPubkey = loadPubkey()

  // If using Nosskey and no private key stored, try to export it first
  if (getLoginMethod() === 'nosskey' && !hasStoredPrivateKey() && hasNosskey()) {
    try {
      const manager = window.nosskeyManager
      const keyInfo = manager.getCurrentKeyInfo()
      if (keyInfo) {
        const privateKeyHex = await manager.exportNostrKey(keyInfo)
        if (privateKeyHex && storedPubkey) {
          storePrivateKey(storedPubkey, privateKeyHex)
        }
      }
    } catch (e) {
      console.error('Failed to export key for decryption:', e)
      throw new Error('復号するにはパスキー認証が必要です')
    }
  }

  // If we have a private key stored securely, use it directly
  if (hasStoredPrivateKey()) {
    try {
      const secretKey = getPrivateKeyBytes()
      if (secretKey) {
        const conversationKey = nip44.v2.utils.getConversationKey(secretKey, pubkey)
        return nip44.v2.decrypt(ciphertext, conversationKey)
      }
    } catch (e) {
      console.error('Private key decryption failed:', e)
      throw new Error('復号に失敗しました: ' + e.message)
    }
  }

  // Fall back to NIP-07
  if (!hasNip07()) {
    throw new Error('復号機能が利用できません')
  }
  try {
    if (window.nostr.nip44?.decrypt) {
      return await window.nostr.nip44.decrypt(pubkey, ciphertext)
    }
    // Fallback to NIP-04
    if (window.nostr.nip04?.decrypt) {
      return await window.nostr.nip04.decrypt(pubkey, ciphertext)
    }
    throw new Error('復号がサポートされていません')
  } catch (e) {
    throw new Error('復号に失敗しました: ' + e.message)
  }
}

// Convert bytes to hex
export function bytesToHex(bytes) {
  return Array.from(bytes)
    .map(b => b.toString(16).padStart(2, '0'))
    .join('')
}

// Convert hex to bytes
export function hexToBytes(hex) {
  const bytes = new Uint8Array(hex.length / 2)
  for (let i = 0; i < hex.length; i += 2) {
    bytes[i / 2] = parseInt(hex.substr(i, 2), 16)
  }
  return bytes
}

// Format timestamp
export function formatTimestamp(timestamp) {
  const date = new Date(timestamp * 1000)
  const now = new Date()
  const diff = now - date
  
  if (diff < 60000) return 'たった今'
  if (diff < 3600000) return `${Math.floor(diff / 60000)}分`
  if (diff < 86400000) return `${Math.floor(diff / 3600000)}時間`
  if (diff < 604800000) return `${Math.floor(diff / 86400000)}日`
  
  return date.toLocaleDateString('ja-JP', {
    month: 'numeric',
    day: 'numeric'
  })
}

// Shorten pubkey for display
export function shortenPubkey(pubkey, length = 8) {
  if (!pubkey) return ''
  try {
    const npub = nip19.npubEncode(pubkey)
    return `${npub.slice(0, length)}...${npub.slice(-4)}`
  } catch (e) {
    return `${pubkey.slice(0, 6)}...${pubkey.slice(-4)}`
  }
}

// Parse profile from event
export function parseProfile(event) {
  if (!event || event.kind !== 0) return null
  try {
    const profile = JSON.parse(event.content)
    return {
      name: profile.name || profile.display_name || '',
      displayName: profile.display_name || profile.name || '',
      about: profile.about || '',
      picture: profile.picture || '',
      banner: profile.banner || '',
      nip05: profile.nip05 || '',
      lud16: profile.lud16 || '',
      website: profile.website || '',
      birthday: profile.birthday || '',
      pubkey: event.pubkey
    }
  } catch (e) {
    return null
  }
}

// Fetch follow list (NIP-02 kind 3)
export async function fetchFollowList(pubkey, relays = [getDefaultRelay()]) {
  const p = getPool()
  try {
    const events = await p.querySync(relays, { kinds: [3], authors: [pubkey], limit: 1 })
    if (events.length === 0) return []
    
    // Get the most recent follow list
    const followEvent = events.sort((a, b) => b.created_at - a.created_at)[0]
    
    // Extract followed pubkeys from p tags
    const follows = followEvent.tags
      .filter(tag => tag[0] === 'p' && tag[1])
      .map(tag => tag[1])
    
    return follows
  } catch (e) {
    console.error('Failed to fetch follow list:', e)
    return []
  }
}

// Follow a user (NIP-02)
export async function followUser(targetPubkey, myPubkey, relays = [getDefaultRelay()]) {
  const p = getPool()
  
  // Fetch current follow list
  const events = await p.querySync(relays, { kinds: [3], authors: [myPubkey], limit: 1 })
  
  let existingTags = []
  let content = ''
  
  if (events.length > 0) {
    const latestEvent = events.sort((a, b) => b.created_at - a.created_at)[0]
    existingTags = latestEvent.tags.filter(tag => tag[0] === 'p')
    content = latestEvent.content || ''
  }
  
  // Check if already following
  if (existingTags.some(tag => tag[1] === targetPubkey)) {
    return { success: false, message: '既にフォローしています' }
  }
  
  // Add new follow
  const newTags = [...existingTags, ['p', targetPubkey]]
  
  const event = {
    kind: 3,
    created_at: Math.floor(Date.now() / 1000),
    tags: newTags,
    content: content,
    pubkey: myPubkey
  }
  
  const signedEvent = await signEventNip07(event)
  await publishEvent(signedEvent, relays)
  
  return { success: true }
}

// Unfollow a user (NIP-02)
export async function unfollowUser(targetPubkey, myPubkey, relays = [getDefaultRelay()]) {
  const p = getPool()
  
  // Fetch current follow list
  const events = await p.querySync(relays, { kinds: [3], authors: [myPubkey], limit: 1 })
  
  if (events.length === 0) {
    return { success: false, message: 'フォローリストがありません' }
  }
  
  const latestEvent = events.sort((a, b) => b.created_at - a.created_at)[0]
  
  // Remove the target from tags
  const newTags = latestEvent.tags.filter(tag => !(tag[0] === 'p' && tag[1] === targetPubkey))
  
  const event = {
    kind: 3,
    created_at: Math.floor(Date.now() / 1000),
    tags: newTags,
    content: latestEvent.content || '',
    pubkey: myPubkey
  }
  
  const signedEvent = await signEventNip07(event)
  await publishEvent(signedEvent, relays)
  
  return { success: true }
}

// Check if following a user
export async function isFollowing(targetPubkey, myPubkey, relays = [getDefaultRelay()]) {
  const follows = await fetchFollowList(myPubkey, relays)
  return follows.includes(targetPubkey)
}

/**
 * Fetch events from relay with throttling and retry via connection manager
 *
 * @param {Object} filter - Nostr filter object
 * @param {string[]} [relays] - Array of relay URLs
 * @param {Object} [options] - Additional options
 * @returns {Promise<Object[]>} Array of events
 */
export async function fetchEvents(filter, relays = [DEFAULT_RELAY], options = {}) {
  // Filter out invalid relay URLs (onion, ws://, etc)
  const validRelays = filterValidRelays(relays)

  // Use actual relay setting
  const primaryRelay = validRelays[0] === DEFAULT_RELAY ? getDefaultRelay() : validRelays[0]
  const allRelays = [primaryRelay, ...FALLBACK_RELAYS.filter(r => r !== primaryRelay)]

  try {
    // Use connection manager for fetching with automatic retry and throttling
    const events = await fetchEventsManaged(filter, allRelays, options)
    return events
  } catch (e) {
    console.error('[nostr] Fetch events failed after all retries:', e.message)
    return []
  }
}

/**
 * Subscribe to events with proper lifecycle management
 *
 * @param {Object|Object[]} filter - Nostr filter object(s)
 * @param {string[]} relays - Array of relay URLs
 * @param {Function} onEvent - Callback for each event
 * @param {Function} [onEose] - Callback when EOSE is received
 * @param {Function} [onError] - Callback for errors
 * @returns {{close: Function}} Subscription handle
 */
export function subscribeToEvents(filter, relays, onEvent, onEose, onError) {
  // Filter out invalid relay URLs
  const validRelays = filterValidRelays(relays)

  // Use connection manager for subscription management
  return subscribeManaged(filter, validRelays, {
    onEvent,
    onEose: onEose || (() => {}),
    onError: onError || ((e) => console.error('[nostr] Subscription error:', e))
  })
}

/**
 * Publish event to relays with retry
 *
 * @param {Object} event - The signed Nostr event to publish
 * @param {string[]} [relays] - Array of relay URLs
 * @returns {Promise<boolean>} True if published successfully
 */
export async function publishEvent(event, relays = [getDefaultRelay()]) {
  // Filter out invalid relay URLs
  const validRelays = filterValidRelays(relays)

  // Use connection manager for publishing with automatic retry
  try {
    await publishManaged(event, validRelays)
    return true
  } catch (e) {
    console.error('[nostr] Failed to publish event:', e)
    return false
  }
}

// Delete event (NIP-09)
export async function deleteEvent(eventId, reason = '') {
  if (!canSign()) throw new Error('署名機能が必要です')
  
  try {
    const event = createEventTemplate(5, reason, [['e', eventId]])
    const signedEvent = await signEventNip07(event)
    const success = await publishEvent(signedEvent)
    return { success, event: signedEvent }
  } catch (e) {
    console.error('Failed to delete event:', e)
    throw e
  }
}

// Unlike (delete reaction event)
export async function unlikeEvent(reactionEventId) {
  if (!canSign()) throw new Error('署名機能が必要です')
  
  try {
    const event = createEventTemplate(5, '', [['e', reactionEventId]])
    const signedEvent = await signEventNip07(event)
    const success = await publishEvent(signedEvent)
    return { success, event: signedEvent }
  } catch (e) {
    console.error('Failed to unlike:', e)
    throw e
  }
}

// Unrepost (delete repost event)
export async function unrepostEvent(repostEventId) {
  if (!canSign()) throw new Error('署名機能が必要です')
  
  try {
    const event = createEventTemplate(5, '', [['e', repostEventId]])
    const signedEvent = await signEventNip07(event)
    const success = await publishEvent(signedEvent)
    return { success, event: signedEvent }
  } catch (e) {
    console.error('Failed to unrepost:', e)
    throw e
  }
}

// Create unsigned event template
export function createEventTemplate(kind, content, tags = []) {
  return {
    kind,
    created_at: Math.floor(Date.now() / 1000),
    tags,
    content
  }
}

// NIP-59: Create gift wrap for NIP-17 DM
export async function createGiftWrap(rumor, recipientPubkey, senderPubkey) {
  // Generate random keypair for gift wrap
  const randomSk = generateSecretKey()
  const randomPk = getPublicKey(randomSk)
  
  // First, seal the rumor (encrypt it with sender -> recipient)
  const rumorJson = JSON.stringify(rumor)
  
  // Create seal event (kind 13)
  const sealContent = await encryptNip44(recipientPubkey, rumorJson)
  const sealEvent = createEventTemplate(13, sealContent, [])
  sealEvent.pubkey = senderPubkey
  const signedSeal = await signEventNip07(sealEvent)
  
  // Create gift wrap (kind 1059) with random key
  const sealJson = JSON.stringify(signedSeal)
  const conversationKey = nip44.getConversationKey(randomSk, recipientPubkey)
  const giftContent = nip44.encrypt(sealJson, conversationKey)
  
  // Random timestamp (up to 2 days ago)
  const randomOffset = Math.floor(Math.random() * 172800)
  
  const giftWrap = {
    kind: 1059,
    created_at: Math.floor(Date.now() / 1000) - randomOffset,
    tags: [['p', recipientPubkey]],
    content: giftContent,
    pubkey: randomPk
  }
  
  return finalizeEvent(giftWrap, randomSk)
}

// NIP-17: Send encrypted DM
export async function sendEncryptedDM(recipientPubkey, content) {
  // If using Nosskey and no private key stored, try to export it first
  if (getLoginMethod() === 'nosskey' && !hasStoredPrivateKey() && hasNosskey()) {
    try {
      const manager = window.nosskeyManager
      const keyInfo = manager.getCurrentKeyInfo()
      if (keyInfo) {
        const privateKeyHex = await manager.exportNostrKey(keyInfo)
        if (privateKeyHex) {
          window.nostrPrivateKey = privateKeyHex
        }
      }
    } catch (e) {
      console.error('Failed to export key for DM:', e)
      throw new Error('DMを送信するにはパスキー認証が必要です')
    }
  }
  
  // Check if we can encrypt (need private key or NIP-44 support)
  if (!hasStoredPrivateKey() && !(hasNip07() && window.nostr?.nip44?.encrypt)) {
    throw new Error('DM機能が利用できません。NIP-44対応の拡張機能か秘密鍵が必要です。')
  }
  
  try {
    const senderPubkey = await getPublicKeyAny()
    
    // Create rumor (unsigned kind 14)
    const rumor = {
      kind: 14,
      created_at: Math.floor(Date.now() / 1000),
      tags: [['p', recipientPubkey]],
      content,
      pubkey: senderPubkey
    }
    
    // Create and publish gift wrap for recipient
    const giftWrapToRecipient = await createGiftWrap(rumor, recipientPubkey, senderPubkey)
    await publishEvent(giftWrapToRecipient)
    
    // Create and publish gift wrap for sender (to save in own inbox)
    const giftWrapToSender = await createGiftWrap(rumor, senderPubkey, senderPubkey)
    await publishEvent(giftWrapToSender)
    
    return { success: true, rumor }
  } catch (e) {
    console.error('Failed to send DM:', e)
    throw e
  }
}

// Store pubkey in localStorage
export function savePubkey(pubkey) {
  if (typeof window !== 'undefined') {
    localStorage.setItem('nostr_pubkey', pubkey)
  }
}

// Load pubkey from localStorage
export function loadPubkey() {
  if (typeof window !== 'undefined') {
    return localStorage.getItem('nostr_pubkey')
  }
  return null
}

// Clear pubkey from localStorage
export function clearPubkey() {
  if (typeof window !== 'undefined') {
    localStorage.removeItem('nostr_pubkey')
  }
}

// Store NWC connection in localStorage
export function saveNWC(nwcUrl) {
  if (typeof window !== 'undefined') {
    localStorage.setItem('nwc_url', nwcUrl)
  }
}

// Load NWC connection from localStorage
export function loadNWC() {
  if (typeof window !== 'undefined') {
    return localStorage.getItem('nwc_url')
  }
  return null
}

// Clear NWC from localStorage
export function clearNWC() {
  if (typeof window !== 'undefined') {
    localStorage.removeItem('nwc_url')
  }
}

// Parse NWC URL
export function parseNWCUrl(url) {
  try {
    if (!url.startsWith('nostr+walletconnect://')) return null
    const parsed = new URL(url)
    const walletPubkey = parsed.pathname.replace('//', '')
    const relay = parsed.searchParams.get('relay')
    const secret = parsed.searchParams.get('secret')
    return { walletPubkey, relay, secret }
  } catch (e) {
    return null
  }
}

// NIP-05: Verify NIP-05 identifier
const nip05Cache = new Map()

export async function verifyNip05(nip05, pubkey) {
  if (!nip05 || !pubkey) return false
  
  // Normalize NIP-05 format
  let normalizedNip05 = nip05
  if (!nip05.includes('@')) {
    // Domain only format - treat as _@domain
    normalizedNip05 = `_@${nip05}`
  }
  
  // Check cache
  const cacheKey = `${normalizedNip05}:${pubkey}`
  if (nip05Cache.has(cacheKey)) {
    return nip05Cache.get(cacheKey)
  }
  
  try {
    const [name, domain] = normalizedNip05.split('@')
    if (!name || !domain) return false

    const url = `https://${domain}/.well-known/nostr.json?name=${encodeURIComponent(name)}`

    // Fetch with timeout and explicit CORS mode
    const controller = new AbortController()
    const timeoutId = setTimeout(() => controller.abort(), 5000) // 5 second timeout

    try {
      const response = await fetch(url, {
        signal: controller.signal,
        mode: 'cors',
        credentials: 'omit',
        referrerPolicy: 'no-referrer'
      })
      clearTimeout(timeoutId)

      if (!response.ok) return false

      const data = await response.json()
      const verified = data.names?.[name]?.toLowerCase() === pubkey.toLowerCase()

      // Cache result for 5 minutes
      nip05Cache.set(cacheKey, verified)
      setTimeout(() => nip05Cache.delete(cacheKey), 5 * 60 * 1000)

      return verified
    } catch (fetchError) {
      clearTimeout(timeoutId)
      // Silently ignore CORS errors, timeouts, and other fetch failures
      // These errors are logged by the browser but cannot be suppressed
      return false
    }
  } catch (e) {
    // Silently ignore verification failures (invalid format, etc)
    return false
  }
}

// NIP-05: Resolve NIP-05 identifier to pubkey
export async function resolveNip05(nip05) {
  if (!nip05) return null

  // Normalize NIP-05 format
  let normalizedNip05 = nip05.trim().toLowerCase()
  if (!normalizedNip05.includes('@')) {
    // Domain only format - treat as _@domain
    normalizedNip05 = `_@${normalizedNip05}`
  }

  try {
    const [name, domain] = normalizedNip05.split('@')
    if (!name || !domain) return null

    const url = `https://${domain}/.well-known/nostr.json?name=${encodeURIComponent(name)}`

    // Fetch with timeout and explicit CORS mode
    const controller = new AbortController()
    const timeoutId = setTimeout(() => controller.abort(), 5000)

    try {
      const response = await fetch(url, {
        signal: controller.signal,
        mode: 'cors',
        credentials: 'omit',
        referrerPolicy: 'no-referrer'
      })
      clearTimeout(timeoutId)

      if (!response.ok) return null

      const data = await response.json()
      const pubkey = data.names?.[name]

      if (pubkey && typeof pubkey === 'string' && pubkey.length === 64) {
        return pubkey.toLowerCase()
      }
      return null
    } catch (fetchError) {
      clearTimeout(timeoutId)
      return null
    }
  } catch (e) {
    return null
  }
}

// Parse nostr: links (note1, nevent1, npub1, nprofile1)
export function parseNostrLink(link) {
  try {
    if (link.startsWith('note1')) {
      const { type, data } = nip19.decode(link)
      if (type === 'note') {
        return { type: 'note', id: data }
      }
    } else if (link.startsWith('nevent1')) {
      const { type, data } = nip19.decode(link)
      if (type === 'nevent') {
        return { type: 'nevent', id: data.id, relays: data.relays, author: data.author }
      }
    } else if (link.startsWith('npub1')) {
      const { type, data } = nip19.decode(link)
      if (type === 'npub') {
        return { type: 'npub', pubkey: data }
      }
    } else if (link.startsWith('nprofile1')) {
      const { type, data } = nip19.decode(link)
      if (type === 'nprofile') {
        return { type: 'nprofile', pubkey: data.pubkey, relays: data.relays }
      }
    } else if (link.startsWith('naddr1')) {
      const { type, data } = nip19.decode(link)
      if (type === 'naddr') {
        return { type: 'naddr', identifier: data.identifier, pubkey: data.pubkey, kind: data.kind, relays: data.relays }
      }
    }
  } catch (e) {
    // Silently ignore invalid nostr links - they might be partial matches
  }
  return null
}

// Encode to note1
export function encodeNoteId(eventId) {
  try {
    return nip19.noteEncode(eventId)
  } catch (e) {
    return null
  }
}

// Encode to npub1
export function encodeNpub(pubkey) {
  try {
    return nip19.npubEncode(pubkey)
  } catch (e) {
    return null
  }
}

// NIP-51: Fetch mute list (kind 10000)
export async function fetchMuteList(pubkey) {
  try {
    const events = await fetchEvents(
      { kinds: [10000], authors: [pubkey], limit: 1 },
      RELAYS
    )
    
    let pubkeys = []
    let eventIds = []
    let hashtags = []
    let words = []
    let rawEvent = null
    
    if (events.length > 0) {
      const event = events[0]
      rawEvent = event
      
      pubkeys = event.tags.filter(t => t[0] === 'p').map(t => t[1])
      eventIds = event.tags.filter(t => t[0] === 'e').map(t => t[1])
      hashtags = event.tags.filter(t => t[0] === 't').map(t => t[1])
      words = event.tags.filter(t => t[0] === 'word').map(t => t[1])
    }
    
    return { pubkeys, eventIds, hashtags, words, rawEvent }
  } catch (e) {
    console.error('Failed to fetch mute list:', e)
    return { pubkeys: [], eventIds: [], hashtags: [], words: [] }
  }
}

// NIP-51: Add to mute list
export async function addToMuteList(myPubkey, type, value) {
  if (!canSign()) throw new Error('署名機能が必要です')
  
  try {
    // Fetch existing mute list
    const existing = await fetchMuteList(myPubkey)
    
    // Build tags from existing + new
    const tags = []
    
    // Add existing tags
    if (existing.rawEvent) {
      for (const tag of existing.rawEvent.tags) {
        tags.push(tag)
      }
    }
    
    // Add new tag if not already present
    const tagType = type === 'pubkey' ? 'p' : type === 'event' ? 'e' : type === 'hashtag' ? 't' : 'word'
    const existingValues = tags.filter(t => t[0] === tagType).map(t => t[1])
    
    if (!existingValues.includes(value)) {
      tags.push([tagType, value])
    } else {
      // Already muted
      return { success: true, alreadyMuted: true }
    }
    
    // Create and publish new mute list event
    const event = createEventTemplate(10000, '', tags)
    event.pubkey = myPubkey
    
    const signedEvent = await signEventNip07(event)
    const success = await publishEvent(signedEvent)
    
    return { success, event: signedEvent }
  } catch (e) {
    console.error('Failed to add to mute list:', e)
    throw e
  }
}

// NIP-51: Remove from mute list
export async function removeFromMuteList(myPubkey, type, value) {
  if (!canSign()) throw new Error('署名機能が必要です')
  
  try {
    const existing = await fetchMuteList(myPubkey)
    if (!existing.rawEvent) return { success: true }
    
    const tagType = type === 'pubkey' ? 'p' : type === 'event' ? 'e' : type === 'hashtag' ? 't' : 'word'
    
    // Filter out the tag to remove
    const tags = existing.rawEvent.tags.filter(t => !(t[0] === tagType && t[1] === value))
    
    const event = createEventTemplate(10000, '', tags)
    event.pubkey = myPubkey
    
    const signedEvent = await signEventNip07(event)
    const success = await publishEvent(signedEvent)
    
    return { success, event: signedEvent }
  } catch (e) {
    console.error('Failed to remove from mute list:', e)
    throw e
  }
}

// Fetch Lightning invoice from lud16 address
export async function fetchLightningInvoice(lud16, amountSats, comment = '') {
  if (!lud16 || !lud16.includes('@')) {
    throw new Error('Invalid Lightning address')
  }

  const [name, domain] = lud16.split('@')
  const lnurlUrl = `https://${domain}/.well-known/lnurlp/${name}`

  try {
    // Fetch LNURL-pay metadata
    const metaResponse = await fetch(lnurlUrl)
    if (!metaResponse.ok) throw new Error('Failed to fetch LNURL metadata')
    
    const meta = await metaResponse.json()
    
    // Check amount limits
    const amountMsats = amountSats * 1000
    if (amountMsats < meta.minSendable || amountMsats > meta.maxSendable) {
      throw new Error(`Amount must be between ${meta.minSendable/1000} and ${meta.maxSendable/1000} sats`)
    }

    // Build callback URL
    let callbackUrl = `${meta.callback}?amount=${amountMsats}`
    if (comment && meta.commentAllowed && comment.length <= meta.commentAllowed) {
      callbackUrl += `&comment=${encodeURIComponent(comment)}`
    }

    // Fetch invoice
    const invoiceResponse = await fetch(callbackUrl)
    if (!invoiceResponse.ok) throw new Error('Failed to fetch invoice')
    
    const invoiceData = await invoiceResponse.json()
    
    if (invoiceData.status === 'ERROR') {
      throw new Error(invoiceData.reason || 'Failed to create invoice')
    }

    return {
      invoice: invoiceData.pr,
      successAction: invoiceData.successAction,
      verify: invoiceData.verify
    }
  } catch (e) {
    console.error('Lightning invoice error:', e)
    throw e
  }
}

// Copy text to clipboard
export async function copyToClipboard(text) {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch (e) {
    console.error('Failed to copy:', e)
    return false
  }
}

// NIP-50: Search notes using search relay
export async function searchNotes(query, options = {}) {
  const { limit = 50, since, until, authors } = options
  const p = getPool()
  
  try {
    const filter = {
      kinds: [1],
      search: query,
      limit
    }
    
    if (since) filter.since = since
    if (until) filter.until = until
    if (authors?.length) filter.authors = authors
    
    const events = await p.querySync(
      [SEARCH_RELAY],
      filter
    )
    
    return events.sort((a, b) => b.created_at - a.created_at)
  } catch (e) {
    console.error('Search failed:', e)
    return []
  }
}

// Search profiles by name/nip05
export async function searchProfiles(query, limit = 20) {
  const p = getPool()
  
  try {
    const events = await p.querySync(
      [SEARCH_RELAY],
      {
        kinds: [0],
        search: query,
        limit
      }
    )
    
    return events.map(e => ({
      pubkey: e.pubkey,
      profile: parseProfile(e),
      event: e
    }))
  } catch (e) {
    console.error('Profile search failed:', e)
    return []
  }
}

// Image upload to nostr.build (default)
export async function uploadToNostrBuild(file) {
  const formData = new FormData()
  formData.append('file', file)
  
  try {
    const headers = {}
    
    // Add NIP-98 auth if possible (some servers require it)
    if (canSign()) {
      try {
        const now = Math.floor(Date.now() / 1000)
        const authEvent = {
          kind: 27235,
          created_at: now,
          tags: [
            ['u', 'https://nostr.build/api/v2/upload/files'],
            ['method', 'POST']
          ],
          content: ''
        }
        const signedAuth = await signEventNip07(authEvent)
        headers['Authorization'] = 'Nostr ' + btoa(JSON.stringify(signedAuth))
      } catch (e) {
        console.log('Could not create auth header:', e)
        // Continue without auth
      }
    }
    
    const response = await fetch('https://nostr.build/api/v2/upload/files', {
      method: 'POST',
      headers,
      body: formData
    })
    
    if (!response.ok) {
      throw new Error('Upload failed')
    }
    
    const data = await response.json()
    if (data.status === 'success' && data.data?.[0]?.url) {
      return data.data[0].url
    }
    throw new Error('Invalid response')
  } catch (e) {
    console.error('nostr.build upload failed:', e)
    throw e
  }
}

// Image upload to Blossom server
export async function uploadToBlossom(file, blossomUrl = 'https://blossom.nostr.build') {
  // Blossom uses PUT with file hash
  const arrayBuffer = await file.arrayBuffer()
  const hashBuffer = await crypto.subtle.digest('SHA-256', arrayBuffer)
  const hashArray = Array.from(new Uint8Array(hashBuffer))
  const hashHex = hashArray.map(b => b.toString(16).padStart(2, '0')).join('')
  
  try {
    // First, create auth event for Blossom (NIP-98)
    if (!canSign()) {
      throw new Error('署名機能が必要です')
    }
    
    const now = Math.floor(Date.now() / 1000)
    const authEvent = {
      kind: 24242,
      created_at: now,
      tags: [
        ['t', 'upload'],
        ['x', hashHex],
        ['expiration', String(now + 300)]
      ],
      content: 'Upload to Blossom'
    }
    
    const signedAuth = await signEventNip07(authEvent)
    const authHeader = btoa(JSON.stringify(signedAuth))
    
    const response = await fetch(`${blossomUrl}/upload`, {
      method: 'PUT',
      headers: {
        'Content-Type': file.type,
        'Authorization': `Nostr ${authHeader}`
      },
      body: file
    })
    
    if (!response.ok) {
      throw new Error(`Blossom upload failed: ${response.status}`)
    }
    
    const data = await response.json()
    if (data.url) {
      return data.url
    }
    throw new Error('Invalid Blossom response')
  } catch (e) {
    console.error('Blossom upload failed:', e)
    throw e
  }
}

// Get upload server URL from localStorage
export function getUploadServer() {
  if (typeof window !== 'undefined') {
    return localStorage.getItem('uploadServer') || 'nostr.build'
  }
  return 'nostr.build'
}

export function setUploadServer(server) {
  if (typeof window !== 'undefined') {
    localStorage.setItem('uploadServer', server)
  }
}

// Image upload to share.yabu.me (NIP-96 nostrcheck server)
export async function uploadToYabuMe(file) {
  const formData = new FormData()
  formData.append('file', file)
  
  try {
    const headers = {}
    
    // Add NIP-98 auth if possible
    if (canSign()) {
      try {
        const now = Math.floor(Date.now() / 1000)
        const authEvent = {
          kind: 27235,
          created_at: now,
          tags: [
            ['u', 'https://share.yabu.me/api/v2/media'],
            ['method', 'POST']
          ],
          content: ''
        }
        const signedAuth = await signEventNip07(authEvent)
        headers['Authorization'] = 'Nostr ' + btoa(JSON.stringify(signedAuth))
      } catch (e) {
        console.log('Could not create auth header:', e)
      }
    }
    
    const response = await fetch('https://share.yabu.me/api/v2/media', {
      method: 'POST',
      headers,
      body: formData
    })
    
    if (!response.ok) {
      throw new Error('Upload failed: ' + response.status)
    }
    
    const data = await response.json()
    // NIP-96 response format
    if (data.status === 'success' && data.nip94_event?.tags) {
      const urlTag = data.nip94_event.tags.find(t => t[0] === 'url')
      if (urlTag) {
        return urlTag[1]
      }
    }
    throw new Error('Invalid response')
  } catch (e) {
    console.error('share.yabu.me upload failed:', e)
    throw e
  }
}

// Generic upload function
export async function uploadImage(file) {
  const server = getUploadServer()
  
  if (server === 'nostr.build') {
    return uploadToNostrBuild(file)
  } else if (server === 'share.yabu.me') {
    return uploadToYabuMe(file)
  } else {
    // Assume it's a Blossom server URL
    return uploadToBlossom(file, server)
  }
}

// ============================================
// Caching and Prefetch Functions for PWA
// ============================================

// Fetch mute list with caching
export async function fetchMuteListCached(pubkey, forceRefresh = false) {
  if (!forceRefresh) {
    const cached = getCachedMuteList(pubkey)
    if (cached) {
      return cached
    }
  }
  
  const muteList = await fetchMuteList(pubkey)
  setCachedMuteList(pubkey, muteList)
  return muteList
}

// Fetch follow list with caching
export async function fetchFollowListCached(pubkey, forceRefresh = false) {
  if (!forceRefresh) {
    const cached = getCachedFollowList(pubkey)
    if (cached) {
      return cached
    }
  }
  
  const followList = await fetchFollowList(pubkey)
  setCachedFollowList(pubkey, followList)
  return followList
}

// Fetch single profile with caching
export async function fetchProfileCached(pubkey, forceRefresh = false) {
  if (!forceRefresh) {
    const cached = getCachedProfile(pubkey)
    if (cached) {
      return cached
    }
  }
  
  try {
    const events = await fetchEvents(
      { kinds: [0], authors: [pubkey], limit: 1 },
      RELAYS
    )
    
    if (events.length > 0) {
      const profile = parseProfile(events[0])
      if (profile) {
        setCachedProfile(pubkey, profile)
        return profile
      }
    }
  } catch (e) {
    console.error('Failed to fetch profile:', e)
  }
  
  return null
}

// Batch fetch profiles with caching
export async function fetchProfilesBatch(pubkeys, forceRefresh = false) {
  const profiles = {}
  const uncachedPubkeys = []
  
  // First, get cached profiles
  if (!forceRefresh) {
    for (const pubkey of pubkeys) {
      const cached = getCachedProfile(pubkey)
      if (cached) {
        profiles[pubkey] = cached
      } else {
        uncachedPubkeys.push(pubkey)
      }
    }
  } else {
    uncachedPubkeys.push(...pubkeys)
  }
  
  // Fetch uncached profiles
  if (uncachedPubkeys.length > 0) {
    try {
      const events = await fetchEvents(
        { kinds: [0], authors: uncachedPubkeys },
        RELAYS
      )
      
      for (const event of events) {
        const profile = parseProfile(event)
        if (profile) {
          profiles[event.pubkey] = profile
          setCachedProfile(event.pubkey, profile)
        }
      }
    } catch (e) {
      console.error('Failed to batch fetch profiles:', e)
    }
  }
  
  return profiles
}

// Get all cached profiles (for instant display)
export function getAllCachedProfiles() {
  return getCachedProfiles()
}

// Prefetch data for a tab in background
export async function prefetchHomeData(pubkey) {
  const results = {
    profile: null,
    posts: [],
    reactions: {}
  }
  
  try {
    // Fetch profile
    results.profile = await fetchProfileCached(pubkey)
    
    // Fetch recent posts (last 24h)
    const oneDayAgo = Math.floor(Date.now() / 1000) - 86400
    const posts = await fetchEvents(
      { kinds: [1], authors: [pubkey], since: oneDayAgo, limit: 30 },
      RELAYS
    )
    results.posts = posts
    
  } catch (e) {
    console.error('Prefetch home failed:', e)
  }
  
  return results
}

// Prefetch timeline data (quick initial load)
export async function prefetchTimelineQuick() {
  try {
    const fiveMinutesAgo = Math.floor(Date.now() / 1000) - 300
    const events = await fetchEvents(
      { kinds: [1], since: fiveMinutesAgo, limit: 20 },
      RELAYS
    )
    return events.sort((a, b) => b.created_at - a.created_at)
  } catch (e) {
    console.error('Quick timeline prefetch failed:', e)
    return []
  }
}

// Prefetch DM conversations list
export async function prefetchDMList(pubkey) {
  try {
    // Just fetch the gift wrap events for conversation list
    const oneWeekAgo = Math.floor(Date.now() / 1000) - 604800
    const events = await fetchEvents(
      { kinds: [1059], '#p': [pubkey], since: oneWeekAgo, limit: 50 },
      RELAYS
    )
    return events
  } catch (e) {
    console.error('Prefetch DM list failed:', e)
    return []
  }
}

// Background prefetch manager
let prefetchPromises = {}

export function startBackgroundPrefetch(pubkey) {
  // Prefetch mute list
  if (!prefetchPromises.muteList) {
    prefetchPromises.muteList = fetchMuteListCached(pubkey)
  }
  
  // Prefetch follow list
  if (!prefetchPromises.followList) {
    prefetchPromises.followList = fetchFollowListCached(pubkey)
  }
  
  // Prefetch own profile
  if (!prefetchPromises.profile) {
    prefetchPromises.profile = fetchProfileCached(pubkey)
  }
  
  return prefetchPromises
}

export function getPrefetchPromises() {
  return prefetchPromises
}

export function clearPrefetchPromises() {
  prefetchPromises = {}
}

// ============================================
// NIP-56: Reporting
// ============================================

/**
 * Report an event or user (NIP-56)
 * @param {Object} options - Report options
 * @param {string} options.eventId - Event ID to report (optional)
 * @param {string} options.pubkey - Pubkey to report
 * @param {string} options.reportType - Report type (spam, nudity, profanity, illegal, impersonation, malware, other)
 * @param {string} options.content - Additional information about the report
 * @returns {Promise<Object>} Result with success status and signed event
 */
export async function reportEvent({ eventId, pubkey, reportType, content = '' }) {
  if (!canSign()) throw new Error('署名機能が必要です')
  if (!pubkey) throw new Error('通報対象の公開鍵が必要です')
  if (!reportType) throw new Error('通報タイプが必要です')

  const validReportTypes = ['spam', 'nudity', 'profanity', 'illegal', 'impersonation', 'malware', 'other']
  if (!validReportTypes.includes(reportType)) {
    throw new Error('無効な通報タイプです')
  }

  try {
    const tags = [['p', pubkey, reportType]]

    // Add event reference if reporting a specific event
    if (eventId) {
      tags.push(['e', eventId, reportType])
    }

    const event = createEventTemplate(1984, content, tags)
    const signedEvent = await signEventNip07(event)
    const success = await publishEvent(signedEvent)

    return { success, event: signedEvent }
  } catch (e) {
    console.error('Failed to create report:', e)
    throw e
  }
}

// ============================================
// NIP-32: Labeling (Birdwatch)
// ============================================

/**
 * Create a Birdwatch label for an event (NIP-32)
 * @param {Object} options - Label options
 * @param {string} options.eventId - Event ID to label
 * @param {string} options.pubkey - Event author's pubkey
 * @param {string} options.contextType - Context type (misleading, missing_context, factual_error, outdated, satire)
 * @param {string} options.content - The context/note content
 * @param {string} options.sourceUrl - Optional source URL
 * @returns {Promise<Object>} Result with success status and signed event
 */
export async function createBirdwatchLabel({ eventId, pubkey, contextType, content, sourceUrl = '' }) {
  if (!canSign()) throw new Error('署名機能が必要です')
  if (!eventId) throw new Error('イベントIDが必要です')
  if (!content) throw new Error('コンテンツが必要です')

  const validContextTypes = ['misleading', 'missing_context', 'factual_error', 'outdated', 'satire']
  if (!validContextTypes.includes(contextType)) {
    contextType = 'missing_context' // Default fallback
  }

  try {
    // Build content with optional source URL
    let fullContent = content
    if (sourceUrl) {
      fullContent = `${content}\n\nソース: ${sourceUrl}`
    }

    // NIP-32 label structure
    const tags = [
      ['L', 'birdwatch'],                    // Label namespace
      ['l', contextType, 'birdwatch'],       // Label value with namespace marker
      ['e', eventId]                         // Target event
    ]

    // Add author reference for better discoverability
    if (pubkey) {
      tags.push(['p', pubkey])
    }

    const event = createEventTemplate(1985, fullContent, tags)
    const signedEvent = await signEventNip07(event)
    const success = await publishEvent(signedEvent)

    return { success, event: signedEvent }
  } catch (e) {
    console.error('Failed to create Birdwatch label:', e)
    throw e
  }
}

/**
 * Fetch Birdwatch labels for events (NIP-32)
 * @param {string[]} eventIds - Array of event IDs to fetch labels for
 * @param {string[]} relays - Optional relay list
 * @returns {Promise<Object>} Map of eventId -> array of label events
 */
export async function fetchBirdwatchLabels(eventIds, relays = [getDefaultRelay()]) {
  if (!eventIds || eventIds.length === 0) return {}

  try {
    // Fetch kind 1985 events that reference these events with birdwatch namespace
    const filter = {
      kinds: [1985],
      '#e': eventIds,
      '#L': ['birdwatch'],
      limit: eventIds.length * 10 // Allow up to 10 labels per event
    }

    const events = await fetchEvents(filter, relays)

    // Group labels by target event ID
    const labelsByEvent = {}
    for (const event of events) {
      const eventTag = event.tags.find(t => t[0] === 'e')
      if (eventTag) {
        const targetEventId = eventTag[1]
        if (!labelsByEvent[targetEventId]) {
          labelsByEvent[targetEventId] = []
        }
        labelsByEvent[targetEventId].push(event)
      }
    }

    return labelsByEvent
  } catch (e) {
    console.error('Failed to fetch Birdwatch labels:', e)
    return {}
  }
}

/**
 * Check if user has already created a Birdwatch label for an event
 * @param {string} eventId - Target event ID
 * @param {string} userPubkey - User's public key
 * @param {string[]} relays - Optional relay list
 * @returns {Promise<boolean>} True if user has already labeled this event
 */
export async function hasUserBirdwatchLabel(eventId, userPubkey, relays = [getDefaultRelay()]) {
  if (!eventId || !userPubkey) return false

  try {
    const filter = {
      kinds: [1985],
      authors: [userPubkey],
      '#e': [eventId],
      '#L': ['birdwatch'],
      limit: 1
    }

    const events = await fetchEvents(filter, relays)
    return events.length > 0
  } catch (e) {
    console.error('Failed to check user Birdwatch label:', e)
    return false
  }
}

/**
 * Rate a Birdwatch label (reaction to kind 1985)
 * @param {string} labelEventId - The label event ID to rate
 * @param {string} rating - 'helpful' or 'not_helpful'
 * @returns {Promise<Object>} Result with success status
 */
export async function rateBirdwatchLabel(labelEventId, rating) {
  if (!canSign()) throw new Error('署名機能が必要です')
  if (!labelEventId) throw new Error('ラベルイベントIDが必要です')

  try {
    // Use reaction content to indicate rating
    const reactionContent = rating === 'helpful' ? '+' : '-'

    const tags = [['e', labelEventId]]
    const event = createEventTemplate(7, reactionContent, tags)
    const signedEvent = await signEventNip07(event)
    const success = await publishEvent(signedEvent)

    return { success, event: signedEvent }
  } catch (e) {
    console.error('Failed to rate Birdwatch label:', e)
    throw e
  }
}

// ============================================
// NIP-70: Protected Events
// ============================================

/**
 * Check if an event is protected (NIP-70)
 * Protected events have a ["-"] tag and should only be published by the author
 * @param {Object} event - The Nostr event to check
 * @returns {boolean} True if the event is protected
 */
export function isProtectedEvent(event) {
  if (!event || !Array.isArray(event.tags)) return false
  return event.tags.some(tag => tag.length === 1 && tag[0] === '-')
}

/**
 * Add protected tag to an event (NIP-70)
 * This prevents the event from being republished by other users
 * @param {Object} event - The event template to protect
 * @returns {Object} The event with protection tag added
 */
export function addProtectedTag(event) {
  if (!event) return event
  const tags = event.tags ? [...event.tags] : []
  if (!tags.some(tag => tag.length === 1 && tag[0] === '-')) {
    tags.push(['-'])
  }
  return { ...event, tags }
}

/**
 * Create a protected event template (NIP-70)
 * @param {number} kind - Event kind
 * @param {string} content - Event content
 * @param {Array} tags - Additional tags
 * @returns {Object} Protected event template
 */
export function createProtectedEventTemplate(kind, content, tags = []) {
  const event = createEventTemplate(kind, content, tags)
  return addProtectedTag(event)
}

// ============================================
// NIP-17 Extended: File Messages and DM Relays
// ============================================

/**
 * Send encrypted file message (NIP-17 kind 15)
 * @param {string} recipientPubkey - Recipient's public key
 * @param {string} fileUrl - URL of the file
 * @param {Object} fileMetadata - File metadata
 * @param {string} fileMetadata.mimeType - MIME type of the file
 * @param {string} fileMetadata.hash - SHA-256 hash of the encrypted file
 * @param {string} [fileMetadata.encryptionKey] - Decryption key (if encrypted)
 * @param {string} [fileMetadata.encryptionNonce] - Decryption nonce (if encrypted)
 * @param {string} [fileMetadata.encryptionAlgorithm] - Encryption algorithm (e.g., "aes-gcm")
 * @returns {Promise<Object>} Result with success status and rumor
 */
export async function sendEncryptedFileMessage(recipientPubkey, fileUrl, fileMetadata = {}) {
  // Check encryption capability
  if (!hasStoredPrivateKey() && !(hasNip07() && window.nostr?.nip44?.encrypt)) {
    throw new Error('DM機能が利用できません。NIP-44対応の拡張機能か秘密鍵が必要です。')
  }

  try {
    const senderPubkey = await getPublicKeyAny()

    // Build tags for file message
    const tags = [['p', recipientPubkey]]

    // Add file-type tag if mimeType provided
    if (fileMetadata.mimeType) {
      tags.push(['file-type', fileMetadata.mimeType])
    }

    // Add encryption info if file is encrypted
    if (fileMetadata.encryptionAlgorithm) {
      tags.push(['encryption-algorithm', fileMetadata.encryptionAlgorithm])
    }
    if (fileMetadata.encryptionKey) {
      tags.push(['decryption-key', fileMetadata.encryptionKey])
    }
    if (fileMetadata.encryptionNonce) {
      tags.push(['decryption-nonce', fileMetadata.encryptionNonce])
    }

    // Add file hash (x tag)
    if (fileMetadata.hash) {
      tags.push(['x', fileMetadata.hash])
    }

    // Create rumor (unsigned kind 15)
    const rumor = {
      kind: 15,
      created_at: Math.floor(Date.now() / 1000),
      tags,
      content: fileUrl,
      pubkey: senderPubkey
    }

    // Create and publish gift wrap for recipient
    const giftWrapToRecipient = await createGiftWrap(rumor, recipientPubkey, senderPubkey)
    await publishEvent(giftWrapToRecipient)

    // Create and publish gift wrap for sender
    const giftWrapToSender = await createGiftWrap(rumor, senderPubkey, senderPubkey)
    await publishEvent(giftWrapToSender)

    return { success: true, rumor }
  } catch (e) {
    console.error('Failed to send file message:', e)
    throw e
  }
}

/**
 * Fetch user's DM relay list (NIP-17 kind 10050)
 * @param {string} pubkey - User's public key
 * @param {string[]} relays - Relays to query
 * @returns {Promise<string[]>} Array of DM relay URLs
 */
export async function fetchDMRelayList(pubkey, relays = [getDefaultRelay()]) {
  try {
    const events = await fetchEvents(
      { kinds: [10050], authors: [pubkey], limit: 1 },
      relays
    )

    if (events.length === 0) return []

    // Extract relay URLs from 'relay' tags
    const dmRelays = events[0].tags
      .filter(tag => tag[0] === 'relay' && tag[1])
      .map(tag => tag[1])
      .filter(isValidRelayUrl)

    return dmRelays
  } catch (e) {
    console.error('Failed to fetch DM relay list:', e)
    return []
  }
}

/**
 * Set user's DM relay list (NIP-17 kind 10050)
 * @param {string[]} dmRelays - Array of relay URLs for receiving DMs
 * @returns {Promise<Object>} Result with success status
 */
export async function setDMRelayList(dmRelays) {
  if (!canSign()) throw new Error('署名機能が必要です')

  try {
    const tags = dmRelays
      .filter(isValidRelayUrl)
      .map(relay => ['relay', relay])

    const event = createEventTemplate(10050, '', tags)
    const signedEvent = await signEventNip07(event)
    const success = await publishEvent(signedEvent)

    return { success, event: signedEvent }
  } catch (e) {
    console.error('Failed to set DM relay list:', e)
    throw e
  }
}

// ============================================
// Event Backup (Export/Import)
// ============================================

/**
 * Fetch all user events for backup
 * @param {string} pubkey - User's public key
 * @param {Object} options - Fetch options
 * @param {number[]} [options.kinds] - Specific kinds to fetch (empty = all)
 * @param {number} [options.since] - Start timestamp
 * @param {number} [options.until] - End timestamp
 * @param {Function} [options.onProgress] - Progress callback
 * @returns {Promise<Object[]>} Array of events
 */
export async function fetchAllUserEvents(pubkey, options = {}) {
  const { kinds, since, until, onProgress } = options
  const allEvents = []
  const seenIds = new Set()

  // Build filter
  const filter = { authors: [pubkey] }
  if (kinds && kinds.length > 0) {
    filter.kinds = kinds
  }
  if (since) filter.since = since
  if (until) filter.until = until

  // Fetch in batches to avoid timeout
  const batchSize = 500
  let lastTimestamp = until || Math.floor(Date.now() / 1000)
  let hasMore = true
  let batchCount = 0

  while (hasMore) {
    const batchFilter = { ...filter, until: lastTimestamp, limit: batchSize }

    try {
      const events = await fetchEvents(batchFilter, [getDefaultRelay()])

      if (events.length === 0) {
        hasMore = false
        break
      }

      for (const event of events) {
        if (!seenIds.has(event.id)) {
          seenIds.add(event.id)
          allEvents.push(event)
        }
      }

      // Update last timestamp for next batch
      const minTimestamp = Math.min(...events.map(e => e.created_at))
      if (minTimestamp >= lastTimestamp) {
        hasMore = false
      } else {
        lastTimestamp = minTimestamp - 1
      }

      batchCount++
      if (onProgress) {
        onProgress({ fetched: allEvents.length, batch: batchCount })
      }

      // Stop if we've reached the since filter
      if (since && minTimestamp <= since) {
        hasMore = false
      }

    } catch (e) {
      console.error('Batch fetch error:', e)
      hasMore = false
    }
  }

  return allEvents.sort((a, b) => a.created_at - b.created_at)
}

/**
 * Export events to NIP-70 JSON format
 * @param {Object[]} events - Array of events to export
 * @returns {string} JSON string in NIP-70 format
 */
export function exportEventsToJson(events) {
  // NIP-70 export format: array of events
  return JSON.stringify(events, null, 2)
}

/**
 * Parse events from JSON import
 * @param {string} jsonString - JSON string to parse
 * @returns {Object[]} Array of valid events
 */
export function parseEventsFromJson(jsonString) {
  try {
    const parsed = JSON.parse(jsonString)

    // Handle both array and single event
    const events = Array.isArray(parsed) ? parsed : [parsed]

    // Validate each event has required fields
    return events.filter(event => {
      if (!event || typeof event !== 'object') return false
      if (!event.id || typeof event.id !== 'string') return false
      if (!event.pubkey || typeof event.pubkey !== 'string') return false
      if (typeof event.kind !== 'number') return false
      if (!event.sig || typeof event.sig !== 'string') return false
      if (typeof event.created_at !== 'number') return false
      if (!Array.isArray(event.tags)) return false
      if (typeof event.content !== 'string') return false
      return true
    })
  } catch (e) {
    console.error('Failed to parse events JSON:', e)
    return []
  }
}

/**
 * Import events to relays
 * @param {Object[]} events - Array of events to import
 * @param {string[]} relays - Relays to publish to
 * @param {Function} [onProgress] - Progress callback
 * @returns {Promise<Object>} Import result with success/failed counts
 */
export async function importEventsToRelays(events, relays = [getDefaultRelay()], onProgress) {
  const results = {
    total: events.length,
    success: 0,
    failed: 0,
    skipped: 0,
    errors: []
  }

  const userPubkey = loadPubkey()

  for (let i = 0; i < events.length; i++) {
    const event = events[i]

    try {
      // Skip protected events from other users
      if (isProtectedEvent(event) && event.pubkey !== userPubkey) {
        results.skipped++
        results.errors.push({ id: event.id, error: 'Protected event from another user' })
        continue
      }

      // Publish the event
      const success = await publishEvent(event, relays)

      if (success) {
        results.success++
      } else {
        results.failed++
        results.errors.push({ id: event.id, error: 'Publish failed' })
      }
    } catch (e) {
      results.failed++
      results.errors.push({ id: event.id, error: e.message })
    }

    if (onProgress) {
      onProgress({
        current: i + 1,
        total: events.length,
        success: results.success,
        failed: results.failed
      })
    }

    // Small delay to avoid overwhelming relays
    if (i < events.length - 1) {
      await new Promise(r => setTimeout(r, 50))
    }
  }

  return results
}

// ============================================
// NIP-11: Relay Information Document
// ============================================

/**
 * Cache for relay information documents
 * @type {Map<string, {info: Object, timestamp: number}>}
 */
const relayInfoCache = new Map()

/**
 * Convert WebSocket URL to HTTP URL for NIP-11 fetch
 * @param {string} wsUrl - WebSocket URL (wss://example.com)
 * @returns {string} HTTP URL (https://example.com)
 */
function wsToHttpUrl(wsUrl) {
  try {
    const url = new URL(wsUrl)
    url.protocol = url.protocol === 'wss:' ? 'https:' : 'http:'
    return url.toString().replace(/\/$/, '') // Remove trailing slash
  } catch {
    return null
  }
}

/**
 * Fetch relay information document (NIP-11)
 * @param {string} relayUrl - WebSocket URL of the relay
 * @param {Object} options - Fetch options
 * @param {boolean} [options.forceRefresh=false] - Force refresh cache
 * @param {number} [options.timeout=5000] - Request timeout in ms
 * @returns {Promise<Object|null>} Relay information or null on error
 */
export async function fetchRelayInfo(relayUrl, options = {}) {
  const { forceRefresh = false, timeout = 5000 } = options

  // Check cache first
  if (!forceRefresh) {
    const cached = relayInfoCache.get(relayUrl)
    if (cached && Date.now() - cached.timestamp < CACHE_CONFIG.durations.relayInfo) {
      return cached.info
    }
  }

  const httpUrl = wsToHttpUrl(relayUrl)
  if (!httpUrl) return null

  try {
    const controller = new AbortController()
    const timeoutId = setTimeout(() => controller.abort(), timeout)

    const response = await fetch(httpUrl, {
      method: 'GET',
      headers: {
        'Accept': 'application/nostr+json'
      },
      signal: controller.signal,
      mode: 'cors',
      credentials: 'omit'
    })

    clearTimeout(timeoutId)

    if (!response.ok) {
      console.warn(`[NIP-11] Failed to fetch relay info from ${relayUrl}: ${response.status}`)
      return null
    }

    const info = await response.json()

    // Cache the result
    relayInfoCache.set(relayUrl, { info, timestamp: Date.now() })

    return info
  } catch (e) {
    if (e.name !== 'AbortError') {
      console.warn(`[NIP-11] Error fetching relay info from ${relayUrl}:`, e.message)
    }
    return null
  }
}

/**
 * Get relay supported NIPs (NIP-11)
 * @param {string} relayUrl - WebSocket URL of the relay
 * @returns {Promise<number[]>} Array of supported NIP numbers
 */
export async function getRelaySupportedNips(relayUrl) {
  const info = await fetchRelayInfo(relayUrl)
  return info?.supported_nips || []
}

/**
 * Check if relay supports a specific NIP (NIP-11)
 * @param {string} relayUrl - WebSocket URL of the relay
 * @param {number} nip - NIP number to check
 * @returns {Promise<boolean>} True if NIP is supported
 */
export async function relaySupportsNip(relayUrl, nip) {
  const supportedNips = await getRelaySupportedNips(relayUrl)
  return supportedNips.includes(nip)
}

/**
 * Get relay limitations (NIP-11)
 * @param {string} relayUrl - WebSocket URL of the relay
 * @returns {Promise<Object|null>} Relay limitations or null
 */
export async function getRelayLimitations(relayUrl) {
  const info = await fetchRelayInfo(relayUrl)
  return info?.limitation || null
}

/**
 * Check if relay requires authentication (NIP-11 + NIP-42)
 * @param {string} relayUrl - WebSocket URL of the relay
 * @returns {Promise<boolean>} True if authentication is required
 */
export async function relayRequiresAuth(relayUrl) {
  const limitations = await getRelayLimitations(relayUrl)
  return limitations?.auth_required === true
}

/**
 * Check if relay requires payment (NIP-11)
 * @param {string} relayUrl - WebSocket URL of the relay
 * @returns {Promise<boolean>} True if payment is required
 */
export async function relayRequiresPayment(relayUrl) {
  const limitations = await getRelayLimitations(relayUrl)
  return limitations?.payment_required === true
}

/**
 * Get all relay info for display (NIP-11)
 * @param {string} relayUrl - WebSocket URL of the relay
 * @returns {Promise<Object>} Formatted relay information
 */
export async function getRelayInfoForDisplay(relayUrl) {
  const info = await fetchRelayInfo(relayUrl)
  if (!info) {
    return {
      url: relayUrl,
      available: false
    }
  }

  return {
    url: relayUrl,
    available: true,
    name: info.name || null,
    description: info.description || null,
    banner: info.banner || null,
    icon: info.icon || null,
    pubkey: info.pubkey || null,
    contact: info.contact || null,
    supportedNips: info.supported_nips || [],
    software: info.software || null,
    version: info.version || null,
    limitation: info.limitation || null,
    retention: info.retention || null,
    relayCountries: info.relay_countries || [],
    languageTags: info.language_tags || [],
    tags: info.tags || [],
    postingPolicy: info.posting_policy || null,
    paymentsUrl: info.payments_url || null,
    fees: info.fees || null
  }
}

/**
 * Clear relay info cache
 * @param {string} [relayUrl] - Specific relay URL to clear, or all if not specified
 */
export function clearRelayInfoCache(relayUrl) {
  if (relayUrl) {
    relayInfoCache.delete(relayUrl)
  } else {
    relayInfoCache.clear()
  }
}

// ============================================
// NIP-42: Authentication of Clients to Relays
// ============================================

/**
 * Store for pending authentication challenges
 * @type {Map<string, {challenge: string, timestamp: number}>}
 */
const authChallenges = new Map()

/**
 * Store for authenticated relays
 * @type {Map<string, {pubkey: string, timestamp: number}>}
 */
const authenticatedRelays = new Map()

/**
 * Handle AUTH challenge from relay (NIP-42)
 * Call this when receiving an AUTH message from a relay
 * @param {string} relayUrl - WebSocket URL of the relay
 * @param {string} challenge - Challenge string from the relay
 */
export function handleAuthChallenge(relayUrl, challenge) {
  authChallenges.set(relayUrl, {
    challenge,
    timestamp: Date.now()
  })
}

/**
 * Create authentication event for relay (NIP-42)
 * @param {string} relayUrl - WebSocket URL of the relay
 * @param {string} challenge - Challenge string from the relay
 * @returns {Promise<Object>} Signed authentication event (kind 22242)
 */
export async function createAuthEvent(relayUrl, challenge) {
  if (!canSign()) {
    throw new Error(ERROR_MESSAGES.noSigningMethod)
  }

  // Validate challenge is recent (within 10 minutes)
  const storedChallenge = authChallenges.get(relayUrl)
  if (storedChallenge) {
    const age = Date.now() - storedChallenge.timestamp
    if (age > 10 * 60 * 1000) {
      authChallenges.delete(relayUrl)
      throw new Error('認証チャレンジの有効期限が切れています')
    }
  }

  const event = {
    kind: NOSTR_KINDS.CLIENT_AUTH,
    created_at: Math.floor(Date.now() / 1000),
    tags: [
      ['relay', relayUrl],
      ['challenge', challenge]
    ],
    content: ''
  }

  const signedEvent = await signEventNip07(event)
  return signedEvent
}

/**
 * Authenticate with a relay (NIP-42)
 * @param {string} relayUrl - WebSocket URL of the relay
 * @param {string} challenge - Challenge string from the relay
 * @returns {Promise<Object>} Signed authentication event
 */
export async function authenticateWithRelay(relayUrl, challenge) {
  const authEvent = await createAuthEvent(relayUrl, challenge)

  // Store the challenge for this authentication attempt
  handleAuthChallenge(relayUrl, challenge)

  return authEvent
}

/**
 * Check if we have a pending authentication challenge for a relay (NIP-42)
 * @param {string} relayUrl - WebSocket URL of the relay
 * @returns {Object|null} Challenge info or null
 */
export function getPendingAuthChallenge(relayUrl) {
  const challenge = authChallenges.get(relayUrl)
  if (!challenge) return null

  // Check if challenge is still valid (10 minutes)
  const age = Date.now() - challenge.timestamp
  if (age > 10 * 60 * 1000) {
    authChallenges.delete(relayUrl)
    return null
  }

  return challenge
}

/**
 * Mark relay as authenticated (NIP-42)
 * @param {string} relayUrl - WebSocket URL of the relay
 * @param {string} pubkey - Authenticated public key
 */
export function markRelayAuthenticated(relayUrl, pubkey) {
  authenticatedRelays.set(relayUrl, {
    pubkey,
    timestamp: Date.now()
  })
  // Clear the challenge since we're now authenticated
  authChallenges.delete(relayUrl)
}

/**
 * Check if relay is authenticated (NIP-42)
 * @param {string} relayUrl - WebSocket URL of the relay
 * @returns {boolean} True if authenticated
 */
export function isRelayAuthenticated(relayUrl) {
  const auth = authenticatedRelays.get(relayUrl)
  if (!auth) return false

  // Consider auth valid for 24 hours
  const age = Date.now() - auth.timestamp
  if (age > 24 * 60 * 60 * 1000) {
    authenticatedRelays.delete(relayUrl)
    return false
  }

  return true
}

/**
 * Get authenticated pubkey for relay (NIP-42)
 * @param {string} relayUrl - WebSocket URL of the relay
 * @returns {string|null} Authenticated pubkey or null
 */
export function getRelayAuthPubkey(relayUrl) {
  const auth = authenticatedRelays.get(relayUrl)
  return auth?.pubkey || null
}

/**
 * Clear authentication state for relay (NIP-42)
 * @param {string} [relayUrl] - Specific relay URL to clear, or all if not specified
 */
export function clearRelayAuth(relayUrl) {
  if (relayUrl) {
    authChallenges.delete(relayUrl)
    authenticatedRelays.delete(relayUrl)
  } else {
    authChallenges.clear()
    authenticatedRelays.clear()
  }
}

/**
 * Parse "auth-required" message from CLOSED/OK responses (NIP-42)
 * @param {string} message - Message from relay
 * @returns {boolean} True if authentication is required
 */
export function isAuthRequiredMessage(message) {
  if (!message || typeof message !== 'string') return false
  return message.toLowerCase().startsWith('auth-required')
}

// ============================================
// NIP-62: Request to Vanish
// ============================================

/**
 * Create a Request to Vanish event (NIP-62)
 * This requests that relays delete all events from the user's public key
 * @param {Object} options - Vanish request options
 * @param {string[]} [options.relays] - Specific relay URLs to request vanish from, or omit for ALL_RELAYS
 * @param {string} [options.reason] - Optional reason for the vanish request
 * @returns {Promise<Object>} Result with success status and signed event
 */
export async function createVanishRequest(options = {}) {
  if (!canSign()) {
    throw new Error(ERROR_MESSAGES.noSigningMethod)
  }

  const { relays, reason = '' } = options
  const tags = []

  // If specific relays provided, add them; otherwise use ALL_RELAYS
  if (relays && Array.isArray(relays) && relays.length > 0) {
    for (const relay of relays) {
      if (isValidRelayUrl(relay)) {
        tags.push(['relay', relay])
      }
    }
  } else {
    // Global vanish request to all relays
    tags.push(['relay', 'ALL_RELAYS'])
  }

  const event = {
    kind: NOSTR_KINDS.REQUEST_TO_VANISH,
    created_at: Math.floor(Date.now() / 1000),
    tags,
    content: reason
  }

  try {
    const signedEvent = await signEventNip07(event)
    return { success: true, event: signedEvent }
  } catch (e) {
    console.error('Failed to create vanish request:', e)
    throw e
  }
}

/**
 * Publish a Request to Vanish event (NIP-62)
 * @param {Object} options - Vanish request options
 * @param {string[]} [options.relays] - Specific relay URLs to request vanish from
 * @param {string} [options.reason] - Optional reason for the vanish request
 * @param {string[]} [options.publishTo] - Relays to publish the vanish request to
 * @returns {Promise<Object>} Result with success status
 */
export async function requestVanish(options = {}) {
  const { relays, reason = '', publishTo = [getDefaultRelay()] } = options

  try {
    // Create the vanish request event
    const { event } = await createVanishRequest({ relays, reason })

    // If it's a global vanish request (ALL_RELAYS), publish to many relays
    const targetRelays = (!relays || relays.length === 0)
      ? [...new Set([...publishTo, ...FALLBACK_RELAYS])]
      : publishTo

    // Publish the event
    const success = await publishEvent(event, targetRelays)

    return {
      success,
      event,
      publishedTo: targetRelays
    }
  } catch (e) {
    console.error('Failed to request vanish:', e)
    throw e
  }
}

/**
 * Request vanish from specific relay (NIP-62)
 * @param {string} relayUrl - Relay URL to request vanish from
 * @param {string} [reason] - Optional reason
 * @returns {Promise<Object>} Result with success status
 */
export async function requestVanishFromRelay(relayUrl, reason = '') {
  if (!isValidRelayUrl(relayUrl)) {
    throw new Error('無効なリレーURLです')
  }

  return requestVanish({
    relays: [relayUrl],
    reason,
    publishTo: [relayUrl]
  })
}

/**
 * Request global vanish from all relays (NIP-62)
 * This should be used with extreme caution as it's irreversible
 * @param {string} [reason] - Optional reason
 * @param {string[]} [additionalRelays] - Additional relays to broadcast to
 * @returns {Promise<Object>} Result with success status
 */
export async function requestGlobalVanish(reason = '', additionalRelays = []) {
  return requestVanish({
    relays: [], // Empty array triggers ALL_RELAYS tag
    reason,
    publishTo: [
      getDefaultRelay(),
      ...FALLBACK_RELAYS,
      ...additionalRelays.filter(isValidRelayUrl)
    ]
  })
}

/**
 * Check if an event is a vanish request (NIP-62)
 * @param {Object} event - Nostr event
 * @returns {boolean} True if the event is a vanish request
 */
export function isVanishRequest(event) {
  return event?.kind === NOSTR_KINDS.REQUEST_TO_VANISH
}

/**
 * Get target relays from a vanish request event (NIP-62)
 * @param {Object} event - Vanish request event
 * @returns {string[]} Array of target relay URLs, or ['ALL_RELAYS'] for global
 */
export function getVanishTargetRelays(event) {
  if (!isVanishRequest(event)) return []

  return event.tags
    .filter(tag => tag[0] === 'relay')
    .map(tag => tag[1])
}

/**
 * Check if a vanish request is global (targets ALL_RELAYS) (NIP-62)
 * @param {Object} event - Vanish request event
 * @returns {boolean} True if it's a global vanish request
 */
export function isGlobalVanishRequest(event) {
  const targets = getVanishTargetRelays(event)
  return targets.includes('ALL_RELAYS')
}

// ============================================
// NIP-65: Relay List Metadata
// ============================================

/**
 * Fetch user's relay list metadata (NIP-65 kind 10002)
 * @param {string} pubkey - User's public key
 * @param {string[]} relays - Relays to query
 * @returns {Promise<{read: string[], write: string[], all: Array<{url: string, read: boolean, write: boolean}>}>}
 */
export async function fetchRelayListMetadata(pubkey, relays = [getDefaultRelay()]) {
  try {
    const events = await fetchEvents(
      { kinds: [10002], authors: [pubkey], limit: 1 },
      relays
    )

    const result = { read: [], write: [], all: [] }
    if (events.length === 0) return result

    // Get most recent event
    const event = events.sort((a, b) => b.created_at - a.created_at)[0]

    // Parse relay tags
    for (const tag of event.tags) {
      if (tag[0] !== 'r') continue

      const url = tag[1]
      if (!isValidRelayUrl(url)) continue

      const marker = tag[2] // 'read' or 'write' or undefined (both)

      if (marker === 'read') {
        result.read.push(url)
        result.all.push({ url, read: true, write: false })
      } else if (marker === 'write') {
        result.write.push(url)
        result.all.push({ url, read: false, write: true })
      } else {
        // No marker means both read and write
        result.read.push(url)
        result.write.push(url)
        result.all.push({ url, read: true, write: true })
      }
    }

    return result
  } catch (e) {
    console.error('Failed to fetch relay list metadata:', e)
    return { read: [], write: [], all: [] }
  }
}

/**
 * Publish relay list metadata (NIP-65 kind 10002)
 * @param {Array<{url: string, read?: boolean, write?: boolean}>} relayList - Array of relay configs
 * @returns {Promise<Object>} Result with success status and signed event
 */
export async function publishRelayListMetadata(relayList) {
  if (!canSign()) throw new Error('署名機能が必要です')

  try {
    const tags = []

    for (const relay of relayList) {
      if (!isValidRelayUrl(relay.url)) continue

      const read = relay.read !== false
      const write = relay.write !== false

      if (read && write) {
        // Both read and write - no marker
        tags.push(['r', relay.url])
      } else if (read) {
        tags.push(['r', relay.url, 'read'])
      } else if (write) {
        tags.push(['r', relay.url, 'write'])
      }
    }

    if (tags.length === 0) {
      throw new Error('少なくとも1つの有効なリレーが必要です')
    }

    const event = createEventTemplate(10002, '', tags)
    const signedEvent = await signEventNip07(event)
    const success = await publishEvent(signedEvent)

    return { success, event: signedEvent }
  } catch (e) {
    console.error('Failed to publish relay list metadata:', e)
    throw e
  }
}

/**
 * Add a relay to user's relay list metadata (NIP-65)
 * @param {string} relayUrl - Relay URL to add
 * @param {Object} options - Options
 * @param {boolean} [options.read=true] - Use for reading
 * @param {boolean} [options.write=true] - Use for writing
 * @returns {Promise<Object>} Result with success status
 */
export async function addRelayToList(relayUrl, options = { read: true, write: true }) {
  if (!isValidRelayUrl(relayUrl)) {
    throw new Error('無効なリレーURLです')
  }

  const pubkey = loadPubkey()
  if (!pubkey) throw new Error('ログインが必要です')

  // Fetch current relay list
  const currentList = await fetchRelayListMetadata(pubkey)

  // Check if relay already exists
  const existing = currentList.all.find(r => r.url === relayUrl)
  if (existing) {
    // Update existing relay
    const updated = currentList.all.map(r => {
      if (r.url === relayUrl) {
        return { url: relayUrl, read: options.read, write: options.write }
      }
      return r
    })
    return publishRelayListMetadata(updated)
  }

  // Add new relay
  const newList = [
    ...currentList.all,
    { url: relayUrl, read: options.read, write: options.write }
  ]

  return publishRelayListMetadata(newList)
}

/**
 * Remove a relay from user's relay list metadata (NIP-65)
 * @param {string} relayUrl - Relay URL to remove
 * @returns {Promise<Object>} Result with success status
 */
export async function removeRelayFromList(relayUrl) {
  const pubkey = loadPubkey()
  if (!pubkey) throw new Error('ログインが必要です')

  // Fetch current relay list
  const currentList = await fetchRelayListMetadata(pubkey)

  // Filter out the relay
  const newList = currentList.all.filter(r => r.url !== relayUrl)

  if (newList.length === currentList.all.length) {
    // Relay wasn't in the list
    return { success: true, noChange: true }
  }

  return publishRelayListMetadata(newList)
}

/**
 * Set relay list from geolocation (auto-setup)
 * @param {Array<{url: string, name: string, region: string}>} recommendedRelays - Relays from geohash lookup
 * @returns {Promise<Object>} Result with success status
 */
export async function setRelayListFromGeolocation(recommendedRelays) {
  if (!recommendedRelays || recommendedRelays.length === 0) {
    throw new Error('リレーリストが空です')
  }

  const relayList = recommendedRelays.map(relay => ({
    url: relay.url,
    read: true,
    write: true
  }))

  return publishRelayListMetadata(relayList)
}

/**
 * Get user's preferred read relays (checks NIP-65 first, then falls back to default)
 * @param {string} [pubkey] - User's pubkey (uses stored if not provided)
 * @returns {Promise<string[]>} Array of read relay URLs
 */
export async function getPreferredReadRelays(pubkey) {
  const userPubkey = pubkey || loadPubkey()
  if (!userPubkey) return [getDefaultRelay()]

  try {
    const relayList = await fetchRelayListMetadata(userPubkey)
    if (relayList.read.length > 0) {
      return relayList.read
    }
  } catch (e) {
    console.error('Failed to fetch preferred relays:', e)
  }

  return [getDefaultRelay()]
}

/**
 * Get user's preferred write relays (checks NIP-65 first, then falls back to default)
 * @param {string} [pubkey] - User's pubkey (uses stored if not provided)
 * @returns {Promise<string[]>} Array of write relay URLs
 */
export async function getPreferredWriteRelays(pubkey) {
  const userPubkey = pubkey || loadPubkey()
  if (!userPubkey) return [getDefaultRelay()]

  try {
    const relayList = await fetchRelayListMetadata(userPubkey)
    if (relayList.write.length > 0) {
      return relayList.write
    }
  } catch (e) {
    console.error('Failed to fetch preferred relays:', e)
  }

  return [getDefaultRelay()]
}
