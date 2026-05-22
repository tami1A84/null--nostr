package io.nurunuru.app.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import uniffi.nurunuru.mlsDbPathFor

/**
 * Issue #181: detect + purge legacy plaintext MLS SQLite databases.
 *
 * Before #181 the Android app called `NuruNuruClient(keyHex)` which
 * silently dropped to the unencrypted code path inside `bind_mls_for_pubkey`,
 * producing a plaintext `nostrdb_ndb_mls.sqlite3` whose first 16 bytes are
 * the literal ASCII `"SQLite format 3\u0000"`. Such a file is unreadable by
 * SQLCipher and must be purged before the new encrypted ctor runs, otherwise
 * `MlsManager::new_with_key` returns `Error::NotADatabase`, the engine
 * propagates an error, and Talk silently breaks.
 *
 * **Content-based detection, not flag-based** (issue #181 B2): we check the
 * file's first 16 bytes on every startup, not a one-shot "migrated" flag.
 * That way any future regression that reintroduces a plaintext DB will be
 * caught + repaired on next launch instead of being permanently locked out.
 *
 * **Run timing** (issue #181 M5): call this **before** `initEngine()` /
 * before any `NuruNuruClient` constructor runs, so no Rust file handle
 * holds the legacy DB open while we try to unlink it.
 */
object MlsLegacyMigration {

    private const val TAG = "MlsLegacyMigration"

    /** First 16 bytes of any plaintext SQLite 3 database. */
    private val PLAINTEXT_MAGIC = byteArrayOf(
        0x53, 0x51, 0x4c, 0x69, 0x74, 0x65, 0x20, 0x66,
        0x6f, 0x72, 0x6d, 0x61, 0x74, 0x20, 0x33, 0x00
    )

    /**
     * If `<filesDir>/nostrdb_ndb_mls.sqlite3` is detected as plaintext,
     * remove it and its WAL/SHM/journal sidecars. Idempotent.
     *
     * @return true when a plaintext DB was purged this call (caller may use
     *         this for telemetry / one-shot user notice). false means either
     *         the file is absent, already encrypted, or the read failed.
     */
    fun purgePlaintextDbIfDetected(context: Context): Boolean {
        // Source of truth for the DB path = the FFI helper. Mirrors the
        // exact format the Rust engine opens (`"{db_path}_mls.sqlite3"`),
        // so we cannot drift if the suffix ever changes.
        val dbPath = mlsDbPathFor("${context.filesDir.absolutePath}/nostrdb_ndb")
        val dbFile = File(dbPath)
        if (!dbFile.exists()) return false
        if (dbFile.length() < 16L) {
            Log.w(TAG, "MLS DB file present but shorter than 16 bytes; treating as corrupt and purging")
            return purgeAll(dbFile, reason = "short-file")
        }

        val header = ByteArray(16)
        try {
            RandomAccessFile(dbFile, "r").use { raf -> raf.readFully(header) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read MLS DB header (${e.message}); leaving untouched")
            return false
        }

        return if (header.contentEquals(PLAINTEXT_MAGIC)) {
            Log.w(TAG, "Detected legacy plaintext MLS DB at $dbPath — purging (issue #181)")
            purgeAll(dbFile, reason = "plaintext-detected")
        } else {
            // Header is salt/IV (SQLCipher) or something we don't recognise;
            // either way it's NOT plaintext SQLite, so leave it for the
            // encrypted ctor to handle.
            false
        }
    }

    private fun purgeAll(dbFile: File, reason: String): Boolean {
        val parent = dbFile.parentFile ?: return false
        val name = dbFile.name
        val targets = listOf(
            dbFile,
            File(parent, "$name-wal"),
            File(parent, "$name-shm"),
            File(parent, "$name-journal")
        )
        var ok = true
        for (t in targets) {
            try {
                if (t.exists() && !t.delete()) {
                    ok = false
                    Log.w(TAG, "Failed to delete ${t.name}")
                }
            } catch (e: Exception) {
                ok = false
                Log.w(TAG, "Exception deleting ${t.name}: ${e.message}")
            }
        }
        Log.i(TAG, "MlsLegacyMigration: purged plaintext DB (reason=$reason, success=$ok)")
        return ok
    }
}
