# ADR-0019: iOS Rust FFI write-path migration starts from keygen/sign/publish contracts

## Status

Accepted — 2026-06-01
Completed for current release-planning scope — 2026-06-02



## Completion note (2026-06-02)

Per user decision on 2026-06-02, the iOS Rust FFI work discussed for the current release-planning scope is complete. This ADR remains the record of the write-path boundary: Rust may provide keygen/sign/publish contracts, while Passkey/Nosskey remains a platform authorization path and private keys remain Keychain-only in app code. ADR-0023 later removes the iOS NIP-46 signer path; any older NIP-46 language in this ADR is historical.

Future Rust-related work, such as broader Talk MLS expansion or additional repository refactors, should be tracked separately and must not be treated as an unfinished blocker for the current Home renewal planning.

## Context

The iOS Rust FFI work initially completed Phase 1 through Phase 1.2 as a read-only MLS diagnostic path: mlsIsEncrypted(), local MLS group counts, sanitized diagnostic errors, manual refresh, and checked-at time. ADR-0019 then defined the write-path contract for signing, publishing, key generation, and targeted publish. As of the 2026-06-02 user decision, the current release-planning scope of iOS Rust FFI is complete; future Talk MLS expansion, if any, should be tracked separately.

The existing iOS app uses Swift implementations for key generation, event signing, NIP-04/NIP-44 helpers, normal publishing, and relay-target fanout. Rust UniFFI already exposes broad MLS and relay APIs, but the iOS app needs a stable write-path contract before replacing Swift paths incrementally. The contract must preserve iOS requirements: Keychain-only private keys, supported platform signing such as Passkey/Nosskey, NIP-70 relay-targeted publishing, and signed-event JSON reuse for UI/fanout. NIP-46 external signing was part of the historical context but is superseded by ADR-0023.

## Decision

Full iOS Rust FFI will proceed incrementally. Phase 2 establishes the Rust FFI contract before wiring iOS UI flows:

- generate_keypair() -> FfiGeneratedKeypair for onboarding key generation.
- derive_public_key_from_secret(secret_key_hex_or_nsec) -> String for key validation and parity checks.
- sign_event_json(secret_key_hex_or_nsec, kind, content, tags, created_at?) -> signed_event_json as a standalone contract test/helper.
- NuruNuruClient.sign_event(kind, content, tags, created_at?) -> signed_event_json for logged-in internal-signer clients.
- NuruNuruClient.publish_raw_event_to_relays(event_json, relay_urls) -> event_id for signed-once relay-targeted publishing.

Passkey/Nosskey remains a platform signer path. Rust may generate unsigned events and publish signed raw events for supported platform signer paths, but must not replace platform authorization UX with an internal Rust secret-key signer. The iOS NIP-46 signer path is removed by ADR-0023.

## Alternatives Considered

- Switch all iOS publishing to Rust immediately. Rejected because iOS currently depends on publishEventAndReturnSigned() semantics and several UI flows need the exact signed event JSON / decoded NostrEvent.
- Keep Swift signing indefinitely and use Rust only for MLS. Rejected because Android already benefits from the shared Rust protocol layer, and iOS needs shared keygen/sign/publish contracts for long-term parity.
- Route platform signers such as NIP-46/Passkey through Rust internal signing. Rejected because those are external/platform authorization flows by design and must not require exporting private keys into Rust. After ADR-0023, the NIP-46 part is historical for iOS.

## Why this fits NuruNuru

This follows [[../culture/principles|五箇条]] by keeping the user-facing experience calm while moving protocol-critical behavior into a shared, testable layer. It also aligns with [[../culture/not-doing|やらないことリスト]] by avoiding a risky all-at-once migration and by not weakening private-key boundaries.

## Consequences

- iOS has completed the current release-planning Rust FFI scope for keygen/sign/publish-path integration.
- The Rust FFI crate builds an rlib in addition to cdylib/staticlib so integration tests can import the crate directly.
- Generated Swift/Kotlin bindings and the iOS XCFramework must be committed whenever these APIs change.
- Passkey/Nosskey remains a platform authorization path; Rust FFI completion must not weaken UX/security boundaries. NIP-46 signer removal is tracked by ADR-0023.
- Future Rust/Talk expansion should be tracked separately instead of keeping this ADR as an open blocker.

## Source references

- rust-engine/nurunuru-ffi/src/lib.rs
- rust-engine/nurunuru-core/src/engine.rs
- rust-engine/nurunuru-ffi/tests/phase2_contract.rs
- rust-engine/nurunuru-ffi/ios/Sources/NuruNuru/nurunuru_ffi.swift
- rust-engine/nurunuru-ffi/bindgen/kotlin-out/uniffi/nurunuru/nurunuru.kt
- rust-engine/nurunuru-ffi/ios/NuruNuruFFI.xcframework/
- ios/NuruNuru/Data/NostrRepository.swift
- ios/GUARDRAILS.md
- docs/wiki/decisions/adr-0003-ios-external-signing-uses-nip46.md (superseded)
- docs/wiki/decisions/adr-0023-ios-remove-nip46-signer.md
- docs/wiki/decisions/adr-0009-mls-db-encryption.md
- docs/wiki/decisions/adr-0010-passkey-prf-direct-method.md
