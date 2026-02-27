package io.nurunuru.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
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

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "nurunuru_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var privateKeyHex: String?
        get() = prefs.getString(KEY_PRIVATE_KEY_HEX, null)
        set(value) {
            if (value == null) prefs.edit().remove(KEY_PRIVATE_KEY_HEX).apply()
            else prefs.edit().putString(KEY_PRIVATE_KEY_HEX, value).apply()
        }

    var publicKeyHex: String?
        get() = prefs.getString(KEY_PUBLIC_KEY_HEX, null)
        set(value) {
            if (value == null) prefs.edit().remove(KEY_PUBLIC_KEY_HEX).apply()
            else prefs.edit().putString(KEY_PUBLIC_KEY_HEX, value).apply()
        }

    var relays: Set<String>
        get() = prefs.getStringSet(KEY_RELAYS, DEFAULT_RELAYS.toSet()) ?: DEFAULT_RELAYS.toSet()
        set(value) = prefs.edit().putStringSet(KEY_RELAYS, value).apply()

    var uploadServer: String
        get() = prefs.getString(KEY_UPLOAD_SERVER, "nostr.build") ?: "nostr.build"
        set(value) = prefs.edit().putString(KEY_UPLOAD_SERVER, value).apply()

    var blossomUrl: String
        get() = prefs.getString(KEY_BLOSSOM_URL, "https://blossom.nostr.build") ?: "https://blossom.nostr.build"
        set(value) = prefs.edit().putString(KEY_BLOSSOM_URL, value).apply()

    var isExternalSigner: Boolean
        get() = prefs.getBoolean(KEY_IS_EXTERNAL_SIGNER, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_EXTERNAL_SIGNER, value).apply()

    var recentSearches: List<String>
        get() {
            val jsonStr = prefs.getString(KEY_RECENT_SEARCHES, "[]") ?: "[]"
            return try {
                Json.decodeFromString(jsonStr)
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val jsonStr = Json.encodeToString(value)
            prefs.edit().putString(KEY_RECENT_SEARCHES, jsonStr).apply()
        }

    var favoriteApps: List<String>
        get() {
            val jsonStr = prefs.getString(KEY_FAVORITE_APPS, "[]") ?: "[]"
            return try {
                Json.decodeFromString(jsonStr)
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val jsonStr = Json.encodeToString(value)
            prefs.edit().putString(KEY_FAVORITE_APPS, jsonStr).apply()
        }

    var selectedRegionId: String?
        get() = prefs.getString(KEY_SELECTED_REGION_ID, null)
        set(value) = prefs.edit().putString(KEY_SELECTED_REGION_ID, value).apply()

    var userGeohash: String?
        get() = prefs.getString(KEY_USER_GEOHASH, null)
        set(value) = prefs.edit().putString(KEY_USER_GEOHASH, value).apply()

    var userLat: Double
        get() = prefs.getFloat(KEY_USER_LAT, 0.0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_USER_LAT, value.toFloat()).apply()

    var userLon: Double
        get() = prefs.getFloat(KEY_USER_LON, 0.0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_USER_LON, value.toFloat()).apply()

    var nip65Relays: List<io.nurunuru.app.data.models.Nip65Relay>
        get() {
            val jsonStr = prefs.getString(KEY_NIP65_RELAYS, "[]") ?: "[]"
            return try {
                Json.decodeFromString(jsonStr)
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val jsonStr = Json.encodeToString(value)
            prefs.edit().putString(KEY_NIP65_RELAYS, jsonStr).apply()
        }

    var externalApps: String
        get() = prefs.getString(KEY_EXTERNAL_APPS, "[]") ?: "[]"
        set(value) = prefs.edit().putString(KEY_EXTERNAL_APPS, value).apply()

    var defaultZapAmount: Int
        get() = prefs.getInt(KEY_DEFAULT_ZAP_AMOUNT, 21)
        set(value) = prefs.edit().putInt(KEY_DEFAULT_ZAP_AMOUNT, value).apply()

    var autoSignEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SIGN_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SIGN_ENABLED, value).apply()

    var elevenLabsApiKey: String
        get() = prefs.getString(KEY_ELEVENLABS_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ELEVENLABS_API_KEY, value).apply()

    var elevenLabsLanguage: String
        get() = prefs.getString(KEY_ELEVENLABS_LANGUAGE, "jpn") ?: "jpn"
        set(value) = prefs.edit().putString(KEY_ELEVENLABS_LANGUAGE, value).apply()

    val isLoggedIn: Boolean
        get() = publicKeyHex != null && (privateKeyHex != null || isExternalSigner)

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_PRIVATE_KEY_HEX = "private_key_hex"
        private const val KEY_PUBLIC_KEY_HEX = "public_key_hex"
        private const val KEY_RELAYS = "relays"
        private const val KEY_UPLOAD_SERVER = "upload_server"
        private const val KEY_BLOSSOM_URL = "blossom_url"
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
    }
}
