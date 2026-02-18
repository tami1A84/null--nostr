/**
 * GET /api/social/mutes?pubkey=xxx
 *
 * Fetches a Nostr mute list (kind 10000, NIP-51) using the Rust engine.
 *
 * Strategy:
 *   1. Query nostrdb local cache first (fast, full tag structure)
 *   2. If not found, fetch from relay via Rust engine (pubkeys only)
 *   3. If engine unavailable, return { mutes: empty, source: 'fallback' }
 *
 * Response:
 *   {
 *     mutes: { pubkeys: string[], eventIds: string[], hashtags: string[], words: string[] },
 *     source: 'nostrdb' | 'rust' | 'fallback'
 *   }
 *
 * POST /api/social/mutes
 *
 * Updates the mute list by publishing a signed kind 10000 event.
 * The browser is responsible for signing the event (NIP-07 / Amber / NIP-46).
 * This endpoint delegates publishing to /api/publish.
 *
 * Request body:
 *   { event: SignedNostrEvent }   — pre-signed kind 10000 event
 *
 * Response:
 *   { id: string, relays: string[], source: string }
 */

import { getOrCreateEngine } from '@/lib/rust-engine-manager'

export const dynamic = 'force-dynamic'

const EMPTY_MUTES = { pubkeys: [], eventIds: [], hashtags: [], words: [] }

/**
 * Parse a kind-10000 mute list event into the full mute structure.
 * Mirrors the JS fetchMuteList() logic, supporting all tag types:
 *   p  → muted pubkeys
 *   e  → muted event IDs
 *   t  → muted hashtags
 *   word → muted words
 */
function parseMutesFromEvent(eventJson) {
  try {
    const event = typeof eventJson === 'string' ? JSON.parse(eventJson) : eventJson
    if (event.kind !== 10000) return null

    const pubkeys = event.tags.filter(t => t[0] === 'p' && t[1]).map(t => t[1])
    const eventIds = event.tags.filter(t => t[0] === 'e' && t[1]).map(t => t[1])
    const hashtags = event.tags.filter(t => t[0] === 't' && t[1]).map(t => t[1])
    const words = event.tags.filter(t => t[0] === 'word' && t[1]).map(t => t[1])

    return { pubkeys, eventIds, hashtags, words }
  } catch {
    return null
  }
}

export async function GET(req) {
  const pubkey = new URL(req.url).searchParams.get('pubkey')

  if (!pubkey || !/^[0-9a-f]{64}$/.test(pubkey)) {
    return Response.json({ error: 'Invalid pubkey' }, { status: 400 })
  }

  const engine = await getOrCreateEngine()

  if (!engine) {
    return Response.json({ mutes: EMPTY_MUTES, source: 'fallback' })
  }

  // ── Step 1: Query nostrdb local cache (instant, full tag structure) ──
  try {
    const localFilter = JSON.stringify({ kinds: [10000], authors: [pubkey], limit: 1 })
    const localEvents = await engine.queryLocal(localFilter)

    if (localEvents && localEvents.length > 0) {
      const mutes = parseMutesFromEvent(localEvents[0])
      if (mutes !== null) {
        return Response.json({ mutes, source: 'nostrdb' })
      }
    }
  } catch (err) {
    console.warn('[api/social/mutes] queryLocal failed:', err.message)
  }

  // ── Step 2: Fetch from relay via Rust engine (pubkeys only) ───────────
  // Note: The Rust fetch_mute_list only extracts p-tags (muted pubkeys).
  // hashtags, eventIds, and words are only available when the full event
  // is cached in nostrdb (Step 1). The relay fetch populates nostrdb so
  // future requests will return the full structure via Step 1.
  try {
    const mutedPubkeys = await engine.fetchMuteList(pubkey)
    return Response.json({
      mutes: { pubkeys: mutedPubkeys, eventIds: [], hashtags: [], words: [] },
      source: 'rust',
    })
  } catch (err) {
    console.warn('[api/social/mutes] fetchMuteList failed:', err.message)
    return Response.json({ mutes: EMPTY_MUTES, source: 'fallback' })
  }
}

export async function POST(req) {
  let body
  try {
    body = await req.json()
  } catch {
    return Response.json({ error: 'Invalid JSON' }, { status: 400 })
  }

  const { event } = body

  if (!event) {
    return Response.json({ error: 'Missing event' }, { status: 400 })
  }

  // Validate this is a kind 10000 (mute list) event
  if (event.kind !== 10000) {
    return Response.json({ error: 'Event must be kind 10000 (mute list)' }, { status: 400 })
  }

  // Validate required NIP-01 fields
  if (!event.id || !event.pubkey || !event.sig) {
    return Response.json({ error: 'Missing required fields: id, pubkey, sig' }, { status: 400 })
  }

  // Delegate to /api/publish for signing validation and relay broadcast
  try {
    const publishRes = await fetch(new URL('/api/publish', req.url).toString(), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ event }),
    })

    const result = await publishRes.json()

    if (!publishRes.ok) {
      return Response.json(result, { status: publishRes.status })
    }

    return Response.json(result)
  } catch (err) {
    console.error('[api/social/mutes] publish delegation failed:', err.message)
    return Response.json({ error: 'Failed to publish event' }, { status: 500 })
  }
}
