package io.nurunuru.app.data

import java.net.URI

/**
 * Input validation and sanitization utilities.
 * Synced with web version: lib/validation.js
 */
object Validation {

    private val SAFE_PROTOCOLS = setOf("http", "https", "mailto", "nostr", "lightning")
    private val DANGEROUS_PROTOCOLS = setOf("javascript", "data", "vbscript")

    // ─── HTML Sanitization ───────────────────────────────────────────────────

    private val HTML_ENTITIES = mapOf(
        '&' to "&amp;", '<' to "&lt;", '>' to "&gt;",
        '"' to "&quot;", '\'' to "&#x27;", '/' to "&#x2F;",
        '`' to "&#x60;", '=' to "&#x3D;"
    )

    fun escapeHtml(str: String): String =
        str.map { HTML_ENTITIES[it] ?: it.toString() }.joinToString("")

    fun sanitizeText(text: String): String =
        text.replace("\u0000", "")
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")
            .replace("\r\n", "\n")
            .replace("\r", "\n")

    fun stripHtmlTags(html: String): String =
        html.replace(Regex("<[^>]*>"), "")

    // ─── URL Validation ──────────────────────────────────────────────────────

    fun validateUrl(
        url: String,
        allowNostr: Boolean = true,
        allowLightning: Boolean = true,
        requireHttps: Boolean = false
    ): String? {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return null

        // Block dangerous protocols
        if (DANGEROUS_PROTOCOLS.any { trimmed.startsWith("$it:", ignoreCase = true) }) return null

        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase() ?: return null

            val allowed = buildSet {
                add("https")
                add("mailto")
                if (!requireHttps) add("http")
                if (allowNostr) add("nostr")
                if (allowLightning) add("lightning")
            }

            if (scheme !in allowed) null else trimmed
        } catch (_: Exception) {
            // Try adding https:// if no protocol
            if (!trimmed.contains("://")) {
                validateUrl("https://$trimmed", allowNostr, allowLightning, requireHttps)
            } else null
        }
    }

    fun isValidRelayUrl(url: String): Boolean {
        return try {
            val uri = URI(url)
            uri.scheme == "wss" && !uri.host.endsWith(".onion")
        } catch (_: Exception) {
            false
        }
    }

    fun isValidImageUrl(url: String): Boolean {
        val validated = validateUrl(url, requireHttps = true) ?: return false
        val imageExtensions = Regex("\\.(jpg|jpeg|png|gif|webp|svg|bmp|ico)(\\?.*)?$", RegexOption.IGNORE_CASE)
        val imageHosts = listOf("nostr.build", "void.cat", "imgur.com", "i.imgur.com", "image.nostr.build")
        return try {
            val uri = URI(validated)
            imageExtensions.containsMatchIn(uri.path ?: "") ||
                imageHosts.any { uri.host?.contains(it) == true }
        } catch (_: Exception) { false }
    }

    // ─── Nostr-specific Validation ───────────────────────────────────────────

    fun isValidHex(hex: String, expectedLength: Int? = null): Boolean {
        if (hex.isBlank()) return false
        if (expectedLength != null && hex.length != expectedLength) return false
        return hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    fun isValidPubkey(pubkey: String): Boolean = isValidHex(pubkey, 64)

    fun isValidEventId(eventId: String): Boolean = isValidHex(eventId, 64)

    fun isValidNip05(nip05: String): Boolean {
        if (nip05.isBlank()) return false
        if (!nip05.contains("@")) return isValidDomain(nip05)
        val parts = nip05.split("@")
        if (parts.size != 2) return false
        val (name, domain) = parts
        if (name.isBlank() || domain.isBlank()) return false
        if (!Regex("^[a-zA-Z0-9._-]+$").matches(name)) return false
        return isValidDomain(domain)
    }

    fun isValidDomain(domain: String): Boolean {
        if (domain.isBlank() || domain.length > 253) return false
        val regex = Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$")
        return regex.matches(domain)
    }

    fun isValidBech32(str: String, prefix: String? = null): Boolean {
        if (str.isBlank()) return false
        val pattern = Regex("^[a-z]+1[023456789acdefghjklmnpqrstuvwxyz]+$")
        if (!pattern.matches(str)) return false
        return prefix == null || str.startsWith("${prefix}1")
    }

    // ─── Event Content Validation ────────────────────────────────────────────

    private val MAX_CONTENT_LENGTH = mapOf(
        0 to 10000, 1 to 100000, 4 to 50000, 7 to 100, 14 to 50000
    )

    data class ValidationResult(
        val valid: Boolean,
        val errors: List<String>,
        val sanitized: String
    )

    fun validateEventContent(content: String, kind: Int): ValidationResult {
        val maxLength = MAX_CONTENT_LENGTH[kind] ?: 100000
        if (content.length > maxLength) {
            return ValidationResult(false, listOf("コンテンツが最大長 $maxLength 文字を超えています"), content)
        }
        return ValidationResult(true, emptyList(), sanitizeText(content))
    }

    // ─── Lightning/Zap Validation ────────────────────────────────────────────

    fun isValidLightningAddress(address: String): Boolean {
        if (address.isBlank()) return false
        val parts = address.split("@")
        if (parts.size != 2) return false
        val (name, domain) = parts
        if (name.isBlank() || domain.isBlank()) return false
        if (!Regex("^[a-zA-Z0-9._-]+$").matches(name)) return false
        return isValidDomain(domain)
    }

    fun isValidZapAmount(amount: Long, min: Long = 1, max: Long = 2_100_000_000_000_000): Boolean =
        amount in min..max

    // ─── Search Query Sanitization ───────────────────────────────────────────

    fun sanitizeSearchQuery(query: String, maxLength: Int = 500): String =
        sanitizeText(query).trim().take(maxLength).replace(Regex("\\s+"), " ")
}
