package io.nurunuru.app.data

import android.util.Log
import io.nurunuru.*
import uniffi.nurunuru.*
import io.nurunuru.app.data.models.*
import io.nurunuru.app.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rust.nostr.sdk.*

private const val TAG = "NuruNuru-Repo"

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
        Log.d(TAG, "Fetching recommended timeline...")
        val scoredPosts = try {
            client.getRecommendedFeedAsync(limit.toUInt())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get recommended feed", e)
            emptyList()
        }

        // Pre-fetch profiles to avoid suspend calls in map
        fetchProfiles(scoredPosts.map { it.pubkey })

        // Enrich with profiles and full event details
        scoredPosts.map { sp: FfiScoredPost ->
            ScoredPost(
                event = NostrEvent(id = sp.eventId, pubkey = sp.pubkey, createdAt = sp.createdAt.toLong()),
                score = sp.score,
                profile = profileCache[sp.pubkey]
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
        Log.d(TAG, "Searching for: $query")
        val eventIds = try {
            client.searchAsync(query, limit.toUInt())
        } catch (e: Exception) {
            Log.e(TAG, "Search failed", e)
            emptyList()
        }
        eventIds.map { id ->
            // Minimal event info, would be better to fetch from cache
            ScoredPost(event = NostrEvent(id = id))
        }
    }

    // ─── Profiles ─────────────────────────────────────────────

    suspend fun fetchProfile(pubkeyHex: String): UserProfile? = withContext(Dispatchers.IO) {
        profileCache[pubkeyHex]?.let { return@withContext it }

        Log.d(TAG, "Fetching profile for $pubkeyHex")
        val ffiProfile = try {
            client.fetchProfileAsync(pubkeyHex)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch profile", e)
            null
        } ?: return@withContext null

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
        Log.d(TAG, "Fetching notes for $pubkeyHex")
        val scoredPosts = try {
            client.fetchUserNotesAsync(pubkeyHex, limit.toUInt())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch user notes", e)
            emptyList()
        }

        // Pre-fetch profiles
        fetchProfiles(scoredPosts.map { it.pubkey })

        scoredPosts.map { sp: FfiScoredPost ->
            ScoredPost(
                event = NostrEvent(id = sp.eventId, pubkey = sp.pubkey, createdAt = sp.createdAt.toLong()),
                profile = profileCache[sp.pubkey]
            )
        }
    }

    /** Fetch follow list (pubkeys). */
    suspend fun fetchFollowList(pubkeyHex: String): List<String> = withContext(Dispatchers.IO) {
        try {
            client.fetchFollowListAsync(pubkeyHex)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch follow list", e)
            emptyList()
        }
    }

    // ─── Actions ──────────────────────────────────────────────

    suspend fun publishNote(content: String): String = withContext(Dispatchers.IO) {
        try {
            client.publishNoteAsync(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish note", e)
            ""
        }
    }

    suspend fun zapPost(eventId: String, authorPubkey: String, amountSats: Long, message: String? = null) = withContext(Dispatchers.IO) {
        try {
            client.zapAsync(eventId, authorPubkey, amountSats.toULong(), message)
        } catch (e: Exception) {
            Log.e(TAG, "Zap failed", e)
        }
    }

    suspend fun likePost(eventId: String): Boolean = withContext(Dispatchers.IO) {
        // Use rust-nostr SDK directly for simple reactions if not in FFI
        // (Assuming NuruNuruClient handles common cases, but showing SDK usage here)
        try {
            // This is just an example of how one might use the SDK directly
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
        Log.d(TAG, "Fetching DM conversations")
        val convs = try {
            client.fetchDmConversationsAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch DM conversations", e)
            emptyList()
        }

        // Pre-fetch profiles
        val profilesMap = fetchProfiles(convs.map { it.partnerPubkey })

        convs.map { c: FfiDmConversation ->
            DmConversation(
                partnerPubkey = c.partnerPubkey,
                partnerProfile = profilesMap[c.partnerPubkey],
                lastMessage = c.lastMessage,
                lastMessageTime = c.lastMessageAt.toLong()
            )
        }
    }

    suspend fun fetchDmMessages(myPubkeyHex: String, partnerPubkeyHex: String): List<DmMessage> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Fetching messages for $partnerPubkeyHex")
        val msgs = try {
            client.fetchDmMessagesAsync(partnerPubkeyHex, 100u)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch DM messages", e)
            emptyList()
        }
        msgs.map { m: FfiDmMessage ->
            DmMessage(
                event = NostrEvent(id = m.eventId, pubkey = m.senderPubkey, createdAt = m.createdAt.toLong()),
                content = m.content,
                isMine = m.senderPubkey == myPubkeyHex,
                timestamp = m.createdAt.toLong()
            )
        }
    }

    suspend fun sendDm(recipientPubkey: String, content: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Sending DM to $recipientPubkey")
        try {
            client.sendDmAsync(recipientPubkey, content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send DM", e)
        }
    }
}
