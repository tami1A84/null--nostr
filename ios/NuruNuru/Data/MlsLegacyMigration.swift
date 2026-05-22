import Foundation

#if NURUNURU_FFI_AVAILABLE
import NuruNuruFFILib
#endif

/// Issue #181: detect + purge legacy plaintext MLS SQLite databases.
///
/// Mirror of Android `MlsLegacyMigration.kt`. Before #181 the iOS app called
/// `MlsFFILiveClient(secretKeyHex:dbPath:)` which silently dropped to the
/// unencrypted code path inside `bind_mls_for_pubkey`, producing a plaintext
/// `nurunuru_ndb_mls.sqlite3` whose first 16 bytes are the literal ASCII
/// `"SQLite format 3\u{0}"`. Such a file is unreadable by SQLCipher and must
/// be purged before the new encrypted ctor runs, otherwise
/// `MlsManager::new_with_key` returns `Error::NotADatabase`, the engine
/// propagates an error, and Talk silently breaks.
///
/// **Content-based detection, not flag-based** (issue #181 B2): we check the
/// file's first 16 bytes on every startup, not a one-shot "migrated" flag.
/// That way any future regression that reintroduces a plaintext DB will be
/// caught + repaired on next launch instead of being permanently locked out.
///
/// **Path resolution** (issue #181 B1): we route the db path through the FFI
/// helper `mlsDbPathFor(...)` rather than reproducing `"{}_mls.sqlite3"`
/// locally, to prevent cross-platform path drift.
///
/// **Run timing** (issue #181 M5): call this **before** `initEngine()` /
/// before any `MlsFFILiveClient` constructor runs, so no Rust file handle
/// holds the legacy DB open while we try to unlink it.
///
/// **Backup exclusion** (issue #181 M6): MLS DB files are excluded from
/// iCloud + iTunes backup via `URLResourceKey.isExcludedFromBackupKey`, so a
/// restored device cannot end up with an encrypted DB it has no key for.
enum MlsLegacyMigration {

    /// First 16 bytes of any plaintext SQLite 3 database.
    private static let plaintextMagic: [UInt8] = [
        0x53, 0x51, 0x4c, 0x69, 0x74, 0x65, 0x20, 0x66,
        0x6f, 0x72, 0x6d, 0x61, 0x74, 0x20, 0x33, 0x00
    ]

    /// If the MLS DB at the canonical path is detected as plaintext, remove
    /// it and its WAL/SHM/journal sidecars. Idempotent.
    ///
    /// - Parameter dbDirectoryPath: The engine `db_path` value (e.g.
    ///   `<AppSupport>/nurunuru_ndb`), NOT the `_mls.sqlite3` file itself.
    /// - Returns: `true` when a plaintext DB was purged this call.
    @discardableResult
    static func purgePlaintextDbIfDetected(dbDirectoryPath: String) -> Bool {
#if NURUNURU_FFI_AVAILABLE
        // Source of truth for the DB path = the FFI helper. Mirrors the
        // exact format the Rust engine opens (`"{db_path}_mls.sqlite3"`),
        // so we cannot drift if the suffix ever changes.
        let dbFile = mlsDbPathFor(dbPath: dbDirectoryPath)
#else
        let dbFile = "\(dbDirectoryPath)_mls.sqlite3"
#endif

        let fm = FileManager.default
        guard fm.fileExists(atPath: dbFile) else {
            AppLogger.log("MlsLegacyMigration", "no MLS DB at \(dbFile); nothing to purge")
            return false
        }

        let attrs = try? fm.attributesOfItem(atPath: dbFile)
        let size = (attrs?[.size] as? NSNumber)?.intValue ?? 0
        if size < 16 {
            AppLogger.log("MlsLegacyMigration", "MLS DB file present but shorter than 16 bytes; treating as corrupt and purging")
            return purgeAll(dbFile: dbFile, reason: "short-file")
        }

        guard let header = readFirst16Bytes(path: dbFile) else {
            AppLogger.log("MlsLegacyMigration", "could not read MLS DB header; leaving untouched")
            return false
        }

        if header == plaintextMagic {
            AppLogger.log("MlsLegacyMigration", "Detected legacy plaintext MLS DB at \(dbFile) — purging (issue #181)")
            return purgeAll(dbFile: dbFile, reason: "plaintext-detected")
        }

        // Header is salt/IV (SQLCipher) or something we don't recognise;
        // either way it's NOT plaintext SQLite, so leave it for the
        // encrypted ctor to handle.
        return false
    }

    /// Mark the MLS DB + WAL/SHM as excluded from iCloud/iTunes backup. Call
    /// this after the encrypted ctor has succeeded and the file exists on
    /// disk. Safe to call repeatedly.
    static func excludeMlsDbFromBackup(dbDirectoryPath: String) {
#if NURUNURU_FFI_AVAILABLE
        let dbFile = mlsDbPathFor(dbPath: dbDirectoryPath)
#else
        let dbFile = "\(dbDirectoryPath)_mls.sqlite3"
#endif
        for name in [dbFile, "\(dbFile)-wal", "\(dbFile)-shm"] {
            guard FileManager.default.fileExists(atPath: name) else { continue }
            var url = URL(fileURLWithPath: name)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            do {
                try url.setResourceValues(values)
            } catch {
                AppLogger.log("MlsLegacyMigration", "failed to mark \(name) excluded-from-backup: \(error)")
            }
        }
    }

    // MARK: - Internals

    private static func readFirst16Bytes(path: String) -> [UInt8]? {
        guard let handle = FileHandle(forReadingAtPath: path) else { return nil }
        defer { try? handle.close() }
        do {
            let data: Data?
            if #available(iOS 13.4, *) {
                data = try handle.read(upToCount: 16)
            } else {
                data = handle.readData(ofLength: 16)
            }
            guard let bytes = data, bytes.count == 16 else { return nil }
            return [UInt8](bytes)
        } catch {
            return nil
        }
    }

    private static func purgeAll(dbFile: String, reason: String) -> Bool {
        let targets = [
            dbFile,
            "\(dbFile)-wal",
            "\(dbFile)-shm",
            "\(dbFile)-journal"
        ]
        var ok = true
        for path in targets {
            guard FileManager.default.fileExists(atPath: path) else { continue }
            do {
                try FileManager.default.removeItem(atPath: path)
            } catch {
                ok = false
                AppLogger.log("MlsLegacyMigration", "failed to delete \(path): \(error)")
            }
        }
        AppLogger.log("MlsLegacyMigration", "purged plaintext DB (reason=\(reason), success=\(ok))")
        return ok
    }
}
