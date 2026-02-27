package io.nurunuru.app.data

import io.nurunuru.app.data.models.*
import io.nurunuru.app.data.prefs.AppPreferences
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request

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
    /** General fetchEvents method. */
    suspend fun fetchEvents(filter: NostrClient.Filter, timeoutMs: Long = 5_000): List<NostrEvent> {
        return client.fetchEvents(filter, timeoutMs)
    }

    suspend fun fetchGlobalTimeline(limit: Int = 50): List<ScoredPost> {
        val now = System.currentTimeMillis() / 1000

        // 1. Fetch recent viral content (high engagement candidate)
        val viralFilter = NostrClient.Filter(
            kinds = listOf(NostrKind.TEXT_NOTE, NostrKind.VIDEO_LOOP),
            limit = 100,
            since = now - 10800 // last 3 hours
        )

        // 2. Fetch some from global recent
        val recentFilter = NostrClient.Filter(
            kinds = listOf(NostrKind.TEXT_NOTE, NostrKind.VIDEO_LOOP),
            limit = 50,
            since = now - 1800 // last 30 mins
        )

        val allEvents = coroutineScope {
            val viral = async { client.fetchEvents(viralFilter, timeoutMs = 4000) }
            val recent = async { client.fetchEvents(recentFilter, timeoutMs = 4000) }
            (viral.await() + recent.await()).distinctBy { it.id }
        }

        val enriched = enrichPosts(allEvents)

        // Sort by the calculated engagement score (already done in enrichPosts but we can re-sort)
        // Similar to web mixing:
        // 1. Give some boost to very recent posts
        // 2. Use the engagement score (Zaps/Reposts/Likes)

        return enriched.sortedByDescending { it.score }.take(limit)
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
        val profileBadgeEvent = events.maxByOrNull { it.createdAt } ?: return emptyList()

        val badgeDefinitions = mutableListOf<NostrEvent>()
        val tags = profileBadgeEvent.tags
        for (i in tags.indices) {
            if (tags[i].getOrNull(0) == "a" && tags[i].getOrNull(1)?.startsWith("30009:") == true) {
                val ref = tags[i][1]
                val parts = ref.split(":")
                if (parts.size < 3) continue
                val creator = parts[1]
                val dTag = parts.drop(2).joinToString(":")

                val defFilter = NostrClient.Filter(
                    kinds = listOf(NostrKind.BADGE_DEFINITION),
                    authors = listOf(creator),
                    tags = mapOf("d" to listOf(dTag)),
                    limit = 1
                )
                val defEvents = client.fetchEvents(defFilter, timeoutMs = 2_000)
                defEvents.maxByOrNull { it.createdAt }?.let { badgeDefinitions.add(it) }
            }
            if (badgeDefinitions.size >= 3) break
        }
        return badgeDefinitions
    }

    suspend fun fetchProfileBadgesInfo(pubkeyHex: String): List<BadgeInfo> = coroutineScope {
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.PROFILE_BADGES),
            authors = listOf(pubkeyHex),
            limit = 1
        )
        val events = client.fetchEvents(filter, timeoutMs = 4_000)
        val profileBadgeEvent = events.maxByOrNull { it.createdAt } ?: return@coroutineScope emptyList()

        val tags = profileBadgeEvent.tags
        val badgeRequests = mutableListOf<Deferred<BadgeInfo>>()
        val seenRefs = mutableSetOf<String>()

        for (i in tags.indices) {
            if (tags[i].getOrNull(0) == "a" && tags[i].getOrNull(1)?.startsWith("30009:") == true) {
                val ref = tags[i][1]
                if (!seenRefs.contains(ref)) {
                    seenRefs.add(ref)
                    val awardEventId = if (i + 1 < tags.size && tags[i+1].getOrNull(0) == "e") tags[i+1].getOrNull(1) else null

                    val parts = ref.split(":")
                    if (parts.size >= 3) {
                        val creator = parts[1]
                        val dTag = parts.drop(2).joinToString(":")

                        badgeRequests.add(async {
                            try {
                                val defFilter = NostrClient.Filter(
                                    kinds = listOf(NostrKind.BADGE_DEFINITION),
                                    authors = listOf(creator),
                                    tags = mapOf("d" to listOf(dTag)),
                                    limit = 1
                                )
                                val defEvents = client.fetchEvents(defFilter, timeoutMs = 2_000)
                                val defEvent = defEvents.maxByOrNull { it.createdAt }

                                BadgeInfo(
                                    ref = ref,
                                    awardEventId = awardEventId,
                                    name = defEvent?.getTagValue("name") ?: dTag,
                                    image = defEvent?.getTagValue("thumb") ?: defEvent?.getTagValue("image") ?: "",
                                    description = defEvent?.getTagValue("description") ?: ""
                                )
                            } catch (e: Exception) {
                                android.util.Log.e("NostrRepository", "Error fetching badge definition: $ref", e)
                                BadgeInfo(ref = ref, awardEventId = awardEventId, name = dTag)
                            }
                        })
                    }
                }
            }
        }
        badgeRequests.awaitAll()
    }

    suspend fun fetchAwardedBadges(pubkeyHex: String, currentBadgeRefs: Set<String>): List<BadgeInfo> = coroutineScope {
        val awardFilter = NostrClient.Filter(
            kinds = listOf(NostrKind.BADGE_AWARD),
            tags = mapOf("p" to listOf(pubkeyHex)),
            limit = 50
        )
        val awardEvents = client.fetchEvents(awardFilter, timeoutMs = 5_000)

        val badgeRequests = mutableListOf<Deferred<BadgeInfo>>()
        val seenAwards = currentBadgeRefs.toMutableSet()

        for (event in awardEvents) {
            val aTag = event.tags.find { it.getOrNull(0) == "a" && it.getOrNull(1)?.startsWith("30009:") == true }
            if (aTag != null) {
                val ref = aTag[1]
                if (seenAwards.contains(ref)) continue
                seenAwards.add(ref)

                val parts = ref.split(":")
                if (parts.size >= 3) {
                    val creator = parts[1]
                    val dTag = parts.drop(2).joinToString(":")

                    badgeRequests.add(async {
                        try {
                            val defFilter = NostrClient.Filter(
                                kinds = listOf(NostrKind.BADGE_DEFINITION),
                                authors = listOf(creator),
                                tags = mapOf("d" to listOf(dTag)),
                                limit = 1
                            )
                            val defEvents = client.fetchEvents(defFilter, timeoutMs = 2_000)
                            val defEvent = defEvents.maxByOrNull { it.createdAt }

                            BadgeInfo(
                                ref = ref,
                                awardEventId = event.id,
                                name = defEvent?.getTagValue("name") ?: dTag,
                                image = defEvent?.getTagValue("thumb") ?: defEvent?.getTagValue("image") ?: "",
                                description = defEvent?.getTagValue("description") ?: ""
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("NostrRepository", "Error fetching awarded badge: $ref", e)
                            BadgeInfo(ref = ref, awardEventId = event.id, name = dTag)
                        }
                    })
                }
            }
        }
        badgeRequests.awaitAll()
    }

    suspend fun updateProfileBadges(pubkeyHex: String, badges: List<BadgeInfo>): Boolean {
        val tags = mutableListOf(listOf("d", "profile_badges"))
        for (badge in badges) {
            tags.add(listOf("a", badge.ref))
            badge.awardEventId?.let { tags.add(listOf("e", it)) }
        }

        return client.publish(
            kind = NostrKind.PROFILE_BADGES,
            content = "",
            tags = tags
        ) != null
    }

    suspend fun fetchEmojiList(pubkeyHex: String): NostrEvent? {
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.EMOJI_LIST),
            authors = listOf(pubkeyHex),
            limit = 1
        )
        val events = client.fetchEvents(filter, timeoutMs = 4_000)
        return events.maxByOrNull { it.createdAt }
    }

    suspend fun fetchEmojiSet(author: String, dTag: String): EmojiSet? {
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.EMOJI_SET),
            authors = listOf(author),
            tags = mapOf("d" to listOf(dTag)),
            limit = 1
        )
        val events = client.fetchEvents(filter, timeoutMs = 4_000)
        val event = events.maxByOrNull { it.createdAt } ?: return null

        val title = event.getTagValue("title") ?: dTag
        val emojiTags = event.tags.filter { it.getOrNull(0) == "emoji" }

        return EmojiSet(
            pointer = "30030:$author:$dTag",
            name = title,
            author = author,
            dTag = dTag,
            emojiCount = emojiTags.size,
            emojis = emojiTags.mapNotNull {
                val shortcode = it.getOrNull(1)
                val url = it.getOrNull(2)
                if (shortcode != null && url != null) EmojiInfo(shortcode, url) else null
            }
        )
    }

    suspend fun searchEmojiSets(query: String): List<EmojiSet> {
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.EMOJI_SET),
            limit = 50
        )
        val events = client.fetchEvents(filter, timeoutMs = 5_000)
        val search = query.lowercase()

        return events.mapNotNull { e ->
            val dTag = e.getTagValue("d") ?: return@mapNotNull null
            val title = e.getTagValue("title") ?: dTag
            if (query.isNotEmpty() && !title.lowercase().contains(search) && !dTag.lowercase().contains(search)) {
                return@mapNotNull null
            }

            val emojiTags = e.tags.filter { it.getOrNull(0) == "emoji" }
            if (emojiTags.isEmpty()) return@mapNotNull null

            EmojiSet(
                pointer = "30030:${e.pubkey}:$dTag",
                name = title,
                author = e.pubkey,
                dTag = dTag,
                emojiCount = emojiTags.size,
                emojis = emojiTags.take(5).mapNotNull {
                    val shortcode = it.getOrNull(1)
                    val url = it.getOrNull(2)
                    if (shortcode != null && url != null) EmojiInfo(shortcode, url) else null
                }
            )
        }.distinctBy { it.pointer }
    }

    suspend fun updateEmojiList(tags: List<List<String>>): Boolean {
        return client.publish(
            kind = NostrKind.EMOJI_LIST,
            content = "",
            tags = tags
        ) != null
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

    suspend fun fetchLightningInvoice(lud16: String, amountSats: Long, comment: String = ""): String? {
        if (!lud16.contains("@")) return null
        val (name, domain) = lud16.split("@")
        val lnurlUrl = "https://$domain/.well-known/lnurlp/$name"

        return withContext(Dispatchers.IO) {
            try {
                val okHttpClient = OkHttpClient()
                val metaRequest = Request.Builder().url(lnurlUrl).build()
                val metaResponse = okHttpClient.newCall(metaRequest).execute()
                if (!metaResponse.isSuccessful) return@withContext null

                val metaBody = metaResponse.body?.string() ?: return@withContext null
                val meta = json.parseToJsonElement(metaBody).jsonObject

                val minSendable = meta["minSendable"]?.jsonPrimitive?.long ?: 0L
                val maxSendable = meta["maxSendable"]?.jsonPrimitive?.long ?: 1000000000L
                val amountMsats = amountSats * 1000

                if (amountMsats < minSendable || amountMsats > maxSendable) return@withContext null

                val callback = meta["callback"]?.jsonPrimitive?.content ?: return@withContext null
                var callbackUrl = "$callback?amount=$amountMsats"

                val commentAllowed = meta["commentAllowed"]?.jsonPrimitive?.int ?: 0
                if (comment.isNotBlank() && comment.length <= commentAllowed) {
                    callbackUrl += "&comment=${java.net.URLEncoder.encode(comment, "UTF-8")}"
                }

                val invRequest = Request.Builder().url(callbackUrl).build()
                val invResponse = okHttpClient.newCall(invRequest).execute()
                if (!invResponse.isSuccessful) return@withContext null

                val invBody = invResponse.body?.string() ?: return@withContext null
                val invData = json.parseToJsonElement(invBody).jsonObject

                if (invData["status"]?.jsonPrimitive?.content == "ERROR") return@withContext null

                invData["pr"]?.jsonPrimitive?.content
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun fetchBirdwatchNotes(eventIds: List<String>): Map<String, List<NostrEvent>> {
        if (eventIds.isEmpty()) return emptyMap()
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.LABEL),
            tags = mapOf(
                "e" to eventIds,
                "L" to listOf("birdwatch")
            ),
            limit = 200
        )
        val events = client.fetchEvents(filter, 3_000)
        val map = mutableMapOf<String, MutableList<NostrEvent>>()
        for (event in events) {
            // Robust check: must have L=birdwatch
            if (event.tags.none { it.getOrNull(0) == "L" && it.getOrNull(1) == "birdwatch" }) continue

            // Note must point to one of requested eventIds
            val targetId = event.getTagValue("e") ?: continue
            if (targetId in eventIds) {
                map.getOrPut(targetId) { mutableListOf() }.add(event)
            }
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

    suspend fun likePost(eventId: String, emoji: String = "+", customTags: List<List<String>> = emptyList()): Boolean {
        if (customTags.isEmpty()) {
            return client.publishReaction(eventId, emoji)
        }
        val tags = mutableListOf(listOf("e", eventId))
        tags.addAll(customTags)
        return client.publish(kind = 7, content = emoji, tags = tags) != null
    }

    suspend fun repostPost(eventId: String): Boolean =
        client.publishRepost(eventId)

    suspend fun publishNote(
        content: String,
        replyToId: String? = null,
        contentWarning: String? = null,
        customTags: List<List<String>> = emptyList(),
        kind: Int = NostrKind.TEXT_NOTE
    ): NostrEvent? {
        val tags = mutableListOf<List<String>>()
        if (replyToId != null) tags.add(listOf("e", replyToId, "", "reply"))
        if (contentWarning != null) tags.add(listOf("content-warning", contentWarning))

        // Add client tag matching web
        tags.add(listOf("client", "nullnull"))
        tags.addAll(customTags)

        return client.publish(kind, content, tags)
    }

    suspend fun sendDm(recipientPubkeyHex: String, content: String): Boolean =
        client.sendEncryptedDm(recipientPubkeyHex, content)

    suspend fun deleteEvent(eventId: String, reason: String = ""): Boolean {
        return client.publish(
            kind = NostrKind.DELETION,
            content = reason,
            tags = listOf(listOf("e", eventId))
        ) != null
    }

    suspend fun reportEvent(targetPubkey: String, eventId: String?, reportType: String, content: String): Boolean {
        val tags = mutableListOf(listOf("p", targetPubkey, reportType))
        eventId?.let { tags.add(listOf("e", it, reportType)) }
        return client.publish(kind = 1984, content = content, tags = tags) != null
    }

    suspend fun publishBirdwatchLabel(eventId: String, authorPubkey: String, contextType: String, content: String, sourceUrl: String = ""): Boolean {
        val fullContent = if (sourceUrl.isNotBlank()) "$content\n\nソース: $sourceUrl" else content
        val tags = listOf(
            listOf("L", "birdwatch"),
            listOf("l", contextType, "birdwatch"),
            listOf("e", eventId),
            listOf("p", authorPubkey)
        )
        return client.publish(kind = 1985, content = fullContent, tags = tags) != null
    }

    suspend fun fetchMuteList(pubkeyHex: String): MuteListData {
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.MUTE_LIST),
            authors = listOf(pubkeyHex),
            limit = 1
        )
        val events = client.fetchEvents(filter, timeoutMs = 4_000)
        val event = events.maxByOrNull { it.createdAt } ?: return MuteListData()

        return MuteListData(
            pubkeys = event.getTagValues("p"),
            eventIds = event.getTagValues("e"),
            hashtags = event.getTagValues("t"),
            words = event.getTagValues("word")
        )
    }

    suspend fun removeFromMuteList(pubkeyHex: String, type: String, value: String): Boolean {
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.MUTE_LIST),
            authors = listOf(pubkeyHex),
            limit = 1
        )
        val events = client.fetchEvents(filter, timeoutMs = 4_000)
        val latest = events.maxByOrNull { it.createdAt } ?: return true

        val tagType = when (type) {
            "pubkey" -> "p"
            "event" -> "e"
            "hashtag" -> "t"
            "word" -> "word"
            else -> return false
        }

        val newTags = latest.tags.filter { !(it.firstOrNull() == tagType && it.getOrNull(1) == value) }
        if (newTags.size == latest.tags.size) return true

        return client.publish(
            kind = NostrKind.MUTE_LIST,
            content = latest.content,
            tags = newTags
        ) != null
    }

    suspend fun muteUser(pubkeyHex: String): Boolean {
        // NIP-51 mute list (Kind 10000)
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.MUTE_LIST),
            authors = listOf(prefs.publicKeyHex ?: return false),
            limit = 1
        )
        val events = client.fetchEvents(filter, timeoutMs = 4_000)
        val latest = events.maxByOrNull { it.createdAt }

        val tags = latest?.tags?.toMutableList() ?: mutableListOf()
        if (tags.any { it.firstOrNull() == "p" && it.getOrNull(1) == pubkeyHex }) {
            return true
        }

        tags.add(listOf("p", pubkeyHex))

        return client.publish(
            kind = NostrKind.MUTE_LIST,
            content = latest?.content ?: "",
            tags = tags
        ) != null
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
        ) != null
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
        ) != null
    }

    fun getUploadServer(): String = prefs.uploadServer
    fun setUploadServer(server: String) { prefs.uploadServer = server }

    fun getDefaultZapAmount(): Int = prefs.defaultZapAmount

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

    // ─── Backup ──────────────────────────────────────────────────────────────

    suspend fun fetchAllUserEvents(
        pubkey: String,
        onProgress: (fetched: Int, batch: Int) -> Unit
    ): List<NostrEvent> {
        val allEvents = mutableListOf<NostrEvent>()
        val seenIds = mutableSetOf<String>()
        val batchSize = 500
        var lastTimestamp = System.currentTimeMillis() / 1000
        var hasMore = true
        var batchCount = 0

        while (hasMore) {
            val filter = NostrClient.Filter(
                authors = listOf(pubkey),
                until = lastTimestamp,
                limit = batchSize
            )

            try {
                val events = client.fetchEvents(filter, timeoutMs = 10_000)

                if (events.isEmpty()) {
                    hasMore = false
                    break
                }

                var newEventsInBatch = 0
                for (event in events) {
                    if (!seenIds.contains(event.id)) {
                        seenIds.add(event.id)
                        allEvents.add(event)
                        newEventsInBatch++
                    }
                }

                val minTimestamp = events.minOf { it.createdAt }
                if (minTimestamp >= lastTimestamp) {
                    hasMore = false
                } else {
                    lastTimestamp = minTimestamp - 1
                }

                batchCount++
                onProgress(allEvents.size, batchCount)

                if (newEventsInBatch == 0) {
                    hasMore = false
                }
            } catch (e: Exception) {
                android.util.Log.e("NostrRepository", "Batch fetch failed", e)
                hasMore = false
            }
        }

        return allEvents.sortedBy { it.createdAt }
    }

    suspend fun importEventsToRelays(
        events: List<NostrEvent>,
        onProgress: (current: Int, total: Int, success: Int, failed: Int) -> Unit
    ): ImportResult {
        var success = 0
        var failed = 0
        var skipped = 0
        val myPubkey = prefs.publicKeyHex

        events.forEachIndexed { index, event ->
            // NIP-70 protection check
            if (event.isProtected() && event.pubkey != myPubkey) {
                skipped++
            } else {
                val published = client.publishEvent(event)
                if (published) {
                    success++
                } else {
                    failed++
                }
            }

            onProgress(index + 1, events.size, success, failed)
            // Small delay to avoid hammering relays
            delay(50)
        }

        return ImportResult(events.size, success, failed, skipped)
    }

    suspend fun uploadImage(fileBytes: ByteArray, mimeType: String): String? {
        val server = prefs.uploadServer
        val normalizedServer = server.removePrefix("https://").removePrefix("http://").removeSuffix("/")

        return when {
            normalizedServer == "nostr.build" -> ImageUploadUtils.uploadToNostrBuild(fileBytes, mimeType, client.getSigner())
            normalizedServer == "share.yabu.me" -> ImageUploadUtils.uploadToYabuMe(fileBytes, mimeType, client.getSigner(), server)
            else -> {
                // For custom servers, we need to decide between NIP-96 and Blossom.
                // For now, let's assume if it contains 'blossom' it's Blossom, otherwise NIP-96 (common pattern).
                // Or better, Web client treats it as Blossom if not nostr.build/yabu.
                // Re-syncing with web: web treats non-explicit servers as Blossom.
                ImageUploadUtils.uploadToBlossom(fileBytes, mimeType, client.getSigner(), server)
            }
        }
    }

    suspend fun updateRelayList(relays: List<Triple<String, Boolean, Boolean>>): Boolean {
        val tags = relays.map { (url, read, write) ->
            val marker = when {
                read && write -> null
                read -> "read"
                write -> "write"
                else -> null
            }
            if (marker != null) listOf("r", url, marker) else listOf("r", url)
        }
        return client.publish(kind = 10002, content = "", tags = tags) != null
    }

    suspend fun requestVanish(relays: List<String>?, reason: String): Boolean {
        val tags = mutableListOf<List<String>>()
        if (relays.isNullOrEmpty()) {
            tags.add(listOf("relay", "ALL_RELAYS"))
        } else {
            relays.forEach { tags.add(listOf("relay", it)) }
        }

        return client.publish(
            kind = NostrKind.VANISH_REQUEST,
            content = reason,
            tags = tags,
            targetRelays = relays
        ) != null
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
        ) != null
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
