package io.nurunuru.app.data

import io.nurunuru.app.data.models.Nip65Relay
import kotlin.math.roundToInt

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

data class RelayInfoWithDistance(
    val info: RelayInfo,
    val distance: Double
)

data class Nip65Config(
    val inbox: List<RelayInfoWithDistance>,
    val outbox: List<RelayInfoWithDistance>,
    val discover: List<RelayInfo>,
    val combined: List<Nip65Relay>
)

object RelayDiscovery {

    val GPS_RELAY_DATABASE = listOf(
        // Japan
        RelayInfo("wss://yabu.me", 35.6092, 139.73, "やぶみ", "JP", 1),
        RelayInfo("wss://relay.nostr.wirednet.jp", 34.706, 135.493, "WiredNet JP", "JP", 1),
        RelayInfo("wss://r.kojira.io", 35.6762, 139.6503, "Kojira", "JP", 1),
        RelayInfo("wss://relay.origin.land", 35.6673, 139.751, "Origin Land", "JP", 2),
        RelayInfo("wss://v-relay.d02.vrtmrz.net", 34.6937, 135.502, "vrtmrz", "JP", 2),

        // Asia
        RelayInfo("wss://relay.0xchat.com", 1.35208, 103.82, "0xchat", "SG", 1),
        RelayInfo("wss://nostr-01.yakihonne.com", 1.29524, 103.79, "Yakihonne", "SG", 2),
        RelayInfo("wss://nostr.dler.com", 25.0501, 121.565, "dler", "TW", 2),
        RelayInfo("wss://relay.islandbitcoin.com", 12.8498, 77.6545, "Island Bitcoin", "IN", 2),
        RelayInfo("wss://nostr.jerrynya.fun", 31.2304, 121.474, "jerrynya", "CN", 2),

        // North America
        RelayInfo("wss://relay.damus.io", 43.6532, -79.3832, "Damus", "NA", 1),
        RelayInfo("wss://relay.primal.net", 43.6532, -79.3832, "Primal", "NA", 1),
        RelayInfo("wss://relay.wellorder.net", 45.5201, -122.99, "Wellorder", "NA", 2),
        RelayInfo("wss://relay.illuminodes.com", 47.6061, -122.333, "Illuminodes", "NA", 2),
        RelayInfo("wss://relay.fundstr.me", 42.3601, -71.0589, "Fundstr", "NA", 2),
        RelayInfo("wss://nostrelites.org", 41.8781, -87.6298, "Nostrelites", "NA", 2),
        RelayInfo("wss://relay.westernbtc.com", 44.5401, -123.368, "Western BTC", "NA", 2),
        RelayInfo("wss://cyberspace.nostr1.com", 40.7057, -74.0136, "Cyberspace", "NA", 2),
        RelayInfo("wss://fanfares.nostr1.com", 40.7128, -74.006, "Fanfares", "NA", 2),

        // Europe
        RelayInfo("wss://nos.lol", 50.4754, 12.3683, "nos.lol", "EU", 1),
        RelayInfo("wss://relay.snort.social", 53.3498, -6.26031, "Snort", "EU", 1),
        RelayInfo("wss://nostr.wine", 48.8566, 2.35222, "nostr.wine", "EU", 1),
        RelayInfo("wss://relay.nostr.band", 52.52, 13.405, "nostr.band", "EU", 1),
        RelayInfo("wss://nostr.bond", 50.1109, 8.68213, "nostr.bond", "EU", 2),
        RelayInfo("wss://relay.thebluepulse.com", 49.4521, 11.0767, "Blue Pulse", "EU", 2),
        RelayInfo("wss://relay.lumina.rocks", 49.0291, 8.35695, "Lumina", "EU", 2),
        RelayInfo("wss://relay.dwadziesciajeden.pl", 52.2297, 21.0122, "dwadziesciajeden", "EU", 2),
        RelayInfo("wss://relay.angor.io", 48.1046, 11.6002, "Angor", "EU", 2),
        RelayInfo("wss://relay.malxte.de", 52.52, 13.405, "malxte", "EU", 2),
        RelayInfo("wss://purplerelay.com", 50.1109, 8.68213, "Purple Relay", "EU", 2),
        RelayInfo("wss://nostr.mom", 50.4754, 12.3683, "nostr.mom", "EU", 2),
        RelayInfo("wss://relay.nostrhub.fr", 48.1045, 11.6004, "NostrHub FR", "EU", 2),
        RelayInfo("wss://wot.dergigi.com", 64.1476, -21.9392, "Gigi WoT", "EU", 2),
        RelayInfo("wss://lightning.red", 53.3498, -6.26031, "Lightning Red", "EU", 2),

        // Scandinavia/Nordic
        RelayInfo("wss://r.alphaama.com", 60.1699, 24.9384, "Alphaama", "EU", 2),
        RelayInfo("wss://nostr.snowbla.de", 60.1699, 24.9384, "Snowblade", "EU", 2),
        RelayInfo("wss://relay.zone667.com", 60.1699, 24.9384, "Zone667", "EU", 2),

        // Russia
        RelayInfo("wss://adre.su", 59.8845, 30.3184, "adre.su", "RU", 2),

        // Middle East
        RelayInfo("wss://shu01.shugur.net", 21.4902, 39.2246, "Shugur", "ME", 2),

        // South America
        RelayInfo("wss://relay.internationalright-wing.org", -22.5022, -48.7114, "IRW", "SA", 2),

        // Global/CDN (use as fallback)
        RelayInfo("wss://relay.nostr.bg", 42.6977, 23.3219, "nostr.bg", "Global", 1),
        RelayInfo("wss://nostr.mutinywallet.com", 37.7749, -122.4194, "Mutiny", "Global", 2)
    )

    val DIRECTORY_RELAYS = listOf(
        RelayInfo("wss://directory.yabu.me", 0.0, 0.0, "Directory yabu.me", "JP", 0),
        RelayInfo("wss://purplepag.es", 0.0, 0.0, "Purple Pages", "Global", 0)
    )

    val REGION_COORDINATES = listOf(
        // Japan regions
        RegionInfo("jp-tokyo", "東京", "Tokyo", 35.6762, 139.6503, "JP"),
        RegionInfo("jp-osaka", "大阪", "Osaka", 34.6937, 135.5023, "JP"),
        RegionInfo("jp-nagoya", "名古屋", "Nagoya", 35.1815, 136.9066, "JP"),
        RegionInfo("jp-fukuoka", "福岡", "Fukuoka", 33.5904, 130.4017, "JP"),
        RegionInfo("jp-sapporo", "札幌", "Sapporo", 43.0618, 141.3545, "JP"),

        // Asia
        RegionInfo("sg", "シンガポール", "Singapore", 1.3521, 103.8198, "SG"),
        RegionInfo("tw", "台湾", "Taiwan", 25.0330, 121.5654, "TW"),
        RegionInfo("kr", "韓国", "South Korea", 37.5665, 126.9780, "KR"),
        RegionInfo("cn-shanghai", "上海", "Shanghai", 31.2304, 121.4737, "CN"),
        RegionInfo("in", "インド", "India", 28.6139, 77.2090, "IN"),

        // North America
        RegionInfo("us-west", "北米西部", "US West", 37.7749, -122.4194, "US"),
        RegionInfo("us-east", "北米東部", "US East", 40.7128, -74.0060, "US"),
        RegionInfo("ca", "カナダ", "Canada", 43.6532, -79.3832, "CA"),

        // Europe
        RegionInfo("eu-west", "西ヨーロッパ", "Western Europe", 48.8566, 2.3522, "EU"),
        RegionInfo("eu-central", "中央ヨーロッパ", "Central Europe", 52.5200, 13.4050, "EU"),
        RegionInfo("eu-north", "北ヨーロッパ", "Northern Europe", 59.3293, 18.0686, "EU"),
        RegionInfo("uk", "イギリス", "UK", 51.5074, -0.1278, "UK"),

        // Others
        RegionInfo("au", "オーストラリア", "Australia", -33.8688, 151.2093, "AU"),
        RegionInfo("br", "ブラジル", "Brazil", -23.5505, -46.6333, "BR"),
        RegionInfo("global", "グローバル", "Global", 0.0, 0.0, "Global")
    )

    fun findNearestRelays(userLat: Double, userLon: Double, count: Int = 5): List<RelayInfoWithDistance> {
        return GPS_RELAY_DATABASE.map { relay ->
            RelayInfoWithDistance(relay, GeohashUtils.calculateDistance(userLat, userLon, relay.lat, relay.lon))
        }.sortedWith(compareBy({ it.info.priority }, { it.distance }))
         .take(count)
    }

    fun generateRelayListByLocation(userLat: Double, userLon: Double): Nip65Config {
        // Find nearest relays
        val nearestRelays = findNearestRelays(userLat, userLon, 15)

        // Select outbox relays (3-5): prefer nearest priority 1 relays
        val outbox = nearestRelays
            .filter { it.info.priority == 1 }
            .take(3)
            .toMutableList()

        // Add more outbox if needed
        if (outbox.size < 3) {
            val additionalOutbox = nearestRelays
                .filter { r -> outbox.none { it.info.url == r.info.url } }
                .take(3 - outbox.size)
            outbox.addAll(additionalOutbox)
        }

        // Select inbox relays (3-4)
        val inbox = nearestRelays
            .filter { it.info.priority == 1 }
            .take(3)
            .toMutableList()

        // Add 1 global relay for international reachability
        val globalRelay = GPS_RELAY_DATABASE
            .filter { it.priority == 1 && it.region == "Global" && inbox.none { i -> i.info.url == it.url } }
            .take(1)
            .map { RelayInfoWithDistance(it, GeohashUtils.calculateDistance(userLat, userLon, it.lat, it.lon)) }
        inbox.addAll(globalRelay)

        // Discovery relays
        val discover = DIRECTORY_RELAYS

        // Combined list for NIP-65 publishing
        val combined = mutableListOf<Nip65Relay>()
        val addedUrls = mutableSetOf<String>()

        // Add nearest relays as both read and write (most important)
        nearestRelays.take(2).forEach { r ->
            if (!addedUrls.contains(r.info.url)) {
                combined.add(Nip65Relay(r.info.url, read = true, write = true))
                addedUrls.add(r.info.url)
            }
        }

        // Add remaining outbox relays (write only if not already added)
        outbox.forEach { r ->
            if (!addedUrls.contains(r.info.url)) {
                combined.add(Nip65Relay(r.info.url, read = false, write = true))
                addedUrls.add(r.info.url)
            }
        }

        // Add remaining inbox relays (read only if not already added)
        inbox.forEach { r ->
            if (!addedUrls.contains(r.info.url)) {
                combined.add(Nip65Relay(r.info.url, read = true, write = false))
                addedUrls.add(r.info.url)
            } else {
                // If already added, ensure read is true
                val existingIndex = combined.indexOfFirst { it.url == r.info.url }
                if (existingIndex != -1) {
                    combined[existingIndex] = combined[existingIndex].copy(read = true)
                }
            }
        }

        return Nip65Config(
            inbox = inbox.take(4),
            outbox = outbox.take(5),
            discover = discover,
            combined = combined
        )
    }

    fun formatDistance(km: Double): String {
        return when {
            km < 1.0 -> "${(km * 1000).roundToInt()}m"
            km < 100.0 -> "%.1fkm".format(km)
            else -> "${km.roundToInt()}km"
        }
    }
}
