package io.nurunuru.app.ui

import io.nurunuru.app.ui.components.extractPostImages
import io.nurunuru.app.ui.components.formatPostTimestamp
import io.nurunuru.app.ui.components.removeImageUrls
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for PostContent utility functions.
 */
class PostContentUtilsTest {

    // ─── extractPostImages ───────────────────────────────────────────────────

    @Test
    fun `extractPostImages finds jpg urls`() {
        val content = "Check this out https://example.com/photo.jpg nice!"
        val images = extractPostImages(content)
        assertEquals(listOf("https://example.com/photo.jpg"), images)
    }

    @Test
    fun `extractPostImages finds multiple image formats`() {
        val content = """
            https://img.com/a.png
            https://img.com/b.webp
            https://img.com/c.gif
        """.trimIndent()
        val images = extractPostImages(content)
        assertEquals(3, images.size)
        assertTrue(images.any { it.endsWith(".png") })
        assertTrue(images.any { it.endsWith(".webp") })
        assertTrue(images.any { it.endsWith(".gif") })
    }

    @Test
    fun `extractPostImages handles query params`() {
        val content = "https://nostr.build/img/abc.jpg?fit=300"
        val images = extractPostImages(content)
        assertEquals(1, images.size)
        assertTrue(images[0].contains("abc.jpg"))
    }

    @Test
    fun `extractPostImages returns empty for no images`() {
        val content = "Just text with https://example.com/page link"
        val images = extractPostImages(content)
        assertTrue(images.isEmpty())
    }

    @Test
    fun `extractPostImages deduplicates urls`() {
        val content = "https://img.com/a.jpg and https://img.com/a.jpg again"
        val images = extractPostImages(content)
        assertEquals(1, images.size)
    }

    // ─── removeImageUrls ─────────────────────────────────────────────────────

    @Test
    fun `removeImageUrls strips image urls from content`() {
        val content = "Hello https://img.com/a.jpg world"
        val cleaned = removeImageUrls(content)
        assertEquals("Hello  world", cleaned)
    }

    @Test
    fun `removeImageUrls preserves non-image urls`() {
        val content = "Visit https://example.com/page for more"
        val cleaned = removeImageUrls(content)
        assertEquals("Visit https://example.com/page for more", cleaned)
    }

    @Test
    fun `removeImageUrls handles empty content`() {
        assertEquals("", removeImageUrls(""))
    }

    // ─── formatPostTimestamp ─────────────────────────────────────────────────

    @Test
    fun `formatPostTimestamp shows seconds for recent posts`() {
        val now = System.currentTimeMillis() / 1000
        val result = formatPostTimestamp(now - 30)
        assertTrue(result.contains("秒"))
    }

    @Test
    fun `formatPostTimestamp shows minutes`() {
        val now = System.currentTimeMillis() / 1000
        val result = formatPostTimestamp(now - 300) // 5 minutes ago
        assertTrue(result.contains("分"))
    }

    @Test
    fun `formatPostTimestamp shows hours`() {
        val now = System.currentTimeMillis() / 1000
        val result = formatPostTimestamp(now - 7200) // 2 hours ago
        assertTrue(result.contains("時間"))
    }

    @Test
    fun `formatPostTimestamp shows days`() {
        val now = System.currentTimeMillis() / 1000
        val result = formatPostTimestamp(now - 86400 * 3) // 3 days ago
        assertTrue(result.contains("日"))
    }

    @Test
    fun `formatPostTimestamp shows date for old posts`() {
        val now = System.currentTimeMillis() / 1000
        val result = formatPostTimestamp(now - 86400 * 30) // 30 days ago
        // Should be M/d format
        assertTrue(result.contains("/"))
    }
}
