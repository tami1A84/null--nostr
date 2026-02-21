package io.nurunuru.shared.platform

/**
 * Platform-specific utility functions using Kotlin Multiplatform expect/actual.
 *
 * Each platform (Android, iOS) provides `actual` implementations in its
 * respective source set (androidMain, iosMain).
 */

/** SHA-256 hash of [data], returns 32-byte digest. */
expect fun sha256(data: ByteArray): ByteArray

/** Encode [data] as Base64 string (no line breaks, no padding). */
expect fun base64Encode(data: ByteArray): String

/** Decode Base64 [encoded] string to bytes. */
expect fun base64Decode(encoded: String): ByteArray

/** Current Unix timestamp in seconds. */
expect fun currentTimeSeconds(): Long

/** Generate [size] cryptographically secure random bytes. */
expect fun randomBytes(size: Int): ByteArray

/** Platform debug log. */
expect fun logDebug(tag: String, message: String)

/** Platform warning log. */
expect fun logWarning(tag: String, message: String)

/**
 * NIP-04 ECDH: compute 32-byte shared secret x-coordinate.
 * [privKey] = 32-byte private key, [pubKeyX] = 32-byte x-only public key.
 */
expect fun ecdhNip04(privKey: ByteArray, pubKeyX: ByteArray): ByteArray

/**
 * BIP-340 Schnorr signature.
 * [msg] = 32 bytes, [privKey] = 32 bytes, [aux] = 32 bytes or null.
 * Returns 64-byte signature (R.x || s).
 */
expect fun schnorrSign(msg: ByteArray, privKey: ByteArray, aux: ByteArray?): ByteArray

/**
 * Derive the x-only (32-byte) public key from a 32-byte private key.
 */
expect fun pubkeyCreate(privKey: ByteArray): ByteArray

/** AES-CBC encrypt [plaintext] with [key] and [iv]. */
expect fun aesEncryptCbc(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray

/** AES-CBC decrypt [ciphertext] with [key] and [iv]. */
expect fun aesDecryptCbc(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray

// ─── Hex utilities (pure Kotlin, no platform dependencies) ────────────────────

fun ByteArray.toHex(): String = buildString {
    forEach { b ->
        val i = b.toInt() and 0xFF
        if (i < 16) append('0')
        append(i.toString(16))
    }
}

fun String.hexToBytes(): ByteArray? {
    if (length % 2 != 0) return null
    return try {
        ByteArray(length / 2) { i ->
            substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    } catch (e: Exception) {
        null
    }
}
