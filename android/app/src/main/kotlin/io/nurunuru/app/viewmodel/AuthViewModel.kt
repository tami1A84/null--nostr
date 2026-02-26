package io.nurunuru.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nurunuru.app.data.NostrClient
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.UserProfile
import io.nurunuru.app.data.prefs.AppPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class AuthState {
    object Checking : AuthState()
    object LoggedOut : AuthState()
    data class LoggedIn(val pubkeyHex: String, val privateKeyHex: String) : AuthState()
    data class Error(val message: String) : AuthState()
}

data class GeneratedAccount(
    val privateKeyHex: String,
    val pubkeyHex: String,
    val nsec: String,
    val npub: String
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Checking)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        checkStoredLogin()
    }

    private fun checkStoredLogin() {
        viewModelScope.launch(Dispatchers.IO) {
            val privKey = prefs.privateKeyHex
            val pubKey = prefs.publicKeyHex
            if (privKey != null && pubKey != null) {
                _authState.value = AuthState.LoggedIn(pubKey, privKey)
            } else {
                _authState.value = AuthState.LoggedOut
            }
        }
    }

    fun generateNewAccount(): GeneratedAccount? {
        return try {
            val keys = NostrKeyUtils.generateKeys()
            val privHex = keys.secretKey().toHex()
            val pubHex = keys.publicKey().toHex()
            GeneratedAccount(
                privateKeyHex = privHex,
                pubkeyHex = pubHex,
                nsec = NostrKeyUtils.encodeNsec(privHex) ?: "",
                npub = NostrKeyUtils.encodeNpub(pubHex) ?: ""
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun publishInitialMetadata(
        privKeyHex: String,
        pubKeyHex: String,
        name: String,
        about: String,
        relays: List<Triple<String, Boolean, Boolean>>? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. Setup temporary client with default JP relays or provided relays
            val targetRelays = relays?.map { it.first } ?: listOf("wss://yabu.me", "wss://relay.nostr.wirednet.jp", "wss://r.kojira.io")
            val client = NostrClient(
                context = getApplication(),
                relays = targetRelays,
                privateKeyHex = privKeyHex,
                publicKeyHex = pubKeyHex
            )
            client.connect()

            // Give it a moment to connect
            delay(1500)

            val repository = NostrRepository(client, prefs)

            // 2. Publish Kind 0 (Metadata)
            val profile = UserProfile(
                pubkey = pubKeyHex,
                name = name,
                displayName = name,
                about = about
            )
            repository.updateProfile(profile)

            // 3. Publish Kind 10002 (Relay List)
            val relayList = relays ?: targetRelays.map { Triple(it, true, true) }
            repository.updateRelayList(relayList)

            // 4. Disconnect
            delay(1000) // Wait for sends to finish
            client.disconnect()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun completeRegistration(privKeyHex: String, pubKeyHex: String) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.privateKeyHex = privKeyHex
            prefs.publicKeyHex = pubKeyHex
            _authState.value = AuthState.LoggedIn(pubKeyHex, privKeyHex)
        }
    }

    fun login(nsecOrHex: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _authState.value = AuthState.Checking

            val privKeyHex = NostrKeyUtils.parsePrivateKey(nsecOrHex)
            if (privKeyHex == null) {
                _authState.value = AuthState.Error("秘密鍵の形式が正しくありません（nsec1... または64桁の16進数）")
                return@launch
            }

            val pubKeyHex = NostrKeyUtils.derivePublicKey(privKeyHex)
            if (pubKeyHex == null) {
                _authState.value = AuthState.Error("公開鍵の導出に失敗しました")
                return@launch
            }

            prefs.privateKeyHex = privKeyHex
            prefs.publicKeyHex = pubKeyHex
            _authState.value = AuthState.LoggedIn(pubKeyHex, privKeyHex)
        }
    }

    fun logout() {
        prefs.clear()
        _authState.value = AuthState.LoggedOut
    }

    fun clearError() {
        if (_authState.value is AuthState.Error) {
            _authState.value = AuthState.LoggedOut
        }
    }
}
