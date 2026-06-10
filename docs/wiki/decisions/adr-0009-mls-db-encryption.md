# ADR-0009: MLS storage DB is encrypted at rest with SQLCipher

## Status

`Accepted` (2026-05-22) — Android verified live on device, iOS bindings + build succeeded.

## Amendment — 2026-06-09 iOS signer update

ADR-0023 removes the iOS NIP-46 signer path. The random per-pubkey MLS DB key derivation described here remains relevant for Android Amber and for iOS non-nsec/read-only fallback cases such as Passkey/Nosskey sessions without an app-held nsec. References to iOS NIP-46 in this ADR are historical.

## Context

Issue #181 reported that the Marmot MLS SQLite storage was plaintext on
disk on both Android and iOS. The file (`nostrdb_ndb_mls.sqlite3` on
Android, `nurunuru_ndb_mls.sqlite3` on iOS) contained:

- MLS signature keys (long-term group identity)
- MLS encryption keys (current epoch)
- Epoch key pairs (forward secrecy state)
- Pending KeyPackages (used to invite a new identity into existing groups)

Anyone with file-system access — rooted/jailbroken device, USB backup
extraction, or forensic image of the device — could read all group
material and impersonate any of the user's MLS identities going forward.

The pre-#181 code path went through `NuruNuruClient(secretKeyHex:)` (and
`newReadOnly(pubkeyHex:)` on the external-signer path). Internally
`bind_mls_for_pubkey` silently dropped to the unencrypted code path when
no key had been set, so there was no startup-time error to surface in UI.

The fix has to satisfy:

- No forensic recovery of key material from the device after-the-fact.
- Continued ability for users to back up their identity via the **nsec**
  alone (no extra key material the user has to manage).
- For non-nsec signer sessions (Amber on Android, legacy NIP-46 bunker on iOS before ADR-0023, and Passkey/Nosskey read-only fallback cases) where the nsec is never
  exposed to this process, a different derivation strategy is needed.
- Backwards compatibility: existing installs have a plaintext DB on disk
  that the new SQLCipher ctor cannot open (header mismatch).

## Decision

1. **Encrypt the MLS SQLite DB with SQLCipher 4.x** using a 32-byte raw
   key supplied at engine bind time. `cipher_compatibility = 4`,
   `temp_store = MEMORY`. Key transport from app → Rust via FFI as 32
   bytes; engine validates length before SQLCipher bind.

2. **Two derivation paths**:
   - **Internal signer** (app holds nsec): deterministic HKDF-SHA256 over
     the nsec with `info = "mdk-sqlite-db-key"` and
     `salt = "io.nurunuru.mdk.v1"`. No additional persistence — the key
     is regenerable from the nsec.
   - **External / non-nsec signer** (Amber on Android, legacy NIP-46 bunker on iOS before ADR-0023, and Passkey/Nosskey read-only fallback cases): random
     32 bytes via `SecRandomCopyBytes` / `SecureRandom`, scoped by pubkey
     hex, persisted in Keychain
     (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`) on iOS or
     `EncryptedSharedPreferences` rooted in Android Keystore on Android.

3. **Engine refuses to silently degrade**: when the caller supplied a key
   and SQLCipher bind fails, `bind_mls_for_pubkey` returns hard error
   instead of `mls = None`. FFI exposes `mls_is_encrypted() -> Option<bool>`
   and the app refuses to expose an `MlsFFIBridge` unless this returns
   `Some(true)` after construction.

4. **Legacy plaintext DBs are auto-purged** on every launch via
   content-based detection (`"SQLite format 3\u0000"` magic match), not a
   one-shot migration flag. Path is routed through FFI `mls_db_path_for()`
   so Kotlin / Swift cannot drift from the engine's path format.

5. **Backup-safe by default**: Android external-key prefs file excluded
   from Auto Backup via `backup_rules.xml`. iOS DB + WAL + SHM marked
   `isExcludedFromBackup`. Keychain entries on iOS are `ThisDeviceOnly`.

6. **Key buffer zeroization** after FFI hand-off: Swift via
   `Data.resetBytes(in:)` taken `inout` (the previous `var z = bytes`
   pattern is a no-op copy); Kotlin via `dbKey.fill(0)` in `finally`.

## Alternatives Considered

- **`SQLITE_HAS_CODEC` via SEE (proprietary)**: rejected — not
  open-source, license cost, distribution friction.
- **Whole-DB encryption at the FS layer (CryptKeeper / iOS Data
  Protection only)**: rejected — `NSFileProtectionComplete` would lock
  out background MLS message processing while device is locked, and
  Android's FBE depends on user PIN policy we cannot rely on.
- **Argon2id over a user-chosen passphrase**: rejected for v1 — UX cost
  (user must remember a separate password); SQLCipher's PBKDF2 is
  bypassed when raw 32-byte keys are supplied which is acceptable because
  the input is already high-entropy (HKDF output or CSPRNG).
- **One key for all accounts**: rejected — leaks group membership across
  identities on a shared device; pubkey-scoped keys are mandatory.
- **Flag-based migration ("ran_v1_purge=true" in prefs)**: rejected
  (issue #181 B2). A future regression that reintroduces plaintext can't
  be auto-detected; users would be silently locked out. Content-based
  detection on every launch is idempotent and self-healing.

## Why this fits NuruNuru

- Maps to [[../culture/principles|五箇条]] "鍵を守る" — private key
  material and group identity state must never be readable off-device.
- Maps to [[../culture/four-freedoms|プライバシーの自由]] — Talk groups
  are end-to-end encrypted on the wire; storing them in cleartext on
  disk negates that guarantee.
- Aligns with [[../culture/not-doing|やらないことリスト]] item "鍵を
  process 外に出さない" — external-signer keys never enter Keychain in
  cleartext on the wire; they're random 32-byte values generated locally
  and bound to the device.

## Consequences

**Good:**
- All MLS key material is encrypted at rest with a 32-byte key derived
  per-account.
- Legacy plaintext DBs are auto-detected and purged — no manual user
  action required.
- Engine guard rail (`mls_is_encrypted()` assertion) prevents future
  regressions from silently disabling encryption.
- Threat surface shrinks meaningfully on lost/stolen devices.

**Bad / technical debt:**
- External/non-nsec signer users (Amber, legacy NIP-46 before ADR-0023, Passkey/Nosskey fallback cases) will lose their MLS group state
  on factory reset or reinstall — the device-bound random key is gone.
  This must be communicated in release notes (issue #181 M6 — pending).
- One-time data loss for all existing users on the first patched build —
  the plaintext DB is purged, MLS groups must be rejoined via fresh
  Welcomes. Documented in CHANGELOG (pending).
- HKDF(nsec) path means that anyone who obtains the nsec can decrypt the
  DB forever (no forward secrecy of disk state across nsec compromise).
  This is consistent with the rest of the app — the nsec already grants
  full identity control — but worth restating in the threat model.
- iOS Keychain `…ThisDeviceOnly` accessibility means the key is
  unavailable in the brief window between boot and first unlock. App
  layer must defer `ensureMlsClient()` until after first unlock, which
  matches current lazy-init semantics.

**Future revisit triggers:**
- MDK upstream adopts a different storage encryption scheme.
- Users complain about losing groups on reinstall → consider an optional
  user-managed backup blob (exported HKDF-derivable key alongside nsec).
- A second high-value secret moves into the same SQLite DB (e.g.
  bookmarks state) — may want to split DBs by sensitivity tier.

## Source references

- `rust-engine/nurunuru-core/src/mls.rs`
- `rust-engine/nurunuru-core/src/engine.rs`
- `rust-engine/nurunuru-ffi/src/lib.rs`
- `rust-engine/nurunuru-napi/src/lib.rs`
- `android/app/src/main/kotlin/io/nurunuru/app/data/MlsDbKeyStore.kt`
- `android/app/src/main/kotlin/io/nurunuru/app/data/MlsLegacyMigration.kt`
- `ios/NuruNuru/Data/MlsDbKeyStore.swift`
- `ios/NuruNuru/Data/MlsLegacyMigration.swift`
- `ios/NuruNuru/Data/NuruNuruFFILiveClient.swift`
- [[../features/mls-db-encryption]]
- [[adr-0002-native-talk-uses-marmot-mls]]
- [[adr-0003-ios-external-signing-uses-nip46]]
- GitHub issue `tami1A84/null--nostr#181`
