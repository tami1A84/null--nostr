// ============================================================
// Auto-generated from design-tokens/constants.json — DO NOT EDIT
// Run: npm run tokens
// ============================================================

import Foundation

// MARK: - WebSocket / Connection
enum Connection {
    static let maxConcurrentRequests:   Int    = 4
    static let maxRequestsPerRelay:     Int    = 2
    static let requestTimeoutMs:        Int    = 15_000
    static let eoseTimeoutMs:           Int    = 15_000
    static let poolIdleTimeoutMs:       Int    = 180_000
    static let healthCheckIntervalMs:   Int    = 60_000
    static let failedRelayCooldownMs:   Int    = 120_000
    static let maxFailuresBeforeCooldown: Int  = 3
    static let retryMaxAttempts:        Int    = 3
    static let retryBaseDelayMs:        Int    = 500
    static let retryMaxDelayMs:         Int    = 10_000
    static let retryJitter:             Double = 0.3
    static let reconnectDelayMs:        Int    = 1_000
    static let maxReconnectDelayMs:     Int    = 30_000
    static let reconnectBackoffMultiplier: Double = 1.5
    static let maxReconnectAttempts:    Int    = 10
    static let heartbeatIntervalMs:     Int    = 30_000
}

enum RateLimit {
    static let requestsPerSecond: Int = 10
    static let burstSize:         Int = 20
}

// MARK: - Cache
enum CacheDuration {
    static let profile:    Int = 300_000
    static let muteList:   Int = 600_000
    static let followList: Int = 600_000
    static let emoji:      Int = 1_800_000
    static let timeline:   Int = 600_000
    static let short:      Int = 60_000
    static let nip05:      Int = 300_000
    static let relayInfo:  Int = 3_600_000
}

enum CacheMaxEntries {
    static let profiles:  Int = 500
    static let timeline:  Int = 100
    static let reactions: Int = 1_000
}

// MARK: - Upload
enum Upload {
    static let maxConcurrentUploads: Int = 3
    static let uploadTimeoutMs:      Int = 30_000
    static let retryMaxAttempts:     Int = 3
    static let retryBaseDelayMs:     Int = 1_000
    static let maxImagesPerPost:     Int = 3
}

// MARK: - UI
enum UI {
    static let debounceSearchMs:       Int = 300
    static let debounceScrollMs:       Int = 100
    static let pageSizeTimeline:       Int = 50
    static let pageSizeSearch:         Int = 30
    static let pageSizeNotifications:  Int = 50
    static let skeletonTimelineCount:  Int = 5
    static let skeletonProfileCount:   Int = 3
    static let nip05VerifyTimeoutMs:   Int = 5_000
    static let profileFetchTimeoutMs:  Int = 10_000
    static let postMaxLength:          Int = 140
}

// MARK: - Engagement Weights
enum EngagementWeight {
    static let zap:            Double = 100
    static let customReaction: Double = 60
    static let quote:          Double = 35
    static let reply:          Double = 30
    static let repost:         Double = 25
    static let bookmark:       Double = 15
    static let like:           Double = 5
}

// MARK: - Error Messages
enum ErrorMessages {
    static let noSigningMethod     = "署名機能が利用できません"
    static let signingFailed       = "署名に失敗しました"
    static let connectionFailed    = "接続に失敗しました"
    static let requestTimeout      = "リクエストがタイムアウトしました"
    static let statusOffline       = "オフライン"
    static let statusReconnecting  = "再接続中..."
    static let statusConnected     = "接続中"
    static let statusDisconnected  = "切断"
}
