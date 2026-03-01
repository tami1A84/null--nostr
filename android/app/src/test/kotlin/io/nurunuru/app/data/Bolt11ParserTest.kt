package io.nurunuru.app.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for NostrRepository.parseBolt11Amount().
 * Verifies bolt11 invoice amount parsing for various multipliers.
 */
class Bolt11ParserTest {

    @Test
    fun `parseBolt11Amount - micro BTC to sats`() {
        // 1000u = 1000 * 100 = 100,000 sats
        assertEquals(100_000L, NostrRepository.parseBolt11Amount("lnbc1000u1..."))
    }

    @Test
    fun `parseBolt11Amount - milli BTC to sats`() {
        // 10m = 10 * 100,000 = 1,000,000 sats
        assertEquals(1_000_000L, NostrRepository.parseBolt11Amount("lnbc10m1..."))
    }

    @Test
    fun `parseBolt11Amount - nano BTC to sats`() {
        // 50000n = 50000 / 10 = 5000 sats
        assertEquals(5_000L, NostrRepository.parseBolt11Amount("lnbc50000n1..."))
    }

    @Test
    fun `parseBolt11Amount - pico BTC to sats`() {
        // 10000000p = 10000000 / 10000 = 1000 sats
        assertEquals(1_000L, NostrRepository.parseBolt11Amount("lnbc10000000p1..."))
    }

    @Test
    fun `parseBolt11Amount - BTC (no multiplier)`() {
        // 1 BTC = 100,000,000 sats
        assertEquals(100_000_000L, NostrRepository.parseBolt11Amount("lnbc1..."))
    }

    @Test
    fun `parseBolt11Amount - blank input returns 0`() {
        assertEquals(0L, NostrRepository.parseBolt11Amount(""))
        assertEquals(0L, NostrRepository.parseBolt11Amount("   "))
    }

    @Test
    fun `parseBolt11Amount - invalid format returns 0`() {
        assertEquals(0L, NostrRepository.parseBolt11Amount("not-a-bolt11"))
        assertEquals(0L, NostrRepository.parseBolt11Amount("lnbc"))
    }

    @Test
    fun `parseBolt11Amount - case insensitive`() {
        assertEquals(100_000L, NostrRepository.parseBolt11Amount("LNBC1000u1..."))
        assertEquals(100_000L, NostrRepository.parseBolt11Amount("LnBc1000U1..."))
    }

    @Test
    fun `parseBolt11Amount - common zap amounts`() {
        // 21 sats = 210000n
        assertEquals(21_000L, NostrRepository.parseBolt11Amount("lnbc210000n1..."))
        // 100 sats = 1000000n or 1000u
        assertEquals(100_000L, NostrRepository.parseBolt11Amount("lnbc1000u1..."))
    }
}
