package io.nurunuru.app.data

import fr.acinq.secp256k1.Secp256k1

/**
 * Utility functions for Nostr key operations.
 * Handles bech32 encoding/decoding and key derivation.
 */
object NostrKeyUtils {

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    // ─── Bech32 ─────────────────────────────────────────────────────────────

    private fun polymod(values: ByteArray): Int {
        var chk = 1
        for (p in values) {
            val top = chk ushr 25
            chk = (chk and 0x1ffffff) shl 5 xor (p.toInt() and 0xff)
            for (i in 0..4) {
                if (top ushr i and 1 != 0) chk = chk xor GENERATOR[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): ByteArray {
        val result = ByteArray(hrp.length * 2 + 1)
        for (i in hrp.indices) {
            result[i] = (hrp[i].code ushr 5).toByte()
            result[i + hrp.length + 1] = (hrp[i].code and 31).toByte()
        }
        result[hrp.length] = 0
        return result
    }

    private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray? {
        var acc = 0
        var bits = 0
        val result = mutableListOf<Byte>()
        val maxv = (1 shl toBits) - 1
        for (value in data) {
            val v = value.toInt() and 0xff
            if (v ushr fromBits != 0) return null
            acc = (acc shl fromBits) or v
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result.add((acc ushr bits and maxv).toByte())
            }
        }
        if (pad) {
            if (bits > 0) result.add((acc shl (toBits - bits) and maxv).toByte())
        } else if (bits >= fromBits || (acc shl (toBits - bits) and maxv) != 0) {
            return null
        }
        return result.toByteArray()
    }

    /** Decode a bech32 string, returns Pair(hrp, data) or null on failure. */
    fun bech32Decode(bechStr: String): Pair<String, ByteArray>? {
        val lower = bechStr.lowercase()
        val pos = lower.lastIndexOf('1')
        if (pos < 1 || pos + 7 > lower.length) return null
        val hrp = lower.substring(0, pos)
        val data = ByteArray(lower.length - pos - 1)
        for (i in data.indices) {
            val c = CHARSET.indexOf(lower[pos + 1 + i])
            if (c == -1) return null
            data[i] = c.toByte()
        }
        val combined = hrpExpand(hrp) + data
        if (polymod(combined) != 1) return null
        val decoded = convertBits(data.copyOf(data.size - 6), 5, 8, false) ?: return null
        return Pair(hrp, decoded)
    }

    /** Encode bytes to bech32. */
    fun bech32Encode(hrp: String, data: ByteArray): String {
        val conv = convertBits(data, 8, 5, true) ?: error("bech32Encode failed")
        val combined = hrpExpand(hrp) + conv + ByteArray(6)
        val polymod = polymod(combined) xor 1
        val checksum = ByteArray(6) { i -> ((polymod ushr (5 * (5 - i))) and 31).toByte() }
        return hrp + '1' + (conv + checksum).map { CHARSET[it.toInt()] }.joinToString("")
    }

    // ─── Key Operations ──────────────────────────────────────────────────────

    /**
     * Parse a private key from nsec bech32 or hex string.
     * Returns hex private key or null on error.
     */
    fun parsePrivateKey(input: String): String? {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith("nsec1") -> {
                val decoded = bech32Decode(trimmed) ?: return null
                if (decoded.first != "nsec") return null
                decoded.second.toHex()
            }
            trimmed.matches(Regex("[0-9a-fA-F]{64}")) -> trimmed.lowercase()
            else -> null
        }
    }

    /**
     * Derive public key hex from private key hex using secp256k1.
     */
    fun derivePublicKey(privateKeyHex: String): String? {
        return try {
            val privKeyBytes = privateKeyHex.hexToBytes() ?: return null
            val pubKeyBytes = Secp256k1.pubkeyCreate(privKeyBytes)
            // Extract x-only (32 bytes) from the 33-byte compressed key
            pubKeyBytes.drop(1).toByteArray().toHex()
        } catch (e: Exception) {
            null
        }
    }

    /** Encode public key as npub bech32. */
    fun encodeNpub(pubkeyHex: String): String? {
        val bytes = pubkeyHex.hexToBytes() ?: return null
        return bech32Encode("npub", bytes)
    }

    /** Encode private key as nsec bech32. */
    fun encodeNsec(privkeyHex: String): String? {
        val bytes = privkeyHex.hexToBytes() ?: return null
        return bech32Encode("nsec", bytes)
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
