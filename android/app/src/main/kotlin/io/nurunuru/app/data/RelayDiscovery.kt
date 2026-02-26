package io.nurunuru.app.data

data class RelayInfo(
    val url: String,
    val lat: Double,
    val lon: Double,
    val name: String,
    val region: String,
    val priority: Int
)

data class RegionInfo(
    val id: String,
    val name: String,
    val nameEn: String,
    val lat: Double,
    val lon: Double,
    val country: String
)

object RelayDiscovery {

    val GPS_RELAY_DATABASE = listOf(
        RelayInfo("wss://yabu.me", 35.6092, 139.73, "やぶみ", "JP", 1),
        RelayInfo("wss://relay.nostr.wirednet.jp", 34.706, 135.493, "WiredNet JP", "JP", 1),
        RelayInfo("wss://r.kojira.io", 35.6762, 139.6503, "Kojira", "JP", 1),
        RelayInfo("wss://relay.origin.land", 35.6673, 139.751, "Origin Land", "JP", 2),
        RelayInfo("wss://v-relay.d02.vrtmrz.net", 34.6937, 135.502, "vrtmrz", "JP", 2),
        RelayInfo("wss://relay.0xchat.com", 1.35208, 103.82, "0xchat", "SG", 1),
        RelayInfo("wss://relay.damus.io", 43.6532, -79.3832, "Damus", "NA", 1),
        RelayInfo("wss://relay.primal.net", 43.6532, -79.3832, "Primal", "NA", 1),
        RelayInfo("wss://nos.lol", 50.4754, 12.3683, "nos.lol", "EU", 1),
        RelayInfo("wss://relay.snort.social", 53.3498, -6.26031, "Snort", "EU", 1),
        RelayInfo("wss://relay.nostr.bg", 42.6977, 23.3219, "nostr.bg", "Global", 1)
    )

    val REGION_COORDINATES = listOf(
        RegionInfo("jp-tokyo", "東京", "Tokyo", 35.6762, 139.6503, "JP"),
        RegionInfo("jp-osaka", "大阪", "Osaka", 34.6937, 135.5023, "JP"),
        RegionInfo("sg", "シンガポール", "Singapore", 1.3521, 103.8198, "SG"),
        RegionInfo("us-east", "北米東部", "US East", 40.7128, -74.0060, "US"),
        RegionInfo("eu-central", "中央ヨーロッパ", "Central Europe", 52.5200, 13.4050, "EU"),
        RegionInfo("global", "グローバル", "Global", 0.0, 0.0, "Global")
    )

    fun findNearestRelays(userLat: Double, userLon: Double, count: Int = 5): List<Pair<RelayInfo, Double>> {
        return GPS_RELAY_DATABASE.map { relay ->
            relay to GeohashUtils.calculateDistance(userLat, userLon, relay.lat, relay.lon)
        }.sortedWith(compareBy({ it.first.priority }, { it.second }))
         .take(count)
    }

    fun generateRelayListByLocation(userLat: Double, userLon: Double): List<Triple<String, Boolean, Boolean>> {
        val nearest = findNearestRelays(userLat, userLon, 10)
        val combined = mutableListOf<Triple<String, Boolean, Boolean>>()
        val added = mutableSetOf<String>()

        // Top 2 as both read/write
        nearest.take(2).forEach { (r, _) ->
            combined.add(Triple(r.url, true, true))
            added.add(r.url)
        }

        // Next 3 as write-only if not added
        nearest.drop(2).take(3).forEach { (r, _) ->
            if (!added.contains(r.url)) {
                combined.add(Triple(r.url, false, true))
                added.add(r.url)
            }
        }

        return combined
    }
}
