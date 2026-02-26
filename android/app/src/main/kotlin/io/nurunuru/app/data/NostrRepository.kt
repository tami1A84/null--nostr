package io.nurunuru.app.data

import io.nurunuru.app.data.models.*
import io.nurunuru.app.data.prefs.AppPreferences
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

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
            kinds = listOf(NostrKind.TEXT_NOTE, NostrKind.VIDEO_LOOP),
            search = query,
            limit = limit
        )
        val events = client.fetchEvents(filter, timeoutMs = 6_000)
        return enrichPosts(events)
    }

    /** Fetch a specific event by ID. */
    suspend fun fetchEvent(eventId: String): ScoredPost? {
        val filter = NostrClient.Filter(
            ids = listOf(eventId),
            limit = 1
        )
        val events = client.fetchEvents(filter, timeoutMs = 4_000)
        return enrichPosts(events).firstOrNull()
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
            val birthdayElement = obj["birthday"]
            val birthdayStr = when (birthdayElement) {
                is JsonPrimitive -> birthdayElement.content
                is JsonObject -> {
                    // NIP-123 support or custom object format
                    val month = birthdayElement["month"]?.jsonPrimitive?.content?.padStart(2, '0')
                    val day = birthdayElement["day"]?.jsonPrimitive?.content?.padStart(2, '0')
                    val year = birthdayElement["year"]?.jsonPrimitive?.content
                    if (month != null && day != null) {
                        if (year != null) "$year-$month-$day" else "$month-$day"
                    } else {
                        null
                    }
                }
                else -> null
            }

            UserProfile(
                pubkey = event.pubkey,
                name = obj["name"]?.jsonPrimitive?.content,
                displayName = obj["display_name"]?.jsonPrimitive?.content,
                about = obj["about"]?.jsonPrimitive?.content,
                picture = obj["picture"]?.jsonPrimitive?.content,
                nip05 = obj["nip05"]?.jsonPrimitive?.content,
                banner = obj["banner"]?.jsonPrimitive?.content,
                lud16 = obj["lud16"]?.jsonPrimitive?.content,
                website = obj["website"]?.jsonPrimitive?.content,
                birthday = birthdayStr
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
            kinds = listOf(NostrKind.TEXT_NOTE, NostrKind.LONG_FORM, NostrKind.VIDEO_LOOP, NostrKind.REPOST),
            authors = listOf(pubkeyHex),
            limit = limit
        )
        val events = client.fetchEvents(filter, timeoutMs = 5_000)
        return enrichPosts(events)
    }

    suspend fun fetchUserLikes(pubkeyHex: String, limit: Int = 30): List<ScoredPost> {
        val reactionFilter = NostrClient.Filter(
            kinds = listOf(NostrKind.REACTION),
            authors = listOf(pubkeyHex),
            limit = limit
        )
        val reactions = client.fetchEvents(reactionFilter, timeoutMs = 4_000)
        val eventIds = reactions.mapNotNull { it.getTagValue("e") }.distinct()
        if (eventIds.isEmpty()) return emptyList()

        val eventsFilter = NostrClient.Filter(
            ids = eventIds
        )
        val events = client.fetchEvents(eventsFilter, timeoutMs = 5_000)
        return enrichPosts(events.sortedByDescending { it.createdAt })
    }

    suspend fun fetchBadges(pubkeyHex: String): List<NostrEvent> {
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.PROFILE_BADGES),
            authors = listOf(pubkeyHex),
            limit = 1
        )
        val events = client.fetchEvents(filter, timeoutMs = 4_000)
        val awardEvent = events.maxByOrNull { it.createdAt } ?: return emptyList()

        // Tags are like ["a", "kind:pubkey:d-tag", "relay"], we want the kind 8 events
        val badgeTags = awardEvent.tags.filter { it.getOrNull(0) == "a" }

        // Extract kind, pubkey, and d-tag from kind:pubkey:d-tag
        val badgeDefinitions = mutableListOf<NostrEvent>()

        for (tag in badgeTags) {
            val identifier = tag.getOrNull(1) ?: continue
            val parts = identifier.split(":")
            if (parts.size < 3) continue

            val kind = parts[0].toIntOrNull() ?: continue
            val pubkey = parts[1]
            val dTag = parts[2]

            val defFilter = NostrClient.Filter(
                kinds = listOf(kind),
                authors = listOf(pubkey),
                tags = mapOf("d" to listOf(dTag)),
                limit = 1
            )
            val defEvents = client.fetchEvents(defFilter, timeoutMs = 2_000)
            defEvents.maxByOrNull { it.createdAt }?.let { badgeDefinitions.add(it) }

            if (badgeDefinitions.size >= 3) break
        }

        return badgeDefinitions
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

    suspend fun fetchBirdwatchNotes(eventIds: List<String>): Map<String, List<NostrEvent>> {
        if (eventIds.isEmpty()) return emptyMap()
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.LABEL),
            tags = mapOf("e" to eventIds),
            limit = 200
        )
        val events = client.fetchEvents(filter, 3_000)
        val map = mutableMapOf<String, MutableList<NostrEvent>>()
        for (event in events) {
            val targetId = event.getTagValue("e") ?: continue
            map.getOrPut(targetId) { mutableListOf() }.add(event)
        }
        return map
    }

    private fun parseZapAmount(event: NostrEvent): Long {
        return try {
            val description = event.getTagValue("description") ?: return 0L
            val zapRequest = json.parseToJsonElement(description).jsonObject
            val tagsArr = zapRequest["tags"] as? JsonArray ?: return 0L
            val amountTag = tagsArr.firstOrNull {
                it is JsonArray && it.firstOrNull()?.jsonPrimitive?.content == "amount"
            } as? JsonArray
            amountTag?.getOrNull(1)?.jsonPrimitive?.content?.toLong() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // ─── DMs ─────────────────────────────────────────────────────────────────

    suspend fun fetchDmConversations(pubkeyHex: String): List<DmConversation> {
        // Fetch received DMs (Kind 4 and Kind 1059)
        val receivedFilter = NostrClient.Filter(
            kinds = listOf(NostrKind.ENCRYPTED_DM, NostrKind.DM_GIFT_WRAP),
            tags = mapOf("p" to listOf(pubkeyHex)),
            limit = 200
        )
        // Fetch sent DMs (Kind 4)
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
        decryptNip04Fn: suspend (String, String) -> String?,
        decryptNip44Fn: suspend (String, String) -> String?
    ): List<DmMessage> {
        // NIP-17 Gift Wraps are sent from random keys, so we only filter by recipient 'p' tag.
        // We filter by conversation partner after decryption.
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.ENCRYPTED_DM, NostrKind.DM_GIFT_WRAP),
            tags = mapOf("p" to listOf(myPubkeyHex)),
            limit = 200
        )
        val events = client.fetchEvents(filter, 5_000)

        val results = mutableListOf<DmMessage>()
        for (event in events) {
            if (event.kind == NostrKind.ENCRYPTED_DM) {
                val pTag = event.getTagValue("p") ?: continue
                if ((event.pubkey == myPubkeyHex && pTag == partnerPubkeyHex) ||
                    (event.pubkey == partnerPubkeyHex && pTag == myPubkeyHex)) {
                    val isMine = event.pubkey == myPubkeyHex
                    val counterparty = if (isMine) partnerPubkeyHex else event.pubkey
                    val decrypted = decryptNip04Fn(counterparty, event.content)
                    if (decrypted != null) {
                        results.add(DmMessage(event, decrypted, isMine, event.createdAt))
                    }
                }
            } else if (event.kind == NostrKind.DM_GIFT_WRAP) {
                // Nested decryption for Gift Wrap (NIP-17)
                try {
                    val sealJson = decryptNip44Fn(event.pubkey, event.content) ?: continue
                    val seal = json.decodeFromString<NostrEvent>(sealJson)
                    if (seal.kind != 13) continue

                    val rumorJson = decryptNip44Fn(seal.pubkey, seal.content) ?: continue
                    val rumor = json.decodeFromString<NostrEvent>(rumorJson)
                    if (rumor.kind != 14) continue

                    val pTag = rumor.getTagValue("p") ?: continue
                    val isMine = rumor.pubkey == myPubkeyHex
                    val messagePartner = if (isMine) pTag else rumor.pubkey

                    if (messagePartner == partnerPubkeyHex) {
                        results.add(DmMessage(event, rumor.content, isMine, rumor.createdAt))
                    }
                } catch (e: Exception) {
                    // Ignore decryption failures
                }
            }
        }
        return results.sortedBy { it.timestamp }
    }

    // ─── Actions ──────────────────────────────────────────────────────────────

    suspend fun likePost(eventId: String): Boolean =
        client.publishReaction(eventId, "+")

    suspend fun repostPost(eventId: String): Boolean =
        client.publishRepost(eventId)

    suspend fun publishNote(
        content: String,
        replyToId: String? = null,
        contentWarning: String? = null
    ): NostrEvent? {
        val tags = mutableListOf<List<String>>()
        if (replyToId != null) tags.add(listOf("e", replyToId, "", "reply"))
        if (contentWarning != null) tags.add(listOf("content-warning", contentWarning))

        // Add client tag matching web
        tags.add(listOf("client", "nullnull"))

        return client.publishNote(content, tags)
    }

    suspend fun sendDm(recipientPubkeyHex: String, content: String): Boolean =
        client.sendEncryptedDm(recipientPubkeyHex, content)

    suspend fun deleteEvent(eventId: String, reason: String = ""): Boolean {
        return client.publish(
            kind = NostrKind.DELETION,
            content = reason,
            tags = listOf(listOf("e", eventId))
        )
    }

    suspend fun followUser(myPubkeyHex: String, targetPubkeyHex: String): Boolean {
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.CONTACT_LIST),
            authors = listOf(myPubkeyHex),
            limit = 1
        )
        val events = client.fetchEvents(filter, timeoutMs = 4_000)
        val latest = events.maxByOrNull { it.createdAt }

        val tags = latest?.tags?.toMutableList() ?: mutableListOf()
        if (tags.any { it.firstOrNull() == "p" && it.getOrNull(1) == targetPubkeyHex }) {
            return true
        }

        tags.add(listOf("p", targetPubkeyHex))

        return client.publish(
            kind = NostrKind.CONTACT_LIST,
            content = latest?.content ?: "",
            tags = tags
        )
    }

    suspend fun unfollowUser(myPubkeyHex: String, targetPubkeyHex: String): Boolean {
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.CONTACT_LIST),
            authors = listOf(myPubkeyHex),
            limit = 1
        )
        val events = client.fetchEvents(filter, timeoutMs = 4_000)
        val latest = events.maxByOrNull { it.createdAt } ?: return false

        val newTags = latest.tags.filter { !(it.firstOrNull() == "p" && it.getOrNull(1) == targetPubkeyHex) }

        return client.publish(
            kind = NostrKind.CONTACT_LIST,
            content = latest.content,
            tags = newTags
        )
    }

    fun getUploadServer(): String = prefs.uploadServer
    fun setUploadServer(server: String) { prefs.uploadServer = server }

    fun getRecentSearches(): List<String> = prefs.recentSearches
    fun saveRecentSearch(query: String) {
        val current = prefs.recentSearches
        val updated = (listOf(query) + current.filter { it != query }).take(5)
        prefs.recentSearches = updated
    }
    fun removeRecentSearch(query: String) {
        prefs.recentSearches = prefs.recentSearches.filter { it != query }
    }
    fun clearRecentSearches() {
        prefs.recentSearches = emptyList()
    }

    suspend fun uploadImage(fileBytes: ByteArray, mimeType: String): String? {
        val server = prefs.uploadServer
        return if (server == "nostr.build") {
            ImageUploadUtils.uploadToNostrBuild(fileBytes, mimeType, client.getSigner())
        } else {
            // Assume Blossom URL
            ImageUploadUtils.uploadToBlossom(fileBytes, mimeType, client.getSigner()!!, server)
        }
    }

    suspend fun updateProfile(profile: UserProfile): Boolean {
        // Fetch current profile to merge fields and avoid losing unknown fields
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.METADATA),
            authors = listOf(profile.pubkey),
            limit = 1
        )
        val events = client.fetchEvents(filter, timeoutMs = 3_000)
        val latestEvent = events.maxByOrNull { it.createdAt }

        val baseObj = if (latestEvent != null) {
            try {
                json.parseToJsonElement(latestEvent.content).jsonObject.toMutableMap()
            } catch (e: Exception) {
                mutableMapOf<String, JsonElement>()
            }
        } else {
            mutableMapOf<String, JsonElement>()
        }

        // Update fields (match web logic: remove if blank/null)
        fun updateField(key: String, value: String?) {
            if (value.isNullOrBlank()) {
                baseObj.remove(key)
            } else {
                baseObj[key] = JsonPrimitive(value)
            }
        }

        updateField("name", profile.name)
        updateField("display_name", profile.displayName ?: profile.name)
        updateField("about", profile.about)
        updateField("picture", profile.picture)
        updateField("banner", profile.banner)
        updateField("nip05", profile.nip05)
        updateField("lud16", profile.lud16)
        updateField("website", profile.website)
        updateField("birthday", profile.birthday)

        val content = JsonObject(baseObj).toString()

        return client.publish(
            kind = NostrKind.METADATA,
            content = content
        )
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private suspend fun enrichPosts(events: List<NostrEvent>): List<ScoredPost> {
        if (events.isEmpty()) return emptyList()

        val myPubkey = prefs.publicKeyHex
        val processedItems = mutableListOf<Pair<NostrEvent, NostrEvent?>>() // Original, Repost(optional)
        val pubkeysToFetch = mutableSetOf<String>()

        for (event in events) {
            if (event.kind == NostrKind.REPOST) {
                try {
                    val original = json.decodeFromString<NostrEvent>(event.content)
                    processedItems.add(original to event)
                    pubkeysToFetch.add(original.pubkey)
                    pubkeysToFetch.add(event.pubkey)
                } catch (e: Exception) {
                    // skip or handle missing content
                }
            } else {
                processedItems.add(event to null)
                pubkeysToFetch.add(event.pubkey)
            }
        }

        if (processedItems.isEmpty()) return emptyList()

        val profiles = fetchProfiles(pubkeysToFetch.toList())
        val ids = processedItems.map { it.first.id }

        // Fetch reactions and check if I liked
        val reactionsFilter = NostrClient.Filter(
            kinds = listOf(NostrKind.REACTION),
            tags = mapOf("e" to ids),
            limit = 500
        )
        val reactionEvents = client.fetchEvents(reactionsFilter, 3000)
        val reactionCounts = reactionEvents.groupBy { it.getTagValue("e") ?: "" }.mapValues { it.value.size }
        val myLikes = if (myPubkey != null) reactionEvents.filter { it.pubkey == myPubkey }.mapNotNull { it.getTagValue("e") }.toSet() else emptySet()

        // Fetch reposts and check if I reposted
        val repostsFilter = NostrClient.Filter(
            kinds = listOf(NostrKind.REPOST),
            tags = mapOf("e" to ids),
            limit = 500
        )
        val repostEvents = client.fetchEvents(repostsFilter, 3000)
        val repostCounts = repostEvents.groupBy { it.getTagValue("e") ?: "" }.mapValues { it.value.size }
        val myReposts = if (myPubkey != null) repostEvents.filter { it.pubkey == myPubkey }.mapNotNull { it.getTagValue("e") }.toSet() else emptySet()

        val zaps = fetchZaps(ids)

        return processedItems.map { (event, repost) ->
            val likeCount = reactionCounts[event.id] ?: 0
            val repostCount = repostCounts[event.id] ?: 0
            val zapAmount = zaps[event.id] ?: 0L

            // Scoring matching web weights: Zap (100), Repost (25), Like (5)
            val engagementScore = (zapAmount / 1000.0) * 100.0 + likeCount * 5.0 + repostCount * 25.0

            val ageHours = (System.currentTimeMillis() / 1000 - event.createdAt) / 3600.0
            val timeMultiplier = (24.0 - ageHours.coerceIn(0.0, 24.0)) / 24.0

            ScoredPost(
                event = event,
                profile = profiles[event.pubkey],
                likeCount = likeCount,
                repostCount = repostCount,
                zapAmount = zapAmount,
                score = engagementScore * timeMultiplier,
                isLiked = myLikes.contains(event.id),
                isReposted = myReposts.contains(event.id),
                repostedBy = if (repost != null) profiles[repost.pubkey] else null,
                repostTime = repost?.createdAt
            )
        }.sortedByDescending { it.repostTime ?: it.event.createdAt }
    }
}
