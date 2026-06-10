package io.nurunuru.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.nurunuru.app.data.models.DEFAULT_RELAYS
import io.nurunuru.app.data.models.Nip65Relay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AppPreferences(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    // 機密データ専用（秘密鍵、公開鍵、外部署名フラグ、APIキー）
    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "nurunuru_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // 非機密データ（設定値、外部アプリリスト等）— 更新時も消えない
    private val plainPrefs: SharedPreferences =
        context.getSharedPreferences("nurunuru_prefs", Context.MODE_PRIVATE)

    init {
        migratePlainPrefsIfNeeded()
    }

    /**
     * 旧バージョンで securePrefs に保存されていた非機密データを plainPrefs へ移行する。
     * 移行済みフラグがあればスキップ。
     */
    private fun migratePlainPrefsIfNeeded() {
        if (plainPrefs.getBoolean(KEY_PLAIN_MIGRATED, false)) return
        try {
            val editor = plainPrefs.edit()
            // relays
            val relaySet = securePrefs.getStringSet(KEY_RELAYS, null)
            if (relaySet != null) editor.putStringSet(KEY_RELAYS, relaySet)
            // uploadServer
            val upload = securePrefs.getString(KEY_UPLOAD_SERVER, null)
            if (upload != null) editor.putString(KEY_UPLOAD_SERVER, upload)
            // blossomUrl
            val blossom = securePrefs.getString(KEY_BLOSSOM_URL, null)
            if (blossom != null) editor.putString(KEY_BLOSSOM_URL, blossom)
            // recentSearches
            val searches = securePrefs.getString(KEY_RECENT_SEARCHES, null)
            if (searches != null) editor.putString(KEY_RECENT_SEARCHES, searches)
            // favoriteApps
            val favApps = securePrefs.getString(KEY_FAVORITE_APPS, null)
            if (favApps != null) editor.putString(KEY_FAVORITE_APPS, favApps)
            // externalApps
            val extApps = securePrefs.getString(KEY_EXTERNAL_APPS, null)
            if (extApps != null) editor.putString(KEY_EXTERNAL_APPS, extApps)
            // defaultZapAmount
            if (securePrefs.contains(KEY_DEFAULT_ZAP_AMOUNT))
                editor.putInt(KEY_DEFAULT_ZAP_AMOUNT, securePrefs.getInt(KEY_DEFAULT_ZAP_AMOUNT, 21))
            // autoSignEnabled
            if (securePrefs.contains(KEY_AUTO_SIGN_ENABLED))
                editor.putBoolean(KEY_AUTO_SIGN_ENABLED, securePrefs.getBoolean(KEY_AUTO_SIGN_ENABLED, true))
            // elevenLabsLanguage
            val lang = securePrefs.getString(KEY_ELEVENLABS_LANGUAGE, null)
            if (lang != null) editor.putString(KEY_ELEVENLABS_LANGUAGE, lang)
            // selectedRegionId
            val region = securePrefs.getString(KEY_SELECTED_REGION_ID, null)
            if (region != null) editor.putString(KEY_SELECTED_REGION_ID, region)
            // userGeohash
            val geohash = securePrefs.getString(KEY_USER_GEOHASH, null)
            if (geohash != null) editor.putString(KEY_USER_GEOHASH, geohash)
            // userLat / userLon
            if (securePrefs.contains(KEY_USER_LAT))
                editor.putFloat(KEY_USER_LAT, securePrefs.getFloat(KEY_USER_LAT, 0.0f))
            if (securePrefs.contains(KEY_USER_LON))
                editor.putFloat(KEY_USER_LON, securePrefs.getFloat(KEY_USER_LON, 0.0f))
            // mainRelay
            val mainRelay = securePrefs.getString(KEY_MAIN_RELAY, null)
            if (mainRelay != null) editor.putString(KEY_MAIN_RELAY, mainRelay)
            // nip65Relays
            val nip65 = securePrefs.getString(KEY_NIP65_RELAYS, null)
            if (nip65 != null) editor.putString(KEY_NIP65_RELAYS, nip65)
            // MLS / Marmot relay settings
            val mlsKeyPackageRelays = securePrefs.getString(KEY_MLS_KEY_PACKAGE_RELAYS, null)
            if (mlsKeyPackageRelays != null) editor.putString(KEY_MLS_KEY_PACKAGE_RELAYS, mlsKeyPackageRelays)
            val mlsInboxRelays = securePrefs.getString(KEY_MLS_INBOX_RELAYS, null)
            if (mlsInboxRelays != null) editor.putString(KEY_MLS_INBOX_RELAYS, mlsInboxRelays)

            editor.putBoolean(KEY_PLAIN_MIGRATED, true)
            editor.apply()
            Log.d("AppPreferences", "Migrated non-sensitive prefs to plainPrefs")
        } catch (e: Exception) {
            Log.w("AppPreferences", "Migration failed (non-fatal): ${e.message}")
            plainPrefs.edit().putBoolean(KEY_PLAIN_MIGRATED, true).apply()
        }
    }

    /**
     * @deprecated SecureKeyManager に移行済み。マイグレーション専用。
     * 新規コードでは SecureKeyManager を使うこと。
     */
    @Deprecated("Use SecureKeyManager instead", level = DeprecationLevel.WARNING)
    var privateKeyHex: String?
        get() = securePrefs.getString(KEY_PRIVATE_KEY_HEX, null)
        set(value) {
            if (value == null) securePrefs.edit().remove(KEY_PRIVATE_KEY_HEX).apply()
            else securePrefs.edit().putString(KEY_PRIVATE_KEY_HEX, value).apply()
        }

    /**
     * マイグレーション後に旧秘密鍵データを削除する。
     */
    fun clearPrivateKey() {
        securePrefs.edit().remove(KEY_PRIVATE_KEY_HEX).apply()
    }

    var publicKeyHex: String?
        get() = securePrefs.getString(KEY_PUBLIC_KEY_HEX, null)
        set(value) {
            if (value == null) securePrefs.edit().remove(KEY_PUBLIC_KEY_HEX).apply()
            else securePrefs.edit().putString(KEY_PUBLIC_KEY_HEX, value).apply()
        }

    var relays: Set<String>
        get() = plainPrefs.getStringSet(KEY_RELAYS, DEFAULT_RELAYS.toSet()) ?: DEFAULT_RELAYS.toSet()
        set(value) = plainPrefs.edit().putStringSet(KEY_RELAYS, value).apply()


    /** Public news source pubkeys for the News tab. Non-sensitive user preference. */
    var newsSources: List<String>
        get() {
            val jsonStr = plainPrefs.getString(KEY_NEWS_SOURCES, "[]") ?: "[]"
            return try { Json.decodeFromString<List<String>>(jsonStr) } catch (_: Exception) { emptyList() }
        }
        set(value) = plainPrefs.edit().putString(KEY_NEWS_SOURCES, Json.encodeToString(value.distinct())).apply()

    var uploadServer: String
        get() = plainPrefs.getString(KEY_UPLOAD_SERVER, "nostr.build") ?: "nostr.build"
        set(value) = plainPrefs.edit().putString(KEY_UPLOAD_SERVER, value).apply()

    var blossomUrl: String
        get() = plainPrefs.getString(KEY_BLOSSOM_URL, "https://blossom.nostr.build") ?: "https://blossom.nostr.build"
        set(value) = plainPrefs.edit().putString(KEY_BLOSSOM_URL, value).apply()

    var uploadedImages: List<String>
        get() {
            val jsonStr = plainPrefs.getString(KEY_UPLOADED_IMAGES, "[]") ?: "[]"
            return try { Json.decodeFromString(jsonStr) } catch (_: Exception) { emptyList() }
        }
        set(value) = plainPrefs.edit().putString(KEY_UPLOADED_IMAGES, Json.encodeToString(value)).apply()

    fun addUploadedImage(url: String) {
        val current = uploadedImages.toMutableList()
        if (!current.contains(url)) {
            current.add(0, url) // newest first
            if (current.size > 50) current.removeAt(current.size - 1) // keep max 50
            uploadedImages = current
        }
    }

    var isExternalSigner: Boolean
        get() = securePrefs.getBoolean(KEY_IS_EXTERNAL_SIGNER, false)
        set(value) = securePrefs.edit().putBoolean(KEY_IS_EXTERNAL_SIGNER, value).apply()

    /**
     * Tracks which authentication backend produced the current session.
     * Valid values: "nsec", "nosskey", "amber", "external", or null when logged out.
     * Stored in plainPrefs because it is just metadata — the actual key/credential
     * material lives in SecureKeyManager / NosskeyManager / the external signer.
     */
    var loginMethod: String?
        get() = plainPrefs.getString(KEY_LOGIN_METHOD, null)
        set(value) {
            if (value == null) plainPrefs.edit().remove(KEY_LOGIN_METHOD).apply()
            else plainPrefs.edit().putString(KEY_LOGIN_METHOD, value).apply()
        }

    /** Pending profile-invite referral. Non-secret, stored so app restarts during onboarding keep the invite. */
    var pendingReferralPubkeyHex: String?
        get() = plainPrefs.getString(KEY_PENDING_REFERRAL_PUBKEY_HEX, null)
        set(value) {
            if (value.isNullOrBlank()) plainPrefs.edit().remove(KEY_PENDING_REFERRAL_PUBKEY_HEX).apply()
            else plainPrefs.edit().putString(KEY_PENDING_REFERRAL_PUBKEY_HEX, value.lowercase()).apply()
        }

    var recentSearches: List<String>
        get() {
            val jsonStr = plainPrefs.getString(KEY_RECENT_SEARCHES, "[]") ?: "[]"
            return try {
                Json.decodeFromString(jsonStr)
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val jsonStr = Json.encodeToString(value)
            plainPrefs.edit().putString(KEY_RECENT_SEARCHES, jsonStr).apply()
        }

    var favoriteApps: List<String>
        get() {
            val jsonStr = plainPrefs.getString(KEY_FAVORITE_APPS, "[]") ?: "[]"
            return try {
                Json.decodeFromString(jsonStr)
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val jsonStr = Json.encodeToString(value)
            plainPrefs.edit().putString(KEY_FAVORITE_APPS, jsonStr).apply()
        }

    var selectedRegionId: String?
        get() = plainPrefs.getString(KEY_SELECTED_REGION_ID, null)
        set(value) = plainPrefs.edit().putString(KEY_SELECTED_REGION_ID, value).apply()

    var userGeohash: String?
        get() = plainPrefs.getString(KEY_USER_GEOHASH, null)
        set(value) = plainPrefs.edit().putString(KEY_USER_GEOHASH, value).apply()

    var userLat: Double
        get() = plainPrefs.getFloat(KEY_USER_LAT, 0.0f).toDouble()
        set(value) = plainPrefs.edit().putFloat(KEY_USER_LAT, value.toFloat()).apply()

    var userLon: Double
        get() = plainPrefs.getFloat(KEY_USER_LON, 0.0f).toDouble()
        set(value) = plainPrefs.edit().putFloat(KEY_USER_LON, value.toFloat()).apply()

    /** おすすめタイムラインに使うメインリレー（最寄りリレーリストから選択した1つ）。Web版の nurunuru_default_relay に相当。 */
    var mainRelay: String
        get() = plainPrefs.getString(KEY_MAIN_RELAY, DEFAULT_RELAYS.first()) ?: DEFAULT_RELAYS.first()
        set(value) = plainPrefs.edit().putString(KEY_MAIN_RELAY, value).apply()

    var nip65Relays: List<io.nurunuru.app.data.models.Nip65Relay>
        get() {
            val jsonStr = plainPrefs.getString(KEY_NIP65_RELAYS, "[]") ?: "[]"
            return try {
                Json.decodeFromString(jsonStr)
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val jsonStr = Json.encodeToString(value)
            plainPrefs.edit().putString(KEY_NIP65_RELAYS, jsonStr).apply()
        }

    /** Marmot/WhiteNoise MLS KeyPackage discovery relays (kind:10051 + KeyPackage publish targets). */
    var mlsKeyPackageRelays: List<String>
        get() {
            val jsonStr = plainPrefs.getString(KEY_MLS_KEY_PACKAGE_RELAYS, "[]") ?: "[]"
            return try { Json.decodeFromString(jsonStr) } catch (_: Exception) { emptyList() }
        }
        set(value) {
            plainPrefs.edit().putString(KEY_MLS_KEY_PACKAGE_RELAYS, Json.encodeToString(value)).apply()
        }

    /** Marmot/NIP-17 inbox relays (kind:10050) where peers should send Welcomes/MLS wrappers. */
    var mlsInboxRelays: List<String>
        get() {
            val jsonStr = plainPrefs.getString(KEY_MLS_INBOX_RELAYS, "[]") ?: "[]"
            return try { Json.decodeFromString(jsonStr) } catch (_: Exception) { emptyList() }
        }
        set(value) {
            plainPrefs.edit().putString(KEY_MLS_INBOX_RELAYS, Json.encodeToString(value)).apply()
        }

    var externalApps: String
        get() = plainPrefs.getString(KEY_EXTERNAL_APPS, "[]") ?: "[]"
        set(value) = plainPrefs.edit().putString(KEY_EXTERNAL_APPS, value).apply()

    var defaultZapAmount: Int
        get() = plainPrefs.getInt(KEY_DEFAULT_ZAP_AMOUNT, 21)
        set(value) = plainPrefs.edit().putInt(KEY_DEFAULT_ZAP_AMOUNT, value).apply()

    var autoSignEnabled: Boolean
        get() = plainPrefs.getBoolean(KEY_AUTO_SIGN_ENABLED, false)
        set(value) = plainPrefs.edit().putBoolean(KEY_AUTO_SIGN_ENABLED, value).apply()

    // ElevenLabs APIキーは機密なので securePrefs に残す
    var elevenLabsApiKey: String
        get() = securePrefs.getString(KEY_ELEVENLABS_API_KEY, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_ELEVENLABS_API_KEY, value).apply()

    var elevenLabsLanguage: String
        get() = plainPrefs.getString(KEY_ELEVENLABS_LANGUAGE, "jpn") ?: "jpn"
        set(value) = plainPrefs.edit().putString(KEY_ELEVENLABS_LANGUAGE, value).apply()

    /**
     * ログイン済みかどうか。
     * SecureKeyManager 移行後は hasSecureKey パラメータで判定する。
     * 旧形式 (privateKeyHex) もフォールバックとして残す。
     */
    fun isLoggedIn(hasSecureKey: Boolean = false): Boolean {
        @Suppress("DEPRECATION")
        return publicKeyHex != null && (hasSecureKey || privateKeyHex != null || isExternalSigner)
    }

    // ── キャッシュ設定 ────────────────────────────────────────────────────────

    fun getCacheTtlMs(typeId: String, defaultMs: Long): Long =
        plainPrefs.getLong("cache_ttl_$typeId", defaultMs)

    fun setCacheTtlMs(typeId: String, ttlMs: Long) {
        plainPrefs.edit().putLong("cache_ttl_$typeId", ttlMs).apply()
    }

    fun isCacheEnabled(typeId: String): Boolean =
        plainPrefs.getBoolean("cache_enabled_$typeId", true)

    fun setCacheEnabled(typeId: String, enabled: Boolean) {
        plainPrefs.edit().putBoolean("cache_enabled_$typeId", enabled).apply()
    }

    fun getMlsSelfUpdateSuccessAt(groupIdHex: String): Long =
        plainPrefs.getLong(KEY_MLS_SELF_UPDATE_SUCCESS_PREFIX + groupIdHex.lowercase(), 0L)

    fun setMlsSelfUpdateSuccessAt(groupIdHex: String, timestampSecs: Long) {
        plainPrefs.edit()
            .putLong(KEY_MLS_SELF_UPDATE_SUCCESS_PREFIX + groupIdHex.lowercase(), timestampSecs)
            .apply()
    }

    var mlsPublishedKeyPackageEventId: String?
        get() = plainPrefs.getString(KEY_MLS_PUBLISHED_KEY_PACKAGE_EVENT_ID, null)
        set(value) {
            if (value == null) plainPrefs.edit().remove(KEY_MLS_PUBLISHED_KEY_PACKAGE_EVENT_ID).apply()
            else plainPrefs.edit().putString(KEY_MLS_PUBLISHED_KEY_PACKAGE_EVENT_ID, value).apply()
        }

    var mlsPublishedKeyPackageAt: Long
        get() = plainPrefs.getLong(KEY_MLS_PUBLISHED_KEY_PACKAGE_AT, 0L)
        set(value) = plainPrefs.edit().putLong(KEY_MLS_PUBLISHED_KEY_PACKAGE_AT, value).apply()

    var mlsKeyPackageStableDTag: String?
        get() = plainPrefs.getString(KEY_MLS_KEY_PACKAGE_STABLE_D_TAG, null)
        set(value) {
            if (value.isNullOrBlank()) plainPrefs.edit().remove(KEY_MLS_KEY_PACKAGE_STABLE_D_TAG).apply()
            else plainPrefs.edit().putString(KEY_MLS_KEY_PACKAGE_STABLE_D_TAG, value).apply()
        }

    var mlsConsumedKeyPackageEventIds: Set<String>
        get() = plainPrefs.getStringSet(KEY_MLS_CONSUMED_KEY_PACKAGE_EVENT_IDS, emptySet()) ?: emptySet()
        set(value) = plainPrefs.edit()
            .putStringSet(KEY_MLS_CONSUMED_KEY_PACKAGE_EVENT_IDS, value.map { it.lowercase() }.toSet())
            .apply()

    fun addMlsConsumedKeyPackageEventId(eventId: String) {
        val normalized = eventId.trim().lowercase()
        if (normalized.isEmpty()) return
        val current = mlsConsumedKeyPackageEventIds.toMutableSet()
        current.add(normalized)
        mlsConsumedKeyPackageEventIds = current.toList().takeLast(200).toSet()
    }

    var notificationEnabledKinds: Set<Int>
        get() {
            val str = plainPrefs.getString(KEY_NOTIFICATION_KINDS, null)
            return if (str == null) {
                setOf(
                    io.nurunuru.app.data.models.NostrKind.REACTION,
                    io.nurunuru.app.data.models.NostrKind.ZAP_RECEIPT,
                    io.nurunuru.app.data.models.NostrKind.REPOST,
                    io.nurunuru.app.data.models.NostrKind.TEXT_NOTE,
                    io.nurunuru.app.data.models.NostrKind.CONTACT_LIST,
                    io.nurunuru.app.data.models.NostrKind.BADGE_AWARD
                )
            } else {
                str.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
            }
        }
        set(value) {
            plainPrefs.edit().putString(KEY_NOTIFICATION_KINDS, value.joinToString(",")).apply()
        }

    var notificationEmojiReactionEnabled: Boolean
        get() = plainPrefs.getBoolean("notif_emoji_reaction_enabled", true)
        set(value) { plainPrefs.edit().putBoolean("notif_emoji_reaction_enabled", value).apply() }

    var hasAcceptedTerms: Boolean
        get() = plainPrefs.getBoolean(KEY_HAS_ACCEPTED_TERMS, false)
        set(value) { plainPrefs.edit().putBoolean(KEY_HAS_ACCEPTED_TERMS, value).apply() }

    var notificationSenderScope: NotificationSenderScope
        get() = NotificationSenderScope.fromValue(plainPrefs.getString(KEY_NOTIFICATION_SENDER_SCOPE, null))
        set(value) { plainPrefs.edit().putString(KEY_NOTIFICATION_SENDER_SCOPE, value.value).apply() }

    /** フォロー通知の重複抑止用: 既知フォロワー pubkey（iOS notificationKnownFollowerPubkeys と同じ）。 */
    var notificationKnownFollowerPubkeys: Set<String>
        get() = plainPrefs.getStringSet(KEY_NOTIFICATION_KNOWN_FOLLOWERS, emptySet()) ?: emptySet()
        set(value) { plainPrefs.edit().putStringSet(KEY_NOTIFICATION_KNOWN_FOLLOWERS, value).apply() }

    /** フォロー通知の重複抑止用: 処理済み Kind 3 の最大 created_at（iOS notificationFollowLastSeenAt と同じ）。 */
    var notificationFollowLastSeenAt: Long
        get() = plainPrefs.getLong(KEY_NOTIFICATION_FOLLOW_LAST_SEEN_AT, 0L)
        set(value) { plainPrefs.edit().putLong(KEY_NOTIFICATION_FOLLOW_LAST_SEEN_AT, value).apply() }

    fun clear() {
        securePrefs.edit().clear().apply()
        plainPrefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_PRIVATE_KEY_HEX = "private_key_hex"
        private const val KEY_PUBLIC_KEY_HEX = "public_key_hex"
        private const val KEY_RELAYS = "relays"
        private const val KEY_UPLOAD_SERVER = "upload_server"
        private const val KEY_BLOSSOM_URL = "blossom_url"
        private const val KEY_UPLOADED_IMAGES = "uploaded_images"
        private const val KEY_RECENT_SEARCHES = "recent_searches"
        private const val KEY_IS_EXTERNAL_SIGNER = "is_external_signer"
        private const val KEY_FAVORITE_APPS = "favorite_apps"
        private const val KEY_EXTERNAL_APPS = "external_apps"
        private const val KEY_DEFAULT_ZAP_AMOUNT = "default_zap_amount"
        private const val KEY_AUTO_SIGN_ENABLED = "auto_sign_enabled"
        private const val KEY_ELEVENLABS_API_KEY = "elevenlabs_api_key"
        private const val KEY_ELEVENLABS_LANGUAGE = "elevenlabs_language"
        private const val KEY_SELECTED_REGION_ID = "selected_region_id"
        private const val KEY_USER_GEOHASH = "user_geohash"
        private const val KEY_USER_LAT = "user_lat"
        private const val KEY_USER_LON = "user_lon"
        private const val KEY_NIP65_RELAYS = "nip65_relays"
        private const val KEY_MAIN_RELAY = "main_relay"
        private const val KEY_PLAIN_MIGRATED = "plain_migrated_v1"
        private const val KEY_NEWS_SOURCES = "news_sources"
        private const val KEY_NOTIFICATION_KINDS = "notification_enabled_kinds"
        private const val KEY_NOTIFICATION_SENDER_SCOPE = "notification_sender_scope"
        private const val KEY_NOTIFICATION_KNOWN_FOLLOWERS = "notification_known_followers"
        private const val KEY_NOTIFICATION_FOLLOW_LAST_SEEN_AT = "notification_follow_last_seen_at"
        private const val KEY_HAS_ACCEPTED_TERMS = "has_accepted_terms"
        private const val KEY_MLS_SELF_UPDATE_SUCCESS_PREFIX = "mls_self_update_success_at_"
        private const val KEY_MLS_PUBLISHED_KEY_PACKAGE_EVENT_ID = "mls_published_key_package_event_id"
        private const val KEY_MLS_PUBLISHED_KEY_PACKAGE_AT = "mls_published_key_package_at"
        private const val KEY_MLS_KEY_PACKAGE_STABLE_D_TAG = "mls_keypackage_stable_d_tag_v1"
        private const val KEY_MLS_CONSUMED_KEY_PACKAGE_EVENT_IDS = "mls_consumed_key_package_event_ids"
        private const val KEY_MLS_KEY_PACKAGE_RELAYS = "mls_key_package_relays"
        private const val KEY_MLS_INBOX_RELAYS = "mls_inbox_relays"
        private const val KEY_LOGIN_METHOD = "login_method"
        private const val KEY_PENDING_REFERRAL_PUBKEY_HEX = "pending_referral_pubkey_hex"
    }
}


enum class NotificationSenderScope(val value: String, val label: String, val description: String) {
    ALL("all", "全員", "すべてのユーザーからの通知を表示"),
    FOLLOWING("following", "フォロー中のみ", "フォローしているユーザーからの通知のみ表示"),
    NETWORK("network", "ネットワーク", "フォローしている人がフォローしているユーザーまで表示");

    companion object {
        fun fromValue(value: String?): NotificationSenderScope =
            values().firstOrNull { it.value == value } ?: ALL
    }
}
