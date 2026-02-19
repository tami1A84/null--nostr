package io.nurunuru.app.data

import android.util.Log
import fr.acinq.secp256k1.Secp256k1
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
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "NostrClient"

/**
 * WebSocket-based Nostr protocol client (NIP-01).
 * Handles multi-relay connections, subscriptions, and event publishing.
 */
class NostrClient(
    private val relays: List<String>,
    private val privateKeyHex: String,
    private val publicKeyHex: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val connections = ConcurrentHashMap<String, WebSocket>()
    private val subscriptions = ConcurrentHashMap<String, SubscriptionCallback>()

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
                // Reconnect after delay
                scope.launch {
                    delay(5_000)
                    connectRelay(relayUrl)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connections.remove(relayUrl)
            }
        }
        client.newWebSocket(request, listener)
    }

    fun disconnect() {
        connections.values.forEach { it.close(1000, "disconnect") }
        connections.clear()
        scope.cancel()
    }

    // ─── Message Handling ─────────────────────────────────────────────────────

    private fun handleMessage(relayUrl: String, text: String) {
        try {
            val arr = Json.parseToJsonElement(text).jsonArray
            when (val type = arr[0].jsonPrimitive.content) {
                "EVENT" -> {
                    val event = Json.decodeFromJsonElement<NostrEvent>(arr[2])
                    scope.launch { _events.emit(event) }
                    val subId = arr[1].jsonPrimitive.content
                    subscriptions[subId]?.onEvent?.invoke(event)
                }
                "EOSE" -> {
                    val subId = arr[1].jsonPrimitive.content
                    subscriptions[subId]?.onEose?.invoke()
                }
                "OK" -> {
                    Log.d(TAG, "OK from $relayUrl: $text")
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
        val onEose: () -> Unit = {}
    )

    fun subscribe(subId: String, filter: Filter, onEvent: (NostrEvent) -> Unit, onEose: () -> Unit = {}) {
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

    suspend fun fetchEvents(filter: Filter, timeoutMs: Long = 5_000): List<NostrEvent> =
        withContext(Dispatchers.IO) {
            val subId = "fetch_${System.currentTimeMillis()}"
            val results = mutableListOf<NostrEvent>()
            val deferred = CompletableDeferred<List<NostrEvent>>()

            subscribe(
                subId,
                filter,
                onEvent = { event -> synchronized(results) { results.add(event) } },
                onEose = {
                    unsubscribe(subId)
                    if (!deferred.isCompleted) deferred.complete(results.toList())
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
        publishEvent(event)
        return event
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
            // Prepend 02 to get compressed pubkey for ECDH
            val compressedPub = byteArrayOf(0x02) + pubKeyBytes
            val sharedSecret = Secp256k1.ecdh(privKeyBytes, compressedPub)

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
            val compressedPub = byteArrayOf(0x02) + pubKeyBytes
            val sharedSecret = Secp256k1.ecdh(privKeyBytes, compressedPub)

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
        val sig = Secp256k1.signSchnorr(msgBytes, privKey, null)
        return sig.toHex()
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
