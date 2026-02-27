package io.nurunuru.app.data

import android.content.Context
import android.util.Log
import io.nurunuru.app.data.models.NostrEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import rust.nostr.sdk.*
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "NostrClient"

/**
 * Nostr client powered by the official rust-nostr SDK (0.44.2).
 */
class NostrClient(
    private val context: Context,
    private val relays: List<String>,
    private val signer: AppSigner
) {
    private var sdkClient: Client? = null
    private val publicKeyHex = signer.getPublicKeyHex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _events = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 100)
    val events: SharedFlow<NostrEvent> = _events.asSharedFlow()

    private val subscriptionCallbacks = ConcurrentHashMap<String, (NostrEvent) -> Unit>()

    // ─── Connection ───────────────────────────────────────────────────────────

    fun getSigner(): AppSigner = signer

    fun connect() {
        scope.launch {
            try {
                // 1. Initialize nostrdb (LMDB) in the app's files directory (Android safe path)
                val dbPath = context.filesDir.absolutePath + "/nostrdb"
                val database = NostrDatabase.lmdb(dbPath)

                // 2. Setup internal SDK signer (dummy if external)
                val sdkSigner: NostrSigner = if (signer is InternalSigner) {
                    val keys = Keys.parse(privateKeyHexForSdk())
                    NostrSigner.keys(keys)
                } else {
                    // For external signers, we use a random key internally in the SDK Client
                    // because we intercept signing calls manually.
                    NostrSigner.keys(Keys.generate())
                }

                // 3. Build SDK Client
                val client = ClientBuilder()
                    .signer(sdkSigner)
                    .database(database)
                    .build()

                // 4. Add relays (handle emulator localhost -> 10.0.2.2)
                relays.forEach { url ->
                    val mappedUrl = mapLocalhost(url)
                    try {
                        client.addRelay(RelayUrl.parse(mappedUrl))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to add relay $mappedUrl: ${e.message}")
                    }
                }

                sdkClient = client
                client.connect()

                Log.d(TAG, "rust-nostr SDK Client connected")

                // Start listening for notifications
                startNotificationListener(client)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize rust-nostr Client", e)
            }
        }
    }

    private fun privateKeyHexForSdk(): String {
        return (signer as? InternalSigner)?.privateKeyHex ?: ""
    }

    private suspend fun signAndSend(builder: EventBuilder, targetRelays: List<String>? = null): Event? {
        val client = sdkClient ?: return null
        return try {
            // For internal signer and no target relays, we use client.sendEventBuilder
            // because it handles more things internally (like auth-required callbacks).
            if (signer is InternalSigner && targetRelays.isNullOrEmpty()) {
                val output = client.sendEventBuilder(builder)
                return client.database().eventById(output.id)
            }

            // Otherwise, manually sign and send
            val publicKey = PublicKey.parse(publicKeyHex)
            val unsignedEvent = builder.build(publicKey)
            val unsignedJson = unsignedEvent.asJson()

            Log.d(TAG, "Requesting signature for: $unsignedJson")
            val signedJson = signer.signEvent(unsignedJson) ?: run {
                Log.e(TAG, "Signer returned null")
                return null
            }

            val signedEvent = Event.fromJson(signedJson)
            if (targetRelays.isNullOrEmpty()) {
                client.sendEvent(signedEvent)
            } else {
                val urls = targetRelays.mapNotNull {
                    try { RelayUrl.parse(it) } catch (e: Exception) { null }
                }
                if (urls.isNotEmpty()) {
                    client.sendEventTo(urls, signedEvent)
                } else {
                    client.sendEvent(signedEvent)
                }
            }
            signedEvent
        } catch (e: Exception) {
            Log.e(TAG, "Sign and send failed", e)
            null
        }
    }

    suspend fun publish(kind: Int, content: String, tags: List<List<String>> = emptyList(), targetRelays: List<String>? = null): NostrEvent? {
        return withContext(Dispatchers.IO) {
            val sdkTags = tags.map { Tag.parse(it) }
            val builder = EventBuilder(Kind(kind.toUShort()), content).tags(sdkTags)
            signAndSend(builder, targetRelays)?.let { mapSdkEvent(it) }
        }
    }

    private fun startNotificationListener(client: Client) {
        scope.launch {
            // SDK 0.44.x: HandleNotification methods are suspend fun
            client.handleNotifications(object : HandleNotification {
                override suspend fun handle(relayUrl: RelayUrl, subscriptionId: String, event: Event) {
                    val nostrEvent = mapSdkEvent(event)
                    _events.emit(nostrEvent)
                    subscriptionCallbacks[subscriptionId]?.invoke(nostrEvent)
                }

                override suspend fun handleMsg(relayUrl: RelayUrl, message: RelayMessage) {
                    // Handle other relay messages if needed (EOSE, OK, etc.)
                }
            })
        }
    }

    fun disconnect() {
        scope.launch {
            sdkClient?.disconnect()
            sdkClient = null
            scope.cancel()
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun mapLocalhost(url: String): String {
        return if (url.contains("localhost") || url.contains("127.0.0.1")) {
            url.replace("localhost", "10.0.2.2").replace("127.0.0.1", "10.0.2.2")
        } else {
            url
        }
    }

    private fun mapSdkEvent(e: Event): NostrEvent {
        val json = Json { ignoreUnknownKeys = true }
        return json.decodeFromString<NostrEvent>(e.asJson())
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
        scope.launch {
            val client = sdkClient ?: return@launch
            val sdkFilter = mapFilter(filter)
            client.subscribe(sdkFilter)
        }
    }

    fun unsubscribe(subId: String) {
        subscriptionCallbacks.remove(subId)
    }

    private fun mapFilter(f: Filter): rust.nostr.sdk.Filter {
        var sdkF = rust.nostr.sdk.Filter()
        f.ids?.let { ids -> sdkF = sdkF.ids(ids.map { EventId.parse(it) }) }
        f.authors?.let { authors -> sdkF = sdkF.authors(authors.map { PublicKey.parse(it) }) }
        f.kinds?.let { kinds -> sdkF = sdkF.kinds(kinds.map { Kind(it.toUShort()) }) }
        f.since?.let { sdkF = sdkF.since(Timestamp.fromSecs(it.toULong())) }
        f.until?.let { sdkF = sdkF.until(Timestamp.fromSecs(it.toULong())) }
        f.limit?.let { sdkF = sdkF.limit(it.toULong()) }
        f.search?.let { sdkF = sdkF.search(it) }
        f.tags?.forEach { (tagName, values) ->
            try {
                val alphabet = Alphabet.valueOf(tagName.uppercase())
                val tag = SingleLetterTag.lowercase(alphabet)
                sdkF = sdkF.customTags(tag, values)
            } catch (e: Exception) {
            }
        }
        return sdkF
    }

    // ─── One-shot Fetch ──────────────────────────────────────────────────────

    suspend fun fetchEvents(filter: Filter, timeoutMs: Long = 5_000): List<NostrEvent> {
        val client = sdkClient ?: return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val sdkFilter = mapFilter(filter)
                val eventsWrapper = client.fetchEvents(sdkFilter, java.time.Duration.ofMillis(timeoutMs))
                // SDK 0.44: Use .toVec() to get a Kotlin List<Event>
                eventsWrapper.toVec().map { mapSdkEvent(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Fetch events failed: ${e.message}")
                emptyList()
            }
        }
    }

    // ─── Publish ──────────────────────────────────────────────────────────────

    suspend fun publishEvent(event: NostrEvent): Boolean {
        val client = sdkClient ?: return false
        return withContext(Dispatchers.IO) {
            try {
                val sdkEvent = Event.fromJson(serializeNostrEvent(event))
                client.sendEvent(sdkEvent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Publish raw event failed: ${e.message}")
                false
            }
        }
    }

    private fun serializeNostrEvent(e: NostrEvent): String {
        val json = Json { encodeDefaults = true }
        return json.encodeToString(e)
    }

    suspend fun publishNote(content: String, tags: List<List<String>> = emptyList()): NostrEvent? {
        return withContext(Dispatchers.IO) {
            val sdkTags = tags.map { Tag.parse(it) }
            val builder = EventBuilder.textNote(content).tags(sdkTags)
            signAndSend(builder)?.let { mapSdkEvent(it) }
        }
    }

    suspend fun publishReaction(eventId: String, emoji: String = "+"): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val id = EventId.parse(eventId)
                val tags = listOf(Tag.event(id))
                val builder = EventBuilder(Kind(7u), emoji).tags(tags)
                signAndSend(builder) != null
            } catch (e: Exception) {
                false
            }
        }
    }

    suspend fun publishRepost(eventId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val id = EventId.parse(eventId)
                val tags = listOf(Tag.event(id))
                val builder = EventBuilder(Kind(6u), "").tags(tags)
                signAndSend(builder) != null
            } catch (e: Exception) {
                false
            }
        }
    }

    // ─── DMs ─────────────────────────────────────────────────────────────────

    suspend fun sendEncryptedDm(recipientPubkeyHex: String, content: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val encrypted = encryptNip04(recipientPubkeyHex, content) ?: return@withContext false
                publish(4, encrypted, listOf(listOf("p", recipientPubkeyHex))) != null
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
