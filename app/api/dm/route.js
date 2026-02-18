/**
 * GET /api/dm?pubkey=xxx&since=xxx&limit=50
 *
 * Fetches NIP-17 gift-wrapped DM events (kind 1059) for a user.
 *
 * Returns raw gift wrap events — decryption (seal + rumor layers)
 * is intentionally left to the browser, since the private key lives
 * in NIP-07 extensions / Amber / NIP-46 and must never reach the server.
 *
 * Strategy:
 *   1. Query nostrdb local cache (fast, no relay round-trip)
 *   2. If cache is empty, fetch from relay via Rust engine (loginUser first)
 *   3. If engine unavailable, return { events: [], source: 'fallback' }
 *
 * Query params:
 *   pubkey  — user's hex pubkey (required)
 *   since   — Unix timestamp; only return events after this (optional)
 *   limit   — max events to return, default 50, max 200 (optional)
 *
 * Response:
 *   { events: NostrEvent[], source: 'nostrdb' | 'rust' | 'fallback' }
 *
 * POST /api/dm
 *
 * Publish a signed gift-wrap event (kind 1059).
 * Delegates to /api/publish for signature validation and relay broadcast.
 *
 * Request body:
 *   { event: SignedNostrEvent }   — pre-signed kind 1059 gift wrap
 *
 * Response:
 *   { id: string, relays: string[], source: string }
 */

import { getOrCreateEngine, loginUser } from '@/lib/rust-engine-manager'

export const dynamic = 'force-dynamic'

export async function GET(req) {
  const { searchParams } = new URL(req.url)
  const pubkey = searchParams.get('pubkey')
  const sinceParam = searchParams.get('since')
  const limitParam = searchParams.get('limit')

  if (!pubkey || !/^[0-9a-f]{64}$/.test(pubkey)) {
    return Response.json({ error: 'Invalid pubkey' }, { status: 400 })
  }

  const limit = Math.min(parseInt(limitParam) || 50, 200)
  const since = sinceParam ? parseInt(sinceParam) : null

  const engine = await getOrCreateEngine()

  if (!engine) {
    return Response.json({ events: [], source: 'fallback' })
  }

  // ── Step 1: Query nostrdb local cache ─────────────────────────
  try {
    const localFilter = JSON.stringify({
      kinds: [1059],
      '#p': [pubkey],
      ...(since ? { since } : {}),
      limit,
    })
    const localJsons = await engine.queryLocal(localFilter)

    if (localJsons && localJsons.length > 0) {
      const events = localJsons.map(j => JSON.parse(j))
      return Response.json({ events, source: 'nostrdb' })
    }
  } catch (err) {
    console.warn('[api/dm] queryLocal failed:', err.message)
  }

  // ── Step 2: Fetch from relay via Rust engine ──────────────────
  // loginUser sets the pubkey context so fetch_dms knows which inbox to query
  try {
    await loginUser(pubkey)
    const eventJsons = await engine.fetchDms(since ? since * 1.0 : null, limit)
    const events = eventJsons.map(j => JSON.parse(j))
    return Response.json({ events, source: 'rust' })
  } catch (err) {
    console.warn('[api/dm] fetchDms failed:', err.message)
    return Response.json({ events: [], source: 'fallback' })
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

  // Gift wrap events are kind 1059
  if (event.kind !== 1059) {
    return Response.json({ error: 'Event must be kind 1059 (gift wrap)' }, { status: 400 })
  }

  if (!event.id || !event.pubkey || !event.sig) {
    return Response.json({ error: 'Missing required fields: id, pubkey, sig' }, { status: 400 })
  }

  // Delegate to /api/publish for signature validation and relay broadcast
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
    console.error('[api/dm] publish delegation failed:', err.message)
    return Response.json({ error: 'Failed to publish event' }, { status: 500 })
  }
}
