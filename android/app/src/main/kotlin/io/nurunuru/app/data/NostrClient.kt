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

// Lenient Json for parsing incoming relay messages (relays may add unknown fields)
private val RELAY_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

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

    // Track relay OK acknowledgments: eventId → list of (accepted, reason) deferreds per relay
    private val publishAcks = ConcurrentHashMap<String, MutableList<CompletableDeferred<Boolean>>>()

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
                reconnectAttempts[relayUrl] = 0
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
            val arr = RELAY_JSON.parseToJsonElement(text).jsonArray
            when (val type = arr[0].jsonPrimitive.content) {
                "EVENT" -> {
                    val subId = arr[1].jsonPrimitive.content
                    // Use lenient parsing: relays may include unknown extra fields
                    val event = RELAY_JSON.decodeFromJsonElement<NostrEvent>(arr[2])
                    if (event.id.isNotEmpty() && event.sig.isNotEmpty()) {
                        scope.launch { _events.emit(event) }
                        subscriptions[subId]?.onEvent?.invoke(event)
                    }
                }
                "EOSE" -> {
                    val subId = arr[1].jsonPrimitive.content
                    subscriptions[subId]?.onEose?.invoke(relayUrl)
                }
                "OK" -> {
                    val eventId = arr.getOrNull(1)?.jsonPrimitive?.content ?: return
                    val accepted = arr.getOrNull(2)?.jsonPrimitive?.booleanOrNull ?: true
                    val reason = arr.getOrNull(3)?.jsonPrimitive?.content ?: ""
                    if (!accepted) {
                        Log.w(TAG, "Event rejected by $relayUrl (${eventId.take(8)}…): $reason")
                    } else {
                        Log.d(TAG, "Event accepted by $relayUrl (${eventId.take(8)}…)")
                    }
                    // Complete the first pending ack deferred for this event
                    publishAcks[eventId]?.let { deferreds ->
                        synchronized(deferreds) {
                            val pending = deferreds.firstOrNull { !it.isCompleted }
                            pending?.complete(accepted)
                        }
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

    suspend fun fetchEvents(filter: Filter, timeoutMs: Long = 8_000): List<NostrEvent> =
        withContext(Dispatchers.IO) {
            val subId = "fetch_${System.nanoTime()}"
            val seenIds = ConcurrentHashMap.newKeySet<String>()
            val results = java.util.concurrent.CopyOnWriteArrayList<NostrEvent>()
            val deferred = CompletableDeferred<List<NostrEvent>>()

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

    /**
     * Publish a signed event and wait for relay acknowledgment.
     * Returns true if at least one relay accepted the event.
     * Times out after [ackTimeoutMs] and falls back to optimistic true if no relay responds.
     */
    suspend fun publishEvent(event: NostrEvent, ackTimeoutMs: Long = 5_000): Boolean =
        withContext(Dispatchers.IO) {
            if (connections.isEmpty()) {
                Log.w(TAG, "publishEvent: no relays connected")
                return@withContext false
            }

            val msg = JsonArray(listOf(
                JsonPrimitive("EVENT"),
                RELAY_JSON.encodeToJsonElement(event)
            )).toString()

            // Register ack deferreds – one per connected relay
            val ackDeferreds = connections.keys.map { CompletableDeferred<Boolean>() }
            if (event.id.isNotEmpty()) {
                publishAcks[event.id] = ackDeferreds.toMutableList()
            }

            val sent = connections.values.sumOf { ws -> if (ws.send(msg)) 1L else 0L }
            if (sent == 0L) {
                publishAcks.remove(event.id)
                Log.w(TAG, "publishEvent: send failed for ${event.id.take(8)}…")
                return@withContext false
            }

            // Wait until at least one relay accepts, or timeout
            return@withContext try {
                withTimeout(ackTimeoutMs) {
                    // Complete as soon as any relay accepts
                    val accepted = CompletableDeferred<Boolean>()
                    ackDeferreds.forEach { d ->
                        scope.launch {
                            if (d.await()) accepted.complete(true)
                        }
                    }
                    // Also complete with false if all relays reject
                    scope.launch {
                        ackDeferreds.forEach { it.join() }
                        if (!accepted.isCompleted) accepted.complete(false)
                    }
                    accepted.await()
                }
            } catch (e: TimeoutCancellationException) {
                Log.d(TAG, "publishEvent: no OK within ${ackTimeoutMs}ms, assuming accepted")
                true // Optimistic: relay likely accepted but slow to respond
            } finally {
                publishAcks.remove(event.id)
            }
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
            // NIP-18: ["e", event_id, relay_url] (3 elements)
            tags = listOf(listOf("e", eventId, relayUrl)),
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

    /**
     * NIP-01 canonical event serialization for ID computation.
     * Built manually to guarantee byte-exact output independent of JSON library behavior.
     * See: https://github.com/nostr-protocol/nostr/blob/master/01.md
     */
    private fun computeEventId(event: NostrEvent): String {
        val sb = StringBuilder()
        sb.append("[0,")
        sb.append(jsonString(event.pubkey))
        sb.append(',')
        sb.append(event.createdAt)
        sb.append(',')
        sb.append(event.kind)
        sb.append(',')
        // Tags array
        sb.append('[')
        event.tags.forEachIndexed { i, tag ->
            if (i > 0) sb.append(',')
            sb.append('[')
            tag.forEachIndexed { j, s ->
                if (j > 0) sb.append(',')
                sb.append(jsonString(s))
            }
            sb.append(']')
        }
        sb.append(']')
        sb.append(',')
        sb.append(jsonString(event.content))
        sb.append(']')
        return sha256(sb.toString().toByteArray(Charsets.UTF_8)).toHex()
    }

    /**
     * Encode a string as a JSON string literal, following NIP-01 requirements:
     * - Escape control chars, backslash, and double-quote
     * - Do NOT escape non-ASCII Unicode (keep UTF-8 as-is)
     */
    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"'  -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                in '\u0000'..'\u001F' -> sb.append("\\u%04x".format(ch.code))
                else -> sb.append(ch)   // Unicode kept as-is (NIP-01)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun signEvent(eventId: String, privKey: ByteArray): String {
        val msgBytes = eventId.hexToBytes()!!
        val sig = Secp256k1Impl.schnorrSign(msgBytes, privKey, null)
        return sig.toHex()
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
