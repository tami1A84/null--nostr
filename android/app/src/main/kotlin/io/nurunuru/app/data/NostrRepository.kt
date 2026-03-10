package io.nurunuru.app.data

import io.nurunuru.app.data.cache.NostrCache
import io.nurunuru.app.data.models.*
import io.nurunuru.app.data.prefs.AppPreferences
import kotlin.math.pow
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * High-level Nostr operations.
 * Uses NostrClient for all relay communication.
 */
class NostrRepository(
    private val client: NostrClient,
    private val prefs: AppPreferences,
    private val cache: NostrCache,
    private val recommendationEngine: RecommendationEngine
) {
    /** Toggle for using the Rust core for heavy operations. */
    private var useRustCore: Boolean = true

    /**
     * 直近の fetchRecommendedFromMainRelay で構築したスコアリングコンテキストをキャッシュ。
     * ライブ新着投稿のスコアリングに再利用することで、リレーへの追加フェッチを避ける。
     */
    @Volatile
    private var cachedScoringContext: RecommendationEngine.ScoringContext? = null

    fun setUseRustCore(enabled: Boolean) {
        useRustCore = enabled
    }

    fun recordEngagement(action: String, authorPubkey: String) {
        client.recordEngagement(action, authorPubkey)
    }

    fun markNotInterested(eventId: String, authorPubkey: String) {
        client.markNotInterested(eventId, authorPubkey)
    }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private fun getOneHourAgo(): Long = System.currentTimeMillis() / 1000 - Constants.Time.HOUR_SECS
    private fun getOneDayAgo(): Long = System.currentTimeMillis() / 1000 - Constants.Time.DAY_SECS

    // ─── External Signer helpers ──────────────────────────────────────────────

    /** True when the current user signs externally (Amber / NIP-55). */
    private fun isExternalSigner(): Boolean = client.getSigner() is ExternalSigner

    /** The user's own hex pubkey. Converts npub/bech32 to hex if needed. */
    private val myPubkeyHex: String get() {
        val raw = (prefs.publicKeyHex ?: "").trim()
        if (raw.isEmpty()) return ""
        if (raw.length == 64 && raw.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return raw
        val hex = NostrKeyUtils.parsePublicKey(raw)
        if (hex != null) {
            prefs.publicKeyHex = hex
            return hex
        }
        return raw
    }

    /**
     * Sign an unsigned event JSON via the AppSigner and broadcast via rustClient.
     * Used by all write operations when [isExternalSigner] is true.
     */
    private suspend fun signAndPublish(unsignedJson: String): Boolean {
        val signedJson = client.getSigner().signEvent(unsignedJson) ?: run {
            android.util.Log.w("NostrRepository", "signAndPublish: signer returned null")
            return false
        }
        val rustClient = client.getRustClient() ?: return false
        return try {
            rustClient.publishRawEvent(signedJson)
            true
        } catch (e: Exception) {
            android.util.Log.w("NostrRepository", "signAndPublish: publish failed: ${e.message}")
            false
        }
    }

    // ─── Timeline ─────────────────────────────────────────────────────────────

    /** Fetch global timeline (recent text notes). */
    /** General fetchEvents method. */
    suspend fun fetchEvents(filter: NostrClient.Filter, timeoutMs: Long = 5_000): List<NostrEvent> {
        return client.fetchEvents(filter, timeoutMs)
    }

    suspend fun fetchRecommendedTimeline(limit: Int = 50): List<ScoredPost> =
        withContext(Dispatchers.IO) {
            try {
                fetchRecommendedFromMainRelay(limit)
            } catch (e: Exception) {
                android.util.Log.e("NostrRepository", "fetchRecommendedTimeline failed: ${e.message}", e)
                emptyList()
            }
        }

    /**
     * メインリレー1台のみからイベントを取得し、Kotlinアルゴリズム＋ミュートフィルタを適用する。
     * Web版の getDefaultRelay() 相当の動作。
     */
    private suspend fun fetchRecommendedFromMainRelay(limit: Int): List<ScoredPost> {
        val mainRelay = prefs.mainRelay
        val myPubkey = prefs.publicKeyHex ?: ""
        val oneHourAgo = getOneHourAgo()
        val threeHoursAgo = System.currentTimeMillis() / 1000 - 10800

        android.util.Log.d("NostrRepository",
            "fetchRecommendedTimeline: mainRelay=$mainRelay (always fresh, no cache)")

        // 改善1: メインリレー障害時フォールバック
        val relaysToTry = listOf(mainRelay) +
            io.nurunuru.app.data.models.DEFAULT_RELAYS.filter { it != mainRelay }.take(2)

        // 1. メインリレー（障害時はフォールバック）並列フェッチ
        val (allEvents, followList, secondDegreeFollows) = coroutineScope {
            val viralJob = async {
                var events = emptyList<NostrEvent>()
                for (relay in relaysToTry) {
                    events = try {
                        client.fetchEventsFrom(
                            listOf(relay),
                            NostrClient.Filter(
                                kinds = listOf(NostrKind.TEXT_NOTE, NostrKind.VIDEO_LOOP, NostrKind.LONG_FORM),
                                limit = 100,
                                since = oneHourAgo
                            ), timeoutMs = 5_000
                        )
                    } catch (_: Exception) { emptyList() }
                    if (events.isNotEmpty()) {
                        if (relay != mainRelay) android.util.Log.w("NostrRepository",
                            "Main relay $mainRelay failed, using fallback $relay")
                        break
                    }
                }
                events
            }
            val followListJob = async { fetchFollowList(myPubkey) }

            val viral = viralJob.await()
            val follows = followListJob.await()

            // 改善3: 2次度サンプリングを動的調整（フォロー数の1/3、最低30人）
            val secondDegree = if (follows.isNotEmpty()) {
                val sampleSize = maxOf(30, follows.size / 3)
                val sampleFollows = follows.shuffled().take(sampleSize)
                val followsOfFollows = fetchFollowListsBatch(sampleFollows)
                RecommendationEngine.extract2ndDegreeNetwork(follows, followsOfFollows)
            } else emptySet()

            val activeRelay = if (viral.isNotEmpty()) mainRelay else relaysToTry.first()
            val secondDegreePosts = if (secondDegree.isNotEmpty()) {
                client.fetchEventsFrom(
                    listOf(activeRelay),
                    NostrClient.Filter(
                        kinds = listOf(NostrKind.TEXT_NOTE, NostrKind.VIDEO_LOOP, NostrKind.LONG_FORM),
                        authors = secondDegree.take(50).toList(),
                        limit = 50,
                        since = threeHoursAgo
                    ), timeoutMs = 4_000
                )
            } else emptyList()

            Triple(
                // リプライ除外: e タグあり = 他投稿へのリプライ
                (viral + secondDegreePosts)
                    .distinctBy { it.id }
                    .filter { it.getTagValues("e").isEmpty() },
                follows.toSet(),
                secondDegree
            )
        }

        // 2. プロフィール・エンゲージメントでエンリッチ
        val enriched = enrichPosts(allEvents)

        // 3. 推薦アルゴリズムでスコアリング
        val profileMap = enriched.associate { it.event.pubkey to (it.profile ?: UserProfile(it.event.pubkey)) }
        val engagements = enriched.associate { it.event.id to RecommendationEngine.EngagementCounts(
            likes = it.likeCount,
            reposts = it.repostCount,
            replies = it.replyCount,
            // 改善2: Zap対数スケール（999sat=0問題を修正）
            zaps = if (it.zapAmount <= 0) 0
                   else (kotlin.math.ln(it.zapAmount.toDouble() / 1000.0 + 1.0) * 10).toInt().coerceAtLeast(1)
        ) }
        val scoringCtx = RecommendationEngine.ScoringContext(
            followList = followList,
            secondDegreeFollows = secondDegreeFollows,
            engagements = engagements,
            profiles = profileMap,
            userGeohash = prefs.userGeohash,
            mutedPubkeys = getCachedMuteList(myPubkey)?.pubkeys?.toSet() ?: emptySet()
        )
        // ライブ新着投稿の即時スコアリングに再利用するためキャッシュ
        cachedScoringContext = scoringCtx
        val scored = recommendationEngine.getRecommendedPosts(enriched, scoringCtx, limit)

        // 4. ミュートフィルタ（pubkey + eventId）
        val muteData = getCachedMuteList(myPubkeyHex)
        if (muteData != null &&
            (muteData.pubkeys.isNotEmpty() || muteData.eventIds.isNotEmpty())
        ) {
            val mutedPks = muteData.pubkeys.toSet()
            val mutedIds = muteData.eventIds.toSet()
            return scored.filter { it.event.pubkey !in mutedPks && it.event.id !in mutedIds }
                .also { android.util.Log.d("NostrRepository",
                    "Recommended[$mainRelay]: ${scored.size} -> ${it.size} after mute filter") }
        }
        android.util.Log.d("NostrRepository", "Recommended[$mainRelay]: ${scored.size} posts")
        return scored
    }

    suspend fun fetchGlobalTimeline(limit: Int = 50): List<ScoredPost> {
        if (useRustCore) {
            return withContext(Dispatchers.IO) {
                try {
                    val rustClient = client.getRustClient()
                        ?: return@withContext fetchGlobalTimelineLegacy(limit)

                    // Direct relay fetch: Kind 1 global timeline via Rust engine.
                    // engine.fetch_timeline(authors=None) sends a REQ to all connected
                    // relays (including wss://yabu.me) with no author filter.
                    val eventsJson = rustClient.fetchGlobalTimeline(limit.toUInt())
                    android.util.Log.d("NostrRepository", "Rust fetchGlobalTimeline: ${eventsJson.size} events from relay")

                    if (eventsJson.isEmpty()) {
                        return@withContext fetchGlobalTimelineLegacy(limit)
                    }

                    val events = eventsJson.mapNotNull { json ->
                        try { Json.decodeFromString<NostrEvent>(json) }
                        catch (e: Exception) {
                            android.util.Log.w("NostrRepository", "Event parse failed: ${e.message}")
                            null
                        }
                    }.distinctBy { it.id }
                    enrichPosts(events)
                } catch (e: Exception) {
                    android.util.Log.e("NostrRepository", "Rust fetchGlobalTimeline failed, falling back", e)
                    fetchGlobalTimelineLegacy(limit)
                }
            }
        }
        return fetchGlobalTimelineLegacy(limit)
    }

    private suspend fun fetchGlobalTimelineLegacy(limit: Int): List<ScoredPost> {
        val myPubkey = prefs.publicKeyHex ?: ""
        val oneHourAgo = getOneHourAgo()
        val threeHoursAgo = System.currentTimeMillis() / 1000 - 10800

        // 1. Fetch candidates in parallel
        val (allEvents, followList, secondDegreeFollows) = coroutineScope {
            val viralJob = async {
                client.fetchEvents(NostrClient.Filter(
                    kinds = listOf(NostrKind.TEXT_NOTE, NostrKind.VIDEO_LOOP, NostrKind.LONG_FORM),
                    limit = 100,
                    since = oneHourAgo
                ), timeoutMs = 4000)
            }
            val recentJob = async {
                client.fetchEvents(NostrClient.Filter(
                    kinds = listOf(NostrKind.TEXT_NOTE, NostrKind.VIDEO_LOOP, NostrKind.LONG_FORM),
                    limit = 50,
                    since = oneHourAgo
                ), timeoutMs = 4000)
            }
            val followListJob = async { fetchFollowList(myPubkey) }

            val viral = viralJob.await()
            val recent = recentJob.await()
            val follows = followListJob.await()

            // Build 2nd-degree network (friends of friends) — 改善3: dynamic sampling
            val secondDegree = if (follows.isNotEmpty()) {
                val sampleSize = maxOf(30, follows.size / 3)
                val sampleFollows = follows.shuffled().take(sampleSize)
                val followsOfFollows = fetchFollowListsBatch(sampleFollows)
                RecommendationEngine.extract2ndDegreeNetwork(follows, followsOfFollows)
            } else emptySet()

            // Fetch some posts from 2nd degree network
            val secondDegreePosts = if (secondDegree.isNotEmpty()) {
                client.fetchEvents(NostrClient.Filter(
                    kinds = listOf(NostrKind.TEXT_NOTE, NostrKind.VIDEO_LOOP, NostrKind.LONG_FORM),
                    authors = secondDegree.take(50).toList(),
                    limit = 50,
                    since = threeHoursAgo
                ), timeoutMs = 4000)
            } else emptyList()

            Triple(
                (viral + recent + secondDegreePosts).distinctBy { it.id },
                follows.toSet(),
                secondDegree
            )
        }

        // 2. Enrich candidates with profiles and engagement data
        val enriched = enrichPosts(allEvents)

        // 3. Score and mix using RecommendationEngine (synced with Web algorithm)
        val profileMap = enriched.associate { it.event.pubkey to (it.profile ?: UserProfile(it.event.pubkey)) }
        val engagements = enriched.associate { it.event.id to RecommendationEngine.EngagementCounts(
            likes = it.likeCount,
            reposts = it.repostCount,
            replies = it.replyCount,
            // 改善2: Zap対数スケール
            zaps = if (it.zapAmount <= 0) 0
                   else (kotlin.math.ln(it.zapAmount.toDouble() / 1000.0 + 1.0) * 10).toInt().coerceAtLeast(1)
        ) }

        val context = RecommendationEngine.ScoringContext(
            followList = followList,
            secondDegreeFollows = secondDegreeFollows,
            engagements = engagements,
            profiles = profileMap,
            userGeohash = prefs.userGeohash,
            mutedPubkeys = getCachedMuteList(myPubkey)?.pubkeys?.toSet() ?: emptySet()
        )

        return recommendationEngine.getRecommendedPosts(enriched, context, limit)
    }

    /**
     * Instant nostrdb cache-first read for following timeline.
     * Returns events already cached locally by previous relay fetches — no network call.
     * Used to show data immediately at startup before the relay fetch completes.
     */
    suspend fun fetchCachedFollowTimeline(pubkeyHex: String, limit: Int = 50): List<ScoredPost> {
        val rustClient = client.getRustClient() ?: return emptyList()
        val followList = getCachedFollowList(pubkeyHex) ?: return emptyList()
        if (followList.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val eventsJson = rustClient.queryLocal(followList.take(500), limit.toUInt())
                if (eventsJson.isEmpty()) return@withContext emptyList()
                val events = eventsJson.mapNotNull { json ->
                    try { Json.decodeFromString<NostrEvent>(json) } catch (_: Exception) { null }
                }.distinctBy { it.id }
                android.util.Log.d("NostrRepository", "nostrdb cache-first: ${events.size} events")
                enrichPosts(events)
            } catch (e: Exception) {
                android.util.Log.w("NostrRepository", "nostrdb cache-first failed: ${e.message}")
                emptyList()
            }
        }
    }

    /** Fetch timeline for followed users. */
    suspend fun fetchFollowTimeline(pubkeyHex: String, limit: Int = 50): List<ScoredPost> {
        if (useRustCore) {
            return withContext(Dispatchers.IO) {
                try {
                    val rustClient = client.getRustClient()
                        ?: return@withContext fetchFollowTimelineLegacy(pubkeyHex, limit)

                    val followList = fetchFollowList(pubkeyHex)
                    if (followList.isEmpty()) return@withContext emptyList()

                    // Direct relay fetch via Rust engine — authors filter applied at
                    // REQ level, nostrdb caches results for future query_local calls.
                    val eventsJson = rustClient.fetchFollowTimeline(
                        followList.take(500),
                        limit.toUInt()
                    )
                    android.util.Log.d(
                        "NostrRepository",
                        "Rust fetchFollowTimeline: ${eventsJson.size} events (${followList.size} authors)"
                    )

                    if (eventsJson.isEmpty()) {
                        return@withContext fetchFollowTimelineLegacy(pubkeyHex, limit)
                    }

                    val events = eventsJson.mapNotNull { json ->
                        try { Json.decodeFromString<NostrEvent>(json) }
                        catch (e: Exception) {
                            android.util.Log.w("NostrRepository", "Event parse failed: ${e.message}")
                            null
                        }
                    }.distinctBy { it.id }
                    enrichPosts(events)
                } catch (e: Exception) {
                    android.util.Log.e("NostrRepository", "Rust fetchFollowTimeline failed, falling back", e)
                    fetchFollowTimelineLegacy(pubkeyHex, limit)
                }
            }
        }
        return fetchFollowTimelineLegacy(pubkeyHex, limit)
    }

    private suspend fun fetchFollowTimelineLegacy(pubkeyHex: String, limit: Int): List<ScoredPost> {
        val followList = fetchFollowList(pubkeyHex)
        if (followList.isEmpty()) return emptyList()

        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.TEXT_NOTE, NostrKind.VIDEO_LOOP),
            authors = followList.take(500),
            limit = limit,
            since = getOneHourAgo()
        )
        val events = client.fetchEvents(filter, timeoutMs = 6_000).distinctBy { it.id }
        return enrichPosts(events)
    }

    /** Search for notes by text (NIP-50) using dedicated search relay. */
    suspend fun searchNotes(query: String, limit: Int = 30): List<ScoredPost> {
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.TEXT_NOTE, NostrKind.VIDEO_LOOP),
            search = query,
            limit = limit,
            since = getOneHourAgo()
        )
        val events = client.fetchEventsFrom(
            listOf(NostrClient.SEARCH_RELAY), filter, timeoutMs = 6_000
        )
        return enrichPosts(events)
    }

    // ─── Notifications ─────────────────────────────────────────────────────────

    /** Fetch notifications (reactions + zaps targeting the user). Cache-first, 1-day window. */
    suspend fun fetchNotifications(pubkeyHex: String, limit: Int = 50): NotificationResult {
        // キャッシュヒット時は即返す
        cache.getCachedNotifications(pubkeyHex)?.let { cached ->
            try {
                val result = json.decodeFromString<NotificationResult>(cached)
                if (result.items.isNotEmpty()) {
                    android.util.Log.d("NostrRepository", "notifications cache hit: ${result.items.size}")
                    return result
                }
            } catch (_: Exception) { }
        }

        val oneDayAgo = System.currentTimeMillis() / 1000 - Constants.Time.DAY_SECS

        // 1. Fetch reactions (#p tag targeting me)
        val reactionFilter = NostrClient.Filter(
            kinds = listOf(NostrKind.REACTION),
            tags = mapOf("p" to listOf(pubkeyHex)),
            since = oneDayAgo,
            limit = limit
        )

        // 2. Fetch zaps (#p tag targeting me)
        val zapFilter = NostrClient.Filter(
            kinds = listOf(NostrKind.ZAP_RECEIPT),
            tags = mapOf("p" to listOf(pubkeyHex)),
            since = oneDayAgo,
            limit = limit
        )

        val reactions = client.fetchEvents(reactionFilter, timeoutMs = 5_000)
        val zaps = client.fetchEvents(zapFilter, timeoutMs = 5_000)

        // 3. Build notification items
        val notificationItems = mutableListOf<NotificationItem>()
        val targetEventIds = mutableSetOf<String>()
        val notifierPubkeys = mutableSetOf<String>()

        for (event in reactions) {
            if (event.pubkey == pubkeyHex) continue // Skip self-reactions
            val targetEvent = event.getTagValue("e")
            targetEvent?.let { targetEventIds.add(it) }
            notifierPubkeys.add(event.pubkey)

            // Check for custom emoji
            val emojiTag = event.tags.firstOrNull { it.getOrNull(0) == "emoji" }
            val emojiUrl = emojiTag?.getOrNull(2)

            notificationItems.add(
                NotificationItem(
                    id = event.id,
                    pubkey = event.pubkey,
                    type = "reaction",
                    createdAt = event.createdAt,
                    targetEventId = targetEvent,
                    comment = event.content.takeIf { it != "+" },
                    emojiUrl = emojiUrl
                )
            )
        }

        for (event in zaps) {
            val targetEvent = event.getTagValue("e")
            targetEvent?.let { targetEventIds.add(it) }

            // Parse zap amount from bolt11 tag
            val bolt11 = event.getTagValue("bolt11") ?: ""
            val amount = parseBolt11Amount(bolt11)

            // Parse sender from description tag (zap request)
            val descTag = event.getTagValue("description")
            val senderPubkey = if (descTag != null) {
                try {
                    val descObj = json.parseToJsonElement(descTag).jsonObject
                    descObj["pubkey"]?.jsonPrimitive?.content
                } catch (e: Exception) { null }
            } else null

            val zapPubkey = senderPubkey ?: event.pubkey
            if (zapPubkey == pubkeyHex) continue
            notifierPubkeys.add(zapPubkey)

            val comment = if (descTag != null) {
                try {
                    json.parseToJsonElement(descTag).jsonObject["content"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                } catch (e: Exception) { null }
            } else null

            notificationItems.add(
                NotificationItem(
                    id = event.id,
                    pubkey = zapPubkey,
                    type = "zap",
                    createdAt = event.createdAt,
                    amount = amount,
                    comment = comment,
                    targetEventId = targetEvent
                )
            )
        }

        // 4. Fetch original posts
        val originalPosts = mutableMapOf<String, NostrEvent>()
        if (targetEventIds.isNotEmpty()) {
            val postsFilter = NostrClient.Filter(
                ids = targetEventIds.take(50).toList()
            )
            val posts = client.fetchEvents(postsFilter, timeoutMs = 4_000)
            posts.forEach { originalPosts[it.id] = it }
        }

        // 5. Fetch notifier profiles
        val profiles = fetchProfiles(notifierPubkeys.toList())

        // 6. Sort by time descending
        val sorted = notificationItems.sortedByDescending { it.createdAt }

        val result = NotificationResult(sorted, profiles, originalPosts)

        // 7. キャッシュに保存（1日有効）
        try {
            cache.setCachedNotifications(pubkeyHex, json.encodeToString(NotificationResult.serializer(), result))
        } catch (_: Exception) { }

        return result
    }

    companion object {
        /**
         * Parse sats amount from a bolt11 invoice string.
         * bolt11 format: lnbc<amount><multiplier>1...
         */
        fun parseBolt11Amount(bolt11: String): Long {
            if (bolt11.isBlank()) return 0
            val regex = Regex("lnbc(\\d+)([munp]?)")
            val match = regex.find(bolt11.lowercase()) ?: return 0
            val num = match.groupValues[1].toLongOrNull() ?: return 0
            val multiplier = match.groupValues[2]
            return when (multiplier) {
                "m" -> num * 100_000   // milli-BTC to sats
                "u" -> num * 100       // micro-BTC to sats
                "n" -> num / 10        // nano-BTC to sats
                "p" -> num / 10_000    // pico-BTC to sats
                "" -> num * 100_000_000 // BTC to sats
                else -> num
            }
        }
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

    fun getCachedProfile(pubkeyHex: String): UserProfile? = cache.getCachedProfile(pubkeyHex)

    suspend fun fetchProfile(pubkeyHex: String): UserProfile? {
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.METADATA),
            authors = listOf(pubkeyHex),
            limit = 1
        )
        val events = client.fetchEvents(filter, timeoutMs = 4_000)
        val event = events.maxByOrNull { it.createdAt } ?: return getCachedProfile(pubkeyHex)
        val profile = parseProfile(event)
        cache.setCachedProfile(pubkeyHex, profile)
        return profile
    }

    suspend fun fetchProfiles(pubkeys: List<String>): Map<String, UserProfile> {
        // 1. Satisfy as many as possible from the in-process Kotlin cache (free).
        val results = pubkeys.associateWith { cache.getCachedProfile(it) }.toMutableMap()
        val missing = pubkeys.filter { results[it] == null }.distinct()

        if (missing.isEmpty()) {
            return pubkeys.associateWith { results[it] ?: UserProfile(pubkey = it) }
        }

        // 2. Try Rust batch fetch: single JNI call for all missing pubkeys.
        //    The Rust engine checks nostrdb first, then fetches only the truly
        //    missing profiles from relays in one REQ.  This avoids per-profile
        //    JNI overhead when enriching a timeline of 50 posts.
        val rustClient = client.getRustClient()
        if (rustClient != null && useRustCore) {
            try {
                val ffiProfiles = withContext(Dispatchers.IO) {
                    rustClient.fetchProfiles(missing)
                }
                ffiProfiles.forEach { ffi ->
                    val profile = ffi.toUserProfile()
                    cache.setCachedProfile(ffi.pubkey, profile)
                    results[ffi.pubkey] = profile
                }
                android.util.Log.d(
                    "NostrRepository",
                    "fetchProfiles via Rust: ${ffiProfiles.size}/${missing.size} resolved"
                )
            } catch (e: Exception) {
                android.util.Log.w("NostrRepository", "Rust fetchProfiles failed, falling back: ${e.message}")
                fetchProfilesLegacy(missing, results)
            }
        } else {
            fetchProfilesLegacy(missing, results)
        }

        return pubkeys.associateWith { results[it] ?: UserProfile(pubkey = it) }
    }

    /** Legacy relay fetch for profiles — used as fallback only. */
    private suspend fun fetchProfilesLegacy(
        missing: List<String>,
        results: MutableMap<String, UserProfile?>
    ) {
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.METADATA),
            authors = missing.take(100),
            limit = missing.size
        )
        val events = client.fetchEvents(filter, timeoutMs = 4_000)
        events.groupBy { it.pubkey }
            .mapValues { (_, evts) -> evts.maxByOrNull { it.createdAt }!! }
            .forEach { (pk, event) ->
                val profile = parseProfile(event)
                cache.setCachedProfile(pk, profile)
                results[pk] = profile
            }
    }

    /** Convert a Rust FFI profile to the app-level model. */
    private fun uniffi.nurunuru.FfiUserProfile.toUserProfile() = UserProfile(
        pubkey = pubkey,
        name = name.nullIfEmpty(),
        displayName = displayName.nullIfEmpty(),
        about = about.nullIfEmpty(),
        picture = picture.nullIfEmpty(),
        nip05 = nip05.nullIfEmpty(),
        lud16 = lud16.nullIfEmpty()
        // banner, website, birthday, geohash not in FfiUserProfile — null for now
    )

    private fun String.nullIfEmpty(): String? = ifEmpty { null }

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
                birthday = birthdayStr,
                geohash = event.getTagValue("g") ?: obj["geohash"]?.jsonPrimitive?.content
            )
        } catch (e: Exception) {
            UserProfile(pubkey = event.pubkey)
        }
    }

    // ─── Follow List ──────────────────────────────────────────────────────────

    fun getCachedFollowList(pubkeyHex: String): List<String>? = cache.getCachedFollowList(pubkeyHex)

    suspend fun fetchFollowList(pubkeyHex: String): List<String> {
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.CONTACT_LIST),
            authors = listOf(pubkeyHex),
            limit = 1
        )
        val events = client.fetchEvents(filter, timeoutMs = 4_000)
        val event = events.maxByOrNull { it.createdAt } ?: return getCachedFollowList(pubkeyHex) ?: emptyList()
        val followList = event.getTagValues("p")
        cache.setCachedFollowList(pubkeyHex, followList)
        return followList
    }

    // ─── User Notes ───────────────────────────────────────────────────────────

    /** キャッシュのみ読む（ネットワーク不使用）。初回表示の即時描画に使う。 */
    fun getCachedUserNotesPosts(pubkeyHex: String): List<ScoredPost> {
        val raw = cache.getCachedUserNotes(pubkeyHex) ?: return emptyList()
        return try {
            json.decodeFromString<List<ScoredPost>>(raw).also {
                android.util.Log.d("NostrRepository", "user_notes cache-only: ${it.size}")
            }
        } catch (_: Exception) { emptyList() }
    }

    /** キャッシュのみ読む（ネットワーク不使用）。初回表示の即時描画に使う。 */
    fun getCachedUserLikesPosts(pubkeyHex: String): List<ScoredPost> {
        val raw = cache.getCachedUserLikes(pubkeyHex) ?: return emptyList()
        return try {
            json.decodeFromString<List<ScoredPost>>(raw).also {
                android.util.Log.d("NostrRepository", "user_likes cache-only: ${it.size}")
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * 常にリレーから最新を取得し、取得後にキャッシュを更新する。
     * キャッシュからの読み出しは行わない（getCachedUserNotesPosts を使うこと）。
     */
    suspend fun fetchUserNotes(pubkeyHex: String, limit: Int = 30): List<ScoredPost> {
        // nostrdb のローカルキャッシュから即時表示用データを取得（kind-1のみ）
        val rustClient = client.getRustClient()
        val nostrdbPosts = if (rustClient != null) {
            try {
                val eventsJson = rustClient.queryLocal(listOf(pubkeyHex), limit.toUInt())
                eventsJson.mapNotNull { j -> try { Json.decodeFromString<NostrEvent>(j) } catch (_: Exception) { null } }
            } catch (_: Exception) { emptyList() }
        } else emptyList()

        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.TEXT_NOTE, NostrKind.LONG_FORM, NostrKind.VIDEO_LOOP, NostrKind.REPOST),
            authors = listOf(pubkeyHex),
            limit = limit,
            since = getOneDayAgo()
        )
        val relayEvents = client.fetchEvents(filter, 6_000)
        // リレーからのイベントがあればそちらを優先、なければ nostrdb フォールバック
        val allEvents = (relayEvents + nostrdbPosts).distinctBy { it.id }
            .sortedByDescending { it.createdAt }
            .take(limit)
        val posts = enrichPosts(allEvents)
        try {
            cache.setCachedUserNotes(pubkeyHex,
                json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ScoredPost.serializer()), posts))
        } catch (_: Exception) { }
        android.util.Log.d("NostrRepository",
            "fetchUserNotes: relay=${relayEvents.size} nostrdb=${nostrdbPosts.size} merged=${posts.size}")
        return posts
    }

    /**
     * 常にリレーから最新のいいねを取得し、取得後にキャッシュを更新する。
     */
    suspend fun fetchUserLikes(pubkeyHex: String, limit: Int = 30): List<ScoredPost> {
        val reactionFilter = NostrClient.Filter(
            kinds = listOf(NostrKind.REACTION),
            authors = listOf(pubkeyHex),
            limit = limit,
            since = getOneDayAgo()
        )
        val reactions = client.fetchEvents(reactionFilter, timeoutMs = 5_000)
        val eventIds = reactions.mapNotNull { it.getTagValue("e") }.distinct()
        if (eventIds.isEmpty()) return emptyList()

        val eventsFilter = NostrClient.Filter(ids = eventIds)
        val posts = enrichPosts(client.fetchEvents(eventsFilter, timeoutMs = 6_000).distinctBy { it.id }.sortedByDescending { it.createdAt })
        try {
            cache.setCachedUserLikes(pubkeyHex,
                json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ScoredPost.serializer()), posts))
        } catch (_: Exception) { }
        android.util.Log.d("NostrRepository", "fetchUserLikes: ${posts.size} liked posts")
        return posts
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

    // ─── Badge Cache helpers ───────────────────────────────────────────────────

    fun getCachedProfileBadges(pubkeyHex: String): List<BadgeInfo> {
        val raw = cache.getCachedBadgeInfo(pubkeyHex) ?: return emptyList()
        return try { json.decodeFromString(raw) } catch (_: Exception) { emptyList() }
    }

    fun getCachedAwardedBadgesList(pubkeyHex: String): List<BadgeInfo> {
        val raw = cache.getCachedAwardedBadges(pubkeyHex) ?: return emptyList()
        return try { json.decodeFromString(raw) } catch (_: Exception) { emptyList() }
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
        val result = badgeRequests.awaitAll()
        // キャッシュに保存（1時間）
        try { cache.setCachedBadgeInfo(pubkeyHex, json.encodeToString(result)) } catch (_: Exception) {}
        result
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
        val result = badgeRequests.awaitAll()
        try { cache.setCachedAwardedBadges(pubkeyHex, json.encodeToString(result)) } catch (_: Exception) {}
        result
    }

    suspend fun updateProfileBadges(pubkeyHex: String, badges: List<BadgeInfo>): Boolean {
        val rustClient = client.getRustClient() ?: return false
        val tags = mutableListOf(listOf("d", "profile_badges"))
        for (badge in badges) {
            tags.add(listOf("a", badge.ref))
            badge.awardEventId?.let { tags.add(listOf("e", it)) }
        }
        return try {
            if (isExternalSigner()) {
                val unsigned = rustClient.createUnsignedEvent(NostrKind.PROFILE_BADGES.toUInt(), "", tags, myPubkeyHex)
                signAndPublish(unsigned)
            } else {
                rustClient.publishEvent(NostrKind.PROFILE_BADGES.toUInt(), "", tags)
                true
            }
        } catch (e: Exception) { false }
    }

    fun getCachedEmojiList(pubkeyHex: String): NostrEvent? {
        val jsonStr = cache.getCachedEmoji(pubkeyHex) ?: return null
        return try { json.decodeFromString<NostrEvent>(jsonStr) } catch (e: Exception) { null }
    }

    suspend fun fetchEmojiList(pubkeyHex: String): NostrEvent? {
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.EMOJI_LIST),
            authors = listOf(pubkeyHex),
            limit = 1
        )
        val events = client.fetchEvents(filter, timeoutMs = 4_000)
        val event = events.maxByOrNull { it.createdAt }
        if (event != null) {
            cache.setCachedEmoji(pubkeyHex, json.encodeToString(NostrEvent.serializer(), event))
            return event
        }
        return getCachedEmojiList(pubkeyHex)
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
        val rustClient = client.getRustClient() ?: return false
        return try {
            if (isExternalSigner()) {
                val unsigned = rustClient.createUnsignedEvent(NostrKind.EMOJI_LIST.toUInt(), "", tags, myPubkeyHex)
                signAndPublish(unsigned)
            } else {
                rustClient.publishEvent(NostrKind.EMOJI_LIST.toUInt(), "", tags)
                true
            }
        } catch (e: Exception) { false }
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
                val metaRequest = Request.Builder().url(lnurlUrl).build()
                val metaResponse = httpClient.newCall(metaRequest).execute()
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
                val invResponse = httpClient.newCall(invRequest).execute()
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

            // Note must point to one of requested eventIds.
            // We only take the first 'e' tag that matches our requested IDs to be safe.
            val targetId = event.tags.firstOrNull { it.getOrNull(0) == "e" && it.getOrNull(1) in eventIds }?.getOrNull(1) ?: continue
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
        val oneHourAgo = getOneHourAgo()
        // Fetch received DMs (Kind 4 and Kind 1059)
        val receivedFilter = NostrClient.Filter(
            kinds = listOf(NostrKind.ENCRYPTED_DM, NostrKind.DM_GIFT_WRAP),
            tags = mapOf("p" to listOf(pubkeyHex)),
            limit = 200,
            since = oneHourAgo
        )
        // Fetch sent DMs (Kind 4)
        val sentFilter = NostrClient.Filter(
            kinds = listOf(NostrKind.ENCRYPTED_DM),
            authors = listOf(pubkeyHex),
            limit = 200,
            since = oneHourAgo
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
            limit = 200,
            since = getOneHourAgo()
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

    suspend fun likePost(
        eventId: String,
        authorPubkey: String,
        emoji: String = "+",
        customTags: List<List<String>> = emptyList()
    ): Boolean {
        val rustClient = client.getRustClient() ?: return false
        return try {
            if (isExternalSigner()) {
                val unsigned = rustClient.createUnsignedReaction(eventId, authorPubkey, emoji, myPubkeyHex)
                signAndPublish(unsigned).also { if (it) android.util.Log.d("NostrRepository", "Ext react OK: $eventId") }
            } else {
                rustClient.react(eventId, authorPubkey, emoji)
                android.util.Log.d("NostrRepository", "Rust react OK: $eventId emoji=$emoji")
                true
            }
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "Rust react failed: ${e.message}")
            false
        }
    }

    /**
     * Repost an event (Kind 6, NIP-18).
     *
     * `eventJson` must be the full serialised Nostr event JSON (with encodeDefaults=true).
     */
    suspend fun repostPost(eventId: String, eventJson: String? = null): Boolean {
        val rustClient = client.getRustClient() ?: return false
        if (eventJson == null) {
            android.util.Log.w("NostrRepository", "repostPost: eventJson is null, skipping $eventId")
            return false
        }
        return try {
            if (isExternalSigner()) {
                val unsigned = rustClient.createUnsignedRepost(eventJson, myPubkeyHex)
                signAndPublish(unsigned).also { if (it) android.util.Log.d("NostrRepository", "Ext repost OK: $eventId") }
            } else {
                rustClient.repost(eventJson)
                android.util.Log.d("NostrRepository", "Rust repost OK: $eventId")
                true
            }
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "Rust repost failed: ${e.message}")
            false
        }
    }

    suspend fun publishNote(
        content: String,
        replyToId: String? = null,
        contentWarning: String? = null,
        customTags: List<List<String>> = emptyList(),
        kind: Int = NostrKind.TEXT_NOTE
    ): NostrEvent? {
        val tags = mutableListOf<List<String>>()
        tags.addAll(customTags)

        if (replyToId != null && tags.none { it.getOrNull(0) == "e" && it.getOrNull(1) == replyToId }) {
            tags.add(listOf("e", replyToId, "", "reply"))
        }
        if (contentWarning != null && tags.none { it.getOrNull(0) == "content-warning" }) {
            tags.add(listOf("content-warning", contentWarning))
        }
        if (tags.none { it.getOrNull(0) == "client" }) {
            tags.add(listOf("client", "nullnull"))
        }

        val rustClient = client.getRustClient() ?: return null

        if (isExternalSigner()) {
            return try {
                // createUnsignedEvent is a blocking Rust FFI call — run off main thread
                val unsigned = withContext(Dispatchers.IO) {
                    rustClient.createUnsignedEvent(kind.toUInt(), content, tags, myPubkeyHex)
                }
                android.util.Log.d("NostrRepository", "Ext publishNote: unsigned created (${unsigned.length} chars), signing...")
                val signedJson = client.getSigner().signEvent(unsigned) ?: run {
                    android.util.Log.w("NostrRepository", "Ext publishNote: signer returned null")
                    return null
                }
                android.util.Log.d("NostrRepository", "Ext publishNote: signed (${signedJson.length} chars), publishing...")
                // publishRawEvent opens relay connections — must not block main thread
                withContext(Dispatchers.IO) { rustClient.publishRawEvent(signedJson) }
                android.util.Log.d("NostrRepository", "Ext publishNote OK")
                try { json.decodeFromString<NostrEvent>(signedJson) } catch (_: Exception) { null }
            } catch (e: Exception) {
                android.util.Log.e("NostrRepository", "Ext publishNote failed: ${e.message}")
                null
            }
        }

        if (kind == NostrKind.TEXT_NOTE) {
            return try {
                val eventId = withContext(Dispatchers.IO) { rustClient.publishNoteWithTags(content, tags) }
                android.util.Log.d("NostrRepository", "Rust publishNote OK: $eventId")
                // Fetch the published event back from local cache for callers that need it
                withContext(Dispatchers.IO) {
                    rustClient.queryLocal(listOf(prefs.publicKeyHex ?: ""), 1u)
                        .firstOrNull()
                        ?.let { try { Json.decodeFromString<NostrEvent>(it) } catch (_: Exception) { null } }
                }
            } catch (e: Exception) {
                android.util.Log.e("NostrRepository", "Rust publishNote failed: ${e.message}")
                null
            }
        }

        // Non-Kind-1: use generic publishEvent
        return try {
            val eventId = withContext(Dispatchers.IO) { rustClient.publishEvent(kind.toUInt(), content, tags) }
            android.util.Log.d("NostrRepository", "Rust publishEvent(kind=$kind) OK id=$eventId tags=${tags.size}")
            // Return a minimal NostrEvent so callers can detect success
            NostrEvent(id = eventId, kind = kind, tags = tags, content = content)
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "Rust publishEvent(kind=$kind) failed: ${e.message}")
            null
        }
    }

    suspend fun sendDm(recipientPubkeyHex: String, content: String): Boolean =
        client.sendEncryptedDm(recipientPubkeyHex, content)

    suspend fun deleteEvent(eventId: String, reason: String = ""): Boolean {
        val rustClient = client.getRustClient() ?: return false
        return try {
            if (isExternalSigner()) {
                val tags = listOf(listOf("e", eventId))
                val unsigned = rustClient.createUnsignedEvent(5u, reason, tags, myPubkeyHex)
                signAndPublish(unsigned).also { if (it) android.util.Log.d("NostrRepository", "Ext deleteEvent OK: $eventId") }
            } else {
                rustClient.deleteEvent(eventId, reason.ifEmpty { null })
                android.util.Log.d("NostrRepository", "Rust deleteEvent OK: $eventId")
                true
            }
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "Rust deleteEvent failed: ${e.message}")
            false
        }
    }

    suspend fun reportEvent(targetPubkey: String, eventId: String?, reportType: String, content: String): Boolean {
        val rustClient = client.getRustClient() ?: return false
        val tags = mutableListOf(listOf("p", targetPubkey, reportType))
        eventId?.let { tags.add(listOf("e", it, reportType)) }
        return try {
            if (isExternalSigner()) {
                val unsigned = rustClient.createUnsignedEvent(1984u, content, tags, myPubkeyHex)
                signAndPublish(unsigned)
            } else {
                rustClient.publishEvent(1984u, content, tags)
                true
            }
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "reportEvent failed: ${e.message}")
            false
        }
    }

    suspend fun publishBirdwatchLabel(eventId: String, authorPubkey: String, contextType: String, content: String, sourceUrl: String = ""): Boolean {
        val rustClient = client.getRustClient() ?: return false
        val fullContent = if (sourceUrl.isNotBlank()) "$content\n\nソース: $sourceUrl" else content
        val tags = listOf(
            listOf("L", "birdwatch"),
            listOf("l", contextType, "birdwatch"),
            listOf("e", eventId),
            listOf("p", authorPubkey)
        )
        return try {
            if (isExternalSigner()) {
                val unsigned = rustClient.createUnsignedEvent(1985u, fullContent, tags, myPubkeyHex)
                signAndPublish(unsigned)
            } else {
                rustClient.publishEvent(1985u, fullContent, tags)
                true
            }
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "publishBirdwatchLabel failed: ${e.message}")
            false
        }
    }

    fun getCachedMuteList(pubkeyHex: String): MuteListData? {
        val jsonStr = cache.getCachedMuteList(pubkeyHex) ?: return null
        return try {
            val event = json.decodeFromString<NostrEvent>(jsonStr)
            MuteListData(
                pubkeys = event.getTagValues("p"),
                eventIds = event.getTagValues("e"),
                hashtags = event.getTagValues("t"),
                words = event.getTagValues("word")
            )
        } catch (e: Exception) { null }
    }

    suspend fun fetchMuteList(pubkeyHex: String): MuteListData {
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.MUTE_LIST),
            authors = listOf(pubkeyHex),
            limit = 1
        )
        val events = client.fetchEvents(filter, timeoutMs = 4_000)
        val event = events.maxByOrNull { it.createdAt }

        if (event != null) {
            cache.setCachedMuteList(pubkeyHex, json.encodeToString(NostrEvent.serializer(), event))
            return MuteListData(
                pubkeys = event.getTagValues("p"),
                eventIds = event.getTagValues("e"),
                hashtags = event.getTagValues("t"),
                words = event.getTagValues("word")
            )
        }

        return getCachedMuteList(pubkeyHex) ?: MuteListData()
    }

    suspend fun fetchNip65WriteRelays(pubkeyHex: String): List<String> {
        return try {
            OutboxModel(client).fetchUserRelayList(pubkeyHex).write
        } catch (_: Exception) { emptyList() }
    }

    /**
     * NIP-65 kind 10002 から最寄りリレーを取得し、mainRelay に保存する。
     * Web版の nurunuru_default_relay に相当する概念。
     * おすすめタイムラインはこの1リレーのみを使用する（全writeリレーではない）。
     */
    suspend fun syncNip65Relays(pubkeyHex: String) {
        val writeRelays = fetchNip65WriteRelays(pubkeyHex)
        val main = writeRelays.firstOrNull() ?: return
        if (main != prefs.mainRelay) {
            prefs.mainRelay = main
            android.util.Log.d("NostrRepository", "NIP-65 main relay synced: $main")
        }
    }

    suspend fun removeFromMuteList(pubkeyHex: String, type: String, value: String): Boolean {
        val rustClient = client.getRustClient() ?: return false
        val filter = NostrClient.Filter(kinds = listOf(NostrKind.MUTE_LIST), authors = listOf(pubkeyHex), limit = 1)
        val latest = client.fetchEvents(filter, timeoutMs = 4_000).maxByOrNull { it.createdAt } ?: return true

        val tagType = when (type) {
            "pubkey" -> "p"; "event" -> "e"; "hashtag" -> "t"; "word" -> "word"
            else -> return false
        }
        val newTags = latest.tags.filter { !(it.firstOrNull() == tagType && it.getOrNull(1) == value) }
        if (newTags.size == latest.tags.size) return true

        return try {
            if (isExternalSigner()) {
                val unsigned = rustClient.createUnsignedEvent(NostrKind.MUTE_LIST.toUInt(), latest.content, newTags, myPubkeyHex)
                signAndPublish(unsigned)
            } else {
                rustClient.publishEvent(NostrKind.MUTE_LIST.toUInt(), latest.content, newTags)
                true
            }
        } catch (e: Exception) { false }
    }

    suspend fun muteUser(pubkeyHex: String): Boolean {
        val rustClient = client.getRustClient() ?: return false
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.MUTE_LIST),
            authors = listOf(prefs.publicKeyHex ?: return false),
            limit = 1
        )
        val latest = client.fetchEvents(filter, timeoutMs = 4_000).maxByOrNull { it.createdAt }
        val tags = latest?.tags?.toMutableList() ?: mutableListOf()
        if (tags.any { it.firstOrNull() == "p" && it.getOrNull(1) == pubkeyHex }) return true
        tags.add(listOf("p", pubkeyHex))
        return try {
            if (isExternalSigner()) {
                val unsigned = rustClient.createUnsignedEvent(NostrKind.MUTE_LIST.toUInt(), latest?.content ?: "", tags, myPubkeyHex)
                signAndPublish(unsigned)
            } else {
                rustClient.publishEvent(NostrKind.MUTE_LIST.toUInt(), latest?.content ?: "", tags)
                true
            }
        } catch (e: Exception) { false }
    }

    suspend fun followUser(myPubkeyHex: String, targetPubkeyHex: String): Boolean {
        val rustClient = client.getRustClient() ?: return false
        return try {
            if (isExternalSigner()) {
                val contactsFilter = NostrClient.Filter(kinds = listOf(NostrKind.CONTACT_LIST), authors = listOf(myPubkeyHex), limit = 1)
                val latest = client.fetchEvents(contactsFilter, timeoutMs = 4_000).maxByOrNull { it.createdAt }
                val tags = latest?.tags?.toMutableList() ?: mutableListOf()
                if (tags.any { it.firstOrNull() == "p" && it.getOrNull(1) == targetPubkeyHex }) return true
                tags.add(listOf("p", targetPubkeyHex))
                val unsigned = rustClient.createUnsignedEvent(NostrKind.CONTACT_LIST.toUInt(), latest?.content ?: "", tags, myPubkeyHex)
                signAndPublish(unsigned).also { if (it) android.util.Log.d("NostrRepository", "Ext followUser OK: $targetPubkeyHex") }
            } else {
                rustClient.followUser(targetPubkeyHex)
                android.util.Log.d("NostrRepository", "Rust followUser OK: $targetPubkeyHex")
                true
            }
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "Rust followUser failed: ${e.message}")
            false
        }
    }

    suspend fun unfollowUser(myPubkeyHex: String, targetPubkeyHex: String): Boolean {
        val rustClient = client.getRustClient() ?: return false
        return try {
            if (isExternalSigner()) {
                val contactsFilter = NostrClient.Filter(kinds = listOf(NostrKind.CONTACT_LIST), authors = listOf(myPubkeyHex), limit = 1)
                val latest = client.fetchEvents(contactsFilter, timeoutMs = 4_000).maxByOrNull { it.createdAt } ?: return true
                val newTags = latest.tags.filter { !(it.firstOrNull() == "p" && it.getOrNull(1) == targetPubkeyHex) }
                if (newTags.size == latest.tags.size) return true
                val unsigned = rustClient.createUnsignedEvent(NostrKind.CONTACT_LIST.toUInt(), latest.content, newTags, myPubkeyHex)
                signAndPublish(unsigned).also { if (it) android.util.Log.d("NostrRepository", "Ext unfollowUser OK: $targetPubkeyHex") }
            } else {
                rustClient.unfollowUser(targetPubkeyHex)
                android.util.Log.d("NostrRepository", "Rust unfollowUser OK: $targetPubkeyHex")
                true
            }
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "Rust unfollowUser failed: ${e.message}")
            false
        }
    }

    fun getUploadServer(): String = prefs.uploadServer
    fun setUploadServer(server: String) { prefs.uploadServer = server }

    fun getDefaultZapAmount(): Int = prefs.defaultZapAmount

    fun getCachedTimeline(): String? = cache.getCachedTimeline()
    fun setCachedTimeline(eventsJson: String) = cache.setCachedTimeline(eventsJson)

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
        val rustClient = client.getRustClient() ?: return false
        val tags = relays.map { (url, read, write) ->
            val marker = when { read && write -> null; read -> "read"; write -> "write"; else -> null }
            if (marker != null) listOf("r", url, marker) else listOf("r", url)
        }
        return try {
            if (isExternalSigner()) {
                val unsigned = rustClient.createUnsignedEvent(10002u, "", tags, myPubkeyHex)
                signAndPublish(unsigned)
            } else {
                rustClient.publishEvent(10002u, "", tags)
                true
            }
        } catch (e: Exception) { false }
    }

    suspend fun requestVanish(relays: List<String>?, reason: String): Boolean {
        val rustClient = client.getRustClient() ?: return false
        val tags = mutableListOf<List<String>>()
        if (relays.isNullOrEmpty()) tags.add(listOf("relay", "ALL_RELAYS"))
        else relays.forEach { tags.add(listOf("relay", it)) }
        return try {
            if (isExternalSigner()) {
                val unsigned = rustClient.createUnsignedEvent(NostrKind.VANISH_REQUEST.toUInt(), reason, tags, myPubkeyHex)
                signAndPublish(unsigned)
            } else {
                rustClient.publishEvent(NostrKind.VANISH_REQUEST.toUInt(), reason, tags)
                true
            }
        } catch (e: Exception) { false }
    }

    suspend fun updateProfile(profile: UserProfile): Boolean {
        // Build metadata JSON: omit blank/null fields to avoid overwriting with empty strings
        val obj = buildJsonObject {
            fun put(key: String, value: String?) {
                if (!value.isNullOrBlank()) put(key, JsonPrimitive(value))
            }
            put("name", profile.name)
            put("display_name", profile.displayName ?: profile.name)
            put("about", profile.about)
            put("picture", profile.picture)
            put("banner", profile.banner)
            put("nip05", profile.nip05)
            put("lud16", profile.lud16)
            put("website", profile.website)
            put("birthday", profile.birthday)
            put("geohash", profile.geohash)
        }
        val metadataJson = obj.toString()

        val rustClient = client.getRustClient() ?: return false
        return try {
            if (isExternalSigner()) {
                val unsigned = rustClient.createUnsignedEvent(0u, metadataJson, emptyList(), myPubkeyHex)
                signAndPublish(unsigned).also { if (it) android.util.Log.d("NostrRepository", "Ext updateProfile OK") }
            } else {
                rustClient.updateProfile(metadataJson)
                android.util.Log.d("NostrRepository", "Rust updateProfile OK")
                true
            }
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "Rust updateProfile failed: ${e.message}")
            false
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Public entry point for live-stream enrichment from ViewModels. */
    suspend fun enrichPostsDirect(events: List<NostrEvent>): List<ScoredPost> = enrichPosts(events)

    /**
     * おすすめタイムライン用フルスコアリング・フィルタリングをライブ投稿に適用する。
     *
     * 直近の fetchRecommendedFromMainRelay で構築した ScoringContext を再利用するため
     * 追加のリレーフェッチは発生しない。コンテキスト未構築時は最低限のキャッシュデータで代替。
     *
     * 適用されるフィルター・アルゴリズム:
     *   - ミュートフィルター (pubkey + eventId)
     *   - プロフィール品質フィルター (アイコン・名前なし除外)
     *   - not-interested 除外
     *   - RecommendationEngine スコアリング (エンゲージメント・ソーシャルブースト・時間減衰)
     *   - 言語ブースト (ジオハッシュ由来の期待言語とのマッチ)
     *   - リプライ除外 (e タグあり)
     */
    fun scoreForRecommended(posts: List<ScoredPost>): List<ScoredPost> {
        if (posts.isEmpty()) return emptyList()

        // キャッシュ済みコンテキストを使用。未構築ならキャッシュから最低限のコンテキストを組み立てる
        val ctx = cachedScoringContext ?: run {
            val myPubkey = prefs.publicKeyHex ?: return emptyList()
            RecommendationEngine.ScoringContext(
                followList = getCachedFollowList(myPubkey)?.toSet() ?: emptySet(),
                mutedPubkeys = getCachedMuteList(myPubkey)?.pubkeys?.toSet() ?: emptySet(),
                userGeohash = prefs.userGeohash
            )
        }

        // ライブ投稿はエンゲージメントデータがまだ存在しないため post 内の値を使う
        val engagements = posts.associate { it.event.id to RecommendationEngine.EngagementCounts(
            likes = it.likeCount,
            reposts = it.repostCount,
            replies = it.replyCount,
            zaps = if (it.zapAmount <= 0) 0
                   else (kotlin.math.ln(it.zapAmount.toDouble() / 1000.0 + 1.0) * 10).toInt().coerceAtLeast(1)
        ) }
        val profileMap = posts.mapNotNull { it.profile?.let { p -> p.pubkey to p } }.toMap()

        val enrichedCtx = ctx.copy(
            engagements = ctx.engagements + engagements,
            profiles = ctx.profiles + profileMap
        )

        // mutedIds はコンテキストに含まれないので再取得
        val mutedIds = getCachedMuteList(prefs.publicKeyHex ?: "")?.eventIds?.toSet() ?: emptySet()

        return posts
            .filter { it.event.getTagValues("e").isEmpty() }  // リプライ除外
            .filter { it.event.id !in mutedIds }               // ミュート eventId
            .map { post ->
                val score = recommendationEngine.calculateScore(post.event, enrichedCtx)
                post.copy(score = score)
            }
            .filter { it.score > 0 }                           // ミュート pubkey・not-interested・品質フィルタ適用済み
            .sortedByDescending { it.score }
    }

    private suspend fun enrichPosts(events: List<NostrEvent>): List<ScoredPost> = coroutineScope {
        if (events.isEmpty()) return@coroutineScope emptyList()

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

        if (processedItems.isEmpty()) return@coroutineScope emptyList()

        val ids = processedItems.map { it.first.id }

        // Fetch everything in parallel
        val profilesDeferred = async { fetchProfiles(pubkeysToFetch.toList()) }
        val reactionsDeferred = async {
            val filter = NostrClient.Filter(kinds = listOf(NostrKind.REACTION), tags = mapOf("e" to ids), limit = 500)
            client.fetchEvents(filter, 3000)
        }
        val repostsDeferred = async {
            val filter = NostrClient.Filter(kinds = listOf(NostrKind.REPOST), tags = mapOf("e" to ids), limit = 500)
            client.fetchEvents(filter, 3000)
        }
        val repliesDeferred = async {
            val filter = NostrClient.Filter(kinds = listOf(NostrKind.TEXT_NOTE), tags = mapOf("e" to ids), limit = 500)
            client.fetchEvents(filter, 3000)
        }
        val zapsDeferred = async { fetchZaps(ids) }

        val profiles = profilesDeferred.await()
        val reactionEvents = reactionsDeferred.await()
        val repostEvents = repostsDeferred.await()
        val replyEvents = repliesDeferred.await()
        val zaps = zapsDeferred.await()

        val reactionCounts = reactionEvents.groupBy { it.getTagValue("e") ?: "" }.mapValues { it.value.size }
        val myLikes = if (myPubkey != null) reactionEvents.filter { it.pubkey == myPubkey }.mapNotNull { it.getTagValue("e") }.toSet() else emptySet()

        val repostCounts = repostEvents.groupBy { it.getTagValue("e") ?: "" }.mapValues { it.value.size }
        val myReposts = if (myPubkey != null) repostEvents.filter { it.pubkey == myPubkey }.mapNotNull { it.getTagValue("e") }.toSet() else emptySet()

        val replyCounts = replyEvents.groupBy { it.getTagValue("e") ?: "" }.mapValues { it.value.size }

        // Parallel NIP-05 verification
        val verificationDeferred = profiles.values.filter { it.nip05 != null }.map { profile ->
            async {
                val isVerified = Nip05Utils.verifyNip05(profile.nip05!!, profile.pubkey)
                profile.pubkey to isVerified
            }
        }
        val verificationResults = verificationDeferred.awaitAll().toMap()

        processedItems.map { (event, repost) ->
            val likeCount = reactionCounts[event.id] ?: 0
            val repostCount = repostCounts[event.id] ?: 0
            val replyCount = replyCounts[event.id] ?: 0
            val zapAmount = zaps[event.id] ?: 0L
            val isVerified = verificationResults[event.pubkey] ?: false

            // Scoring matching web weights (lib/recommendation.js):
            // Zap=100, custom_reaction=60, quote=35, reply=30, Repost=25, bookmark=15, Like=5
            val engagementScore = (zapAmount / 1000.0) * Constants.Engagement.WEIGHT_ZAP +
                likeCount * Constants.Engagement.WEIGHT_LIKE +
                repostCount * Constants.Engagement.WEIGHT_REPOST +
                1.0 // base score

            // Time decay: exponential with half-life of 6 hours (matching web)
            val ageHours = (System.currentTimeMillis() / 1000 - event.createdAt) / 3600.0
            val timeMultiplier = if (ageHours < 1.0) {
                Constants.TimeDecay.FRESHNESS_BOOST // 1.5x boost for <1 hour
            } else {
                kotlin.math.max(0.001, 0.5.pow(ageHours / Constants.TimeDecay.HALF_LIFE_HOURS))
            }

            ScoredPost(
                event = event,
                profile = profiles[event.pubkey],
                likeCount = likeCount,
                repostCount = repostCount,
                replyCount = replyCount,
                zapAmount = zapAmount,
                score = engagementScore * timeMultiplier,
                isLiked = myLikes.contains(event.id),
                isReposted = myReposts.contains(event.id),
                isVerified = isVerified,
                repostedBy = if (repost != null) profiles[repost.pubkey] else null,
                repostTime = repost?.createdAt
            )
        }.sortedByDescending { it.repostTime ?: it.event.createdAt }
    }

    // ─── Convenience helpers for miniapps ─────────────────────────────────────

    /** Fetch events with simplified parameters for miniapps */
    suspend fun fetchEvents(
        kinds: List<Int>,
        authors: List<String>? = null,
        limit: Int = 10,
        dTags: List<String>? = null,
        since: Long? = null
    ): List<NostrEvent> {
        val tags = mutableMapOf<String, List<String>>()
        dTags?.let { tags["d"] = it }

        return client.fetchEvents(
            NostrClient.Filter(
                kinds = kinds,
                authors = authors,
                limit = limit,
                tags = tags.ifEmpty { null },
                since = since
            ),
            timeoutMs = 5_000
        )
    }

    /** Publish a generic event (used by SchedulerApp, etc.) */
    suspend fun publishEvent(
        kind: Int,
        content: String,
        tags: List<List<String>> = emptyList()
    ): NostrEvent? {
        val rustClient = client.getRustClient() ?: return null
        val allTags = tags.toMutableList()
        if (allTags.none { it.getOrNull(0) == "client" }) {
            allTags.add(listOf("client", "nullnull"))
        }
        return try {
            if (isExternalSigner()) {
                val unsigned = rustClient.createUnsignedEvent(kind.toUInt(), content, allTags, myPubkeyHex)
                signAndPublish(unsigned)
            } else {
                rustClient.publishEvent(kind.toUInt(), content, allTags)
            }
            null
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "publishEvent(kind=$kind) failed: ${e.message}")
            null
        }
    }

    // ─── Live Streaming ───────────────────────────────────────────────────────

    /**
     * Start a live subscription for new Kind-1 events.
     *
     * Pass an empty list for the global feed, or a follow list for the follow
     * timeline.  Returns a subscription ID to pass to [pollLiveStream] and
     * [stopLiveStream].  Returns null if the Rust client is not available.
     */
    fun startLiveStream(authors: List<String> = emptyList()): String? {
        return try {
            client.getRustClient()?.startLiveSubscription(authors).also {
                android.util.Log.d("NostrRepository",
                    "Live stream started: sub=$it authors=${authors.size}")
            }
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "startLiveStream failed", e)
            null
        }
    }

    /**
     * Drain buffered live events. Returns immediately with whatever has arrived
     * since the last call.  Safe to call every second from a coroutine loop.
     */
    fun pollLiveStream(subId: String): List<NostrEvent> {
        return try {
            val jsonList = client.getRustClient()
                ?.pollLiveEvents(subId, 20u)
                ?: return emptyList()
            jsonList.mapNotNull { jsonStr ->
                try { Json.decodeFromString<NostrEvent>(jsonStr) }
                catch (_: Exception) { null }
            }
        } catch (e: Exception) {
            android.util.Log.w("NostrRepository", "pollLiveStream error", e)
            emptyList()
        }
    }

    /**
     * Cancel a live subscription and release its resources.
     */
    fun stopLiveStream(subId: String) {
        try {
            client.getRustClient()?.stopLiveSubscription(subId)
            android.util.Log.d("NostrRepository", "Live stream stopped: sub=$subId")
        } catch (e: Exception) {
            android.util.Log.w("NostrRepository", "stopLiveStream error", e)
        }
    }

    // ─── WebView / NIP-07 Bridge helpers ─────────────────────────────────────

    /**
     * WebView から渡された未署名イベント JSON に署名して返す。
     * JS 側から `{ kind, content, tags, created_at? }` 形式で受け取る。
     * id・pubkey・sig はサイナーが付加する。
     */
    suspend fun signEventForWebBridge(eventJson: String): String? = withContext(Dispatchers.IO) {
        try {
            val parsed = kotlinx.serialization.json.Json.parseToJsonElement(eventJson)
                .jsonObject
            val kind      = parsed["kind"]?.jsonPrimitive?.intOrNull ?: return@withContext null
            val content   = parsed["content"]?.jsonPrimitive?.content ?: ""
            val tagsArr   = parsed["tags"]?.jsonArray
                ?: kotlinx.serialization.json.JsonArray(emptyList())
            val createdAt = parsed["created_at"]?.jsonPrimitive?.longOrNull
                ?: (System.currentTimeMillis() / 1000)

            // 未署名イベント（id/sig なし）を構築
            val unsigned = buildString {
                append("{")
                append("\"kind\":$kind,")
                append("\"content\":${json.encodeToString(content)},")
                append("\"tags\":${tagsArr},")
                append("\"created_at\":$createdAt,")
                append("\"pubkey\":\"$myPubkeyHex\"")
                append("}")
            }
            client.getSigner().signEvent(unsigned)
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "signEventForWebBridge: ${e.message}")
            null
        }
    }

    /** NIP-04 暗号化（WebView ブリッジ用） */
    suspend fun nip04EncryptForBridge(receiverPubkey: String, plaintext: String): String? =
        client.encryptNip04(receiverPubkey, plaintext)

    /** NIP-04 復号（WebView ブリッジ用） */
    suspend fun nip04DecryptForBridge(senderPubkey: String, ciphertext: String): String? =
        client.decryptNip04(senderPubkey, ciphertext)

    /** リレーリストを JSON 文字列で返す（WebView ブリッジ用） */
    fun getRelaysJson(): String {
        val relays = prefs.relays.associateWith { mapOf("read" to true, "write" to true) }
        return json.encodeToString(relays)
    }

    /** Fetch follow lists for multiple pubkeys (for 2nd-degree network) */
    suspend fun fetchFollowListsBatch(pubkeys: List<String>): Map<String, List<String>> {
        val filter = NostrClient.Filter(
            kinds = listOf(NostrKind.CONTACT_LIST),
            authors = pubkeys.take(50),
            limit = pubkeys.size
        )
        val events = client.fetchEvents(filter, timeoutMs = 5_000)

        val latestByAuthor = mutableMapOf<String, NostrEvent>()
        for (event in events) {
            val existing = latestByAuthor[event.pubkey]
            if (existing == null || event.createdAt > existing.createdAt) {
                latestByAuthor[event.pubkey] = event
            }
        }

        return latestByAuthor.mapValues { (_, event) ->
            event.tags.filter { it.firstOrNull() == "p" }.mapNotNull { it.getOrNull(1) }
        }
    }
}
