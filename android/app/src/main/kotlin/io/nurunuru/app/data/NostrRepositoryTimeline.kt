package io.nurunuru.app.data

import io.nurunuru.app.data.models.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ─── Timeline ─────────────────────────────────────────────────────────────────

/** General fetchEvents method. */
suspend fun NostrRepository.fetchEvents(filter: NostrClient.Filter, timeoutMs: Long = 5_000): List<NostrEvent> {
    return client.fetchEvents(filter, timeoutMs)
}

suspend fun NostrRepository.fetchRecommendedTimeline(limit: Int = 50): List<ScoredPost> =
    withContext(Dispatchers.IO) {
        try {
            fetchRecommendedFromMainRelay(limit)
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "fetchRecommendedTimeline failed: ${e.message}", e)
            emptyList()
        }
    }

/**
 * メインリレーから kind 1 を時系列で取得し、ミュートフィルタのみ適用する。
 */
private suspend fun NostrRepository.fetchRecommendedFromMainRelay(limit: Int): List<ScoredPost> {
    val mainRelay = prefs.mainRelay
    val myPubkey = prefs.publicKeyHex ?: ""

    val relaysToTry = listOf(mainRelay) +
        io.nurunuru.app.data.models.DEFAULT_RELAYS.filter { it != mainRelay }.take(2)

    var events = emptyList<NostrEvent>()
    for (relay in relaysToTry) {
        events = try {
            client.fetchEventsFrom(
                listOf(relay),
                NostrClient.Filter(
                    kinds = listOf(NostrKind.TEXT_NOTE),
                    limit = limit
                ), timeoutMs = 5_000
            )
        } catch (_: Exception) { emptyList() }
        if (events.isNotEmpty()) break
    }

    // リプライ除外 (e タグあり = 他投稿へのリプライ)
    val rootPosts = events.filter { it.getTagValues("e").isEmpty() }

    val enriched = enrichPosts(rootPosts)

    val muteData = getCachedMuteList(myPubkey)
    val result = if (muteData != null &&
        (muteData.pubkeys.isNotEmpty() || muteData.eventIds.isNotEmpty())) {
        val mutedPks = muteData.pubkeys.toSet()
        val mutedIds = muteData.eventIds.toSet()
        enriched.filter { it.event.pubkey !in mutedPks && it.event.id !in mutedIds }
    } else enriched

    return result.sortedByDescending { it.event.createdAt }
}

suspend fun NostrRepository.fetchGlobalTimeline(limit: Int = 50): List<ScoredPost> {
    if (useRustCore) {
        return withContext(Dispatchers.IO) {
            try {
                val rustClient = client.getRustClient()
                    ?: return@withContext fetchGlobalTimelineLegacy(limit)

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

private suspend fun NostrRepository.fetchGlobalTimelineLegacy(limit: Int): List<ScoredPost> {
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
            (viral + recent + secondDegreePosts).distinctBy { it.id }
                .filter { it.getTagValues("e").isEmpty() }, // リプライ除外
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
suspend fun NostrRepository.fetchCachedFollowTimeline(pubkeyHex: String, limit: Int = 50): List<ScoredPost> {
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
suspend fun NostrRepository.fetchFollowTimeline(pubkeyHex: String, limit: Int = 50): List<ScoredPost> {
    if (useRustCore) {
        return withContext(Dispatchers.IO) {
            try {
                val rustClient = client.getRustClient()
                    ?: return@withContext fetchFollowTimelineLegacy(pubkeyHex, limit)

                val followList = fetchFollowList(pubkeyHex)
                if (followList.isEmpty()) return@withContext emptyList()

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
                    .filter { it.getTagValues("e").isEmpty() } // リプライ除外
                enrichPosts(events)
            } catch (e: Exception) {
                android.util.Log.e("NostrRepository", "Rust fetchFollowTimeline failed, falling back", e)
                fetchFollowTimelineLegacy(pubkeyHex, limit)
            }
        }
    }
    return fetchFollowTimelineLegacy(pubkeyHex, limit)
}

private suspend fun NostrRepository.fetchFollowTimelineLegacy(pubkeyHex: String, limit: Int): List<ScoredPost> {
    val followList = fetchFollowList(pubkeyHex)
    if (followList.isEmpty()) return emptyList()

    val filter = NostrClient.Filter(
        kinds = listOf(NostrKind.TEXT_NOTE, NostrKind.VIDEO_LOOP),
        authors = followList.take(500),
        limit = limit,
        since = getOneHourAgo()
    )
    val events = client.fetchEvents(filter, timeoutMs = 6_000).distinctBy { it.id }
        .filter { it.getTagValues("e").isEmpty() } // リプライ除外
    return enrichPosts(events)
}

/** Search for notes by text (NIP-50) using dedicated search relay. */
suspend fun NostrRepository.searchNotes(query: String, limit: Int = 30): List<ScoredPost> {
    val filter = NostrClient.Filter(
        kinds = listOf(NostrKind.TEXT_NOTE, NostrKind.VIDEO_LOOP),
        search = query,
        limit = limit
    )
    val events = client.fetchEventsFrom(
        listOf(NostrClient.SEARCH_RELAY), filter, timeoutMs = 6_000
    )
    return enrichPosts(events)
}

/**
 * オペレータ付き高度検索。
 * - テキストあり → searchnos (NIP-50) に構造化フィルタを組み合わせて送信
 * - テキストなし → 標準リレーREQ（#t / authors / since / until のみ）
 * - クライアント側後処理: -除外語、"完全一致"、filter:image/video/link
 */
suspend fun NostrRepository.advancedSearch(
    parsed: ParsedSearchQuery,
    resolvedNip05Authors: List<String> = emptyList(),
    limit: Int = 30,
): List<ScoredPost> {
    // from: を hex pubkey に解決
    val fromHex = (parsed.fromPubkeys.mapNotNull { NostrKeyUtils.parsePublicKey(it) }
            + resolvedNip05Authors).distinct()

    val tagFilters = buildMap<String, List<String>> {
        if (parsed.hashtags.isNotEmpty()) put("t", parsed.hashtags)
    }.takeIf { it.isNotEmpty() }

    android.util.Log.d("SearchQuery", "text='${parsed.textQuery}' hashtags=${parsed.hashtags} " +
        "from=${fromHex} since=${parsed.since} until=${parsed.until} " +
        "exclude=${parsed.excludeWords} exact=${parsed.exactPhrases} media=${parsed.mediaFilter}")

    val rawEvents = if (parsed.textQuery.isNotEmpty()) {
        val filter = NostrClient.Filter(
            kinds   = listOf(NostrKind.TEXT_NOTE, NostrKind.VIDEO_LOOP),
            search  = parsed.textQuery,
            authors = fromHex.takeIf { it.isNotEmpty() },
            tags    = tagFilters,
            since   = parsed.since,
            until   = parsed.until,
            limit   = limit,
        )
        client.fetchEventsFrom(listOf(NostrClient.SEARCH_RELAY), filter, timeoutMs = 6_000)
    } else {
        val filter = NostrClient.Filter(
            kinds   = listOf(NostrKind.TEXT_NOTE, NostrKind.VIDEO_LOOP),
            authors = fromHex.takeIf { it.isNotEmpty() },
            tags    = tagFilters,
            since   = parsed.since,
            until   = parsed.until,
            limit   = limit,
        )
        client.fetchEvents(filter, timeoutMs = 6_000)
    }

    // クライアント側後処理フィルタ
    val filtered = rawEvents.filter { event ->
        val c = event.content
        parsed.excludeWords.none  { w -> c.contains(w, ignoreCase = true) } &&
        parsed.exactPhrases.all   { p -> c.contains(p) } &&
        when (parsed.mediaFilter) {
            ParsedSearchQuery.MediaFilter.IMAGE ->
                NostrRepository.SEARCH_IMAGE_HOSTS.any { c.contains(it, ignoreCase = true) }
            ParsedSearchQuery.MediaFilter.VIDEO ->
                NostrRepository.SEARCH_VIDEO_HOSTS.any { c.contains(it, ignoreCase = true) }
            ParsedSearchQuery.MediaFilter.LINK  ->
                c.contains("http://") || c.contains("https://")
            null -> true
        }
    }
    android.util.Log.d("SearchQuery", "raw=${rawEvents.size} filtered=${filtered.size}")
    return enrichPosts(filtered)
}

/**
 * バッチでフォロー中ユーザーの NIP-65 リレーリスト (kind 10002) を取得し、キャッシュに保存する。
 * 起動時にバックグラウンドで実行。TalkViewModel の DM 送信でキャッシュを参照。
 */
suspend fun NostrRepository.prefetchFollowRelayLists() = withContext(Dispatchers.IO) {
    val follows = followingSet.toList().take(200).ifEmpty { return@withContext }
    // Already cached? Skip those
    val uncached = follows.filter { cache.getCachedRelayList(it) == null }.take(100)
    if (uncached.isEmpty()) return@withContext

    android.util.Log.d("NostrRepository", "prefetchFollowRelayLists: fetching ${uncached.size} relay lists")
    val filter = NostrClient.Filter(
        kinds = listOf(NostrKind.RELAY_LIST),
        authors = uncached,
        limit = uncached.size
    )
    val events = client.fetchEvents(filter, timeoutMs = 10_000)
    // Store latest relay list per pubkey
    val latestPerPubkey = events.groupBy { it.pubkey }.mapValues { it.value.maxByOrNull { e -> e.createdAt }!! }
    for ((pubkey, event) in latestPerPubkey) {
        try {
            val relayList = event.tags.filter { it.firstOrNull() == "r" }.map { tag ->
                val url = tag.getOrElse(1) { "" }
                val marker = tag.getOrNull(2)
                Nip65Relay(url = url, read = marker == null || marker == "read", write = marker == null || marker == "write")
            }
            cache.setCachedRelayList(pubkey, Json.encodeToString(relayList))
        } catch (_: Exception) {}
    }
    android.util.Log.d("NostrRepository", "prefetchFollowRelayLists: cached ${latestPerPubkey.size} relay lists")
}
