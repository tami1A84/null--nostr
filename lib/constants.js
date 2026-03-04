/**
 * Application Constants
 *
 * Centralized configuration for all magic numbers and settings.
 * Some constants are auto-generated from design-tokens/constants.json.
 */

import {
  WS_CONFIG as WS_CONFIG_GEN,
  CACHE_CONFIG as CACHE_CONFIG_GEN,
  DEDUP_CONFIG as DEDUP_CONFIG_GEN,
  UPLOAD_CONFIG as UPLOAD_CONFIG_GEN,
  UI_CONFIG as UI_CONFIG_GEN,
  TIME as TIME_GEN,
  ERROR_MESSAGES as ERROR_MESSAGES_GEN,
  ENGAGEMENT_WEIGHTS,
  NEGATIVE_WEIGHTS,
  SOCIAL_BOOST,
  TIME_DECAY
} from './constants.generated'

export const WS_CONFIG = WS_CONFIG_GEN
export const CACHE_CONFIG = CACHE_CONFIG_GEN
export const DEDUP_CONFIG = DEDUP_CONFIG_GEN
export const UPLOAD_CONFIG = UPLOAD_CONFIG_GEN
export const UI_CONFIG = UI_CONFIG_GEN
export const TIME = TIME_GEN
export const ERROR_MESSAGES = ERROR_MESSAGES_GEN

export {
  ENGAGEMENT_WEIGHTS,
  NEGATIVE_WEIGHTS,
  SOCIAL_BOOST,
  TIME_DECAY
}

// ============================================
// Nostr Protocol Constants (Managed Manually)
// ============================================
export const NOSTR_KINDS = {
  METADATA: 0,
  TEXT_NOTE: 1,
  RECOMMEND_RELAY: 2,
  CONTACTS: 3,
  ENCRYPTED_DM: 4,
  DELETE: 5,
  REPOST: 6,
  REACTION: 7,
  BADGE_AWARD: 8,
  SEAL: 13,
  DIRECT_MESSAGE: 14,         // NIP-17 chat message
  FILE_MESSAGE: 15,           // NIP-17 file message
  GENERIC_REPOST: 16,
  CHANNEL_CREATE: 40,
  CHANNEL_META: 41,
  CHANNEL_MESSAGE: 42,
  CHANNEL_HIDE: 43,
  CHANNEL_MUTE: 44,
  REPORT: 1984,
  ZAP_REQUEST: 9734,
  ZAP: 9735,
  MUTE_LIST: 10000,
  PIN_LIST: 10001,
  RELAY_LIST: 10002,
  BOOKMARKS: 10003,
  COMMUNITIES: 10004,
  PUBLIC_CHATS: 10005,
  BLOCKED_RELAYS: 10006,
  SEARCH_RELAYS: 10007,
  USER_GROUPS: 10009,
  INTERESTS: 10015,
  EMOJI_LIST: 10030,
  DM_RELAY_LIST: 10050,       // NIP-17 DM receiving relay list
  GIFT_WRAP: 1059,
  NIP98_AUTH: 27235,
  BLOSSOM_AUTH: 24242,
  CLIENT_AUTH: 22242,          // NIP-42 relay authentication
  REQUEST_TO_VANISH: 62,       // NIP-62 request to vanish
  LONG_FORM: 30023,
  DRAFT_LONG_FORM: 30024,
  EMOJI_SET: 30030,
  APP_DATA: 30078,
  LIVE_EVENT: 30311,
  CLASSIFIED: 30402,
  CALENDAR_EVENT: 31922,
  CALENDAR_RSVP: 31923,
  HANDLER_INFO: 31990,
  SHORT_VIDEO: 34236,
}

// ============================================
// Connection State (Managed Manually)
// ============================================
export const CONNECTION_STATE = {
  DISCONNECTED: 'disconnected',
  CONNECTING: 'connecting',
  CONNECTED: 'connected',
  RECONNECTING: 'reconnecting',
  ERROR: 'error',
}

// ============================================
// Default Export
// ============================================
export default {
  WS_CONFIG,
  CACHE_CONFIG,
  DEDUP_CONFIG,
  UPLOAD_CONFIG,
  UI_CONFIG,
  TIME,
  NOSTR_KINDS,
  ERROR_MESSAGES,
  CONNECTION_STATE,
  ENGAGEMENT_WEIGHTS,
  NEGATIVE_WEIGHTS,
  SOCIAL_BOOST,
  TIME_DECAY
}
