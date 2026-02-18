/**
 * POST /api/profile/batch
 *
 * Fetches multiple Nostr profiles (kind 0) in a single request.
 *
 * Strategy:
 *   1. Query nostrdb local cache for all pubkeys (fast, no relay round-trip)
 *   2. For any pubkeys not found locally, batch-fetch from relays via Rust
 *   3. If engine unavailable, return { profiles: {}, source: 'fallback' }
 *
 * Request body:
 *   { pubkeys: string[] }   — array of 64-char hex pubkeys (max 200)
 *
 * Response:
 *   { profiles: { [pubkey]: UserProfile }, source: 'nostrdb' | 'rust' | 'mixed' | 'fallback' }
 *
 * UserProfile shape (matches lib/nostr.js parseProfile output):
 *   name, displayName, about, picture, banner, nip05, lud16, website, pubkey
 */

import { getOrCreateEngine } from '@/lib/rust-engine-manager'

export const dynamic = 'force-dynamic'

const MAX_PUBKEYS = 200

/**
 * Parse a kind-0 event JSON into the JS-compatible profile shape.
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
 * Convert a UserProfile from fetchProfilesJson (snake_case JSON from Rust)
 * to the JS-compatible profile shape.
 */
function convertRustProfile(rustProfile, pubkey) {
  if (!rustProfile) return null
  return {
    name: rustProfile.name || rustProfile.display_name || '',
    displayName: rustProfile.display_name || rustProfile.name || '',
    about: rustProfile.about || '',
    picture: rustProfile.picture || '',
    banner: rustProfile.banner || '',
    nip05: rustProfile.nip05 || '',
    lud16: rustProfile.lud16 || '',
    website: rustProfile.website || '',
    pubkey: rustProfile.pubkey || pubkey,
  }
}

export async function POST(req) {
  let body
  try {
    body = await req.json()
  } catch {
    return Response.json({ error: 'Invalid JSON body' }, { status: 400 })
  }

  const { pubkeys } = body

  if (!Array.isArray(pubkeys)) {
    return Response.json({ error: 'pubkeys must be an array' }, { status: 400 })
  }

  if (pubkeys.length > MAX_PUBKEYS) {
    return Response.json(
      { error: `Maximum ${MAX_PUBKEYS} pubkeys per request` },
      { status: 400 }
    )
  }

  // Validate pubkeys
  const validPubkeys = pubkeys.filter(pk => typeof pk === 'string' && /^[0-9a-f]{64}$/.test(pk))

  if (validPubkeys.length === 0) {
    return Response.json({ profiles: {}, source: 'nostrdb' })
  }

  const engine = await getOrCreateEngine()

  if (!engine) {
    return Response.json({ profiles: {}, source: 'fallback' })
  }

  const profiles = {}

  // ── Step 1: Query nostrdb local cache for all pubkeys ──────────
  let foundLocally = new Set()
  try {
    const localFilter = JSON.stringify({ kinds: [0], authors: validPubkeys })
    const localEvents = await engine.queryLocal(localFilter)

    for (const eventJson of localEvents || []) {
      const profile = parseProfileFromEvent(eventJson)
      if (profile && profile.pubkey) {
        profiles[profile.pubkey] = profile
        foundLocally.add(profile.pubkey)
      }
    }
  } catch (err) {
    console.warn('[api/profile/batch] queryLocal failed:', err.message)
  }

  // ── Step 2: Batch-fetch missing profiles from relay ────────────
  const missing = validPubkeys.filter(pk => !foundLocally.has(pk))

  if (missing.length > 0) {
    try {
      const remoteJson = await engine.fetchProfilesJson(missing)
      const remoteProfiles = JSON.parse(remoteJson)

      for (const [pubkey, rustProfile] of Object.entries(remoteProfiles)) {
        const profile = convertRustProfile(rustProfile, pubkey)
        if (profile) {
          profiles[pubkey] = profile
        }
      }
    } catch (err) {
      console.warn('[api/profile/batch] fetchProfilesJson failed:', err.message)
    }
  }

  // Determine response source
  const foundCount = Object.keys(profiles).length
  const allLocal = foundCount > 0 && missing.length === 0
  const source = allLocal ? 'nostrdb' : (missing.length > 0 && foundLocally.size > 0 ? 'mixed' : 'rust')

  return Response.json({ profiles, source })
}
