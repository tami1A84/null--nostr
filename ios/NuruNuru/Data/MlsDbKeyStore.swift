import Foundation
import Security

#if NURUNURU_FFI_AVAILABLE
import NuruNuruFFILib
#endif

/// Issue #181: provides the 32-byte SQLCipher key that the Rust core uses to
/// encrypt `<AppSupport>/nurunuru_ndb_mls.sqlite3` on disk.
///
/// Mirror of Android `MlsDbKeyStore.kt`. Two key derivation paths:
///
/// 1. **Internal signer** — caller already holds the nsec hex; we derive
///    deterministically via HKDF-SHA256 (FFI helper). No persistence needed:
///    the key is regenerable from the nsec, so it survives reinstall as long
///    as the user has their nsec backed up.
///
/// 2. **External signer (NIP-46 bunker)** — no nsec is ever exposed to this
///    process. We generate a random 32-byte secret, scope it by pubkey hex,
///    and store it in Keychain with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`.
///    Stronger device-binding, at the cost of being unrecoverable after
///    factory reset (and explicitly NOT iCloud-backed via the
///    `ThisDeviceOnly` accessibility class).
///
/// **Threading**: `getOrCreateExternalKey` is guarded by an in-process
/// `NSLock` so concurrent first-launch threads cannot generate two different
/// random keys and race on the Keychain write (issue #181 B4).
enum MlsDbKeyStore {

    /// App-scope salt for HKDF derivation. Bump suffix if the scheme changes.
    /// Must match Android `MlsDbKeyStore.APP_SALT`.
    static let appSalt = "io.nurunuru.mdk.v1"

    private static let keychainService = "io.nurunuru.app.mls"
    private static let accountPrefixExternalKey = "external_mls_db_key_"

    /// Synchronizes external-key generation across threads (issue #181 B4).
    private static let lock = NSLock()

    // MARK: - Internal signer (HKDF)

    /// Deterministic SQLCipher key for the internal-signer path.
    /// Equivalent to calling `derive_mls_db_key(nsec_bytes, APP_SALT)` in Rust.
    ///
    /// - Parameter secretKeyHex: 64-char nsec hex (no `nsec1` prefix).
    /// - Returns: 32-byte HKDF-SHA256 output as `Data`.
    static func deriveInternalKey(secretKeyHex: String) throws -> Data {
#if NURUNURU_FFI_AVAILABLE
        return try deriveMlsDbKeyFromSecret(secretKeyHex: secretKeyHex, appSalt: appSalt)
#else
        throw MlsDbKeyStoreError.ffiUnavailable
#endif
    }

    // MARK: - External signer (random + Keychain)

    /// Returns the cached 32-byte SQLCipher key for the given pubkey,
    /// generating + persisting a new random one on first call.
    ///
    /// We deliberately use Keychain (NOT UserDefaults) with the most
    /// conservative accessibility class so the key is:
    ///   - never present in iCloud Keychain backups
    ///   - unavailable while the device is locked at first boot
    ///   - wiped on factory reset
    ///
    /// - Parameter pubkeyHex: 64-char lowercase hex pubkey.
    static func getOrCreateExternalKey(pubkeyHex: String) throws -> Data {
        guard pubkeyHex.count == 64 else {
            throw MlsDbKeyStoreError.invalidPubkey
        }
        lock.lock()
        defer { lock.unlock() }

        let account = accountPrefixExternalKey + pubkeyHex.lowercased()

        if let existing = readKeychain(account: account) {
            if existing.count == 32 {
                return existing
            }
            // Wrong-length entry: legacy or corrupt. Delete + regenerate.
            deleteKeychain(account: account)
        }

        var fresh = Data(count: 32)
        let status = fresh.withUnsafeMutableBytes { ptr -> Int32 in
            guard let base = ptr.baseAddress else { return errSecAllocate }
            return Int32(SecRandomCopyBytes(kSecRandomDefault, 32, base))
        }
        guard status == errSecSuccess else {
            throw MlsDbKeyStoreError.randomFailed(status: status)
        }

        // We must NOT return a key that isn't persisted — the encrypted DB
        // we'd create with it would become unrecoverable after process exit.
        try writeKeychain(account: account, data: fresh)
        return fresh
    }

    /// Removes the pubkey-scoped external key. Called from `AuthViewModel.logout()`
    /// *before* `prefs.clear()` so we still have the pubkey to scope by.
    static func clearExternalKey(pubkeyHex: String) {
        lock.lock()
        defer { lock.unlock() }
        let account = accountPrefixExternalKey + pubkeyHex.lowercased()
        deleteKeychain(account: account)
    }

    /// Removes ALL external keys. Used on full wipe.
    static func clearAllExternalKeys() {
        lock.lock()
        defer { lock.unlock() }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService
        ]
        SecItemDelete(query as CFDictionary)
    }

    // MARK: - Keychain helpers

    private static func readKeychain(account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return data
    }

    private static func writeKeychain(account: String, data: Data) throws {
        // Delete existing entry first to avoid `errSecDuplicateItem`.
        deleteKeychain(account: account)
        let attrs: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData as String: data
        ]
        let status = SecItemAdd(attrs as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw MlsDbKeyStoreError.keychainWriteFailed(status: status)
        }
    }

    private static func deleteKeychain(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
    }
}

enum MlsDbKeyStoreError: Error, LocalizedError {
    case ffiUnavailable
    case invalidPubkey
    case randomFailed(status: Int32)
    case keychainWriteFailed(status: Int32)

    var errorDescription: String? {
        switch self {
        case .ffiUnavailable:
            return "MLS FFI is not available (xcframework not linked)"
        case .invalidPubkey:
            return "MLS DB key requires a 64-char hex pubkey"
        case .randomFailed(let status):
            return "SecRandomCopyBytes failed (status=\(status))"
        case .keychainWriteFailed(let status):
            return "Keychain write failed (status=\(status))"
        }
    }
}
