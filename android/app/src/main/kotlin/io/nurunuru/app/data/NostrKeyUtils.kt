package io.nurunuru.app.data

import rust.nostr.sdk.*

data class NostrLink(val type: String, val id: String)

/**
 * Utility functions for Nostr key operations.
 * Uses official rust-nostr SDK.
 */
object NostrKeyUtils {

    /**
     * Generate new Nostr keys.
     */
    fun generateKeys(): Keys {
        return Keys.generate()
    }

    /**
     * Parse a private key from nsec bech32 or hex string.
     * Returns hex private key or null on error.
     */
    fun parsePrivateKey(input: String): String? {
        return try {
            val keys = Keys.parse(input)
            keys.secretKey().toHex()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse a public key from npub bech32 or hex string.
     * Returns hex public key or null on error.
     */
    fun parsePublicKey(input: String): String? {
        return try {
            PublicKey.parse(input).toHex()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Derive public key hex from private key hex using rust-nostr.
     */
    fun derivePublicKey(privateKeyHex: String): String? {
        return try {
            val keys = Keys.parse(privateKeyHex)
            keys.publicKey().toHex()
        } catch (e: Exception) {
            null
        }
    }

    /** Encode public key as npub bech32. */
    fun encodeNpub(pubkeyHex: String): String? {
        return try {
            val publicKey = PublicKey.parse(pubkeyHex)
            publicKey.toBech32()
        } catch (e: Exception) {
            null
        }
    }

    /** Encode private key as nsec bech32. */
    fun encodeNsec(privkeyHex: String): String? {
        return try {
            val secretKey = SecretKey.parse(privkeyHex)
            secretKey.toBech32()
        } catch (e: Exception) {
            null
        }
    }

    /** Shorten a public key for display (first 6 chars of npub). */
    fun shortenPubkey(pubkeyHex: String, chars: Int = 8): String {
        val npub = encodeNpub(pubkeyHex) ?: return pubkeyHex.take(chars)
        return npub.take(chars + 5) // npub1 prefix + chars
    }

    /** Parse nostr link (note1, npub1, etc.) */
    fun parseNostrLink(input: String): NostrLink? {
        return try {
            when {
                input.startsWith("npub1") -> NostrLink("npub", PublicKey.parse(input).toHex())
                input.startsWith("note1") -> NostrLink("note", EventId.parse(input).toHex())
                // Basic support for now, complex NIP-19 parsing skipped to ensure build stability
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ─── Hex Helpers ─────────────────────────────────────────────────────────

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun String.hexToBytes(): ByteArray? {
        if (length % 2 != 0) return null
        return try {
            ByteArray(length / 2) { i ->
                substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
