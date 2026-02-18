/**
 * GET /api/feed
 *
 * Returns a ranked feed using the Rust recommendation engine.
 * Falls back to an empty response if the engine is unavailable
 * (client uses its own JS algorithm as fallback).
 *
 * Query params:
 *   pubkey - User's public key hex (required for personalized feed)
 *   limit  - Maximum number of posts (default: 50, max: 200)
 */

import { getOrCreateEngine, loginUser } from '@/lib/rust-engine-manager'

export const dynamic = 'force-dynamic'

export async function GET(req) {
  const { searchParams } = new URL(req.url)
  const pubkey = searchParams.get('pubkey')
  const limit = Math.min(parseInt(searchParams.get('limit') || '50', 10) || 50, 200)

  if (!pubkey) {
    return Response.json(
      { error: 'pubkey parameter is required' },
      { status: 400 }
    )
  }

  // Validate pubkey format (64-char hex)
  if (!/^[0-9a-f]{64}$/.test(pubkey)) {
    return Response.json(
      { error: 'Invalid pubkey format' },
      { status: 400 }
    )
  }

  try {
    const engine = await loginUser(pubkey)
    if (!engine) {
      return Response.json({ posts: [], source: 'fallback', reason: 'engine_unavailable' })
    }

    // Step 1: Get scored posts from recommendation engine
    const scored = await engine.getRecommendedFeed(limit)

    if (!scored || scored.length === 0) {
      return Response.json({ posts: [], source: 'rust', reason: 'no_scored_posts' })
    }

    // Step 2: Fetch full events from nostrdb by their IDs
    const eventIds = scored.map(s => s.eventId)
    const filterJson = JSON.stringify({ ids: eventIds })
    let events = []

    try {
      const eventJsons = await engine.queryLocal(filterJson)
      events = eventJsons.map(j => JSON.parse(j))
    } catch (e) {
      console.warn('[api/feed] queryLocal failed, returning scores only:', e.message)
    }

    // Step 3: Build score lookup and merge with events
    const scoreMap = new Map()
    for (const s of scored) {
      scoreMap.set(s.eventId, { score: s.score, createdAt: s.createdAt })
    }

    if (events.length > 0) {
      // Full mode: events with scores
      const feed = events.map(e => ({
        ...e,
        _score: scoreMap.get(e.id)?.score || 0,
      }))

      // Sort by score (descending)
      feed.sort((a, b) => (b._score || 0) - (a._score || 0))

      return Response.json({ posts: feed, source: 'rust' })
    }

    // Scores-only mode: return scored post metadata (client can match with local events)
    return Response.json({
      scores: scored.map(s => ({
        eventId: s.eventId,
        pubkey: s.pubkey,
        score: s.score,
        createdAt: s.createdAt,
      })),
      source: 'rust_scores_only',
    })
  } catch (e) {
    console.error('[api/feed] Error:', e.message)
    return Response.json({ posts: [], source: 'fallback', error: e.message })
  }
}
