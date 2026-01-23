/**
 * Application Constants
 *
 * Centralized configuration for all magic numbers and settings.
 * This improves maintainability and makes tuning easier.
 */

// ============================================
// WebSocket Connection Settings
// ============================================
export const WS_CONFIG = {
  // Maximum concurrent requests across all operations
  maxConcurrentRequests: 4,

  // Maximum concurrent requests per relay
  maxRequestsPerRelay: 2,

  // Request timeout in ms
  requestTimeout: 15000,

  // EOSE (End of Stored Events) timeout
  eoseTimeout: 15000,

  // Pool idle timeout before reset (3 minutes)
  poolIdleTimeout: 180000,

  // Health check interval (60 seconds)
  healthCheckInterval: 60000,

  // Failed relay cooldown (2 minutes)
  failedRelayCooldown: 120000,

  // Max failures before cooldown
  maxFailuresBeforeCooldown: 3,

  // Retry configuration
  retry: {
    maxAttempts: 3,
    baseDelay: 500,
    maxDelay: 10000,
    jitter: 0.3 // 30% random jitter
  },

  // Rate limiting (requests per second per relay)
  rateLimit: {
    requestsPerSecond: 10,
    burstSize: 20
  },

  // Subscription auto-reconnect settings
  subscription: {
    reconnectDelay: 1000,        // Initial reconnect delay
    maxReconnectDelay: 30000,    // Maximum reconnect delay
    reconnectBackoffMultiplier: 1.5,
    maxReconnectAttempts: 10,
    heartbeatInterval: 30000,    // 30 seconds heartbeat
  }
}

// ============================================
// Cache Settings
// ============================================
export const CACHE_CONFIG = {
  prefix: 'nurunuru_cache_',

  // Cache durations (in milliseconds)
  durations: {
    profile: 5 * 60 * 1000,      // 5 minutes for profiles
    muteList: 10 * 60 * 1000,    // 10 minutes for mute list
    followList: 10 * 60 * 1000,  // 10 minutes for follow list
    emoji: 30 * 60 * 1000,       // 30 minutes for custom emoji
    timeline: 30 * 1000,         // 30 seconds for timeline (for quick restore)
    short: 60 * 1000,            // 1 minute
    nip05: 5 * 60 * 1000,        // 5 minutes for NIP-05 verification
    relayInfo: 60 * 60 * 1000,   // 1 hour for NIP-11 relay info
  },

  // Maximum cache entries before cleanup
  maxEntries: {
    profiles: 500,
    timeline: 100,
    reactions: 1000,
  },

  // Index settings
  indexRefreshInterval: 60000,  // 1 minute
}

// ============================================
// Request Deduplication Settings
// ============================================
export const DEDUP_CONFIG = {
  // How long to keep pending requests for deduplication (ms)
  pendingRequestTTL: 30000,

  // How long to keep completed results for sharing (ms)
  completedResultTTL: 5000,

  // Maximum pending requests before cleanup
  maxPendingRequests: 100,
}

// ============================================
// Image Upload Settings
// ============================================
export const UPLOAD_CONFIG = {
  // Maximum concurrent uploads
  maxConcurrentUploads: 3,

  // Upload timeout in ms (30 seconds)
  uploadTimeout: 30000,

  // Retry configuration
  retry: {
    maxAttempts: 3,
    baseDelay: 1000,
    maxDelay: 5000,
    jitter: 0.3  // 30% random jitter
  },

  // Maximum images per post
  maxImagesPerPost: 3,
}

// ============================================
// UI/UX Settings
// ============================================
export const UI_CONFIG = {
  // Debounce delays (ms)
  debounce: {
    search: 300,
    scroll: 100,
    resize: 150,
  },

  // Pagination
  pageSize: {
    timeline: 50,
    profiles: 20,
    search: 30,
    notifications: 50,
  },

  // Loading states
  skeleton: {
    timelineCount: 5,
    profileCount: 3,
  },

  // Timeouts
  nip05VerifyTimeout: 5000,
  profileFetchTimeout: 10000,
}

// ============================================
// Time Constants
// ============================================
export const TIME = {
  // In seconds
  MINUTE: 60,
  HOUR: 3600,
  DAY: 86400,
  WEEK: 604800,

  // In milliseconds
  MS_SECOND: 1000,
  MS_MINUTE: 60000,
  MS_HOUR: 3600000,
  MS_DAY: 86400000,
}

// ============================================
// Nostr Protocol Constants
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
}

// ============================================
// Error Messages (Japanese)
// ============================================
export const ERROR_MESSAGES = {
  // Signing
  noSigningMethod: '署名機能が利用できません',
  signingFailed: '署名に失敗しました',
  passkeySigningFailed: 'パスキーでの署名に失敗しました。ミニアプリ画面で秘密鍵をエクスポートしてください。',
  amberSigningFailed: 'Amberでの署名に失敗しました。再度お試しください。',
  bunkerSigningFailed: 'nsec.appでの署名に失敗しました。再接続してください。',

  // Encryption
  encryptionFailed: '暗号化に失敗しました',
  decryptionFailed: '復号に失敗しました',
  noEncryptionMethod: '暗号化機能が利用できません。ミニアプリ画面で秘密鍵をエクスポートしてください。',

  // DM
  dmRequiresAuth: 'DMを送信するにはパスキー認証が必要です',
  dmNotAvailable: 'DM機能が利用できません。NIP-44対応の拡張機能か秘密鍵が必要です。',

  // NIP-07
  nip07NotFound: 'NIP-07拡張機能が見つかりません',
  publicKeyFailed: '公開鍵の取得に失敗しました',

  // Network
  requestTimeout: 'リクエストがタイムアウトしました',
  allRetriesFailed: 'すべての再試行が失敗しました',
  connectionFailed: '接続に失敗しました',

  // Follow
  alreadyFollowing: '既にフォローしています',
  noFollowList: 'フォローリストがありません',

  // Amber
  amberTimeout: 'Amberからの応答がタイムアウトしました',
  amberInvalidEvent: 'Amberから無効なイベントが返されました',
  amberNoPendingRequest: '保留中の署名リクエストが見つかりません',
}

// ============================================
// Connection State
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
}
