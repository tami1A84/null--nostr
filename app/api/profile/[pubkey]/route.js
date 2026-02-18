/**
 * GET /api/profile/[pubkey]
 *
 * Fetches a Nostr profile (kind 0) using the Rust engine.
 *
 * Strategy:
 *   1. Query nostrdb local cache first (fast, no relay round-trip)
 *   2. If not found, fetch from relay via Rust engine (up to 10s timeout)
 *   3. If engine unavailable, return { profile: null, source: 'fallback' }
 *      so the client can fall back to the existing JS implementation.
 *
 * Response:
 *   { profile: UserProfile | null, source: 'nostrdb' | 'rust' | 'fallback' }
 *
 * UserProfile shape (matches lib/nostr.js parseProfile output):
 *   name, displayName, about, picture, banner, nip05, lud16, website, pubkey
 */

import { getOrCreateEngine } from '@/lib/rust-engine-manager'

export const dynamic = 'force-dynamic'

/**
 * Parse a kind-0 event JSON into the JS-compatible profile shape.
 * Mirrors lib/nostr.js parseProfile().
 */
function parseProfileFromEvent(eventJson) {
  try {
    const event = typeof eventJson === 'string' ? JSON.parse(eventJson) : eventJson
    if (event.kind !== 0) return null
    const content = JSON.parse(event.content)
    return {
      name: content.name || content.display_name || '',
      displayName: content.display_name || content.name || '',
      about: content.about || '',
      picture: content.picture || '',
      banner: content.banner || '',
      nip05: content.nip05 || '',
      lud16: content.lud16 || '',
      website: content.website || '',
      pubkey: event.pubkey,
    }
  } catch {
    return null
  }
}

/**
 * Convert a NapiUserProfile (from engine.fetchProfile) to the JS profile shape.
 * napi-rs auto-converts display_name → displayName, so we access camelCase here.
 */
function convertNapiProfile(napiProfile) {
  if (!napiProfile) return null
  return {
    name: napiProfile.name || napiProfile.displayName || '',
    displayName: napiProfile.displayName || napiProfile.name || '',
    about: napiProfile.about || '',
    picture: napiProfile.picture || '',
    banner: napiProfile.banner || '',
    nip05: napiProfile.nip05 || '',
    lud16: napiProfile.lud16 || '',
    website: napiProfile.website || '',
    pubkey: napiProfile.pubkey || '',
  }
}

export async function GET(req, { params }) {
  const { pubkey } = await params

  // Validate pubkey (64 hex chars)
  if (!pubkey || !/^[0-9a-f]{64}$/.test(pubkey)) {
    return Response.json({ error: 'Invalid pubkey' }, { status: 400 })
  }

  const engine = await getOrCreateEngine()

  if (!engine) {
    return Response.json({ profile: null, source: 'fallback' })
  }

  // ── Step 1: Query nostrdb local cache (instant, no relay) ──────
  try {
    const localFilter = JSON.stringify({ kinds: [0], authors: [pubkey], limit: 1 })
    const localEvents = await engine.queryLocal(localFilter)

    if (localEvents && localEvents.length > 0) {
      const profile = parseProfileFromEvent(localEvents[0])
      if (profile) {
        return Response.json({ profile, source: 'nostrdb' })
      }
    }
  } catch (err) {
    console.warn('[api/profile] queryLocal failed:', err.message)
  }

  // ── Step 2: Fetch from relay via Rust engine ───────────────────
  try {
    const napiProfile = await engine.fetchProfile(pubkey)
    const profile = convertNapiProfile(napiProfile)
    return Response.json({ profile, source: 'rust' })
  } catch (err) {
    console.warn('[api/profile] fetchProfile failed:', err.message)
    return Response.json({ profile: null, source: 'fallback' })
  }
}
