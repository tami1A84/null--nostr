/**
 * GET /api/social/follows?pubkey=xxx
 *
 * Fetches a Nostr follow list (kind 3, NIP-02) using the Rust engine.
 *
 * Strategy:
 *   1. Query nostrdb local cache first (fast, no relay round-trip)
 *   2. If not found, fetch from relay via Rust engine
 *   3. If engine unavailable, return { follows: [], source: 'fallback' }
 *
 * Response:
 *   { follows: string[], source: 'nostrdb' | 'rust' | 'fallback' }
 *
 * POST /api/social/follows
 *
 * Updates the follow list by publishing a signed kind 3 event.
 * The browser is responsible for signing the event (NIP-07 / Amber / NIP-46).
 * This endpoint delegates publishing to /api/publish.
 *
 * Request body:
 *   { event: SignedNostrEvent }   — pre-signed kind 3 event
 *
 * Response:
 *   { id: string, relays: string[], source: string }
 */

import { getOrCreateEngine } from '@/lib/rust-engine-manager'

export const dynamic = 'force-dynamic'

/**
 * Parse a kind-3 contact list event into an array of followed pubkeys.
 * Mirrors the JS fetchFollowList() logic.
 */
function parseFollowsFromEvent(eventJson) {
  try {
    const event = typeof eventJson === 'string' ? JSON.parse(eventJson) : eventJson
    if (event.kind !== 3) return null
    return event.tags
      .filter(tag => tag[0] === 'p' && tag[1])
      .map(tag => tag[1])
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
    return Response.json({ follows: [], source: 'fallback' })
  }

  // ── Step 1: Query nostrdb local cache (instant, no relay) ──────
  try {
    const localFilter = JSON.stringify({ kinds: [3], authors: [pubkey], limit: 1 })
    const localEvents = await engine.queryLocal(localFilter)

    if (localEvents && localEvents.length > 0) {
      const follows = parseFollowsFromEvent(localEvents[0])
      if (follows !== null) {
        return Response.json({ follows, source: 'nostrdb' })
      }
    }
  } catch (err) {
    console.warn('[api/social/follows] queryLocal failed:', err.message)
  }

  // ── Step 2: Fetch from relay via Rust engine ───────────────────
  try {
    const follows = await engine.fetchFollowList(pubkey)
    return Response.json({ follows, source: 'rust' })
  } catch (err) {
    console.warn('[api/social/follows] fetchFollowList failed:', err.message)
    return Response.json({ follows: [], source: 'fallback' })
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

  // Validate this is a kind 3 (contact list) event
  if (event.kind !== 3) {
    return Response.json({ error: 'Event must be kind 3 (contact list)' }, { status: 400 })
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
    console.error('[api/social/follows] publish delegation failed:', err.message)
    return Response.json({ error: 'Failed to publish event' }, { status: 500 })
  }
}
