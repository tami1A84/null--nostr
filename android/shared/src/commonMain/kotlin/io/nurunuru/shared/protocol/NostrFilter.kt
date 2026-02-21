package io.nurunuru.shared.protocol

import kotlinx.serialization.json.*

/**
 * NIP-01 subscription filter.
 * Mirrors the web lib/connection-manager.js filter shape.
 */
data class NostrFilter(
    val ids: List<String>? = null,
    val authors: List<String>? = null,
    val kinds: List<Int>? = null,
    /** Tag filters: key = tag letter (e.g. "e", "p"), value = list of values. */
    val tags: Map<String, List<String>>? = null,
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
    /** NIP-50 full-text search. */
    val search: String? = null
) {
    /** Serialize to NIP-01 JSON object for wire transmission. */
    fun toJsonObject(): JsonObject = buildJsonObject {
        ids?.let { put("ids", JsonArray(it.map(::JsonPrimitive))) }
        authors?.let { put("authors", JsonArray(it.map(::JsonPrimitive))) }
        kinds?.let { put("kinds", JsonArray(it.map(::JsonPrimitive))) }
        tags?.forEach { (tag, values) ->
            put("#$tag", JsonArray(values.map(::JsonPrimitive)))
        }
        since?.let { put("since", JsonPrimitive(it)) }
        until?.let { put("until", JsonPrimitive(it)) }
        limit?.let { put("limit", JsonPrimitive(it)) }
        search?.let { put("search", JsonPrimitive(it)) }
    }
}
