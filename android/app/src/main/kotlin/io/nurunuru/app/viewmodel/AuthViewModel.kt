package io.nurunuru.app.viewmodel

import android.app.Application
import android.content.Context
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
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
    object ExternalSignerWaiting : AuthState()
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
        picture: String = "",
        banner: String = "",
        nip05: String = "",
        lud16: String = "",
        website: String = "",
        birthday: String = "",
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
                about = about,
                picture = picture,
                banner = banner,
                nip05 = nip05,
                lud16 = lud16,
                website = website,
                birthday = birthday
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

    /**
     * Passkey login simulation (Android Credential Manager)
     */
    fun loginWithPasskey(context: Context) {
        viewModelScope.launch {
            _authState.value = AuthState.Checking
            try {
                val credentialManager = CredentialManager.create(context)
                val getPublicKeyCredentialOption = GetPublicKeyCredentialOption(
                    requestJson = "{\"challenge\":\"Y2hhbGxlbmdl\",\"allowCredentials\":[],\"timeout\":60000,\"userVerification\":\"required\"}"
                )
                val getCredRequest = GetCredentialRequest(listOf(getPublicKeyCredentialOption))

                // Note: In a real implementation with a backend/PRF, we would get the seed here.
                // For now, if we have a stored key, we simulate the 'unlock' UX.
                val result = credentialManager.getCredential(context, getCredRequest)

                val storedPrivKey = prefs.privateKeyHex
                val storedPubKey = prefs.publicKeyHex

                if (storedPrivKey != null && storedPubKey != null) {
                    _authState.value = AuthState.LoggedIn(storedPubKey, storedPrivKey)
                } else {
                    _authState.value = AuthState.Error("パスキーに対応するアカウントが見つかりません。新規登録してください。")
                }
            } catch (e: Exception) {
                _authState.value = AuthState.Error("パスキー認証に失敗しました: ${e.message}")
            }
        }
    }

    /**
     * Passkey signup simulation
     */
    suspend fun signUpWithPasskey(context: Context): Boolean {
        return try {
            val credentialManager = CredentialManager.create(context)
            val requestJson = """
                {
                    "challenge": "Y2hhbGxlbmdl",
                    "rp": { "name": "ぬるぬる", "id": "nurunuru.app" },
                    "user": { "id": "dXNlcmlk", "name": "user", "displayName": "Nostr User" },
                    "pubKeyCredParams": [{ "type": "public-key", "alg": -7 }],
                    "timeout": 60000,
                    "attestation": "none",
                    "authenticatorSelection": { "authenticatorAttachment": "platform", "userVerification": "required" }
                }
            """.trimIndent()

            val createPublicKeyCredentialRequest = CreatePublicKeyCredentialRequest(requestJson)
            credentialManager.createCredential(context, createPublicKeyCredentialRequest)
            true
        } catch (e: Exception) {
            false
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
