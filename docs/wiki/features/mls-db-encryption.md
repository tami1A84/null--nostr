# MLS DB Encryption (Issue #181)

## Summary

The Marmot MLS storage SQLite database (`<filesDir>/nostrdb_ndb_mls.sqlite3`
on Android, `<AppSupport>/nurunuru_ndb_mls.sqlite3` on iOS) is encrypted
with **SQLCipher 4.x** using a 32-byte raw key. Before issue #181 this DB
was plaintext on disk; signature keys, encryption keys, epoch key pairs,
and KeyPackages were readable to anyone with file-system access (rooted /
jailbroken device, USB backup, forensic image).

## Current behavior

### Key material

- **32-byte raw key** passed to SQLCipher via `PRAGMA key = "x'<64-hex>';"`.
- `cipher_compatibility = 4`, `temp_store = MEMORY` (set by MDK).
- Two derivation paths, selected by signer type:

| Signer | Derivation | Persistence | Recovery |
|---|---|---|---|
| Internal (nsec held by app) | HKDF-SHA256 over the nsec, info=`"mdk-sqlite-db-key"`, salt=`"io.nurunuru.mdk.v1"` | none (regenerable) | Survives reinstall as long as the user has their nsec backed up. |
| External (Amber / NIP-46) | `SecRandomCopyBytes` / `SecureRandom` 32 bytes, scoped by pubkey hex | Android: `EncryptedSharedPreferences` + `MasterKey` (AES256_GCM); iOS: Keychain with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` | Lost on factory reset / app reinstall — keys are device-bound by design. |

### Engine guard rails

- `NuruNuruClient::new_with_mls_db_key()` / `new_read_only_with_mls_db_key()`
  validate the key is exactly 32 bytes before binding.
- `bind_mls_for_pubkey()` returns hard error (not silent `mls = None`) when
  the caller supplied a key and SQLCipher bind fails (issue #181 B5).
- FFI exposes `mls_is_encrypted() -> Option<bool>` so the app layer can
  assert encryption state at runtime (issue #181 B7).
- Both `MlsFFILiveClient` (iOS) and Android `NostrClient` call this guard
  immediately after the encrypted ctor and refuse to expose an
  `MlsFFIBridge` when the state is not `true` — Talk surfaces an error
  instead of "groups are empty".

### Legacy migration

Pre-#181 builds wrote a plaintext SQLite DB with the magic header
`"SQLite format 3\u0000"` (ASCII, 16 bytes). On every launch the migration
runs **before** `init_engine()`:

1. Resolves the canonical DB path via FFI `mls_db_path_for(db_path)` — the
   only source of truth. The suffix format `"{db_path}_mls.sqlite3"` is
   never reproduced in Kotlin or Swift (issue #181 B1).
2. Reads the file's first 16 bytes (content-based, not flag-based, so any
   future regression is auto-repaired on next launch — issue #181 B2).
3. If the header matches the plaintext magic, purges the DB and its
   `-wal` / `-shm` / `-journal` sidecars.
4. If the header is SQLCipher salt/IV, leaves it for the encrypted ctor.

### Backup posture

- **Android**: external-signer key prefs file (`nuru_mls_keystore`) is
  excluded from Auto Backup via
  `android/app/src/main/res/xml/backup_rules.xml` (issue #181 B6). Without
  this, prefs could be restored to a new device where Keystore master key
  is absent → permanent unreadable backup.
- **iOS**: the MLS DB + WAL + SHM are marked
  `URLResourceValues.isExcludedFromBackup = true` after the encrypted ctor
  succeeds (`MlsLegacyMigration.excludeMlsDbFromBackup`). Keychain entries
  use `…ThisDeviceOnly` so they are never iCloud-backed either.

### Logout / key disposal

- `AuthViewModel.logout()` clears the per-pubkey external key from
  Keychain / EncryptedSharedPreferences **before** clearing
  `prefs.publicKeyHex` (otherwise we lose the scope key). Internal-signer
  path requires no explicit removal — `SecureKeyManager.deleteAll()` /
  `prefs.clear()` drops the nsec which is the root secret.
- In Swift, the key buffer is zeroized via `Data.resetBytes(in:)` taken by
  `inout` from `MlsDbKeyStore` through `MlsFFILiveClient` — the previous
  pattern of `var z = bytes` was a no-op copy (issue #181 B3).
- In Kotlin, `dbKey.fill(0)` runs in a `finally` block after FFI hand-off.

## Platform notes

| Concern | Android | iOS |
|---|---|---|
| DB path | `<filesDir>/nostrdb_ndb_mls.sqlite3` | `<AppSupport>/nurunuru_ndb_mls.sqlite3` |
| External-key store | `EncryptedSharedPreferences` (`nuru_mls_keystore`) | Keychain service `io.nurunuru.app.mls` |
| Backup exclusion | `backup_rules.xml` | `isExcludedFromBackup` + `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` |
| Migration entry point | `NuruNuruApp.onCreate()` (before `initEngine`) | `NostrRepository.ensureMlsClient()` (before `initEngine`, lazy) |
| Concurrency guard | `synchronized(LOCK)` + `commit()` (not `apply()`) | `NSLock` + Keychain `kSecMatchLimitOne` write |
| Logout cleanup | `MlsDbKeyStore.clearExternalKey(...)` before `prefs.clear()` | `MlsDbKeyStore.clearExternalKey(pubkeyHex:)` before `prefs.clear()` |
| Zeroize after FFI | `dbKey.fill(0)` in `finally` | `Data.resetBytes(in:)` via `inout` |

Desktop (`nurunuru-napi`) also exposes `create_with_mls_db_key()` +
`mls_is_encrypted()` (issue #181 M1); the legacy unkeyed ctor remains
only as a deprecated compatibility surface.

## Threat model

| Adversary | Internal-signer (HKDF) | External-signer (random + keystore) |
|---|---|---|
| Casual file-system read on rooted/JB device | Blocked (SQLCipher) | Blocked (SQLCipher) |
| USB backup / forensic image of disk | Blocked (key not in disk image) | Blocked (key in Keychain/Keystore, not in backup) |
| Compromised nsec (e.g. user pasted to phishing site) | DB readable from any clone of the nsec | DB still bound to the lost device — attacker cannot reuse on another device |
| Factory reset | Recoverable from nsec backup | Permanently lost (by design) |
| iCloud / Google account compromise | Same as nsec exposure (DB regenerable) | Keys never leave the device — not in cloud backup |

The internal-signer path inherits the nsec's threat profile: easy
recoverability at the cost of "anyone with the nsec can decrypt the DB
forever". The external-signer path is strictly device-bound by design —
this matches the user model where the nsec never enters the app
(`AGENTS.md`: "Private keys: Keychain only…").

## Verification

A physical Android device was used to verify the encrypted ctor
runtime behavior:

```text
W/MlsLegacyMigration: Detected legacy plaintext MLS DB ... purging (issue #181)
I/MlsLegacyMigration: purged plaintext DB (reason=plaintext-detected, success=true)
I/NostrClient: MLS DB encrypted (SQLCipher) — issue #181 guard OK
```

The DB header changed from the plaintext magic
`53 51 4c 69 74 65 20 66 6f 72 6d 61 74 20 33 00` to SQLCipher random bytes
`21 1d c0 1b 59 f1 4a af 49 94 af 6c dd 30 26 c4`. File size preserved at
258,048 bytes.

iOS xcodebuild for `iPhone 17` simulator returns `BUILD SUCCEEDED` with
the new `MlsDbKeyStore.swift`, `MlsLegacyMigration.swift`, the updated
`MlsFFILiveClient` encrypted inits, and the `MlsFFIBridge.mlsIsEncrypted()`
protocol extension. Runtime device verification is the next step.

## Open questions

- **CI guard** (issue #181 M2): implemented as `scripts/issue-181-guard.mjs`
  (run via `npm run lint:issue-181`). Walks `ios/NuruNuru/` and
  `android/app/src/main/kotlin/` and fails on any reintroduction of the
  unkeyed `NuruNuruClient(secretKeyHex:)` or `NuruNuruClient.newReadOnly(pubkeyHex:)`
  ctors. Skips generated UniFFI bindings under `bindgen/`. Verified by
  negative test (inject 2 violations → 2 reported, exit non-zero) and
  positive sweep (214 files, 141 596 pattern checks, 0 violations on the
  clean tree).
- **Settings UI** (issue #181 M4): deliberately not shipped. Decision noted
  in the v1.5 cycle — exposing an "encrypted ✓" indicator adds UI noise
  without giving the user an actionable signal; the runtime guard already
  hard-fails if the DB is ever unencrypted, so a green checkmark would be
  redundant. Revisit only if a future feature requires per-DB status (e.g.
  multi-account MLS).
- **Release notes** (issue #181 M6): implemented in `CHANGELOG.md`
  ([Unreleased] > Security + Upgrade notes sections) and as a per-channel
  template in [`docs/release-notes/issue-181-mls-db-encryption.md`](../../release-notes/issue-181-mls-db-encryption.md)
  (JP + EN short forms for zapstore / GitHub Release / Google Play /
  TestFlight, plus a support-facing FAQ).

## Source references

- `rust-engine/nurunuru-core/src/mls.rs` — `mls_db_path_for`, `new_with_key`, header test.
- `rust-engine/nurunuru-core/src/engine.rs` — `bind_mls_for_pubkey`, `mls_is_encrypted`.
- `rust-engine/nurunuru-ffi/src/lib.rs` — `new_with_mls_db_key`, `new_read_only_with_mls_db_key`, `mls_db_path_for`, `derive_mls_db_key_from_secret`.
- `rust-engine/nurunuru-napi/src/lib.rs` — `create_with_mls_db_key`, `mls_is_encrypted`.
- `android/app/src/main/kotlin/io/nurunuru/app/data/MlsDbKeyStore.kt`
- `android/app/src/main/kotlin/io/nurunuru/app/data/MlsLegacyMigration.kt`
- `android/app/src/main/kotlin/io/nurunuru/app/data/NostrClient.kt`
- `android/app/src/main/kotlin/io/nurunuru/app/NuruNuruApp.kt`
- `android/app/src/main/kotlin/io/nurunuru/app/viewmodel/AuthViewModel.kt`
- `ios/NuruNuru/Data/MlsDbKeyStore.swift`
- `ios/NuruNuru/Data/MlsLegacyMigration.swift`
- `ios/NuruNuru/Data/NuruNuruFFILiveClient.swift`
- `ios/NuruNuru/Data/NuruNuruFFIBridge.swift`
- `ios/NuruNuru/Data/NostrRepository.swift`
- `ios/NuruNuru/ViewModels/AuthViewModel.swift`
- `scripts/issue-181-guard.mjs` — CI lint guard (M2)
- `package.json` `scripts.lint:issue-181` — npm entry point for the guard
- `CHANGELOG.md` `[Unreleased] > Security` + `Upgrade notes` — user-facing changelog (M6)
- `docs/release-notes/issue-181-mls-db-encryption.md` — per-channel release notes + support FAQ (M6)

## Related pages

- [[talk]]
- [[talk-marmot-mls]]
- [[talk-relays]]
- [[../platforms/rust-engine]]
- [[../platforms/android]]
- [[../platforms/ios]]
- [[../decisions/adr-0009-mls-db-encryption]]
- [[../decisions/adr-0002-native-talk-uses-marmot-mls]]
