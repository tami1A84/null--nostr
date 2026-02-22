/**
 * Store Types
 *
 * Type definitions for the Zustand store
 *
 * @module core/store/types
 */

import type { SignerType } from '@/src/adapters/signing'

/**
 * Login methods supported by the app
 */
export type LoginMethod =
  | 'nip07'
  | 'nosskey'
  | 'amber'
  | 'bunker'
  | 'nsec'
  | 'nostr-connect'
  | 'nostr-login'
  | 'connect'

/**
 * Authentication state
 */
export interface AuthState {
  /** Current user's public key (hex) */
  pubkey: string | null
  /** Method used to login */
  loginMethod: LoginMethod | null
  /** Whether the user is logged in */
  isLoggedIn: boolean
}

/**
 * Authentication actions
 */
export interface AuthActions {
  /** Log in with a public key and method */
  login: (pubkey: string, method: LoginMethod) => void
  /** Log out and clear auth state */
  logout: () => void
  /** Set just the pubkey (for Amber signer that needs pubkey before signing) */
  setPubkey: (pubkey: string) => void
}

/**
 * Settings state
 */
export interface SettingsState {
  /** Default amount for Zap (in sats) */
  defaultZapAmount: number
  /** Enable low bandwidth mode */
  lowBandwidthMode: boolean
  /** Auto-sign events without confirmation */
  autoSign: boolean
  /** User's geohash for location-based features */
  userGeohash: string | null
  /** Custom relay list */
  customRelays: string[]
  /** Muted public keys */
  mutedPubkeys: string[]
  /** Muted words for content filtering */
  mutedWords: string[]
}

/**
 * Settings actions
 */
export interface SettingsActions {
  setDefaultZapAmount: (amount: number) => void
  setLowBandwidthMode: (enabled: boolean) => void
  setAutoSign: (enabled: boolean) => void
  setUserGeohash: (geohash: string | null) => void
  addCustomRelay: (url: string) => void
  removeCustomRelay: (url: string) => void
  setCustomRelays: (urls: string[]) => void
  addMutedPubkey: (pubkey: string) => void
  removeMutedPubkey: (pubkey: string) => void
  addMutedWord: (word: string) => void
  removeMutedWord: (word: string) => void
}

/**
 * Profile data structure
 */
export interface Profile {
  pubkey: string
  name?: string
  displayName?: string
  picture?: string
  banner?: string
  about?: string
  nip05?: string
  lud16?: string
  website?: string
  /** Timestamp when this profile was fetched */
  fetchedAt: number
}

/**
 * Profile cache state
 */
export interface CacheState {
  /** Profile cache (LRU-like, limited entries) */
  profiles: Record<string, Profile>
  /** Follow lists by pubkey */
  followLists: Record<string, string[]>
}

/**
 * Cache actions
 */
export interface CacheActions {
  /** Set a profile in cache */
  setProfile: (profile: Profile) => void
  /** Get a profile from cache */
  getProfile: (pubkey: string) => Profile | undefined
  /** Set follow list for a pubkey */
  setFollowList: (pubkey: string, follows: string[]) => void
  /** Get follow list for a pubkey */
  getFollowList: (pubkey: string) => string[] | undefined
  /** Clear all cached profiles */
  clearProfiles: () => void
  /** Clear all cache */
  clearAllCache: () => void
}

/**
 * Combined store state
 */
export type StoreState = AuthState & SettingsState & CacheState

/**
 * Combined store actions
 */
export type StoreActions = AuthActions & SettingsActions & CacheActions

/**
 * Complete store type
 */
export type Store = StoreState & StoreActions

/**
 * Keys that should be persisted to storage
 */
export const PERSISTED_KEYS: (keyof StoreState)[] = [
  'pubkey',
  'loginMethod',
  'defaultZapAmount',
  'lowBandwidthMode',
  'autoSign',
  'userGeohash',
  'customRelays',
  'mutedPubkeys',
  'mutedWords',
]

/**
 * Keys for profile cache (separate storage)
 */
export const CACHE_KEYS: (keyof CacheState)[] = ['profiles', 'followLists']

/**
 * Maximum number of profiles to keep in cache
 */
export const MAX_CACHED_PROFILES = 500

/**
 * Profile cache TTL in milliseconds (24 hours)
 */
export const PROFILE_CACHE_TTL = 24 * 60 * 60 * 1000
