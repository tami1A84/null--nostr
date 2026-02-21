package io.nurunuru.shared.platform

import android.util.Base64
import android.util.Log
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// ─── Core platform actuals ────────────────────────────────────────────────────

actual fun sha256(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(data)

actual fun base64Encode(data: ByteArray): String =
    Base64.encodeToString(data, Base64.NO_WRAP)

actual fun base64Decode(encoded: String): ByteArray =
    Base64.decode(encoded, Base64.DEFAULT)

actual fun currentTimeSeconds(): Long = System.currentTimeMillis() / 1000

actual fun randomBytes(size: Int): ByteArray =
    ByteArray(size).also { SecureRandom().nextBytes(it) }

actual fun logDebug(tag: String, message: String) { Log.d(tag, message) }

actual fun logWarning(tag: String, message: String) { Log.w(tag, message) }

// ─── AES-CBC ─────────────────────────────────────────────────────────────────

actual fun aesEncryptCbc(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    return cipher.doFinal(plaintext)
}

actual fun aesDecryptCbc(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    return cipher.doFinal(ciphertext)
}

// ─── secp256k1 ────────────────────────────────────────────────────────────────

actual fun ecdhNip04(privKey: ByteArray, pubKeyX: ByteArray): ByteArray =
    Secp256k1Impl.ecdhNip04(privKey, pubKeyX)

actual fun schnorrSign(msg: ByteArray, privKey: ByteArray, aux: ByteArray?): ByteArray =
    Secp256k1Impl.schnorrSign(msg, privKey, aux)

actual fun pubkeyCreate(privKey: ByteArray): ByteArray =
    Secp256k1Impl.pubkeyCreate(privKey)

// ─── Pure-Kotlin secp256k1 ────────────────────────────────────────────────────
//
// BIP-340 Schnorr signing + NIP-04 ECDH.
// No native library required; uses Java's BigInteger for field arithmetic.
//
internal object Secp256k1Impl {

    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val Gx = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val Gy = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val G = ECPoint(Gx, Gy)

    private data class ECPoint(val x: BigInteger, val y: BigInteger)

    // ── EC arithmetic ─────────────────────────────────────────────────────────

    private fun pointAdd(p: ECPoint?, q: ECPoint?): ECPoint? {
        if (p == null) return q
        if (q == null) return p
        return if (p.x == q.x) {
            if (p.y != q.y || p.y == BigInteger.ZERO) null
            else {
                val two = BigInteger.valueOf(2)
                val lam = BigInteger.valueOf(3).multiply(p.x.pow(2))
                    .multiply(two.multiply(p.y).modInverse(P)).mod(P)
                val x3 = lam.pow(2).subtract(two.multiply(p.x)).mod(P)
                val y3 = lam.multiply(p.x.subtract(x3)).subtract(p.y).mod(P)
                ECPoint(x3, y3)
            }
        } else {
            val lam = q.y.subtract(p.y).multiply(q.x.subtract(p.x).modInverse(P)).mod(P)
            val x3 = lam.pow(2).subtract(p.x).subtract(q.x).mod(P)
            val y3 = lam.multiply(p.x.subtract(x3)).subtract(p.y).mod(P)
            ECPoint(x3, y3)
        }
    }

    private fun scalarMul(k: BigInteger, point: ECPoint): ECPoint? {
        var result: ECPoint? = null
        var addend: ECPoint? = point
        var scalar = k.mod(N)
        while (scalar > BigInteger.ZERO) {
            if (scalar.testBit(0)) result = pointAdd(result, addend)
            addend = pointAdd(addend, addend)
            scalar = scalar.shiftRight(1)
        }
        return result
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun BigInteger.to32Bytes(): ByteArray {
        val raw = toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size == 33 && raw[0] == 0.toByte() -> raw.copyOfRange(1, 33)
            raw.size < 32 -> ByteArray(32 - raw.size) + raw
            else -> throw IllegalArgumentException("Value too large for 32 bytes")
        }
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val out = ByteArray(size + other.size)
        copyInto(out); other.copyInto(out, size)
        return out
    }

    private fun ByteArray.xorWith(other: ByteArray): ByteArray =
        ByteArray(size) { i -> (this[i].toInt() xor other[i].toInt()).toByte() }

    /** BIP-340 tagged hash: SHA256(SHA256(tag) || SHA256(tag) || data). */
    private fun taggedHash(tag: String, data: ByteArray): ByteArray {
        val tagHash = sha256(tag.toByteArray(Charsets.UTF_8))
        return sha256(tagHash + tagHash + data)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun pubkeyCreate(privKey: ByteArray): ByteArray {
        val d = BigInteger(1, privKey)
        require(d > BigInteger.ZERO && d < N) { "Invalid private key" }
        val point = scalarMul(d, G) ?: throw IllegalArgumentException("Invalid private key")
        return point.x.to32Bytes()
    }

    fun ecdhNip04(privKey: ByteArray, pubKeyX: ByteArray): ByteArray {
        val d = BigInteger(1, privKey)
        val x = BigInteger(1, pubKeyX)
        val y2 = x.modPow(BigInteger.valueOf(3), P).add(BigInteger.valueOf(7)).mod(P)
        val y = y2.modPow(P.add(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)
        val yEven = if (y.testBit(0)) P.subtract(y) else y
        val shared = scalarMul(d, ECPoint(x, yEven))
            ?: throw IllegalArgumentException("ECDH produced point at infinity")
        return shared.x.to32Bytes()
    }

    fun schnorrSign(msg: ByteArray, privKey: ByteArray, aux: ByteArray?): ByteArray {
        require(msg.size == 32) { "Message must be 32 bytes" }
        var d0 = BigInteger(1, privKey)
        require(d0 > BigInteger.ZERO && d0 < N) { "Invalid private key" }

        val point = scalarMul(d0, G) ?: throw IllegalArgumentException("Invalid private key")
        val d = if (point.y.testBit(0)) N.subtract(d0) else d0

        val auxRand = aux ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
        val t = d.to32Bytes().xorWith(taggedHash("BIP340/aux", auxRand))
        val randHash = taggedHash("BIP340/nonce", t + point.x.to32Bytes() + msg)
        val k0 = BigInteger(1, randHash).mod(N)
        require(k0 > BigInteger.ZERO) { "Nonce k is zero" }

        val R = scalarMul(k0, G) ?: throw IllegalArgumentException("R is point at infinity")
        val k = if (R.y.testBit(0)) N.subtract(k0) else k0

        val Rx = R.x.to32Bytes()
        val Px = point.x.to32Bytes()
        val e = BigInteger(1, taggedHash("BIP340/challenge", Rx + Px + msg)).mod(N)
        val s = k.add(e.multiply(d)).mod(N)
        return Rx + s.to32Bytes()
    }
}
