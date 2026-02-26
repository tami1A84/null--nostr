package io.nurunuru.app.data

import kotlin.math.*

object GeohashUtils {
    private const val BASE32_CODES = "0123456789bcdefghjkmnpqrstuvwxyz"
    private val BASE32_CODES_DICT = BASE32_CODES.withIndex().associate { it.value to it.index }

    fun encodeGeohash(lat: Double, lon: Double, precision: Int = 5): String {
        var latRange = doubleArrayOf(-90.0, 90.0)
        var lonRange = doubleArrayOf(-180.0, 180.0)
        var hash = ""
        var bit = 0
        var ch = 0
        var isLon = true

        while (hash.length < precision) {
            if (isLon) {
                val mid = (lonRange[0] + lonRange[1]) / 2
                if (lon > mid) {
                    ch = ch or (1 shl (4 - bit))
                    lonRange[0] = mid
                } else {
                    lonRange[1] = mid
                }
            } else {
                val mid = (latRange[0] + latRange[1]) / 2
                if (lat > mid) {
                    ch = ch or (1 shl (4 - bit))
                    latRange[0] = mid
                } else {
                    latRange[1] = mid
                }
            }

            isLon = !isLon
            if (bit < 4) {
                bit++
            } else {
                hash += BASE32_CODES[ch]
                bit = 0
                ch = 0
            }
        }

        return hash
    }

    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // Earth's radius in km
        val dLat = (lat2 - lat1) * Math.PI / 180
        val dLon = (lon2 - lon1) * Math.PI / 180
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1 * Math.PI / 180) * cos(lat2 * Math.PI / 180) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
