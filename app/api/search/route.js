/**
 * GET /api/search?q=xxx&limit=20
 *
 * Full-text search via NIP-50, proxied through the Rust engine's unified
 * relay pool (connects to wss://search.nos.today by default).
 *
 * Moving search to the server-side Rust engine avoids the need for the
 * browser to maintain a separate WebSocket connection to a search relay,
 * and allows nostrdb to cache search results for future queries.
 *
 * Strategy:
 *   1. Validate query param
 *   2. Call engine.search(query, limit) → NIP-50 relay fetch
 *   3. Store returned events into nostrdb via engine.storeEvent() (background)
 *   4. If engine unavailable, return { results: [], source: 'fallback' }
 *
 * Query params:
 *   q      — search query string (required)
 *   limit  — max results, default 50, max 100 (optional)
 *
 * Response:
 *   { results: NostrEvent[], source: 'rust' | 'fallback' }
 */

import { getOrCreateEngine } from '@/lib/rust-engine-manager'

export const dynamic = 'force-dynamic'

export async function GET(req) {
  const { searchParams } = new URL(req.url)
  const q = searchParams.get('q')
  const limitParam = searchParams.get('limit')

  if (!q || !q.trim()) {
    return Response.json({ error: 'Missing query parameter: q' }, { status: 400 })
  }

  const limit = Math.min(parseInt(limitParam) || 50, 100)
  const query = q.trim()

  const engine = await getOrCreateEngine()

  if (!engine) {
    return Response.json({ results: [], source: 'fallback' })
  }

  try {
    const eventJsons = await engine.search(query, limit)
    const results = eventJsons.map(j => JSON.parse(j))

    // Persist search results into nostrdb in the background so future
    // local queries can find them without hitting the relay again.
    if (results.length > 0) {
      Promise.allSettled(
        results.map(event => engine.storeEvent(JSON.stringify(event)))
      ).catch(() => {/* ignore background errors */})
    }

    return Response.json({ results, source: 'rust' })
  } catch (err) {
    console.warn('[api/search] search failed:', err.message)
    return Response.json({ results: [], source: 'fallback' })
  }
}
