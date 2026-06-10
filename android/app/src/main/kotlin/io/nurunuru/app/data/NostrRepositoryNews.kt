package io.nurunuru.app.data

import io.nurunuru.app.data.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val NEWS_LIMIT = 80

suspend fun NostrRepository.fetchNewsArticles(
    sourcePubkeys: List<String> = emptyList(),
    limit: Int = NEWS_LIMIT
): List<NewsArticle> = withContext(Dispatchers.IO) {
    val filter = NostrClient.Filter(
        kinds = listOf(NostrKind.LONG_FORM),
        authors = sourcePubkeys.distinct().takeIf { it.isNotEmpty() },
        limit = limit
    )
    val relayCandidates = (
        prefs.nip65Relays.map { it.url } +
        prefs.relays.toList() +
        DEFAULT_RELAYS +
        listOf(NostrClient.SEARCH_RELAY)
    ).distinct()
    val directEvents = client.fetchEvents(filter, timeoutMs = 6_000)
    val relayEvents = if (relayCandidates.isNotEmpty()) {
        client.fetchEventsFrom(relayCandidates, filter, timeoutMs = 6_000)
    } else emptyList()
    val events = (directEvents + relayEvents)
        .distinctBy { it.id }
        .filter { it.kind == NostrKind.LONG_FORM }

    val latestByAddress = linkedMapOf<String, NostrEvent>()
    for (event in events) {
        val d = event.getTagValue("d") ?: event.id
        val address = "${NostrKind.LONG_FORM}:${event.pubkey}:$d"
        val current = latestByAddress[address]
        if (current == null || event.createdAt > current.createdAt) latestByAddress[address] = event
    }

    val profiles = fetchProfiles(latestByAddress.values.map { it.pubkey }.distinct())
    latestByAddress.values.map { event ->
        val profile = profiles[event.pubkey] ?: getCachedProfile(event.pubkey)
        event.toNewsArticle(profile)
    }.sortedByDescending { it.publishedAt }
}

private fun NostrEvent.toNewsArticle(profile: UserProfile?): NewsArticle {
    val d = getTagValue("d") ?: id
    val title = getTagValue("title")?.takeIf { it.isNotBlank() }
        ?: content.lineSequence().firstOrNull()?.take(80)?.takeIf { it.isNotBlank() }
        ?: "無題の記事"
    val summary = getTagValue("summary")?.takeIf { it.isNotBlank() }
        ?: content.replace(Regex("\\s+"), " ").take(160)
    val published = getTagValue("published_at")?.toLongOrNull() ?: createdAt
    val categories = extractNewsCategories()
    return NewsArticle(
        id = id,
        address = "${NostrKind.LONG_FORM}:$pubkey:$d",
        pubkey = pubkey,
        dTag = d,
        title = title,
        summary = summary,
        imageUrl = getTagValue("image"),
        content = content,
        publishedAt = published,
        createdAt = createdAt,
        sourceName = profile?.displayedName?.takeIf { it.isNotBlank() }
            ?: NostrKeyUtils.shortenPubkey(pubkey),
        sourcePicture = profile?.picture,
        categories = categories,
        rawEvent = this
    )
}

private fun NostrEvent.extractNewsCategories(): List<String> {
    val fromLabels = tags.filter { tag ->
        tag.size >= 3 && tag[0] == "l" && tag[2] == NEWS_CATEGORY_NAMESPACE
    }.mapNotNull { it.getOrNull(1)?.lowercase() }

    val fromHashtags = getTagValues("t").map { it.lowercase() }
    val normalized = (fromLabels + fromHashtags).mapNotNull { raw ->
        when (raw) {
            "top", "news" -> "top"
            "domestic", "japan", "jp", "国内" -> "domestic"
            "entertainment", "entame", "エンタメ" -> "entertainment"
            "sports", "sport", "スポーツ" -> "sports"
            "economy", "business", "経済" -> "economy"
            "tech", "technology", "テック" -> "tech"
            "nostr" -> "nostr"
            else -> null
        }
    }.distinct()
    return normalized.ifEmpty { listOf("top") }
}
