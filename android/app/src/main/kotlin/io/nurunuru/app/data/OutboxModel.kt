package io.nurunuru.app.data

import io.nurunuru.app.data.cache.LRUCache
import io.nurunuru.app.data.models.NostrEvent
import io.nurunuru.app.data.models.NostrKind

/**
 * Outbox Model Implementation (NIP-65).
 * Synced with web version: lib/outbox.js
 *
 * The Outbox Model uses kind 10002 relay list metadata to:
 * - Fetch users' posts from their WRITE relays (outbox)
 * - Send mentions to users' READ relays (inbox)
 */
class OutboxModel(private val client: NostrClient) {

    /** Dedicated relays for fetching kind 10002 relay lists */
    companion object {
        val RELAY_LIST_DISCOVERY_RELAYS = listOf(
            "wss://directory.yabu.me",
            "wss://purplepag.es"
        )
    }

    data class RelayList(
        val read: List<String> = emptyList(),
        val write: List<String> = emptyList(),
        val all: List<RelayEntry> = emptyList()
    )

    data class RelayEntry(
        val url: String,
        val read: Boolean,
        val write: Boolean
    )

    private val relayListCache = LRUCache<String, CacheEntry>(500)

    private data class CacheEntry(val data: RelayList, val timestamp: Long)

    // ─── Relay List Parsing ──────────────────────────────────────────────────

    private fun parseRelayListEvent(event: NostrEvent): RelayList {
        val read = mutableListOf<String>()
        val write = mutableListOf<String>()
        val all = mutableListOf<RelayEntry>()

        for (tag in event.tags) {
            if (tag.firstOrNull() != "r") continue
            val url = tag.getOrNull(1) ?: continue
            if (!Validation.isValidRelayUrl(url)) continue

            val marker = tag.getOrNull(2) // "read" or "write" or null (both)
            when (marker) {
                "read" -> {
                    read.add(url)
                    all.add(RelayEntry(url, read = true, write = false))
                }
                "write" -> {
                    write.add(url)
                    all.add(RelayEntry(url, read = false, write = true))
                }
                else -> {
                    read.add(url)
                    write.add(url)
                    all.add(RelayEntry(url, read = true, write = true))
                }
            }
        }

        return RelayList(read, write, all)
    }

    // ─── Cache ───────────────────────────────────────────────────────────────

    private fun getCached(pubkey: String): RelayList? {
        val entry = relayListCache.get(pubkey) ?: return null
        if (System.currentTimeMillis() - entry.timestamp > Constants.CacheDuration.RELAY_INFO) {
            relayListCache.delete(pubkey)
            return null
        }
        return entry.data
    }

    private fun setCached(pubkey: String, data: RelayList) {
        relayListCache.set(pubkey, CacheEntry(data, System.currentTimeMillis()))
    }

    // ─── Fetch Relay Lists ───────────────────────────────────────────────────

    /** Fetch relay list for a single user */
    suspend fun fetchUserRelayList(pubkey: String): RelayList {
        getCached(pubkey)?.let { return it }

        val events = client.fetchEvents(
            NostrClient.Filter(
                kinds = listOf(NostrKind.RELAY_LIST),
                authors = listOf(pubkey),
                limit = 1
            ),
            timeoutMs = 8_000
        )

        if (events.isEmpty()) {
            val empty = RelayList()
            setCached(pubkey, empty)
            return empty
        }

        val event = events.sortedByDescending { it.createdAt }.first()
        val result = parseRelayListEvent(event)
        setCached(pubkey, result)
        return result
    }

    /** Batch fetch relay lists for multiple users */
    suspend fun fetchRelayListsBatch(pubkeys: List<String>): Map<String, RelayList> {
        val result = mutableMapOf<String, RelayList>()
        val uncached = mutableListOf<String>()

        for (pubkey in pubkeys) {
            val cached = getCached(pubkey)
            if (cached != null) {
                result[pubkey] = cached
            } else {
                uncached.add(pubkey)
            }
        }

        if (uncached.isEmpty()) return result

        // Batch fetch in chunks of 50
        for (chunk in uncached.chunked(50)) {
            val events = client.fetchEvents(
                NostrClient.Filter(
                    kinds = listOf(NostrKind.RELAY_LIST),
                    authors = chunk
                ),
                timeoutMs = 10_000
            )

            // Group by author, get most recent
            val eventsByAuthor = mutableMapOf<String, NostrEvent>()
            for (event in events) {
                val existing = eventsByAuthor[event.pubkey]
                if (existing == null || event.createdAt > existing.createdAt) {
                    eventsByAuthor[event.pubkey] = event
                }
            }

            for ((pubkey, event) in eventsByAuthor) {
                val parsed = parseRelayListEvent(event)
                setCached(pubkey, parsed)
                result[pubkey] = parsed
            }

            // Cache empty results for pubkeys with no relay list
            for (pubkey in chunk) {
                if (pubkey !in result) {
                    val empty = RelayList()
                    setCached(pubkey, empty)
                    result[pubkey] = empty
                }
            }
        }

        return result
    }

    // ─── Outbox/Inbox Relays ─────────────────────────────────────────────────

    /** Get write relays (outbox) for a user */
    suspend fun getUserOutboxRelays(pubkey: String): List<String> {
        val relayList = fetchUserRelayList(pubkey)
        return relayList.write.ifEmpty { listOf("wss://yabu.me") }
    }

    /** Get read relays (inbox) for a user */
    suspend fun getUserInboxRelays(pubkey: String): List<String> {
        val relayList = fetchUserRelayList(pubkey)
        return relayList.read.ifEmpty { listOf("wss://yabu.me") }
    }

    // ─── Optimal Fetch Planning ──────────────────────────────────────────────

    /** Calculate optimal relays for fetching posts from multiple users */
    suspend fun getOptimalFetchRelays(pubkeys: List<String>): Map<String, List<String>> {
        val relayLists = fetchRelayListsBatch(pubkeys)
        val relayToPubkeys = mutableMapOf<String, MutableList<String>>()

        for (pubkey in pubkeys) {
            val relayList = relayLists[pubkey]
            val writeRelays = relayList?.write?.ifEmpty { listOf("wss://yabu.me") }
                ?: listOf("wss://yabu.me")

            for (relay in writeRelays) {
                relayToPubkeys.getOrPut(relay) { mutableListOf() }.add(pubkey)
            }
        }

        return relayToPubkeys
    }

    /** Get relays for publishing an event that mentions specific users */
    suspend fun getPublishRelaysForMentions(
        mentionedPubkeys: List<String>,
        ownWriteRelays: List<String> = emptyList()
    ): List<String> {
        val relays = ownWriteRelays.toMutableSet()

        val relayLists = fetchRelayListsBatch(mentionedPubkeys)
        for ((_, relayList) in relayLists) {
            // Add first 2 read relays from each mentioned user
            relayList.read.take(2).forEach { relays.add(it) }
        }

        return relays.filter { Validation.isValidRelayUrl(it) }
    }

    /** Clear the relay list cache */
    fun clearCache() {
        relayListCache.clear()
    }
}
