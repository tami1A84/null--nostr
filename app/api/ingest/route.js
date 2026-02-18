/**
 * POST /api/ingest
 *
 * Receives Nostr events from the browser and stores them directly into
 * nostrdb via the Rust engine, making them immediately available to the
 * recommendation engine.
 *
 * The browser maintains WebSocket connections to relays and receives
 * events in real-time. This endpoint lets the browser push those events
 * to the server-side nostrdb for the recommendation engine.
 *
 * Body (JSON):
 *   events - Array of Nostr event objects (NIP-01 format)
 */

import { getOrCreateEngine } from '@/lib/rust-engine-manager'

export const dynamic = 'force-dynamic'

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

  // Get the Rust engine (may be null if not yet initialised)
  const engine = await getOrCreateEngine()

  let accepted = 0
  let duplicate = 0
  let invalid = 0
  let stored = 0

  for (const event of events) {
    if (!isValidEvent(event)) {
      invalid++
      continue
    }

    // Store directly into nostrdb via Rust
    if (engine) {
      try {
        const eventJson = JSON.stringify(event)
        const isNew = await engine.storeEvent(eventJson)
        if (isNew) {
          stored++
        } else {
          duplicate++
        }
        accepted++
      } catch (err) {
        // Rust rejected the event (invalid sig, malformed, etc.)
        invalid++
      }
    } else {
      // Engine not available yet — count as accepted but not stored
      accepted++
    }
  }

  return Response.json({
    accepted,
    duplicate,
    invalid,
    stored,
    total: events.length,
    engineAvailable: !!engine,
  })
}

/**
 * GET /api/ingest
 *
 * Returns engine availability (for debugging).
 */
export async function GET() {
  const engine = await getOrCreateEngine()

  return Response.json({
    engineAvailable: !!engine,
  })
}
