package io.nurunuru.app.data

import io.nurunuru.app.data.NostrKeyUtils.hexToBytes
import io.nurunuru.app.data.NostrKeyUtils.toHex
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for NostrKeyUtils: bech32, key parsing/derivation, hex helpers.
 * Uses the same BIP-340 key vectors as Secp256k1ImplTest.
 */
class NostrKeyUtilsTest {

    // Known key pair (BIP-340 vector #0)
    private val knownPrivHex = "0000000000000000000000000000000000000000000000000000000000000003"
    private val knownPubHex  = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"

    // Known key pair (BIP-340 vector #1)
    private val knownPrivHex2 = "b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfef"
    private val knownPubHex2  = "dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659"

    // ── Hex helpers ───────────────────────────────────────────────────────────

    @Test
    fun toHex_encodesAllBytesCorrectly() {
        val bytes = byteArrayOf(0x00, 0xff.toByte(), 0x0f, 0xf0.toByte(), 0xab.toByte())
        assertEquals("00ff0ff0ab", bytes.toHex())
    }

    @Test
    fun toHex_emptyArrayReturnsEmptyString() {
        assertEquals("", ByteArray(0).toHex())
    }

    @Test
    fun hexToBytes_decodesLowercase() {
        val result = "00ff0ff0".hexToBytes()
        assertNotNull(result)
        assertArrayEquals(byteArrayOf(0x00, 0xff.toByte(), 0x0f, 0xf0.toByte()), result)
    }

    @Test
    fun hexToBytes_decodesUppercase() {
        val result = "00FF0FF0".hexToBytes()
        assertNotNull(result)
        assertArrayEquals(byteArrayOf(0x00, 0xff.toByte(), 0x0f, 0xf0.toByte()), result)
    }

    @Test
    fun hexToBytes_returnsNullForOddLength() {
        assertNull("abc".hexToBytes())
    }

    @Test
    fun hexToBytes_returnsNullForInvalidChars() {
        assertNull("00gg".hexToBytes())
    }

    @Test
    fun hexToBytes_emptyStringReturnsEmptyArray() {
        val result = "".hexToBytes()
        assertNotNull(result)
        assertEquals(0, result!!.size)
    }

    @Test
    fun toHex_hexToBytes_roundtrip() {
        val original = ByteArray(32) { it.toByte() }
        val roundtrip = original.toHex().hexToBytes()
        assertArrayEquals(original, roundtrip)
    }

    // ── Bech32 ────────────────────────────────────────────────────────────────

    @Test
    fun bech32EncodeAndDecode_roundtripFor32ByteKey() {
        val data = ByteArray(32) { (it + 1).toByte() }
        val encoded = NostrKeyUtils.bech32Encode("npub", data)
        val decoded = NostrKeyUtils.bech32Decode(encoded)
        assertNotNull(decoded)
        assertEquals("npub", decoded!!.first)
        assertArrayEquals(data, decoded.second)
    }

    @Test
    fun bech32EncodeAndDecode_roundtripEmptyData() {
        val data = ByteArray(0)
        val encoded = NostrKeyUtils.bech32Encode("test", data)
        val decoded = NostrKeyUtils.bech32Decode(encoded)
        assertNotNull(decoded)
        assertEquals("test", decoded!!.first)
        assertArrayEquals(data, decoded.second)
    }

    @Test
    fun bech32Decode_returnsNullForInvalidChecksum() {
        // Corrupt the last character of a valid bech32
        val valid = NostrKeyUtils.bech32Encode("npub", ByteArray(32) { it.toByte() })
        val corrupted = valid.dropLast(1) + "x"
        assertNull(NostrKeyUtils.bech32Decode(corrupted))
    }

    @Test
    fun bech32Decode_isCaseInsensitive() {
        val data = ByteArray(32) { (it + 1).toByte() }
        val encoded = NostrKeyUtils.bech32Encode("npub", data)
        val decodedUpper = NostrKeyUtils.bech32Decode(encoded.uppercase())
        assertNotNull(decodedUpper)
        assertArrayEquals(data, decodedUpper!!.second)
    }

    @Test
    fun bech32Encode_producesOnlyValidCharsAndHrp() {
        val data = ByteArray(32) { it.toByte() }
        val encoded = NostrKeyUtils.bech32Encode("npub", data)
        assertTrue("Should start with 'npub1'", encoded.startsWith("npub1"))
        // All chars after hrp1 must be in bech32 charset
        val charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        encoded.drop(5).forEach { c ->
            assertTrue("'$c' not in bech32 charset", charset.contains(c.lowercaseChar()))
        }
    }

    // ── parsePrivateKey ───────────────────────────────────────────────────────

    @Test
    fun parsePrivateKey_acceptsLowercaseHex() {
        val result = NostrKeyUtils.parsePrivateKey(knownPrivHex)
        assertEquals(knownPrivHex, result)
    }

    @Test
    fun parsePrivateKey_acceptsUppercaseHexAndNormalizes() {
        val upper = knownPrivHex2.uppercase()
        val result = NostrKeyUtils.parsePrivateKey(upper)
        assertEquals(knownPrivHex2.lowercase(), result)
    }

    @Test
    fun parsePrivateKey_acceptsNsecBech32() {
        val nsec = NostrKeyUtils.encodeNsec(knownPrivHex)!!
        val result = NostrKeyUtils.parsePrivateKey(nsec)
        assertEquals(knownPrivHex, result)
    }

    @Test
    fun parsePrivateKey_returnsNullForShortHex() {
        assertNull(NostrKeyUtils.parsePrivateKey("0003"))
    }

    @Test
    fun parsePrivateKey_returnsNullForEmptyString() {
        assertNull(NostrKeyUtils.parsePrivateKey(""))
    }

    @Test
    fun parsePrivateKey_returnsNullForInvalidBech32() {
        assertNull(NostrKeyUtils.parsePrivateKey("nsec1invalid"))
    }

    @Test
    fun parsePrivateKey_returnsNullForNpubInsteadOfNsec() {
        // npub bech32 should NOT be accepted as private key
        val npub = NostrKeyUtils.encodeNpub(knownPubHex)!!
        assertNull(NostrKeyUtils.parsePrivateKey(npub))
    }

    @Test
    fun parsePrivateKey_trimsWhitespace() {
        val result = NostrKeyUtils.parsePrivateKey("  $knownPrivHex  ")
        assertEquals(knownPrivHex, result)
    }

    // ── derivePublicKey ───────────────────────────────────────────────────────

    @Test
    fun derivePublicKey_vector0_returnsCorrectPubkey() {
        val pubHex = NostrKeyUtils.derivePublicKey(knownPrivHex)
        assertNotNull(pubHex)
        assertEquals(knownPubHex, pubHex)
    }

    @Test
    fun derivePublicKey_vector1_returnsCorrectPubkey() {
        val pubHex = NostrKeyUtils.derivePublicKey(knownPrivHex2)
        assertNotNull(pubHex)
        assertEquals(knownPubHex2, pubHex)
    }

    @Test
    fun derivePublicKey_returnsNullForInvalidHex() {
        assertNull(NostrKeyUtils.derivePublicKey("not-valid-hex"))
    }

    @Test
    fun derivePublicKey_returns64CharHex() {
        val pubHex = NostrKeyUtils.derivePublicKey(knownPrivHex)
        assertNotNull(pubHex)
        assertEquals(64, pubHex!!.length)
    }

    // ── encodeNpub / encodeNsec ───────────────────────────────────────────────

    @Test
    fun encodeNpub_startsWithNpub1() {
        val npub = NostrKeyUtils.encodeNpub(knownPubHex)
        assertNotNull(npub)
        assertTrue("Expected npub1 prefix, got: $npub", npub!!.startsWith("npub1"))
    }

    @Test
    fun encodeNsec_startsWithNsec1() {
        val nsec = NostrKeyUtils.encodeNsec(knownPrivHex)
        assertNotNull(nsec)
        assertTrue("Expected nsec1 prefix, got: $nsec", nsec!!.startsWith("nsec1"))
    }

    @Test
    fun encodeNpub_decodeRoundtrip() {
        val npub = NostrKeyUtils.encodeNpub(knownPubHex)!!
        val decoded = NostrKeyUtils.bech32Decode(npub)!!
        val recoveredHex = decoded.second.joinToString("") { "%02x".format(it) }
        assertEquals(knownPubHex, recoveredHex)
    }

    @Test
    fun encodeNsec_decodeRoundtrip() {
        val nsec = NostrKeyUtils.encodeNsec(knownPrivHex)!!
        val decoded = NostrKeyUtils.bech32Decode(nsec)!!
        assertEquals("nsec", decoded.first)
        val recoveredHex = decoded.second.joinToString("") { "%02x".format(it) }
        assertEquals(knownPrivHex, recoveredHex)
    }

    @Test
    fun encodeNpub_returnsNullForInvalidHex() {
        assertNull(NostrKeyUtils.encodeNpub("not-hex"))
    }

    // ── shortenPubkey ─────────────────────────────────────────────────────────

    @Test
    fun shortenPubkey_returnsNpub1Prefix() {
        val shortened = NostrKeyUtils.shortenPubkey(knownPubHex)
        assertTrue("Expected npub1 prefix, got: $shortened", shortened.startsWith("npub1"))
    }

    @Test
    fun shortenPubkey_hasReasonableLength() {
        val shortened = NostrKeyUtils.shortenPubkey(knownPubHex)
        // npub1 (5) + 8 chars = 13 total
        assertEquals(13, shortened.length)
    }

    @Test
    fun shortenPubkey_customCharsCount() {
        val shortened = NostrKeyUtils.shortenPubkey(knownPubHex, chars = 4)
        assertEquals(9, shortened.length) // npub1 (5) + 4 chars
    }

    @Test
    fun shortenPubkey_fallsBackToHexPrefixForInvalidKey() {
        // If pubkeyHex is not valid hex, encodeNpub returns null and it falls back to hex prefix
        val result = NostrKeyUtils.shortenPubkey("ZZZZ") // invalid hex → encodeNpub returns null
        assertTrue(result.isNotEmpty())
    }

    // ── Cross-function consistency ────────────────────────────────────────────

    @Test
    fun derivePublicKey_thenEncodeNpub_thenDecodeBack_roundtrip() {
        val pubHex = NostrKeyUtils.derivePublicKey(knownPrivHex)!!
        val npub = NostrKeyUtils.encodeNpub(pubHex)!!
        val decoded = NostrKeyUtils.bech32Decode(npub)!!
        val recoveredHex = decoded.second.joinToString("") { "%02x".format(it) }
        assertEquals(pubHex, recoveredHex)
    }

    @Test
    fun parsePrivateKey_thenDerivePublicKey_matchesKnownVector() {
        val nsec = NostrKeyUtils.encodeNsec(knownPrivHex)!!
        val parsedPriv = NostrKeyUtils.parsePrivateKey(nsec)!!
        val pub = NostrKeyUtils.derivePublicKey(parsedPriv)!!
        assertEquals(knownPubHex, pub)
    }
}
