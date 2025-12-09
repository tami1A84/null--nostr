import { 
  generateSecretKey, 
  getPublicKey, 
  finalizeEvent, 
  nip19,
  nip44
} from 'nostr-tools'
import { SimplePool } from 'nostr-tools/pool'

// Default relay for fast timeline loading
export const DEFAULT_RELAY = 'wss://yabu.me'

// Default relays
const DEFAULT_READ_RELAYS = ['wss://yabu.me']
const DEFAULT_WRITE_RELAYS = [
  'wss://relay-jp.nostr.wirednet.jp',
  'wss://yabu.me',
  'wss://r.kojira.io'
]
const DEFAULT_ALL_RELAYS = [
  'wss://relay-jp.nostr.wirednet.jp',
  'wss://yabu.me',
  'wss://r.kojira.io'
]

// Search relay (NIP-50)
export const SEARCH_RELAY = 'wss://search.nos.today'

// Get relays from localStorage or use defaults
export function getReadRelays() {
  if (typeof window !== 'undefined') {
    const saved = localStorage.getItem('readRelays')
    if (saved) {
      try {
        return JSON.parse(saved)
      } catch (e) {}
    }
  }
  return DEFAULT_READ_RELAYS
}

export function getWriteRelays() {
  if (typeof window !== 'undefined') {
    const saved = localStorage.getItem('writeRelays')
    if (saved) {
      try {
        return JSON.parse(saved)
      } catch (e) {}
    }
  }
  return DEFAULT_WRITE_RELAYS
}

export function setReadRelays(relays) {
  if (typeof window !== 'undefined') {
    localStorage.setItem('readRelays', JSON.stringify(relays))
  }
}

export function setWriteRelays(relays) {
  if (typeof window !== 'undefined') {
    localStorage.setItem('writeRelays', JSON.stringify(relays))
  }
}

// Export for backwards compatibility
export const READ_RELAYS = DEFAULT_READ_RELAYS
export const WRITE_RELAYS = DEFAULT_WRITE_RELAYS
export const RELAYS = DEFAULT_ALL_RELAYS

// Pool singleton with error handling
let pool = null

export function getPool() {
  if (!pool) {
    pool = new SimplePool()
  }
  return pool
}

// Reset pool (useful for reconnection)
export function resetPool() {
  if (pool) {
    try {
      pool.close(RELAYS)
    } catch (e) {
      console.log('Pool close error:', e)
    }
  }
  pool = new SimplePool()
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
export async function publishEvent(event, relays = WRITE_RELAYS) {
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

// Delete event (NIP-09)
export async function deleteEvent(eventId, reason = '') {
  if (!hasNip07()) throw new Error('NIP-07拡張機能が必要です')
  
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
  if (!hasNip07()) throw new Error('NIP-07拡張機能が必要です')
  
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
  if (!hasNip07()) throw new Error('NIP-07拡張機能が必要です')
  
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

// NIP-05: Verify NIP-05 identifier
const nip05Cache = new Map()

export async function verifyNip05(nip05, pubkey) {
  if (!nip05 || !pubkey) return false
  
  // Check cache
  const cacheKey = `${nip05}:${pubkey}`
  if (nip05Cache.has(cacheKey)) {
    return nip05Cache.get(cacheKey)
  }
  
  try {
    const [name, domain] = nip05.split('@')
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
    console.log('NIP-05 verification failed:', e)
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
    console.log('Failed to parse nostr link:', e)
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
    if (events.length === 0) return { pubkeys: [], eventIds: [], hashtags: [], words: [] }
    
    const event = events[0]
    const pubkeys = event.tags.filter(t => t[0] === 'p').map(t => t[1])
    const eventIds = event.tags.filter(t => t[0] === 'e').map(t => t[1])
    const hashtags = event.tags.filter(t => t[0] === 't').map(t => t[1])
    const words = event.tags.filter(t => t[0] === 'word').map(t => t[1])
    
    return { pubkeys, eventIds, hashtags, words, rawEvent: event }
  } catch (e) {
    console.error('Failed to fetch mute list:', e)
    return { pubkeys: [], eventIds: [], hashtags: [], words: [] }
  }
}

// NIP-51: Add to mute list
export async function addToMuteList(myPubkey, type, value) {
  if (!hasNip07()) throw new Error('NIP-07拡張機能が必要です')
  
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
  if (!hasNip07()) throw new Error('NIP-07拡張機能が必要です')
  
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
    const response = await fetch('https://nostr.build/api/v2/upload/files', {
      method: 'POST',
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
export async function uploadToBlossom(file, blossomUrl = 'https://blossom.primal.net') {
  // Blossom uses PUT with file hash
  const arrayBuffer = await file.arrayBuffer()
  const hashBuffer = await crypto.subtle.digest('SHA-256', arrayBuffer)
  const hashArray = Array.from(new Uint8Array(hashBuffer))
  const hashHex = hashArray.map(b => b.toString(16).padStart(2, '0')).join('')
  
  try {
    // First, create auth event for Blossom (NIP-98)
    if (!hasNip07()) {
      throw new Error('NIP-07 extension required for Blossom upload')
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

// Generic upload function
export async function uploadImage(file) {
  const server = getUploadServer()
  
  if (server === 'nostr.build') {
    return uploadToNostrBuild(file)
  } else {
    // Assume it's a Blossom server URL
    return uploadToBlossom(file, server)
  }
}
