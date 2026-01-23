/**
 * Recommendation Engine for Discovery Feed
 *
 * Implements scoring algorithm for "おすすめ" tab:
 * - 2nd-degree network priority (friends of friends)
 * - Geolocation boost
 * - NIP-05 verification boost
 * - Time decay
 * - Negative feedback filtering
 */

import { loadUserGeohash, geohashesMatch } from './geohash'

// Storage keys
const NOT_INTERESTED_KEY = 'nurunuru_not_interested'
const NOT_INTERESTED_AUTHORS_KEY = 'nurunuru_not_interested_authors'

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
 * Get author scores (negative for "not interested" feedback)
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
 * Calculate recommendation score for a post
 *
 * Formula: FinalScore = (BaseScore * SocialBoost * GeohashBoost * AuthorModifier) / (Age_in_hours + 2)^1.5
 *
 * @param {Object} post - The post event
 * @param {Object} options - Scoring options
 * @param {Set<string>} options.followList - User's 1st-degree follows
 * @param {Set<string>} options.secondDegreeFollows - 2nd-degree follows (friends of friends)
 * @param {Object} options.engagements - Map of eventId to {zaps, replies, reposts}
 * @param {Object} options.profiles - Map of pubkey to profile (for NIP-05)
 * @param {string} options.userGeohash - User's geohash for proximity
 * @returns {number} Final score (higher is better)
 */
export function calculateRecommendationScore(post, options = {}) {
  const {
    followList = new Set(),
    secondDegreeFollows = new Set(),
    engagements = {},
    profiles = {},
    userGeohash = null
  } = options

  const authorPubkey = post.pubkey
  const authorScores = getAuthorScores()
  const notInterestedPosts = getNotInterestedPosts()

  // Skip if marked as not interested
  if (notInterestedPosts.has(post.id)) {
    return -1
  }

  // Base engagement score
  const engagement = engagements[post.id] || { zaps: 0, replies: 0, reposts: 0, likes: 0 }
  const baseScore = (
    (engagement.zaps || 0) * 100 +
    (engagement.replies || 0) * 27 +
    (engagement.reposts || 0) * 20 +
    (engagement.likes || 0) * 5 +
    1 // Minimum base score
  )

  // Social boost based on network position
  let socialBoost = 1.0
  if (followList.has(authorPubkey)) {
    // 1st-degree: lower boost (user already sees in Follow feed)
    socialBoost = 0.3
  } else if (secondDegreeFollows.has(authorPubkey)) {
    // 2nd-degree network: highest boost (discovery priority)
    socialBoost = 2.5
  } else {
    // Unknown: moderate boost
    socialBoost = 1.0
  }

  // NIP-05 verification boost
  const profile = profiles[authorPubkey]
  if (profile?.nip05) {
    socialBoost *= 1.2
  }

  // Geohash proximity boost
  let geohashBoost = 1.0
  if (userGeohash && profile?.geohash) {
    if (geohashesMatch(userGeohash, profile.geohash, 5)) {
      geohashBoost = 2.0 // Very close
    } else if (geohashesMatch(userGeohash, profile.geohash, 3)) {
      geohashBoost = 1.5 // Same region
    } else if (geohashesMatch(userGeohash, profile.geohash, 2)) {
      geohashBoost = 1.2 // Same country
    }
  }

  // Author modifier from feedback history
  const authorModifier = authorScores.get(authorPubkey) || 1.0

  // Time decay: posts get lower scores as they age
  const now = Math.floor(Date.now() / 1000)
  const ageInHours = Math.max(0, (now - post.created_at) / 3600)
  const timeDecay = Math.pow(ageInHours + 2, 1.5)

  // Final score calculation
  const finalScore = (baseScore * socialBoost * geohashBoost * authorModifier) / timeDecay

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

  for (const [followerPubkey, theirFollows] of followsOfFollows) {
    if (!firstDegree.has(followerPubkey)) continue

    for (const pubkey of theirFollows) {
      // Only add if not in 1st-degree
      if (!firstDegree.has(pubkey)) {
        secondDegree.add(pubkey)
      }
    }
  }

  return secondDegree
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
 * Get recommended posts with proper mix
 * Ensures ~50% from 2nd-degree network when available
 *
 * @param {Array} allPosts - All available posts
 * @param {Object} options - Scoring options
 * @param {number} limit - Maximum number of posts to return
 * @returns {Array} Mixed array of recommended posts
 */
export function getRecommendedPosts(allPosts, options = {}, limit = 50) {
  const { followList = new Set(), secondDegreeFollows = new Set() } = options
  const notInterestedPosts = getNotInterestedPosts()

  // Filter out "not interested" and 1st-degree posts
  const candidatePosts = allPosts.filter(post => {
    if (notInterestedPosts.has(post.id)) return false
    return true
  })

  // Separate into categories
  const secondDegreePostsUnfiltered = candidatePosts.filter(post =>
    secondDegreeFollows.has(post.pubkey)
  )
  const otherPosts = candidatePosts.filter(post =>
    !secondDegreeFollows.has(post.pubkey) && !followList.has(post.pubkey)
  )
  const firstDegreePosts = candidatePosts.filter(post =>
    followList.has(post.pubkey)
  )

  // Score all posts
  const scored2nd = sortByRecommendation(secondDegreePostsUnfiltered, options)
  const scoredOther = sortByRecommendation(otherPosts, options)
  const scored1st = sortByRecommendation(firstDegreePosts, options)

  // Mix: prioritize 2nd-degree (~50%), then other (~40%), then 1st-degree (~10%)
  const result = []
  const target2nd = Math.min(scored2nd.length, Math.floor(limit * 0.5))
  const targetOther = Math.min(scoredOther.length, Math.floor(limit * 0.4))
  const target1st = Math.min(scored1st.length, Math.floor(limit * 0.1))

  result.push(...scored2nd.slice(0, target2nd))
  result.push(...scoredOther.slice(0, targetOther))
  result.push(...scored1st.slice(0, target1st))

  // Fill remaining with any available posts
  const remaining = limit - result.length
  if (remaining > 0) {
    const used = new Set(result.map(p => p.id))
    const available = [...scored2nd, ...scoredOther, ...scored1st]
      .filter(p => !used.has(p.id))
      .slice(0, remaining)
    result.push(...available)
  }

  // Final sort by score
  return sortByRecommendation(result, options).slice(0, limit)
}
