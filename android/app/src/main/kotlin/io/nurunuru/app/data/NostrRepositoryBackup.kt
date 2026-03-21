package io.nurunuru.app.data

import io.nurunuru.app.data.models.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// ─── Backup / Import / Profile / Relays ──────────────────────────────────────

suspend fun NostrRepository.fetchAllUserEvents(
    pubkey: String,
    onProgress: (fetched: Int, batch: Int) -> Unit
): List<NostrEvent> {
    val allEvents = mutableListOf<NostrEvent>()
    val seenIds = mutableSetOf<String>()
    val batchSize = 500
    var lastTimestamp = System.currentTimeMillis() / 1000
    var hasMore = true
    var batchCount = 0

    while (hasMore) {
        val filter = NostrClient.Filter(
            authors = listOf(pubkey),
            until = lastTimestamp,
            limit = batchSize
        )

        try {
            val events = client.fetchEvents(filter, timeoutMs = 10_000)

            if (events.isEmpty()) {
                hasMore = false
                break
            }

            var newEventsInBatch = 0
            for (event in events) {
                if (!seenIds.contains(event.id)) {
                    seenIds.add(event.id)
                    allEvents.add(event)
                    newEventsInBatch++
                }
            }

            val minTimestamp = events.minOf { it.createdAt }
            if (minTimestamp >= lastTimestamp) {
                hasMore = false
            } else {
                lastTimestamp = minTimestamp - 1
            }

            batchCount++
            onProgress(allEvents.size, batchCount)

            if (newEventsInBatch == 0) {
                hasMore = false
            }
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "Batch fetch failed", e)
            hasMore = false
        }
    }

    return allEvents.sortedBy { it.createdAt }
}

suspend fun NostrRepository.importEventsToRelays(
    events: List<NostrEvent>,
    onProgress: (current: Int, total: Int, success: Int, failed: Int) -> Unit
): ImportResult {
    var success = 0
    var failed = 0
    var skipped = 0
    val myPubkey = prefs.publicKeyHex

    events.forEachIndexed { index, event ->
        if (event.isProtected() && event.pubkey != myPubkey) {
            skipped++
        } else {
            val published = client.publishEvent(event)
            if (published) success++ else failed++
        }
        onProgress(index + 1, events.size, success, failed)
        delay(50)
    }

    return ImportResult(events.size, success, failed, skipped)
}

suspend fun NostrRepository.uploadImage(fileBytes: ByteArray, mimeType: String): String? {
    val server = prefs.uploadServer
    val normalizedServer = server.removePrefix("https://").removePrefix("http://").removeSuffix("/")

    return when {
        normalizedServer == "nostr.build" -> ImageUploadUtils.uploadToNostrBuild(fileBytes, mimeType, client.getSigner())
        normalizedServer == "share.yabu.me" -> ImageUploadUtils.uploadToYabuMe(fileBytes, mimeType, client.getSigner(), server)
        else -> ImageUploadUtils.uploadToBlossom(fileBytes, mimeType, client.getSigner(), server)
    }
}

suspend fun NostrRepository.updateRelayList(relays: List<Triple<String, Boolean, Boolean>>): Boolean {
    val tags = relays.map { (url, read, write) ->
        val marker = when { read && write -> null; read -> "read"; write -> "write"; else -> null }
        if (marker != null) listOf("r", url, marker) else listOf("r", url)
    }
    return publishNewEvent(10002, "", tags) != null
}

suspend fun NostrRepository.requestVanish(relays: List<String>?, reason: String): Boolean {
    val tags = mutableListOf<List<String>>()
    if (relays.isNullOrEmpty()) tags.add(listOf("relay", "ALL_RELAYS"))
    else relays.forEach { tags.add(listOf("relay", it)) }
    return publishNewEvent(NostrKind.VANISH_REQUEST, reason, tags) != null
}

suspend fun NostrRepository.updateProfile(profile: UserProfile): Boolean {
    val obj = buildJsonObject {
        fun put(key: String, value: String?) {
            if (!value.isNullOrBlank()) put(key, JsonPrimitive(value))
        }
        put("name", profile.name)
        put("display_name", profile.displayName ?: profile.name)
        put("about", profile.about)
        put("picture", profile.picture)
        put("banner", profile.banner)
        put("nip05", profile.nip05)
        put("lud16", profile.lud16)
        put("website", profile.website)
        put("birthday", profile.birthday)
        put("geohash", profile.geohash)
    }
    val metadataJson = obj.toString()
    return publishNewEvent(0, metadataJson, emptyList()) != null
}
