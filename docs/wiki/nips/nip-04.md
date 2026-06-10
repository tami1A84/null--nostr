# NIP-04: Legacy Encrypted Direct Messages

## Summary

NIP-04 appears in code as a legacy encryption/signing compatibility path. Newer private messaging and remote signing paths prefer NIP-44/NIP-59/NIP-17 or Marmot MLS depending on platform.

## Current behavior

- Web NIP-44 helpers fall back to NIP-04 when a browser extension lacks NIP-44 support.
- Android internal/external signer APIs include NIP-04 encryption/decryption compatibility.
- Rust FFI exposes NIP-04 encrypt/decrypt functions for callers that still need legacy compatibility.
- Native Talk does not use NIP-04 payloads as the current main message display model.

## Platform notes

### Android

- `InternalSigner.kt` and `ExternalSigner.kt` expose NIP-04 encryption/decryption paths.
- `NostrBrowserApp.kt` bridges `window.nostr` APIs for mini-app/browser use.

### iOS

- iOS signer/bridge files should be checked before adding new NIP-04 UI claims; iOS NIP-46 signer is removed by ADR-0023 and main Talk direction is Marmot MLS.

### Web

- `encryptNip44()` / `decryptNip44()` in `lib/nostr.js` can fall back to NIP-04 through extension APIs.

### Rust

- `nurunuru-ffi/src/lib.rs` exposes legacy encryption methods alongside NIP-44 helpers.

## Source references

- `android/app/src/main/kotlin/io/nurunuru/app/data/InternalSigner.kt`
- `android/app/src/main/kotlin/io/nurunuru/app/data/ExternalSigner.kt`
- `android/app/src/main/kotlin/io/nurunuru/app/ui/miniapps/NostrBrowserApp.kt`
- `lib/nostr.js`
  - `encryptNip44()`, `decryptNip44()` fallback behavior
- `rust-engine/nurunuru-ffi/src/lib.rs`

## Related pages

- [[nip-44]]
- [[nip-17]]
- [[features/talk]]

## Open questions

- Do not expand NIP-04 claims without checking the current signer bridge code on each platform.
