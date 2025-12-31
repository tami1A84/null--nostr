/**
 * Web of Trust Library
 *
 * Coracleの実装に基づくWeb of Trustスコア計算
 * https://github.com/coracle-social/welshman/blob/master/packages/app/src/wot.ts
 *
 * WoTスコア = あなたがフォローしている人の中で対象をフォローしている人数
 *           - あなたがフォローしている人の中で対象をミュートしている人数
 *
 * @module wot
 */

import { fetchEventsManaged } from './connection-manager'

// Cache for follow lists and mute lists
const followListCache = new Map()
const muteListCache = new Map()
const CACHE_TTL = 5 * 60 * 1000 // 5 minutes

/**
 * Get follow list for a pubkey
 * @param {string} pubkey
 * @param {string[]} relays
 * @returns {Promise<string[]>} List of followed pubkeys
 */
export async function getFollows(pubkey, relays = ['wss://yabu.me', 'wss://relay-jp.nostr.wirednet.jp']) {
  const cached = followListCache.get(pubkey)
  if (cached && Date.now() - cached.timestamp < CACHE_TTL) {
    return cached.follows
  }

  try {
    const events = await fetchEventsManaged(
      { kinds: [3], authors: [pubkey], limit: 1 },
      relays,
      { timeout: 5000 }
    )

    if (events.length === 0) {
      followListCache.set(pubkey, { follows: [], timestamp: Date.now() })
      return []
    }

    // Get the most recent follow list
    const latestEvent = events.sort((a, b) => b.created_at - a.created_at)[0]
    const follows = latestEvent.tags
      .filter(tag => tag[0] === 'p')
      .map(tag => tag[1])

    followListCache.set(pubkey, { follows, timestamp: Date.now() })
    return follows
  } catch (e) {
    console.error('[WoT] Failed to fetch follows:', e)
    return []
  }
}

/**
 * Get mute list for a pubkey (NIP-51)
 * @param {string} pubkey
 * @param {string[]} relays
 * @returns {Promise<string[]>} List of muted pubkeys
 */
export async function getMutes(pubkey, relays = ['wss://yabu.me', 'wss://relay-jp.nostr.wirednet.jp']) {
  const cached = muteListCache.get(pubkey)
  if (cached && Date.now() - cached.timestamp < CACHE_TTL) {
    return cached.mutes
  }

  try {
    const events = await fetchEventsManaged(
      { kinds: [10000], authors: [pubkey], limit: 1 },
      relays,
      { timeout: 5000 }
    )

    if (events.length === 0) {
      muteListCache.set(pubkey, { mutes: [], timestamp: Date.now() })
      return []
    }

    const latestEvent = events.sort((a, b) => b.created_at - a.created_at)[0]
    const mutes = latestEvent.tags
      .filter(tag => tag[0] === 'p')
      .map(tag => tag[1])

    muteListCache.set(pubkey, { mutes, timestamp: Date.now() })
    return mutes
  } catch (e) {
    console.error('[WoT] Failed to fetch mutes:', e)
    return []
  }
}

/**
 * Get people you follow who also follow the target
 * @param {string} viewerPubkey - Your pubkey
 * @param {string} targetPubkey - Target user's pubkey
 * @param {string[]} relays
 * @returns {Promise<string[]>} List of pubkeys
 */
export async function getFollowsWhoFollow(viewerPubkey, targetPubkey, relays) {
  const myFollows = await getFollows(viewerPubkey, relays)

  // Fetch follow lists for all my follows in parallel (limited batch)
  const batchSize = 10
  const results = []

  for (let i = 0; i < myFollows.length; i += batchSize) {
    const batch = myFollows.slice(i, i + batchSize)
    const followLists = await Promise.all(
      batch.map(pk => getFollows(pk, relays))
    )

    batch.forEach((pk, idx) => {
      if (followLists[idx].includes(targetPubkey)) {
        results.push(pk)
      }
    })
  }

  return results
}

/**
 * Get people you follow who mute the target
 * @param {string} viewerPubkey - Your pubkey
 * @param {string} targetPubkey - Target user's pubkey
 * @param {string[]} relays
 * @returns {Promise<string[]>} List of pubkeys
 */
export async function getFollowsWhoMute(viewerPubkey, targetPubkey, relays) {
  const myFollows = await getFollows(viewerPubkey, relays)

  const batchSize = 10
  const results = []

  for (let i = 0; i < myFollows.length; i += batchSize) {
    const batch = myFollows.slice(i, i + batchSize)
    const muteLists = await Promise.all(
      batch.map(pk => getMutes(pk, relays))
    )

    batch.forEach((pk, idx) => {
      if (muteLists[idx].includes(targetPubkey)) {
        results.push(pk)
      }
    })
  }

  return results
}

/**
 * Calculate Web of Trust score for a target user
 * Score = (follows who follow) - (follows who mute)
 *
 * @param {string} viewerPubkey - Your pubkey
 * @param {string} targetPubkey - Target user's pubkey
 * @param {string[]} relays
 * @returns {Promise<{score: number, followsWhoFollow: number, followsWhoMute: number}>}
 */
export async function getWotScore(viewerPubkey, targetPubkey, relays = ['wss://yabu.me']) {
  if (viewerPubkey === targetPubkey) {
    return { score: Infinity, followsWhoFollow: 0, followsWhoMute: 0 }
  }

  const [followsWhoFollow, followsWhoMute] = await Promise.all([
    getFollowsWhoFollow(viewerPubkey, targetPubkey, relays),
    getFollowsWhoMute(viewerPubkey, targetPubkey, relays)
  ])

  return {
    score: followsWhoFollow.length - followsWhoMute.length,
    followsWhoFollow: followsWhoFollow.length,
    followsWhoMute: followsWhoMute.length
  }
}

/**
 * Calculate WoT scores for multiple users at once
 * @param {string} viewerPubkey
 * @param {string[]} targetPubkeys
 * @param {string[]} relays
 * @returns {Promise<Map<string, {score: number, followsWhoFollow: number, followsWhoMute: number}>>}
 */
export async function getWotScoresBatch(viewerPubkey, targetPubkeys, relays = ['wss://yabu.me']) {
  const myFollows = await getFollows(viewerPubkey, relays)

  // Build a map of who follows/mutes whom
  const followsMap = new Map()
  const mutesMap = new Map()

  // Fetch all follow/mute lists for my follows
  const batchSize = 10
  for (let i = 0; i < myFollows.length; i += batchSize) {
    const batch = myFollows.slice(i, i + batchSize)
    const [followLists, muteLists] = await Promise.all([
      Promise.all(batch.map(pk => getFollows(pk, relays))),
      Promise.all(batch.map(pk => getMutes(pk, relays)))
    ])

    batch.forEach((pk, idx) => {
      followsMap.set(pk, new Set(followLists[idx]))
      mutesMap.set(pk, new Set(muteLists[idx]))
    })
  }

  // Calculate scores for each target
  const results = new Map()
  for (const target of targetPubkeys) {
    if (target === viewerPubkey) {
      results.set(target, { score: Infinity, followsWhoFollow: 0, followsWhoMute: 0 })
      continue
    }

    let followsWhoFollow = 0
    let followsWhoMute = 0

    for (const follow of myFollows) {
      if (followsMap.get(follow)?.has(target)) {
        followsWhoFollow++
      }
      if (mutesMap.get(follow)?.has(target)) {
        followsWhoMute++
      }
    }

    results.set(target, {
      score: followsWhoFollow - followsWhoMute,
      followsWhoFollow,
      followsWhoMute
    })
  }

  return results
}

/**
 * Calculate vote weight based on WoT score
 * Higher WoT = higher weight (max 1.0)
 * Negative WoT = lower weight (min 0.1)
 *
 * @param {number} wotScore
 * @param {number} maxWot - Maximum expected WoT score for normalization
 * @returns {number} Weight between 0.1 and 1.0
 */
export function getVoteWeightFromWot(wotScore, maxWot = 10) {
  if (wotScore === Infinity) return 1.0
  if (wotScore <= 0) return 0.1

  // Normalize to 0-1 range
  const normalized = Math.min(wotScore / maxWot, 1.0)
  // Map to 0.1-1.0 range
  return 0.1 + normalized * 0.9
}

/**
 * Check if a user passes the minimum WoT threshold
 * @param {number} wotScore
 * @param {number} minWot - Minimum required WoT score
 * @returns {boolean}
 */
export function passesWotThreshold(wotScore, minWot = 0) {
  return wotScore >= minWot
}

/**
 * Clear all caches
 */
export function clearWotCache() {
  followListCache.clear()
  muteListCache.clear()
}
