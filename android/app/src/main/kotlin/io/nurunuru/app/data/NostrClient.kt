package io.nurunuru.app.data

import android.content.Context
import android.util.Log
import io.nurunuru.app.data.models.NostrEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
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
                    NuruNuruClient.newReadOnly(publicKeyHex)
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

    private suspend fun signAndSend(content: String, kind: Int = 1): String? {
        val client = sdkClient ?: return null
        return try {
            if (signer is InternalSigner) {
                // Rust core handles internal signing
                client.publishNote(content)
            } else {
                // External signing flow
                val unsignedJson = client.createUnsignedNote(publicKeyHex, content)
                Log.d(TAG, "Requesting external signature for: $unsignedJson")
                val signedJson = signer.signEvent(unsignedJson) ?: return null
                client.publishRawEvent(signedJson)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sign and send failed", e)
            null
        }
    }

    private suspend fun ensureClient(): NuruNuruClient? {
        if (sdkClient == null) {
            withTimeoutOrNull(5000) {
                isReady.filter { it }.first()
            }
        }
        return sdkClient
    }

    suspend fun publish(kind: Int, content: String, tags: List<List<String>> = emptyList(), targetRelays: List<String>? = null): NostrEvent? {
        return withContext(Dispatchers.IO) {
            val client = ensureClient() ?: return@withContext null
            val eventId = signAndSend(content, kind) ?: return@withContext null

            // For a complete implementation, we would fetch the event back from nostrdb.
            // For this hybrid phase, we'll return a minimal event if found.
            val eventsJson = client.queryLocal(listOf(publicKeyHex), 1u)
            if (eventsJson.isNotEmpty()) {
                Json.decodeFromString<NostrEvent>(eventsJson[0])
            } else null
        }
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
                // Bridge with Rust fetch methods
                // For now, using query_local as a placeholder for local cache
                val eventsJson = client.queryLocal(filter.authors, (filter.limit ?: 10).toUInt())
                eventsJson.map { Json.decodeFromString<NostrEvent>(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Fetch events failed: ${e.message}")
                emptyList()
            }
        }
    }

    /** Fetch events from specific relays only (e.g. NIP-50 search relay). */
    suspend fun fetchEventsFrom(
        relayUrls: List<String>,
        filter: Filter,
        timeoutMs: Long = 5_000
    ): List<NostrEvent> {
        return fetchEvents(filter, timeoutMs)
    }

    // ─── Publish ──────────────────────────────────────────────────────────────

    suspend fun publishEvent(event: NostrEvent): Boolean {
        return withContext(Dispatchers.IO) {
            val client = ensureClient() ?: return@withContext false
            try {
                val jsonStr = Json.encodeToString(event)
                client.publishRawEvent(jsonStr)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Publish raw event failed: ${e.message}")
                false
            }
        }
    }

    suspend fun publishNote(content: String, tags: List<List<String>> = emptyList()): NostrEvent? {
        return publish(1, content, tags)
    }

    suspend fun publishReaction(eventId: String, emoji: String = "+"): Boolean {
        return publishNote("reacted $emoji to $eventId") != null
    }

    suspend fun publishRepost(eventId: String): Boolean {
        return publishNote("reposted $eventId") != null
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
        return signer.nip04Decrypt(senderPubkeyHex, encryptedContent)
    }

    suspend fun decryptNip44(senderPubkeyHex: String, encryptedContent: String): String? {
        return signer.nip44Decrypt(senderPubkeyHex, encryptedContent)
    }

    suspend fun encryptNip04(receiverPubkeyHex: String, content: String): String? {
        return signer.nip04Encrypt(receiverPubkeyHex, content)
    }

    suspend fun encryptNip44(receiverPubkeyHex: String, content: String): String? {
        return signer.nip44Encrypt(receiverPubkeyHex, content)
    }
}
