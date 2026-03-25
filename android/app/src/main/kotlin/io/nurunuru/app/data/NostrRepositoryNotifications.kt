package io.nurunuru.app.data

import io.nurunuru.app.data.models.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

// ─── Notifications ────────────────────────────────────────────────────────────

/** Fetch notifications (reactions + zaps targeting the user). Cache-first, 1-day window. */
suspend fun NostrRepository.fetchNotifications(pubkeyHex: String, limit: Int = 50, skipCache: Boolean = false): NotificationResult {
    // キャッシュヒット時は即返す（skipCache=true のときはスキップ）
    if (!skipCache) {
        cache.getCachedNotifications(pubkeyHex)?.let { cached ->
            try {
                val result = json.decodeFromString<NotificationResult>(cached)
                if (result.items.isNotEmpty()) {
                    android.util.Log.d("NostrRepository", "notifications cache hit: ${result.items.size}")
                    return result
                }
            } catch (_: Exception) { }
        }
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

    // 3. Fetch reposts of my posts (Kind 6 #p tag)
    val repostFilter = NostrClient.Filter(
        kinds = listOf(NostrKind.REPOST),
        tags = mapOf("p" to listOf(pubkeyHex)),
        since = oneDayAgo,
        limit = limit
    )

    // 4. Fetch replies to my posts (Kind 1 #p tag)
    val replyFilter = NostrClient.Filter(
        kinds = listOf(NostrKind.TEXT_NOTE),
        tags = mapOf("p" to listOf(pubkeyHex)),
        since = oneDayAgo,
        limit = limit
    )

    // 5. Fetch badge awards (Kind 8 #p tag)
    val badgeFilter = NostrClient.Filter(
        kinds = listOf(NostrKind.BADGE_AWARD),
        tags = mapOf("p" to listOf(pubkeyHex)),
        since = oneDayAgo,
        limit = limit
    )

    val enabledKinds = prefs.notificationEnabledKinds
    val emojiReactionEnabled = prefs.notificationEmojiReactionEnabled
    android.util.Log.d("NostrRepository",
        "fetchNotifications: enabledKinds=${enabledKinds.sorted()} emojiReaction=$emojiReactionEnabled")

    // Use NIP-65 read relays if configured; fall back to default client.fetchEvents
    val readRelayUrls = prefs.nip65Relays.filter { it.read }.map { it.url }.takeIf { it.isNotEmpty() }
    android.util.Log.d("NostrRepository", "fetchNotifications: readRelays=${readRelayUrls?.size ?: 0}")

    suspend fun fetchWith(filter: NostrClient.Filter): List<NostrEvent> {
        return if (readRelayUrls != null) {
            fetchNotificationEventsFromRelays(readRelayUrls, filter, timeoutMs = 6_000)
        } else {
            client.fetchEvents(filter, timeoutMs = 5_000)
        }
    }

    val reactions = if (NostrKind.REACTION in enabledKinds || emojiReactionEnabled)
        fetchWith(reactionFilter) else emptyList()
    val zaps = if (NostrKind.ZAP_RECEIPT in enabledKinds)
        fetchWith(zapFilter) else emptyList()
    val reposts = if (NostrKind.REPOST in enabledKinds)
        fetchWith(repostFilter) else emptyList()
    val mentions = if (NostrKind.TEXT_NOTE in enabledKinds)
        fetchWith(replyFilter) else emptyList()
    val badges = if (NostrKind.BADGE_AWARD in enabledKinds)
        fetchWith(badgeFilter) else emptyList()
    android.util.Log.d("NostrRepository",
        "fetchNotifications: reactions=${reactions.size} zaps=${zaps.size} reposts=${reposts.size} mentions=${mentions.size} badges=${badges.size}")

    // Build notification items
    val notificationItems = mutableListOf<NotificationItem>()
    val targetEventIds = mutableSetOf<String>()
    val notifierPubkeys = mutableSetOf<String>()

    for (event in reactions) {
        if (event.pubkey == pubkeyHex) continue // Skip self-reactions
        val targetEvent = event.getTagValue("e")
        val emojiTag = event.tags.firstOrNull { it.getOrNull(0) == "emoji" }
        val emojiUrl = emojiTag?.getOrNull(2)
        val content = event.content.ifBlank { "+" }
        val isEmoji = emojiUrl != null ||
            (content.startsWith(":") && content.endsWith(":") && content.length > 2)

        if (isEmoji && !emojiReactionEnabled) continue
        if (!isEmoji && NostrKind.REACTION !in enabledKinds) continue

        targetEvent?.let { targetEventIds.add(it) }
        notifierPubkeys.add(event.pubkey)

        notificationItems.add(
            NotificationItem(
                id = event.id,
                pubkey = event.pubkey,
                type = if (isEmoji) "emoji_reaction" else "reaction",
                createdAt = event.createdAt,
                targetEventId = targetEvent,
                comment = content.takeIf { it != "+" && it != "-" && it.isNotBlank() },
                emojiUrl = emojiUrl,
                reactionEmoji = content
            )
        )
    }

    for (event in zaps) {
        val targetEvent = event.getTagValue("e")
        targetEvent?.let { targetEventIds.add(it) }

        // Parse zap amount from bolt11 tag
        val bolt11 = event.getTagValue("bolt11") ?: ""
        val amount = NostrRepository.parseBolt11Amount(bolt11)

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

    for (event in reposts) {
        if (event.pubkey == pubkeyHex) continue
        val targetEvent = event.getTagValue("e")
        targetEvent?.let { targetEventIds.add(it) }
        notifierPubkeys.add(event.pubkey)

        notificationItems.add(
            NotificationItem(
                id = event.id,
                pubkey = event.pubkey,
                type = "repost",
                createdAt = event.createdAt,
                targetEventId = targetEvent
            )
        )
    }

    for (event in mentions) {
        if (event.pubkey == pubkeyHex) continue
        // Distinguish reply (has "e" tag) vs plain mention (only "p" tag)
        val targetEvent = event.getTagValue("e")
        targetEvent?.let { targetEventIds.add(it) }
        notifierPubkeys.add(event.pubkey)

        val type = if (targetEvent != null) "reply" else "mention"
        notificationItems.add(
            NotificationItem(
                id = event.id,
                pubkey = event.pubkey,
                type = type,
                createdAt = event.createdAt,
                targetEventId = targetEvent,
                comment = event.content.take(100).ifBlank { null }
            )
        )
    }

    for (event in badges) {
        if (event.pubkey == pubkeyHex) continue
        notifierPubkeys.add(event.pubkey)
        // バッジ名: "a" タグ "30009:pubkey:d-tag" の d-tag 部分
        val aRef = event.tags.find { it.getOrNull(0) == "a" && it.getOrNull(1)?.startsWith("30009:") == true }
        val badgeName = aRef?.getOrNull(1)?.split(":")?.drop(2)?.joinToString(":") ?: ""
        notificationItems.add(
            NotificationItem(
                id = event.id,
                pubkey = event.pubkey,
                type = "badge",
                createdAt = event.createdAt,
                comment = badgeName.ifBlank { null }
            )
        )
    }

    // Fetch original posts
    val originalPosts = mutableMapOf<String, NostrEvent>()
    if (targetEventIds.isNotEmpty()) {
        val postsFilter = NostrClient.Filter(
            ids = targetEventIds.take(50).toList()
        )
        val posts = client.fetchEvents(postsFilter, timeoutMs = 4_000)
        posts.forEach { originalPosts[it.id] = it }
    }

    // Fetch notifier profiles
    val profiles = fetchProfiles(notifierPubkeys.toList())

    // Sort by time descending
    val sorted = notificationItems.sortedByDescending { it.createdAt }

    val result = NotificationResult(sorted, profiles, originalPosts)

    // キャッシュに保存（1日有効）
    try {
        cache.setCachedNotifications(pubkeyHex, json.encodeToString(NotificationResult.serializer(), result))
    } catch (_: Exception) { }

    return result
}

/**
 * Fetch notification events from specific relay URLs via OkHttp WebSocket.
 * Connects to each relay in parallel and aggregates results.
 */
private suspend fun NostrRepository.fetchNotificationEventsFromRelays(
    relayUrls: List<String>,
    filter: NostrClient.Filter,
    timeoutMs: Long = 6_000
): List<NostrEvent> = coroutineScope {
    val filterJson = buildString {
        append("{")
        filter.kinds?.let { append("\"kinds\":${it},") }
        filter.since?.let { append("\"since\":$it,") }
        filter.limit?.let { append("\"limit\":$it,") }
        filter.tags?.forEach { (key, values) ->
            append("\"#$key\":${values.map { "\"$it\"" }},")
        }
        if (endsWith(",")) deleteCharAt(length - 1)
        append("}")
    }

    val allEvents = java.util.concurrent.ConcurrentHashMap<String, NostrEvent>()

    relayUrls.map { relayUrl ->
        async(Dispatchers.IO) {
            try {
                val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                val subId = "notif-${System.currentTimeMillis()}-${relayUrl.hashCode()}"
                val reqMsg = """["REQ","$subId",$filterJson]"""
                val wsClient = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder().url(relayUrl).build()
                val listener = object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) { ws.send(reqMsg) }
                    override fun onMessage(ws: WebSocket, text: String) {
                        if (done.isCompleted) return
                        try {
                            val arr = Json.parseToJsonElement(text).jsonArray
                            when (arr[0].jsonPrimitive.content) {
                                "EVENT" -> {
                                    val ev = Json { ignoreUnknownKeys = true }
                                        .decodeFromString<NostrEvent>(arr[2].toString())
                                    allEvents[ev.id] = ev
                                }
                                "EOSE" -> { ws.close(1000, "done"); done.complete(Unit) }
                            }
                        } catch (_: Exception) {}
                    }
                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        if (!done.isCompleted) done.complete(Unit)
                    }
                    override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                        if (!done.isCompleted) done.complete(Unit)
                    }
                }
                val ws = wsClient.newWebSocket(request, listener)
                try {
                    withTimeout(timeoutMs) { done.await() }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) { }
                ws.cancel()
            } catch (_: Exception) {}
        }
    }.awaitAll()

    allEvents.values.toList()
}

/** Fetch a specific event by ID. */
suspend fun NostrRepository.fetchEvent(eventId: String): ScoredPost? {
    val filter = NostrClient.Filter(
        ids = listOf(eventId),
        limit = 1
    )
    val events = client.fetchEvents(filter, timeoutMs = 4_000)
    return enrichPosts(events).firstOrNull()
}
