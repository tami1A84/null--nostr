package io.nurunuru.shared.models

/**
 * A post enriched with engagement counts, profile, and recommendation score.
 */
data class ScoredPost(
    val event: NostrEvent,
    val score: Double = 0.0,
    val profile: UserProfile? = null,
    val likeCount: Int = 0,
    val repostCount: Int = 0,
    val replyCount: Int = 0,
    val zapCount: Int = 0,
    val isLiked: Boolean = false,
    val isReposted: Boolean = false,
    val quotedPost: ScoredPost? = null
)

/** Summary of a DM conversation with a single counterpart. */
data class DmConversation(
    val partnerPubkey: String,
    val partnerProfile: UserProfile? = null,
    val lastMessage: String = "",
    val lastMessageTime: Long = 0L,
    val unreadCount: Int = 0
)

/** A single decrypted DM message. */
data class DmMessage(
    val event: NostrEvent,
    val content: String,
    val isMine: Boolean,
    val timestamp: Long
)

// ─── Default relay list (matches Web version) ─────────────────────────────────

val DEFAULT_RELAYS: List<String> = listOf(
    "wss://yabu.me",                       // メイン (日本)
    "wss://relay-jp.nostr.wirednet.jp",    // 日本
    "wss://r.kojira.io",                   // 日本
    "wss://relay.damus.io"                 // フォールバック
)

val SEARCH_RELAY = "wss://search.nos.today" // NIP-50 検索専用

// ─── Nostr event kinds ────────────────────────────────────────────────────────

object NostrKind {
    const val METADATA = 0
    const val TEXT_NOTE = 1
    const val RECOMMEND_SERVER = 2
    const val CONTACT_LIST = 3
    const val ENCRYPTED_DM = 4
    const val DELETION = 5
    const val REPOST = 6
    const val REACTION = 7
    const val ZAP_REQUEST = 9734
    const val ZAP_RECEIPT = 9735
    const val SEALED_DM = 13
    const val DM_GIFT_WRAP = 1059
    const val LONG_FORM = 30023
    const val BOOKMARK_LIST = 10003
    const val MUTE_LIST = 10000
}
