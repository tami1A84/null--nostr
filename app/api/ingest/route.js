/**
 * POST /api/ingest
 *
 * Receives Nostr events from the browser and stores them for the
 * Rust engine to use in feed ranking.
 *
 * The browser maintains WebSocket connections to relays and receives
 * events in real-time. This endpoint lets the browser push those events
 * to the server-side nostrdb for the recommendation engine.
 *
 * Body (JSON):
 *   events - Array of Nostr event objects (NIP-01 format)
 *
 * Currently the engine receives events from relays directly.
 * This endpoint validates and acknowledges events; full nostrdb
 * storage will be added when a store_event napi method is available.
 */

import { getOrCreateEngine } from '@/lib/rust-engine-manager'

export const dynamic = 'force-dynamic'

// Server-side event buffer for events not yet in nostrdb.
// Kept small (LRU-style) to avoid memory issues.
const EVENT_BUFFER_MAX = 500
const eventBuffer = new Map()

/**
 * Validate a Nostr event has required fields (NIP-01).
 */
function isValidEvent(event) {
  return (
    event &&
    typeof event.id === 'string' && /^[0-9a-f]{64}$/.test(event.id) &&
    typeof event.pubkey === 'string' && /^[0-9a-f]{64}$/.test(event.pubkey) &&
    typeof event.created_at === 'number' &&
    typeof event.kind === 'number' &&
    Array.isArray(event.tags) &&
    typeof event.content === 'string' &&
    typeof event.sig === 'string'
  )
}

export async function POST(req) {
  let body
  try {
    body = await req.json()
  } catch {
    return Response.json({ error: 'Invalid JSON body' }, { status: 400 })
  }

  const { events } = body

  if (!Array.isArray(events)) {
    return Response.json(
      { error: 'events must be an array of Nostr events' },
      { status: 400 }
    )
  }

  if (events.length > 100) {
    return Response.json(
      { error: 'Maximum 100 events per request' },
      { status: 400 }
    )
  }

  // Validate and buffer events
  let accepted = 0
  let duplicate = 0
  let invalid = 0

  for (const event of events) {
    if (!isValidEvent(event)) {
      invalid++
      continue
    }

    if (eventBuffer.has(event.id)) {
      duplicate++
      continue
    }

    // Add to buffer (evict oldest if full)
    if (eventBuffer.size >= EVENT_BUFFER_MAX) {
      const oldestKey = eventBuffer.keys().next().value
      eventBuffer.delete(oldestKey)
    }
    eventBuffer.set(event.id, event)
    accepted++
  }

  // If engine is available, try to use queryLocal to verify storage
  // (future: direct nostrdb write when napi method is available)
  const engine = await getOrCreateEngine()
  const engineAvailable = !!engine

  return Response.json({
    accepted,
    duplicate,
    invalid,
    total: events.length,
    buffered: eventBuffer.size,
    engineAvailable,
  })
}

/**
 * GET /api/ingest
 *
 * Returns buffer stats (for debugging).
 */
export async function GET() {
  const engine = await getOrCreateEngine()

  return Response.json({
    buffered: eventBuffer.size,
    maxBuffer: EVENT_BUFFER_MAX,
    engineAvailable: !!engine,
  })
}
