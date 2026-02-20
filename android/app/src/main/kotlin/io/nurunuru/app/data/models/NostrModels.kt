package io.nurunuru.app.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class NostrEvent(
    val id: String = "",
    val pubkey: String = "",
    @SerialName("created_at") val createdAt: Long = 0L,
    val kind: Int = 1,
    val tags: List<List<String>> = emptyList(),
    val content: String = "",
    val sig: String = ""
) {
    fun getTagValue(tagName: String): String? =
        tags.firstOrNull { it.firstOrNull() == tagName }?.getOrNull(1)

    fun getTagValues(tagName: String): List<String> =
        tags.filter { it.firstOrNull() == tagName }.mapNotNull { it.getOrNull(1) }
}

@Serializable
data class UserProfile(
    val pubkey: String = "",
    val name: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val about: String? = null,
    val picture: String? = null,
    val nip05: String? = null,
    val banner: String? = null,
    val lud16: String? = null,
    val website: String? = null,
    // runtime-only: set after NIP-05 verification, not from JSON
    val nip05Verified: Boolean = false
) {
    val displayedName: String
        get() = displayName?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: pubkey.take(12) + "..."
}

data class ScoredPost(
    val event: NostrEvent,
    val score: Double = 0.0,
    val profile: UserProfile? = null,
    val likeCount: Int = 0,
    val repostCount: Int = 0,
    val replyCount: Int = 0,
    val zapCount: Int = 0,
    val zapSats: Long = 0L,
    val isLiked: Boolean = false,
    val isReposted: Boolean = false,
    val isZapped: Boolean = false,
    val quotedPost: ScoredPost? = null
)

data class DmConversation(
    val partnerPubkey: String,
    val partnerProfile: UserProfile? = null,
    val lastMessage: String = "",
    val lastMessageTime: Long = 0L,
    val unreadCount: Int = 0
)

data class DmMessage(
    val event: NostrEvent,
    val content: String,
    val isMine: Boolean,
    val timestamp: Long
)

// Default relay list matching web version (CLAUDE.md)
val DEFAULT_RELAYS = listOf(
    "wss://yabu.me",
    "wss://relay-jp.nostr.wirednet.jp",
    "wss://r.kojira.io",
    "wss://relay.damus.io"
)

/**
 * Nostr event kind constants.
 * Covers all NIPs supported by the web version:
 * NIP-01, NIP-04, NIP-09, NIP-10, NIP-17, NIP-18, NIP-23, NIP-25, NIP-42, NIP-51, NIP-57, NIP-59, NIP-65
 */
object NostrKind {
    const val METADATA = 0
    const val TEXT_NOTE = 1
    const val RECOMMEND_SERVER = 2
    const val CONTACT_LIST = 3
    const val ENCRYPTED_DM = 4          // NIP-04
    const val DELETION = 5              // NIP-09
    const val REPOST = 6                // NIP-18
    const val REACTION = 7              // NIP-25
    const val SEALED_DM = 13            // NIP-17
    const val DM_GIFT_WRAP = 1059       // NIP-59
    const val MUTE_LIST = 10000         // NIP-51
    const val RELAY_LIST = 10002        // NIP-65
    const val AUTH = 22242              // NIP-42
    const val ZAP_REQUEST = 9734        // NIP-57
    const val ZAP_RECEIPT = 9735        // NIP-57
    const val LONG_FORM = 30023         // NIP-23
}
