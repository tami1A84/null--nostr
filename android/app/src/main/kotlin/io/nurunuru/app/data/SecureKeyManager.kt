package io.nurunuru.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore を使った Nostr 秘密鍵の安全な管理。
 *
 * ## 設計
 * - Android Keystore に AES-256-GCM 鍵を生成し、secp256k1 秘密鍵を暗号化して保存
 * - Android Keystore は secp256k1 を直接サポートしないため、AES ラッパーキーで保護する
 *   ハイブリッド方式を採用
 * - メモリ上では ByteArray のみ使用し、不要になったら即座にゼロフィル
 * - 生体認証 / デバイス認証と連携し、認証なしでは復号不可
 *
 * Web 版 `lib/secure-key-store.js` に対応する Android 実装。
 */
class SecureKeyManager(private val context: Context) {

    companion object {
        private const val TAG = "SecureKeyManager"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "nurunuru_nostr_key_encryption"
        private const val PREFS_NAME = "nurunuru_key_vault"
        private const val PREF_ENCRYPTED_KEY = "encrypted_nostr_key"
        private const val PREF_IV = "encrypted_nostr_key_iv"
        private const val PREF_PUBKEY = "nostr_pubkey"
        private const val PREF_MIGRATED = "migrated_from_legacy"
        private const val PREF_BIOMETRIC_BOUND = "biometric_bound"
        private const val GCM_TAG_LENGTH = 128
    }

    // メモリ上の復号済み秘密鍵 (ByteArray — ゼロ化可能)
    @Volatile
    private var decryptedKey: ByteArray? = null

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ─── Keystore 鍵管理 ───────────────────────────────────────

    /**
     * Android Keystore に AES-256-GCM 暗号化鍵を生成する。
     *
     * @param requireBiometric true なら生体認証バインド、false ならデバイス認証のみ
     */
    fun generateKeystoreKey(requireBiometric: Boolean) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            Log.d(TAG, "Keystore key already exists")
            return
        }

        val purposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        val specBuilder = KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, purposes)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)

        if (requireBiometric) {
            specBuilder.setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                specBuilder.setUserAuthenticationParameters(
                    0, // 毎回認証を要求
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                )
            } else {
                @Suppress("DEPRECATION")
                specBuilder.setUserAuthenticationValidityDurationSeconds(-1)
            }
        }

        // StrongBox (HSM) を試行
        val isStrongBoxAvailable = checkStrongBoxAvailability()
        if (isStrongBoxAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            specBuilder.setIsStrongBoxBacked(true)
        }

        val spec = specBuilder.build()
        try {
            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            keyGen.init(spec)
            keyGen.generateKey()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate key with spec (StrongBox=$isStrongBoxAvailable)", e)
            if (isStrongBoxAvailable) {
                // Fallback to TEE if StrongBox failed despite check
                Log.d(TAG, "Retrying without StrongBox...")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    specBuilder.setIsStrongBoxBacked(false)
                }
                val fallbackSpec = specBuilder.build()
                val keyGenFallback = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                keyGenFallback.init(fallbackSpec)
                keyGenFallback.generateKey()
            } else {
                throw e
            }
        }

        prefs.edit().putBoolean(PREF_BIOMETRIC_BOUND, requireBiometric).apply()
        Log.d(TAG, "Keystore AES key generated (biometric=$requireBiometric, strongbox=$isStrongBoxAvailable)")
    }

    /**
     * StrongBox 対応デバイスでは HSM バッキングを試み、非対応ならフォールバック。
     */
    private fun checkStrongBoxAvailability(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false

        val tempAlias = "strongbox_test_key"
        return try {
            val purposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            val spec = KeyGenParameterSpec.Builder(tempAlias, purposes)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(true)
                .build()

            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            keyGen.init(spec)
            keyGen.generateKey()

            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            ks.deleteEntry(tempAlias)
            true
        } catch (e: Exception) {
            Log.d(TAG, "StrongBox check failed: ${e.message}")
            false
        }
    }

    // ─── 鍵の保存 ─────────────────────────────────────────────

    /**
     * 秘密鍵を Keystore AES 鍵で暗号化し SharedPreferences に保存する。
     *
     * @param privateKeyBytes 32バイトの secp256k1 秘密鍵
     * @param publicKeyHex    対応する公開鍵 (hex)
     * @return 暗号化に使った Cipher (生体認証バインドなら BiometricPrompt で使う)、
     *         生体認証不要なら null
     */
    fun storeKey(privateKeyBytes: ByteArray, publicKeyHex: String) {
        try {
            val secretKey = getKeystoreSecretKey()
                ?: throw IllegalStateException("Keystore key not found. Call generateKeystoreKey() first.")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)

            val encrypted = cipher.doFinal(privateKeyBytes)
            val iv = cipher.iv

            prefs.edit()
                .putString(PREF_ENCRYPTED_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(PREF_PUBKEY, publicKeyHex)
                .apply()

            // メモリにもロード
            zeroize()
            decryptedKey = privateKeyBytes.copyOf()

            Log.d(TAG, "Key stored securely (pubkey=${publicKeyHex.take(12)}...)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store key", e)
            throw e
        }
    }

    /**
     * 生体認証必須の場合に、BiometricPrompt 用の Cipher を取得する。
     * 認証成功後、この Cipher を unlockKey() に渡す。
     */
    fun getCipherForDecryption(): Cipher? {
        return try {
            val secretKey = getKeystoreSecretKey() ?: return null
            val ivBase64 = prefs.getString(PREF_IV, null) ?: return null
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            cipher
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create cipher for decryption", e)
            null
        }
    }

    /**
     * BiometricPrompt 認証済みの Cipher で秘密鍵を復号し、メモリにロードする。
     */
    fun unlockKey(cipher: Cipher): Boolean {
        return try {
            val encryptedBase64 = prefs.getString(PREF_ENCRYPTED_KEY, null) ?: return false
            val encrypted = Base64.decode(encryptedBase64, Base64.NO_WRAP)

            zeroize()
            decryptedKey = cipher.doFinal(encrypted)
            Log.d(TAG, "Key unlocked via biometric")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unlock key", e)
            false
        }
    }

    /**
     * 生体認証不要モード: 直接復号してメモリにロードする。
     * Keystore 鍵が生体認証バインドされていない場合に使用。
     */
    fun unlockKeyDirect(): Boolean {
        return try {
            val cipher = getCipherForDecryption() ?: return false
            unlockKey(cipher)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unlock key directly", e)
            false
        }
    }

    // ─── メモリ上の鍵アクセス ──────────────────────────────────

    /**
     * メモリ上の復号済み秘密鍵を ByteArray のコピーとして取得。
     * 使用後は呼び出し側で `ByteArray.fill(0)` すること。
     */
    fun getKeyBytes(): ByteArray? {
        return decryptedKey?.copyOf()
    }

    /**
     * メモリ上の復号済み秘密鍵を一時的に hex 文字列に変換して取得。
     * rust-nostr SDK への受け渡し等、String が必要な箇所で使用。
     * 呼び出し側は参照を長期保持しないこと。
     */
    fun getKeyHexTemporary(): String? {
        val bytes = decryptedKey ?: return null
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 鍵がメモリ上にアンロック済みか。
     */
    fun isUnlocked(): Boolean = decryptedKey != null

    // ─── メモリ消去 ───────────────────────────────────────────

    /**
     * メモリ上の秘密鍵をゼロフィルして破棄する。
     * Web 版 `clearAllPrivateKeys()` に対応。
     */
    fun zeroize() {
        decryptedKey?.fill(0)
        decryptedKey = null
    }

    // ─── 永続ストレージ照会 ───────────────────────────────────

    /**
     * SharedPreferences に暗号化された秘密鍵が保存されているか。
     */
    fun hasStoredKey(): Boolean {
        return prefs.getString(PREF_ENCRYPTED_KEY, null) != null
    }

    /**
     * 保存済みの公開鍵 hex を取得。
     */
    fun getStoredPublicKeyHex(): String? {
        return prefs.getString(PREF_PUBKEY, null)
    }

    /**
     * Keystore 鍵が生体認証バインドされているか。
     */
    fun isBiometricBound(): Boolean {
        return prefs.getBoolean(PREF_BIOMETRIC_BOUND, false)
    }

    // ─── 全削除 ──────────────────────────────────────────────

    /**
     * Keystore 鍵 + SharedPreferences + メモリ上の鍵をすべて削除。
     */
    fun deleteAll() {
        zeroize()

        // Keystore 鍵を削除
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                keyStore.deleteEntry(KEYSTORE_ALIAS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete keystore entry", e)
        }

        // SharedPreferences をクリア
        prefs.edit().clear().apply()
        Log.d(TAG, "All key material deleted")
    }

    // ─── マイグレーション ────────────────────────────────────

    /**
     * 旧 EncryptedSharedPreferences から秘密鍵を移行する。
     * 成功したら旧データを削除する。
     *
     * @return true: 移行成功または不要、false: 移行失敗
     */
    fun migrateFromLegacy(legacyPrefs: io.nurunuru.app.data.prefs.AppPreferences): Boolean {
        // 既に移行済み
        if (prefs.getBoolean(PREF_MIGRATED, false)) return true

        // 旧データがなければスキップ
        @Suppress("DEPRECATION")
        val legacyKeyHex = legacyPrefs.privateKeyHex ?: return true
        val legacyPubkey = legacyPrefs.publicKeyHex ?: return true

        return try {
            // hex → ByteArray
            val keyBytes = hexToBytes(legacyKeyHex)
            if (keyBytes == null || keyBytes.size != 32) {
                Log.e(TAG, "Invalid legacy key format")
                return false
            }

            // Keystore 鍵を生成 (マイグレーション時は生体認証なし → 次回起動時に有効化)
            generateKeystoreKey(requireBiometric = false)

            // 暗号化して保存
            storeKey(keyBytes, legacyPubkey)

            // 旧データを消去
            legacyPrefs.clearPrivateKey()

            prefs.edit().putBoolean(PREF_MIGRATED, true).apply()

            // ByteArray をゼロ化
            keyBytes.fill(0)

            Log.d(TAG, "Migration from legacy storage completed")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed", e)
            false
        }
    }

    // ─── 内部ユーティリティ ──────────────────────────────────

    private fun getKeystoreSecretKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
        return entry?.secretKey
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
