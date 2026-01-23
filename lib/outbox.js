/**
 * Outbox Model Implementation (NIP-65)
 *
 * The Outbox Model (formerly Gossip Model) is a relay selection strategy that:
 * - Fetches users' posts from their WRITE relays (outbox)
 * - Sends mentions to users' READ relays (inbox)
 * - Uses kind 10002 relay list metadata to discover relay preferences
 *
 * Reference: https://mikedilger.com/gossip-model/
 */

import { fetchEventsManaged, batchFetchManaged } from './connection-manager'
import { isValidRelayUrl, filterValidRelays, getDefaultRelay } from './nostr'
import { NOSTR_KINDS, CACHE_CONFIG } from './constants'

// Dedicated relays for fetching kind 10002 relay lists
// These are directory relays that store relay metadata
export const RELAY_LIST_DISCOVERY_RELAYS = [
  'wss://directory.yabu.me',
  'wss://purplepag.es'
]

// Cache for relay lists
const relayListCache = new Map()
const RELAY_LIST_CACHE_TTL = CACHE_CONFIG.durations.relayInfo // 1 hour

/**
 * Get cached relay list for a pubkey
 * @param {string} pubkey
 * @returns {Object|null}
 */
function getCachedRelayList(pubkey) {
  const cached = relayListCache.get(pubkey)
  if (!cached) return null
  if (Date.now() - cached.timestamp > RELAY_LIST_CACHE_TTL) {
    relayListCache.delete(pubkey)
    return null
  }
  return cached.data
}

/**
 * Set cached relay list for a pubkey
 * @param {string} pubkey
 * @param {Object} data
 */
function setCachedRelayList(pubkey, data) {
  relayListCache.set(pubkey, {
    data,
    timestamp: Date.now()
  })
}

/**
 * Parse relay list from a kind 10002 event
 * @param {Object} event - NIP-65 relay list event
 * @returns {{read: string[], write: string[], all: Array<{url: string, read: boolean, write: boolean}>}}
 */
function parseRelayListEvent(event) {
  const result = { read: [], write: [], all: [] }
  if (!event || !event.tags) return result

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
}

/**
 * Fetch relay list for a single user from discovery relays
 * @param {string} pubkey - User's public key
 * @returns {Promise<{read: string[], write: string[], all: Array}>}
 */
export async function fetchUserRelayList(pubkey) {
  // Check cache first
  const cached = getCachedRelayList(pubkey)
  if (cached) return cached

  try {
    const events = await fetchEventsManaged(
      { kinds: [NOSTR_KINDS.RELAY_LIST], authors: [pubkey], limit: 1 },
      RELAY_LIST_DISCOVERY_RELAYS,
      { timeout: 8000 }
    )

    if (events.length === 0) {
      // Return empty result but cache it to avoid repeated queries
      const empty = { read: [], write: [], all: [] }
      setCachedRelayList(pubkey, empty)
      return empty
    }

    // Get most recent event
    const event = events.sort((a, b) => b.created_at - a.created_at)[0]
    const result = parseRelayListEvent(event)

    setCachedRelayList(pubkey, result)
    return result
  } catch (e) {
    console.error('Failed to fetch relay list for', pubkey, e)
    return { read: [], write: [], all: [] }
  }
}

/**
 * Batch fetch relay lists for multiple users
 * @param {string[]} pubkeys - Array of public keys
 * @returns {Promise<Map<string, {read: string[], write: string[], all: Array}>>}
 */
export async function fetchRelayListsBatch(pubkeys) {
  const result = new Map()
  const uncached = []

  // Check cache first
  for (const pubkey of pubkeys) {
    const cached = getCachedRelayList(pubkey)
    if (cached) {
      result.set(pubkey, cached)
    } else {
      uncached.push(pubkey)
    }
  }

  if (uncached.length === 0) return result

  try {
    // Batch fetch in chunks of 50 to avoid overwhelming relays
    const chunkSize = 50
    for (let i = 0; i < uncached.length; i += chunkSize) {
      const chunk = uncached.slice(i, i + chunkSize)

      const events = await fetchEventsManaged(
        { kinds: [NOSTR_KINDS.RELAY_LIST], authors: chunk },
        RELAY_LIST_DISCOVERY_RELAYS,
        { timeout: 10000 }
      )

      // Group events by author and get most recent
      const eventsByAuthor = new Map()
      for (const event of events) {
        const existing = eventsByAuthor.get(event.pubkey)
        if (!existing || event.created_at > existing.created_at) {
          eventsByAuthor.set(event.pubkey, event)
        }
      }

      // Parse and cache results
      for (const [pubkey, event] of eventsByAuthor) {
        const parsed = parseRelayListEvent(event)
        setCachedRelayList(pubkey, parsed)
        result.set(pubkey, parsed)
      }

      // Cache empty results for pubkeys with no relay list
      for (const pubkey of chunk) {
        if (!result.has(pubkey)) {
          const empty = { read: [], write: [], all: [] }
          setCachedRelayList(pubkey, empty)
          result.set(pubkey, empty)
        }
      }
    }
  } catch (e) {
    console.error('Failed to batch fetch relay lists:', e)
  }

  return result
}

/**
 * Get write relays (outbox) for a user
 * These are where the user publishes their posts
 * @param {string} pubkey
 * @returns {Promise<string[]>}
 */
export async function getUserOutboxRelays(pubkey) {
  const relayList = await fetchUserRelayList(pubkey)
  return relayList.write.length > 0 ? relayList.write : [getDefaultRelay()]
}

/**
 * Get read relays (inbox) for a user
 * These are where the user receives mentions
 * @param {string} pubkey
 * @returns {Promise<string[]>}
 */
export async function getUserInboxRelays(pubkey) {
  const relayList = await fetchUserRelayList(pubkey)
  return relayList.read.length > 0 ? relayList.read : [getDefaultRelay()]
}

/**
 * Calculate optimal relays for fetching posts from multiple users
 * Groups users by their outbox relays to minimize connections
 * @param {string[]} pubkeys - Array of public keys
 * @returns {Promise<Map<string, string[]>>} Map of relay URL to array of pubkeys
 */
export async function getOptimalFetchRelays(pubkeys) {
  const relayLists = await fetchRelayListsBatch(pubkeys)
  const relayToPubkeys = new Map()

  for (const pubkey of pubkeys) {
    const relayList = relayLists.get(pubkey)
    let writeRelays = relayList?.write || []

    // Fallback to default relay if no write relays
    if (writeRelays.length === 0) {
      writeRelays = [getDefaultRelay()]
    }

    for (const relay of writeRelays) {
      if (!relayToPubkeys.has(relay)) {
        relayToPubkeys.set(relay, [])
      }
      relayToPubkeys.get(relay).push(pubkey)
    }
  }

  return relayToPubkeys
}

/**
 * Fetch events from users using their outbox relays
 * This is the core of the Outbox Model
 * @param {Object} filter - Base filter (kinds, since, limit etc.)
 * @param {string[]} authorPubkeys - Authors to fetch from
 * @param {Object} options - Fetch options
 * @returns {Promise<Array>} Events from all authors
 */
export async function fetchEventsWithOutboxModel(filter, authorPubkeys, options = {}) {
  const { timeout = 15000, maxRelaysPerAuthor = 3 } = options

  // Get optimal relay groupings
  const relayToPubkeys = await getOptimalFetchRelays(authorPubkeys)

  // Limit total relays to query
  const maxTotalRelays = 10
  const sortedRelays = Array.from(relayToPubkeys.entries())
    .sort((a, b) => b[1].length - a[1].length) // Prefer relays with more users
    .slice(0, maxTotalRelays)

  // Fetch from each relay group in parallel
  const fetchPromises = sortedRelays.map(async ([relay, pubkeys]) => {
    try {
      const relayFilter = {
        ...filter,
        authors: pubkeys.slice(0, 100) // Limit authors per query
      }

      const events = await fetchEventsManaged(
        relayFilter,
        [relay],
        { timeout }
      )
      return events
    } catch (e) {
      console.error(`Failed to fetch from ${relay}:`, e)
      return []
    }
  })

  const results = await Promise.all(fetchPromises)

  // Deduplicate events by ID
  const eventMap = new Map()
  for (const events of results) {
    for (const event of events) {
      if (!eventMap.has(event.id)) {
        eventMap.set(event.id, event)
      }
    }
  }

  return Array.from(eventMap.values())
}

/**
 * Get relays for publishing an event that mentions specific users
 * Uses the inbox relays of mentioned users
 * @param {string[]} mentionedPubkeys - Pubkeys mentioned in the event
 * @param {string[]} ownWriteRelays - Publisher's own write relays
 * @returns {Promise<string[]>} Combined list of relays to publish to
 */
export async function getPublishRelaysForMentions(mentionedPubkeys, ownWriteRelays = []) {
  const relays = new Set(ownWriteRelays)

  // Add inbox relays of mentioned users
  const relayLists = await fetchRelayListsBatch(mentionedPubkeys)
  for (const [pubkey, relayList] of relayLists) {
    // Add first 2 read relays from each mentioned user
    const inboxRelays = relayList.read.slice(0, 2)
    inboxRelays.forEach(r => relays.add(r))
  }

  return filterValidRelays(Array.from(relays))
}

/**
 * Clear the relay list cache
 */
export function clearRelayListCache() {
  relayListCache.clear()
}

/**
 * Get cache statistics for debugging
 */
export function getRelayListCacheStats() {
  return {
    size: relayListCache.size,
    entries: Array.from(relayListCache.keys())
  }
}
