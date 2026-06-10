# NIP-46: Nostr Connect

## Summary

NIP-46 is Nostr Connect / remote signing. In null--nostr it remains relevant to Web helpers and historical iOS code, but **the iOS app signer path is removed by ADR-0023**.

## Current behavior

- Web implements a NIP-46 client/session flow in `lib/nip46.js`.
- iOS still has audited legacy code in `ExternalSigner.swift`, `AuthViewModel`, `LoginView`, `NostrRepository`, and `AppPreferences.isExternalSigner`; Phase 1 should remove or migrate these paths.
- iOS must not expose NIP-46 as a login/signer option after ADR-0023 implementation.
- Android uses NIP-55/Amber for its native external signer path rather than NIP-46.
- Rust FFI/core supports unsigned event creation and raw signed event publishing, but that does not imply iOS NIP-46 signer support.

## Platform notes

### iOS

- NIP-46 signer is removed from the iOS app path.
- Do not replace it with NIP-55/Amber.
- Existing NIP-46 sessions need a safe re-login migration to Passkey/Nosskey or nsec import.
- Private keys remain Keychain-only for internal nsec sessions; Passkey/Nosskey remains the preferred non-nsec signer path.

### Web

- `lib/nip46.js` manages session persistence, bunker URL parsing, encrypted requests, response handling, signing, and public-key retrieval.
- Web support is not automatically an iOS support claim.

### Android

- External signer code is Amber/NIP-55-oriented (`ExternalSigner.kt`).

## Source references

- `ios/NuruNuru/Data/ExternalSigner.swift`
- `ios/NuruNuru/ViewModels/AuthViewModel.swift`
- `ios/NuruNuru/Views/Screens/LoginView.swift`
- `ios/NuruNuru/Data/NostrRepository.swift`
- `ios/NuruNuru/Data/AppPreferences.swift`
- `lib/nip46.js`
- `android/app/src/main/kotlin/io/nurunuru/app/data/ExternalSigner.kt`
- `docs/wiki/decisions/adr-0023-ios-remove-nip46-signer.md`

## Related pages

- [[../platforms/ios]]
- [[../platforms/web]]
- [[README]]
