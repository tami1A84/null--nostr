/**
 * Recommendation Engine for Discovery Feed
 *
 * Implements a sophisticated "For You" style recommendation algorithm inspired by X's algorithm:
 * https://github.com/xai-org/x-algorithm
 *
 * Key concepts:
 * - In-Network (follows) vs Out-of-Network (discovery) mixing
 * - Multi-signal engagement scoring (likes, reposts, replies, zaps)
 * - Social graph analysis (2nd-degree connections for discovery)
 * - Negative signals (mutes, not interested, reports)
 * - Time decay with freshness boost
 * - Author quality signals (NIP-05, follower count)
 *
 * Feed mix target:
 * - 50% from 2nd-degree network (friends of friends - discovery focus)
 * - 30% from out-of-network with high engagement (viral content)
 * - 20% from 1st-degree (to ensure you see important follows)
 */

import { loadUserGeohash, geohashesMatch } from './geohash'
import { fetchEventsManaged } from './connection-manager'
import { fetchEventsWithOutboxModel, fetchRelayListsBatch } from './outbox'
import { NOSTR_KINDS } from './constants'

// Storage keys
const NOT_INTERESTED_KEY = 'nurunuru_not_interested'
const NOT_INTERESTED_AUTHORS_KEY = 'nurunuru_not_interested_authors'
const ENGAGEMENT_HISTORY_KEY = 'nurunuru_engagement_history'
const AUTHOR_SCORES_KEY = 'nurunuru_author_scores'

// Engagement weights (inspired by X algorithm)
const ENGAGEMENT_WEIGHTS = {
  zap: 100,           // Zaps are highest value - monetary commitment
  custom_reaction: 60, // Custom reactions show more intent than likes
  quote: 35,          // Quotes with commentary are valuable
  reply: 30,          // Replies show deep engagement
  repost: 25,         // Reposts indicate share-worthy content
  bookmark: 15,       // Bookmarks show personal value
  like: 5,            // Likes are easy, lower weight
}

// Negative signal weights
const NEGATIVE_WEIGHTS = {
  not_interested: -50,
  muted_author: -1000,  // Effectively filters out
  reported: -200,
}

// Social boost multipliers
const SOCIAL_BOOST = {
  second_degree: 3.0,     // Friends of friends - highest discovery value
  mutual_follow: 2.5,     // People who follow you back
  high_engagement_author: 2.0,  // Authors you often engage with
  first_degree: 0.5,      // Already in your follow feed
  unknown: 1.0,           // Neutral
}

// Time decay parameters
const TIME_DECAY = {
  halfLife: 6,            // Hours until score halves
  maxAge: 48,             // Hours after which posts score very low
  freshnessBoost: 1.5,    // Boost for posts under 1 hour old
}

/**
 * Get list of posts user marked as "not interested"
 * @returns {Set<string>} Set of event IDs
 */
export function getNotInterestedPosts() {
  if (typeof window === 'undefined') return new Set()
  try {
    const data = localStorage.getItem(NOT_INTERESTED_KEY)
    return data ? new Set(JSON.parse(data)) : new Set()
  } catch {
    return new Set()
  }
}

/**
 * Get author scores (positive for engaged, negative for not interested)
 * @returns {Map<string, number>} Map of pubkey to score modifier
 */
export function getAuthorScores() {
  if (typeof window === 'undefined') return new Map()
  try {
    const data = localStorage.getItem(NOT_INTERESTED_AUTHORS_KEY)
    return data ? new Map(Object.entries(JSON.parse(data))) : new Map()
  } catch {
    return new Map()
  }
}

/**
 * Get user's engagement history (for personalization)
 * @returns {Object} Engagement data
 */
export function getEngagementHistory() {
  if (typeof window === 'undefined') return { likedAuthors: {}, repostedAuthors: {}, repliedAuthors: {} }
  try {
    const data = localStorage.getItem(ENGAGEMENT_HISTORY_KEY)
    return data ? JSON.parse(data) : { likedAuthors: {}, repostedAuthors: {}, repliedAuthors: {} }
  } catch {
    return { likedAuthors: {}, repostedAuthors: {}, repliedAuthors: {} }
  }
}

/**
 * Record an engagement action for personalization
 * @param {string} type - 'like', 'repost', 'reply'
 * @param {string} authorPubkey - Author of the content engaged with
 */
export function recordEngagement(type, authorPubkey) {
  if (typeof window === 'undefined') return

  const history = getEngagementHistory()
  const key = `${type}dAuthors`

  if (!history[key]) history[key] = {}
  history[key][authorPubkey] = (history[key][authorPubkey] || 0) + 1

  // Keep only top 500 authors per category
  const entries = Object.entries(history[key])
  if (entries.length > 500) {
    entries.sort((a, b) => b[1] - a[1])
    history[key] = Object.fromEntries(entries.slice(0, 500))
  }

  localStorage.setItem(ENGAGEMENT_HISTORY_KEY, JSON.stringify(history))
}

/**
 * Mark a post as "not interested"
 * @param {string} eventId - Event ID to mark
 * @param {string} authorPubkey - Author's pubkey for score reduction
 */
export function markNotInterested(eventId, authorPubkey) {
  if (typeof window === 'undefined') return

  // Add to not interested posts
  const posts = getNotInterestedPosts()
  posts.add(eventId)
  // Keep max 500 posts
  const postsArray = Array.from(posts)
  if (postsArray.length > 500) {
    postsArray.splice(0, postsArray.length - 500)
  }
  localStorage.setItem(NOT_INTERESTED_KEY, JSON.stringify(postsArray))

  // Reduce author score
  const scores = getAuthorScores()
  const currentScore = scores.get(authorPubkey) || 1.0
  // Each "not interested" reduces score by 30%
  const newScore = Math.max(0.1, currentScore * 0.7)
  scores.set(authorPubkey, newScore)

  // Keep max 200 authors
  if (scores.size > 200) {
    const entries = Array.from(scores.entries())
    entries.sort((a, b) => b[1] - a[1])
    scores.clear()
    entries.slice(0, 200).forEach(([k, v]) => scores.set(k, v))
  }

  localStorage.setItem(NOT_INTERESTED_AUTHORS_KEY, JSON.stringify(Object.fromEntries(scores)))
}

/**
 * Clear not interested data
 */
export function clearNotInterestedData() {
  if (typeof window === 'undefined') return
  localStorage.removeItem(NOT_INTERESTED_KEY)
  localStorage.removeItem(NOT_INTERESTED_AUTHORS_KEY)
}

/**
 * Clear all recommendation data (including engagement history)
 */
export function clearAllRecommendationData() {
  if (typeof window === 'undefined') return
  localStorage.removeItem(NOT_INTERESTED_KEY)
  localStorage.removeItem(NOT_INTERESTED_AUTHORS_KEY)
  localStorage.removeItem(ENGAGEMENT_HISTORY_KEY)
}

/**
 * Calculate time decay factor
 * @param {number} createdAt - Unix timestamp of post creation
 * @returns {number} Decay multiplier (0-1.5)
 */
function calculateTimeDecay(createdAt) {
  const now = Math.floor(Date.now() / 1000)
  const ageInHours = Math.max(0, (now - createdAt) / 3600)

  // Freshness boost for very recent posts (under 1 hour)
  if (ageInHours < 1) {
    return TIME_DECAY.freshnessBoost
  }

  // Exponential decay with half-life
  const decayFactor = Math.pow(0.5, ageInHours / TIME_DECAY.halfLife)

  // Minimum score for old posts
  return Math.max(0.001, decayFactor)
}

/**
 * Calculate engagement score for a post
 * @param {Object} engagements - Engagement counts {zaps, replies, reposts, likes}
 * @returns {number} Engagement score
 */
function calculateEngagementScore(engagements) {
  if (!engagements) return 1

  return (
    (engagements.zaps || 0) * ENGAGEMENT_WEIGHTS.zap +
    (engagements.custom_reactions || 0) * ENGAGEMENT_WEIGHTS.custom_reaction +
    (engagements.quotes || 0) * ENGAGEMENT_WEIGHTS.quote +
    (engagements.replies || 0) * ENGAGEMENT_WEIGHTS.reply +
    (engagements.reposts || 0) * ENGAGEMENT_WEIGHTS.repost +
    (engagements.bookmarks || 0) * ENGAGEMENT_WEIGHTS.bookmark +
    (engagements.likes || 0) * ENGAGEMENT_WEIGHTS.like +
    1 // Minimum base score
  )
}

/**
 * Calculate social boost based on network position
 * @param {string} authorPubkey - Post author
 * @param {Object} context - Social context
 * @returns {number} Social boost multiplier
 */
function calculateSocialBoost(authorPubkey, context) {
  const {
    followList = new Set(),
    secondDegreeFollows = new Set(),
    followers = new Set(),
    engagementHistory = {}
  } = context

  // Check engagement history for personalized boost
  const likedCount = engagementHistory.likedAuthors?.[authorPubkey] || 0
  const repostedCount = engagementHistory.repostedAuthors?.[authorPubkey] || 0
  const repliedCount = engagementHistory.repliedAuthors?.[authorPubkey] || 0
  const totalEngagements = likedCount + repostedCount * 2 + repliedCount * 3

  // High engagement author boost
  let engagementBoost = 1.0
  if (totalEngagements >= 10) {
    engagementBoost = SOCIAL_BOOST.high_engagement_author
  } else if (totalEngagements >= 5) {
    engagementBoost = 1.5
  }

  // Network position boost
  if (followList.has(authorPubkey)) {
    // Check if mutual follow
    if (followers.has(authorPubkey)) {
      return SOCIAL_BOOST.mutual_follow * engagementBoost
    }
    // First degree - lower boost as they're already in follow feed
    return SOCIAL_BOOST.first_degree * engagementBoost
  }

  if (secondDegreeFollows.has(authorPubkey)) {
    // Friends of friends - highest discovery value
    return SOCIAL_BOOST.second_degree * engagementBoost
  }

  // Unknown author
  return SOCIAL_BOOST.unknown * engagementBoost
}

/**
 * Calculate author quality score
 * @param {Object} profile - Author's profile
 * @param {Object} authorStats - Author statistics
 * @returns {number} Quality multiplier
 */
function calculateAuthorQuality(profile, authorStats = {}) {
  let quality = 1.0

  // NIP-05 verification boost
  if (profile?.nip05) {
    quality *= 1.3
  }

  // Follower count boost (logarithmic)
  if (authorStats.followerCount) {
    const followerBoost = 1 + Math.log10(Math.max(1, authorStats.followerCount)) * 0.1
    quality *= Math.min(followerBoost, 1.5) // Cap at 1.5x
  }

  return quality
}

/**
 * Calculate recommendation score for a post
 *
 * Final formula:
 * Score = (EngagementScore * SocialBoost * AuthorQuality * GeohashBoost * AuthorModifier * TimeDecay)
 *
 * @param {Object} post - The post event
 * @param {Object} options - Scoring options
 * @returns {number} Final score (higher is better, -1 means filtered out)
 */
export function calculateRecommendationScore(post, options = {}) {
  const {
    followList = new Set(),
    secondDegreeFollows = new Set(),
    followers = new Set(),
    engagements = {},
    profiles = {},
    userGeohash = null,
    mutedPubkeys = new Set(),
    authorStats = {}
  } = options

  const authorPubkey = post.pubkey
  const authorScores = getAuthorScores()
  const notInterestedPosts = getNotInterestedPosts()
  const engagementHistory = getEngagementHistory()

  // Hard filters - return -1 to exclude
  if (notInterestedPosts.has(post.id)) {
    return -1
  }

  if (mutedPubkeys.has(authorPubkey)) {
    return -1
  }

  // Get engagement data for this post
  const engagement = engagements[post.id] || { zaps: 0, replies: 0, reposts: 0, likes: 0 }

  // Calculate component scores
  const engagementScore = calculateEngagementScore(engagement)

  const socialBoost = calculateSocialBoost(authorPubkey, {
    followList,
    secondDegreeFollows,
    followers,
    engagementHistory
  })

  const profile = profiles[authorPubkey]
  const authorQuality = calculateAuthorQuality(profile, authorStats[authorPubkey])

  // Geohash proximity boost
  let geohashBoost = 1.0
  if (userGeohash && profile?.geohash) {
    if (geohashesMatch(userGeohash, profile.geohash, 5)) {
      geohashBoost = 2.0 // Very close (~5km)
    } else if (geohashesMatch(userGeohash, profile.geohash, 3)) {
      geohashBoost = 1.5 // Same region
    } else if (geohashesMatch(userGeohash, profile.geohash, 2)) {
      geohashBoost = 1.2 // Same country
    }
  }

  // Author modifier from feedback history
  const authorModifier = authorScores.get(authorPubkey) || 1.0

  // Time decay
  const timeDecay = calculateTimeDecay(post.created_at)

  // Final score calculation
  const finalScore = engagementScore * socialBoost * authorQuality * geohashBoost * authorModifier * timeDecay

  return finalScore
}

/**
 * Extract 2nd-degree network from follow lists
 * @param {string[]} myFollows - User's 1st-degree follows
 * @param {Map<string, string[]>} followsOfFollows - Map of pubkey to their follows
 * @returns {Set<string>} Set of 2nd-degree follow pubkeys (excluding 1st-degree)
 */
export function extract2ndDegreeNetwork(myFollows, followsOfFollows) {
  const firstDegree = new Set(myFollows)
  const secondDegree = new Set()
  const connectionCount = new Map() // Track how many 1st-degree friends follow each 2nd-degree

  for (const [followerPubkey, theirFollows] of followsOfFollows) {
    if (!firstDegree.has(followerPubkey)) continue

    for (const pubkey of theirFollows) {
      // Only add if not in 1st-degree
      if (!firstDegree.has(pubkey)) {
        secondDegree.add(pubkey)
        connectionCount.set(pubkey, (connectionCount.get(pubkey) || 0) + 1)
      }
    }
  }

  return secondDegree
}

/**
 * Get 2nd-degree network with connection strength
 * @param {string[]} myFollows - User's 1st-degree follows
 * @param {Map<string, string[]>} followsOfFollows - Map of pubkey to their follows
 * @returns {Map<string, number>} Map of 2nd-degree pubkeys to connection strength
 */
export function get2ndDegreeWithStrength(myFollows, followsOfFollows) {
  const firstDegree = new Set(myFollows)
  const strength = new Map()

  for (const [followerPubkey, theirFollows] of followsOfFollows) {
    if (!firstDegree.has(followerPubkey)) continue

    for (const pubkey of theirFollows) {
      if (!firstDegree.has(pubkey)) {
        // Higher strength = more friends follow this person
        strength.set(pubkey, (strength.get(pubkey) || 0) + 1)
      }
    }
  }

  return strength
}

/**
 * Sort posts by recommendation score
 * @param {Array} posts - Array of post events
 * @param {Object} options - Scoring options
 * @returns {Array} Sorted array of posts with scores
 */
export function sortByRecommendation(posts, options = {}) {
  const notInterestedPosts = getNotInterestedPosts()

  // Filter out "not interested" posts first
  const filteredPosts = posts.filter(post => !notInterestedPosts.has(post.id))

  // Calculate scores and sort
  const scoredPosts = filteredPosts.map(post => ({
    post,
    score: calculateRecommendationScore(post, options)
  }))

  // Filter out negative scores and sort descending
  return scoredPosts
    .filter(item => item.score > 0)
    .sort((a, b) => b.score - a.score)
    .map(item => item.post)
}

/**
 * Fetch engagement data for posts
 * @param {string[]} eventIds - Event IDs to fetch engagement for
 * @param {string[]} relays - Relays to query
 * @returns {Promise<Object>} Map of eventId to engagement counts
 */
export async function fetchEngagementData(eventIds, relays) {
  if (eventIds.length === 0) return {}

  try {
    // Fetch reactions, reposts, replies, zaps, and bookmarks in parallel
    const [reactions, reposts, textNotes, zaps, bookmarks] = await Promise.all([
      fetchEventsManaged(
        { kinds: [NOSTR_KINDS.REACTION], '#e': eventIds, limit: 1000 },
        relays,
        { timeout: 10000 }
      ),
      fetchEventsManaged(
        { kinds: [NOSTR_KINDS.REPOST, NOSTR_KINDS.GENERIC_REPOST], '#e': eventIds, limit: 500 },
        relays,
        { timeout: 10000 }
      ),
      fetchEventsManaged(
        [
          { kinds: [NOSTR_KINDS.TEXT_NOTE], '#e': eventIds, limit: 1000 },
          { kinds: [NOSTR_KINDS.TEXT_NOTE], '#q': eventIds, limit: 1000 }
        ],
        relays,
        { timeout: 10000 }
      ),
      fetchEventsManaged(
        { kinds: [NOSTR_KINDS.ZAP], '#e': eventIds, limit: 500 },
        relays,
        { timeout: 10000 }
      ),
      fetchEventsManaged(
        { kinds: [NOSTR_KINDS.BOOKMARKS], '#e': eventIds, limit: 500 },
        relays,
        { timeout: 10000 }
      )
    ])

    const engagement = {}

    // Initialize all event IDs
    for (const id of eventIds) {
      engagement[id] = { likes: 0, custom_reactions: 0, reposts: 0, replies: 0, quotes: 0, zaps: 0, bookmarks: 0 }
    }

    // Count reactions (likes vs custom)
    for (const event of reactions) {
      const targetId = event.tags.find(t => t[0] === 'e')?.[1]
      if (targetId && engagement[targetId]) {
        if (event.content === '+' || event.content === '' || !event.content) {
          engagement[targetId].likes++
        } else {
          engagement[targetId].custom_reactions++
        }
      }
    }

    // Count reposts
    for (const event of reposts) {
      const targetId = event.tags.find(t => t[0] === 'e')?.[1]
      if (targetId && engagement[targetId]) {
        engagement[targetId].reposts++
      }
    }

    // Count replies and quotes
    for (const event of textNotes) {
      // Get all unique referenced event IDs from this note
      const referencedIds = new Set([
        ...event.tags.filter(t => t[0] === 'e').map(t => t[1]),
        ...event.tags.filter(t => t[0] === 'q').map(t => t[1])
      ])

      for (const targetId of referencedIds) {
        if (engagement[targetId]) {
          // It's a quote if it has a 'q' tag or an 'e' tag with 'mention' marker
          const isQuote = event.tags.some(t =>
            (t[0] === 'q' && t[1] === targetId) ||
            (t[0] === 'e' && t[1] === targetId && t[3] === 'mention')
          )

          if (isQuote) {
            engagement[targetId].quotes++
          } else {
            // Standard reply (usually has 'reply' or 'root' marker, or no marker)
            // But we only count it if it's actually one of our requested eventIds
            engagement[targetId].replies++
          }
        }
      }
    }

    // Count zaps
    for (const event of zaps) {
      const targetId = event.tags.find(t => t[0] === 'e')?.[1]
      if (targetId && engagement[targetId]) {
        engagement[targetId].zaps++
      }
    }

    // Count bookmarks
    for (const event of bookmarks) {
      const targetIds = event.tags.filter(t => t[0] === 'e').map(t => t[1])
      for (const id of targetIds) {
        if (engagement[id]) {
          engagement[id].bookmarks++
        }
      }
    }

    return engagement
  } catch (e) {
    console.error('Failed to fetch engagement data:', e)
    return {}
  }
}

/**
 * Get recommended posts with proper category mixing
 * Implements X-style feed mixing:
 * - 50% 2nd-degree network (discovery)
 * - 30% high-engagement out-of-network (viral)
 * - 20% 1st-degree (important follows)
 *
 * @param {Array} allPosts - All available posts
 * @param {Object} options - Scoring options
 * @param {number} limit - Maximum number of posts to return
 * @returns {Array} Mixed array of recommended posts
 */
export function getRecommendedPosts(allPosts, options = {}, limit = 50) {
  const {
    followList = new Set(),
    secondDegreeFollows = new Set(),
    mutedPubkeys = new Set()
  } = options

  const notInterestedPosts = getNotInterestedPosts()

  // Filter out muted and not interested
  const candidatePosts = allPosts.filter(post => {
    if (notInterestedPosts.has(post.id)) return false
    if (mutedPubkeys.has(post.pubkey)) return false
    return true
  })

  // Categorize posts
  const secondDegreePosts = candidatePosts.filter(post =>
    secondDegreeFollows.has(post.pubkey)
  )

  const otherPosts = candidatePosts.filter(post =>
    !secondDegreeFollows.has(post.pubkey) && !followList.has(post.pubkey)
  )

  const firstDegreePosts = candidatePosts.filter(post =>
    followList.has(post.pubkey)
  )

  // Score all categories
  const scored2nd = sortByRecommendation(secondDegreePosts, options)
  const scoredOther = sortByRecommendation(otherPosts, options)
  const scored1st = sortByRecommendation(firstDegreePosts, options)

  // Mix categories with target ratios: 50% 2nd-degree, 30% other, 20% 1st-degree
  const candidates = []
  const target2nd = Math.min(scored2nd.length, Math.floor(limit * 0.5))
  const targetOther = Math.min(scoredOther.length, Math.floor(limit * 0.3))
  const target1st = Math.min(scored1st.length, Math.floor(limit * 0.2))

  candidates.push(...scored2nd.slice(0, target2nd))
  candidates.push(...scoredOther.slice(0, targetOther))
  candidates.push(...scored1st.slice(0, target1st))

  // Fill remaining slots with any available high-scoring posts
  const remaining = limit - candidates.length
  if (remaining > 0) {
    const used = new Set(candidates.map(p => p.id))
    const available = [...scored2nd, ...scoredOther, ...scored1st]
      .filter(p => !used.has(p.id))
      .slice(0, remaining)
    candidates.push(...available)
  }

  // Sort by score first
  const sortedByScore = sortByRecommendation(candidates, options)

  // Apply diversity: avoid consecutive posts from same author
  const result = []
  const pool = [...sortedByScore]
  let lastAuthor = null

  while (result.length < limit && pool.length > 0) {
    // Find the highest score post that isn't from the same author as the last one
    let index = pool.findIndex(p => p.pubkey !== lastAuthor)

    // If we can't find a different author, just take the first one (highest score)
    if (index === -1) index = 0

    const [nextPost] = pool.splice(index, 1)
    result.push(nextPost)
    lastAuthor = nextPost.pubkey
  }

  return result
}

/**
 * Fetch follow lists for multiple users (for 2nd-degree calculation)
 * @param {string[]} pubkeys - Pubkeys to fetch follow lists for
 * @param {string[]} relays - Relays to query
 * @returns {Promise<Map<string, string[]>>} Map of pubkey to their follows
 */
export async function fetchFollowListsBatch(pubkeys, relays) {
  const followLists = new Map()

  try {
    const events = await fetchEventsManaged(
      { kinds: [NOSTR_KINDS.CONTACTS], authors: pubkeys },
      relays,
      { timeout: 15000 }
    )

    // Group by author and get most recent
    const latestByAuthor = new Map()
    for (const event of events) {
      const existing = latestByAuthor.get(event.pubkey)
      if (!existing || event.created_at > existing.created_at) {
        latestByAuthor.set(event.pubkey, event)
      }
    }

    // Extract follow lists
    for (const [pubkey, event] of latestByAuthor) {
      const follows = event.tags
        .filter(t => t[0] === 'p')
        .map(t => t[1])
      followLists.set(pubkey, follows)
    }
  } catch (e) {
    console.error('Failed to fetch follow lists:', e)
  }

  return followLists
}

/**
 * Build recommendation context with all necessary data
 * @param {Object} params - Parameters for building context
 * @returns {Promise<Object>} Complete context for scoring
 */
export async function buildRecommendationContext(params) {
  const {
    userPubkey,
    followList = [],
    relays = [],
    mutedPubkeys = new Set(),
    profiles = {}
  } = params

  const context = {
    followList: new Set(followList),
    secondDegreeFollows: new Set(),
    followers: new Set(),
    engagements: {},
    profiles,
    userGeohash: loadUserGeohash(),
    mutedPubkeys,
    authorStats: {}
  }

  // Fetch follow lists for 2nd-degree calculation (sample of follows for performance)
  const sampleFollows = followList.slice(0, 50) // Limit for performance
  if (sampleFollows.length > 0) {
    try {
      const followsOfFollows = await fetchFollowListsBatch(sampleFollows, relays)
      context.secondDegreeFollows = extract2ndDegreeNetwork(followList, followsOfFollows)
    } catch (e) {
      console.error('Failed to build 2nd-degree network:', e)
    }
  }

  return context
}
