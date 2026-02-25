package io.nurunuru.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.nurunuru.app.data.models.DEFAULT_RELAYS

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

    val isLoggedIn: Boolean
        get() = privateKeyHex != null && publicKeyHex != null

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_PRIVATE_KEY_HEX = "private_key_hex"
        private const val KEY_PUBLIC_KEY_HEX = "public_key_hex"
        private const val KEY_RELAYS = "relays"
        private const val KEY_UPLOAD_SERVER = "upload_server"
        private const val KEY_BLOSSOM_URL = "blossom_url"
    }
}
