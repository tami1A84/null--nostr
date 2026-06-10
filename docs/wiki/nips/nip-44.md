# NIP-44: Versioned Encryption

## Summary

NIP-44 is the preferred encrypted payload primitive for NIP-17 DMs, Web NIP-46 remote signing requests, and private list/encryption helpers where supported. iOS NIP-46 signer support is removed by ADR-0023.

## Current behavior

- Web uses NIP-44 for encrypted DMs and NIP-46 request/response encryption.
- Android internal/external signers include NIP-44 encrypt/decrypt APIs.
- Legacy iOS NIP-46 code used NIP-44-encrypted request/response payloads, but the iOS signer path is removed by ADR-0023.
- Rust FFI exposes NIP-44 encryption/decryption helpers.
- Native Talk's main MLS payload encryption is Marmot/MLS, not simply NIP-44 DMs.

## Platform notes

### Android

- `InternalSigner.kt` and `ExternalSigner.kt` support NIP-44 APIs.
- NIP-55/Amber external signing may supply encryption support depending on signer capability.

### iOS

- `ExternalSigner.swift` is legacy NIP-46 removal/migration debt after ADR-0023.
- Internal signing/key storage remains Keychain-backed; avoid logging encrypted session secrets.

### Web

- `lib/nostr.js` contains NIP-44 encrypt/decrypt helpers with NIP-04 fallback.
- `lib/nip46.js` encrypts NIP-46 payloads with NIP-44.

### Rust

- `nurunuru-ffi/src/lib.rs` exposes NIP-44 encrypt/decrypt bridge methods.

## Source references

- `android/app/src/main/kotlin/io/nurunuru/app/data/InternalSigner.kt`
- `android/app/src/main/kotlin/io/nurunuru/app/data/ExternalSigner.kt`
- `ios/NuruNuru/Data/ExternalSigner.swift`
- `lib/nostr.js`
  - `encryptNip44()`, `decryptNip44()`
- `lib/nip46.js`
  - NIP-44 request encryption helpers
- `rust-engine/nurunuru-ffi/src/lib.rs`

## Related pages

- [[nip-04]]
- [[nip-17]]
- [[nip-46]]
- [[features/talk]]

## Open questions

- Platform-specific signer capabilities should be tested before assuming every external signer can encrypt/decrypt NIP-44 payloads.
