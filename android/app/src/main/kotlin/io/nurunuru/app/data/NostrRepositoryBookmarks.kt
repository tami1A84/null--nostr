package io.nurunuru.app.data

import android.util.Log
import io.nurunuru.app.data.models.*

// ─── Bookmarks (NIP-51 kind 10003) ────────────────────────────────────────────

/**
 * Fetch the bookmark list for [pubkeyHex] and return enriched posts.
 * Returns empty list if no bookmarks exist.
 */
suspend fun NostrRepository.fetchBookmarkedPosts(pubkeyHex: String): List<ScoredPost> {
    val eventIds = fetchBookmarkEventIds(pubkeyHex)
    if (eventIds.isEmpty()) return emptyList()
    val filter = NostrClient.Filter(ids = eventIds)
    val events = client.fetchEvents(filter, timeoutMs = 6_000).distinctBy { it.id }
    return enrichPosts(events)
}

/** Returns the raw list of bookmarked event IDs from kind 10003. */
suspend fun NostrRepository.fetchBookmarkEventIds(pubkeyHex: String): List<String> {
    val filter = NostrClient.Filter(
        kinds = listOf(NostrKind.BOOKMARKS),
        authors = listOf(pubkeyHex),
        limit = 1
    )
    val event = client.fetchEvents(filter, timeoutMs = 4_000).maxByOrNull { it.createdAt }
        ?: return emptyList()
    return event.getTagValues("e")
}

/**
 * Add [eventId] to the bookmark list (kind 10003) and republish.
 */
suspend fun NostrRepository.addBookmark(pubkeyHex: String, eventId: String): Boolean {
    val existing = fetchBookmarkEventIds(pubkeyHex).toMutableList()
    if (existing.contains(eventId)) return true
    existing.add(eventId)
    val tags = existing.map { listOf("e", it) }
    return try {
        publishNewEvent(NostrKind.BOOKMARKS, "", tags) != null
    } catch (e: Exception) {
        Log.e("NostrRepository", "addBookmark failed: ${e.message}")
        false
    }
}

/**
 * Remove [eventId] from the bookmark list (kind 10003) and republish.
 */
suspend fun NostrRepository.removeBookmark(pubkeyHex: String, eventId: String): Boolean {
    val existing = fetchBookmarkEventIds(pubkeyHex).toMutableList()
    if (!existing.remove(eventId)) return true
    val tags = existing.map { listOf("e", it) }
    return try {
        publishNewEvent(NostrKind.BOOKMARKS, "", tags) != null
    } catch (e: Exception) {
        Log.e("NostrRepository", "removeBookmark failed: ${e.message}")
        false
    }
}
