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
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/**
 * High-level Nostr operations.
 * Uses NostrClient for all relay communication.
 *
 * Domain extensions:
 *   NostrRepositoryTimeline.kt      — timeline fetch methods
 *   NostrRepositoryNotifications.kt — notifications
 *   NostrRepositoryProfiles.kt      — profiles, badges, emoji
 *   NostrRepositoryReactions.kt     — reactions, zaps, lightning, birdwatch
 *   NostrRepositoryActions.kt       — publish, like, repost, follow, mute
 *   NostrRepositoryTalk.kt          — MLS / NIP-EE groups
 *   NostrRepositoryBackup.kt        — backup, import, updateProfile, relays
 *   NostrRepositoryLiveStream.kt    — live subscriptions, relay timeline, WebBridge
 */
class NostrRepository(
    internal val client: NostrClient,
    internal val prefs: AppPreferences,
    internal val cache: NostrCache,
    internal val recommendationEngine: RecommendationEngine
) {
    /** Toggle for using the Rust core for heavy operations. */
    internal var useRustCore: Boolean = true

    fun setUseRustCore(enabled: Boolean) {
        useRustCore = enabled
    }

    fun recordEngagement(action: String, authorPubkey: String) {
        client.recordEngagement(action, authorPubkey)
    }

    fun markNotInterested(eventId: String, authorPubkey: String) {
        client.markNotInterested(eventId, authorPubkey)
    }

    internal val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── MLS message cache ────────────────────────────────────────────────────
    // Each Kind-445 event must only be passed to process_message() once;
    // re-processing causes MLS epoch mismatches. We track processed event IDs
    // per group and accumulate decrypted messages in memory for the session.
    internal val mlsProcessedIds = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    internal val mlsCachedMessages = java.util.concurrent.ConcurrentHashMap<String, MutableList<MlsMessage>>()

    internal val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    internal fun getOneHourAgo(): Long = System.currentTimeMillis() / 1000 - Constants.Time.HOUR_SECS
    internal fun getOneDayAgo(): Long = System.currentTimeMillis() / 1000 - Constants.Time.DAY_SECS

    // ─── External Signer helpers ──────────────────────────────────────────────

    /** True when the current user signs externally (Amber / NIP-55). */
    internal fun isExternalSigner(): Boolean = client.getSigner() is ExternalSigner

    /** The user's own hex pubkey. Converts npub/bech32 to hex if needed. */
    internal val myPubkeyHex: String get() {
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
    internal suspend fun signAndPublish(unsignedJson: String): Boolean =
        signAndPublishGetId(unsignedJson) != null

    /** Signs, publishes, and returns the event ID on success (null on failure). */
    internal suspend fun signAndPublishGetId(unsignedJson: String): String? {
        // signEvent() は Amber Intent 起動を伴うため Main スレッドで呼ぶ
        val signedJson = client.getSigner().signEvent(unsignedJson) ?: run {
            android.util.Log.w("NostrRepository", "signAndPublish: signer returned null")
            return null
        }
        val rustClient = client.getRustClient() ?: return null
        // publishRawEvent() は Rust FFI のブロッキング呼び出し — IO スレッドで実行してメイン ANR を防ぐ
        return withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                rustClient.publishRawEvent(signedJson)
                kotlinx.serialization.json.Json.parseToJsonElement(signedJson)
                    .jsonObject["id"]?.jsonPrimitive?.content
            } catch (e: Exception) {
                android.util.Log.w("NostrRepository", "signAndPublish: publish failed: ${e.message}")
                null
            }
        }
    }

    /**
     * Unified publish: creates a new signed event and broadcasts it.
     * - External signer (Amber): creates unsigned event → delegates signing to Amber → publishes raw.
     * - Internal signer: uses Rust client's publishEvent() directly.
     * Returns the event ID on success, null on failure.
     *
     * Use this for generic event kinds where no specialized Rust API exists.
     * For kinds with optimized Rust methods (react, repost, followUser, etc.) keep the if/else.
     */
    internal suspend fun publishNewEvent(
        kind: Int,
        content: String,
        tags: List<List<String>>
    ): String? {
        val rustClient = client.getRustClient() ?: return null
        return try {
            if (isExternalSigner()) {
                val unsigned = rustClient.createUnsignedEvent(kind.toUInt(), content, tags, myPubkeyHex)
                signAndPublishGetId(unsigned)
            } else {
                rustClient.publishEvent(kind.toUInt(), content, tags).takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "publishNewEvent(kind=$kind) failed: ${e.message}")
            null
        }
    }

    // ─── Profiles (shared — used by enrichPosts) ──────────────────────────────

    fun getCachedProfile(pubkeyHex: String): UserProfile? = cache.getCachedProfile(pubkeyHex)

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
    internal suspend fun fetchProfilesLegacy(
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
    internal fun uniffi.nurunuru.FfiUserProfile.toUserProfile() = UserProfile(
        pubkey = pubkey,
        name = name.nullIfEmpty(),
        displayName = displayName.nullIfEmpty(),
        about = about.nullIfEmpty(),
        picture = picture.nullIfEmpty(),
        nip05 = nip05.nullIfEmpty(),
        lud16 = lud16.nullIfEmpty()
        // banner, website, birthday, geohash not in FfiUserProfile — null for now
    )

    internal fun String.nullIfEmpty(): String? = ifEmpty { null }

    internal fun parseProfile(event: NostrEvent): UserProfile {
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

    // ─── Follow List (shared — used by timeline) ──────────────────────────────

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

    // ─── Zaps (shared — used by enrichPosts) ─────────────────────────────────

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

    internal fun parseZapAmount(event: NostrEvent): Long {
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

    // ─── Mute List (shared — used by timeline, helpers) ──────────────────────

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

    // ─── Post Enrichment (shared — used by all timeline/notifications) ────────

    /** Public entry point for live-stream enrichment from ViewModels. */
    suspend fun enrichPostsDirect(events: List<NostrEvent>): List<ScoredPost> = enrichPosts(events)

    /**
     * リレータブのライブ投稿にミュートフィルタのみ適用する。
     */
    fun scoreForRecommended(posts: List<ScoredPost>): List<ScoredPost> {
        if (posts.isEmpty()) return emptyList()
        val myPubkey = prefs.publicKeyHex ?: return emptyList()
        val muteData = getCachedMuteList(myPubkey) ?: return posts
        val mutedPks = muteData.pubkeys.toSet()
        val mutedIds = muteData.eventIds.toSet()
        return posts.filter { it.event.pubkey !in mutedPks && it.event.id !in mutedIds }
    }

    internal suspend fun enrichPosts(events: List<NostrEvent>): List<ScoredPost> = coroutineScope {
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
        val myReactionEvents = if (myPubkey != null) reactionEvents.filter { it.pubkey == myPubkey }
            .mapNotNull { ev -> ev.getTagValue("e")?.let { targetId -> targetId to ev.id } }.toMap() else emptyMap()
        val myLikes = myReactionEvents.keys

        val repostCounts = repostEvents.groupBy { it.getTagValue("e") ?: "" }.mapValues { it.value.size }
        val myRepostEventMap = if (myPubkey != null) repostEvents.filter { it.pubkey == myPubkey }
            .mapNotNull { ev -> ev.getTagValue("e")?.let { targetId -> targetId to ev.id } }.toMap() else emptyMap()
        val myReposts = myRepostEventMap.keys

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
                myLikeEventId = myReactionEvents[event.id],
                myRepostEventId = myRepostEventMap[event.id],
                isVerified = isVerified,
                repostedBy = if (repost != null) profiles[repost.pubkey] else null,
                repostTime = repost?.createdAt
            )
        }.sortedByDescending { it.repostTime ?: it.event.createdAt }
    }

    /**
     * リレーフィード用の軽量エンリッチ。プロファイルのみ取得する。
     * reactions/reposts/replies/zaps/NIP-05 は取得しない（高速化のため）。
     */
    internal suspend fun enrichPostsLight(events: List<NostrEvent>): List<ScoredPost> {
        if (events.isEmpty()) return emptyList()
        val pubkeys = events.map { it.pubkey }.distinct()
        val profiles = fetchProfiles(pubkeys)
        return events.map { event ->
            ScoredPost(
                event = event,
                profile = profiles[event.pubkey],
                likeCount = 0,
                repostCount = 0,
                replyCount = 0,
                zapAmount = 0L,
                score = 1.0,
                isLiked = false,
                isReposted = false
            )
        }
    }

    // ─── Relay helpers ────────────────────────────────────────────────────────

    /** 保存済みリレーの URL 一覧を返す（タイムラインリレー選択用）。NIP-65 優先、なければ general relays。 */
    fun getSavedRelayUrls(): List<String> {
        val nip65 = prefs.nip65Relays.map { it.url }
        return nip65.ifEmpty { prefs.relays.toList().sorted() }
    }

    /** リレーリストを JSON 文字列で返す（WebView ブリッジ用） */
    fun getRelaysJson(): String {
        val relays = prefs.relays.associateWith { mapOf("read" to true, "write" to true) }
        return json.encodeToString(relays)
    }

    // ── キャッシュ管理 ────────────────────────────────────────────────────────

    fun getCacheStats() = cache.getCacheStats()
    fun getCacheEntriesCount(typeId: String) = cache.getEntriesCount(typeId)
    fun clearExpiredCache() = cache.clearExpiredCache()
    fun clearCacheByType(typeId: String) {
        cache.clearByType(typeId)
        if (typeId == "mls_groups" || typeId == "mls_messages") {
            mlsCachedMessages.clear()
            mlsProcessedIds.clear()
        }
    }
    fun clearAllCache() {
        cache.clearAll()
        mlsCachedMessages.clear()
        mlsProcessedIds.clear()
    }
    fun applyCacheSettings() {
        cache.applySettings(prefs)
        android.util.Log.d("NostrRepository",
            "Cache settings applied: profile=${cache.profileEnabled}/${cache.profileTtl/86400000.0}d " +
            "timeline=${cache.timelineEnabled}/${cache.timelineTtl/86400000.0}d " +
            "notification=${cache.notificationEnabled}/${cache.notificationTtl/86400000.0}d " +
            "badge=${cache.badgeEnabled}/${cache.badgeTtl/86400000.0}d " +
            "mls_groups=${cache.mlsGroupsTtl/86400000.0}d " +
            "mls_messages=${cache.mlsMessagesTtl/86400000.0}d")
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

    companion object {
        // filter:image / filter:video 判定用ホスト・拡張子リスト
        val SEARCH_IMAGE_HOSTS = listOf(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif",
            "nostr.build", "void.cat", "imgur.com", "i.imgur.com",
            "image.nostr.build", "cdn.nostr.build",
        )
        val SEARCH_VIDEO_HOSTS = listOf(
            ".mp4", ".webm", ".mov", ".m3u8",
            "youtube.com", "youtu.be", "twitch.tv",
        )

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
}
