'use client'

/**
 * Custom Hooks for Nostr Web Client
 *
 * These hooks provide reusable logic for:
 * - WebSocket subscription management
 * - Profile fetching with caching
 * - Connection status monitoring
 * - Event fetching with pagination
 */

// Subscription hooks
export {
  useNostrSubscription,
  useTimelineSubscription,
  useUserEventsSubscription,
  useNotificationsSubscription,
} from './useNostrSubscription'

// Profile hooks
export {
  useProfile,
  useProfiles,
  useProfileUpdater,
} from './useProfile'

// Connection status hooks
export {
  useConnectionStatus,
  useRelayHealth,
  useNetworkStatus,
  useFullConnectionStatus,
} from './useConnectionStatus'

// Event fetching hooks
export {
  useNostrEvents,
  useTimeline,
  useReactions,
  useReposts,
} from './useNostrEvents'

// Default export
export { default as useNostrSubscription } from './useNostrSubscription'
export { default as useProfile } from './useProfile'
export { default as useConnectionStatus } from './useConnectionStatus'
export { default as useNostrEvents } from './useNostrEvents'
