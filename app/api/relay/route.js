/**
 * Relay Management API (Step 4: リレー接続移行)
 *
 * Exposes Rust relay connection management to the browser.
 * The browser-side WebSocket connections stay in JS (connection-manager.js),
 * but relay health / list management is now driven by the Rust engine.
 *
 * GET  /api/relay           — current relay list + connection stats
 * POST /api/relay           — add a relay URL  { url: string }
 * DELETE /api/relay         — remove a relay URL  { url: string }
 * POST /api/relay/reconnect — disconnect then reconnect all relays
 */

import { getOrCreateEngine } from '@/lib/rust-engine-manager'

// ──────────────────────────────────────────────
// GET /api/relay
// Returns the full relay list with per-relay status and aggregate stats.
// ──────────────────────────────────────────────
export async function GET() {
  const engine = await getOrCreateEngine()

  if (!engine) {
    return Response.json(
      {
        relays: [],
        stats: { connectedRelays: 0, totalRelays: 0 },
        source: 'fallback',
      },
      { status: 200 }
    )
  }

  try {
    const [relays, stats] = await Promise.all([
      engine.getRelayList(),
      engine.connectionStats(),
    ])

    return Response.json({
      relays,
      stats: {
        connectedRelays: stats.connectedRelays,
        totalRelays: stats.totalRelays,
      },
      source: 'rust',
    })
  } catch (e) {
    console.error('[api/relay] GET error:', e.message)
    return Response.json({ error: e.message }, { status: 500 })
  }
}

// ──────────────────────────────────────────────
// POST /api/relay
// Body: { url: string }  — adds a relay and connects.
// Body: { action: 'reconnect' }  — alias for reconnect (convenience).
// ──────────────────────────────────────────────
export async function POST(req) {
  let body
  try {
    body = await req.json()
  } catch {
    return Response.json({ error: 'Invalid JSON body' }, { status: 400 })
  }

  // Reconnect alias
  if (body.action === 'reconnect') {
    return handleReconnect()
  }

  const { url } = body
  if (!url || typeof url !== 'string') {
    return Response.json({ error: 'url is required' }, { status: 400 })
  }

  const engine = await getOrCreateEngine()
  if (!engine) {
    return Response.json({ error: 'Rust engine not available' }, { status: 503 })
  }

  try {
    await engine.addRelay(url)
    return Response.json({ ok: true, url, action: 'added' })
  } catch (e) {
    console.error('[api/relay] POST add error:', e.message)
    return Response.json({ error: e.message }, { status: 500 })
  }
}

// ──────────────────────────────────────────────
// DELETE /api/relay
// Body: { url: string }  — removes a relay.
// ──────────────────────────────────────────────
export async function DELETE(req) {
  let body
  try {
    body = await req.json()
  } catch {
    return Response.json({ error: 'Invalid JSON body' }, { status: 400 })
  }

  const { url } = body
  if (!url || typeof url !== 'string') {
    return Response.json({ error: 'url is required' }, { status: 400 })
  }

  const engine = await getOrCreateEngine()
  if (!engine) {
    return Response.json({ error: 'Rust engine not available' }, { status: 503 })
  }

  try {
    await engine.removeRelay(url)
    return Response.json({ ok: true, url, action: 'removed' })
  } catch (e) {
    console.error('[api/relay] DELETE error:', e.message)
    return Response.json({ error: e.message }, { status: 500 })
  }
}

// ──────────────────────────────────────────────
// Reconnect handler (used by POST with action:'reconnect' and /reconnect sub-route)
// ──────────────────────────────────────────────
async function handleReconnect() {
  const engine = await getOrCreateEngine()
  if (!engine) {
    return Response.json({ error: 'Rust engine not available' }, { status: 503 })
  }

  try {
    await engine.reconnect()
    return Response.json({ ok: true, action: 'reconnected' })
  } catch (e) {
    console.error('[api/relay] reconnect error:', e.message)
    return Response.json({ error: e.message }, { status: 500 })
  }
}
