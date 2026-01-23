/**
 * Nostr Filter Helpers
 *
 * Utility functions for creating common Nostr event filters.
 * Reduces code duplication across timeline components.
 *
 * @module filters
 */

/**
 * @typedef {Object} TimelineFilters
 * @property {Object} notes - Filter for note events (kind 1)
 * @property {Object} reposts - Filter for repost events (kind 6)
 */

/**
 * @typedef {Object} TimelineLimits
 * @property {number} [notes=100] - Maximum number of notes to fetch
 * @property {number} [reposts=50] - Maximum number of reposts to fetch
 */

/**
 * Create timeline filters for notes and reposts
 *
 * @param {Object} options - Filter options
 * @param {string[]} [options.authors] - Filter by author pubkeys
 * @param {number} [options.since] - Filter events since this timestamp
 * @param {number} [options.until] - Filter events until this timestamp
 * @param {TimelineLimits} [options.limits] - Limits for each event type
 * @returns {TimelineFilters} Filters for notes and reposts
 *
 * @example
 * const filters = createTimelineFilters({
 *   authors: [pubkey1, pubkey2],
 *   since: Date.now() / 1000 - 3600,
 *   limits: { notes: 50, reposts: 25 }
 * })
 */
export function createTimelineFilters({
  authors,
  since,
  until,
  limits = { notes: 100, reposts: 50 }
} = {}) {
  const baseFilter = {}

  if (authors?.length) {
    baseFilter.authors = authors
  }
  if (since) {
    baseFilter.since = since
  }
  if (until) {
    baseFilter.until = until
  }

  return {
    notes: {
      kinds: [1],
      ...baseFilter,
      limit: limits.notes || 100
    },
    reposts: {
      kinds: [6],
      ...baseFilter,
      limit: limits.reposts || 50
    }
  }
}

/**
 * Create a filter for fetching user metadata (kind 0)
 *
 * @param {string[]} pubkeys - Public keys to fetch profiles for
 * @returns {Object} Filter object
 */
export function createProfileFilter(pubkeys) {
  return {
    kinds: [0],
    authors: pubkeys
  }
}

/**
 * Create a filter for fetching follow lists (kind 3)
 *
 * @param {string} pubkey - Public key to fetch follow list for
 * @returns {Object} Filter object
 */
export function createFollowListFilter(pubkey) {
  return {
    kinds: [3],
    authors: [pubkey],
    limit: 1
  }
}

/**
 * Create a filter for fetching mute lists (kind 10000)
 *
 * @param {string} pubkey - Public key to fetch mute list for
 * @returns {Object} Filter object
 */
export function createMuteListFilter(pubkey) {
  return {
    kinds: [10000],
    authors: [pubkey],
    limit: 1
  }
}

/**
 * Create a filter for fetching reactions (kind 7)
 *
 * @param {string[]} eventIds - Event IDs to fetch reactions for
 * @param {number} [limit=500] - Maximum number of reactions
 * @returns {Object} Filter object
 */
export function createReactionFilter(eventIds, limit = 500) {
  return {
    kinds: [7],
    '#e': eventIds,
    limit
  }
}

/**
 * Create a filter for fetching replies to events
 *
 * @param {string[]} eventIds - Event IDs to fetch replies for
 * @param {number} [limit=100] - Maximum number of replies
 * @returns {Object} Filter object
 */
export function createReplyFilter(eventIds, limit = 100) {
  return {
    kinds: [1],
    '#e': eventIds,
    limit
  }
}

/**
 * Create a filter for DM events (NIP-17)
 *
 * @param {string} pubkey - User's public key
 * @param {number} [since] - Fetch messages since this timestamp
 * @param {number} [limit=100] - Maximum number of messages
 * @returns {Object} Filter object
 */
export function createDMFilter(pubkey, since, limit = 100) {
  const filter = {
    kinds: [1059], // Gift-wrapped events
    '#p': [pubkey],
    limit
  }

  if (since) {
    filter.since = since
  }

  return filter
}

/**
 * Create a filter for zap receipts (kind 9735)
 *
 * @param {string[]} eventIds - Event IDs to fetch zaps for
 * @param {number} [limit=100] - Maximum number of zap receipts
 * @returns {Object} Filter object
 */
export function createZapFilter(eventIds, limit = 100) {
  return {
    kinds: [9735],
    '#e': eventIds,
    limit
  }
}

/**
 * Create a text search filter (NIP-50)
 *
 * @param {string} query - Search query
 * @param {number} [limit=50] - Maximum number of results
 * @returns {Object} Filter object
 */
export function createSearchFilter(query, limit = 50) {
  return {
    kinds: [1],
    search: query,
    limit
  }
}

/**
 * Create a filter for custom emoji sets (kind 10030)
 *
 * @param {string} pubkey - Public key to fetch emoji for
 * @returns {Object} Filter object
 */
export function createEmojiFilter(pubkey) {
  return {
    kinds: [10030],
    authors: [pubkey],
    limit: 1
  }
}

/**
 * Create a filter for badges (NIP-58)
 *
 * @param {string} pubkey - Public key to fetch badges for
 * @returns {Object} Filter object
 */
export function createBadgeFilter(pubkey) {
  return {
    kinds: [30008], // Profile badges
    authors: [pubkey],
    limit: 1
  }
}

/**
 * Merge multiple filters into an array for batch queries
 *
 * @param {...Object} filters - Filters to merge
 * @returns {Object[]} Array of filters
 */
export function mergeFilters(...filters) {
  return filters.filter(Boolean)
}

/**
 * Calculate "since" timestamp for a given duration
 *
 * @param {number} hours - Number of hours
 * @returns {number} Unix timestamp
 */
export function getSinceHoursAgo(hours) {
  return Math.floor(Date.now() / 1000) - hours * 3600
}

/**
 * Calculate "since" timestamp for a given number of days
 *
 * @param {number} days - Number of days
 * @returns {number} Unix timestamp
 */
export function getSinceDaysAgo(days) {
  return Math.floor(Date.now() / 1000) - days * 86400
}
