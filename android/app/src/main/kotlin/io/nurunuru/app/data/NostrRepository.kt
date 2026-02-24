package io.nurunuru.app.data

import io.nurunuru.app.data.models.*
import io.nurunuru.app.data.prefs.AppPreferences
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * High-level Nostr operations.
 * Uses NostrClient for all relay communication.
 */
class NostrRepository(
    private val client: NostrClient,
    private val prefs: AppPreferences
) {
    private val profileCache = mutableMapOf<String, UserProfile>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ─── Timeline ─────────────────────────────────────────────────────────────

    /** Fetch global timeline (recent text notes). */
    suspend fun fetchGlobalTimeline(limit: Int = 50): List<ScoredPost> {
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.TEXT_NOTE, NostrKind.VIDEO_LOOP),
            limit = limit,
            since = System.currentTimeMillis() / 1000 - 3600 // last hour
        )
        val events = client.fetchEvents(filter, timeoutMs = 6_000)
        return enrichPosts(events)
    }

    /** Fetch timeline for followed users. */
    suspend fun fetchFollowTimeline(pubkeyHex: String, limit: Int = 50): List<ScoredPost> {
        val followList = fetchFollowList(pubkeyHex)
        if (followList.isEmpty()) return fetchGlobalTimeline(limit)

        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.TEXT_NOTE, NostrKind.VIDEO_LOOP),
            authors = followList.take(500),
            limit = limit,
            since = System.currentTimeMillis() / 1000 - 86400 // last 24h
        )
        val events = client.fetchEvents(filter, timeoutMs = 6_000)
        return enrichPosts(events)
    }

    /** Search for notes by text (NIP-50). */
    suspend fun searchNotes(query: String, limit: Int = 30): List<ScoredPost> {
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.TEXT_NOTE),
            search = query,
            limit = limit
        )
        val events = client.fetchEvents(filter, timeoutMs = 6_000)
        return enrichPosts(events)
    }

    // ─── Profiles ─────────────────────────────────────────────────────────────

    suspend fun fetchProfile(pubkeyHex: String): UserProfile? {
        profileCache[pubkeyHex]?.let { return it }

        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.METADATA),
            authors = listOf(pubkeyHex),
            limit = 1
        )
        val events = client.fetchEvents(filter, timeoutMs = 4_000)
        val event = events.maxByOrNull { it.createdAt } ?: return null
        val profile = parseProfile(event)
        profileCache[pubkeyHex] = profile
        return profile
    }

    suspend fun fetchProfiles(pubkeys: List<String>): Map<String, UserProfile> {
        val missing = pubkeys.filter { !profileCache.containsKey(it) }.distinct()
        if (missing.isNotEmpty()) {
            val filter = NostrClient.Filter(
                kinds = listOf(NostrKind.METADATA),
                authors = missing.take(100),
                limit = missing.size
            )
            val events = client.fetchEvents(filter, timeoutMs = 4_000)
            events.groupBy { it.pubkey }
                .mapValues { (_, evts) -> evts.maxByOrNull { it.createdAt }!! }
                .forEach { (pk, event) -> profileCache[pk] = parseProfile(event) }
        }
        return pubkeys.associateWith { profileCache[it] ?: UserProfile(pubkey = it) }
    }

    private fun parseProfile(event: NostrEvent): UserProfile {
        return try {
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
    }

    // ─── Follow List ──────────────────────────────────────────────────────────

    suspend fun fetchFollowList(pubkeyHex: String): List<String> {
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.CONTACT_LIST),
            authors = listOf(pubkeyHex),
            limit = 1
        )
        val events = client.fetchEvents(filter, timeoutMs = 4_000)
        val event = events.maxByOrNull { it.createdAt } ?: return emptyList()
        return event.getTagValues("p")
    }

    // ─── User Notes ───────────────────────────────────────────────────────────

    suspend fun fetchUserNotes(pubkeyHex: String, limit: Int = 30): List<ScoredPost> {
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.TEXT_NOTE),
            authors = listOf(pubkeyHex),
            limit = limit
        )
        val events = client.fetchEvents(filter, timeoutMs = 5_000)
        return enrichPosts(events)
    }

    // ─── Reactions ────────────────────────────────────────────────────────────

    suspend fun fetchReactions(eventIds: List<String>): Map<String, Int> {
        if (eventIds.isEmpty()) return emptyMap()
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.REACTION),
            tags = mapOf("e" to eventIds),
            limit = 500
        )
        val events = client.fetchEvents(filter, timeoutMs = 3_000)
        return events
            .groupBy { it.getTagValue("e") ?: "" }
            .mapValues { it.value.size }
            .filterKeys { it.isNotEmpty() }
    }

    suspend fun fetchZaps(eventIds: List<String>): Map<String, Long> {
        if (eventIds.isEmpty()) return emptyMap()
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.ZAP_RECEIPT),
            tags = mapOf("e" to eventIds),
            limit = 500
        )
        val events = client.fetchEvents(filter, timeoutMs = 3_000)

        val zapAmounts = mutableMapOf<String, Long>()
        events.forEach { event ->
            val targetEventId = event.getTagValue("e") ?: return@forEach
            val amount = parseZapAmount(event)
            zapAmounts[targetEventId] = (zapAmounts[targetEventId] ?: 0L) + amount
        }
        return zapAmounts
    }

    private fun parseZapAmount(event: NostrEvent): Long {
        return try {
            val description = event.getTagValue("description") ?: return 0L
            val zapRequest = json.parseToJsonElement(description).jsonObject
            val tags = zapRequest["tags"] as? JsonArray ?: return 0L
            val amountTag = tags.mapNotNull { it as? JsonArray }.firstOrNull {
                it.firstOrNull()?.jsonPrimitive?.content == "amount"
            }
            amountTag?.getOrNull(1)?.jsonPrimitive?.content?.toLong() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // ─── DMs ─────────────────────────────────────────────────────────────────

    suspend fun fetchDmConversations(pubkeyHex: String): List<DmConversation> {
        // Fetch received DMs
        val receivedFilter = NostrClient.Filter(
            kinds = listOf(NostrKind.ENCRYPTED_DM),
            tags = mapOf("p" to listOf(pubkeyHex)),
            limit = 200
        )
        // Fetch sent DMs
        val sentFilter = NostrClient.Filter(
            kinds = listOf(NostrKind.ENCRYPTED_DM),
            authors = listOf(pubkeyHex),
            limit = 200
        )
        val allEvents = coroutineScope {
            val received = async { client.fetchEvents(receivedFilter, 5_000) }
            val sent = async { client.fetchEvents(sentFilter, 5_000) }
            received.await() + sent.await()
        }

        // Group by conversation partner
        val conversations = mutableMapOf<String, MutableList<NostrEvent>>()
        for (event in allEvents) {
            val partner = if (event.pubkey == pubkeyHex) {
                event.getTagValue("p") ?: continue
            } else {
                event.pubkey
            }
            conversations.getOrPut(partner) { mutableListOf() }.add(event)
        }

        val profiles = fetchProfiles(conversations.keys.toList())

        return conversations.map { (partnerKey, events) ->
            val lastEvent = events.maxByOrNull { it.createdAt }!!
            DmConversation(
                partnerPubkey = partnerKey,
                partnerProfile = profiles[partnerKey],
                lastMessage = "...", // decryption done in ViewModel
                lastMessageTime = lastEvent.createdAt,
                unreadCount = 0
            )
        }.sortedByDescending { it.lastMessageTime }
    }

    suspend fun fetchDmMessages(
        myPubkeyHex: String,
        partnerPubkeyHex: String,
        decryptFn: (String, String) -> String?
    ): List<DmMessage> {
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.ENCRYPTED_DM),
            authors = listOf(myPubkeyHex, partnerPubkeyHex),
            limit = 100
        )
        val events = client.fetchEvents(filter, 5_000)

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
                DmMessage(
                    event = event,
                    content = decrypted,
                    isMine = isMine,
                    timestamp = event.createdAt
                )
            }
            .sortedBy { it.timestamp }
    }

    // ─── Actions ──────────────────────────────────────────────────────────────

    suspend fun likePost(eventId: String): Boolean =
        client.publishReaction(eventId, "+")

    suspend fun repostPost(eventId: String): Boolean =
        client.publishRepost(eventId)

    suspend fun publishNote(content: String, replyToId: String? = null): NostrEvent? {
        val tags = mutableListOf<List<String>>()
        if (replyToId != null) tags.add(listOf("e", replyToId, "", "reply"))
        return client.publishNote(content, tags)
    }

    suspend fun sendDm(recipientPubkeyHex: String, content: String): Boolean =
        client.sendEncryptedDm(recipientPubkeyHex, content)

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private suspend fun enrichPosts(events: List<NostrEvent>): List<ScoredPost> {
        val sortedEvents = events.filter { it.kind == NostrKind.TEXT_NOTE || it.kind == NostrKind.VIDEO_LOOP }
            .sortedByDescending { it.createdAt }
        if (sortedEvents.isEmpty()) return emptyList()

        val ids = sortedEvents.map { it.id }
        val profiles = fetchProfiles(sortedEvents.map { it.pubkey }.distinct())

        val reactions = fetchReactions(ids)
        val zaps = fetchZaps(ids)

        return sortedEvents.map { event ->
            ScoredPost(
                event = event,
                profile = profiles[event.pubkey],
                likeCount = reactions[event.id] ?: 0,
                zapAmount = zaps[event.id] ?: 0L
            )
        }
    }
}
