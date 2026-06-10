# ADR-0003: iOS external signing uses NIP-46

## Status

Superseded by [[adr-0023-ios-remove-nip46-signer]] — 2026-06-09.

Previously Accepted.

## Context

iOS does not have Android's Amber/NIP-55 environment. The project targets iOS 17+ and keeps private keys in Keychain for internal signing.

## Decision

Historical decision: use NIP-46 / Nostr Connect for iOS external signing and do not introduce NIP-55 as an iOS external signer path. This is no longer the current iOS app signer direction; ADR-0023 removes the iOS NIP-46 signer path entirely while continuing to reject NIP-55 on iOS.

## Consequences

- iOS external signer work should touch `ExternalSigner.swift` / auth flow rather than Android `ExternalSigner.kt` assumptions.
- Docs should point iOS external signing to [[nips/nip-46]].

## Source references

- `ios/NuruNuru/Data/ExternalSigner.swift`
- `ios/NuruNuru/ViewModels/AuthViewModel.swift`
- `ios/NuruNuru/Views/Screens/LoginView.swift`
- `lib/nip46.js`
