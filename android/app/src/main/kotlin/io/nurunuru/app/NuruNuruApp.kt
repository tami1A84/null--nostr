package io.nurunuru.app

import android.app.Application
import android.util.Log
import io.nurunuru.app.data.ExternalSigner
import io.nurunuru.app.data.NostrClient
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.RecommendationEngine
import io.nurunuru.app.data.cache.NostrCache
import io.nurunuru.app.data.prefs.AppPreferences
import uniffi.nurunuru.initEngine

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
        if (prefs.isExternalSigner && pubkey != null) {
            try {
                val signer = ExternalSigner.apply { setCurrentUser(pubkey) }
                prewarmedNostrClient = NostrClient(
                    context = this,
                    relays = prefs.relays.toList(),
                    signer = signer
                ).also { it.connect() }
                Log.d("NuruNuruApp", "Pre-warmed NostrClient for external signer")
            } catch (e: Exception) {
                Log.w("NuruNuruApp", "Pre-warm failed (non-fatal): ${e.message}")
            }
        }
    }
}
