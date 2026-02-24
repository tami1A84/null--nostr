package io.nurunuru.app.data

import rust.nostr.sdk.Keys
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.SecretKey

/**
 * Utility functions for Nostr key operations.
 * Uses official rust-nostr SDK.
 */
object NostrKeyUtils {

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
     * Derive public key hex from private key hex using rust-nostr.
     */
    fun derivePublicKey(privateKeyHex: String): String? {
        return try {
            val secretKey = SecretKey.fromHex(privateKeyHex)
            val keys = Keys(secretKey)
            keys.publicKey().toHex()
        } catch (e: Exception) {
            null
        }
    }

    /** Encode public key as npub bech32. */
    fun encodeNpub(pubkeyHex: String): String? {
        return try {
            val publicKey = PublicKey.fromHex(pubkeyHex)
            publicKey.toBech32()
        } catch (e: Exception) {
            null
        }
    }

    /** Encode private key as nsec bech32. */
    fun encodeNsec(privkeyHex: String): String? {
        return try {
            val secretKey = SecretKey.fromHex(privkeyHex)
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
