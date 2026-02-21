package io.nurunuru.shared.client

import io.nurunuru.shared.models.NostrEvent
import io.nurunuru.shared.protocol.NostrFilter

/**
 * Platform-agnostic Nostr client interface.
 *
 * Implementations live in platform source sets:
 *   - androidMain: [OkHttpNostrClient] (OkHttp WebSocket)
 *   - iosMain: (Ktor or URLSession WebSocket)
 */
interface INostrClient {

    /** The public key hex this client is operating as. */
    val publicKeyHex: String

    /** Connect to all configured relays. */
    fun connect()

    /** Disconnect from all relays and free resources. */
    fun disconnect()

    // ─── Read ─────────────────────────────────────────────────────────────────

    /**
     * One-shot fetch: subscribe, collect events until EOSE or [timeoutMs], then close.
     */
    suspend fun fetchEvents(filter: NostrFilter, timeoutMs: Long = 5_000): List<NostrEvent>

    /**
     * Long-lived subscription. Caller must [unsubscribe] when done.
     */
    fun subscribe(
        subId: String,
        filter: NostrFilter,
        onEvent: (NostrEvent) -> Unit,
        onEose: () -> Unit = {}
    )

    fun unsubscribe(subId: String)

    // ─── Write ────────────────────────────────────────────────────────────────

    /** Broadcast a fully signed event to all connected relays. */
    suspend fun publishEvent(event: NostrEvent): Boolean

    /** Create and publish a kind-1 text note. Returns the signed event. */
    suspend fun publishNote(
        content: String,
        tags: List<List<String>> = emptyList()
    ): NostrEvent?

    /** Create and publish a kind-7 reaction (default "+"). */
    suspend fun publishReaction(eventId: String, emoji: String = "+"): Boolean

    /** Create and publish a kind-6 repost. */
    suspend fun publishRepost(eventId: String, relayUrl: String = ""): Boolean

    // ─── DMs ─────────────────────────────────────────────────────────────────

    /** Send a NIP-04 encrypted DM to [recipientPubkeyHex]. */
    suspend fun sendEncryptedDm(recipientPubkeyHex: String, content: String): Boolean

    /** Decrypt a NIP-04 message received from [senderPubkeyHex]. */
    fun decryptNip04(senderPubkeyHex: String, encryptedContent: String): String?
}
