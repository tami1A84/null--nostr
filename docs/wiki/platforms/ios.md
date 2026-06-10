# iOS Platform

## Summary

iOS は Swift / SwiftUI / iOS 17+ Observation による native app です。Android との pixel-perfect sync を重視します。

## Main structure

```text
ios/NuruNuru/
  Theme/
  Models/
  Data/
  ViewModels/
  Views/Screens/
  Views/Components/
  Views/Sheets/
  Views/MiniApps/
```

## Important rules

- `NostrRepository` は `actor`。
- ViewModel は `@Observable`。Combine / `ObservableObject` は使わない。
- NIP-46 signer は廃止。iOS では NIP-55 も使わない。signer は internal nsec/Keychain または Passkey/Nosskey を使う。
- 秘密鍵は Keychain のみ。`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`。
- body text は LINE Seed JP のみ。system font fallback を避ける。
- timeline は `.id(post.event.id)` を使い、entrance animation を避ける。
- image viewer は `.fullScreenCover`。その他は原則 `.sheet`。
- tab bar は `.safeAreaInset(edge: .bottom, spacing: 0)`。

- Uploads support NIP-98 and Blossom auth kind 24242 paths.
- Talk is Marmot MLS oriented; NIP-17 is not the native iOS external signer path and legacy messaging boundaries must be checked in code.
- PostActions includes bookmark when handler is supplied; do not describe it as strictly 3 buttons.


## Rust FFI migration status

As of 2026-06-02, the iOS Rust FFI work relevant to the current release-planning scope is complete. The migration includes the previous read-only MLS diagnostics and the write-path contracts for key generation, public-key derivation, event signing JSON, internal-client signing, and raw-event relay-target publishing. Future Talk MLS expansion, if any, should be tracked separately rather than treated as an unfinished blocker for Home renewal.

Important boundaries:

- Internal nsec sessions may later use Rust signing through a Swift EventSigner adapter.
- NIP-46 external signing is removed from the iOS app signer path by ADR-0023. Passkey/Nosskey remains a platform signer path; Rust may create unsigned events and publish signed raw events around supported signers, but must not replace the platform authorization UX.
- Private keys remain Keychain-only in app code; generated private_key_hex / nsec values must not be logged or stored outside secure storage / explicit backup UX.


## Rust FFI write-path migration

As of 2026-06-01, iOS has the first write-path wiring for Full Rust FFI. nsec onboarding prefers Rust generateKeypair with Swift fallback. RustInternalSigner implements EventSigner and delegates NIP-01 signing to Rust signEventJson while NIP-04/NIP-44 remain Swift fallback methods. NostrRepository.publishEventAndReturnSigned can publish signed raw JSON through Rust FFI when iosRustFfiPublishEnabled is enabled, otherwise it falls back to NostrClient. NIP-46 external signing is removed by ADR-0023; Passkey/Nosskey remains a platform signer path, and Rust may create unsigned events / publish signed raw events around supported signers.

Rollout flags live in AppPreferences: iosRustFfiKeygenEnabled default on, iosRustFfiSigningEnabled default off, and iosRustFfiPublishEnabled default off.


## Rust FFI write-path rollout

As of 2026-06-01, iOS has Phase 2-5 implementation wiring for the Rust write path. Phase 3 keygen uses Rust generateKeypair first for nsec onboarding and falls back to Swift keygen. Phase 4 signing has RustInternalSigner behind a feature flag; NIP-01 plus Rust NIP-04/NIP-44 helpers are used when the Rust client is available, with Swift fallback. Phase 5 publishing can route signed raw JSON through Rust FFI and fall back to NostrClient. Relay-targeted fanout also uses the Rust path when enabled. Passkey/Nosskey stays a platform signer path; NIP-46 signer is removed on iOS.

Rollout switches are visible in the security section: Rust keygen is default-on; Rust signing and Rust publish are default-off until real-device QA.

## Phase 6 Talk MLS Rust FFI expansion

After Phase 5 QA completion, iOS started Phase 6 Talk MLS expansion. The current implementation adds a guarded iosRustFfiTalkMlsEnabled rollout switch, exposes Rust engine live-subscription drain/stop methods through MlsFFIBridge, and routes MLS discovery / kind:445 / Welcome raw-event publishes through the Rust-aware raw publish helper when the Talk MLS flag is enabled. Existing relay polling remains the fallback and primary safety net.


## Phase 6 completion status

Phase 6 Talk MLS Rust FFI expansion passed Android ↔ iOS interop QA on 2026-06-01. The Rust-aware Talk MLS path covers KeyPackage / Welcome / Kind 445 raw publishing and live subscription drains while keeping the existing relay polling, repair, and catch-up fallback paths available. The next step is Phase 7 rollout: decide which Rust FFI flags become default-on, retain fallback switches for at least one release window, and update release gates.


## Phase 7 rollout and account-switch lifecycle

Phase 7 rollout is active: Rust keygen, Rust signing, Rust raw publish, and Rust Talk MLS default to enabled while the Settings toggles remain as fallback switches. Account switching now explicitly resets process-wide Rust FFI state via NostrRepository.resetSharedRustFfiForAccountSwitch() on login, registration completion, external-signer login, and logout. The repository also tracks the Rust write-path client's account pubkey and discards stale clients when the active account changes.

This fixes the observed post-account-switch failure mode where Settings could show Rust FFI as unavailable and manual reconnect did not recover.


## Rust FFI account-switch recovery note

Passkey/Nosskey sessions are not nsec-backed. MLS FFI initialization must use the read-only encrypted constructor with a pubkey-scoped SQLCipher key, not the internal nsec constructor. This avoids `Rust FFI: 未接続` after account switching when no Keychain nsec exists.


## Account-scoped Rust FFI DB paths

The iOS Rust FFI MLS database base path is account-scoped as nurunuru_ndb_<pubkey>. This is required because internal nsec accounts derive different SQLCipher keys from their nsec; sharing one encrypted DB across accounts can make non-owning accounts fail to open the DB and show Rust FFI unavailable.

NostrRepository.ensureMlsClient() and ensureRustNostrClient() both use the active account's scoped DB path, including read-only fallback paths for Passkey/Nosskey and locked nsec sessions. Legacy NIP-46 session handling is migration-only after ADR-0023.

## Rust structured publish / outbox diagnostics

As of 2026-06-09, iOS maps Rust `FfiPublishResult` through `RustPublishDeliveryResult` in `RustNostrFFIClient`. Rust raw-event publishing uses structured result APIs, logs queued/failed delivery state, and still falls back to `NostrClient` when Rust publish does not report success. `RustNostrFFIClient.connect(relayUrls:)` starts a detached best-effort `retryPendingPublishOutbox(20)` so signed events left in the durable Rust outbox can recover after reconnect without blocking the `NostrRepository` actor.

Repository accessors expose `retryPendingPublishOutbox()`, `getRelayHealthSnapshots()`, and `getPendingPublishOutbox()` for future Settings / Performance Console UI. These diagnostics must remain sanitized and must not display raw event content without explicit user intent.


## Rust publish diagnostics UI

- `RelaySettingsView` includes a Rust diagnostics card when `NURUNURU_FFI_AVAILABLE` is enabled.
- The card shows local pending/failed signed publish outbox count, up to three pending event IDs, attempt counts, and a manual retry button.
- It also shows Rust `RelayRouter` health snapshots (availability, role, success/failure counts) for up to five relays.
- Diagnostics stay local and sanitized; raw event content is not displayed.
- Source: `ios/NuruNuru/Views/MiniApps/RelaySettingsView.swift`, `ios/NuruNuru/Data/NostrRepository.swift`, `ios/NuruNuru/Data/NuruNuruFFILiveClient.swift`.

## Phase 0 zero-base audit (2026-06-09)

- iOS root navigation target is 4 tabs: ホーム / トーク / タイムライン / ミニアプリ.
- NIP-46 signer is removed from the iOS app path; NIP-55 remains forbidden.
- Startup relay connection duplication is a P0 performance/stability issue. See [[ios-phase0-audit]].

## Source references

- `ios/NuruNuru/`
- `ios/GUARDRAILS.md`
- `docs/wiki/decisions/adr-0019-ios-rust-ffi-write-path.md`
- `ios/project.yml`
- `ios/NuruNuru/Data/`
- `rust-engine/nurunuru-ffi/ios/Sources/NuruNuru/nurunuru_ffi.swift`
- `ios/NuruNuru/Data/NostrRepository.swift`
- `ios/NuruNuru/Views/MiniApps/RelaySettingsView.swift`
- `ios/NuruNuru/Data/NuruNuruFFILiveClient.swift`
- `ios/NuruNuru/ViewModels/`

## Related pages

- [[ui/android-ios-sync]]
- [[decisions/adr-0001-ios-observation]]
- [[features/post-composer]]
