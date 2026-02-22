package io.nurunuru

import android.content.Context
import uniffi.nurunuru.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Convenience factory and async extension wrappers for the UniFFI-generated
 * [NuruNuruClient].
 *
 * ## Basic usage
 * ```kotlin
 * // Activity / ViewModel:
 * val client = NuruNuruBridge.create(requireContext(), nsecKey)
 *
 * lifecycleScope.launch {
 *     client.connectAsync()
 *     client.loginAsync(npubHex)
 *     val feed = client.getRecommendedFeedAsync(50u)
 *     // feed: List<FfiScoredPost>
 * }
 * ```
 *
 * The underlying [NuruNuruClient] methods are synchronous (they block the
 * calling thread via a Tokio runtime inside Rust). Always call them from a
 * coroutine using [Dispatchers.IO] or use the async extension functions
 * provided here.
 */
object NuruNuruBridge {
    /**
     * Create a [NuruNuruClient] backed by the app's private storage.
     *
     * @param context       Application or Activity context (used to resolve the DB path).
     * @param secretKeyHex  User's private key — hex or nsec Bech32.
     */
    fun create(context: Context, secretKeyHex: String): NuruNuruClient {
        val dbDir = File(context.filesDir, "nurunuru-db").also { it.mkdirs() }
        return NuruNuruClient(secretKeyHex = secretKeyHex, dbPath = dbDir.absolutePath)
    }
}

// ─── Coroutine-friendly extension functions ────────────────────────────────

/** Connect to relays on the IO dispatcher. */
suspend fun NuruNuruClient.connectAsync() = withContext(Dispatchers.IO) { connect() }

/** Disconnect from all relays on the IO dispatcher. */
suspend fun NuruNuruClient.disconnectAsync() = withContext(Dispatchers.IO) { disconnect() }

/** Load the user's follow/mute lists from relays. */
suspend fun NuruNuruClient.loginAsync(pubkeyHex: String) =
    withContext(Dispatchers.IO) { login(pubkeyHex = pubkeyHex) }

/** Fetch a user profile (kind 0 metadata). Returns null if not found. */
suspend fun NuruNuruClient.fetchProfileAsync(pubkeyHex: String): FfiUserProfile? =
    withContext(Dispatchers.IO) { fetchProfile(pubkeyHex = pubkeyHex) }

/** Get the ranked recommendation feed. */
suspend fun NuruNuruClient.getRecommendedFeedAsync(limit: UInt): List<FfiScoredPost> =
    withContext(Dispatchers.IO) { getRecommendedFeed(limit = limit) }

/** Fetch recent notes from a specific user. */
suspend fun NuruNuruClient.fetchUserNotesAsync(pubkeyHex: String, limit: UInt): List<FfiScoredPost> =
    withContext(Dispatchers.IO) { fetchUserNotes(pubkeyHex = pubkeyHex, limit = limit) }

/** Fetch all DM conversations. */
suspend fun NuruNuruClient.fetchDmConversationsAsync(): List<FfiDmConversation> =
    withContext(Dispatchers.IO) { fetchDmConversations() }

/** Fetch messages in a conversation. */
suspend fun NuruNuruClient.fetchDmMessagesAsync(partnerPubkeyHex: String, limit: UInt): List<FfiDmMessage> =
    withContext(Dispatchers.IO) { fetchDmMessages(partnerPubkeyHex = partnerPubkeyHex, limit = limit) }

/** Publish a text note (kind 1). Returns the event ID hex. */
suspend fun NuruNuruClient.publishNoteAsync(content: String): String =
    withContext(Dispatchers.IO) { publishNote(content = content) }

/** Send an encrypted DM (NIP-17). */
suspend fun NuruNuruClient.sendDmAsync(recipientHex: String, content: String) =
    withContext(Dispatchers.IO) { sendDm(recipientHex = recipientHex, content = content) }

/** Fetch the follow list for a user. Returns pubkey hex strings. */
suspend fun NuruNuruClient.fetchFollowListAsync(pubkeyHex: String): List<String> =
    withContext(Dispatchers.IO) { fetchFollowList(pubkeyHex = pubkeyHex) }

/** Follow a user (publishes updated kind 3 contact list). */
suspend fun NuruNuruClient.followUserAsync(targetPubkeyHex: String) =
    withContext(Dispatchers.IO) { followUser(targetPubkeyHex = targetPubkeyHex) }

/** Unfollow a user (publishes updated kind 3 contact list). */
suspend fun NuruNuruClient.unfollowUserAsync(targetPubkeyHex: String) =
    withContext(Dispatchers.IO) { unfollowUser(targetPubkeyHex = targetPubkeyHex) }

/** Full-text search (NIP-50). Returns matching event ID hex strings. */
suspend fun NuruNuruClient.searchAsync(query: String, limit: UInt): List<String> =
    withContext(Dispatchers.IO) { search(query = query, limit = limit) }

/** Zap a user's post (NIP-57). */
suspend fun NuruNuruClient.zapAsync(eventIdHex: String, authorPubkeyHex: String, amountSats: ULong, message: String? = null) =
    withContext(Dispatchers.IO) { zap(eventIdHex = eventIdHex, authorPubkeyHex = authorPubkeyHex, amountSats = amountSats, message = message) }

/** Fetch earned badges for a user (NIP-58). */
suspend fun NuruNuruClient.fetchBadgesAsync(pubkeyHex: String): List<FfiBadgeAward> =
    withContext(Dispatchers.IO) { fetchBadges(pubkeyHex = pubkeyHex) }

/** Create a Birdwatch label for an event (NIP-32). */
suspend fun NuruNuruClient.birdwatchPostAsync(eventIdHex: String, authorPubkeyHex: String, contextType: String, content: String): String =
    withContext(Dispatchers.IO) { birdwatchPost(eventIdHex = eventIdHex, authorPubkeyHex = authorPubkeyHex, contextType = contextType, content = content) }

/** Verify NIP-05 identifier. */
suspend fun NuruNuruClient.verifyNip05Async(nip05: String, pubkeyHex: String): Boolean =
    withContext(Dispatchers.IO) { verifyNip05(nip05 = nip05, pubkeyHex = pubkeyHex) }

/** Request to vanish (NIP-62). IRREVERSIBLE. */
suspend fun NuruNuruClient.requestVanishAsync(reason: String? = null): String =
    withContext(Dispatchers.IO) { requestVanish(reason = reason) }

/** Delete an event (NIP-09). */
suspend fun NuruNuruClient.deleteEventAsync(eventIdHex: String, reason: String? = null): String =
    withContext(Dispatchers.IO) { deleteEvent(eventIdHex = eventIdHex, reason = reason) }
