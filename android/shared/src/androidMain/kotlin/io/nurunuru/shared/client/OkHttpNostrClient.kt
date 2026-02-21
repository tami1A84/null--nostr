package io.nurunuru.shared.client

import io.nurunuru.shared.models.NostrEvent
import io.nurunuru.shared.platform.*
import io.nurunuru.shared.protocol.NostrFilter
import io.nurunuru.shared.protocol.NostrProtocol
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "OkHttpNostrClient"

/**
 * Android implementation of [INostrClient] using OkHttp WebSockets.
 *
 * Features mirroring Web's lib/connection-manager.js:
 *   - Multi-relay fan-out
 *   - Per-relay reconnect with 5s delay
 *   - Subscription re-registration on reconnect
 *   - Duplicate-event deduplication across relays
 */
class OkHttpNostrClient(
    private val relays: List<String>,
    private val privateKeyHex: String,
    override val publicKeyHex: String
) : INostrClient {

    constructor(relays: List<String>, privateKeyHex: String) : this(
        relays,
        privateKeyHex,
        NostrProtocol.getPublicKey(privateKeyHex) ?: throw IllegalArgumentException("Invalid private key")
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val connections = ConcurrentHashMap<String, WebSocket>()
    private val subscriptions = ConcurrentHashMap<String, ActiveSub>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val seenEventIds = ConcurrentHashMap.newKeySet<String>()

    private data class ActiveSub(
        val filter: NostrFilter,
        val onEvent: (NostrEvent) -> Unit,
        val onEose: () -> Unit
    )

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun connect() {
        relays.forEach { connectRelay(it) }
    }

    private fun connectRelay(url: String) {
        val req = Request.Builder().url(url).build()
        http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                logDebug(TAG, "Connected: $url")
                connections[url] = ws
                subscriptions.forEach { (id, sub) -> sendReq(ws, id, sub.filter) }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(url, text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                logWarning(TAG, "Relay failed: $url – ${t.message}")
                connections.remove(url)
                scope.launch {
                    delay(5_000)
                    connectRelay(url)
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                connections.remove(url)
            }
        })
    }

    override fun disconnect() {
        connections.values.forEach { it.close(1000, "disconnect") }
        connections.clear()
        scope.cancel()
    }

    // ─── Message handling ─────────────────────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun handleMessage(relay: String, text: String) {
        try {
            val arr = json.parseToJsonElement(text).jsonArray
            when (arr[0].jsonPrimitive.content) {
                "EVENT" -> {
                    val subId = arr[1].jsonPrimitive.content
                    val event = json.decodeFromJsonElement<NostrEvent>(arr[2])
                    // Deduplicate across relays
                    if (seenEventIds.add(event.id)) {
                        subscriptions[subId]?.onEvent?.invoke(event)
                    }
                }
                "EOSE" -> {
                    val subId = arr[1].jsonPrimitive.content
                    subscriptions[subId]?.onEose?.invoke()
                }
                "OK" -> logDebug(TAG, "OK from $relay: $text")
                "NOTICE" -> logDebug(TAG, "NOTICE from $relay: ${arr.getOrNull(1)}")
            }
        } catch (e: Exception) {
            logWarning(TAG, "Failed to parse relay message: ${e.message}")
        }
    }

    // ─── Subscriptions ────────────────────────────────────────────────────────

    override fun subscribe(
        subId: String,
        filter: NostrFilter,
        onEvent: (NostrEvent) -> Unit,
        onEose: () -> Unit
    ) {
        subscriptions[subId] = ActiveSub(filter, onEvent, onEose)
        connections.values.forEach { ws -> sendReq(ws, subId, filter) }
    }

    override fun unsubscribe(subId: String) {
        subscriptions.remove(subId)
        val msg = JsonArray(listOf(JsonPrimitive("CLOSE"), JsonPrimitive(subId))).toString()
        connections.values.forEach { it.send(msg) }
    }

    private fun sendReq(ws: WebSocket, subId: String, filter: NostrFilter) {
        val req = JsonArray(listOf(
            JsonPrimitive("REQ"),
            JsonPrimitive(subId),
            filter.toJsonObject()
        )).toString()
        ws.send(req)
    }

    // ─── Fetch (one-shot with EOSE timeout) ──────────────────────────────────

    override suspend fun fetchEvents(filter: NostrFilter, timeoutMs: Long): List<NostrEvent> =
        withContext(Dispatchers.IO) {
            val subId = "fetch_${currentTimeSeconds()}_${(0..9999).random()}"
            val results = mutableListOf<NostrEvent>()
            val deferred = CompletableDeferred<List<NostrEvent>>()
            var eoseCount = 0

            subscribe(
                subId,
                filter,
                onEvent = { event -> synchronized(results) { results.add(event) } },
                onEose = {
                    eoseCount++
                    // Complete after first EOSE (fast path) or all relays
                    if (!deferred.isCompleted) deferred.complete(results.toList())
                }
            )

            try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                results.toList()
            } finally {
                unsubscribe(subId)
            }
        }

    // ─── Publish ──────────────────────────────────────────────────────────────

    override suspend fun publishEvent(event: NostrEvent): Boolean = withContext(Dispatchers.IO) {
        val msg = JsonArray(listOf(JsonPrimitive("EVENT"), Json.encodeToJsonElement(event))).toString()
        connections.values.sumOf { if (it.send(msg)) 1L else 0L } > 0
    }

    override suspend fun publishNote(content: String, tags: List<List<String>>): NostrEvent? {
        val template = NostrEvent(
            kind = 1,
            content = content,
            tags = tags,
            pubkey = publicKeyHex,
            createdAt = currentTimeSeconds()
        )
        val signed = NostrProtocol.finalizeEvent(template, privateKeyHex) ?: return null
        publishEvent(signed)
        return signed
    }

    override suspend fun publishReaction(eventId: String, emoji: String): Boolean {
        val template = NostrEvent(
            kind = 7,
            content = emoji,
            tags = listOf(listOf("e", eventId)),
            pubkey = publicKeyHex,
            createdAt = currentTimeSeconds()
        )
        return NostrProtocol.finalizeEvent(template, privateKeyHex)?.let { publishEvent(it) } ?: false
    }

    override suspend fun publishRepost(eventId: String, relayUrl: String): Boolean {
        val template = NostrEvent(
            kind = 6,
            content = "",
            tags = listOf(listOf("e", eventId, relayUrl, "mention")),
            pubkey = publicKeyHex,
            createdAt = currentTimeSeconds()
        )
        return NostrProtocol.finalizeEvent(template, privateKeyHex)?.let { publishEvent(it) } ?: false
    }

    // ─── Encrypted DMs ────────────────────────────────────────────────────────

    override suspend fun sendEncryptedDm(recipientPubkeyHex: String, content: String): Boolean {
        val encrypted = NostrProtocol.encryptNip04(privateKeyHex, recipientPubkeyHex, content)
            ?: return false
        val template = NostrEvent(
            kind = 4,
            content = encrypted,
            tags = listOf(listOf("p", recipientPubkeyHex)),
            pubkey = publicKeyHex,
            createdAt = currentTimeSeconds()
        )
        return NostrProtocol.finalizeEvent(template, privateKeyHex)?.let { publishEvent(it) } ?: false
    }

    override fun decryptNip04(senderPubkeyHex: String, encryptedContent: String): String? =
        NostrProtocol.decryptNip04(privateKeyHex, senderPubkeyHex, encryptedContent)
}
