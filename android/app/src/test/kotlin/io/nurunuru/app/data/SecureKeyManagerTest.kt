package io.nurunuru.app.data

import org.junit.Test
import org.junit.Assert.*

/**
 * SecureKeyManager のユニットテスト。
 *
 * Android Keystore はエミュレータ/デバイスでのみ動作するため、
 * ここでは Keystore に依存しないロジック部分のみテストする。
 * 暗号化/復号の統合テストは androidTest で行う。
 */
class SecureKeyManagerTest {

    @Test
    fun `ByteArray zeroize clears all bytes`() {
        val key = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10,
            0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
            0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20)

        // fill(0) をシミュレート
        key.fill(0)

        for (b in key) {
            assertEquals("All bytes should be zero after zeroize", 0.toByte(), b)
        }
    }

    @Test
    fun `hexToBytes converts valid hex string correctly`() {
        val hex = "0123456789abcdef"
        val bytes = hexToBytes(hex)

        assertNotNull(bytes)
        assertEquals(8, bytes!!.size)
        assertEquals(0x01.toByte(), bytes[0])
        assertEquals(0x23.toByte(), bytes[1])
        assertEquals(0xab.toByte(), bytes[5])
        assertEquals(0xef.toByte(), bytes[7])
    }

    @Test
    fun `hexToBytes returns null for odd length`() {
        val result = hexToBytes("abc")
        assertNull(result)
    }

    @Test
    fun `hexToBytes returns null for invalid hex`() {
        val result = hexToBytes("gg01")
        assertNull(result)
    }

    @Test
    fun `bytesToHex roundtrip preserves data`() {
        val original = byteArrayOf(
            0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()
        )
        val hex = bytesToHex(original)
        val restored = hexToBytes(hex)

        assertNotNull(restored)
        assertArrayEquals(original, restored)
    }

    @Test
    fun `32 byte key roundtrip through hex`() {
        // Simulate a secp256k1 private key
        val key = ByteArray(32) { (it + 1).toByte() }
        val hex = bytesToHex(key)
        assertEquals(64, hex.length)

        val restored = hexToBytes(hex)
        assertNotNull(restored)
        assertEquals(32, restored!!.size)
        assertArrayEquals(key, restored)
    }

    @Test
    fun `zeroize then read returns all zeros`() {
        val key = ByteArray(32) { 0xFF.toByte() }
        // Verify it's initially non-zero
        assertTrue(key.any { it != 0.toByte() })

        // Zeroize
        key.fill(0)

        // Verify all zeros
        assertTrue(key.all { it == 0.toByte() })
    }

    @Test
    fun `copyOf creates independent copy that survives zeroize of original`() {
        val original = ByteArray(32) { (it + 1).toByte() }
        val copy = original.copyOf()

        // Zeroize original
        original.fill(0)

        // Copy should be unaffected
        assertEquals(1.toByte(), copy[0])
        assertEquals(32.toByte(), copy[31])

        // Original should be zeroed
        assertTrue(original.all { it == 0.toByte() })
    }

    // ─── Helper functions (duplicated from SecureKeyManager for testing) ─────

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
