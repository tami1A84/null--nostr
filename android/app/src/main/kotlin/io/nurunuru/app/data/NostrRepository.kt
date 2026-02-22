package io.nurunuru.app.data

import io.nurunuru.NuruNuruClient
import io.nurunuru.app.data.models.*
import io.nurunuru.app.data.prefs.AppPreferences
import io.nurunuru.connectAsync
import io.nurunuru.fetchFollowListAsync
import io.nurunuru.fetchProfileAsync
import io.nurunuru.getRecommendedFeedAsync
import io.nurunuru.loginAsync
import io.nurunuru.publishNoteAsync
import io.nurunuru.searchAsync
import io.nurunuru.zapAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rust.nostr.sdk.*

/**
 * High-level Nostr operations using the Rust engine and official SDK.
 */
class NostrRepository(
    private val client: NuruNuruClient,
    private val prefs: AppPreferences
) {
    private val profileCache = mutableMapOf<String, UserProfile>()

    // ─── Timeline ─────────────────────────────────────────────

    /** Fetch global recommended timeline using the Rust recommendation engine. */
    suspend fun fetchRecommendedTimeline(limit: Int = 50): List<ScoredPost> = withContext(Dispatchers.IO) {
        val scoredPosts = client.getRecommendedFeedAsync(limit.toUInt())

        // Enrich with profiles and full event details
        scoredPosts.map { sp ->
            val profile = fetchProfile(sp.pubkey)
            // Note: In a real app, we'd fetch full event content from nostrdb via FFI or SDK
            ScoredPost(
                event = NostrEvent(id = sp.eventId, pubkey = sp.pubkey, createdAt = sp.createdAt.toLong()),
                score = sp.score,
                profile = profile
            )
        }
    }

    /** Fetch timeline for followed users. */
    suspend fun fetchFollowTimeline(pubkeyHex: String, limit: Int = 50): List<ScoredPost> = withContext(Dispatchers.IO) {
        // For now, we can use the same recommendation engine but filter or just use standard fetch
        // The Rust engine's get_recommended_feed already prioritizes follows.
        fetchRecommendedTimeline(limit)
    }

    /** Search for notes by text (NIP-50). */
    suspend fun searchNotes(query: String, limit: Int = 30): List<ScoredPost> = withContext(Dispatchers.IO) {
        val eventIds = client.searchAsync(query, limit.toUInt())
        eventIds.map { id ->
            // Minimal event info, would be better to fetch from cache
            ScoredPost(event = NostrEvent(id = id))
        }
    }

    // ─── Profiles ─────────────────────────────────────────────

    suspend fun fetchProfile(pubkeyHex: String): UserProfile? = withContext(Dispatchers.IO) {
        profileCache[pubkeyHex]?.let { return@withContext it }

        val ffiProfile = client.fetchProfileAsync(pubkeyHex) ?: return@withContext null
        val profile = UserProfile(
            pubkey = ffiProfile.pubkey,
            name = ffiProfile.name,
            displayName = ffiProfile.displayName,
            about = ffiProfile.about,
            picture = ffiProfile.picture,
            nip05 = ffiProfile.nip05,
            lud16 = ffiProfile.lud16
        )
        profileCache[pubkeyHex] = profile
        profile
    }

    suspend fun fetchProfiles(pubkeys: List<String>): Map<String, UserProfile> = withContext(Dispatchers.IO) {
        pubkeys.distinct().associateWith { fetchProfile(it) ?: UserProfile(pubkey = it) }
    }

    /** Fetch recent notes for a user. */
    suspend fun fetchUserNotes(pubkeyHex: String, limit: Int = 30): List<ScoredPost> = withContext(Dispatchers.IO) {
        val scoredPosts = client.fetchUserNotesAsync(pubkeyHex, limit.toUInt())
        scoredPosts.map { sp ->
            ScoredPost(
                event = NostrEvent(id = sp.eventId, pubkey = sp.pubkey, createdAt = sp.createdAt.toLong()),
                profile = fetchProfile(sp.pubkey)
            )
        }
    }

    /** Fetch follow list (pubkeys). */
    suspend fun fetchFollowList(pubkeyHex: String): List<String> = withContext(Dispatchers.IO) {
        client.fetchFollowListAsync(pubkeyHex)
    }

    // ─── Actions ──────────────────────────────────────────────

    suspend fun publishNote(content: String): String = withContext(Dispatchers.IO) {
        client.publishNoteAsync(content)
    }

    suspend fun zapPost(eventId: String, authorPubkey: String, amountSats: Long, message: String? = null) = withContext(Dispatchers.IO) {
        client.zapAsync(eventId, authorPubkey, amountSats.toULong(), message)
    }

    suspend fun likePost(eventId: String): Boolean = withContext(Dispatchers.IO) {
        // Use rust-nostr SDK directly for simple reactions if not in FFI
        // (Assuming NuruNuruClient handles common cases, but showing SDK usage here)
        try {
            // This is just an example of how one might use the SDK directly
            // In practice, we'd use the signer associated with the client
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun repostPost(eventId: String): Boolean = withContext(Dispatchers.IO) {
        true // TODO: Implement via FFI or SDK
    }

    // ─── DMs (NIP-17) ──────────────────────────────────────────

    suspend fun fetchDmConversations(pubkeyHex: String): List<DmConversation> = withContext(Dispatchers.IO) {
        val convs = client.fetchDmConversationsAsync()
        val profiles = fetchProfiles(convs.map { it.partnerPubkey })
        convs.map { c ->
            DmConversation(
                partnerPubkey = c.partnerPubkey,
                partnerProfile = profiles[c.partnerPubkey],
                lastMessage = c.lastMessage,
                lastMessageTime = c.lastMessageAt.toLong()
            )
        }
    }

    suspend fun fetchDmMessages(myPubkeyHex: String, partnerPubkeyHex: String): List<DmMessage> = withContext(Dispatchers.IO) {
        val msgs = client.fetchDmMessagesAsync(partnerPubkeyHex, 100u)
        msgs.map { m ->
            DmMessage(
                event = NostrEvent(id = m.eventId, pubkey = m.senderPubkey, createdAt = m.createdAt.toLong()),
                content = m.content,
                isMine = m.senderPubkey == myPubkeyHex,
                timestamp = m.createdAt.toLong()
            )
        }
    }

    suspend fun sendDm(recipientPubkey: String, content: String) = withContext(Dispatchers.IO) {
        client.sendDmAsync(recipientPubkey, content)
    }
}
