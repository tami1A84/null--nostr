package com.example.nostr.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.nostr.data.database.dao.ContactDao
import com.example.nostr.nostr.crypto.NostrSigner
import com.example.nostr.nostr.event.NostrEvent
import com.example.nostr.nostr.event.UnsignedEvent
import com.example.nostr.nostr.event.hexToByteArray
import com.example.nostr.nostr.event.toHexString
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth")

@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactDao: ContactDao
) {
    private val dataStore = context.authDataStore

    // DataStore keys
    private object PreferencesKeys {
        val PUBKEY = stringPreferencesKey("pubkey")
        val PRIVATE_KEY = stringPreferencesKey("private_key")
        val LOGIN_METHOD = stringPreferencesKey("login_method")
        val IS_READ_ONLY = booleanPreferencesKey("is_read_only")
    }

    enum class LoginMethod {
        LOCAL_KEY,
        AMBER,
        READ_ONLY
    }

    /**
     * Check if user is logged in
     */
    val isLoggedIn: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PUBKEY] != null
    }

    /**
     * Get current pubkey
     */
    suspend fun getCurrentPubkey(): String? {
        return dataStore.data.first()[PreferencesKeys.PUBKEY]
    }

    /**
     * Observe current pubkey
     */
    fun observePubkey(): Flow<String?> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PUBKEY]
    }

    /**
     * Get login method
     */
    suspend fun getLoginMethod(): LoginMethod? {
        val method = dataStore.data.first()[PreferencesKeys.LOGIN_METHOD]
        return method?.let { LoginMethod.valueOf(it) }
    }

    /**
     * Check if read-only mode
     */
    suspend fun isReadOnly(): Boolean {
        return dataStore.data.first()[PreferencesKeys.IS_READ_ONLY] ?: false
    }

    /**
     * Login with nsec (private key)
     */
    suspend fun loginWithNsec(nsec: String): Result<String> {
        return try {
            val privateKey = NostrSigner.nsecToPrivateKey(nsec)
            val publicKey = NostrSigner.getPublicKey(privateKey)
            val pubkey = publicKey.toHexString()

            dataStore.edit { preferences ->
                preferences[PreferencesKeys.PUBKEY] = pubkey
                preferences[PreferencesKeys.PRIVATE_KEY] = privateKey.toHexString()
                preferences[PreferencesKeys.LOGIN_METHOD] = LoginMethod.LOCAL_KEY.name
                preferences[PreferencesKeys.IS_READ_ONLY] = false
            }

            Result.success(pubkey)
        } catch (e: Exception) {
            Timber.e(e, "Error logging in with nsec")
            Result.failure(e)
        }
    }

    /**
     * Login with hex private key
     */
    suspend fun loginWithHexKey(hexPrivateKey: String): Result<String> {
        return try {
            val privateKey = hexPrivateKey.hexToByteArray()
            val publicKey = NostrSigner.getPublicKey(privateKey)
            val pubkey = publicKey.toHexString()

            dataStore.edit { preferences ->
                preferences[PreferencesKeys.PUBKEY] = pubkey
                preferences[PreferencesKeys.PRIVATE_KEY] = hexPrivateKey
                preferences[PreferencesKeys.LOGIN_METHOD] = LoginMethod.LOCAL_KEY.name
                preferences[PreferencesKeys.IS_READ_ONLY] = false
            }

            Result.success(pubkey)
        } catch (e: Exception) {
            Timber.e(e, "Error logging in with hex key")
            Result.failure(e)
        }
    }

    /**
     * Login with npub (read-only mode)
     */
    suspend fun loginWithNpub(npub: String): Result<String> {
        return try {
            val publicKey = NostrSigner.npubToPublicKey(npub)
            val pubkey = publicKey.toHexString()

            dataStore.edit { preferences ->
                preferences[PreferencesKeys.PUBKEY] = pubkey
                preferences[PreferencesKeys.LOGIN_METHOD] = LoginMethod.READ_ONLY.name
                preferences[PreferencesKeys.IS_READ_ONLY] = true
            }

            Result.success(pubkey)
        } catch (e: Exception) {
            Timber.e(e, "Error logging in with npub")
            Result.failure(e)
        }
    }

    /**
     * Login with hex pubkey (read-only mode)
     */
    suspend fun loginWithHexPubkey(hexPubkey: String): Result<String> {
        return try {
            dataStore.edit { preferences ->
                preferences[PreferencesKeys.PUBKEY] = hexPubkey
                preferences[PreferencesKeys.LOGIN_METHOD] = LoginMethod.READ_ONLY.name
                preferences[PreferencesKeys.IS_READ_ONLY] = true
            }

            Result.success(hexPubkey)
        } catch (e: Exception) {
            Timber.e(e, "Error logging in with hex pubkey")
            Result.failure(e)
        }
    }

    /**
     * Generate new keypair and login
     */
    suspend fun generateAndLogin(): Result<Pair<String, String>> {
        return try {
            val privateKey = NostrSigner.generatePrivateKey()
            val publicKey = NostrSigner.getPublicKey(privateKey)
            val pubkey = publicKey.toHexString()
            val nsec = NostrSigner.privateKeyToNsec(privateKey)

            dataStore.edit { preferences ->
                preferences[PreferencesKeys.PUBKEY] = pubkey
                preferences[PreferencesKeys.PRIVATE_KEY] = privateKey.toHexString()
                preferences[PreferencesKeys.LOGIN_METHOD] = LoginMethod.LOCAL_KEY.name
                preferences[PreferencesKeys.IS_READ_ONLY] = false
            }

            Result.success(Pair(pubkey, nsec))
        } catch (e: Exception) {
            Timber.e(e, "Error generating keypair")
            Result.failure(e)
        }
    }

    /**
     * Sign an event
     */
    suspend fun signEvent(unsignedEvent: UnsignedEvent): NostrEvent? {
        val method = getLoginMethod()
        if (method == LoginMethod.READ_ONLY) {
            Timber.w("Cannot sign events in read-only mode")
            return null
        }

        return when (method) {
            LoginMethod.LOCAL_KEY -> {
                val privateKeyHex = dataStore.data.first()[PreferencesKeys.PRIVATE_KEY]
                if (privateKeyHex != null) {
                    val privateKey = privateKeyHex.hexToByteArray()
                    NostrSigner.signEvent(unsignedEvent, privateKey)
                } else null
            }
            LoginMethod.AMBER -> {
                // TODO: Implement Amber signing via Intent
                null
            }
            else -> null
        }
    }

    /**
     * Get the list of pubkeys the user is following
     */
    suspend fun getFollowing(): List<String> {
        val pubkey = getCurrentPubkey() ?: return emptyList()
        return contactDao.getContactPubkeys(pubkey)
    }

    /**
     * Get nsec for export
     */
    suspend fun getNsec(): String? {
        val privateKeyHex = dataStore.data.first()[PreferencesKeys.PRIVATE_KEY]
        return privateKeyHex?.let {
            NostrSigner.privateKeyToNsec(it.hexToByteArray())
        }
    }

    /**
     * Get npub
     */
    suspend fun getNpub(): String? {
        val pubkey = getCurrentPubkey()
        return pubkey?.let {
            NostrSigner.publicKeyToNpub(it.hexToByteArray())
        }
    }

    /**
     * Logout
     */
    suspend fun logout() {
        dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.PUBKEY)
            preferences.remove(PreferencesKeys.PRIVATE_KEY)
            preferences.remove(PreferencesKeys.LOGIN_METHOD)
            preferences.remove(PreferencesKeys.IS_READ_ONLY)
        }
    }
}
