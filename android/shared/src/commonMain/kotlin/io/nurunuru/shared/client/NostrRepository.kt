package io.nurunuru.shared.client

import io.nurunuru.shared.models.*
import io.nurunuru.shared.platform.currentTimeSeconds
import io.nurunuru.shared.protocol.NostrFilter
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * High-level Nostr operations using [INostrClient].
 *
 * Platform-independent: uses only Kotlin stdlib + kotlinx.serialization + kotlinx.coroutines.
 * Mirrors the logic in Web's lib/nostr.js and lib/recommendation.js.
 */
class NostrRepository(private val client: INostrClient) {

    private val profileCache = mutableMapOf<String, UserProfile>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ─── Timeline ─────────────────────────────────────────────────────────────

    /** Fetch global timeline (recent kind-1 notes). */
    suspend fun fetchGlobalTimeline(limit: Int = 50): List<ScoredPost> {
        val filter = NostrFilter(
            kinds = listOf(NostrKind.TEXT_NOTE),
            limit = limit,
            since = currentTimeSeconds() - 3_600 // last hour
        )
        return enrichPosts(client.fetchEvents(filter, timeoutMs = 6_000))
    }

    /** Fetch timeline for followed users. */
    suspend fun fetchFollowTimeline(pubkeyHex: String, limit: Int = 50): List<ScoredPost> {
        val followList = fetchFollowList(pubkeyHex)
        if (followList.isEmpty()) return fetchGlobalTimeline(limit)

        val filter = NostrFilter(
            kinds = listOf(NostrKind.TEXT_NOTE),
            authors = followList.take(500),
            limit = limit,
            since = currentTimeSeconds() - 86_400 // last 24h
        )
        return enrichPosts(client.fetchEvents(filter, timeoutMs = 6_000))
    }

    /** NIP-50 full-text search. */
    suspend fun searchNotes(query: String, limit: Int = 30): List<ScoredPost> {
        val filter = NostrFilter(
            kinds = listOf(NostrKind.TEXT_NOTE),
            search = query,
            limit = limit
        )
        return enrichPosts(client.fetchEvents(filter, timeoutMs = 6_000))
    }

    // ─── Profiles ─────────────────────────────────────────────────────────────

    suspend fun fetchProfile(pubkeyHex: String): UserProfile? {
        profileCache[pubkeyHex]?.let { return it }

        val events = client.fetchEvents(
            NostrFilter(kinds = listOf(NostrKind.METADATA), authors = listOf(pubkeyHex), limit = 1),
            timeoutMs = 4_000
        )
        val event = events.maxByOrNull { it.createdAt } ?: return null
        return parseProfile(event).also { profileCache[pubkeyHex] = it }
    }

    suspend fun fetchProfiles(pubkeys: List<String>): Map<String, UserProfile> {
        val missing = pubkeys.filter { !profileCache.containsKey(it) }.distinct()
        if (missing.isNotEmpty()) {
            val events = client.fetchEvents(
                NostrFilter(
                    kinds = listOf(NostrKind.METADATA),
                    authors = missing.take(100),
                    limit = missing.size
                ),
                timeoutMs = 4_000
            )
            events.groupBy { it.pubkey }
                .mapValues { (_, evts) -> evts.maxByOrNull { it.createdAt }!! }
                .forEach { (pk, event) -> profileCache[pk] = parseProfile(event) }
        }
        return pubkeys.associateWith { profileCache[it] ?: UserProfile(pubkey = it) }
    }

    private fun parseProfile(event: NostrEvent): UserProfile = try {
        val obj = json.parseToJsonElement(event.content).jsonObject
        UserProfile(
            pubkey = event.pubkey,
            name = obj["name"]?.jsonPrimitive?.content,
            displayName = obj["display_name"]?.jsonPrimitive?.content,
            about = obj["about"]?.jsonPrimitive?.content,
            picture = obj["picture"]?.jsonPrimitive?.content,
            nip05 = obj["nip05"]?.jsonPrimitive?.content,
            banner = obj["banner"]?.jsonPrimitive?.content,
            lud16 = obj["lud16"]?.jsonPrimitive?.content,
            website = obj["website"]?.jsonPrimitive?.content
        )
    } catch (e: Exception) {
        UserProfile(pubkey = event.pubkey)
    }

    // ─── Follow / Contact list ────────────────────────────────────────────────

    suspend fun fetchFollowList(pubkeyHex: String): List<String> {
        val events = client.fetchEvents(
            NostrFilter(kinds = listOf(NostrKind.CONTACT_LIST), authors = listOf(pubkeyHex), limit = 1),
            timeoutMs = 4_000
        )
        return events.maxByOrNull { it.createdAt }?.getTagValues("p") ?: emptyList()
    }

    // ─── User notes ───────────────────────────────────────────────────────────

    suspend fun fetchUserNotes(pubkeyHex: String, limit: Int = 30): List<ScoredPost> {
        val filter = NostrFilter(
            kinds = listOf(NostrKind.TEXT_NOTE),
            authors = listOf(pubkeyHex),
            limit = limit
        )
        return enrichPosts(client.fetchEvents(filter, timeoutMs = 5_000))
    }

    // ─── Reactions ────────────────────────────────────────────────────────────

    /** Returns map of eventId → like count. */
    suspend fun fetchReactions(eventIds: List<String>): Map<String, Int> {
        if (eventIds.isEmpty()) return emptyMap()
        val events = client.fetchEvents(
            NostrFilter(kinds = listOf(NostrKind.REACTION), tags = mapOf("e" to eventIds), limit = 500),
            timeoutMs = 3_000
        )
        return events
            .groupBy { it.getTagValue("e") ?: "" }
            .mapValues { it.value.size }
            .filterKeys { it.isNotEmpty() }
    }

    /** Returns map of eventId → repost count. */
    suspend fun fetchReposts(eventIds: List<String>): Map<String, Int> {
        if (eventIds.isEmpty()) return emptyMap()
        val events = client.fetchEvents(
            NostrFilter(kinds = listOf(NostrKind.REPOST), tags = mapOf("e" to eventIds), limit = 500),
            timeoutMs = 3_000
        )
        return events
            .groupBy { it.getTagValue("e") ?: "" }
            .mapValues { it.value.size }
            .filterKeys { it.isNotEmpty() }
    }

    /** Returns map of eventId → reply count. */
    suspend fun fetchReplyCounts(eventIds: List<String>): Map<String, Int> {
        if (eventIds.isEmpty()) return emptyMap()
        val events = client.fetchEvents(
            NostrFilter(kinds = listOf(NostrKind.TEXT_NOTE), tags = mapOf("e" to eventIds), limit = 500),
            timeoutMs = 3_000
        )
        return events
            .groupBy { it.getTagValue("e") ?: "" }
            .mapValues { it.value.size }
            .filterKeys { it.isNotEmpty() }
    }

    // ─── DMs ─────────────────────────────────────────────────────────────────

    suspend fun fetchDmConversations(pubkeyHex: String): List<DmConversation> = coroutineScope {
        val received = async {
            client.fetchEvents(
                NostrFilter(kinds = listOf(NostrKind.ENCRYPTED_DM), tags = mapOf("p" to listOf(pubkeyHex)), limit = 200),
                5_000
            )
        }
        val sent = async {
            client.fetchEvents(
                NostrFilter(kinds = listOf(NostrKind.ENCRYPTED_DM), authors = listOf(pubkeyHex), limit = 200),
                5_000
            )
        }
        val allEvents = received.await() + sent.await()

        val conversations = mutableMapOf<String, MutableList<NostrEvent>>()
        for (event in allEvents) {
            val partner = if (event.pubkey == pubkeyHex) {
                event.getTagValue("p") ?: continue
            } else event.pubkey
            conversations.getOrPut(partner) { mutableListOf() }.add(event)
        }

        val profiles = fetchProfiles(conversations.keys.toList())
        conversations.map { (partnerKey, events) ->
            val last = events.maxByOrNull { it.createdAt }!!
            DmConversation(
                partnerPubkey = partnerKey,
                partnerProfile = profiles[partnerKey],
                lastMessageTime = last.createdAt
            )
        }.sortedByDescending { it.lastMessageTime }
    }

    suspend fun fetchDmMessages(
        myPubkeyHex: String,
        partnerPubkeyHex: String,
        decryptFn: (String, String) -> String?
    ): List<DmMessage> {
        val events = client.fetchEvents(
            NostrFilter(
                kinds = listOf(NostrKind.ENCRYPTED_DM),
                authors = listOf(myPubkeyHex, partnerPubkeyHex),
                limit = 100
            ),
            5_000
        )
        return events
            .filter { event ->
                val pTag = event.getTagValue("p") ?: return@filter false
                (event.pubkey == myPubkeyHex && pTag == partnerPubkeyHex) ||
                    (event.pubkey == partnerPubkeyHex && pTag == myPubkeyHex)
            }
            .mapNotNull { event ->
                val isMine = event.pubkey == myPubkeyHex
                val counterparty = if (isMine) partnerPubkeyHex else event.pubkey
                val decrypted = decryptFn(counterparty, event.content) ?: return@mapNotNull null
                DmMessage(event = event, content = decrypted, isMine = isMine, timestamp = event.createdAt)
            }
            .sortedBy { it.timestamp }
    }

    // ─── Write actions ────────────────────────────────────────────────────────

    suspend fun likePost(eventId: String): Boolean = client.publishReaction(eventId, "+")
    suspend fun repostPost(eventId: String): Boolean = client.publishRepost(eventId)

    suspend fun publishNote(content: String, replyToId: String? = null): NostrEvent? {
        val tags = mutableListOf<List<String>>()
        if (replyToId != null) tags.add(listOf("e", replyToId, "", "reply"))
        return client.publishNote(content, tags)
    }

    suspend fun sendDm(recipientPubkeyHex: String, content: String): Boolean =
        client.sendEncryptedDm(recipientPubkeyHex, content)

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private suspend fun enrichPosts(events: List<NostrEvent>): List<ScoredPost> {
        val notes = events.filter { it.kind == NostrKind.TEXT_NOTE }
            .sortedByDescending { it.createdAt }
        if (notes.isEmpty()) return emptyList()

        val profiles = fetchProfiles(notes.map { it.pubkey }.distinct())
        return notes.map { event -> ScoredPost(event = event, profile = profiles[event.pubkey]) }
    }
}
