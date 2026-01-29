package com.example.nostr.nostr.nip

import com.example.nostr.nostr.event.EventKind
import com.example.nostr.nostr.event.NostrEvent
import com.example.nostr.nostr.event.UnsignedEvent

/**
 * NIP-65: Relay List Metadata
 *
 * Defines how users advertise their preferred relays for discovering their content
 */
object Nip65 {

    /**
     * Relay usage type
     */
    enum class RelayUsage {
        READ,   // User reads from this relay
        WRITE,  // User writes to this relay
        BOTH    // User both reads from and writes to this relay
    }

    /**
     * Relay with usage info
     */
    data class RelayInfo(
        val url: String,
        val usage: RelayUsage
    )

    /**
     * Parse relay list from a kind 10002 event
     *
     * @param event The relay list event
     * @return List of relay info, empty if invalid
     */
    fun parseRelayList(event: NostrEvent): List<RelayInfo> {
        if (event.kind != EventKind.RELAY_LIST) return emptyList()

        return event.tags.filter { it.firstOrNull() == "r" }.mapNotNull { tag ->
            val url = tag.getOrNull(1) ?: return@mapNotNull null
            val marker = tag.getOrNull(2)

            val usage = when (marker) {
                "read" -> RelayUsage.READ
                "write" -> RelayUsage.WRITE
                else -> RelayUsage.BOTH
            }

            RelayInfo(url, usage)
        }
    }

    /**
     * Get read relays from a relay list
     */
    fun getReadRelays(relayList: List<RelayInfo>): List<String> {
        return relayList
            .filter { it.usage == RelayUsage.READ || it.usage == RelayUsage.BOTH }
            .map { it.url }
    }

    /**
     * Get write relays from a relay list
     */
    fun getWriteRelays(relayList: List<RelayInfo>): List<String> {
        return relayList
            .filter { it.usage == RelayUsage.WRITE || it.usage == RelayUsage.BOTH }
            .map { it.url }
    }

    /**
     * Create a relay list event (kind 10002)
     *
     * @param pubkey The user's pubkey
     * @param relays List of relay info
     * @return UnsignedEvent for the relay list
     */
    fun createRelayListEvent(
        pubkey: String,
        relays: List<RelayInfo>
    ): UnsignedEvent {
        val tags = relays.map { relay ->
            when (relay.usage) {
                RelayUsage.READ -> listOf("r", relay.url, "read")
                RelayUsage.WRITE -> listOf("r", relay.url, "write")
                RelayUsage.BOTH -> listOf("r", relay.url)
            }
        }

        return UnsignedEvent(
            pubkey = pubkey,
            kind = EventKind.RELAY_LIST,
            tags = tags,
            content = ""
        )
    }

    /**
     * Outbox model: Get relays to fetch content from a user
     *
     * According to the outbox model, to fetch content FROM a user,
     * you should connect to their WRITE relays
     *
     * @param relayList The user's relay list
     * @return List of relay URLs to connect to
     */
    fun getOutboxRelays(relayList: List<RelayInfo>): List<String> {
        return getWriteRelays(relayList)
    }

    /**
     * Inbox model: Get relays to send content to a user
     *
     * According to the inbox model, to send content TO a user,
     * you should connect to their READ relays
     *
     * @param relayList The user's relay list
     * @return List of relay URLs to connect to
     */
    fun getInboxRelays(relayList: List<RelayInfo>): List<String> {
        return getReadRelays(relayList)
    }
}

/**
 * NIP-17: Private Direct Messages
 *
 * Defines the gift-wrapped DM protocol
 */
object Nip17 {

    /**
     * DM relay list (kind 10050)
     */
    fun parseDMRelayList(event: NostrEvent): List<String> {
        if (event.kind != EventKind.DM_RELAY_LIST) return emptyList()

        return event.tags
            .filter { it.firstOrNull() == "relay" }
            .mapNotNull { it.getOrNull(1) }
    }

    /**
     * Create a DM relay list event (kind 10050)
     */
    fun createDMRelayListEvent(
        pubkey: String,
        relays: List<String>
    ): UnsignedEvent {
        val tags = relays.map { listOf("relay", it) }

        return UnsignedEvent(
            pubkey = pubkey,
            kind = EventKind.DM_RELAY_LIST,
            tags = tags,
            content = ""
        )
    }
}
