/**
 * POST /api/relay/reconnect
 * Disconnect then reconnect to all configured relays in the Rust engine.
 */

import { getOrCreateEngine } from '@/lib/rust-engine-manager'

export async function POST() {
  const engine = await getOrCreateEngine()
  if (!engine) {
    return Response.json({ error: 'Rust engine not available' }, { status: 503 })
  }

  try {
    await engine.reconnect()
    return Response.json({ ok: true, action: 'reconnected' })
  } catch (e) {
    console.error('[api/relay/reconnect] error:', e.message)
    return Response.json({ error: e.message }, { status: 500 })
  }
}
