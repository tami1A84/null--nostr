package io.nurunuru.app.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Secp256k1Impl using BIP-340 official test vectors.
 * Source: https://github.com/bitcoin/bips/blob/master/bip-0340/test-vectors.csv
 */
class Secp256k1ImplTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun String.fromHex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }

    // ── BIP-340 pubkey derivation test vectors ────────────────────────────────

    /**
     * BIP-340 vector #0: secret=3, expected pubkey x-coordinate.
     */
    @Test
    fun pubkeyCreate_bip340Vector0_returnsExpectedXCoord() {
        val secret = "0000000000000000000000000000000000000000000000000000000000000003".fromHex()
        val pubkey = Secp256k1Impl.pubkeyCreate(secret)
        assertEquals(32, pubkey.size)
        assertEquals(
            "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9",
            pubkey.toHexString()
        )
    }

    /**
     * BIP-340 vector #1.
     */
    @Test
    fun pubkeyCreate_bip340Vector1_returnsExpectedXCoord() {
        val secret = "b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfef".fromHex()
        val pubkey = Secp256k1Impl.pubkeyCreate(secret)
        assertEquals(
            "dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659",
            pubkey.toHexString()
        )
    }

    /**
     * BIP-340 vector #2.
     */
    @Test
    fun pubkeyCreate_bip340Vector2_returnsExpectedXCoord() {
        val secret = "c90fdaa22168c234c4c6628b80dc1cd129024e088a67cc74020bbea63b14e5c9".fromHex()
        val pubkey = Secp256k1Impl.pubkeyCreate(secret)
        assertEquals(
            "dd308afec5777e13121fa72b9cc1b7cc0139715309b086c960e18fd969774eb8",
            pubkey.toHexString()
        )
    }

    // ── BIP-340 Schnorr signing test vectors ──────────────────────────────────

    /**
     * BIP-340 signing vector #0:
     *   secret=3, msg=0..0, aux=0..0
     *   expected sig=E907831F...536C0
     */
    @Test
    fun schnorrSign_bip340Vector0_returnsExpectedSignature() {
        val secret = "0000000000000000000000000000000000000000000000000000000000000003".fromHex()
        val msg    = ByteArray(32) // all zeros
        val aux    = ByteArray(32) // all zeros
        val sig = Secp256k1Impl.schnorrSign(msg, secret, aux)
        assertEquals(64, sig.size)
        assertEquals(
            "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0",
            sig.toHexString()
        )
    }

    /**
     * BIP-340 signing vector #1.
     */
    @Test
    fun schnorrSign_bip340Vector1_returnsExpectedSignature() {
        val secret = "b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfef".fromHex()
        val msg    = "243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89".fromHex()
        val aux    = "0000000000000000000000000000000000000000000000000000000000000001".fromHex()
        val sig = Secp256k1Impl.schnorrSign(msg, secret, aux)
        assertEquals(
            "6896bd60eeae296db48a229ff71dfe071bde413e6d43f917dc8dcf8c78de33418906d11ac976abccb20b091292bff4ea897efcb639ea871cfa95f6de339e4b0a",
            sig.toHexString()
        )
    }

    // ── General signing properties ────────────────────────────────────────────

    @Test
    fun schnorrSign_produces64ByteSignature() {
        val secret = ByteArray(32).also { it[31] = 7 }
        val msg = ByteArray(32).also { it[0] = 42 }
        val sig = Secp256k1Impl.schnorrSign(msg, secret, null)
        assertEquals(64, sig.size)
    }

    @Test
    fun schnorrSign_isDeterministicWithFixedAux() {
        val secret = ByteArray(32).also { it[31] = 7 }
        val msg = ByteArray(32).also { it[0] = 42 }
        val aux = ByteArray(32).also { it[31] = 1 }
        val sig1 = Secp256k1Impl.schnorrSign(msg, secret, aux)
        val sig2 = Secp256k1Impl.schnorrSign(msg, secret, aux)
        assertArrayEquals(sig1, sig2)
    }

    @Test
    fun schnorrSign_differentMessagesProduceDifferentSignatures() {
        val secret = ByteArray(32).also { it[31] = 3 }
        val aux = ByteArray(32)
        val msg1 = ByteArray(32).also { it[0] = 1 }
        val msg2 = ByteArray(32).also { it[0] = 2 }
        val sig1 = Secp256k1Impl.schnorrSign(msg1, secret, aux)
        val sig2 = Secp256k1Impl.schnorrSign(msg2, secret, aux)
        assertFalse(sig1.contentEquals(sig2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun schnorrSign_throwsForNon32ByteMessage() {
        val secret = ByteArray(32).also { it[31] = 3 }
        Secp256k1Impl.schnorrSign(ByteArray(31), secret, null)
    }

    // ── Invalid key handling ──────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun pubkeyCreate_throwsForZeroPrivateKey() {
        Secp256k1Impl.pubkeyCreate(ByteArray(32)) // all zeros → d=0 is invalid
    }

    // ── NIP-04 ECDH ───────────────────────────────────────────────────────────

    @Test
    fun ecdhNip04_returns32ByteSharedSecret() {
        val priv1 = ByteArray(32).also { it[31] = 3 }
        val priv2 = ByteArray(32).also { it[31] = 5 }
        val pub1 = Secp256k1Impl.pubkeyCreate(priv1)
        val shared = Secp256k1Impl.ecdhNip04(priv2, pub1)
        assertEquals(32, shared.size)
    }

    @Test
    fun ecdhNip04_isSymmetric() {
        val priv1 = ByteArray(32).also { it[31] = 3 }
        val priv2 = ByteArray(32).also { it[31] = 5 }
        val pub1 = Secp256k1Impl.pubkeyCreate(priv1)
        val pub2 = Secp256k1Impl.pubkeyCreate(priv2)
        val shared12 = Secp256k1Impl.ecdhNip04(priv1, pub2)
        val shared21 = Secp256k1Impl.ecdhNip04(priv2, pub1)
        assertArrayEquals(shared12, shared21)
    }

    @Test
    fun ecdhNip04_differentKeysDifferentSecrets() {
        val priv1 = ByteArray(32).also { it[31] = 3 }
        val priv2 = ByteArray(32).also { it[31] = 5 }
        val priv3 = ByteArray(32).also { it[31] = 7 }
        val pub1 = Secp256k1Impl.pubkeyCreate(priv1)
        val pub3 = Secp256k1Impl.pubkeyCreate(priv3)
        val shared12 = Secp256k1Impl.ecdhNip04(priv2, pub1)
        val shared32 = Secp256k1Impl.ecdhNip04(priv2, pub3)
        assertFalse(shared12.contentEquals(shared32))
    }
}
