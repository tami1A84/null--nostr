package io.nurunuru.app.data.cache

import android.content.Context
import android.content.SharedPreferences
import io.nurunuru.app.data.Constants
import io.nurunuru.app.data.models.UserProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Two-layer caching: in-memory LRU + SharedPreferences persistence.
 * Synced with web version: lib/cache.js
 */

class LRUCache<K, V>(private val maxSize: Int) {
    private val cache = LinkedHashMap<K, V>(maxSize, 0.75f, true)

    @Synchronized
    fun get(key: K): V? = cache[key]

    @Synchronized
    fun set(key: K, value: V) {
        if (cache.size >= maxSize && !cache.containsKey(key)) {
            val firstKey = cache.keys.first()
            cache.remove(firstKey)
        }
        cache[key] = value
    }

    @Synchronized
    fun has(key: K): Boolean = cache.containsKey(key)

    @Synchronized
    fun delete(key: K) { cache.remove(key) }

    @Synchronized
    fun clear() { cache.clear() }

    val size: Int @Synchronized get() = cache.size

    @Synchronized
    fun keys(): Set<K> = cache.keys.toSet()
}

private data class CacheEntry(val data: String, val expiry: Long)

class NostrCache(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("nurunuru_cache", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val prefix = "nuru_c_"

    // Hot caches (in-memory)
    private val profileCache = LRUCache<String, UserProfile>(Constants.CacheMaxEntries.PROFILES)
    private val timelineCache = LRUCache<String, String>(Constants.CacheMaxEntries.TIMELINE)

    // ─── Generic localStorage-like Operations ────────────────────────────────

    private fun getRaw(key: String): String? {
        val raw = prefs.getString(prefix + key, null) ?: return null
        return try {
            val entry = json.decodeFromString<CacheEntry>(CacheEntry.serializer(), raw)
            if (System.currentTimeMillis() > entry.expiry) {
                prefs.edit().remove(prefix + key).apply()
                null
            } else entry.data
        } catch (_: Exception) {
            prefs.edit().remove(prefix + key).apply()
            null
        }
    }

    private fun setRaw(key: String, data: String, durationMs: Long) {
        val entry = CacheEntry(data, System.currentTimeMillis() + durationMs)
        prefs.edit().putString(prefix + key, json.encodeToString(CacheEntry.serializer(), entry)).apply()
    }

    private fun removeRaw(key: String) {
        prefs.edit().remove(prefix + key).apply()
    }

    // ─── Profile Cache ───────────────────────────────────────────────────────

    fun getCachedProfile(pubkey: String): UserProfile? {
        profileCache.get(pubkey)?.let { return it }
        val stored = getRaw("profile_$pubkey") ?: return null
        return try {
            val profile = json.decodeFromString<UserProfile>(stored)
            profileCache.set(pubkey, profile)
            profile
        } catch (_: Exception) { null }
    }

    fun setCachedProfile(pubkey: String, profile: UserProfile) {
        profileCache.set(pubkey, profile)
        try {
            setRaw("profile_$pubkey", json.encodeToString(profile), Constants.CacheDuration.PROFILE)
        } catch (_: Exception) { }
    }

    fun clearProfileCache(pubkey: String? = null) {
        if (pubkey != null) {
            profileCache.delete(pubkey)
            removeRaw("profile_$pubkey")
        } else {
            profileCache.clear()
            clearByPrefix("profile_")
        }
    }

    // ─── Follow List Cache ───────────────────────────────────────────────────

    fun getCachedFollowList(pubkey: String): List<String>? {
        val stored = getRaw("followlist_$pubkey") ?: return null
        return try { json.decodeFromString(stored) } catch (_: Exception) { null }
    }

    fun setCachedFollowList(pubkey: String, followList: List<String>) {
        try {
            setRaw("followlist_$pubkey", json.encodeToString(followList), Constants.CacheDuration.FOLLOW_LIST)
        } catch (_: Exception) { }
    }

    // ─── Mute List Cache ─────────────────────────────────────────────────────

    fun getCachedMuteList(pubkey: String): String? = getRaw("mutelist_$pubkey")

    fun setCachedMuteList(pubkey: String, muteListJson: String) {
        setRaw("mutelist_$pubkey", muteListJson, Constants.CacheDuration.MUTE_LIST)
    }

    // ─── Emoji Cache ─────────────────────────────────────────────────────────

    fun getCachedEmoji(pubkey: String): String? = getRaw("emoji_$pubkey")

    fun setCachedEmoji(pubkey: String, emojiDataJson: String) {
        setRaw("emoji_$pubkey", emojiDataJson, Constants.CacheDuration.EMOJI)
    }

    fun clearCachedEmoji(pubkey: String) { removeRaw("emoji_$pubkey") }

    // ─── Timeline Cache ──────────────────────────────────────────────────────

    fun getCachedTimeline(): String? {
        timelineCache.get("events")?.let { return it }
        return getRaw("timeline_events")
    }

    fun setCachedTimeline(eventsJson: String) {
        timelineCache.set("events", eventsJson)
        setRaw("timeline_events", eventsJson, Constants.CacheDuration.TIMELINE)
    }

    // ─── Relay List Cache (NIP-65) ───────────────────────────────────────────

    fun getCachedRelayList(pubkey: String): String? = getRaw("relaylist_$pubkey")

    fun setCachedRelayList(pubkey: String, relayListJson: String) {
        setRaw("relaylist_$pubkey", relayListJson, Constants.CacheDuration.RELAY_INFO)
    }

    // ─── Cleanup ─────────────────────────────────────────────────────────────

    fun clearExpiredCache(): Int {
        val now = System.currentTimeMillis()
        val keysToRemove = mutableListOf<String>()
        prefs.all.forEach { (key, _) ->
            if (key.startsWith(prefix)) {
                val raw = prefs.getString(key, null)
                if (raw != null) {
                    try {
                        val entry = json.decodeFromString<CacheEntry>(CacheEntry.serializer(), raw)
                        if (now > entry.expiry) keysToRemove.add(key)
                    } catch (_: Exception) {
                        keysToRemove.add(key)
                    }
                }
            }
        }
        val editor = prefs.edit()
        keysToRemove.forEach { editor.remove(it) }
        editor.apply()
        return keysToRemove.size
    }

    private fun clearByPrefix(subPrefix: String) {
        val fullPrefix = prefix + subPrefix
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(fullPrefix) }.forEach { editor.remove(it) }
        editor.apply()
    }

    data class CacheStats(
        val entryCount: Int,
        val memoryProfileCount: Int,
        val memoryTimelineCount: Int
    )

    fun getCacheStats(): CacheStats = CacheStats(
        entryCount = prefs.all.count { it.key.startsWith(prefix) },
        memoryProfileCount = profileCache.size,
        memoryTimelineCount = timelineCache.size
    )
}
