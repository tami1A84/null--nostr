package io.nurunuru.app.data

import io.nurunuru.app.data.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

// ─── Reactions / Lightning / Birdwatch ────────────────────────────────────────

suspend fun NostrRepository.fetchReactions(eventIds: List<String>): Map<String, Int> {
    if (eventIds.isEmpty()) return emptyMap()
    val filter = NostrClient.Filter(
        kinds = listOf(NostrKind.REACTION),
        tags = mapOf("e" to eventIds),
        limit = 500
    )
    val events = client.fetchEvents(filter, timeoutMs = 3_000)
    return events
        .groupBy { it.getTagValue("e") ?: "" }
        .mapValues { it.value.size }
        .filterKeys { it.isNotEmpty() }
}

suspend fun NostrRepository.fetchLightningInvoice(lud16: String, amountSats: Long, comment: String = ""): String? {
    if (!lud16.contains("@")) return null
    val (name, domain) = lud16.split("@")
    val lnurlUrl = "https://$domain/.well-known/lnurlp/$name"

    return withContext(Dispatchers.IO) {
        try {
            val metaRequest = okhttp3.Request.Builder().url(lnurlUrl).build()
            val metaResponse = httpClient.newCall(metaRequest).execute()
            if (!metaResponse.isSuccessful) return@withContext null

            val metaBody = metaResponse.body?.string() ?: return@withContext null
            val meta = json.parseToJsonElement(metaBody).jsonObject

            val minSendable = meta["minSendable"]?.jsonPrimitive?.long ?: 0L
            val maxSendable = meta["maxSendable"]?.jsonPrimitive?.long ?: 1000000000L
            val amountMsats = amountSats * 1000

            if (amountMsats < minSendable || amountMsats > maxSendable) return@withContext null

            val callback = meta["callback"]?.jsonPrimitive?.content ?: return@withContext null
            var callbackUrl = "$callback?amount=$amountMsats"

            val commentAllowed = meta["commentAllowed"]?.jsonPrimitive?.int ?: 0
            if (comment.isNotBlank() && comment.length <= commentAllowed) {
                callbackUrl += "&comment=${java.net.URLEncoder.encode(comment, "UTF-8")}"
            }

            val invRequest = okhttp3.Request.Builder().url(callbackUrl).build()
            val invResponse = httpClient.newCall(invRequest).execute()
            if (!invResponse.isSuccessful) return@withContext null

            val invBody = invResponse.body?.string() ?: return@withContext null
            val invData = json.parseToJsonElement(invBody).jsonObject

            if (invData["status"]?.jsonPrimitive?.content == "ERROR") return@withContext null

            invData["pr"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }
}

suspend fun NostrRepository.fetchBirdwatchNotes(eventIds: List<String>): Map<String, List<NostrEvent>> {
    if (eventIds.isEmpty()) return emptyMap()
    val filter = NostrClient.Filter(
        kinds = listOf(NostrKind.LABEL),
        tags = mapOf(
            "e" to eventIds,
            "L" to listOf("birdwatch")
        ),
        limit = 200
    )
    val events = client.fetchEvents(filter, 3_000)
    val map = mutableMapOf<String, MutableList<NostrEvent>>()
    for (event in events) {
        if (event.tags.none { it.getOrNull(0) == "L" && it.getOrNull(1) == "birdwatch" }) continue
        val targetId = event.tags.firstOrNull { it.getOrNull(0) == "e" && it.getOrNull(1) in eventIds }?.getOrNull(1) ?: continue
        map.getOrPut(targetId) { mutableListOf() }.add(event)
    }
    return map
}
