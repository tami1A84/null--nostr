package io.nurunuru.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import uniffi.nurunuru.deriveMlsDbKeyFromSecret

/**
 * Issue #181: provides the 32-byte SQLCipher key that the Rust core uses to
 * encrypt `nostrdb_ndb_mls.sqlite3` on disk.
 *
 * Two key derivation paths:
 *
 * 1. **Internal signer** — caller already holds the nsec hex; we derive
 *    deterministically via HKDF-SHA256 (FFI helper). No persistence needed:
 *    the key is regenerable from the nsec, so it survives reinstall as
 *    long as the user has their nsec backed up.
 *
 * 2. **External signer (Amber / NIP-46)** — no nsec is ever exposed to
 *    this process. We generate a random 32-byte secret, scope it by
 *    pubkey hex, and store it in `EncryptedSharedPreferences` backed by
 *    a `MasterKey` rooted in Android Keystore. Stronger device-binding,
 *    at the cost of being unrecoverable after factory reset.
 *
 * **Threading**: `getOrCreateExternalKey` is guarded by an in-process
 * lock (`LOCK`) so concurrent first-launch threads cannot generate two
 * different random keys and race on commit (issue #181 B4).
 */
object MlsDbKeyStore {

    /** App-scope salt for HKDF derivation. Bump suffix if the scheme changes. */
    const val APP_SALT = "io.nurunuru.mdk.v1"

    private const val TAG = "MlsDbKeyStore"
    private const val PREFS_NAME = "nuru_mls_keystore"
    private const val PREF_PREFIX_EXTERNAL_KEY = "external_mls_db_key_"

    /** Synchronizes external-key generation across threads (issue #181 B4). */
    private val LOCK = Any()

    // ─── Internal signer (HKDF) ───────────────────────────────────────

    /**
     * Deterministic SQLCipher key for the internal-signer path.
     * Equivalent to calling `derive_mls_db_key(nsec_bytes, APP_SALT)` in Rust.
     *
     * @param secretKeyHex 64-char nsec hex (no `nsec1` prefix).
     */
    fun deriveInternalKey(secretKeyHex: String): ByteArray {
        return deriveMlsDbKeyFromSecret(secretKeyHex, APP_SALT)
    }

    // ─── External signer (random + keystore) ──────────────────────────

    /**
     * Returns the cached 32-byte SQLCipher key for the given pubkey,
     * generating + persisting a new random one on first call.
     *
     * `commit()` (not `apply()`) is used so the bytes are durable on disk
     * before the caller hands the key to the Rust ctor; an `apply()`-and-
     * crash sequence could leave the prefs without the key while the
     * encrypted DB exists, making the DB unrecoverable.
     *
     * @param pubkeyHex 64-char lowercase hex pubkey.
     */
    fun getOrCreateExternalKey(context: Context, pubkeyHex: String): ByteArray {
        require(pubkeyHex.length == 64) {
            "MlsDbKeyStore.getOrCreateExternalKey: pubkeyHex must be 64-char hex"
        }
        synchronized(LOCK) {
            val prefs = openPrefs(context)
            val prefKey = PREF_PREFIX_EXTERNAL_KEY + pubkeyHex.lowercase()
            val existing = prefs.getString(prefKey, null)
            if (existing != null) {
                val bytes = Base64.decode(existing, Base64.NO_WRAP)
                if (bytes.size == 32) return bytes
                Log.w(TAG, "Stored external MLS key has wrong length ${bytes.size}; regenerating")
            }
            val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val ok = prefs.edit()
                .putString(prefKey, Base64.encodeToString(fresh, Base64.NO_WRAP))
                .commit()
            if (!ok) {
                // Don't return a key that isn't persisted — the encrypted DB
                // we'd create with it would become unrecoverable.
                throw IllegalStateException(
                    "MlsDbKeyStore: failed to persist external MLS DB key"
                )
            }
            return fresh
        }
    }

    /**
     * Removes the pubkey-scoped external key on logout. Called from
     * AuthViewModel.logout() *before* purging the MLS SQLite file.
     */
    fun clearExternalKey(context: Context, pubkeyHex: String) {
        synchronized(LOCK) {
            val prefs = openPrefs(context)
            val prefKey = PREF_PREFIX_EXTERNAL_KEY + pubkeyHex.lowercase()
            prefs.edit().remove(prefKey).commit()
        }
    }

    /** Removes ALL external keys. Used on full logout / wipe. */
    fun clearAllExternalKeys(context: Context) {
        synchronized(LOCK) {
            openPrefs(context).edit().clear().commit()
        }
    }

    private fun openPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
