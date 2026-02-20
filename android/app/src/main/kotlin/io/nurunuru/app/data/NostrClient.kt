package io.nurunuru.app.data

import android.util.Log
import io.nurunuru.app.data.NostrKeyUtils.hexToBytes
import io.nurunuru.app.data.NostrKeyUtils.toHex
import io.nurunuru.app.data.models.NostrEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "NostrClient"
private const val MAX_RECONNECT_ATTEMPTS = 5
private val RECONNECT_DELAYS_MS = longArrayOf(5_000, 10_000, 20_000, 40_000, 80_000)

/**
 * WebSocket-based Nostr protocol client (NIP-01).
 * Handles multi-relay connections, subscriptions, and event publishing.
 */
class NostrClient(
    private val relays: List<String>,
    private val privateKeyHex: String,
    private val publicKeyHex: String
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val connections = ConcurrentHashMap<String, WebSocket>()
    private val subscriptions = ConcurrentHashMap<String, SubscriptionCallback>()

    // Track reconnect jobs and attempt counts per relay
    private val reconnectJobs = ConcurrentHashMap<String, Job>()
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()

    private val _events = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 100)
    val events: SharedFlow<NostrEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ─── Connection ───────────────────────────────────────────────────────────

    fun connect() {
        relays.forEach { relay -> connectRelay(relay) }
    }

    private fun connectRelay(relayUrl: String) {
        val request = Request.Builder().url(relayUrl).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected: $relayUrl")
                connections[relayUrl] = webSocket
                reconnectAttempts[relayUrl] = 0 // Reset on successful connect
                // Re-send active subscriptions on reconnect
                subscriptions.forEach { (subId, callback) ->
                    sendReq(webSocket, subId, callback.filter)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(relayUrl, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Relay failed: $relayUrl – ${t.message}")
                connections.remove(relayUrl)
                scheduleReconnect(relayUrl)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Relay closed: $relayUrl code=$code")
                connections.remove(relayUrl)
                if (code != 1000) {
                    // Abnormal close; try to reconnect
                    scheduleReconnect(relayUrl)
                }
            }
        }
        httpClient.newWebSocket(request, listener)
    }

    private fun scheduleReconnect(relayUrl: String) {
        val attempt = reconnectAttempts.getOrDefault(relayUrl, 0)
        if (attempt >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Giving up on relay after $attempt attempts: $relayUrl")
            return
        }
        // Cancel any pending reconnect for this relay
        reconnectJobs[relayUrl]?.cancel()

        val delayMs = RECONNECT_DELAYS_MS[attempt.coerceAtMost(RECONNECT_DELAYS_MS.size - 1)]
        Log.d(TAG, "Scheduling reconnect #${attempt + 1} to $relayUrl in ${delayMs}ms")

        reconnectJobs[relayUrl] = scope.launch {
            delay(delayMs)
            if (isActive) {
                reconnectAttempts[relayUrl] = attempt + 1
                connectRelay(relayUrl)
            }
        }
    }

    fun disconnect() {
        // Cancel all pending reconnect jobs first
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        connections.values.forEach { it.close(1000, "disconnect") }
        connections.clear()
        scope.cancel()
    }

    val connectedRelayCount: Int get() = connections.size

    // ─── Message Handling ─────────────────────────────────────────────────────

    private fun handleMessage(relayUrl: String, text: String) {
        try {
            val arr = Json.parseToJsonElement(text).jsonArray
            when (val type = arr[0].jsonPrimitive.content) {
                "EVENT" -> {
                    val subId = arr[1].jsonPrimitive.content
                    val event = Json.decodeFromJsonElement<NostrEvent>(arr[2])
                    scope.launch { _events.emit(event) }
                    subscriptions[subId]?.onEvent?.invoke(event)
                }
                "EOSE" -> {
                    val subId = arr[1].jsonPrimitive.content
                    subscriptions[subId]?.onEose?.invoke(relayUrl)
                }
                "OK" -> {
                    val accepted = arr.getOrNull(2)?.jsonPrimitive?.booleanOrNull ?: true
                    if (!accepted) {
                        Log.w(TAG, "Event rejected by $relayUrl: ${arr.getOrNull(3)}")
                    }
                }
                "NOTICE" -> {
                    Log.d(TAG, "NOTICE from $relayUrl: ${arr.getOrNull(1)}")
                }
                else -> Log.d(TAG, "Unknown message type $type from $relayUrl")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse message from $relayUrl: ${e.message}")
        }
    }

    // ─── Subscriptions ────────────────────────────────────────────────────────

    data class Filter(
        val ids: List<String>? = null,
        val authors: List<String>? = null,
        val kinds: List<Int>? = null,
        val tags: Map<String, List<String>>? = null,
        val since: Long? = null,
        val until: Long? = null,
        val limit: Int? = null,
        val search: String? = null
    ) {
        fun toJsonObject(): JsonObject = buildJsonObject {
            ids?.let { put("ids", JsonArray(it.map { id -> JsonPrimitive(id) })) }
            authors?.let { put("authors", JsonArray(it.map { a -> JsonPrimitive(a) })) }
            kinds?.let { put("kinds", JsonArray(it.map { k -> JsonPrimitive(k) })) }
            tags?.forEach { (tag, values) ->
                put("#$tag", JsonArray(values.map { v -> JsonPrimitive(v) }))
            }
            since?.let { put("since", JsonPrimitive(it)) }
            until?.let { put("until", JsonPrimitive(it)) }
            limit?.let { put("limit", JsonPrimitive(it)) }
            search?.let { put("search", JsonPrimitive(it)) }
        }
    }

    data class SubscriptionCallback(
        val filter: Filter,
        val onEvent: (NostrEvent) -> Unit,
        // relayUrl passed so callers can count per-relay EOSEs
        val onEose: (String) -> Unit = {}
    )

    fun subscribe(
        subId: String,
        filter: Filter,
        onEvent: (NostrEvent) -> Unit,
        onEose: (String) -> Unit = {}
    ) {
        subscriptions[subId] = SubscriptionCallback(filter, onEvent, onEose)
        connections.values.forEach { ws -> sendReq(ws, subId, filter) }
    }

    fun unsubscribe(subId: String) {
        subscriptions.remove(subId)
        val msg = JsonArray(listOf(JsonPrimitive("CLOSE"), JsonPrimitive(subId))).toString()
        connections.values.forEach { it.send(msg) }
    }

    private fun sendReq(ws: WebSocket, subId: String, filter: Filter) {
        val req = JsonArray(listOf(
            JsonPrimitive("REQ"),
            JsonPrimitive(subId),
            filter.toJsonObject()
        ))
        ws.send(req.toString())
    }

    // ─── Fetch (one-shot with EOSE) ───────────────────────────────────────────

    /**
     * Fetch events matching filter, waiting for EOSE from ALL connected relays
     * (or until timeout). Events are deduplicated by ID.
     */
    suspend fun fetchEvents(filter: Filter, timeoutMs: Long = 8_000): List<NostrEvent> =
        withContext(Dispatchers.IO) {
            val subId = "fetch_${System.nanoTime()}"
            // Thread-safe dedup set
            val seenIds = ConcurrentHashMap.newKeySet<String>()
            val results = java.util.concurrent.CopyOnWriteArrayList<NostrEvent>()
            val deferred = CompletableDeferred<List<NostrEvent>>()

            // Count how many relays we expect EOSE from
            val expectedEose = connections.size.coerceAtLeast(1)
            val eoseCount = AtomicInteger(0)

            subscribe(
                subId,
                filter,
                onEvent = { event ->
                    if (event.id.isNotEmpty() && seenIds.add(event.id)) {
                        results.add(event)
                    }
                },
                onEose = { _ ->
                    if (eoseCount.incrementAndGet() >= expectedEose) {
                        unsubscribe(subId)
                        if (!deferred.isCompleted) deferred.complete(results.toList())
                    }
                }
            )

            try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                unsubscribe(subId)
                results.toList()
            }
        }

    // ─── Publish ──────────────────────────────────────────────────────────────

    suspend fun publishEvent(event: NostrEvent): Boolean = withContext(Dispatchers.IO) {
        val json = JsonArray(listOf(JsonPrimitive("EVENT"), Json.encodeToJsonElement(event)))
        val msg = json.toString()
        val sent = connections.values.sumOf { ws ->
            if (ws.send(msg)) 1L else 0L
        }
        sent > 0
    }

    suspend fun publishNote(content: String, tags: List<List<String>> = emptyList()): NostrEvent? {
        val event = createEvent(NostrEvent(
            kind = 1,
            content = content,
            tags = tags,
            pubkey = publicKeyHex,
            createdAt = System.currentTimeMillis() / 1000
        )) ?: return null
        return if (publishEvent(event)) event else null
    }

    suspend fun publishReaction(eventId: String, emoji: String = "+"): Boolean {
        val event = createEvent(NostrEvent(
            kind = 7,
            content = emoji,
            tags = listOf(listOf("e", eventId)),
            pubkey = publicKeyHex,
            createdAt = System.currentTimeMillis() / 1000
        )) ?: return false
        return publishEvent(event)
    }

    suspend fun publishRepost(eventId: String, relayUrl: String = ""): Boolean {
        val event = createEvent(NostrEvent(
            kind = 6,
            content = "",
            tags = listOf(listOf("e", eventId, relayUrl, "mention")),
            pubkey = publicKeyHex,
            createdAt = System.currentTimeMillis() / 1000
        )) ?: return false
        return publishEvent(event)
    }

    // ─── NIP-04 Encrypted DM ─────────────────────────────────────────────────

    suspend fun sendEncryptedDm(recipientPubkeyHex: String, content: String): Boolean {
        val encrypted = encryptNip04(recipientPubkeyHex, content) ?: return false
        val event = createEvent(NostrEvent(
            kind = 4,
            content = encrypted,
            tags = listOf(listOf("p", recipientPubkeyHex)),
            pubkey = publicKeyHex,
            createdAt = System.currentTimeMillis() / 1000
        )) ?: return false
        return publishEvent(event)
    }

    fun decryptNip04(senderPubkeyHex: String, encryptedContent: String): String? {
        return try {
            val privKeyBytes = privateKeyHex.hexToBytes() ?: return null
            val pubKeyBytes = senderPubkeyHex.hexToBytes() ?: return null
            val sharedSecret = Secp256k1Impl.ecdhNip04(privKeyBytes, pubKeyBytes)

            val parts = encryptedContent.split("?iv=")
            if (parts.size != 2) return null
            val ciphertext = android.util.Base64.decode(parts[0], android.util.Base64.DEFAULT)
            val iv = android.util.Base64.decode(parts[1], android.util.Base64.DEFAULT)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "NIP-04 decrypt failed: ${e.message}")
            null
        }
    }

    private fun encryptNip04(recipientPubkeyHex: String, content: String): String? {
        return try {
            val privKeyBytes = privateKeyHex.hexToBytes() ?: return null
            val pubKeyBytes = recipientPubkeyHex.hexToBytes() ?: return null
            val sharedSecret = Secp256k1Impl.ecdhNip04(privKeyBytes, pubKeyBytes)

            val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(iv))
            val encrypted = cipher.doFinal(content.toByteArray(Charsets.UTF_8))

            val encB64 = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
            val ivB64 = android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP)
            "$encB64?iv=$ivB64"
        } catch (e: Exception) {
            Log.w(TAG, "NIP-04 encrypt failed: ${e.message}")
            null
        }
    }

    // ─── Event Signing ────────────────────────────────────────────────────────

    private fun createEvent(template: NostrEvent): NostrEvent? {
        return try {
            val privKeyBytes = privateKeyHex.hexToBytes() ?: return null
            val id = computeEventId(template)
            val sig = signEvent(id, privKeyBytes)
            template.copy(id = id, sig = sig)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create event: ${e.message}")
            null
        }
    }

    private fun computeEventId(event: NostrEvent): String {
        val serialized = buildJsonArray {
            add(0)
            add(event.pubkey)
            add(event.createdAt)
            add(event.kind)
            add(JsonArray(event.tags.map { tag ->
                JsonArray(tag.map { JsonPrimitive(it) })
            }))
            add(event.content)
        }.toString()
        return sha256(serialized.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun signEvent(eventId: String, privKey: ByteArray): String {
        val msgBytes = eventId.hexToBytes()!!
        val sig = Secp256k1Impl.schnorrSign(msgBytes, privKey, null)
        return sig.toHex()
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
