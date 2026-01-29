package com.example.nostr.nostr.event

/**
 * Represents an unsigned Nostr event that needs to be signed before publishing
 */
data class UnsignedEvent(
    val pubkey: String,
    val createdAt: Long = System.currentTimeMillis() / 1000,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String
) {
    /**
     * Get the serialized form for signing
     */
    fun serialize(): String {
        return NostrEvent.serializeForSigning(pubkey, createdAt, kind, tags, content)
    }

    /**
     * Calculate the event ID
     */
    fun calculateId(): String {
        return NostrEvent.calculateId(serialize())
    }

    /**
     * Convert to a signed event
     */
    fun toSignedEvent(signature: String): NostrEvent {
        val id = calculateId()
        return NostrEvent(
            id = id,
            pubkey = pubkey,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = signature
        )
    }

    companion object {
        /**
         * Create a text note event (kind 1)
         */
        fun textNote(pubkey: String, content: String, tags: List<List<String>> = emptyList()): UnsignedEvent {
            return UnsignedEvent(
                pubkey = pubkey,
                kind = EventKind.TEXT_NOTE,
                tags = tags,
                content = content
            )
        }

        /**
         * Create a reply event (kind 1 with e and p tags)
         */
        fun reply(
            pubkey: String,
            content: String,
            replyToEvent: NostrEvent,
            rootEventId: String? = null
        ): UnsignedEvent {
            val tags = mutableListOf<List<String>>()

            // Add root event tag
            val rootId = rootEventId ?: replyToEvent.getTagValue("e") ?: replyToEvent.id
            tags.add(listOf("e", rootId, "", "root"))

            // Add reply event tag
            if (replyToEvent.id != rootId) {
                tags.add(listOf("e", replyToEvent.id, "", "reply"))
            } else {
                tags[0] = listOf("e", rootId, "", "reply")
            }

            // Add p tag for the author being replied to
            tags.add(listOf("p", replyToEvent.pubkey))

            return UnsignedEvent(
                pubkey = pubkey,
                kind = EventKind.TEXT_NOTE,
                tags = tags,
                content = content
            )
        }

        /**
         * Create a reaction event (kind 7)
         */
        fun reaction(
            pubkey: String,
            targetEvent: NostrEvent,
            content: String = "+"
        ): UnsignedEvent {
            return UnsignedEvent(
                pubkey = pubkey,
                kind = EventKind.REACTION,
                tags = listOf(
                    listOf("e", targetEvent.id),
                    listOf("p", targetEvent.pubkey)
                ),
                content = content
            )
        }

        /**
         * Create a repost event (kind 6)
         */
        fun repost(
            pubkey: String,
            targetEvent: NostrEvent,
            relay: String = ""
        ): UnsignedEvent {
            return UnsignedEvent(
                pubkey = pubkey,
                kind = EventKind.REPOST,
                tags = listOf(
                    listOf("e", targetEvent.id, relay),
                    listOf("p", targetEvent.pubkey)
                ),
                content = ""
            )
        }

        /**
         * Create a delete event (kind 5 - NIP-09)
         */
        fun delete(
            pubkey: String,
            eventIds: List<String>,
            reason: String = ""
        ): UnsignedEvent {
            val tags = eventIds.map { listOf("e", it) }
            return UnsignedEvent(
                pubkey = pubkey,
                kind = EventKind.DELETE,
                tags = tags,
                content = reason
            )
        }

        /**
         * Create a metadata event (kind 0)
         */
        fun metadata(
            pubkey: String,
            name: String? = null,
            about: String? = null,
            picture: String? = null,
            nip05: String? = null,
            lud16: String? = null,
            banner: String? = null
        ): UnsignedEvent {
            val metadata = buildMap {
                name?.let { put("name", it) }
                about?.let { put("about", it) }
                picture?.let { put("picture", it) }
                nip05?.let { put("nip05", it) }
                lud16?.let { put("lud16", it) }
                banner?.let { put("banner", it) }
            }
            val content = metadata.entries.joinToString(",", "{", "}") { (k, v) ->
                "\"$k\":\"${v.replace("\"", "\\\"")}\""
            }
            return UnsignedEvent(
                pubkey = pubkey,
                kind = EventKind.METADATA,
                tags = emptyList(),
                content = content
            )
        }

        /**
         * Create a contact list event (kind 3 - NIP-02)
         */
        fun contactList(
            pubkey: String,
            contacts: List<Contact>,
            relayUrls: Map<String, RelayPolicy> = emptyMap()
        ): UnsignedEvent {
            val tags = contacts.map { contact ->
                listOfNotNull("p", contact.pubkey, contact.relay, contact.petname)
            }
            val content = if (relayUrls.isNotEmpty()) {
                relayUrls.entries.joinToString(",", "{", "}") { (url, policy) ->
                    "\"$url\":{\"read\":${policy.read},\"write\":${policy.write}}"
                }
            } else ""

            return UnsignedEvent(
                pubkey = pubkey,
                kind = EventKind.CONTACT_LIST,
                tags = tags,
                content = content
            )
        }
    }
}

/**
 * Represents a contact in a contact list
 */
data class Contact(
    val pubkey: String,
    val relay: String? = null,
    val petname: String? = null
)

/**
 * Represents relay read/write policy
 */
data class RelayPolicy(
    val read: Boolean = true,
    val write: Boolean = true
)
