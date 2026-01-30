/**
 * Common UI types for the application
 */

import type { Event as NostrEvent } from 'nostr-tools'

/**
 * Profile data structure
 */
export interface Profile {
  name?: string
  display_name?: string
  displayName?: string
  about?: string
  picture?: string
  banner?: string
  nip05?: string
  lud16?: string
  lud06?: string
  website?: string
  birthday?: string | { year?: number; month?: number; day?: number }
  pubkey?: string
}

/**
 * Post with optional repost metadata
 */
export interface Post extends NostrEvent {
  _repostedBy?: string
  _repostTime?: number
  _isRepost?: boolean
  _repostId?: string
  _recommendationScore?: number
}

/**
 * Emoji tag format
 */
export type EmojiTag = ['emoji', string, string] // [type, shortcode, url]

/**
 * Custom emoji data
 */
export interface CustomEmoji {
  shortcode: string
  url: string
}

/**
 * Birdwatch label (NIP-32)
 */
export interface BirdwatchLabel extends NostrEvent {
  // Additional fields for birdwatch functionality
}

/**
 * Timeline mode
 */
export type TimelineMode = 'global' | 'following'

/**
 * Login method types
 */
export type LoginMethod =
  | 'nosskey'
  | 'extension'
  | 'readOnly'
  | 'local'
  | 'connect'
  | 'amber'

/**
 * Reaction counts map
 */
export type ReactionCounts = Record<string, number>

/**
 * User interaction tracking
 */
export interface UserInteractions {
  reactions: Set<string>      // Event IDs the user has liked
  reposts: Set<string>        // Event IDs the user has reposted
  reactionIds: Record<string, string>  // eventId -> reactionEventId
  repostIds: Record<string, string>    // eventId -> repostEventId
}

/**
 * Zap modal state
 */
export interface ZapModalState {
  event: NostrEvent
  profile: Profile
}

/**
 * Common component props
 */
export interface BaseComponentProps {
  className?: string
}
