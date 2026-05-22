package io.nurunuru.app.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nurunuru.app.data.NostrClient
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.SecureKeyManager
import io.nurunuru.app.data.models.UserProfile
import io.nurunuru.app.data.prefs.AppPreferences
import io.nurunuru.app.data.*
import javax.crypto.Cipher
import java.io.File
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

    private suspend fun checkStoredLogin() {
        val pubKey = prefs.publicKeyHex
        val isExternal = prefs.isExternalSigner
        val hasSecureKey = keyManager.hasStoredKey()

        if (pubKey != null && (hasSecureKey || isExternal)) {
            if (isExternal) {
                io.nurunuru.app.data.ExternalSigner.setCurrentUser(pubKey)
                _authState.value = AuthState.LoggedIn(pubKey, isExternal = true)
                viewModelScope.launch(Dispatchers.IO) { syncRelayListOnLogin(pubKey, io.nurunuru.app.data.ExternalSigner) }
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
                        viewModelScope.launch(Dispatchers.IO) { syncRelayListOnLogin(pubKey, io.nurunuru.app.data.InternalSigner(keyManager)) }
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
                launch { syncRelayListOnLogin(pubKey, io.nurunuru.app.data.InternalSigner(keyManager)) }
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
        viewModelScope.launch(Dispatchers.IO) {
            _authState.value = AuthState.LoggedIn(pubKey, isExternal = false, hasInternalKey = true)
            launch { syncRelayListOnLogin(pubKey, io.nurunuru.app.data.InternalSigner(keyManager)) }
        }
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

            val cache = io.nurunuru.app.data.cache.NostrCache(getApplication())
            val recommendationEngine = io.nurunuru.app.data.RecommendationEngine(getApplication())
            val repository = NostrRepository(client, prefs, cache, recommendationEngine)

            val relayList = relays ?: targetRelays.map { Triple(it, true, true) }
            // Persist the selected relay set before publishing so the just-created
            // account immediately uses the same NIP-65 relays after login.
            prefs.nip65Relays = relayList.map { (url, read, write) ->
                io.nurunuru.app.data.models.Nip65Relay(url, read, write)
            }
            prefs.mainRelay = relayList.firstOrNull { it.second && it.third }?.first
                ?: relayList.firstOrNull()?.first
                ?: "wss://yabu.me"

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
            val profilePublished = repository.updateProfile(profile)
            if (!profilePublished) {
                android.util.Log.e("AuthViewModel", "Initial kind0 profile publish failed")
                client.disconnect()
                return@withContext false
            }

            delay(500)
            val relayPublished = repository.updateRelayList(relayList) || run {
                android.util.Log.w("AuthViewModel", "Initial kind10002 relay publish failed; retrying once")
                delay(1000)
                repository.updateRelayList(relayList)
            }
            if (!relayPublished) {
                android.util.Log.e("AuthViewModel", "Initial kind10002 relay publish failed")
                client.disconnect()
                return@withContext false
            }

            delay(1000)
            client.disconnect()
            true
        } catch (e: Exception) {
            android.util.Log.e("AuthViewModel", "publishInitialMetadata failed", e)
            false
        }
    }

    private suspend fun syncRelayListOnLogin(pubKeyHex: String, signer: io.nurunuru.app.data.AppSigner) {
        try {
            val discoveryRelays = (prefs.nip65Relays.map { it.url } + prefs.relays.toList() + OutboxModel.RELAY_LIST_DISCOVERY_RELAYS).distinct()
            val client = NostrClient(
                context = getApplication(),
                relays = discoveryRelays,
                signer = signer
            )
            client.connect()
            delay(1200)
            val cache = io.nurunuru.app.data.cache.NostrCache(getApplication())
            val recommendationEngine = io.nurunuru.app.data.RecommendationEngine(getApplication())
            val repository = NostrRepository(client, prefs, cache, recommendationEngine)
            repository.syncLoggedInUserRelayList(pubKeyHex)
            client.disconnect()
        } catch (e: Exception) {
            android.util.Log.w("AuthViewModel", "syncRelayListOnLogin failed: " + e.message)
        }
    }

    fun completeRegistration(pubKeyHex: String) {
        // 秘密鍵は既に SecureKeyManager に保存済み。
        // 「はじめる」タップ時はログイン状態を先に反映し、LoginScreen/新規登録画面へ
        // 一瞬戻ることなく MainScreen(ホーム) へ直接切り替える。
        prefs.publicKeyHex = pubKeyHex
        prefs.isExternalSigner = false
        _authState.value = AuthState.LoggedIn(pubKeyHex, isExternal = false, hasInternalKey = true)

        // リレー同期は遷移後にバックグラウンドで継続する。
        viewModelScope.launch(Dispatchers.IO) {
            syncRelayListOnLogin(pubKeyHex, io.nurunuru.app.data.InternalSigner(keyManager))
        }
    }

    fun loginWithAmber(pubkey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // Normalize to hex — Amber may return npub (bech32) or hex format
            val pubkeyHex = io.nurunuru.app.data.NostrKeyUtils.parsePublicKey(pubkey) ?: pubkey
            prefs.publicKeyHex = pubkeyHex
            prefs.isExternalSigner = true
            io.nurunuru.app.data.ExternalSigner.setCurrentUser(pubkeyHex)
            syncRelayListOnLogin(pubkeyHex, io.nurunuru.app.data.ExternalSigner)
            _authState.value = AuthState.LoggedIn(pubkeyHex, isExternal = true)
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

                syncRelayListOnLogin(pubKeyHex, io.nurunuru.app.data.InternalSigner(keyManager))
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
        val app = getApplication<Application>()

        // Issue #181: external-signer MLS DB key is pubkey-scoped, so we
        // wipe it via the current pubkey BEFORE prefs.clear() forgets it.
        // For the internal-signer path the key is derived from nsec via
        // HKDF and not persisted, so deleteAll() / clearLocalRustDatabases()
        // is sufficient.
        try {
            val currentPubkey = prefs.publicKeyHex
            if (currentPubkey != null && currentPubkey.length == 64) {
                io.nurunuru.app.data.MlsDbKeyStore.clearExternalKey(app, currentPubkey)
            } else if (currentPubkey != null) {
                // bech32 form or unexpected — wipe everything in the
                // external keystore to be safe.
                io.nurunuru.app.data.MlsDbKeyStore.clearAllExternalKeys(app)
            }
        } catch (_: Exception) { }

        keyManager.deleteAll()

        // Privacy/account isolation: Talk uses Rust MLS SQLite as its source of truth.
        // If it survives logout, a different account can still see old local groups/messages
        // because the FFI DB is app-global. Clear both app-layer cache and local Rust DB files.
        try { io.nurunuru.app.data.cache.NostrCache(app).clearAll() } catch (_: Exception) { }
        try { clearLocalRustDatabases(app) } catch (_: Exception) { }

        prefs.clear()
        _authState.value = AuthState.LoggedOut
    }

    private fun clearLocalRustDatabases(context: Context) {
        val base = File(context.filesDir, "nostrdb_ndb")
        // MLS database path is configured in Rust as "${filesDir}/nostrdb_ndb_mls.sqlite3".
        listOf(
            base,
            File(context.filesDir, "nostrdb_ndb_mls.sqlite3"),
            File(context.filesDir, "nostrdb_ndb_mls.sqlite3-shm"),
            File(context.filesDir, "nostrdb_ndb_mls.sqlite3-wal")
        ).forEach { file ->
            if (file.exists()) {
                if (file.isDirectory) file.deleteRecursively() else file.delete()
            }
        }
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
