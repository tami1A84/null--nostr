package io.nurunuru.app.data

import android.util.Log
import io.nurunuru.app.data.models.*
import io.nurunuru.app.data.prefs.AppPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.pow

private const val PROFILE_CACHE_MAX = 500
private const val PROFILE_CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes

/**
 * High-level Nostr operations.
 * Uses NostrClient for all relay communication.
 *
 * Recommendation algorithm ported from web version (lib/recommendation.js):
 *   Engagement weights: Zap=100, Repost=25, Like=5
 *   Time decay: half-life 6 hours
 *   Freshness boost: <1h → 1.5x, <6h → 1.2x
 */
private const val REPO_TAG = "NostrRepository"

class NostrRepository(
    private val client: NostrClient,
    private val prefs: AppPreferences
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    // For serializing events to send to LNURL endpoints (all fields required)
    private val eventJson = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    // Lightweight OkHttp client for NIP-05 HTTPS verification (no WebSocket needed)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // Thread-safe LRU profile cache: pubkey → (profile, fetchedAt)
    private val profileCache = LinkedHashMap<String, Pair<UserProfile, Long>>(
        PROFILE_CACHE_MAX, 0.75f, true // accessOrder=true for LRU
    )
    private val cacheMutex = Mutex()

    // ─── Timeline ─────────────────────────────────────────────────────────────

    /** Fetch global timeline (recent text notes, scored by recommendation algorithm). */
    suspend fun fetchGlobalTimeline(limit: Int = 100): List<ScoredPost> {
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.TEXT_NOTE),
            limit = limit,
            since = System.currentTimeMillis() / 1000 - 7200 // last 2 hours
        )
        val events = client.fetchEvents(filter, timeoutMs = 10_000)
        return enrichPosts(events)
    }

    /** Fetch timeline for followed users, scored by recommendation algorithm. */
    suspend fun fetchFollowTimeline(pubkeyHex: String, limit: Int = 100): List<ScoredPost> {
        val followList = fetchFollowList(pubkeyHex)
        if (followList.isEmpty()) return fetchGlobalTimeline(limit)

        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.TEXT_NOTE),
            authors = followList.take(500),
            limit = limit,
            since = System.currentTimeMillis() / 1000 - 86400 // last 24h
        )
        val events = client.fetchEvents(filter, timeoutMs = 10_000)
        return enrichPosts(events)
    }

    /** Search for notes by text (NIP-50). */
    suspend fun searchNotes(query: String, limit: Int = 30): List<ScoredPost> {
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.TEXT_NOTE),
            search = query,
            limit = limit
        )
        val events = client.fetchEvents(filter, timeoutMs = 8_000)
        return enrichPosts(events)
    }

    // ─── Profiles ─────────────────────────────────────────────────────────────

    suspend fun fetchProfile(pubkeyHex: String): UserProfile? {
        getCachedProfile(pubkeyHex)?.let { return it }

        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.METADATA),
            authors = listOf(pubkeyHex),
            limit = 1
        )
        val events = client.fetchEvents(filter, timeoutMs = 5_000)
        val event = events.maxByOrNull { it.createdAt } ?: return null
        val profile = parseProfile(event)
        putCachedProfile(pubkeyHex, profile)
        return profile
    }

    suspend fun fetchProfiles(pubkeys: List<String>): Map<String, UserProfile> {
        val missing = pubkeys.filter { getCachedProfile(it) == null }.distinct()
        if (missing.isNotEmpty()) {
            val filter = NostrClient.Filter(
                kinds = listOf(NostrKind.METADATA),
                authors = missing.take(100),
                limit = missing.size
            )
            val events = client.fetchEvents(filter, timeoutMs = 5_000)
            events.groupBy { it.pubkey }
                .mapValues { (_, evts) -> evts.maxByOrNull { it.createdAt }!! }
                .forEach { (pk, event) -> putCachedProfile(pk, parseProfile(event)) }
        }
        return pubkeys.associateWith { getCachedProfile(it) ?: UserProfile(pubkey = it) }
    }

    private suspend fun getCachedProfile(pubkey: String): UserProfile? =
        cacheMutex.withLock {
            val entry = profileCache[pubkey] ?: return@withLock null
            val (profile, fetchedAt) = entry
            if (System.currentTimeMillis() - fetchedAt > PROFILE_CACHE_TTL_MS) {
                profileCache.remove(pubkey)
                return@withLock null
            }
            profile
        }

    private suspend fun putCachedProfile(pubkey: String, profile: UserProfile) =
        cacheMutex.withLock {
            if (profileCache.size >= PROFILE_CACHE_MAX) {
                profileCache.entries.firstOrNull()?.let { profileCache.remove(it.key) }
            }
            profileCache[pubkey] = Pair(profile, System.currentTimeMillis())
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

    /**
     * NIP-05: Verify that <local>@<domain> maps to the given pubkey.
     * Makes a direct HTTPS GET to https://<domain>/.well-known/nostr.json?name=<local>
     * Returns updated UserProfile with nip05Verified = true on success.
     */
    suspend fun verifyNip05(profile: UserProfile): UserProfile = withContext(Dispatchers.IO) {
        val nip05 = profile.nip05 ?: return@withContext profile
        try {
            val parts = nip05.split("@")
            if (parts.size != 2) return@withContext profile
            val localPart = parts[0].ifEmpty { "_" }
            val domain = parts[1]
            val url = "https://$domain/.well-known/nostr.json?name=$localPart"
            val request = Request.Builder().url(url).build()
            val responseBody = httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext profile
                resp.body?.string() ?: return@withContext profile
            }
            val obj = json.parseToJsonElement(responseBody).jsonObject
            val names = obj["names"]?.jsonObject ?: return@withContext profile
            val mappedPubkey = names[localPart]?.jsonPrimitive?.content ?: return@withContext profile
            val verified = mappedPubkey == profile.pubkey
            if (verified) {
                val updated = profile.copy(nip05Verified = true)
                putCachedProfile(profile.pubkey, updated)
                updated
            } else {
                profile
            }
        } catch (e: Exception) {
            Log.d(REPO_TAG, "NIP-05 verify failed for ${profile.nip05}: ${e.message}")
            profile
        }
    }

    // ─── Follow List ──────────────────────────────────────────────────────────

    suspend fun fetchFollowList(pubkeyHex: String): List<String> {
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.CONTACT_LIST),
            authors = listOf(pubkeyHex),
            limit = 1
        )
        val events = client.fetchEvents(filter, timeoutMs = 5_000)
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
        val events = client.fetchEvents(filter, timeoutMs = 8_000)
        return enrichPosts(events)
    }

    // ─── Reactions (NIP-25) ───────────────────────────────────────────────────

    /** Returns map of eventId → reaction count. */
    suspend fun fetchReactions(eventIds: List<String>): Map<String, Int> {
        if (eventIds.isEmpty()) return emptyMap()
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.REACTION),
            tags = mapOf("e" to eventIds),
            limit = 500
        )
        val events = client.fetchEvents(filter, timeoutMs = 4_000)
        return events
            .groupBy { it.getTagValue("e") ?: "" }
            .mapValues { it.value.size }
            .filterKeys { it.isNotEmpty() }
    }

    private suspend fun fetchReactionCounts(eventIds: List<String>): Map<String, Int> {
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

    private suspend fun fetchRepostCounts(eventIds: List<String>): Map<String, Int> {
        if (eventIds.isEmpty()) return emptyMap()
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.REPOST),
            tags = mapOf("e" to eventIds),
            limit = 200
        )
        val events = client.fetchEvents(filter, timeoutMs = 3_000)
        return events
            .groupBy { it.getTagValue("e") ?: "" }
            .mapValues { it.value.size }
            .filterKeys { it.isNotEmpty() }
    }

    // ─── DMs ─────────────────────────────────────────────────────────────────

    suspend fun fetchDmConversations(pubkeyHex: String): List<DmConversation> {
        val receivedFilter = NostrClient.Filter(
            kinds = listOf(NostrKind.ENCRYPTED_DM),
            tags = mapOf("p" to listOf(pubkeyHex)),
            limit = 200
        )
        val sentFilter = NostrClient.Filter(
            kinds = listOf(NostrKind.ENCRYPTED_DM),
            authors = listOf(pubkeyHex),
            limit = 200
        )
        val allEvents = coroutineScope {
            val received = async { client.fetchEvents(receivedFilter, 6_000) }
            val sent = async { client.fetchEvents(sentFilter, 6_000) }
            received.await() + sent.await()
        }

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
                lastMessage = "...",
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
        val events = client.fetchEvents(filter, 6_000)

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

    // NIP-25: Like (reaction "+")
    suspend fun likePost(eventId: String, authorPubkey: String): Boolean =
        client.publishReaction(eventId, authorPubkey, "+")

    // NIP-18: Repost
    suspend fun repostPost(eventId: String, authorPubkey: String): Boolean =
        client.publishRepost(eventId, authorPubkey)

    // NIP-01: Publish text note
    suspend fun publishNote(content: String, replyToId: String? = null): NostrEvent? {
        val tags = mutableListOf<List<String>>()
        if (replyToId != null) {
            // NIP-10: reply tag
            tags.add(listOf("e", replyToId, "", "reply"))
        }
        return client.publishNote(content, tags)
    }

    // NIP-09: Delete event
    suspend fun deleteEvent(eventId: String, reason: String = ""): Boolean =
        client.publishDeletion(listOf(eventId), reason)

    // NIP-04: Send DM
    suspend fun sendDm(recipientPubkeyHex: String, content: String): Boolean =
        client.sendEncryptedDm(recipientPubkeyHex, content)

    // NIP-02: Follow / Unfollow
    suspend fun followUser(myPubkeyHex: String, targetPubkeyHex: String): Boolean {
        val current = fetchFollowList(myPubkeyHex).toMutableList()
        if (targetPubkeyHex in current) return true
        current.add(targetPubkeyHex)
        return client.publishContactList(current)
    }

    suspend fun unfollowUser(myPubkeyHex: String, targetPubkeyHex: String): Boolean {
        val current = fetchFollowList(myPubkeyHex).toMutableList()
        current.remove(targetPubkeyHex)
        return client.publishContactList(current)
    }

    suspend fun isFollowing(myPubkeyHex: String, targetPubkeyHex: String): Boolean =
        targetPubkeyHex in fetchFollowList(myPubkeyHex)

    // ─── NIP-57 Zap ───────────────────────────────────────────────────────────

    data class LnurlPayInfo(
        val callback: String,
        val minSendable: Long,
        val maxSendable: Long,
        val allowsNostr: Boolean,
        val nostrPubkey: String?
    )

    /**
     * Resolve lud16 (e.g. "alice@domain.com") to LNURL-pay info.
     * Returns null if the endpoint doesn't support NIP-57 Zap.
     */
    suspend fun fetchLnurlPayInfo(lud16: String): LnurlPayInfo? = withContext(Dispatchers.IO) {
        try {
            val parts = lud16.split("@")
            if (parts.size != 2) return@withContext null
            val (local, domain) = parts
            val url = "https://$domain/.well-known/lnurlp/$local"
            val request = Request.Builder().url(url).build()
            val body = httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string() ?: return@withContext null
            }
            val obj = json.parseToJsonElement(body).jsonObject
            LnurlPayInfo(
                callback = obj["callback"]?.jsonPrimitive?.content ?: return@withContext null,
                minSendable = obj["minSendable"]?.jsonPrimitive?.content?.toLongOrNull() ?: 1000L,
                maxSendable = obj["maxSendable"]?.jsonPrimitive?.content?.toLongOrNull() ?: 10_000_000_000L,
                allowsNostr = obj["allowsNostr"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                nostrPubkey = obj["nostrPubkey"]?.jsonPrimitive?.content
            )
        } catch (e: Exception) {
            Log.d(REPO_TAG, "fetchLnurlPayInfo failed for $lud16: ${e.message}")
            null
        }
    }

    /**
     * Create a NIP-57 kind:9734 zap request event (signed but not published to relay).
     * The event is sent to the LNURL server via HTTP to obtain a Lightning invoice.
     */
    fun createZapRequest(
        recipientPubkeyHex: String,
        eventId: String,
        msats: Long,
        comment: String,
        relays: List<String>
    ): NostrEvent? {
        val tags = buildList {
            add(listOf("p", recipientPubkeyHex))
            add(listOf("e", eventId))
            add(listOf("amount", msats.toString()))
            if (relays.isNotEmpty()) add(listOf("relays") + relays.take(3))
        }
        return client.buildSignedEvent(
            kind = NostrKind.ZAP_REQUEST,
            content = comment,
            tags = tags
        )
    }

    /**
     * Call the LNURL-pay callback to get a BOLT-11 invoice.
     * Returns the invoice string ("lnbc...") or null on failure.
     */
    suspend fun fetchZapInvoice(
        payInfo: LnurlPayInfo,
        msats: Long,
        zapRequest: NostrEvent
    ): String? = withContext(Dispatchers.IO) {
        try {
            val zapJson = eventJson.encodeToJsonElement(zapRequest).toString()
            val encodedZap = java.net.URLEncoder.encode(zapJson, "UTF-8")
            val url = "${payInfo.callback}?amount=$msats&nostr=$encodedZap"
            val request = Request.Builder().url(url).build()
            val body = httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string() ?: return@withContext null
            }
            val obj = json.parseToJsonElement(body).jsonObject
            obj["pr"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            Log.d(REPO_TAG, "fetchZapInvoice failed: ${e.message}")
            null
        }
    }

    // ─── Recommendation Algorithm (ported from web lib/recommendation.js) ─────

    /**
     * Score a post using the web version's algorithm:
     *   - Engagement weights: Zap=100, Repost=25, Like=5
     *   - Time decay: half-life 6 hours (2^(-age/6))
     *   - Freshness boost: <1h → 1.5x, <6h → 1.2x
     */
    private fun computeScore(event: NostrEvent, likes: Int, reposts: Int, zapSats: Long): Double {
        val now = System.currentTimeMillis() / 1000
        val ageHours = (now - event.createdAt).toDouble() / 3600.0

        // Time decay: half-life 6h
        val timeFactor = 2.0.pow(-ageHours / 6.0)

        // Freshness boost (from web version)
        val freshnessBoost = when {
            ageHours < 1.0 -> 1.5
            ageHours < 6.0 -> 1.2
            else -> 1.0
        }

        // Engagement weights (Zap=100, Repost=25, Like=5)
        val zapScore = (zapSats / 1000.0) * 100.0  // zap sats → normalized
        val engagementScore = zapScore + reposts * 25.0 + likes * 5.0

        return (engagementScore + 1.0) * timeFactor * freshnessBoost
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * NIP-10: Extract the ID of the event this note is replying to.
     * Checks for "reply" marker first (NIP-10 standard), then falls back to
     * the last e-tag (deprecated positional convention).
     */
    private fun NostrEvent.getReplyToEventId(): String? {
        // Look for explicit "reply" marker tag: ["e", id, relay, "reply"]
        val replyTag = tags.firstOrNull { tag ->
            tag.getOrNull(0) == "e" && tag.getOrNull(3) == "reply"
        }
        if (replyTag != null) return replyTag.getOrNull(1)

        // Fallback: if there's exactly one e-tag with no marker, it's a reply
        val eTags = tags.filter { it.getOrNull(0) == "e" }
        return if (eTags.size == 1) eTags.first().getOrNull(1) else null
    }

    /**
     * Enrich events with profiles, reaction/repost counts, reply context, and recommendation scores.
     * Fetches profiles + reactions + reposts + reply context in parallel for speed.
     */
    private suspend fun enrichPosts(events: List<NostrEvent>): List<ScoredPost> {
        val textNotes = events
            .filter { it.kind == NostrKind.TEXT_NOTE }
            .distinctBy { it.id }
        if (textNotes.isEmpty()) return emptyList()

        val eventIds = textNotes.map { it.id }

        // Collect parent event IDs for reply context
        val replyParentIds = textNotes.mapNotNull { it.getReplyToEventId() }.distinct()

        return coroutineScope {
            val profilesDeferred = async {
                fetchProfiles(textNotes.map { it.pubkey }.distinct())
            }
            val reactionsDeferred = async {
                try { fetchReactionCounts(eventIds) } catch (_: Exception) { emptyMap() }
            }
            val repostsDeferred = async {
                try { fetchRepostCounts(eventIds) } catch (_: Exception) { emptyMap() }
            }
            // Fetch parent events to get reply author pubkeys
            val replyParentEventsDeferred = async {
                if (replyParentIds.isEmpty()) return@async emptyMap<String, String>()
                try {
                    val filter = NostrClient.Filter(ids = replyParentIds, limit = replyParentIds.size)
                    val parentEvents = client.fetchEvents(filter, timeoutMs = 3_000)
                    parentEvents.associate { it.id to it.pubkey }
                } catch (_: Exception) { emptyMap() }
            }

            val profiles = profilesDeferred.await()
            val reactionCounts = reactionsDeferred.await()
            val repostCounts = repostsDeferred.await()
            val replyParentPubkeys = replyParentEventsDeferred.await() // eventId → authorPubkey

            // Fetch any reply-context profiles not already in the profiles map
            val missingReplyPubkeys = replyParentPubkeys.values
                .filter { it !in profiles }
                .distinct()
            val replyProfiles = if (missingReplyPubkeys.isNotEmpty()) {
                try { fetchProfiles(missingReplyPubkeys) } catch (_: Exception) { emptyMap() }
            } else emptyMap()
            val allProfiles = profiles + replyProfiles

            textNotes.map { event ->
                val likes = reactionCounts[event.id] ?: 0
                val reposts = repostCounts[event.id] ?: 0
                // Resolve reply context: find parent author profile
                val replyToProfile = event.getReplyToEventId()?.let { parentId ->
                    replyParentPubkeys[parentId]?.let { pubkey -> allProfiles[pubkey] }
                }
                ScoredPost(
                    event = event,
                    profile = profiles[event.pubkey],
                    likeCount = likes,
                    repostCount = reposts,
                    score = computeScore(event, likes, reposts, 0L),
                    replyToProfile = replyToProfile
                )
            }.sortedByDescending { it.score }
        }
    }
}
