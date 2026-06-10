# ADR-0023: Remove iOS NIP-46 signer

## Status

Accepted — 2026-06-09

Supersedes [[adr-0003-ios-external-signing-uses-nip46]] for the iOS app signer path.

## Context

ADR-0003 chose NIP-46 / Nostr Connect for iOS external signing because iOS does not have Android's Amber/NIP-55 environment. The 2026-06-09 iOS zero-base direction simplifies account/signing UX and removes the iOS NIP-46 signer path entirely.

The code audit found active NIP-46 plumbing in `ExternalSigner.swift`, `AuthViewModel`, `LoginView`, `NostrRepository`, and `AppPreferences.isExternalSigner`.

## Decision

Remove NIP-46 / Nostr Connect as an iOS app signer path.

Supported iOS signer paths after removal:

- Internal nsec signer with private key stored only in Keychain.
- RustInternalSigner for internal nsec sessions when the Rust signing rollout flag is enabled.
- Passkey/Nosskey signer through the shared `EventSigner` abstraction.

Explicitly not supported on iOS:

- NIP-46 / Nostr Connect remote signer.
- NIP-55 / Amber.

This ADR is scoped to the iOS app signer path. It does not by itself remove Web NIP-46 helper code or any future non-user-facing operational bunker design for project-owned accounts.

## Alternatives Considered

### Keep NIP-46 as an advanced login option

Rejected. It keeps a complex protocol path in onboarding/settings, increases failure modes, and works against the zero-base simplification goal.

### Replace NIP-46 with NIP-55 on iOS

Rejected. iOS still does not have Android's Amber environment, and adding NIP-55 would violate existing iOS platform constraints.

### Keep NIP-46 read-only sessions

Rejected as the default product path. Existing users need a safe migration/re-login path, not a half-signed session that appears logged in but cannot publish reliably.

## Why this fits NuruNuru

- 第一条「日常を壊さない」: login choices become easier to understand.
- 第三条「複雑さは裏側に隠す」: Nostr Connect details no longer appear in beginner-facing iOS UI.
- 第五条「かわいさと、秘密鍵への厳格さを、同時に持つ」: removing a remote signer path reduces key/authorization ambiguity while keeping Keychain and Passkey/Nosskey boundaries strict.

## Consequences

- `LoginView` must remove the Nostr Connect button/sheet.
- `AuthViewModel` must remove `connectExternalSigner` / `loginWithExternalSigner` paths or quarantine them behind migration-only code until deleted.
- `NostrRepository` must remove `externalSigner` signing branches and rely on `EventSigner` for write paths.
- `AppPreferences.isExternalSigner` needs a migration plan. Existing NIP-46 sessions should be guided to re-login with Passkey/Nosskey or nsec import.
- Docs and guardrails must stop listing NIP-46 as an iOS signer requirement.
- Web NIP-46 support, if retained, must be documented as Web-specific.

## Source references

- User decision on 2026-06-09.
- `ios/NuruNuru/Data/ExternalSigner.swift`
- `ios/NuruNuru/ViewModels/AuthViewModel.swift`
- `ios/NuruNuru/Views/Screens/LoginView.swift`
- `ios/NuruNuru/Data/NostrRepository.swift`
- `ios/NuruNuru/Data/AppPreferences.swift`
- `docs/wiki/platforms/ios-phase0-audit.md`
- `docs/wiki/decisions/adr-0003-ios-external-signing-uses-nip46.md`
