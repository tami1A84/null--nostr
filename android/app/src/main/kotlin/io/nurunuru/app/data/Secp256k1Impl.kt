package io.nurunuru.app.data

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pure Kotlin secp256k1 implementation for Nostr.
 * Covers: pubkey derivation, BIP-340 Schnorr signing, ECDH (NIP-04).
 * No native libraries required.
 */
internal object Secp256k1Impl {

    // ── Curve parameters ────────────────────────────────────────────────────
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val Gx = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val Gy = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val G = ECPoint(Gx, Gy)

    private data class ECPoint(val x: BigInteger, val y: BigInteger)

    // ── EC arithmetic ────────────────────────────────────────────────────────

    private fun pointAdd(P: ECPoint?, Q: ECPoint?): ECPoint? {
        if (P == null) return Q
        if (Q == null) return P
        return if (P.x == Q.x) {
            if (P.y != Q.y || P.y == BigInteger.ZERO) null // point at infinity
            else { // doubling
                val lam = BigInteger.valueOf(3).multiply(P.x.pow(2))
                    .multiply(BigInteger.TWO.multiply(P.y).modInverse(this.P)).mod(this.P)
                val x3 = lam.pow(2).subtract(BigInteger.TWO.multiply(P.x)).mod(this.P)
                val y3 = lam.multiply(P.x.subtract(x3)).subtract(P.y).mod(this.P)
                ECPoint(x3, y3)
            }
        } else {
            val lam = Q.y.subtract(P.y).multiply(Q.x.subtract(P.x).modInverse(this.P)).mod(this.P)
            val x3 = lam.pow(2).subtract(P.x).subtract(Q.x).mod(this.P)
            val y3 = lam.multiply(P.x.subtract(x3)).subtract(P.y).mod(this.P)
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

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Encode BigInteger as exactly 32 bytes (big-endian, zero-padded). */
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
        val out = ByteArray(this.size + other.size)
        copyInto(out); other.copyInto(out, this.size)
        return out
    }

    private fun ByteArray.xorWith(other: ByteArray): ByteArray =
        ByteArray(size) { i -> (this[i].toInt() xor other[i].toInt()).toByte() }

    /** BIP-340 tagged hash: SHA256(SHA256(tag) || SHA256(tag) || data). */
    private fun taggedHash(tag: String, data: ByteArray): ByteArray {
        val tagHash = sha256(tag.toByteArray(Charsets.UTF_8))
        return sha256(tagHash + tagHash + data)
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Derive the x-only (32-byte) public key from a 32-byte private key.
     */
    fun pubkeyCreate(privKey: ByteArray): ByteArray {
        val d = BigInteger(1, privKey)
        require(d > BigInteger.ZERO && d < N) { "Invalid private key" }
        val P = scalarMul(d, G) ?: throw IllegalArgumentException("Invalid private key")
        return P.x.to32Bytes()
    }

    /**
     * NIP-04 ECDH: returns the 32-byte x-coordinate of the shared point.
     * [pubKeyX] is the 32-byte x-only (Nostr) public key of the other party.
     */
    fun ecdhNip04(privKey: ByteArray, pubKeyX: ByteArray): ByteArray {
        val d = BigInteger(1, privKey)
        val x = BigInteger(1, pubKeyX)
        // Recover Y (even) from X: y² = x³ + 7 mod P, p ≡ 3 mod 4
        val y2 = x.modPow(BigInteger.valueOf(3), P).add(BigInteger.valueOf(7)).mod(P)
        val y = y2.modPow(P.add(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)
        val yEven = if (y.testBit(0)) P.subtract(y) else y
        val sharedPoint = scalarMul(d, ECPoint(x, yEven))
            ?: throw IllegalArgumentException("ECDH produced point at infinity")
        return sharedPoint.x.to32Bytes()
    }

    /**
     * BIP-340 Schnorr signature.
     * Returns 64-byte signature (R.x || s).
     * [aux] is 32 bytes of auxiliary randomness (null = use SecureRandom).
     */
    fun schnorrSign(msg: ByteArray, privKey: ByteArray, aux: ByteArray?): ByteArray {
        require(msg.size == 32) { "Message must be 32 bytes" }
        var d0 = BigInteger(1, privKey)
        require(d0 > BigInteger.ZERO && d0 < N) { "Invalid private key" }

        val P = scalarMul(d0, G) ?: throw IllegalArgumentException("Invalid private key")
        // Negate secret key if P.y is odd (BIP-340 lift_x convention)
        val d = if (P.y.testBit(0)) N.subtract(d0) else d0

        val auxRand = aux ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
        val t = d.to32Bytes().xorWith(taggedHash("BIP340/aux", auxRand))
        val randHash = taggedHash("BIP340/nonce", t + P.x.to32Bytes() + msg)
        val k0 = BigInteger(1, randHash).mod(N)
        require(k0 > BigInteger.ZERO) { "Nonce k is zero" }

        val R = scalarMul(k0, G) ?: throw IllegalArgumentException("R is point at infinity")
        val k = if (R.y.testBit(0)) N.subtract(k0) else k0

        val Rx = R.x.to32Bytes()
        val Px = P.x.to32Bytes()
        val e = BigInteger(1, taggedHash("BIP340/challenge", Rx + Px + msg)).mod(N)
        val s = k.add(e.multiply(d)).mod(N)

        return Rx + s.to32Bytes()
    }
}
