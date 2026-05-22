package io.nurunuru.app

import android.app.Application
import android.util.Log
import io.nurunuru.app.data.ExternalSigner
import io.nurunuru.app.data.MlsLegacyMigration
import io.nurunuru.app.data.NostrClient
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.RecommendationEngine
import io.nurunuru.app.data.cache.NostrCache
import io.nurunuru.app.data.prefs.AppPreferences
import uniffi.nurunuru.initEngine
import java.io.File

class NuruNuruApp : Application() {

    lateinit var prefs: AppPreferences
        private set

    /** Pre-warmed client for external-signer (Amber) users. Reused by MainScreen. */
    var prewarmedNostrClient: NostrClient? = null
        private set

    /** Pre-created cache and engine — avoids SharedPreferences disk I/O on first Compose frame. */
    lateinit var nostrCache: NostrCache
        private set
    lateinit var recommendationEngine: RecommendationEngine
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        nostrCache = NostrCache(this).also { it.applySettings(prefs) }
        recommendationEngine = RecommendationEngine(this)

        // Issue #181: BEFORE the engine ever opens the MLS DB, detect any
        // legacy plaintext `nostrdb_ndb_mls.sqlite3` left by pre-#181
        // builds and purge it. SQLCipher cannot open a plaintext file
        // produced by an older build; without this purge, the encrypted
        // ctor would fail and Talk would silently disable.
        //
        // Content-based check on every launch (not a one-shot flag) so
        // any future regression is caught + repaired automatically.
        try {
            val purged = MlsLegacyMigration.purgePlaintextDbIfDetected(this)
            if (purged) Log.w("NuruNuruApp", "MlsLegacyMigration: plaintext MLS DB purged (issue #181)")
        } catch (e: Exception) {
            Log.e("NuruNuruApp", "MlsLegacyMigration failed", e)
        }

        // Initialise the Rust core database path once at startup.
        // Must happen before any NuruNuruClient is created.
        val dbPath = "${filesDir.absolutePath}/nostrdb_ndb"
        try {
            initEngine(dbPath)
            Log.d("NuruNuruApp", "Rust engine initialised at $dbPath")
        } catch (e: Exception) {
            Log.e("NuruNuruApp", "Failed to initialise Rust engine", e)
        }

        // Pre-warm relay connections for external-signer (Amber) users.
        // Relay WebSocket handshakes begin immediately so the client is ready
        // by the time MainScreen composes and starts fetching data.
        val rawPubkey = prefs.publicKeyHex
        // Normalize npub→hex if stored in bech32 format (migration)
        val pubkey = if (rawPubkey != null) {
            NostrKeyUtils.parsePublicKey(rawPubkey)?.also { hex ->
                if (hex != rawPubkey) {
                    prefs.publicKeyHex = hex
                    Log.d("NuruNuruApp", "Migrated stored pubkey from npub to hex")
                }
            } ?: rawPubkey
        } else null
        if (pubkey == null) {
            // Privacy/account isolation: older builds did not delete local Rust MLS
            // SQLite on logout. If the app starts logged out, purge stale local DBs so
            // the next login cannot see the previous user's Talk history.
            clearLocalRustDatabases()
        }

        if (prefs.isExternalSigner && pubkey != null) {
            try {
                val signer = ExternalSigner.apply { setCurrentUser(pubkey) }
                val startupRelays = prefs.nip65Relays.map { it.url }.ifEmpty { prefs.relays.toList() }
                prewarmedNostrClient = NostrClient(
                    context = this,
                    relays = startupRelays,
                    signer = signer
                ).also { it.connect() }
                Log.d("NuruNuruApp", "Pre-warmed NostrClient for external signer")
            } catch (e: Exception) {
                Log.w("NuruNuruApp", "Pre-warm failed (non-fatal): ${e.message}")
            }
        }
    }

    private fun clearLocalRustDatabases() {
        listOf(
            File(filesDir, "nostrdb_ndb"),
            File(filesDir, "nostrdb_ndb_mls.sqlite3"),
            File(filesDir, "nostrdb_ndb_mls.sqlite3-shm"),
            File(filesDir, "nostrdb_ndb_mls.sqlite3-wal")
        ).forEach { file ->
            try {
                if (file.exists()) {
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                }
            } catch (e: Exception) {
                Log.w("NuruNuruApp", "Failed to delete local Rust DB ${file.name}: ${e.message}")
            }
        }
    }
}
