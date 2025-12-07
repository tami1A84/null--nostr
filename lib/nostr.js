import { 
  generateSecretKey, 
  getPublicKey, 
  finalizeEvent, 
  nip19,
  nip44
} from 'nostr-tools'
import { SimplePool } from 'nostr-tools/pool'

// Default relays
export const DEFAULT_RELAY = 'wss://yabu.me'
export const RELAYS = ['wss://yabu.me', 'wss://relay.damus.io', 'wss://nos.lol', 'wss://relay.nostr.band']

// Pool singleton
let pool = null

export function getPool() {
  if (!pool) {
    pool = new SimplePool()
  }
  return pool
}

// Check if NIP-07 extension is available
export function hasNip07() {
  return typeof window !== 'undefined' && window.nostr !== undefined
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

// Sign event via NIP-07
export async function signEventNip07(event) {
  if (!hasNip07()) {
    throw new Error('NIP-07拡張機能が見つかりません')
  }
  try {
    const signedEvent = await window.nostr.signEvent(event)
    return signedEvent
  } catch (e) {
    throw new Error('署名に失敗しました')
  }
}

// Encrypt message via NIP-07 (NIP-44)
export async function encryptNip44(pubkey, plaintext) {
  if (!hasNip07()) {
    throw new Error('NIP-07拡張機能が見つかりません')
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

// Decrypt message via NIP-07 (NIP-44)
export async function decryptNip44(pubkey, ciphertext) {
  if (!hasNip07()) {
    throw new Error('NIP-07拡張機能が見つかりません')
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
      pubkey: event.pubkey
    }
  } catch (e) {
    return null
  }
}

// Fetch events from relay
export async function fetchEvents(filter, relays = [DEFAULT_RELAY]) {
  const p = getPool()
  try {
    const events = await p.querySync(relays, filter)
    return events.sort((a, b) => b.created_at - a.created_at)
  } catch (e) {
    console.error('Failed to fetch events:', e)
    return []
  }
}

// Subscribe to events
export function subscribeToEvents(filter, relays, onEvent, onEose) {
  const p = getPool()
  const filters = Array.isArray(filter) ? filter : [filter]
  const sub = p.subscribeMany(relays, filters, {
    onevent: onEvent,
    oneose: onEose || (() => {})
  })
  return sub
}

// Publish event to relays
export async function publishEvent(event, relays = RELAYS) {
  const p = getPool()
  try {
    const promises = p.publish(relays, event)
    await Promise.any(promises)
    return true
  } catch (e) {
    console.error('Failed to publish event:', e)
    return false
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
  if (!hasNip07()) throw new Error('NIP-07拡張機能が必要です')
  
  try {
    const senderPubkey = await getNip07PublicKey()
    
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
