package io.nurunuru.app.data.cache

import android.content.Context
import android.content.SharedPreferences
import io.nurunuru.app.data.Constants
import io.nurunuru.app.data.models.UserProfile
import kotlinx.serialization.Serializable
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

@Serializable
private data class CacheEntry(val data: String, val expiry: Long)

class NostrCache(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("nurunuru_cache", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val prefix = "nuru_c_"

    // Hot caches (in-memory)
    private val profileCache = LRUCache<String, UserProfile>(Constants.CacheMaxEntries.PROFILES)
    private val timelineCache = LRUCache<String, String>(Constants.CacheMaxEntries.TIMELINE)

    // ── 可変TTL・有効フラグ（AppPreferences から applySettings() で設定）────────

    var profileTtl:      Long    = Constants.CacheDuration.PROFILE
    var profileEnabled:  Boolean = true

    var timelineTtl:     Long    = 30L * Constants.Time.MS_DAY   // 30日
    var timelineEnabled: Boolean = true

    var followListTtl:   Long    = Constants.CacheDuration.FOLLOW_LIST
    var followListEnabled: Boolean = true

    var muteListTtl:     Long    = Constants.CacheDuration.MUTE_LIST
    var muteListEnabled: Boolean = true

    var notificationTtl:     Long    = Constants.CacheDuration.NOTIFICATION
    var notificationEnabled: Boolean = true

    var emojiTtl:     Long    = Constants.CacheDuration.EMOJI
    var emojiEnabled: Boolean = true

    var relayInfoTtl:     Long    = Constants.CacheDuration.NOTIFICATION
    var relayInfoEnabled: Boolean = true

    var badgeTtl:     Long    = Constants.CacheDuration.NOTIFICATION
    var badgeEnabled: Boolean = true

    var mlsGroupsTtl:     Long    = 30L * 24 * 60 * 60 * 1000  // 30日
    var mlsMessagesTtl:   Long    = 30L * 24 * 60 * 60 * 1000  // 30日

    fun applySettings(appPrefs: io.nurunuru.app.data.prefs.AppPreferences) {
        val day = Constants.CacheDuration.NOTIFICATION  // 1日 = 86_400_000
        profileTtl      = appPrefs.getCacheTtlMs("profile",      day)
        profileEnabled  = appPrefs.isCacheEnabled("profile")
        timelineTtl     = appPrefs.getCacheTtlMs("timeline",     day)
        timelineEnabled = appPrefs.isCacheEnabled("timeline")
        followListTtl      = appPrefs.getCacheTtlMs("followlist",   day)
        followListEnabled  = appPrefs.isCacheEnabled("followlist")
        muteListTtl      = appPrefs.getCacheTtlMs("mutelist",    day)
        muteListEnabled  = appPrefs.isCacheEnabled("mutelist")
        notificationTtl     = appPrefs.getCacheTtlMs("notification", day)
        notificationEnabled = appPrefs.isCacheEnabled("notification")
        emojiTtl      = appPrefs.getCacheTtlMs("emoji",       day)
        emojiEnabled  = appPrefs.isCacheEnabled("emoji")
        relayInfoTtl     = appPrefs.getCacheTtlMs("relay",       day)
        relayInfoEnabled = appPrefs.isCacheEnabled("relay")
        badgeTtl     = appPrefs.getCacheTtlMs("badge",       day)
        badgeEnabled = appPrefs.isCacheEnabled("badge")
        mlsGroupsTtl   = appPrefs.getCacheTtlMs("mls_groups",   30L * day)
        mlsMessagesTtl = appPrefs.getCacheTtlMs("mls_messages", 30L * day)
    }

    // ─── Generic localStorage-like Operations ────────────────────────────────

    private fun getRaw(key: String): String? {
        val raw = prefs.getString(prefix + key, null) ?: return null
        return try {
            val entry = json.decodeFromString<CacheEntry>(raw)
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
        prefs.edit().putString(prefix + key, json.encodeToString(entry)).apply()
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
        if (!profileEnabled) return
        try {
            setRaw("profile_$pubkey", json.encodeToString(profile), profileTtl)
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
        if (!followListEnabled) return
        try {
            setRaw("followlist_$pubkey", json.encodeToString(followList), followListTtl)
        } catch (_: Exception) { }
    }

    // ─── Mute List Cache ─────────────────────────────────────────────────────

    fun getCachedMuteList(pubkey: String): String? = getRaw("mutelist_$pubkey")

    fun setCachedMuteList(pubkey: String, muteListJson: String) {
        if (!muteListEnabled) return
        setRaw("mutelist_$pubkey", muteListJson, muteListTtl)
    }

    // ─── Emoji Cache ─────────────────────────────────────────────────────────

    fun getCachedEmoji(pubkey: String): String? = getRaw("emoji_$pubkey")

    fun setCachedEmoji(pubkey: String, emojiDataJson: String) {
        if (!emojiEnabled) return
        setRaw("emoji_$pubkey", emojiDataJson, emojiTtl)
    }

    fun clearCachedEmoji(pubkey: String) { removeRaw("emoji_$pubkey") }

    // ─── Badge Cache (1 hour) ────────────────────────────────────────────────

    fun getCachedBadgeInfo(pubkey: String): String? = getRaw("badge_info_$pubkey")

    fun setCachedBadgeInfo(pubkey: String, dataJson: String) {
        if (!badgeEnabled) return
        setRaw("badge_info_$pubkey", dataJson, badgeTtl)
    }

    fun getCachedAwardedBadges(pubkey: String): String? = getRaw("badge_awarded_$pubkey")

    fun setCachedAwardedBadges(pubkey: String, dataJson: String) {
        if (!badgeEnabled) return
        setRaw("badge_awarded_$pubkey", dataJson, badgeTtl)
    }

    // ─── User Notes / Likes Cache ────────────────────────────────────────────

    fun getCachedUserNotes(pubkey: String): String? = getRaw("user_notes_$pubkey")

    fun setCachedUserNotes(pubkey: String, json: String) {
        setRaw("user_notes_$pubkey", json, Constants.CacheDuration.NOTIFICATION) // 1 day
    }

    fun removeFromUserNotesCache(pubkey: String, eventId: String, serializer: kotlinx.serialization.json.Json) {
        val raw = getCachedUserNotes(pubkey) ?: return
        try {
            val posts = serializer.decodeFromString<List<io.nurunuru.app.data.models.ScoredPost>>(raw)
            val updated = posts.filter { it.event.id != eventId }
            if (updated.size != posts.size) {
                setCachedUserNotes(pubkey, serializer.encodeToString(updated))
            }
        } catch (_: Exception) { }
    }

    fun getCachedUserLikes(pubkey: String): String? = getRaw("user_likes_$pubkey")

    fun setCachedUserLikes(pubkey: String, json: String) {
        setRaw("user_likes_$pubkey", json, Constants.CacheDuration.NOTIFICATION) // 1 day
    }

    fun removeFromUserLikesCache(pubkey: String, eventId: String, serializer: kotlinx.serialization.json.Json) {
        val raw = getCachedUserLikes(pubkey) ?: return
        try {
            val posts = serializer.decodeFromString<List<io.nurunuru.app.data.models.ScoredPost>>(raw)
            val updated = posts.filter { it.event.id != eventId }
            if (updated.size != posts.size) {
                setCachedUserLikes(pubkey, serializer.encodeToString(updated))
            }
        } catch (_: Exception) { }
    }

    // ─── Timeline Cache ──────────────────────────────────────────────────────

    fun getCachedTimeline(): String? {
        timelineCache.get("events")?.let { return it }
        // TTL チェックをスキップして生データを読む。
        // タイムラインキャッシュは「フレッシュなデータが届くまでの表示用」なので
        // 期限切れでも古いポストを見せることに問題はなく、
        // fetchFollowTimeline() が成功するたびに常に上書きされる。
        val raw = prefs.getString(prefix + "timeline_events", null) ?: return null
        return try {
            json.decodeFromString<CacheEntry>(raw).data
        } catch (_: Exception) { null }
    }

    fun setCachedTimeline(eventsJson: String) {
        timelineCache.set("events", eventsJson)
        if (!timelineEnabled) return
        setRaw("timeline_events", eventsJson, timelineTtl)
    }

    // ─── Notification Cache (1 day) ──────────────────────────────────────────

    fun getCachedNotifications(pubkey: String): String? = getRaw("notifications_$pubkey")

    fun setCachedNotifications(pubkey: String, json: String) {
        if (!notificationEnabled) return
        setRaw("notifications_$pubkey", json, notificationTtl)
    }

    // ─── Relay List Cache (NIP-65) ───────────────────────────────────────────

    fun getCachedRelayList(pubkey: String): String? = getRaw("relaylist_$pubkey")

    fun setCachedRelayList(pubkey: String, relayListJson: String) {
        if (!relayInfoEnabled) return
        setRaw("relaylist_$pubkey", relayListJson, relayInfoTtl)
    }

    // ─── MLS / Talk Cache ────────────────────────────────────────────────────
    // TTL チェックをスキップ（タイムライン方式）: 期限切れでも古いデータを見せ、
    // fetchMlsGroups / fetchMlsMessages が成功するたびに上書きする。

    fun getCachedMlsGroups(pubkey: String): String? {
        val raw = prefs.getString(prefix + "mls_groups_$pubkey", null) ?: return null
        return try { json.decodeFromString<CacheEntry>(raw).data } catch (_: Exception) { null }
    }

    fun setCachedMlsGroups(pubkey: String, data: String) {
        setRaw("mls_groups_$pubkey", data, mlsGroupsTtl)
    }

    fun getCachedMlsMessages(groupId: String): String? {
        val raw = prefs.getString(prefix + "mls_msgs_$groupId", null) ?: return null
        return try { json.decodeFromString<CacheEntry>(raw).data } catch (_: Exception) { null }
    }

    fun setCachedMlsMessages(groupId: String, data: String) {
        setRaw("mls_msgs_$groupId", data, mlsMessagesTtl)
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
                        val entry = json.decodeFromString<CacheEntry>(raw)
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

    // ── 削除済みイベント ID（アプリ再起動後もリレーフェッチから除外するため永続） ──────
    // NIP-09 の削除イベントはリレーが即座に反映しないため、ローカルで追跡する。
    // 最大 500 件。超えた場合は古いものを削除しない（SetはLRUではないが実用上問題なし）。
    private val DELETED_IDS_KEY = "deleted_event_ids"

    fun addDeletedEventId(eventId: String) {
        val current = prefs.getStringSet(DELETED_IDS_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(eventId)
        // 上限を超えたら古めのものを間引く（SetはLRUを保証しないが近似的に機能する）
        val trimmed = if (current.size > 500) current.drop(current.size - 500).toMutableSet() else current
        prefs.edit().putStringSet(DELETED_IDS_KEY, trimmed).commit()
    }

    fun getDeletedEventIds(): Set<String> =
        prefs.getStringSet(DELETED_IDS_KEY, emptySet()) ?: emptySet()

    // ── 退出済みグループ ブロックリスト（MLS キャッシュとは独立して永続） ────────

    fun markGroupAsLeft(groupIdHex: String) {
        val current = prefs.getStringSet("mls_left_groups", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(groupIdHex)
        // commit() instead of apply() — synchronous write so getLeftGroupIds() immediately reflects
        // the leave when fetchMlsGroups() is called right after leaveGroup() in the same coroutine.
        prefs.edit().putStringSet("mls_left_groups", current).commit()
    }

    fun getLeftGroupIds(): Set<String> =
        prefs.getStringSet("mls_left_groups", emptySet()) ?: emptySet()

    fun removeGroupFromCache(pubkey: String, groupIdHex: String, json: kotlinx.serialization.json.Json) {
        val raw = getCachedMlsGroups(pubkey) ?: return
        try {
            val groups = json.decodeFromString<List<io.nurunuru.app.data.models.MlsGroup>>(raw)
            val updated = groups.filter { it.groupIdHex != groupIdHex }
            setCachedMlsGroups(pubkey, json.encodeToString(updated))
        } catch (_: Exception) { }
        removeRaw("mls_msgs_$groupIdHex")
    }

    fun clearByType(typeId: String) {
        when (typeId) {
            "profile"      -> { profileCache.clear(); clearByPrefix("profile_") }
            "timeline"     -> { timelineCache.clear(); clearByPrefix("timeline_") }
            "followlist"   -> clearByPrefix("followlist_")
            "mutelist"     -> clearByPrefix("mutelist_")
            "notification" -> clearByPrefix("notifications_")
            "emoji"        -> clearByPrefix("emoji_")
            "relay"        -> clearByPrefix("relaylist_")
            "badge"        -> clearByPrefix("badge_")
            "mls_groups"   -> clearByPrefix("mls_groups_")
            "mls_messages" -> clearByPrefix("mls_msgs_")
        }
    }

    fun clearAll() {
        profileCache.clear()
        timelineCache.clear()
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }

    fun getEntriesCount(typeId: String): Int {
        val sub = when (typeId) {
            "profile"      -> "profile_"
            "timeline"     -> "timeline_"
            "followlist"   -> "followlist_"
            "mutelist"     -> "mutelist_"
            "notification" -> "notifications_"
            "emoji"        -> "emoji_"
            "relay"        -> "relaylist_"
            "badge"        -> "badge_"
            "mls_groups"   -> "mls_groups_"
            "mls_messages" -> "mls_msgs_"
            else           -> return 0
        }
        return prefs.all.keys.count { it.startsWith(prefix + sub) }
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
