package io.nurunuru.app.data

import android.content.Context
import android.util.Log
import io.nurunuru.app.data.models.NostrEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import rust.nostr.sdk.PublicKey
import uniffi.nurunuru.NuruNuruClient
import uniffi.nurunuru.FfiScoredPost
import uniffi.nurunuru.FfiUserProfile
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "NostrClient"

/**
 * Nostr client powered by the NuruNuru Rust Core.
 */
class NostrClient(
    private val context: Context,
    private val relays: List<String>,
    private val signer: AppSigner
) {
    companion object {
        /** NIP-50 search relay (synced with web: lib/nostr.js SEARCH_RELAY) */
        const val SEARCH_RELAY = "wss://search.nos.today"
    }

    private var sdkClient: NuruNuruClient? = null
    private val _isReady = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isReady = _isReady.asStateFlow()

    private val publicKeyHex = signer.getPublicKeyHex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _events = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 100)
    val events: SharedFlow<NostrEvent> = _events.asSharedFlow()

    private val subscriptionCallbacks = ConcurrentHashMap<String, (NostrEvent) -> Unit>()

    // ─── Connection ───────────────────────────────────────────────────────────

    fun getSigner(): AppSigner = signer

    fun getRustClient(): NuruNuruClient? = sdkClient

    fun connect() {
        scope.launch {
            try {
                val client = if (signer is InternalSigner) {
                    val keyHex = privateKeyHexForSdk()
                    if (keyHex.isNullOrEmpty()) {
                        Log.e(TAG, "Key not available from SecureKeyManager, cannot initialize Rust Client")
                        return@launch
                    }
                    NuruNuruClient(keyHex)
                } else {
                    // Normalise pubkey to 64-char lowercase hex.
                    // prefs may store npub1... bech32 if the login flow stored it
                    // that way, or it may have been set from Amber as hex already.
                    val hexPubkey = if (publicKeyHex.startsWith("npub1")) {
                        try {
                            PublicKey.parse(publicKeyHex).toHex()
                        } catch (_: Exception) { publicKeyHex }
                    } else {
                        publicKeyHex
                    }
                    if (hexPubkey.length != 64 || hexPubkey.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) {
                        Log.e(TAG, "Invalid pubkey for Rust client (len=${hexPubkey.length}): ${hexPubkey.take(12)}…")
                        return@launch
                    }
                    NuruNuruClient.newReadOnly(hexPubkey)
                }

                sdkClient = client
                _isReady.value = true
                client.connect()

                Log.d(TAG, "NuruNuru Rust Client connected")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize NuruNuru Rust Client", e)
            }
        }
    }

    private fun privateKeyHexForSdk(): String? {
        return (signer as? InternalSigner)?.getKeyHexFromManager()
    }

    // signAndSend removed — all publishing now goes through rustClient directly

    private suspend fun ensureClient(): NuruNuruClient? {
        if (sdkClient == null) {
            withTimeoutOrNull(5000) {
                isReady.filter { it }.first()
            }
        }
        return sdkClient
    }

    fun disconnect() {
        scope.launch {
            sdkClient?.disconnect()
            sdkClient = null
            scope.cancel()
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
    )

    fun subscribe(subId: String, filter: Filter, onEvent: (NostrEvent) -> Unit) {
        subscriptionCallbacks[subId] = onEvent
        // TODO: Bridge with Rust core stream subscription
    }

    fun unsubscribe(subId: String) {
        subscriptionCallbacks.remove(subId)
    }

    // ─── One-shot Fetch ──────────────────────────────────────────────────────

    suspend fun fetchEvents(filter: Filter, timeoutMs: Long = 5_000): List<NostrEvent> {
        return withContext(Dispatchers.IO) {
            val client = ensureClient() ?: return@withContext emptyList()
            try {
                val filterJson = buildFilterJson(filter)
                val timeoutSecs = ((timeoutMs + 999) / 1000).toUInt()
                val eventsJson = client.fetchEventsFromRelay(filterJson, timeoutSecs)
                eventsJson.map { Json { ignoreUnknownKeys = true }.decodeFromString<NostrEvent>(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Fetch events failed: ${e.message}")
                emptyList()
            }
        }
    }

    /** Fetch events from specific relays only (e.g. NIP-50 search relay).
     *  The Rust engine already has the search relay configured, so this
     *  is equivalent to fetchEvents — the search relay is included automatically. */
    suspend fun fetchEventsFrom(
        relayUrls: List<String>,
        filter: Filter,
        timeoutMs: Long = 5_000
    ): List<NostrEvent> {
        return fetchEvents(filter, timeoutMs)
    }

    /** Serialise a [Filter] to a NIP-01 JSON filter string for the Rust FFI. */
    private fun buildFilterJson(filter: Filter): String {
        val obj = buildJsonObject {
            filter.ids?.takeIf { it.isNotEmpty() }?.let { ids ->
                put("ids", buildJsonArray { ids.forEach { add(it) } })
            }
            filter.authors?.takeIf { it.isNotEmpty() }?.let { authors ->
                put("authors", buildJsonArray { authors.forEach { add(it) } })
            }
            filter.kinds?.takeIf { it.isNotEmpty() }?.let { kinds ->
                put("kinds", buildJsonArray { kinds.forEach { add(it) } })
            }
            filter.since?.let { put("since", it) }
            filter.until?.let { put("until", it) }
            filter.limit?.let { put("limit", it) }
            filter.search?.let { put("search", it) }
            // Tag filters: keys like "e","p","d" → "#e","#p","#d" in NIP-01 JSON
            filter.tags?.forEach { (key, values) ->
                if (values.isNotEmpty()) {
                    put("#$key", buildJsonArray { values.forEach { add(it) } })
                }
            }
        }
        return obj.toString()
    }

    // ─── Publish ──────────────────────────────────────────────────────────────

    /** Publish a pre-signed Nostr event as-is (used for backup import). */
    suspend fun publishEvent(event: NostrEvent): Boolean {
        return withContext(Dispatchers.IO) {
            val client = ensureClient() ?: return@withContext false
            try {
                val jsonStr = Json { encodeDefaults = true }.encodeToString(event)
                client.publishRawEvent(jsonStr)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Publish raw event failed: ${e.message}")
                false
            }
        }
    }

    fun recordEngagement(action: String, authorPubkey: String) {
        sdkClient?.recordEngagement(action, authorPubkey)
    }

    fun markNotInterested(eventId: String, authorPubkey: String) {
        sdkClient?.markNotInterested(eventId, authorPubkey)
    }

    // ─── DMs ─────────────────────────────────────────────────────────────────

    suspend fun sendEncryptedDm(recipientPubkeyHex: String, content: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val client = ensureClient() ?: return@withContext false
                client.sendDm(recipientPubkeyHex, content)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    suspend fun decryptNip04(senderPubkeyHex: String, encryptedContent: String): String? {
        // InternalSigner: prefer Rust FFI (same Rust crate, avoids JNI round-trip to rust-nostr-sdk)
        if (signer is InternalSigner) {
            sdkClient?.let { client ->
                try { return client.nip04Decrypt(senderPubkeyHex, encryptedContent) }
                catch (_: Exception) { /* fall through to AppSigner */ }
            }
        }
        return signer.nip04Decrypt(senderPubkeyHex, encryptedContent)
    }

    suspend fun decryptNip44(senderPubkeyHex: String, encryptedContent: String): String? {
        if (signer is InternalSigner) {
            sdkClient?.let { client ->
                try { return client.nip44Decrypt(senderPubkeyHex, encryptedContent) }
                catch (_: Exception) { }
            }
        }
        return signer.nip44Decrypt(senderPubkeyHex, encryptedContent)
    }

    suspend fun encryptNip04(receiverPubkeyHex: String, content: String): String? {
        if (signer is InternalSigner) {
            sdkClient?.let { client ->
                try { return client.nip04Encrypt(receiverPubkeyHex, content) }
                catch (_: Exception) { }
            }
        }
        return signer.nip04Encrypt(receiverPubkeyHex, content)
    }

    suspend fun encryptNip44(receiverPubkeyHex: String, content: String): String? {
        if (signer is InternalSigner) {
            sdkClient?.let { client ->
                try { return client.nip44Encrypt(receiverPubkeyHex, content) }
                catch (_: Exception) { }
            }
        }
        return signer.nip44Encrypt(receiverPubkeyHex, content)
    }
}
