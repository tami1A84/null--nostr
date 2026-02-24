package io.nurunuru.app.data

import android.content.Context
import android.util.Log
import io.nurunuru.app.data.models.NostrEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rust.nostr.sdk.*
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "NostrClient"

/**
 * Nostr client powered by the official rust-nostr SDK.
 * Handles relay connections, persistence via nostrdb (LMDB), and event flow.
 */
class NostrClient(
    private val context: Context,
    private val relays: List<String>,
    private val privateKeyHex: String,
    private val publicKeyHex: String
) {
    private var sdkClient: Client? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _events = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 100)
    val events: SharedFlow<NostrEvent> = _events.asSharedFlow()

    private val subscriptionCallbacks = ConcurrentHashMap<String, (NostrEvent) -> Unit>()

    // ─── Connection ───────────────────────────────────────────────────────────

    fun connect() {
        scope.launch {
            try {
                // 1. Initialize nostrdb (LMDB) in the app's files directory
                val dbPath = context.filesDir.absolutePath + "/nostrdb"
                val database = NostrDatabase.lmdb(dbPath)

                // 2. Setup signer
                val keys = Keys.parse(privateKeyHex)

                // 3. Build SDK Client
                val client = ClientBuilder()
                    .signer(keys)
                    .database(database)
                    .build()

                // 4. Add relays (handle emulator localhost -> 10.0.2.2)
                relays.forEach { url ->
                    val mappedUrl = mapLocalhost(url)
                    try {
                        client.addRelay(mappedUrl)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to add relay $mappedUrl: ${e.message}")
                    }
                }

                sdkClient = client
                client.connect()

                Log.d(TAG, "rust-nostr SDK Client connected")

                // Start listening for notifications (incoming events)
                handleNotifications(client)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize rust-nostr Client", e)
            }
        }
    }

    private fun handleNotifications(client: Client) {
        scope.launch {
            client.handleNotifications(object : HandleNotification {
                override fun handle(notification: RelayPoolNotification) {
                    when (notification) {
                        is RelayPoolNotification.Event -> {
                            val sdkEvent = notification.event
                            val event = mapSdkEvent(sdkEvent)
                            scope.launch { _events.emit(event) }

                            // Trigger subscription callbacks
                            subscriptionCallbacks.values.forEach { callback ->
                                callback(event)
                            }
                        }
                        else -> {}
                    }
                }

                override fun handleMsg(message: RelayMessage) {
                    // Optional: handle low-level messages
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
        return NostrEvent(
            id = e.id().asHex(),
            pubkey = e.author().asHex(),
            createdAt = e.createdAt().asSecs().toLong(),
            kind = e.kind().asLong().toInt(),
            tags = e.tags().toList().map { it.asVector().toList() },
            content = e.content(),
            sig = e.signature().asHex()
        )
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
            client.subscribe(listOf(sdkFilter))
        }
    }

    fun unsubscribe(subId: String) {
        subscriptionCallbacks.remove(subId)
        // SDK client doesn't always need explicit unsubscribe for simple flows,
        // but we can add it if we want to send CLOSE.
    }

    private fun mapFilter(f: Filter): rust.nostr.sdk.Filter {
        var sdkF = rust.nostr.sdk.Filter()
        f.ids?.let { ids -> sdkF = sdkF.ids(ids.map { EventId.parse(it) }) }
        f.authors?.let { authors -> sdkF = sdkF.authors(authors.map { PublicKey.parse(it) }) }
        f.kinds?.let { kinds -> sdkF = sdkF.kinds(kinds.map { Kind(it.toULong()) }) }
        f.since?.let { sdkF = sdkF.since(Timestamp.fromSecs(it.toULong())) }
        f.until?.let { sdkF = sdkF.until(Timestamp.fromSecs(it.toULong())) }
        f.limit?.let { sdkF = sdkF.limit(it.toULong()) }
        f.search?.let { sdkF = sdkF.search(it) }
        f.tags?.forEach { (tagName, values) ->
            try {
                val alphabet = Alphabet.valueOf(tagName.uppercase())
                sdkF = sdkF.customTag(alphabet, values)
            } catch (e: Exception) {
                // Ignore unknown tags for now
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
                val events = client.fetchEvents(listOf(sdkFilter), Timestamp.fromSecs(timeoutMs.toULong() / 1000u))
                events.map { mapSdkEvent(it) }
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
                // Re-build SDK event from our model
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
        return """
            {
                "id": "${e.id}",
                "pubkey": "${e.pubkey}",
                "created_at": ${e.createdAt},
                "kind": ${e.kind},
                "tags": ${Json.encodeToString(e.tags)},
                "content": ${Json.encodeToString(e.content)},
                "sig": "${e.sig}"
            }
        """.trimIndent()
    }

    suspend fun publishNote(content: String, tags: List<List<String>> = emptyList()): NostrEvent? {
        val client = sdkClient ?: return null
        return withContext(Dispatchers.IO) {
            try {
                var builder = EventBuilder.textNote(content)
                tags.forEach { tagVector ->
                    builder = builder.tag(Tag.fromVector(tagVector))
                }
                val result = client.sendEventBuilder(builder)
                // Fetch the event from database to return it
                val event = client.database().eventById(result.id)
                event?.let { mapSdkEvent(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Publish note failed: ${e.message}")
                null
            }
        }
    }

    suspend fun publishReaction(eventId: String, emoji: String = "+"): Boolean {
        val client = sdkClient ?: return false
        return withContext(Dispatchers.IO) {
            try {
                val id = EventId.parse(eventId)
                val builder = EventBuilder.reaction(id, emoji)
                client.sendEventBuilder(builder)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    suspend fun publishRepost(eventId: String): Boolean {
        val client = sdkClient ?: return false
        return withContext(Dispatchers.IO) {
            try {
                val id = EventId.parse(eventId)
                val builder = EventBuilder.repost(id, null)
                client.sendEventBuilder(builder)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    // ─── DMs ─────────────────────────────────────────────────────────────────

    suspend fun sendEncryptedDm(recipientPubkeyHex: String, content: String): Boolean {
        val client = sdkClient ?: return false
        return withContext(Dispatchers.IO) {
            try {
                val receiver = PublicKey.parse(recipientPubkeyHex)
                client.sendPrivateMsg(receiver, content, emptyList())
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    fun decryptNip04(senderPubkeyHex: String, encryptedContent: String): String? {
        val client = sdkClient ?: return null
        return try {
            val sender = PublicKey.parse(senderPubkeyHex)
            client.nip04Decrypt(sender, encryptedContent)
        } catch (e: Exception) {
            null
        }
    }
}
