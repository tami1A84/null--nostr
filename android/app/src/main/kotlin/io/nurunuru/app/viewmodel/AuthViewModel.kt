package io.nurunuru.app.viewmodel

import android.app.Application
import android.content.Context
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nurunuru.app.data.NostrClient
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.SecureKeyManager
import io.nurunuru.app.data.models.UserProfile
import io.nurunuru.app.data.prefs.AppPreferences
import javax.crypto.Cipher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class AuthState {
    object Checking : AuthState()
    object LoggedOut : AuthState()
    /** 秘密鍵は AuthState に含めない — SecureKeyManager 経由でのみアクセス */
    data class LoggedIn(
        val pubkeyHex: String,
        val isExternal: Boolean = false,
        val hasInternalKey: Boolean = false
    ) : AuthState()
    /** 生体認証が必要な状態 */
    object BiometricRequired : AuthState()
    data class Error(val message: String) : AuthState()
    object ExternalSignerWaiting : AuthState()
}

data class GeneratedAccount(
    val pubkeyHex: String,
    val nsec: String,
    val npub: String
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    val prefs = AppPreferences(application)
    val keyManager = SecureKeyManager(application)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Checking)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        migrateAndCheckLogin()
    }

    /**
     * 旧形式からのマイグレーション + ログイン状態チェック。
     */
    private fun migrateAndCheckLogin() {
        viewModelScope.launch(Dispatchers.IO) {
            // 旧 EncryptedSharedPreferences からの移行
            @Suppress("DEPRECATION")
            if (prefs.privateKeyHex != null && !keyManager.hasStoredKey()) {
                keyManager.migrateFromLegacy(prefs)
            }

            checkStoredLogin()
        }
    }

    private fun checkStoredLogin() {
        val pubKey = prefs.publicKeyHex
        val isExternal = prefs.isExternalSigner
        val hasSecureKey = keyManager.hasStoredKey()

        if (pubKey != null && (hasSecureKey || isExternal)) {
            if (isExternal) {
                io.nurunuru.app.data.ExternalSigner.setCurrentUser(pubKey)
                _authState.value = AuthState.LoggedIn(pubKey, isExternal = true)
            } else if (hasSecureKey) {
                if (keyManager.isBiometricBound()) {
                    // 生体認証が必要 → BiometricRequired 状態にして UI に委譲
                    _authState.value = AuthState.BiometricRequired
                } else {
                    // 生体認証不要 → 直接復号
                    if (keyManager.unlockKeyDirect()) {
                        _authState.value = AuthState.LoggedIn(
                            pubKey,
                            isExternal = false,
                            hasInternalKey = true
                        )
                    } else {
                        _authState.value = AuthState.Error("秘密鍵の復号に失敗しました")
                    }
                }
            }
        } else {
            _authState.value = AuthState.LoggedOut
        }
    }

    /**
     * 生体認証成功時に呼ばれる。
     */
    fun onBiometricSuccess(cipher: Cipher) {
        viewModelScope.launch(Dispatchers.IO) {
            val pubKey = prefs.publicKeyHex
            if (pubKey != null && keyManager.unlockKey(cipher)) {
                _authState.value = AuthState.LoggedIn(
                    pubKey,
                    isExternal = false,
                    hasInternalKey = true
                )
            } else {
                _authState.value = AuthState.Error("秘密鍵のアンロックに失敗しました")
            }
        }
    }

    /**
     * 生体認証が利用不可で直接復号に成功した場合のフォールバック。
     */
    fun onBiometricFallbackSuccess() {
        val pubKey = prefs.publicKeyHex ?: return
        _authState.value = AuthState.LoggedIn(pubKey, isExternal = false, hasInternalKey = true)
    }

    /**
     * 生体認証失敗/キャンセル時。
     */
    fun onBiometricFailure() {
        _authState.value = AuthState.LoggedOut
    }

    fun generateNewAccount(): GeneratedAccount? {
        return try {
            val keys = NostrKeyUtils.generateKeys()
            val privHex = keys.secretKey().toHex()
            val pubHex = keys.publicKey().toHex()
            val nsec = NostrKeyUtils.encodeNsec(privHex) ?: ""
            val npub = NostrKeyUtils.encodeNpub(pubHex) ?: ""

            // 秘密鍵を SecureKeyManager に安全に保存
            val keyBytes = hexToBytes(privHex)
            if (keyBytes != null) {
                keyManager.generateKeystoreKey(requireBiometric = false)
                keyManager.storeKey(keyBytes, pubHex)
                keyBytes.fill(0)
            }

            GeneratedAccount(
                pubkeyHex = pubHex,
                nsec = nsec,
                npub = npub
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun publishInitialMetadata(
        signer: io.nurunuru.app.data.AppSigner,
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
            val targetRelays = relays?.map { it.first } ?: listOf("wss://yabu.me", "wss://relay.nostr.wirednet.jp", "wss://r.kojira.io")
            val client = NostrClient(
                context = getApplication(),
                relays = targetRelays,
                signer = signer
            )
            client.connect()

            delay(1500)

            val repository = NostrRepository(client, prefs)

            val profile = UserProfile(
                pubkey = signer.getPublicKeyHex(),
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

            val relayList = relays ?: targetRelays.map { Triple(it, true, true) }
            repository.updateRelayList(relayList)

            delay(1000)
            client.disconnect()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun completeRegistration(pubKeyHex: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // 秘密鍵は既に SecureKeyManager に保存済み
            prefs.publicKeyHex = pubKeyHex
            prefs.isExternalSigner = false
            _authState.value = AuthState.LoggedIn(pubKeyHex, isExternal = false, hasInternalKey = true)
        }
    }

    fun loginWithAmber(pubkey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.publicKeyHex = pubkey
            prefs.isExternalSigner = true
            io.nurunuru.app.data.ExternalSigner.setCurrentUser(pubkey)
            _authState.value = AuthState.LoggedIn(pubkey, isExternal = true)
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

            try {
                // hex → ByteArray → SecureKeyManager で暗号化保存
                val keyBytes = hexToBytes(privKeyHex)
                if (keyBytes == null || keyBytes.size != 32) {
                    _authState.value = AuthState.Error("秘密鍵のバイト変換に失敗しました")
                    return@launch
                }

                try {
                    keyManager.generateKeystoreKey(requireBiometric = false)
                } catch (e: Exception) {
                    _authState.value = AuthState.Error("キーストア鍵の生成に失敗しました: ${e.message}")
                    return@launch
                }

                try {
                    keyManager.storeKey(keyBytes, pubKeyHex)
                } catch (e: Exception) {
                    _authState.value = AuthState.Error("秘密鍵の暗号化保存に失敗しました: ${e.message}")
                    return@launch
                } finally {
                    keyBytes.fill(0)
                }

                prefs.publicKeyHex = pubKeyHex
                prefs.isExternalSigner = false
                prefs.clearPrivateKey()

                _authState.value = AuthState.LoggedIn(pubKeyHex, isExternal = false, hasInternalKey = true)
            } catch (e: Exception) {
                _authState.value = AuthState.Error("ログイン処理中にエラーが発生しました: ${e.message}")
            }
        }
    }

    /**
     * Passkey login by redirecting to web
     */
    fun loginWithPasskey(context: Context) {
        try {
            val nonce = System.currentTimeMillis()
            val redirectUri = android.net.Uri.encode("io.nurunuru.app://login")
            val url = "https://www.nullnull.app/?redirect_uri=$redirectUri&nonce=$nonce"

            val customTabsIntent = androidx.browser.customtabs.CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setShareState(androidx.browser.customtabs.CustomTabsIntent.SHARE_STATE_OFF)
                .build()

            customTabsIntent.intent.setPackage("com.android.chrome")
            customTabsIntent.launchUrl(context, android.net.Uri.parse(url))
        } catch (e: Exception) {
            try {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://www.nullnull.app/?redirect_uri=io.nurunuru.app://login")
                )
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e2: Exception) {
                _authState.value = AuthState.Error("ブラウザを開けませんでした: ${e2.message}")
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
                    "rp": { "name": "ぬるぬる", "id": "www.nullnull.app" },
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

    /**
     * 生体認証を有効化する (設定画面から呼ばれる)。
     */
    fun enableBiometric() {
        viewModelScope.launch(Dispatchers.IO) {
            val keyBytes = keyManager.getKeyBytes() ?: return@launch
            val pubkey = keyManager.getStoredPublicKeyHex() ?: return@launch

            try {
                keyManager.deleteAll()
                keyManager.generateKeystoreKey(requireBiometric = true)
                keyManager.storeKey(keyBytes, pubkey)
                keyBytes.fill(0)
            } catch (e: Exception) {
                keyManager.generateKeystoreKey(requireBiometric = false)
                keyManager.storeKey(keyBytes, pubkey)
                keyBytes.fill(0)
            }
        }
    }

    fun getNsecTemporary(): String? {
        return keyManager.getKeyHexTemporary()?.let { NostrKeyUtils.encodeNsec(it) }
    }

    fun logout() {
        keyManager.deleteAll()
        prefs.clear()
        _authState.value = AuthState.LoggedOut
    }

    fun clearError() {
        if (_authState.value is AuthState.Error) {
            _authState.value = AuthState.LoggedOut
        }
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
