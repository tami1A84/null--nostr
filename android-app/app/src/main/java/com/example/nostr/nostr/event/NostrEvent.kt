package com.example.nostr.nostr.event

import com.google.gson.annotations.SerializedName
import java.security.MessageDigest

/**
 * Represents a Nostr event according to NIP-01
 *
 * @property id 32-bytes lowercase hex-encoded sha256 of the serialized event data
 * @property pubkey 32-bytes lowercase hex-encoded public key of the event creator
 * @property createdAt Unix timestamp in seconds
 * @property kind Integer representing the event type
 * @property tags Array of tags
 * @property content Arbitrary string content
 * @property sig 64-bytes lowercase hex-encoded signature of the sha256 hash of the serialized event data
 */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    @SerializedName("created_at")
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String
) {
    companion object {
        /**
         * Creates a serialized event string for signing (NIP-01)
         * [0,<pubkey>,<created_at>,<kind>,<tags>,<content>]
         */
        fun serializeForSigning(
            pubkey: String,
            createdAt: Long,
            kind: Int,
            tags: List<List<String>>,
            content: String
        ): String {
            val tagsJson = tags.joinToString(",") { tag ->
                "[" + tag.joinToString(",") { "\"${escapeJson(it)}\"" } + "]"
            }
            return "[0,\"$pubkey\",$createdAt,$kind,[$tagsJson],\"${escapeJson(content)}\"]"
        }

        /**
         * Calculates the event ID from the serialized event
         */
        fun calculateId(serialized: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(serialized.toByteArray(Charsets.UTF_8))
            return hash.toHexString()
        }

        private fun escapeJson(str: String): String {
            return str
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        }
    }

    /**
     * Get a specific tag value by name
     */
    fun getTagValue(tagName: String): String? {
        return tags.find { it.firstOrNull() == tagName }?.getOrNull(1)
    }

    /**
     * Get all tag values for a specific tag name
     */
    fun getTagValues(tagName: String): List<String> {
        return tags.filter { it.firstOrNull() == tagName }.mapNotNull { it.getOrNull(1) }
    }

    /**
     * Check if this event is a reply
     */
    fun isReply(): Boolean {
        return tags.any { it.firstOrNull() == "e" }
    }

    /**
     * Get the event this is replying to (root or reply)
     */
    fun getReplyToId(): String? {
        // NIP-10: Positional "e" tags
        val eTags = tags.filter { it.firstOrNull() == "e" }
        // Marked tags take precedence
        val replyTag = eTags.find { it.getOrNull(3) == "reply" }
        if (replyTag != null) return replyTag.getOrNull(1)
        // Otherwise use last e tag
        return eTags.lastOrNull()?.getOrNull(1)
    }
}

/**
 * Event kinds according to various NIPs
 */
object EventKind {
    const val METADATA = 0                  // NIP-01: User metadata
    const val TEXT_NOTE = 1                 // NIP-01: Text note
    const val RECOMMEND_SERVER = 2          // NIP-01: Recommend relay
    const val CONTACT_LIST = 3              // NIP-02: Contact list
    const val ENCRYPTED_DM = 4              // NIP-04: Encrypted DM (deprecated)
    const val DELETE = 5                    // NIP-09: Event deletion
    const val REPOST = 6                    // NIP-18: Repost
    const val REACTION = 7                  // NIP-25: Reaction
    const val BADGE_AWARD = 8               // NIP-58: Badge award
    const val SEAL = 13                     // NIP-59: Seal (encrypted)
    const val GIFT_WRAP = 1059              // NIP-59: Gift wrap
    const val FILE_METADATA = 1063          // NIP-94: File metadata
    const val REPORT = 1984                 // NIP-56: Reporting
    const val LABEL = 1985                  // NIP-32: Labeling
    const val ZAP_REQUEST = 9734            // NIP-57: Zap request
    const val ZAP_RECEIPT = 9735            // NIP-57: Zap receipt
    const val MUTE_LIST = 10000             // NIP-51: Mute list
    const val RELAY_LIST = 10002            // NIP-65: Relay list
    const val DM_RELAY_LIST = 10050         // NIP-17: DM relay list
    const val AUTH = 22242                  // NIP-42: Client authentication
    const val BADGE_DEFINITION = 30009      // NIP-58: Badge definition
    const val PROFILE_BADGES = 30008        // NIP-58: Profile badges
}

/**
 * Extension function to convert ByteArray to hex string
 */
fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

/**
 * Extension function to convert hex string to ByteArray
 */
fun String.hexToByteArray(): ByteArray {
    check(length % 2 == 0) { "Hex string must have even length" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
