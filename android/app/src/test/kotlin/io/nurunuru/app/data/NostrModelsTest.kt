package io.nurunuru.app.data

import io.nurunuru.app.data.models.NostrEvent
import io.nurunuru.app.data.models.UserProfile
import io.nurunuru.app.data.models.NotificationItem
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for data model classes in NostrModels.kt.
 */
class NostrModelsTest {

    // ─── NostrEvent ──────────────────────────────────────────────────────────

    @Test
    fun `getTagValue returns first matching tag value`() {
        val event = NostrEvent(
            tags = listOf(
                listOf("e", "event123"),
                listOf("p", "pubkey456"),
                listOf("e", "event789")
            )
        )
        assertEquals("event123", event.getTagValue("e"))
        assertEquals("pubkey456", event.getTagValue("p"))
    }

    @Test
    fun `getTagValue returns null for missing tag`() {
        val event = NostrEvent(tags = listOf(listOf("e", "event123")))
        assertNull(event.getTagValue("p"))
        assertNull(event.getTagValue("t"))
    }

    @Test
    fun `getTagValues returns all matching tag values`() {
        val event = NostrEvent(
            tags = listOf(
                listOf("e", "event1"),
                listOf("p", "pubkey1"),
                listOf("e", "event2"),
                listOf("e", "event3")
            )
        )
        assertEquals(listOf("event1", "event2", "event3"), event.getTagValues("e"))
        assertEquals(listOf("pubkey1"), event.getTagValues("p"))
        assertEquals(emptyList<String>(), event.getTagValues("t"))
    }

    @Test
    fun `isProtected detects protected events`() {
        val protectedEvent = NostrEvent(tags = listOf(listOf("-")))
        assertTrue(protectedEvent.isProtected())

        val normalEvent = NostrEvent(tags = listOf(listOf("e", "id")))
        assertFalse(normalEvent.isProtected())

        val emptyEvent = NostrEvent()
        assertFalse(emptyEvent.isProtected())
    }

    @Test
    fun `getTagValue handles single-element tags gracefully`() {
        val event = NostrEvent(tags = listOf(listOf("e")))
        assertNull(event.getTagValue("e"))
    }

    // ─── UserProfile ─────────────────────────────────────────────────────────

    @Test
    fun `displayedName returns displayName when available`() {
        val profile = UserProfile(
            pubkey = "abc123",
            name = "alice",
            displayName = "Alice"
        )
        assertEquals("Alice", profile.displayedName)
    }

    @Test
    fun `displayedName falls back to name`() {
        val profile = UserProfile(
            pubkey = "abc123",
            name = "alice",
            displayName = null
        )
        assertEquals("alice", profile.displayedName)
    }

    @Test
    fun `displayedName falls back to name when displayName is blank`() {
        val profile = UserProfile(
            pubkey = "abc123",
            name = "alice",
            displayName = "  "
        )
        assertEquals("alice", profile.displayedName)
    }

    @Test
    fun `displayedName falls back to truncated pubkey`() {
        val profile = UserProfile(
            pubkey = "abcdef1234567890",
            name = null,
            displayName = null
        )
        assertEquals("abcdef123456...", profile.displayedName)
    }

    @Test
    fun `displayedName falls back to truncated pubkey when name is blank`() {
        val profile = UserProfile(
            pubkey = "abcdef1234567890",
            name = "",
            displayName = ""
        )
        assertEquals("abcdef123456...", profile.displayedName)
    }

    // ─── NotificationItem ────────────────────────────────────────────────────

    @Test
    fun `NotificationItem defaults`() {
        val item = NotificationItem(
            id = "id1",
            pubkey = "pk1",
            type = "reaction",
            createdAt = 1000L
        )
        assertNull(item.amount)
        assertNull(item.comment)
        assertNull(item.targetEventId)
        assertNull(item.emojiUrl)
    }

    @Test
    fun `NotificationItem zap with amount`() {
        val item = NotificationItem(
            id = "id2",
            pubkey = "pk2",
            type = "zap",
            createdAt = 2000L,
            amount = 21000L,
            comment = "Nice!"
        )
        assertEquals("zap", item.type)
        assertEquals(21000L, item.amount)
        assertEquals("Nice!", item.comment)
    }
}
