package io.nurunuru.app.data.models

import kotlinx.serialization.Serializable

const val NEWS_CATEGORY_NAMESPACE = "null.news.category"

@Serializable
data class NewsSource(
    val pubkey: String,
    val displayName: String? = null,
    val picture: String? = null,
    val nip05: String? = null,
    val addedAt: Long = System.currentTimeMillis() / 1000
)

data class NewsArticle(
    val id: String,
    val address: String,
    val pubkey: String,
    val dTag: String,
    val title: String,
    val summary: String,
    val imageUrl: String?,
    val content: String,
    val publishedAt: Long,
    val createdAt: Long,
    val sourceName: String,
    val sourcePicture: String?,
    val categories: List<String>,
    val rawEvent: NostrEvent
)

enum class NewsCategory(val label: String, val key: String?) {
    TOP("トップ", null),
    DOMESTIC("国内", "domestic"),
    ENTERTAINMENT("エンタメ", "entertainment"),
    SPORTS("スポーツ", "sports"),
    ECONOMY("経済", "economy"),
    TECH("テック", "tech"),
    NOSTR("Nostr", "nostr")
}
