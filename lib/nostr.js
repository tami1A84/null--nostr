import { 
  generateSecretKey, 
  getPublicKey, 
  finalizeEvent, 
  nip19,
  nip44
} from 'nostr-tools'
import { SimplePool } from 'nostr-tools/pool'
import { createRxNostr, createRxForwardReq, createRxBackwardReq } from 'rx-nostr'
import { verifier } from 'rx-nostr-crypto'
import {
  getCachedProfile,
  setCachedProfile,
  getCachedProfiles,
  getCachedMuteList,
  setCachedMuteList,
  getCachedFollowList,
  setCachedFollowList
} from './cache'

// Default relay for all operations
export const DEFAULT_RELAY = 'wss://yabu.me'

// Fallback relays when primary fails (Japanese relays + relay.damus.io only)
export const FALLBACK_RELAYS = [
  'wss://relay-jp.nostr.wirednet.jp',
  'wss://r.kojira.io',
  'wss://relay.damus.io'
]

// Search relay (NIP-50)
export const SEARCH_RELAY = 'wss://search.nos.today'

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

// Use rx-nostr mode (default: false - SimplePool is more reliable)
let useRxNostr = false

export function setUseRxNostr(enabled) {
  useRxNostr = enabled
}

export function isUsingRxNostr() {
  return useRxNostr
}

// Get single default relay from localStorage
export function getDefaultRelay() {
  if (typeof window !== 'undefined') {
    const saved = localStorage.getItem('defaultRelay')
    if (saved) return saved
  }
  return DEFAULT_RELAY
}

export function setDefaultRelay(relay) {
  if (typeof window !== 'undefined') {
    localStorage.setItem('defaultRelay', relay)
    // Update rx-nostr relays
    updateRxNostrRelays()
  }
}

// Get relays array (always single relay for simplicity)
export function getReadRelays() {
  return [getDefaultRelay()]
}

export function getWriteRelays() {
  return [getDefaultRelay()]
}

// Legacy setters - no-op now
export function setReadRelays(relays) {}
export function setWriteRelays(relays) {}

// Export for backwards compatibility
export const READ_RELAYS = [DEFAULT_RELAY]
export const WRITE_RELAYS = [DEFAULT_RELAY]
export const RELAYS = [DEFAULT_RELAY]

// SimplePool singleton with improved reliability
let pool = null
let poolLastUsed = Date.now()

export function getPool() {
  if (!pool) {
    pool = new SimplePool({
      // Enable heartbeat to detect dead connections
      enablePing: true,
      // Enable automatic reconnection with exponential backoff
      enableReconnect: true,
      // Connection timeouts
      eoseSubTimeout: 15000,
      getTimeout: 15000,
    })
  }
  poolLastUsed = Date.now()
  return pool
}

// Reset pool (useful for reconnection)
export function resetPool() {
  if (pool) {
    try {
      const relays = [getDefaultRelay()]
      pool.close(relays)
    } catch (e) {
      console.log('Pool close error:', e)
    }
  }
  pool = new SimplePool({
    enablePing: true,
    enableReconnect: true,
    eoseSubTimeout: 15000,
    getTimeout: 15000,
  })
  poolLastUsed = Date.now()
  return pool
}

// Auto-reset pool if inactive for too long (prevents stale connections)
if (typeof window !== 'undefined') {
  setInterval(() => {
    if (pool && Date.now() - poolLastUsed > 180000) { // 3 minutes
      console.log('Auto-resetting inactive pool')
      resetPool()
    }
  }, 60000)
}

// rx-nostr singleton
let rxNostr = null

export function getRxNostr() {
  if (!rxNostr && typeof window !== 'undefined') {
    rxNostr = createRxNostr({
      verifier,
      connectionStrategy: 'lazy',
      eoseTimeout: 5000,
      okTimeout: 5000,
    })
    // Set default relays
    rxNostr.setDefaultRelays([getDefaultRelay()])
  }
  return rxNostr
}

// Reset rx-nostr
export function resetRxNostr() {
  if (rxNostr) {
    try {
      rxNostr.dispose()
    } catch (e) {
      console.log('rx-nostr dispose error:', e)
    }
  }
  rxNostr = null
  return getRxNostr()
}

// Update relay for rx-nostr when default relay changes
export function updateRxNostrRelays() {
  if (rxNostr) {
    rxNostr.setDefaultRelays([getDefaultRelay()])
  }
}

// Check if NIP-07 extension is available
export function hasNip07() {
  return typeof window !== 'undefined' && window.nostr !== undefined
}

// Check if any signing method is available
export function canSign() {
  // Private key stored
  if (hasStoredPrivateKey()) return true
  // Nosskey manager available
  if (hasNosskey()) return true
  // NIP-07 extension
  if (hasNip07()) return true
  return false
}

// Get public key via NIP-07
export async function getNip07PublicKey() {
  if (!hasNip07()) {
    throw new Error('NIP-07拡張機能が見つかりません')
  }
  try {
    const pubkey = await window.nostr.getPublicKey()
    return pubkey
  } catch (e) {
    throw new Error('公開鍵の取得に失敗しました')
  }
}

// Get public key (works with any login method)
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

// Get login method
export function getLoginMethod() {
  if (typeof window !== 'undefined') {
    return localStorage.getItem('nurunuru_login_method') || 'nip07'
  }
  return 'nip07'
}

// Check if Nosskey is available
export function hasNosskey() {
  return typeof window !== 'undefined' && window.nosskeyManager !== undefined
}

// Check if we have private key stored (for auto-sign)
export function hasStoredPrivateKey() {
  return typeof window !== 'undefined' && window.nostrPrivateKey !== undefined
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

// Sign event via NIP-07, Nosskey, bunker or private key
export async function signEventNip07(event) {
  const loginMethod = getLoginMethod()
  
  // If we have a private key stored, always use it for signing (fast, no re-auth)
  if (hasStoredPrivateKey() && getAutoSignEnabled()) {
    try {
      const secretKey = hexToBytes(window.nostrPrivateKey)
      const signedEvent = finalizeEvent(event, secretKey)
      return signedEvent
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
      throw new Error('パスキーでの署名に失敗しました。ミニアプリ画面で秘密鍵をエクスポートしてください。')
    }
  }
  
  // Try Amber (NIP-55) signing for Android
  if (loginMethod === 'amber') {
    try {
      const signedEvent = await signEventAmber(event)
      return signedEvent
    } catch (e) {
      console.error('Amber signing failed:', e)
      throw new Error('Amberでの署名に失敗しました。再度お試しください。')
    }
  }
  
  // Try bunker signer if available
  if (loginMethod === 'bunker' && window.bunkerSigner) {
    try {
      const signedEvent = await window.bunkerSigner.signEvent(event)
      return signedEvent
    } catch (e) {
      console.error('Bunker signing failed:', e)
      throw new Error('nsec.appでの署名に失敗しました。再接続してください。')
    }
  }
  
  // Fallback to NIP-07 (this also works for bunker via window.nostr)
  if (!hasNip07()) {
    throw new Error('署名機能が利用できません')
  }
  try {
    const signedEvent = await window.nostr.signEvent(event)
    return signedEvent
  } catch (e) {
    throw new Error('署名に失敗しました')
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

// Handle Amber sign callback (call this from page component on mount)
export function handleAmberSignCallback() {
  if (typeof window === 'undefined') return null
  
  const urlParams = new URLSearchParams(window.location.search)
  const callbackParam = urlParams.get('amber_sign_callback')
  const signature = urlParams.get('signature')
  const eventParam = urlParams.get('event')
  
  if (callbackParam && (signature || eventParam)) {
    // Clear timeout
    if (window.amberSignTimeout) {
      clearTimeout(window.amberSignTimeout)
    }
    
    try {
      let signedEvent
      
      if (eventParam) {
        // Full signed event returned
        signedEvent = JSON.parse(decodeURIComponent(eventParam))
      } else if (signature) {
        // Just signature returned - need to reconstruct event
        const pendingRequestId = sessionStorage.getItem('amber_sign_pending')
        const originalEvent = JSON.parse(sessionStorage.getItem(`amber_sign_${pendingRequestId}`) || '{}')
        signedEvent = {
          ...originalEvent,
          sig: signature
        }
      }
      
      // Clean up
      const requestId = callbackParam
      sessionStorage.removeItem(`amber_sign_${requestId}`)
      sessionStorage.removeItem('amber_sign_pending')
      
      // Clean URL
      window.history.replaceState({}, '', window.location.pathname)
      
      // Resolve the promise
      if (window.amberSignResolve) {
        window.amberSignResolve(signedEvent)
        window.amberSignResolve = null
        window.amberSignReject = null
      }
      
      return signedEvent
    } catch (e) {
      console.error('Failed to parse Amber sign callback:', e)
      if (window.amberSignReject) {
        window.amberSignReject(new Error('Amberからの署名応答を処理できませんでした'))
        window.amberSignResolve = null
        window.amberSignReject = null
      }
    }
  }
  
  return null
}

// Encrypt message via NIP-07 (NIP-44) or private key
export async function encryptNip44(pubkey, plaintext) {
  // If we have a private key stored, use it directly
  if (hasStoredPrivateKey()) {
    try {
      const secretKey = hexToBytes(window.nostrPrivateKey)
      const conversationKey = nip44.v2.utils.getConversationKey(secretKey, pubkey)
      return nip44.v2.encrypt(plaintext, conversationKey)
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

// Decrypt message via NIP-07 (NIP-44) or private key
export async function decryptNip44(pubkey, ciphertext) {
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
      console.error('Failed to export key for decryption:', e)
      throw new Error('復号するにはパスキー認証が必要です')
    }
  }
  
  // If we have a private key stored, use it directly
  if (hasStoredPrivateKey()) {
    try {
      const secretKey = hexToBytes(window.nostrPrivateKey)
      const conversationKey = nip44.v2.utils.getConversationKey(secretKey, pubkey)
      return nip44.v2.decrypt(ciphertext, conversationKey)
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

// Request queue to limit concurrent connections
const requestQueue = {
  pending: 0,
  maxConcurrent: 3, // Limit concurrent requests per relay
  queue: [],
  
  async acquire() {
    if (this.pending < this.maxConcurrent) {
      this.pending++
      return
    }
    
    // Wait in queue
    await new Promise(resolve => {
      this.queue.push(resolve)
    })
    this.pending++
  },
  
  release() {
    this.pending--
    if (this.queue.length > 0) {
      const next = this.queue.shift()
      next()
    }
  }
}

// Fetch events from relay with throttling
export async function fetchEvents(filter, relays = [DEFAULT_RELAY]) {
  // Filter out invalid relay URLs (onion, ws://, etc)
  const validRelays = filterValidRelays(relays)
  
  // Use rx-nostr if enabled
  if (useRxNostr && typeof window !== 'undefined') {
    return rxFetchEvents(filter, validRelays)
  }
  
  // Use actual relay setting
  const primaryRelay = validRelays[0] === DEFAULT_RELAY ? getDefaultRelay() : validRelays[0]
  const allRelays = [primaryRelay, ...FALLBACK_RELAYS.filter(r => r !== primaryRelay)]
  
  // Throttle requests
  await requestQueue.acquire()
  
  try {
    // Fallback to SimplePool with exponential backoff retry
    const p = getPool()
    const maxRetries = 3
    let retries = 0
    let currentRelayIndex = 0
    
    while (retries <= maxRetries) {
      // Try current relay (with fallback rotation on retries)
      const currentRelays = [allRelays[currentRelayIndex % allRelays.length]]
      
      try {
        const events = await Promise.race([
          p.querySync(currentRelays, filter),
          new Promise((_, reject) => setTimeout(() => reject(new Error('Timeout')), 15000))
        ])
        return events.sort((a, b) => b.created_at - a.created_at)
      } catch (e) {
        retries++
        currentRelayIndex++
        console.error(`Fetch events failed on ${currentRelays[0]} (attempt ${retries}/${maxRetries + 1}):`, e.message)
        
        if (retries <= maxRetries) {
          // Short delay before trying next relay
          const delay = 300 + Math.random() * 200
          console.log(`Trying fallback relay ${allRelays[currentRelayIndex % allRelays.length]}...`)
          
          // Reset pool before retry
          resetPool()
          await new Promise(r => setTimeout(r, delay))
        }
      }
    }
    
    console.error('All fetch retries exhausted')
    return []
  } finally {
    requestQueue.release()
  }
}

// Fetch events using rx-nostr
async function rxFetchEvents(filter, relays) {
  return new Promise((resolve) => {
    const rx = getRxNostr()
    if (!rx) {
      resolve([])
      return
    }
    
    const events = []
    const req = createRxBackwardReq()
    
    const sub = rx.use(req).subscribe({
      next: (packet) => {
        if (packet.event) {
          events.push(packet.event)
        }
      },
      complete: () => {
        resolve(events.sort((a, b) => b.created_at - a.created_at))
      },
      error: (err) => {
        console.error('rx-nostr fetch error:', err)
        resolve(events.sort((a, b) => b.created_at - a.created_at))
      }
    })
    
    // Emit filter to specified relays
    req.emit(filter, { relays })
    
    // Timeout after 8 seconds
    setTimeout(() => {
      req.over()
    }, 8000)
  })
}

// Subscribe to events
export function subscribeToEvents(filter, relays, onEvent, onEose) {
  // Filter out invalid relay URLs
  const validRelays = filterValidRelays(relays)
  
  // Use rx-nostr if enabled
  if (useRxNostr && typeof window !== 'undefined') {
    return rxSubscribeToEvents(filter, validRelays, onEvent, onEose)
  }
  
  // Fallback to SimplePool
  const p = getPool()
  const filters = Array.isArray(filter) ? filter : [filter]
  const sub = p.subscribeMany(validRelays, filters, {
    onevent: onEvent,
    oneose: onEose || (() => {})
  })
  return sub
}

// Subscribe using rx-nostr
function rxSubscribeToEvents(filter, relays, onEvent, onEose) {
  const rx = getRxNostr()
  if (!rx) {
    return { close: () => {} }
  }
  
  const req = createRxForwardReq()
  let eoseReceived = false
  
  const sub = rx.use(req).subscribe({
    next: (packet) => {
      if (packet.event) {
        onEvent(packet.event)
      }
      // Handle EOSE
      if (packet.eose && !eoseReceived) {
        eoseReceived = true
        if (onEose) onEose()
      }
    },
    error: (err) => {
      console.error('rx-nostr subscription error:', err)
    }
  })
  
  // Emit filter
  const filters = Array.isArray(filter) ? filter : [filter]
  filters.forEach(f => req.emit(f, { relays }))
  
  return {
    close: () => {
      sub.unsubscribe()
    }
  }
}

// Publish event to relays
export async function publishEvent(event, relays = WRITE_RELAYS) {
  // Filter out invalid relay URLs
  const validRelays = filterValidRelays(relays)
  
  // Use rx-nostr if enabled
  if (useRxNostr && typeof window !== 'undefined') {
    return rxPublishEvent(event, validRelays)
  }
  
  // Fallback to SimplePool
  const p = getPool()
  try {
    const promises = p.publish(validRelays, event)
    await Promise.any(promises)
    return true
  } catch (e) {
    console.error('Failed to publish event:', e)
    return false
  }
}

// Publish using rx-nostr
async function rxPublishEvent(event, relays) {
  const rx = getRxNostr()
  if (!rx) {
    return false
  }
  
  return new Promise((resolve) => {
    let resolved = false
    
    const sub = rx.send(event, { relays }).subscribe({
      next: (packet) => {
        if (packet.ok && !resolved) {
          resolved = true
          resolve(true)
        }
      },
      complete: () => {
        if (!resolved) {
          resolved = true
          resolve(true)
        }
      },
      error: (err) => {
        console.error('rx-nostr publish error:', err)
        if (!resolved) {
          resolved = true
          resolve(false)
        }
      }
    })
    
    // Timeout after 10 seconds
    setTimeout(() => {
      if (!resolved) {
        resolved = true
        sub.unsubscribe()
        resolve(true) // Assume success on timeout
      }
    }, 10000)
  })
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
    const response = await fetch(url)
    if (!response.ok) return false
    
    const data = await response.json()
    const verified = data.names?.[name]?.toLowerCase() === pubkey.toLowerCase()
    
    // Cache result for 5 minutes
    nip05Cache.set(cacheKey, verified)
    setTimeout(() => nip05Cache.delete(cacheKey), 5 * 60 * 1000)
    
    return verified
  } catch (e) {
    // Silently ignore verification failures (CORS, network, etc)
    return false
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
