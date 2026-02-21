package io.nurunuru.shared.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * NIP-01 Nostr event.
 * Fully serializable for relay wire format.
 */
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
    /** Return first value of a tag by name (e.g. "e", "p"). */
    fun getTagValue(tagName: String): String? =
        tags.firstOrNull { it.firstOrNull() == tagName }?.getOrNull(1)

    /** Return all values for a given tag name. */
    fun getTagValues(tagName: String): List<String> =
        tags.filter { it.firstOrNull() == tagName }.mapNotNull { it.getOrNull(1) }
}

/**
 * NIP-01 kind-0 metadata, decoded from event content JSON.
 */
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
    val website: String? = null
) {
    /** Best available display name, falling back to shortened pubkey. */
    val displayedName: String
        get() = displayName?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: pubkey.take(12) + "..."
}
