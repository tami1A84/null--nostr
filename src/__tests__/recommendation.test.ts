import { describe, it, expect, vi, beforeEach } from 'vitest'
import {
  calculateRecommendationScore,
  sortByRecommendation,
  getRecommendedPosts,
  fetchEngagementData
} from '../../lib/recommendation'
import { NOSTR_KINDS } from '../../lib/constants'

// Mock dependencies
vi.mock('../../lib/geohash', () => ({
  loadUserGeohash: vi.fn(() => 'xn77'),
  geohashesMatch: vi.fn((a, b, precision) => a.substring(0, precision) === b.substring(0, precision)),
}))

const mockFetchEventsManaged = vi.fn()
vi.mock('../../lib/connection-manager', () => ({
  fetchEventsManaged: (...args) => mockFetchEventsManaged(...args),
}))

vi.mock('../../lib/outbox', () => ({
  fetchEventsWithOutboxModel: vi.fn(),
  fetchRelayListsBatch: vi.fn(),
}))

describe('Recommendation Algorithm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  describe('Engagement Score', () => {
    it('should calculate base score correctly with all actions', () => {
      const post = { id: '1', pubkey: 'p1', created_at: Math.floor(Date.now() / 1000) }
      const options = {
        engagements: {
          '1': {
            zaps: 1,
            replies: 1,
            reposts: 1,
            likes: 1,
            quotes: 1,
            bookmarks: 1,
            custom_reactions: 1
          }
        }
      }
      // Weights:
      // Zap: 100
      // Custom Reaction: 60
      // Quote: 35
      // Reply: 30
      // Repost: 25
      // Bookmark: 15
      // Like: 5
      // Base: 1
      // Total: 100 + 60 + 35 + 30 + 25 + 15 + 5 + 1 = 271
      // Freshness: 1.5
      // Expected Final: 271 * 1.5 = 406.5
      const score = calculateRecommendationScore(post, options)
      expect(score).toBe(406.5)
    })
  })

  describe('Time Decay', () => {
    it('should handle very old posts correctly (no jump at 48h)', () => {
      const now = Math.floor(Date.now() / 1000)
      const post47 = { id: '1', pubkey: 'p1', created_at: now - 47 * 3600 }
      const post49 = { id: '2', pubkey: 'p1', created_at: now - 49 * 3600 }

      const score47 = calculateRecommendationScore(post47, {})
      const score49 = calculateRecommendationScore(post49, {})

      expect(score49).toBeLessThan(score47)
      expect(score49).toBeLessThan(0.01)
    })
  })

  describe('Diversity', () => {
    it('should interleave posts from different authors', () => {
      const now = Math.floor(Date.now() / 1000)
      const posts = [
        { id: '1', pubkey: 'author1', created_at: now, content: 'a1-1' },
        { id: '2', pubkey: 'author1', created_at: now - 1, content: 'a1-2' },
        { id: '3', pubkey: 'author2', created_at: now - 2, content: 'a2-1' },
        { id: '4', pubkey: 'author2', created_at: now - 3, content: 'a2-2' },
      ]

      const options = {
        engagements: {
          '1': { likes: 10 },
          '2': { likes: 10 },
          '3': { likes: 10 },
          '4': { likes: 10 },
        }
      }

      const recommended = getRecommendedPosts(posts, options, 10)

      expect(recommended[0].pubkey).toBe('author1')
      expect(recommended[1].pubkey).toBe('author2')
      expect(recommended[2].pubkey).toBe('author1')
      expect(recommended[3].pubkey).toBe('author2')
    })
  })

  describe('fetchEngagementData', () => {
    it('should correctly categorize engagement events', async () => {
      const eventIds = ['e1']
      const relays = ['ws://relay']

      mockFetchEventsManaged.mockImplementation((filterOrFilters) => {
        const filters = Array.isArray(filterOrFilters) ? filterOrFilters : [filterOrFilters]

        const allKinds = new Set()
        filters.forEach(f => {
          if (f.kinds) f.kinds.forEach(k => allKinds.add(k))
        })

        if (allKinds.has(NOSTR_KINDS.REACTION)) {
          return Promise.resolve([
            { kind: 7, content: '+', tags: [['e', 'e1']] },
            { kind: 7, content: '🔥', tags: [['e', 'e1']] }
          ])
        }
        if (allKinds.has(NOSTR_KINDS.TEXT_NOTE)) {
          return Promise.resolve([
            { kind: 1, content: 'reply', tags: [['e', 'e1', '', 'reply']] },
            { kind: 1, content: 'quote', tags: [['q', 'e1']] }
          ])
        }
        if (allKinds.has(NOSTR_KINDS.BOOKMARKS)) {
          return Promise.resolve([
            { kind: 10003, tags: [['e', 'e1']] }
          ])
        }
        return Promise.resolve([])
      })

      const data = await fetchEngagementData(eventIds, relays)

      expect(data.e1.likes).toBe(1)
      expect(data.e1.custom_reactions).toBe(1)
      expect(data.e1.replies).toBe(1)
      expect(data.e1.quotes).toBe(1)
      expect(data.e1.bookmarks).toBe(1)
    })
  })
})
