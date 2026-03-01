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

    fun isProtected(): Boolean = tags.any { it.size == 1 && it[0] == "-" }
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
    val birthday: String? = null
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
    val zapAmount: Long = 0L,
    val repostCount: Int = 0,
    val replyCount: Int = 0,
    val isLiked: Boolean = false,
    val isReposted: Boolean = false,
    val badges: List<String> = emptyList(),
    val quotedPost: ScoredPost? = null,
    val repostedBy: UserProfile? = null,
    val repostTime: Long? = null
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

@Serializable
data class BadgeInfo(
    val ref: String,
    val awardEventId: String? = null,
    val name: String = "",
    val image: String = "",
    val description: String = ""
)

data class MuteListData(
    val pubkeys: List<String> = emptyList(),
    val eventIds: List<String> = emptyList(),
    val hashtags: List<String> = emptyList(),
    val words: List<String> = emptyList()
)

data class ImportResult(
    val total: Int,
    val success: Int,
    val failed: Int,
    val skipped: Int
)

@Serializable
data class EmojiInfo(
    val shortcode: String,
    val url: String
)

@Serializable
data class EmojiSet(
    val pointer: String,
    val name: String,
    val author: String,
    val dTag: String,
    val emojiCount: Int,
    val emojis: List<EmojiInfo> = emptyList()
)

@Serializable
data class Nip65Relay(
    val url: String,
    val read: Boolean = true,
    val write: Boolean = true
)

// Default relay list matching web version
val DEFAULT_RELAYS = listOf(
    "wss://yabu.me",
    "wss://relay-jp.nostr.wirednet.jp",
    "wss://r.kojira.io",
    "wss://relay.damus.io"
)

// Nostr event kinds - synced with web version lib/constants.js NOSTR_KINDS
object NostrKind {
    const val METADATA = 0
    const val TEXT_NOTE = 1
    const val RECOMMEND_SERVER = 2
    const val CONTACT_LIST = 3
    const val ENCRYPTED_DM = 4
    const val DELETION = 5
    const val REPOST = 6
    const val REACTION = 7
    const val BADGE_AWARD = 8
    const val SEALED_DM = 13
    const val DIRECT_MESSAGE = 14       // NIP-17 chat message
    const val FILE_MESSAGE = 15         // NIP-17 file message
    const val GENERIC_REPOST = 16
    const val CHANNEL_CREATE = 40
    const val CHANNEL_META = 41
    const val CHANNEL_MESSAGE = 42
    const val CHANNEL_HIDE = 43
    const val CHANNEL_MUTE = 44
    const val VANISH_REQUEST = 62       // NIP-62 request to vanish
    const val DM_GIFT_WRAP = 1059
    const val REPORT = 1984
    const val LABEL = 1985              // Birdwatch
    const val NIP98_AUTH = 27235
    const val BLOSSOM_AUTH = 24242
    const val CLIENT_AUTH = 22242       // NIP-42 relay authentication
    const val ZAP_REQUEST = 9734
    const val ZAP_RECEIPT = 9735
    const val MUTE_LIST = 10000
    const val PIN_LIST = 10001
    const val RELAY_LIST = 10002
    const val BOOKMARKS = 10003
    const val COMMUNITIES = 10004
    const val PUBLIC_CHATS = 10005
    const val BLOCKED_RELAYS = 10006
    const val SEARCH_RELAYS = 10007
    const val USER_GROUPS = 10009
    const val INTERESTS = 10015
    const val EMOJI_LIST = 10030
    const val DM_RELAY_LIST = 10050     // NIP-17 DM receiving relay list
    const val LONG_FORM = 30023
    const val DRAFT_LONG_FORM = 30024
    const val EMOJI_SET = 30030
    const val BADGE_DEFINITION = 30009
    const val PROFILE_BADGES = 30008
    const val VIDEO_LOOP = 34236
    // Chronostr (calendar/scheduler)
    const val CALENDAR_RSVP = 31925
    const val DATE_CANDIDATE = 31926
    const val TIME_BASED_EVENT = 31927
    const val CHRONOSTR_EVENT = 31928
}
