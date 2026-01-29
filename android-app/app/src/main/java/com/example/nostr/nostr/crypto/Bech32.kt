package com.example.nostr.nostr.crypto

/**
 * Bech32 encoding/decoding for NIP-19
 */
object Bech32 {

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val CHARSET_REV = IntArray(128) { -1 }.apply {
        CHARSET.forEachIndexed { index, c -> this[c.code] = index }
    }

    /**
     * Encode data to Bech32 format
     */
    fun encode(hrp: String, data: ByteArray): String {
        val data5bit = convertBits(data, 8, 5, true)
        val checksum = createChecksum(hrp, data5bit)
        val combined = data5bit + checksum

        return hrp + "1" + combined.map { CHARSET[it] }.joinToString("")
    }

    /**
     * Decode Bech32 string
     */
    fun decode(bech32: String): Pair<String, ByteArray> {
        val lowercase = bech32.lowercase()
        val pos = lowercase.lastIndexOf('1')
        require(pos >= 1) { "Invalid Bech32 string: missing separator" }
        require(pos + 7 <= lowercase.length) { "Invalid Bech32 string: too short" }

        val hrp = lowercase.substring(0, pos)
        val data = lowercase.substring(pos + 1).map { c ->
            CHARSET_REV[c.code].also {
                require(it != -1) { "Invalid Bech32 character: $c" }
            }
        }.toIntArray()

        require(verifyChecksum(hrp, data)) { "Invalid Bech32 checksum" }

        // Remove checksum (last 6 characters)
        val data5bit = data.dropLast(6).toIntArray()
        val data8bit = convertBits(data5bit, 5, 8, false)

        return Pair(hrp, data8bit.map { it.toByte() }.toByteArray())
    }

    /**
     * Convert bits between different group sizes
     */
    private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): IntArray {
        var acc = 0
        var bits = 0
        val result = mutableListOf<Int>()
        val maxv = (1 shl toBits) - 1

        for (value in data) {
            acc = (acc shl fromBits) or (value.toInt() and 0xff)
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result.add((acc shr bits) and maxv)
            }
        }

        if (pad) {
            if (bits > 0) {
                result.add((acc shl (toBits - bits)) and maxv)
            }
        } else {
            require(bits < fromBits && ((acc shl (toBits - bits)) and maxv) == 0) {
                "Invalid padding"
            }
        }

        return result.toIntArray()
    }

    private fun convertBits(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val result = mutableListOf<Byte>()
        val maxv = (1 shl toBits) - 1

        for (value in data) {
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result.add(((acc shr bits) and maxv).toByte())
            }
        }

        if (pad) {
            if (bits > 0) {
                result.add(((acc shl (toBits - bits)) and maxv).toByte())
            }
        }

        return result.toByteArray()
    }

    /**
     * Polymod for checksum calculation
     */
    private fun polymod(values: IntArray): Int {
        val generator = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val top = chk shr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in generator.indices) {
                if ((top shr i) and 1 == 1) {
                    chk = chk xor generator[i]
                }
            }
        }
        return chk
    }

    /**
     * Expand HRP for checksum calculation
     */
    private fun hrpExpand(hrp: String): IntArray {
        val result = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) {
            result[i] = hrp[i].code shr 5
            result[i + hrp.length + 1] = hrp[i].code and 31
        }
        result[hrp.length] = 0
        return result
    }

    /**
     * Create checksum
     */
    private fun createChecksum(hrp: String, data: IntArray): IntArray {
        val values = hrpExpand(hrp) + data + intArrayOf(0, 0, 0, 0, 0, 0)
        val polymod = polymod(values) xor 1
        return IntArray(6) { i -> (polymod shr (5 * (5 - i))) and 31 }
    }

    /**
     * Verify checksum
     */
    private fun verifyChecksum(hrp: String, data: IntArray): Boolean {
        return polymod(hrpExpand(hrp) + data) == 1
    }
}

/**
 * NIP-19 encoding utilities
 */
object Nip19 {

    /**
     * Encode note ID
     */
    fun encodeNote(eventId: ByteArray): String {
        return Bech32.encode("note", eventId)
    }

    /**
     * Decode note ID
     */
    fun decodeNote(note: String): ByteArray {
        val (hrp, data) = Bech32.decode(note)
        require(hrp == "note") { "Invalid note format" }
        return data
    }

    /**
     * Parse any NIP-19 identifier
     */
    fun parse(bech32: String): Nip19Data {
        val (hrp, data) = Bech32.decode(bech32)
        return when (hrp) {
            "npub" -> Nip19Data.NPub(data)
            "nsec" -> Nip19Data.NSec(data)
            "note" -> Nip19Data.Note(data)
            "nprofile" -> parseNProfile(data)
            "nevent" -> parseNEvent(data)
            "naddr" -> parseNAddr(data)
            else -> throw IllegalArgumentException("Unknown NIP-19 type: $hrp")
        }
    }

    private fun parseNProfile(data: ByteArray): Nip19Data.NProfile {
        // TLV format
        val relays = mutableListOf<String>()
        var pubkey: ByteArray? = null
        var i = 0
        while (i < data.size) {
            val type = data[i].toInt() and 0xff
            val length = data[i + 1].toInt() and 0xff
            val value = data.copyOfRange(i + 2, i + 2 + length)
            when (type) {
                0 -> pubkey = value
                1 -> relays.add(String(value, Charsets.UTF_8))
            }
            i += 2 + length
        }
        return Nip19Data.NProfile(pubkey!!, relays)
    }

    private fun parseNEvent(data: ByteArray): Nip19Data.NEvent {
        val relays = mutableListOf<String>()
        var eventId: ByteArray? = null
        var author: ByteArray? = null
        var kind: Int? = null
        var i = 0
        while (i < data.size) {
            val type = data[i].toInt() and 0xff
            val length = data[i + 1].toInt() and 0xff
            val value = data.copyOfRange(i + 2, i + 2 + length)
            when (type) {
                0 -> eventId = value
                1 -> relays.add(String(value, Charsets.UTF_8))
                2 -> author = value
                3 -> kind = value.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
            }
            i += 2 + length
        }
        return Nip19Data.NEvent(eventId!!, relays, author, kind)
    }

    private fun parseNAddr(data: ByteArray): Nip19Data.NAddr {
        val relays = mutableListOf<String>()
        var identifier: String? = null
        var author: ByteArray? = null
        var kind: Int? = null
        var i = 0
        while (i < data.size) {
            val type = data[i].toInt() and 0xff
            val length = data[i + 1].toInt() and 0xff
            val value = data.copyOfRange(i + 2, i + 2 + length)
            when (type) {
                0 -> identifier = String(value, Charsets.UTF_8)
                1 -> relays.add(String(value, Charsets.UTF_8))
                2 -> author = value
                3 -> kind = value.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
            }
            i += 2 + length
        }
        return Nip19Data.NAddr(identifier!!, author!!, kind!!, relays)
    }
}

/**
 * NIP-19 data types
 */
sealed class Nip19Data {
    data class NPub(val pubkey: ByteArray) : Nip19Data()
    data class NSec(val privateKey: ByteArray) : Nip19Data()
    data class Note(val eventId: ByteArray) : Nip19Data()
    data class NProfile(val pubkey: ByteArray, val relays: List<String>) : Nip19Data()
    data class NEvent(val eventId: ByteArray, val relays: List<String>, val author: ByteArray?, val kind: Int?) : Nip19Data()
    data class NAddr(val identifier: String, val author: ByteArray, val kind: Int, val relays: List<String>) : Nip19Data()
}
