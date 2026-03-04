/* ============================================================ */
/* Auto-generated from design-tokens/constants.json — DO NOT EDIT */
/* Run: npm run tokens                                          */
/* ============================================================ */

export const WS_CONFIG = { maxConcurrentRequests: 4, maxRequestsPerRelay: 2, requestTimeout: 15000, eoseTimeout: 15000, poolIdleTimeout: 180000, healthCheckInterval: 60000, failedRelayCooldown: 120000, maxFailuresBeforeCooldown: 3, retry: { maxAttempts: 3, baseDelay: 500, maxDelay: 10000, jitter: 0.3 }, rateLimit: { requestsPerSecond: 10, burstSize: 20 }, subscription: { reconnectDelay: 1000, maxReconnectDelay: 30000, reconnectBackoffMultiplier: 1.5, maxReconnectAttempts: 10, heartbeatInterval: 30000 } };
export const CACHE_CONFIG = { prefix: "nurunuru_cache_", durations: { profile: 300000, muteList: 600000, followList: 600000, emoji: 1800000, timeline: 30000, short: 60000, nip05: 300000, relayInfo: 3600000 }, maxEntries: { profiles: 500, timeline: 100, reactions: 1000 }, indexRefreshInterval: 60000 };
export const DEDUP_CONFIG = { pendingRequestTTL: 30000, completedResultTTL: 5000, maxPendingRequests: 100 };
export const UPLOAD_CONFIG = { maxConcurrentUploads: 3, uploadTimeout: 30000, retry: { maxAttempts: 3, baseDelay: 1000, maxDelay: 5000, jitter: 0.3 }, maxImagesPerPost: 3 };
export const UI_CONFIG = { debounce: { search: 300, scroll: 100, resize: 150 }, pageSize: { timeline: 50, profiles: 20, search: 30, notifications: 50 }, skeleton: { timelineCount: 5, profileCount: 3 }, nip05VerifyTimeout: 5000, profileFetchTimeout: 10000 };
export const TIME = { MINUTE: 60, HOUR: 3600, DAY: 86400, WEEK: 604800, MS_SECOND: 1000, MS_MINUTE: 60000, MS_HOUR: 3600000, MS_DAY: 86400000 };
export const ENGAGEMENT_WEIGHTS = { zap: 100, custom_reaction: 60, quote: 35, reply: 30, repost: 25, bookmark: 15, like: 5 };
export const NEGATIVE_WEIGHTS = { not_interested: -50, muted_author: -1000, reported: -200 };
export const SOCIAL_BOOST = { second_degree: 3, mutual_follow: 2.5, high_engagement_author: 2, first_degree: 0.5, unknown: 1 };
export const TIME_DECAY = { halfLife: 6, maxAge: 48, freshnessBoost: 1.5 };
export const ERROR_MESSAGES = { noSigningMethod: "署名機能が利用できません", signingFailed: "署名に失敗しました", passkeySigningFailed: "パスキーでの署名に失敗しました。ミニアプリ画面で秘密鍵をエクスポートしてください。", amberSigningFailed: "Amberでの署名に失敗しました。再度お試しください。", bunkerSigningFailed: "Nostr Connectでの署名に失敗しました。再接続してください。", encryptionFailed: "暗号化に失敗しました", decryptionFailed: "復号に失敗しました", noEncryptionMethod: "暗号化機能が利用できません。ミニアプリ画面で秘密鍵をエクスポートしてください。", dmRequiresAuth: "DMを送信するにはパスキー認証が必要です", dmNotAvailable: "DM機能が利用できません。NIP-44対応の拡張機能か秘密鍵が必要です。", nip07NotFound: "NIP-07拡張機能が見つかりません", publicKeyFailed: "公開鍵の取得に失敗しました", requestTimeout: "リクエストがタイムアウトしました", allRetriesFailed: "すべての再試行が失敗しました", connectionFailed: "接続に失敗しました", alreadyFollowing: "既にフォローしています", noFollowList: "フォローリストがありません", amberTimeout: "Amberからの応答がタイムアウトしました", amberInvalidEvent: "Amberから無効なイベントが返されました", amberNoPendingRequest: "保留中の署名リクエストが見つかりません", statusOffline: "オフライン", statusReconnecting: "再接続中...", statusError: "接続に問題があります", statusConnected: "接続中", statusDisconnected: "切断" };
