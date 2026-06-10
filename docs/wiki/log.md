## [2026-06-10] impl | iOS single-profile fetch de-dupe

- Added actor-isolated in-flight de-dupe for `fetchProfile(pubkey:)` so repeated Home/Timeline/Profile requests for the same Kind-0 profile join one network task.
- Added short cooldown guards: cached profiles refreshed within 60 seconds are reused, and recent cache-miss attempts are not immediately retried during startup.
- This targets the remaining実機ログ issue where the same self pubkey produced repeated `fetchProfile Kind 0` lines after relay/badge optimizations.

## [2026-06-10] impl | iOS startup profile/enrichment request reduction

- Removed serial per-pubkey fallback from batch profile hydration to avoid dozens of Kind-0 requests during startup enrich.
- Delayed and capped Timeline follow-list profile warmup; badge warmup is now cache-only outside explicit profile surfaces.
- Capped Timeline/Home missing-profile fills and made Home post-list badge enrichment cache-only.
- Added cached badge URL accessor for list enrichment without relay fetches.

## [2026-06-10] impl | iOS broad relay expansion guardrail

- Added an iOS NostrClient global background pool cap of 4 relays so broad NIP-65/saved-relay connect calls cannot expand startup connections to 10+ relays.
- Delayed Timeline NIP-65 sync to after the first startup minute and reduced early relay prefetch fan-out.
- Changed syncNip65Relays to persist NIP-65 metadata without rewriting selectedRelays during background sync, preventing later generic connects from inheriting broad write-relay lists.

## [2026-06-10] impl | iOS startup fetch-storm and relay expansion controls

- Gated MainTab startup so compact relay warmup completes before Timeline remote refresh and notification polling.
- Delayed notification dot polling by 12 seconds after launch to avoid competing with first paint.
- Updated HomeViewModel to show cached Home data immediately, then wait for compact relay warmup before remote header refresh.
- Deferred heavier Home badges/user-notes/liked-post refresh to reduce startup fetchRecovery join storm.
- Adjusted NostrClient connect logging to report only physical new connects or in-flight waits, not every orchestration call.

## [2026-06-09] impl | iOS Phases 2-6 quality implementation

- Implemented local-first Timeline first paint from cached events/profiles, while keeping relay refresh in the background.
- Added Swift signed-event outbox fallback for fully signed events when all relay ACKs fail, with retry after healthy relay connection.
- Completed like toggle undo by deleting the user's prior reaction event when available, and storing the new reaction event id after like.
- Reduced Talk send preflight catch-up timeout to keep optimistic LINE-style send UX responsive.
- Improved Search result profile rendering with cache-first profiles and fresh fill for misses.
- Added accessibility labels/values for post action controls.

## [2026-06-09] impl | iOS Phase 1 4tab, NIP-46 removal, relay dedupe

Implemented the Phase 1 code pass for the iOS zero-base plan: `MainTabView` is the 4-tab target with `ミニアプリ` copy and Home default, active iOS NIP-46/Nostr Connect signer code was removed with legacy migration handling, and `NostrRepository.ensureRelayConnections(reason:)` now dedupes startup relay warmup across connect/fetch/publish paths. Verified with an iOS Simulator build.

## [2026-06-09] docs | iOS Phase 0 audit for 4tab, NIP-46 removal, relay dedupe

- Recorded the iOS zero-base Phase 0 audit in `docs/wiki/platforms/ios-phase0-audit.md`.
- Added ADR-0022 for the 4-tab iOS root target: ホーム / トーク / タイムライン / ミニアプリ.
- Added ADR-0023 to supersede ADR-0003 and remove the iOS NIP-46 signer path while continuing to reject NIP-55 on iOS.
- Added ADR-0024 as the proposed design for deduplicating startup relay connection attempts behind a single in-flight coordinator.
- Updated iOS guardrails/wiki references so News/Rokunana are not iOS root tabs and NIP-46 is no longer an iOS signer requirement.
- Source references: `ios/NuruNuru/Views/Screens/MainTabView.swift`, `ios/NuruNuru/Data/NostrRepository.swift`, `ios/NuruNuru/Data/NostrClient.swift`, `ios/NuruNuru/Data/ExternalSigner.swift`, `ios/GUARDRAILS.md`.

## [2026-06-09] performance | Web publish outbox and relay diagnostics parity

- Added a browser-local publish outbox in `lib/publish-outbox.js` that stores fully signed event JSON only in `localStorage`; private keys, unsigned events, and signing material are never stored.
- Added structured Web publish APIs `publishManagedResult()` and `publishEventResult()` while preserving the legacy boolean `publishEvent()` wrapper.
- Added `retryPendingPublishOutbox()` and online-event best-effort retry for pending/failed signed events.
- Added a Web Relay Settings diagnostics card showing pending outbox counts, recent event IDs, manual retry, and `connection-manager.js` relay health without displaying raw signed content.
- Source: `lib/publish-outbox.js`, `lib/connection-manager.js`, `lib/nostr.js`, `components/miniapps/RelaySettings.js`, `docs/wiki/platforms/web.md`, `docs/wiki/ui/android-ios-sync.md`.

## [2026-06-09] performance | Android Relay Settings publish diagnostics UI

- Added an Android Mini Apps relay settings diagnostics card showing pending signed publish outbox count, recent pending event IDs, retry attempts, manual retry, and Rust RelayRouter health snapshots.
- Kept diagnostics local and sanitized: raw signed event JSON is not displayed.
- Reused repository accessors `retryPendingPublishOutbox`, `getRelayHealthSnapshots`, and `getPendingPublishOutbox`.
- Verified Android debug Kotlin compilation succeeds with `cd android && ./gradlew :app:compileDebugKotlin`.
- Source: `android/app/src/main/kotlin/io/nurunuru/app/ui/screens/MiniAppsScreen.kt`, `android/app/src/main/kotlin/io/nurunuru/app/data/NostrRepository.kt`, `android/app/src/main/kotlin/io/nurunuru/app/data/NostrClient.kt`, `docs/wiki/platforms/android.md`, `docs/wiki/ui/android-ios-sync.md`.

## [2026-06-09] performance | iOS RelaySettings publish diagnostics UI

- Added an iOS Relay Settings diagnostics card behind `NURUNURU_FFI_AVAILABLE` showing pending signed publish outbox count, recent pending event IDs, retry attempts, manual retry, and Rust RelayRouter health snapshots.
- Kept diagnostics local and sanitized: raw signed event content is not displayed.
- Reused repository accessors `retryPendingPublishOutbox`, `getRelayHealthSnapshots`, and `getPendingPublishOutbox` added in the previous phase.
- Verified iOS simulator build succeeds with `xcodebuild -scheme NuruNuru -destination 'platform=iOS Simulator,name=iPhone 17' -skipPackagePluginValidation build`.
- Source: `ios/NuruNuru/Views/MiniApps/RelaySettingsView.swift`, `ios/NuruNuru/Data/NostrRepository.swift`, `ios/NuruNuru/Data/NuruNuruFFILiveClient.swift`, `docs/wiki/platforms/ios.md`, `docs/wiki/ui/android-ios-sync.md`.

## [2026-06-09] performance | iOS structured publish result integration

- Synced the iOS Swift Package UniFFI source from regenerated Swift bindings so `FfiPublishResult`, relay health snapshots, and publish outbox APIs are visible to app code.
- Added iOS `RustPublishDeliveryResult`, `RustRelayHealthStatus`, and `RustPublishOutboxStatus` bridge models in `RustNostrFFIClient`.
- Updated Rust raw-event publish integration to use `publishRawEventResult` / targeted result variants and log queued/failed delivery state before fallback.
- Added async repository accessors for `retryPendingPublishOutbox`, `getRelayHealthSnapshots`, and `getPendingPublishOutbox`.
- `RustNostrFFIClient.connect(relayUrls:)` now starts a detached best-effort pending outbox retry after connect without blocking the repository actor.
- Source: `ios/NuruNuru/Data/NuruNuruFFILiveClient.swift`, `ios/NuruNuru/Data/NostrRepository.swift`, `rust-engine/nurunuru-ffi/ios/Sources/NuruNuru/nurunuru_ffi.swift`.

## [2026-06-09] performance | Android structured publish result integration

- Added Android domain models for `PublishDeliveryResult`, `RelayHealthStatus`, and `PublishOutboxStatus`.
- Wired `NostrClient` to map Rust `FfiPublishResult`, relay health snapshots, and publish outbox items into Android data models.
- Triggered best-effort `retryPendingPublishOutbox(20)` after Rust client connect without blocking app startup.
- Updated `NostrRepository.signAndPublishGetId()` and `NostrRepositoryActions.publishNote()` to use structured publish result APIs for raw signed events and text-note publish paths, including targeted relay variants.
- Added repository accessors for pending outbox and relay health diagnostics for future Relay Settings / Performance Console UI.
- Verified `cd android && ./gradlew :app:compileDebugKotlin` succeeds with existing warnings.
- Source: `android/app/src/main/kotlin/io/nurunuru/app/data/NostrClient.kt`, `android/app/src/main/kotlin/io/nurunuru/app/data/NostrRepository.kt`, `android/app/src/main/kotlin/io/nurunuru/app/data/NostrRepositoryActions.kt`, `android/app/src/main/kotlin/io/nurunuru/app/data/models/NostrModels.kt`.

## [2026-06-09] performance | Structured publish results and outbox retry API

- Added Rust `PublishResult` for write paths with event id, aggregate ok/failed relay lists, first-ok latency, retry queued flag, and error string.
- Added structured publish APIs for raw signed events and tagged notes, including targeted relay variants, while preserving legacy event-id-returning APIs.
- Added `retry_pending_publish_outbox(limit)` to retry pending/failed fully-signed event JSON from `publish_outbox.json` using existing RelayRouter cooldown rules.
- Exposed Phase 2 APIs through UniFFI and regenerated Kotlin/Swift bindings, including `FfiPublishResult`.
- Verified `cargo check -p nurunuru-ffi`, targeted Rust tests for RelayRouter/outbox, and regenerated bindings via `bindgen/gen_kotlin.sh` and `bindgen/gen_swift.sh`.
- Source: `rust-engine/nurunuru-core/src/outbox.rs`, `rust-engine/nurunuru-core/src/engine.rs`, `rust-engine/nurunuru-ffi/src/lib.rs`, `rust-engine/nurunuru-ffi/bindgen/kotlin-out/uniffi/nurunuru/nurunuru.kt`, `rust-engine/nurunuru-ffi/bindgen/swift-out/nurunuru.swift`.

## [2026-06-09] performance | Rust RelayRouter and signed publish outbox groundwork

- Added Rust `RelayRouter` / `RelayHealthSnapshot` groundwork in `rust-engine/nurunuru-core/src/relay.rs`, including cooldown-aware relay availability and unit coverage.
- Wired relay health recording into explicit relay fetches and targeted publish paths, and made note/raw publish paths enqueue signed events before network send.
- Added `PublishOutbox` in `rust-engine/nurunuru-core/src/outbox.rs`, storing fully-signed event JSON only under `db_path/publish_outbox.json` before raw publish and marking items published/failed after network result.
- Exposed UniFFI APIs `relay_health_snapshots()`, `enqueue_publish_outbox()`, and `pending_publish_outbox()`; regenerated Kotlin and Swift bindings.
- Verified `cargo check -p nurunuru-ffi` and targeted Rust tests for relay cooldown and outbox persistence.
- Source: `rust-engine/nurunuru-core/src/relay.rs`, `rust-engine/nurunuru-core/src/outbox.rs`, `rust-engine/nurunuru-core/src/engine.rs`, `rust-engine/nurunuru-ffi/src/lib.rs`, `rust-engine/nurunuru-ffi/bindgen/kotlin-out/uniffi/nurunuru/nurunuru.kt`, `rust-engine/nurunuru-ffi/bindgen/swift-out/nurunuru.swift`.

## [2026-06-09] performance | Phase 0 timeline first-paint safety

- Removed Android timeline NIP-05 verification from `NostrRepository.enrichPosts()` so DNS/HTTPS checks no longer block timeline enrichment.
- Added local-only Web performance metrics helper and instrumented managed relay fetches plus following first-page and URL preview fetch timings.
- Changed Web URL previews to fetch only near the viewport, reducing Microlink/API pressure on long timelines.
- Preserved existing Web following posts and Android selected relay posts on transient empty relay responses.
- Source: `android/app/src/main/kotlin/io/nurunuru/app/data/NostrRepository.kt`, `android/app/src/main/kotlin/io/nurunuru/app/viewmodel/TimelineViewModel.kt`, `components/URLPreview.js`, `components/TimelineTab.js`, `lib/connection-manager.js`, `lib/performance-metrics.js`.

## [2026-06-09] implementation | Shared Nostr kind registry and upstream aliases

- Added Web shared kind registry `lib/nostr-kinds.js` and re-exported `NOSTR_KINDS` from `lib/constants.js`.
- Added NIP-5A nsite aliases across Web / Android / iOS: root `15128`, legacy `34128`, and named `35128`.
- Replaced project-local `VIDEO_LOOP` / `videoLoop` / Web `SHORT_VIDEO` usage with upstream-aligned `ADDRESSABLE_SHORT_VIDEO` / `addressableShortVideo` naming for kind `34236`.
- Replaced Web `NIP46_KIND` and iOS raw kind `24133` usage with `NOSTR_CONNECT` / `nostrConnect` constants while preserving NIP-46 / Nostr Connect feature behavior.
- Source references: `lib/nostr-kinds.js`, `lib/constants.js`, `lib/nip46.js`, `android/app/src/main/kotlin/io/nurunuru/app/data/models/NostrModels.kt`, `ios/NuruNuru/Models/NostrKind.swift`, `ios/NuruNuru/Data/ExternalSigner.swift`.

## [2026-06-09] docs | NIP and kind registry audit

- Audited upstream `nostr-protocol/nips` at `7a2197c00d1bbff19b32d19851f4dffe4810b8ed` and `nostr-protocol/registry-of-kinds` at `d93db0c028184f317497763837e9524246507acb`.
- Updated NIP support docs to distinguish official NIP-5A nsites from Scroll mini-app kinds, mark NIP-96 as legacy/unrecommended compatibility, clarify NIP-EE as superseded by Marmot, and note the new NIP-50 `autocomplete:true/false` extension gap.
- Added `docs/wiki/nips/kind-registry.md`, `docs/wiki/nips/nip-5a.md`, and `docs/wiki/nips/nip-b7.md`; updated NIP-71, NIP-98, image upload, and wiki index links.
- Source references: `docs/wiki/nips/README.md`, `docs/wiki/nips/kind-registry.md`, `docs/wiki/nips/nip-5a.md`, `docs/wiki/nips/nip-b7.md`, `docs/wiki/nips/nip-50.md`, `docs/wiki/nips/nip-71.md`, `docs/wiki/nips/nip-98.md`, `docs/wiki/features/image-upload.md`, `docs/wiki/index.md`.

## [2026-06-03] change | Passkey-only onboarding and invite relocation

- 新規登録を Web / Android / iOS でパスキー登録のみに整理し、オンボーディング中の nsec バックアップ/従来作成導線を削除した。
- 「リレーセットアップ」をユーザー向けには「地域の設定」とし、地域に応じたリレーサーバー自動セットアップとして案内するようにした。
- #nostrはじめました チュートリアルに「あいさつしてみましょう」案内と例文チップを追加した。
- オンボーディング最後のプロフィール共有/招待導線を削除し、ホームタブ設定の「招待」項目へ移設した。

## [2026-06-02] change | Rename Mini Apps screens and record Home settings migration QA

- Renamed native Mini Apps hub files to match their post-migration responsibility: Android SettingsScreen.kt to MiniAppsScreen.kt, iOS SettingsView.swift to MiniAppsView.swift.
- Updated call sites, AGENTS references, wiki source references, and sync docs that previously treated SettingsScreen/SettingsView as the Mini Apps hub names.
- Added QA evidence for the Home settings / Mini Apps responsibility split, including Web build/wiki lint, Android real-device passkey export, iOS build, and security behavior.
- Source: android/app/src/main/kotlin/io/nurunuru/app/ui/screens/MiniAppsScreen.kt, ios/NuruNuru/Views/Screens/MiniAppsView.swift, docs/wiki/quality/qa-2026-06-02.md.

# null--nostr LLM Wiki Log

## [2026-06-02] change | Move account and security settings to Home settings

- Web / Android / iOS now route login status and security controls through the Home tab gear settings instead of the Mini Apps tab.
- Mini Apps surfaces no longer render login status, logout, auto-sign, or nsec export controls; they remain focused on app search/favorites/categories/details.
- Added shared account/security components for Web (AccountSecuritySettings.js), Android (AccountSecuritySettings.kt), and iOS (AccountSecuritySettingsView.swift).
- iOS AppSettingsView is now its own screen file and is presented with fullScreenCover; nsec text is privacySensitive and cleared on disappear/background.
- Android Home settings uses the shared account/security Compose section and applies dynamic FLAG_SECURE while nsec is visible.
- Source: components/SettingsModal.js, components/MiniAppTab.js, components/AccountSecuritySettings.js, android/app/src/main/kotlin/io/nurunuru/app/ui/screens/MainScreen.kt, android/app/src/main/kotlin/io/nurunuru/app/ui/screens/MiniAppsScreen.kt, android/app/src/main/kotlin/io/nurunuru/app/ui/components/AccountSecuritySettings.kt, ios/NuruNuru/Views/Screens/MainTabView.swift, ios/NuruNuru/Views/Screens/AppSettingsView.swift, ios/NuruNuru/Views/Screens/MiniAppsView.swift, ios/NuruNuru/Views/Components/AccountSecuritySettingsView.swift.

## [2026-06-01] fix | Account-scoped iOS Rust FFI MLS DB paths

- User confirmed the post-account-switch Rust FFI issue is resolved.
- Root cause: multiple nsec accounts used the same MLS SQLCipher DB base path while each account derives a different SQLCipher key from its nsec. Opening another account's encrypted DB with the active account key failed and surfaced as Rust FFI unavailable.
- Fix: iOS Rust FFI DB base paths are now account-scoped as nurunuru_ndb_<pubkey>, so Rust derives an account-specific _mls.sqlite3 path.
- ensureMlsClient() and ensureRustNostrClient() both use the active account's scoped DB path, including read-only fallback paths.

## [2026-06-01] fix | iOS Rust FFI unavailable after Passkey/Nosskey account switch

- Root cause: `ensureMlsClient()` treated all non-external sessions as nsec-backed internal signer sessions. Passkey/Nosskey sessions have `isExternalSigner=false` but intentionally keep no nsec in Keychain, so MLS FFI init returned nil and Settings showed `Rust FFI: 未接続`.
- Fix: Passkey/Nosskey sessions now use the read-only encrypted MLS FFI constructor with a pubkey-scoped SQLCipher key, matching the NIP-46 external signer path. nsec sessions without an unlocked key also try this read-only fallback when `publicKeyHex` is available.
- Logout clears pubkey-scoped MLS DB keys for both NIP-46 and Nosskey sessions.

## [2026-06-01] fix | Rust FFI account-switch lifecycle reset and Phase 7 defaults

- Fixed an iOS Rust FFI lifecycle issue where switching accounts could leave Settings diagnostics showing Rust FFI unavailable and reconnection attempts ineffective.
- Added NostrRepository.resetSharedRustFfiForAccountSwitch() and call it on nsec login, external-signer login, registration completion, and logout.
- ensureMlsClient() now reconnects same-account cached/shared clients before returning them; ensureRustNostrClient() tracks the account pubkey and discards stale write-path clients across account boundaries.
- disconnect() now clears local and matching process-wide MLS FFI references, stops Rust MLS live subscriptions, and clears write-path client/account state.
- Phase 7 rollout defaults are now ON for Rust signing, Rust publish, and Rust Talk MLS while retaining Settings fallback switches.
- Verified iOS simulator build succeeds and Rust FFI contract tests/check pass.

## [2026-06-01] qa | Phase 6 Talk MLS Android-iOS interop passed

- User confirmed the Phase 6 Talk MLS QA checklist succeeded after enabling the Rust Talk MLS path.
- Phase 6 is now considered complete: KeyPackage / Welcome / Kind 445 / repair-oriented fallback paths are wired, and Android ↔ iOS MLS interop passed.
- Next milestone is Phase 7 rollout: default-on policy, fallback retention window, release gating, and documentation cleanup.

## [2026-06-01] implementation | Phase 6 Talk MLS Rust FFI expansion started

- Marked Phase 5 QA checklist complete based on user confirmation.
- Added iosRustFfiTalkMlsEnabled rollout switch for Talk MLS Rust FFI expansion.
- Extended MlsFFIBridge / live client with pollLiveEvents and stopLiveSubscription wrappers around existing Rust UniFFI live subscription APIs.
- Added bounded Rust live subscription drain for Welcome and KeyPackage rotation events; existing relay polling remains the safety-net path.
- Routed MLS discovery / kind:445 / Welcome signed raw JSON publishing through the Rust-aware raw publish helper when the Talk MLS flag is enabled, with existing Swift relay publish fallback otherwise.
- Verified iOS simulator build succeeds after the Phase 6 wiring.

## [2026-06-01] implementation | iOS Rust FFI Phase 4/5 signing and publish wiring

- Added Settings security toggles for Rust keygen/signing/publish rollout.
- Updated RustInternalSigner to prefer Rust client signing plus Rust NIP-04/NIP-44 helpers, keeping Swift fallback if the encrypted Rust client cannot initialize.
- Kept NIP-46 and Passkey/Nosskey as platform signer paths while allowing Rust unsigned-event creation and signed raw publish.
- Verified iOS simulator build succeeds and Rust FFI contract tests/check pass.
- Phase 5 implementation wiring is complete; product completion still requires real-device QA with Rust signing/publish toggles enabled across major write paths.

## [2026-06-01] implementation | iOS Rust FFI write-path wiring

- Added Swift RustInternalSigner backed by Rust signEventJson for NIP-01 signing; NIP-04/NIP-44 remain Swift fallback methods.
- Added feature flags in AppPreferences: Rust keygen default-on with fallback, Rust signing/publish default-off for staged rollout.
- Wired NostrRepository.publishEventAndReturnSigned to optionally create unsigned events through Rust for platform signers, publish signed raw JSON through Rust FFI, and fall back to Swift NostrClient publishing.
- Migrated nsec onboarding key generation to prefer Rust generateKeypair and fall back to Swift NostrKeyUtils.generateKeys.
- Kept NIP-46 and Passkey/Nosskey authorization as platform-owned signer paths.
- Verified iOS simulator build succeeds.

## [2026-06-01] implementation | iOS Rust FFI Phase 2 keygen/sign/publish contracts

- Added Rust FFI write-path contract APIs for the next Full iOS Rust FFI phase: generate_keypair, derive_public_key_from_secret, standalone sign_event_json, NuruNuruClient.sign_event, and NuruNuruClient.publish_raw_event_to_relays.
- Added NuruNuruEngine.publish_raw_event_to_relays so a signed event JSON can be reused and targeted to selected relays without re-signing.
- Added FfiGeneratedKeypair, generated Swift/Kotlin bindings, rebuilt the iOS XCFramework, and added a Rust integration contract test for keygen/derive/sign JSON.
- iOS UI flows are not yet switched to these APIs; NIP-46 and Passkey/Nosskey remain platform signer paths.
- Source: rust-engine/nurunuru-ffi/src/lib.rs, rust-engine/nurunuru-core/src/engine.rs, rust-engine/nurunuru-ffi/tests/phase2_contract.rs, rust-engine/nurunuru-ffi/ios/Sources/NuruNuru/nurunuru_ffi.swift, rust-engine/nurunuru-ffi/bindgen/kotlin-out/uniffi/nurunuru/nurunuru.kt.

## [2026-06-01] qa | iOS Rust FFI Phase 1.2 diagnostic polish PASS

- Manually confirmed Phase 1.2 iOS Settings diagnostic polish: sanitized read-only counts, local check time, and refresh behavior work inside expanded セキュリティ設定.
- Confirmed no private key, pubkey, DB path, relay auth challenge, or raw Rust/UniFFI error is displayed.
- Phase 1.2 is closed. Signing, publishing, key generation, private-key export, and broader Talk/MLS live-path migration remain out of scope.
- QA record: docs/wiki/quality/qa-2026-06-01.md.

## [2026-06-01] implementation | iOS Rust FFI Phase 1.2 diagnostic polish

- Added sanitized per-helper statuses for the iOS Rust FFI read-only diagnostics so group-count and self-update-count failures show safe UI states instead of raw Rust/UniFFI errors.
- Added a manual refresh button and local check time inside the expanded セキュリティ設定 diagnostic row.
- Scope remains read-only only: no signing, publishing, key generation, private-key export, pubkey display, DB path display, or raw error display.
- Source: `ios/NuruNuru/Data/NostrRepository.swift`, `ios/NuruNuru/Views/Screens/MiniAppsView.swift`, `ios/GUARDRAILS.md`, `docs/wiki/architecture.md`.

## [2026-06-01] qa | iOS Rust FFI Phase 1.1 read-only diagnostics PASS

- Manually confirmed the iOS Settings diagnostic inside expanded セキュリティ設定: `MLS DB: 暗号化済み / グループ 0件 / 更新待ち 0件`.
- This confirms the Phase 1.1 read-only live FFI path for `mlsIsEncrypted()`, `mlsListGroups()` count, and `mlsGroupsNeedingSelfUpdate(thresholdSecs:)` count.
- Phase 1.1 is closed. Signing, publishing, key generation, private-key export, and broader Talk/MLS live-path migration remain out of scope.
- QA record: `docs/wiki/quality/qa-2026-06-01.md`.

## [2026-06-01] implementation | iOS Rust FFI Phase 1.1 read-only diagnostics

- Extended the iOS Rust FFI Settings diagnostic with two additional read-only MLS checks: mlsListGroups() count and mlsGroupsNeedingSelfUpdate(thresholdSecs:) count.
- The diagnostic remains inside expanded セキュリティ設定 and still does not add signing, publishing, key generation, private-key export, pubkey display, or DB path display.
- Source: ios/NuruNuru/Data/NostrRepository.swift, ios/NuruNuru/Views/Screens/MiniAppsView.swift, ios/GUARDRAILS.md, docs/wiki/architecture.md.

## [2026-06-01] implementation | iOS Rust FFI Phase 1 diagnostic confirmed

- Confirmed iOS Rust FFI Phase 1 live path from Settings: mlsIsEncrypted() -> Bool? reports MLS DB: 暗号化済み.
- Moved the Rust FFI diagnostic row into the expanded セキュリティ設定 section so the Mini Apps header stays user-facing and less technical.
- Scope remains read-only only: no signing, publishing, key generation, private-key export, pubkey display, or DB path display.
- Source: ios/NuruNuru/Data/NostrRepository.swift, ios/NuruNuru/Views/Screens/MiniAppsView.swift, ios/GUARDRAILS.md, docs/wiki/architecture.md.

## [2026-06-01] implementation | iOS Rust FFI Phase 1 read-only diagnostic

- Added a minimal iOS Settings diagnostic that calls mlsIsEncrypted() -> Bool? through NostrRepository and MlsFFIBridge.
- The diagnostic reports only encrypted / plaintext / unavailable state and does not add signing, publishing, key generation, or private-key export.
- Documented Phase 1 guardrails in ios/GUARDRAILS.md and the architecture wiki.
- Source: ios/NuruNuru/Data/NostrRepository.swift, ios/NuruNuru/Views/Screens/MiniAppsView.swift, ios/GUARDRAILS.md, docs/wiki/architecture.md.

## [2026-06-01] strategy-decision | ThemaDAY management meeting confirms onboarding-first June plan

- Added `docs/wiki/strategy/themaday-2026-06-01-management.md` for the management meeting and leader-hat alignment.
- Confirmed onboarding as the leading clause of the June monthly objective; relay-feed removal and safe 4-tab skeleton remain parallel goals.
- Confirmed News W25 as internal dogfood alpha, external communication deferred to 1.6.0 if stable.
- Confirmed iOS Rust FFI Phase 1 starts from one read-only helper, not signing.
- Amended ADR-0018: rokunana is root-tab removed and code-only retained in June, with no Home/Mini App/Settings entry.
- Added `docs/wiki/quality/qa-template.md` for ADR-0014 manual real-device QA notes.

## [2026-05-31] decision | Defer local-first metrics in favor of manual QA

- Changed ADR-0014 from an implementation task to Deferred: local-first metrics remain a future option, but June Phase 1 will not add Android/iOS/Web counters.
- Updated the June roadmap and ThemaDAY week review so P1 becomes manual real-device QA + qualitative ThemaDAY/Design Crit notes.
- Rationale: the maintainer will perform direct real-device testing, and June's Home/News/Mini Apps restructuring benefits more from fast qualitative iteration than from cross-platform metrics plumbing.

## [2026-05-31] decision-implementation | Relay feed removal ADRs and first UI cut

- Added ADR-0013/0014/0015 for relay feed removal, local-first product metrics, and Home tab renewal.
- Implemented the first ADR-0013 safety cut: Android/iOS/Web Timeline primary UI now shows only the follow-graph feed; relay picker/tab and Web desktop relay column are removed/hidden.
- Disabled relay-wide background prefetch/fetch paths used only to populate the removed primary relay feed where safe; relay settings, NIP-65 metadata, relay-targeted publishing, search, and notification relay usage remain in scope.

## [2026-05-31] decision | June tab strategy finalized: 2-hop trust graph, NIP-5A validation, rokunana kept

- Finalized three June strategy decisions: News uses 2-hop trust-graph curation, NIP-5A manifest/launch validation is implementation-owned and safety-first, and rokunana is removed from root tab but kept as a feature via Home/Mini App migration.
- Updated docs/wiki/strategy/june-2026-roadmap.md and the 2026-05-31 ThemaDAY Outcome section accordingly.
- Added ADRs: adr-0016-news-curation-model, adr-0017-nip-5a-mini-apps, adr-0018-rokunana-root-tab-removal.

## [2026-05-31] strategy | June roadmap updated for NIP-5A, network scope, and rokunana removal

- Added/updated docs/wiki/strategy/june-2026-roadmap.md with the user-confirmed June direction: no 6/1 Google Play release, P4 NIP-50 MCP deferred, P5 Compass follow-up done, NIP-5A mini apps as WebView/static-site surfaces, network-scoped discovery, and rokunana root-tab removal while keeping the feature.
- Corrected the mini-app assumption: NIP-5A is treated as the WebView/static-site mini-app mechanism referenced by Nostr Compass. Monetization remains optional/uncertain and will not block the WebView execution environment.
- Reframed News and Mini Apps around 2-hop follow-graph discovery to avoid replaying the relay-feed spam/illegal-content problem.

## [2026-05-31] strategy | ThemaDAY week review and next-week strategy

- Added docs/wiki/strategy/themaday-2026-05-31-week-review.md documenting the 2026-05-25〜31 weekly KPT review and the next-week Learning Velocity Week plan.
- Later updated the Outcome section with the 6/1 release skip, P4 deferral, P5 completion, and June roadmap decisions.

LLM Wiki の時系列ログです。追記専用として扱います。

## [2026-05-29] chore | iOS App Store Connect version bump

- iOS App Store Connect upload metadata bumped to MARKETING_VERSION 1.5.5 / CURRENT_PROJECT_VERSION 11.
- Normalized ios/project.yml Info.plist version keys to $(MARKETING_VERSION) / $(CURRENT_PROJECT_VERSION) so XcodeGen regeneration does not restore stale 1.5.2 / 8 values.
- Source: ios/project.yml, ios/NuruNuru.xcodeproj/project.pbxproj.

## [2026-05-29] fix | iOS passkey login after reinstall

- iOS loginWithPasskey() no longer stops at missing local NosskeyKeyInfo. When UserDefaults was wiped by app reinstall, it now runs a discoverable Passkey assertion with no allowedCredentials so iCloud Keychain can show the RP's Passkey picker, then rebuilds and saves credentialId / pubkey / salt before entering the session.
- LoginView now surfaces passkey-login errors in the initial button stack, so missing or failed credentials are visible instead of looking like an inert button.
- Source: ios/NuruNuru/Data/NosskeyManager.swift, ios/NuruNuru/ViewModels/AuthViewModel.swift, ios/NuruNuru/Views/Screens/LoginView.swift, docs/wiki/features/onboarding.md, docs/wiki/nips/nosskey.md.

## [2026-05-29] culture | Platform別実機テスト時間割と反復テスト項目を追加

- 月曜リリース列車 / Nuru Production System に、platform ごとの dedicated 実機テスト時間割を追加。初期値は午前 iOS、午後 Android、夕方 Web / cross-platform parity、最後に GO / HOLD / STOP / HOTFIX 判定。
- 「日常的に使ってみた」だけでは release test と呼ばない方針を明文化。事前定義した test case を PASS / FAIL / BLOCKED / NOT RUN で記録し、重要項目は最低2周 (機能確認 + 再現性/回帰確認) する。
- iOS / Android / Web / cross-platform の標準実機テスト項目を追加。Keychain / Secure storage、passkey/nsec/外部署名、投稿140文字、画像、timeline、Talk MLS、通知/share、CameraX/Media3、relay/security/parity/NIP behavior を release candidate ごとの確認対象にした。
- ADR-0012 の中核判断に「platform ごとの dedicated 実機テスト slot」と「test case に基づく反復確認」を追加。トヨタ級の世界最高品質と安定供給を NPS の標準作業へ接続。
- Source: User directive (2026-05-29), docs/wiki/culture/release-quality.md, docs/wiki/decisions/adr-0012-monday-release-nuru-production-system.md.

## [2026-05-29] culture | 月曜リリース列車と Nuru Production System を制度化

- Thema DAY「企業文化 / カルチャー構築」の方針として、週刊少年ジャンプ型の月曜リリース列車を文化規約に追加。定期 release は原則 月曜日 (JST) に集約し、品質ゲート不通過時は GO ではなく HOLD / STOP として次の列車へ回す。
- トヨタ生産方式 (TPS) を Nostr クライアント向けに翻訳した Nuru Production System (NPS) を定義。Jidoka / Andon / Just-in-Time / Heijunka / Standardized Work / Genchi Genbutsu / Kaizen を、秘密鍵・署名・relay・Talk MLS・platform parity・日本語 UI の品質保証に接続。
- 新規ページ [[culture/release-quality]] を追加。週間リズム、release skip / hotfix 条件、品質ゲート (Build/Test, Nostr protocol, UX/Japanese quality, Stability, Release communication)、Nostr 最高品質の7軸、metrics、roles、Open Questions を整理。
- 新規 ADR [[decisions/adr-0012-monday-release-nuru-production-system]] を Accepted で追加。CI / scheduler / GitHub labels による enforcement は未実装だが、release / quality / culture の判断規約として即日有効。
- AGENTS.md, docs/wiki/index.md, docs/wiki/culture/principles.md, docs/wiki/decisions/README.md に新規ページ / ADR へのリンクを追加。
- Source: User directive (2026-05-29), docs/wiki/culture/release-quality.md, docs/wiki/decisions/adr-0012-monday-release-nuru-production-system.md, AGENTS.md, docs/wiki/index.md, docs/wiki/culture/principles.md, docs/wiki/decisions/README.md.

## [2026-05-28] strategy | ぬるる IP マーケティング & 成長戦略 v1.0 起票

- 新規ページ `docs/wiki/strategy/nuruh-ip-2026-05-28.md` を追加。ぬるぬる公式マスコット「ぬるる」を**商品ではなく住人**として育てる長期 IP 戦略 v1.0。中核構成は以下5層:
  1. **ぬるるドクトリン四箇条** (IP 憲章) — 第一条「ユーザーである」/ 第二条「商品ではない」/ 第三条「溶ける」/ 第四条「誰のものでもない」。
  2. **3層接点モデル** — 接触 (Surface) / 関与 (Engage) / 共生 (Symbiosis)。層3に直接介入しない設計思想。
  3. **5つの成長ループ** — 日常投稿 / 二次創作 / プロダクト内 / 物理 / コラボ (年4本上限)。
  4. **ライセンス階段 L0〜L4** — L0 鑑賞 / L1 二次創作 / L2 同人物販 (**年商100万円まで届出不要**) / L3 商用 (Design Crit) / L4 公式コラボ (招待制)。商標は最小限取るがファンの利用制約には使わない。
  5. **KPI 6軸 + ガードレール 7軸** — 文化指標を短期数値で曲げない二層構造。
- 上記四箇条を [[decisions/adr-0011-nuruh-ip-doctrine]] として ADR 化 (Proposed)。変更には Design Crit 2回連続の合意を要する。具体運用 (KPI 値・ライセンス・施策内訳) は戦略文書側で更新可能とし、別 ADR 不要。
- **即決3事項** (Design Crit にかける提案): (a) L2 同人物販閾値 100万円、(b) ぬるる本体 npub は NIP-46 bunker + 2-of-N 合議、(c) 「ぬるるの日」を **8月8日** に制定。
- **90日 (Phase 0)** チェックリスト: Week 1-2 で四箇条 ADR / npub + bunker / ガイドライン / 8表情 NIP-30 絵文字パック配布。Week 3-4 でおやつ15時 + おやすみ23時 開始。Week 5-8 で月例とろけ便り第1号と Talk「ぬるるルーム」開設。Week 9-13 でステッカー小ロット + 90日レビュー。
- **24ヶ月ロードマップ** は [[decisions/adr-0008-four-freedoms-mission|Four Freedoms]] と Phase 同期 (Phase 0〜4)。
- **体制**: 最小3名 (代弁者2 + 兼任1) で開始、Phase 2 までに5名 (代弁者 / 図鑑キュレーター / 儀式運用 / Design Crit 主宰 / コラボ窓口) に拡張。
- **doctrine alignment**: 五箇条すべて (第一条〜第五条) との整合を本文に明示。特に第三条「複雑さは裏側に隠す」(NIP-46 bunker / NIP-30 / NIP-65 を表層に出さない) と第五条「かわいさと厳格さを同時に」(かわいいキャラの 2-of-N 鍵管理) を強調。
- `docs/wiki/index.md` の Strategy セクションと Decisions セクションを更新 (ADR-0010 / ADR-0011 を追記)。
- Source: `docs/wiki/strategy/nuruh-ip-2026-05-28.md`, `docs/wiki/decisions/adr-0011-nuruh-ip-doctrine.md`, `docs/wiki/index.md`, `docs/wiki/culture/principles.md`, `docs/wiki/culture/not-doing.md`, `docs/wiki/culture/copy-style.md`, `docs/wiki/culture/four-freedoms.md`, `docs/wiki/decisions/adr-0008-four-freedoms-mission.md`.

## [2026-05-27] improvement | Feedback MCP query and classifier tuning

- Updated feedback collection defaults to search ぬるぬる, #ぬるぬるはじめました, nullnull Android, nullnull iOS, nullnull, and via nullnull variants.
- Improved feedback classification with client/via metadata platform inference, signing and post_content areas, URL/OGP/link signals, and safer grouping for vague unknown reports.
- Source references: `scripts/mcp/nurunuru-feedback.mjs`, `src/__tests__/mcp/nurunuru-feedback.test.ts`, `docs/wiki/operations/feedback-loop.md`.

## [2026-05-26] feature | NIP-50 feedback-loop MCP

- Added `scripts/mcp/nurunuru-mcp.mjs`, a stdio MCP server and CLI exposing `nip50_search`, `collect_feedback`, `classify_feedback`, and `draft_github_issue` tools for Nostr feedback automation.
- Added NIP-50 / search.nos.today feedback classification helpers, Vitest coverage, and package scripts (`mcp:nurunuru`, `feedback:search`, `feedback:collect`) for daily feedback triage.
- Documented the feedback-loop boundaries: Issue / PR drafts may be automated, but merge, Design Crit, secrets / signing changes, and production release remain human-approved.
- Source references: `scripts/mcp/nurunuru-mcp.mjs`, `scripts/mcp/nurunuru-feedback.mjs`, `src/__tests__/mcp/nurunuru-feedback.test.ts`, `docs/wiki/operations/feedback-loop.md`, `docs/wiki/nips/nip-50.md`, `docs/wiki/features/search.md`, `docs/wiki/nips/README.md`.

## [2026-05-26] fix | Timeline/passkey/link notification hardening

- Android regular timeline fetches now exclude NIP-71 short-video kind 34236 while keeping text notes, long-form posts, and Kind 6 reposts. Repost unwrap now preserves repostedBy / repostTime so follow timelines show repost provenance instead of looking like unfollowed direct posts.
- Android and iOS infinite-scroll load-more no longer permanently disables older pagination after a single empty/failed older-page response.
- Android post text URL spans are clickable and open through LocalUriHandler; URL previews remain rendered below the text.
- Android passkey login is visible on supported devices from the initial login choices and from other login methods; missing local credential is reported by AuthViewModel.
- iOS passkey-backed publishing warms the NosskeySigner cache before repository publish, fixing cold-cache failures after the first post. Logout now explicitly zeroizes the passkey signer cache.
- Notification cache restore is type-whitelisted on Android and iOS so unexpected notification kinds/types are not displayed.
- Source: android/app/src/main/kotlin/io/nurunuru/app/data/NostrRepositoryTimeline.kt, android/app/src/main/kotlin/io/nurunuru/app/viewmodel/TimelineViewModel.kt, android/app/src/main/kotlin/io/nurunuru/app/ui/components/PostContent.kt, android/app/src/main/kotlin/io/nurunuru/app/ui/screens/LoginScreen.kt, android/app/src/main/kotlin/io/nurunuru/app/data/NostrRepositoryNotifications.kt, ios/NuruNuru/Data/NostrRepository.swift, ios/NuruNuru/ViewModels/AuthViewModel.swift, ios/NuruNuru/ViewModels/TimelineViewModel.swift, ios/NuruNuru/Data/NostrRepository+Notifications.swift.

## [2026-05-23] sec | Dependabot 9 件 (high 2 / moderate 2 / low 5) 解消

- GHSA-hc3c-63hc-2r9f **high** `libcrux-chacha20poly1305 0.0.7 → 0.0.8` — Overlong ciphertext buffer での panic を修正。`libcrux-aead` も 0.0.7→0.0.8 へ追従。
- GHSA-82j2-j2ch-gfr8 **high** `rustls-webpki 0.103.9 → 0.103.13` — Malformed CRL BIT STRING による panic / DoS を修正。同時に GHSA-pwjx-qhcg-rvj4 (moderate, CRL distribution-point matching) と GHSA-965h-392x-2mh5 / GHSA-xgp8-3hg3-c2mh (low, URI / wildcard name constraints) も同バージョンで解消。
- GHSA-qx2v-qp2m-jg93 **moderate** `postcss 8.4.38 → 8.5.15` (>=8.5.10) — `</style>` の unescape による XSS を修正。`next` 内部の transitive な 8.4.31 を抑止するため `overrides` を併用。
- GHSA-cq8v-f236-94qc **low** `rand 0.8.5 → 0.8.6`, `0.9.2 → 0.9.3`, `0.10.0 → 0.10.1` — カスタムロガー + `rand::rng()` での unsoundness を修正。
- 直接 `cargo update --precise 0.0.8` は `hpke-rs-libcrux 0.6.1` の `libcrux-aead = "0.0.7"` ピンに阻まれるため、`rust-engine/Cargo.toml` に `[patch.crates-io]` セクションを追加し `cryspen/hpke-rs` の `franziskus/bump-libcrux` PR (#154, rev 110d7477) を一時的に取り込んだ。upstream が 0.6.2 をリリースしたら patch を撤去予定。
- 検証: `cargo check --workspace` 通過 / `cargo test -p nurunuru-core --no-run` 通過 / `npm run test` 189 passed / `npm audit` 0 vulnerabilities / `npm run tokens:check` in sync。
- 変更ファイル: `package.json`, `package-lock.json`, `rust-engine/Cargo.toml`, `rust-engine/Cargo.lock`。Rust ソースコード (`lib.rs` 等) は無変更のため Android `.so` の再ビルドは次回リリース時で十分。

## [2026-05-23] perf | Send-button spinner reflects only the MLS send call (not pre-flight)

- `TalkViewModel.sendMessage` on both iOS and Android no longer sets `sendingMessage = true` at the top of the function. The flag is now set immediately before the `repository.sendMlsMessage(...)` call so the spinner covers only the actual MLS network round-trip.
- Previously the spinner stayed visible for up to ~110s (Android) or ~77s (iOS) because the pre-flight chain (DM canonicalize → catch-up 15s → repair 30s → deep-catch-up 35s) ran while `sendingMessage` was true. User reported the spinner staying on for "about 1 minute" after tapping send.
- The optimistic bubble + cleared composer remain the affordance for "message accepted". The spinner is now only the affordance for "network send in flight". `abortOptimisticSend(...)` still defensively clears the flag on every early-return path.
- Source: `android/app/src/main/kotlin/io/nurunuru/app/viewmodel/TalkViewModel.kt`, `ios/NuruNuru/ViewModels/TalkViewModel.swift`, `docs/wiki/ui/android-ios-sync.md`.

## [2026-05-23] perf | Instant send + drop sender-name label in Talk chat

- Removed the sender display-name `Text` rendered above incoming bubbles on both platforms (`TalkView.swift` `MessageBubble`, `TalkComponents.kt` `MlsMessageBubble`). The avatar already carries identity; duplicating the name on every consecutive incoming message broke the LINE silhouette.
- Moved the optimistic-bubble insertion to the very top of `sendMessage` on both platforms (`TalkViewModel.swift` and `TalkViewModel.kt`). Previously the optimistic `MlsMessage` was appended **after** DM canonicalization, `fetchMlsMessages(repairFull = false/true)` catch-up, and gap-repair with `withTimeout(15_000)` + `withTimeout(30_000)`, so the user could wait up to 45 s between tapping send and seeing their own bubble. Now the bubble appears in the same UI frame as the tap; the heavy convergence work continues in the background. If a later DM remap picks a different canonical group, the optimistic message's `groupIdHex` is rewritten in place rather than dropped/re-added.
- Hardened the optimistic-send path: Android now uses `abortOptimisticSend(...)` and `keepOptimisticBubbleIn(...)`; iOS uses `abortOptimisticSend(...)` and `keepOptimisticBubble(in:)`. Pre-send abort paths remove the temporary bubble and clear `sendingMessage`; canonical remaps preserve or restore the temporary bubble in the target group instead of losing it during message-list replacement.
- Removed the remaining micro-animations on the send affordance and auto-scroll. iOS `TalkView.swift`: dropped `.animation(.easeInOut(duration: 0.15), value: hasText)` on the send button so the mic ↔ paper-plane swap happens on the same frame as the tap, and wrapped both `proxy.scrollTo` calls in a `Transaction` with `disablesAnimations = true` so no enclosing implicit animation can attach. Android `TalkComponents.kt`: replaced the two `animateColorAsState(tween(150))` on the send button background/tint with plain conditional values (and removed the now-unused `animateColorAsState` / `tween` imports). Net effect: every send tap commits in <16 ms with zero easing between tap, bubble insertion, and bottom-snap.
- Replaced the auto-scroll easing in the chat list with instantaneous jumps (`scrollToItem` on Android `TalkScreen.kt`, plain `proxy.scrollTo(..., anchor: .bottom)` without `withAnimation` on iOS `TalkView.swift`). The optimistic bubble is already at the bottom, so any easing only adds perceived latency.
- Documented the new "instant send" rule and the no-sender-name rule in `docs/wiki/ui/android-ios-sync.md`.

## [2026-05-23] fix | Talk chat icon parity + drop read-receipt label

- Aligned Android Talk chat icons with the iOS SF Symbols set. Header `⌕ / ☎ / 31 / ☰` text glyphs replaced with `Icons.Outlined.Search / Call / CalendarToday / Menu` in `TalkScreen.kt`; the obsolete `LineCalendarAction` and Unicode-symbol `LineHeaderAction(symbol: String, …)` helper were removed and the helper now takes an `ImageVector` + `onClick`.
- Composer left-side icons in `TalkComponents.kt` rebuilt: `Icons.Outlined.Add` (＋), `Icons.Outlined.PhotoCamera` (camera), `NuruIcons.Image` (photo). Previously the camera slot reused `NuruIcons.Image`, so two identical photo glyphs were shown.
- Removed the outgoing 「既読」 label on both platforms (`TalkView.swift` `MessageBubble`, `TalkComponents.kt` `MlsMessageBubble`) because end-to-end read receipts are not yet observable; only the timestamp remains beside the bubble.
- Updated `docs/wiki/ui/android-ios-sync.md` with the explicit header / composer icon mapping and the no-read-receipt rule.

## [2026-05-23] fix | LINE-style native Talk chat chrome

- Updated native Talk chat UI on iOS and Android toward the LINE reference: compact black header, LINE-like action icons, green outgoing bubbles, dark-gray incoming bubbles, outside timestamps, and LINE-style composer controls.
- Hidden the global bottom tab bar while a Talk conversation is open so the chat and composer own the full screen; the tab bar remains visible on the Talk list.
- Removed visible MLS group IDs from chat headers, composers, and conversation rows. Added the full group ID to the group information sheet/modal on iOS and Android.
- Source: `ios/NuruNuru/Views/Screens/TalkView.swift`, `ios/NuruNuru/Views/Sheets/GroupInfoSheet.swift`, `ios/NuruNuru/Views/Screens/MainTabView.swift`, `android/app/src/main/kotlin/io/nurunuru/app/ui/screens/TalkScreen.kt`, `android/app/src/main/kotlin/io/nurunuru/app/ui/components/TalkComponents.kt`, `android/app/src/main/kotlin/io/nurunuru/app/ui/components/GroupInfoModal.kt`, `android/app/src/main/kotlin/io/nurunuru/app/ui/screens/MainScreen.kt`.

## [2026-05-23] feat | iOS MLS peer-epoch catch-up parity (issue #190)

- Wired the Issue #183 Rust FFI (`mls_catch_up_to_peer`,
  `mls_prune_replay_cache`, `mls_replay_cache_size`) through
  `NuruNuruFFIBridge` + `MlsFFIStub` + `NuruNuruFFILiveClient`. Added
  Swift mirrors `FfiMlsCatchUpStatus` and `FfiMlsCatchUpReport`.
- Added `NostrRepository.deepCatchUpMlsGroup`,
  `pruneMlsReplayCache`, `mlsRecoveryStatusFor`,
  `clearMlsRecoveryStatus`, and `recreateDmConversation` plus the
  `MlsRecoveryStatus` / `MlsDeepCatchUpResult` Swift types — names and
  semantics mirror Android one-to-one. The replay-cache prune now
  piggy-backs on the first `fetchMlsGroups` call per session via a
  `mlsReplayCachePrunedThisSession` gate.
- Added `TalkViewModel.recoveryStatus`, `recreatingConversation`,
  `recreateActiveDmConversation`, and `dismissRecoveryBanner`. Deep
  catch-up is escalated after the standard preflight in `sendMessage`
  and after `repairCurrentGroup` leaves a DM gap; the cached banner
  state is restored on `openGroup` and cleared on `closeGroup`.
- Added the SwiftUI `MlsRecoveryBanner` to `TalkView.swift` with copy
  matching Android exactly (「メッセージを完全に復元できません」 +
  「作り直す」 / 「後で」). Native SwiftUI per `ios/GUARDRAILS.md`.
- Rebuilt the `NuruNuruFFI.xcframework` (device + sim slices) so the
  new UniFFI symbols are linkable; verified
  `_uniffi_uniffi_nurunuru_fn_method_nurunuruclient_mls_catch_up_to_peer`
  / `mls_prune_replay_cache` / `mls_replay_cache_size` are exported
  from both slices. iOS Simulator (iPhone 17) Debug build succeeded
  with no new errors.
- Closes the AC4 requirement from issue #183.

## [2026-05-23] fix | Android MLS peer-epoch catch-up (issue #183)

- Added a sidecar SQLite replay cache (`{mls_db_path}.replay.sqlite3`, 30-day
  TTL, 2,000-row per-group cap) so peer Kind-445 wrappers survive relay aging
  and app process death. Cache writes are best-effort and never alter
  PR #180's receive-path semantics.
- Added `MlsManager::catch_up_to_peer(group_id_hex, candidates)` which
  replays caller-supplied + cached wrappers in `created_at` order across up
  to 8 retry passes and returns a typed `MlsCatchUpReport` with status
  `Recovered` / `PartiallyRecovered` / `NotRecoverable` / `NoSuchGroup`.
  Never touches pending-commit state, so PR #180's invariants are preserved.
- FFI: added `mls_catch_up_to_peer`, `mls_prune_replay_cache`,
  `mls_replay_cache_size` plus `FfiMlsCatchUpReport` /
  `FfiMlsCatchUpStatus`; regenerated Kotlin bindings and cross-compiled the
  arm64-v8a `.so`.
- Android: `NostrRepositoryTalk.deepCatchUpMlsGroup` orchestrates the wider
  Kind-445 relay pull and the FFI catch-up call; `recreateDmConversation`
  automates "workaround A" (leave + create fresh DM). `TalkViewModel`
  escalates to deep catch-up after every standard repair and after the
  send-preflight fullRepair fallback; new `recoveryStatus` UI state plus
  `MlsRecoveryBanner` in `TalkScreen` prompts the user with
  「メッセージを完全に復元できません — 作り直す / 後で」 when the missing
  Commit is no longer retrievable from configured relays and is not in the
  cache (AC2).
- Tests: new `rust-engine/nurunuru-core/tests/issue_183_catch_up.rs`
  (8 tests, all passing); existing 47-test core suite still green.
- Wiki: new `docs/wiki/features/mls-peer-epoch-catch-up.md` and updated
  `docs/wiki/index.md`.

## [2026-05-21] setup | Initial LLM Wiki scaffold

- `AGENTS.md` に LLM Wiki 運用ルールを追加。
- `docs/wiki/` 配下に初期構成を作成。
- Core / Platforms / Features / UI / NIPs / Decisions の最小ページを追加。
- 真実の源泉はソースコード・design tokens・設計文書であり、Wiki は派生ナビゲーション層であることを明記。

## [2026-05-21] docs | Code-backed Wiki corrections and NIP audit

- Corrected stale PostActions documentation: current code has like / repost / zap plus optional bookmark, with no reply button.
- Replaced the short Supported NIPs line with a code-backed NIP support table in `docs/wiki/nips/README.md`.
- Added high-priority pages: `features/image-upload`, `features/talk`, `features/relay-management`, `ui/post-row`, `nips/nip-46`, `nips/nip-57`, `nips/nip-65`, `nips/nip-70`, `nips/nip-71`, and `nips/nip-98`, plus `lint-report`.
- Updated platform and feature pages with source references from Android, iOS, Web, and Rust code.

## [2026-05-21] docs | Detailed NIP boundary pages

- Added detailed pages for `nip-17`, `nip-25`, `nip-30`, `nip-51`, `nip-58`, and `nip-59`.
- Clarified the native Talk boundary: NIP-17 remains legacy/compatibility while Talk is Marmot MLS-oriented; NIP-59 kind 1059 is used for Marmot Welcome delivery.
- Updated `index.md`, `nips/README.md`, and `lint-report.md` to reflect the new pages and remaining doc targets.

## [2026-05-21] docs | Wiki maintenance, parity, glossary, and ADRs

- Removed stale `ios/DESIGN.md` and `ios/SYNC_PLAN.md` links from `AGENTS.md`, replacing them with existing guardrail/wiki links.
- Added `logs/` to `.gitignore` after identifying it as local Android/iOS Talk/Marmot diagnostic output.
- Added `docs/wiki/platforms/parity-matrix.md` and `docs/wiki/glossary.md`.
- Added ADRs for native Marmot MLS Talk, iOS NIP-46 external signing, design tokens, PostActions, and Web Rust bridge stubs.
- Updated `docs/wiki/nips/README.md` with a `Level` column and separated official numbered NIPs from ecosystem/BUD/project-specific protocols.
- Added `scripts/wiki-lint.mjs` for basic Wiki health checks.

## [2026-05-21] tooling | Wiki lint npm script

- Added `npm run wiki:lint` to `package.json` and documented it in `AGENTS.md`.
- Verified the lint script directly with Node because this tool environment does not expose `npm` on PATH.

## [2026-05-21] docs | Low-priority wiki completion

- Added GitHub Actions workflow `.github/workflows/wiki-lint.yml` to run `npm run wiki:lint` on relevant PR/push changes.
- Split Talk documentation into Marmot MLS internals, relay strategy, debugging guidance, and Android/iOS parity pages.
- Added detailed pages for `nip-04`, `nip-18`, `nip-23`, and `nip-44`.
- Updated `index.md`, `nips/README.md`, and `lint-report.md` with the new pages and remaining future targets.

## [2026-05-22] culture | NuruNuru Charter v0.1 と4軸自由ドクトリンを起票

- Theme Day「企業文化・カルチャー構築」の成果として `docs/wiki/culture/` を新設。
- 北極星 + 五箇条を [[culture/principles]] に明文化 (Charter v0.1)。
- [[culture/not-doing]] にやらないことリストを起票 (体験 / 日本語 / 一貫性 / 鍵 / 設計判断 / NIP / 経済 / 配布 / 短期 KPI)。
- [[culture/design-crit]] に Weekly Nuru Design Crit の運用 (沈黙批評 → 発話批評 → 4 ラベル) を定義し、[[decisions/adr-0007-design-crit-ritual]] として制度化を起票。
- [[culture/copy-style]] に日本語コピー規約 (直訳禁止 / 既存採用語保護 / 場面別ガイド) を起票。
- [[culture/llm-onboarding]] に LLM コントリビュータ向けの編集前チェックリストと出力規約を起票。
- ユーザーからの「業界の10年先を行く」「経済 / 配布の自由も視野に入れる」という方針を [[culture/four-freedoms]] に整理し、[[decisions/adr-0008-four-freedoms-mission]] として長期ミッションを Proposed で起票。
- 新規 ADR テンプレート [[decisions/_template]] を追加。
- `.github/pull_request_template_ui.md` に UI 変更 PR チェックリストを追加。
- `AGENTS.md` に Culture セクションを追加し、Wiki から AGENTS へのエントリを確立。
- `docs/wiki/index.md` に Culture セクションを追加し、ADR-0007/0008 とテンプレートをリストに追加。
- 本件はコード変更を伴わない文化憲章 (Proposed)。Phase 1 (Talk Marmot 完成) → Phase 2 (経済) → Phase 3 (配布) のロードマップは [[culture/four-freedoms]] を参照。

## [2026-05-22] security | Issue #181 MLS DB encryption (Android verified, iOS shipped)

- Rust core: `mls_db_path_for(db_path)` as single source of truth for the on-disk MLS SQLite path. `bind_mls_for_pubkey` errors hard on `had_key && bind_failed` (B5). New `mls_is_encrypted() -> Option<bool>` (B7) lifted through UniFFI + napi-rs for app-layer assertion.
- FFI: `NuruNuruClient::new_with_mls_db_key` / `new_read_only_with_mls_db_key` validate 32-byte key length before SQLCipher bind. `derive_mls_db_key_from_secret(secret_hex, app_salt)` exposes HKDF-SHA256 derivation to Kotlin + Swift.
- Android: new `MlsDbKeyStore` (HKDF for internal signer, `EncryptedSharedPreferences` + `MasterKey` for external signer, `synchronized` lock + `commit()` for B4 race). New `MlsLegacyMigration` (content-based plaintext detection via FFI `mls_db_path_for`, runs every launch — B1+B2). `NuruNuruApp.onCreate()` purges before `initEngine()` (M5). Logout clears external key before `prefs.clear()`.
- iOS: mirror `MlsDbKeyStore` (Keychain `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` + `NSLock`) and `MlsLegacyMigration` (`isExcludedFromBackup` on DB/WAL/SHM — M6 partial). `MlsFFILiveClient` uses new encrypted ctors with `inout Data` zeroize via `Data.resetBytes(in:)` (B3). `mlsIsEncrypted() -> Bool?` lifted to `MlsFFIBridge` protocol + stub.
- Verified: a physical Android device runtime confirms plaintext purge + SQLCipher header (`53 51 4c…00` → `21 1d c0…c4`) + `MLS DB encrypted (SQLCipher) — issue #181 guard OK` log. iOS xcodebuild for iPhone 17 simulator returns BUILD SUCCEEDED.
- Bindings: `gen_swift.sh` switched to debug build (workspace `release` profile has `strip = true` which removes UniFFI metadata `.symtab`, causing silent missing-types in bindgen output).
- Wiki: added [[features/mls-db-encryption]] (threat model + verification trace) and [[decisions/adr-0009-mls-db-encryption]] (rationale + alternatives + consequences). Updated `index.md`.
- Open: CI lint to block legacy unkeyed ctor reintroduction (M2), Settings UI status indicator (M4), release notes + CHANGELOG (M6 user-facing).

## [2026-05-23] security | Issue #181 follow-up (M2 CI guard + M6 release notes; M4 dropped)

- M2 (CI guard): added `scripts/issue-181-guard.mjs` + `npm run lint:issue-181`. Walks `ios/NuruNuru/` and `android/app/src/main/kotlin/`, fails on reintroduction of unkeyed `NuruNuruClient(secretKeyHex:)` / `NuruNuruClient.newReadOnly(pubkeyHex:)` ctors. Skips generated `bindgen/` directories. Verified: 214 files scanned, 141 596 pattern checks, 0 violations on clean tree; negative test with 2 injected violations reports both with file:line + remediation hint.
- M6 (release notes): added `[Unreleased] > Security` + `Upgrade notes` to `CHANGELOG.md` documenting the SQLCipher migration and the unavoidable past-message loss on upgrade. Added `docs/release-notes/issue-181-mls-db-encryption.md` with JP + EN short forms for zapstore / GitHub Release / Google Play / TestFlight, plus a support-facing FAQ ("過去メッセージが見えなくなった理由").
- M4 (Settings UI "encrypted ✓"): dropped by product decision. The runtime guard already hard-fails on missing encryption (`MLS DB encrypted (SQLCipher) — issue #181 guard OK` line is mandatory), so a green checkmark would be redundant UI noise without an actionable user signal.
- Verification log unchanged: a physical Android device + a physical iPhone 12 mini both show the guard line on cold launch + SQLCipher random-bytes header on disk. Talk receive-loop logic (PR #180) is NOT touched by this change — `git diff HEAD --stat -- ios/NuruNuru/Data/NostrRepository+Talk.swift android/app/src/main/kotlin/io/nurunuru/app/data/NostrRepositoryTalk.kt` returns empty.
- Unrelated open issue surfaced during log analysis: iOS-sent kind:445 messages occasionally retry-queue on Android as `state_not_ready` (MDK epoch lag). Tracked separately — not an Issue #181 regression.

## [2026-05-23] ux | Android Talk pull-to-refresh and auto-repair

- Added Android open-conversation pull-to-refresh for Talk MLS history catch-up, aligned with the existing Material3 pull-to-refresh pattern used by Timeline.
- Added guarded Android Talk auto-repair after repeated empty Kind-445 relay fetches for an already-populated conversation, using relay fetch stats from `NostrRepositoryTalk.kt`.
- Kept explicit Group Info "メッセージを修復" as the stronger manual repair path while pull/auto refresh avoid clearing pending commits.
## [2026-05-23] fix | Android Talk pull-to-refresh top-edge fallback

- Root cause: Material3 `PullToRefreshContainer` only receives downward drags via `nestedScrollConnection` when the inner scrollable is already at scroll position 0. `GroupChatScreen` runs `listState.animateScrollToItem(messages.size - 1)` on every message update so the LazyColumn is almost always scrolled toward the newest message; the user's pull gesture was consumed by the list as a normal upward scroll and never reached `PullToRefresh`.
- Fix: added a `pointerInput` top-edge drag detector around the message-area `Box` in `TalkScreen.kt`. Touches that start within ~120dp of the conversation viewport top and accumulate ~72dp of downward travel call `pullRefreshState.startRefresh()` directly, which triggers the existing `refreshCurrentGroup()` → `runMlsRepair(source = "pull", clearPendingCommit = false)` path. The Material3 `nestedScrollConnection` is kept as the secondary path for the case where the user has scrolled to the oldest message.
- Visual layout is unchanged (oldest → newest top → bottom, auto-scroll to newest); only the gesture surface is extended.
- Verified by rebuild + reinstall: `BUILD SUCCESSFUL`, versionName=1.5.0, on a physical Android device. iOS parity for this fallback is tracked separately.
## [2026-05-23] ux | Android Talk pull-to-refresh redesigned for LINE-grade parity

- Removed the temporary TopBar refresh icon and the `gid:xxx msg:N` debug subtitle on the conversation screen. Both were diagnostic, not aligned with the LINE-grade visual language.
- Changed conversation auto-scroll to only follow new messages when the user is already within 3 items of the list bottom (`lastVisibleIndex >= totalItems - 3`). While the user is scrolled up to read history, the LazyColumn stays put, so the Material3 `PullToRefreshContainer.nestedScrollConnection` can receive downward drags and pull-to-refresh works naturally from any scroll position.
- The conversation `pointerInput` top-edge fallback is retained as a secondary trigger.
- Conversation pull-to-refresh now performs a STRONG repair (`clearPendingCommit = true`) instead of the weak `repairFull=true`-only path. iOS frequently advances MLS epoch ahead of Android; an explicit user-initiated refresh should clear any stranded Android pending commit so iOS-originated messages decrypt. This matches the strength of the Group Info「メッセージを修復」action.
- Added pull-to-refresh to the Talk list (GroupListScreen) across all three filter pages (すべて / 友だち / グループ) via a shared `PullToRefreshState` and `refreshGroupList()` on the ViewModel. Achieves iOS Talk-list parity.
- Verified by rebuild + reinstall: `BUILD SUCCESSFUL`, versionName=1.5.0, on a physical Android device.
## [2026-05-23] fix | Android Talk render decrypted iOS messages despite residual MLS gaps

- Root cause for "iOS new message fetched but not shown on Android": Android was successfully fetching Kind-445 events and could apply at least one iOS-originated application message, but `TalkViewModel.startMessageStream()` stopped the polling loop on a residual DM MLS gap before writing the normalized message list into `_uiState.messages`. Logs showed `application id=... len=3` followed by residual `state_not_ready` retryables, so the relay/decrypt path was not the only issue; the UI render path was dropping usable history.
- Fix: update `_uiState.messages` before handling residual DM gap diagnostics, and do not break the stream solely because `mlsStateGapCount() > 0` when usable normalized history exists. Manual pull and guarded auto-repair remain responsible for reducing the remaining gap.
- Kept the LINE-grade Talk UX changes: no TopBar refresh icon, no debug `gid:/msg:` subtitle, Android Talk-list pull-to-refresh added, and conversation pull-to-refresh uses strong repair.
- Verified by rebuild + reinstall + launch on a physical Android device: versionName=1.5.0.
## [2026-05-23] fix | iOS Talk fresh DM isolation and cache-first open

- iOS explicit DM creation now treats「新しくトークを作成」as a hard reset for that DM conversation key: older sibling DM groups are locally hidden, removed from the visible ViewModel lists, and the fresh group is pinned as the canonical send/open target.
- iOS `fetchMlsGroups` now applies DM hidden tombstones when an unhidden sibling DM exists for the same peer, so old hidden DM history does not resurrect after app restart and get merged into the new Talk. If all valid peer DMs are hidden (legacy auto-recovery tombstone bug), the valid shared group remains visible to avoid orphan sends.
- iOS `loadGroups` now paints locally-known Rust SQLite groups before relay Welcome/profile discovery, and `openGroup` paints local SQLite message history immediately (then local sibling histories) before relay-backed repair/canonical scanning. Relay catch-up remains background refinement, so opening Talk after launch is cache-first.
## [2026-05-23] fix | iOS Talk exited groups stay hidden

- iOS Talk now records explicit MLS exits in the persistent left-group blocklist and applies that blocklist in both `getLocalMlsGroups` (cache-first startup) and `fetchMlsGroups` (relay refresh).
- `visibleFfiMlsGroups` now treats local hidden/left tombstones as authoritative for DMs and named groups, and no longer auto-prunes tombstones just because the visible list would otherwise be empty. This prevents a deliberately empty Talk list after leaving the last group from being repopulated from Rust SQLite/relay state.
- `TalkViewModel.leaveGroup` removes exited named groups from both visible lists immediately, mirrors DM sibling exits into the persistent left set, and clears fresh-DM session pins for exited groups.

## [2026-05-23] feat | Onboarding tutorial post step (#nostrはじめました)

- Added a new "tutorial" step between `profile` and `success` in the 新規登録 (sign-up) wizard across all 3 platforms.
- The step pre-fills `#nostrはじめました\n`, enforces 140-char limit, lets the user freely edit / append, and publishes a kind-1 note via the existing publish path with auto-extracted `t` tags (lowercased). The `nostrはじめました` tag is auto-appended to both content and `t` tags if the user removes it.
- Web (`components/SignUpModal.js`): new `tutorial` step state + `handlePostTutorial()` using `createEventTemplate(1, ...)` → `signEventNip07` → `publishEvent`. Progress bar reflects 6 segments (was 5).
- Android (`SignUpModal.kt` + `AuthViewModel.kt`): new `TutorialStep` composable + `AuthViewModel.publishTutorialPost(signer, content, relays)`. Uses the same temporary `NostrClient` + `NostrRepository` pattern as `publishInitialMetadata`, then calls `NostrRepository.publishNote(content, customTags = [["t", ...], ...])`.
- iOS (`LoginView.swift` + `AuthViewModel.swift`): new `SignUpTutorialStep` view + `AuthViewModel.publishTutorialPost(content:, relays:)`. Mirrors the Android pattern with a temporary `NostrRepository`, signing via `keyManager`-backed `signer`.
- Both confirmation copy ("投稿しました！" + 「タイムラインで「#nostrはじめました」を検索すると、同じ仲間が見つかります。」) and skip behavior are identical across platforms per [[ui/android-ios-sync]].
- New wiki page: [[features/onboarding]] documents the 6-step contract, tutorial-step semantics, hashtag handling, and per-platform notes.
- No NIP support change; no design-token change; no new dependency. AGENTS.md unchanged.

## [2026-05-23] polish | Onboarding tutorial step (production hardening)

- iOS `SignUpTutorialStep` の `TextEditor` に `scrollContentBackground(.hidden)` を追加。リポジトリ内の他 `TextEditor` 使用箇所 (PostSheet / QuoteRepostSheet / ReportSheet / 各 MiniApp) と同じ扱いに揃え、ダーク/ライトテーマでデフォルト背景 (白) が透けてしまう問題を防止。
- Android `AuthViewModel.publishTutorialPost` で `client.connect()` 以降を `try/finally` で囲み、例外パスでも必ず `client.disconnect()` を呼ぶよう修正 (リレー接続リーク防止)。
- iOS `AuthViewModel.publishTutorialPost` で `publishNote` 例外パスにも `await repo.client.disconnect()` を追加 (同上)。
- 仕様・UI フロー・wiki ドキュメント (`docs/wiki/features/onboarding.md` / `docs/wiki/index.md` / `docs/wiki/log.md`) に変更なし。`wiki-lint: 0 failure(s), 0 warning(s)`。

## [2026-05-23] polish | Onboarding tutorial step (UX revision: hashtag-at-end, green bubble, placeholder)

- ハッシュタグ配置を変更: pre-fill していた `#nostrはじめました\n` (本文先頭) を撤廃し、デフォルト本文を空に。投稿時に `publishTutorialPost` (Web/Android/iOS いずれも) が本文末尾へ改行 + `#nostrはじめました` を自動付与するため、結果として「本文 → 改行 → ハッシュタグ」という配置が常に成立する。本文を完全に空にしたまま投稿した場合は `#nostrはじめました` 単独で送信される。
- プレースホルダー追加: `いまどうしてる？\n#nostrはじめました` を薄い灰色 (`var(--text-tertiary)` / `nuruColors.textTertiary` / `theme.textTertiary`) で表示し、ユーザーが何を書けばよいかの例示にする。
  - Web (`components/SignUpModal.js`): `<textarea>` の `placeholder` 属性 + `placeholder:text-[var(--text-tertiary)] placeholder:opacity-70`。
  - Android (`SignUpModal.kt`): `OutlinedTextField` の `placeholder = { Text(TUTORIAL_PLACEHOLDER, color = nuruColors.textTertiary) }`。
  - iOS (`LoginView.swift`): `TextEditor` が placeholder API を持たないため、`ZStack(alignment: .topLeading)` で `content.isEmpty` 時だけ `Text(kTutorialPlaceholder)` を `theme.textTertiary` で重ね描画。`allowsHitTesting(false)` で下層 `TextEditor` にタップを通す。
- ブランド統一: チュートリアル吹き出しアイコンの色を pink (`#E91E63` / `Color.pink`) からぬるぬるブランドカラーの **LineGreen** に変更。
  - Web: `bg-pink-500/10` + `text-pink-500` → `rgba(6,199,85,0.1)` + `var(--line-green)`。
  - Android: `Color(0xFFE91E63)` → `LineGreen`。
  - iOS: `Color.pink` / `.pink` → `NuruColors.lineGreen`。
- 投稿ボタンの `enabled` 条件を緩和: 本文が空でも `#nostrはじめました` 単独投稿が可能になるよう、すべてのプラットフォームで「投稿中でなく、かつ 140 文字以下」のみを有効条件とした (空文字拒否を削除)。Android/iOS の `publishTutorialPost` も `trimmed.isEmpty()` の場合は `#nostrはじめました` を本文として送信する分岐を追加。
- 修正ファイル: `components/SignUpModal.js`, `android/app/src/main/kotlin/io/nurunuru/app/ui/components/SignUpModal.kt`, `android/app/src/main/kotlin/io/nurunuru/app/viewmodel/AuthViewModel.kt`, `ios/NuruNuru/Views/Screens/LoginView.swift`, `ios/NuruNuru/ViewModels/AuthViewModel.swift`, `docs/wiki/features/onboarding.md`。NIP サポート / design-token / 依存に変更なし。

## [2026-05-23] polish | Onboarding tutorial step (UX revision 2: visible hashtag + user-respect deletion)

- 「#nostrはじめました が見えないまま自動付与されるのは不信感を生む」「ユーザーが消したら消えたまま投稿したい」というユーザー要望に基づき、3 プラットフォームの仕様を以下の通り改訂:
  - **pre-fill 復活**: 既定本文を空ではなく `\n#nostrはじめました` に変更。エディタを開いた瞬間からハッシュタグが常時可視化される。1 行目を空にしてカーソルを先頭に置けば「本文 → 改行 → ハッシュタグ」の配置が自然に成立する。
  - **自動補完ロジック完全撤廃**: `publishTutorialPost` (Web/Android/iOS) から「本文末尾に `\n#nostrはじめました` を 3 分岐で付与する」処理を削除。ユーザーがハッシュタグ行を消したら、消した状態のまま送信される。
  - **t タグ抽出も意図尊重**: 本文中の `#xxx` のみを `["t", value]` として送信。ユーザーが `#nostrはじめました` を消していれば `t` タグも付かない (本文と `t` タグの内容が常に一致する規約)。
  - **空本文ガード追加**: 投稿ボタン enabled 条件を「投稿中でない && 140 文字以下 && trim 後 0 文字でない」に強化。pre-fill された `#nostrはじめました` を残せば自動的に enable のため、UX としては自然。Web は throw、Android/iOS は `return false` で空送信を防ぐ。
- 修正ファイル (コード 5 + ドキュメント 2):
  - `components/SignUpModal.js`: `TUTORIAL_DEFAULT_CONTENT` 復活 + `handlePostTutorial` 内の末尾自動補完 3 分岐削除 + 空本文 throw 追加 + ボタン disabled 条件に `trim().length === 0` 追加。
  - `android/.../ui/components/SignUpModal.kt`: `TUTORIAL_DEFAULT_CONTENT = "\n#nostrはじめました"` + KDoc 新仕様化 + 投稿ボタン `enabled = !isPosting && content.trim().isNotEmpty()`。
  - `android/.../viewmodel/AuthViewModel.kt`: `publishTutorialPost` の末尾自動補完 `when` ブロック削除 + 空本文ガード + KDoc 新仕様化。
  - `ios/.../Views/Screens/LoginView.swift`: `kTutorialDefaultContent = "\n#nostrはじめました"` + docstring 新仕様化 + `canPost` に空本文判定追加。
  - `ios/.../ViewModels/AuthViewModel.swift`: `publishTutorialPost` の末尾自動補完分岐削除 + 空本文 guard + docstring 新仕様化。
  - `docs/wiki/features/onboarding.md`: 「Tutorial step contract」セクションを pre-fill + no-auto-completion 規約に書き換え + Android/iOS の投稿ボタン条件を更新 + Open questions に「先頭改行 1 文字分の 140 文字制限への影響」を追記。
- finalize: Android `publishTutorialPost` の t タグ抽出を `distinct → lowercase` から `lowercase → distinct` に修正。Web (`toLowerCase()` 後に `seen` 重複排除) / iOS (`lowercased()` 後に `seen.insert`) と同じ「lowercase-first → distinct」順に揃え、`#Foo` と `#foo` を本文に混在させた時の `t` タグ重複を 3 プラットフォーム同一挙動 (1 個に正規化) で扱うようにした。下流 `tags = foundTags.map { listOf("t", it) }` も二重 lowercase を解消。シナリオ検証 6 ケース (pre-fill そのまま / 本文追記 + pre-fill 残し / ハッシュタグだけ削除 + 本文あり / 全部削除 / 140 文字超 / `#Foo` と `#foo` 混在) すべて 3 プラットフォーム同一結果で通過。
- NIP サポート / design-token / 依存に変更なし。AGENTS.md 不変。


## [2026-05-23] feat | Nosskey (Passkey/PRF direct) sign-up across iOS + Android

- **新規登録オンボーディングに Passkey 経路を追加**。iOS と Android で
  [nosskey "PRF Direct Method"](./nips/nosskey.md) ベースの新規登録を実装した。
  Web は 2025 年以前から `nosskey-sdk@^0.0.4` で対応済みのため、本変更で
  3 プラットフォームの parity を確立。
- **設計判断**: [[decisions/adr-0010-passkey-prf-direct-method|ADR-0010]] を新規起票。
  PRF Direct Method を採用し、秘密鍵をディスクに保存しない方針。
- **Salt 統一**: Web の `components/SignUpModal.js` が使っていた旧誤値
  `6e6f7374722d6b6579` (`"nostr-key"`) を、nosskey-sdk 標準値
  `6e6f7374722d70776b` (`"nostr-pwk"`) に修正。SDK 自身が読み込み時に旧値を
  自動正規化するため既存ユーザーへの影響なし。
- **iOS (iOS 18+ 必須)**:
  - 新規ファイル: `ios/NuruNuru/Data/NosskeyManager.swift` (≈410 行,
    `AuthenticationServices` の `ASAuthorizationController` +
    `ASAuthorizationPlatformPublicKeyCredential*` を MainActor でラップし、
    PRF 拡張による secret 導出を実装)。
  - 新規ファイル: `ios/NuruNuru/Data/NosskeySigner.swift` (≈357 行,
    新規 `EventSigner` プロトコルを実装。5 分 TTL の in-memory PRF キャッシュ
    + NIP-04 / NIP-44 v2 / Schnorr 署名)。
  - 新規ファイル: `ios/NuruNuru/Data/EventSigner.swift` (`InternalSigner` と
    `NosskeySigner` の共通プロトコル)。`InternalSigner` は同プロトコルに準拠する
    よう改修 (シグネチャ互換)。
  - 修正: `ios/NuruNuru/ViewModels/AuthViewModel.swift` に
    `generateNewAccountWithPasskey(username:)`, `loginWithPasskey()`,
    `currentSessionSigner()` を追加。`checkStoredLogin()` に
    `loginMethod == "nosskey"` ブランチ。`logout()` で
    `nosskeyManager.clearStoredKeyInfo()` も実行。
  - 修正: `ios/NuruNuru/Views/Screens/LoginView.swift` の `SignUpWelcomeStep` に
    `onNextWithPasskey` クロージャと `passkeyAvailable` プロパティを追加。
    Passkey 対応端末では「**パスキーで登録**」が primary、
    「従来の方法で作成（nsec）」が secondary。`SignUpSheet.usingPasskey` 状態と
    `progress` 5 分割計算を追加 (Passkey 経路は backup ステップをスキップ)。
  - 修正: `ios/NuruNuru/Data/NostrRepository.swift` の `signer` 型を
    `InternalSigner` から `EventSigner` プロトコルへ。`init` に
    `signer: EventSigner? = nil` 引数を追加し、サインアップ経路から
    `NosskeySigner` を注入可能に。
  - 修正: `ios/NuruNuru/Data/AppPreferences.swift` に `loginMethod: String?`
    (`"nsec" | "nosskey" | "external"`) を追加 + `clear()` でも削除。
  - 既定 RP ID は `"www.nullnull.app"`。本番デプロイには
    `https://www.nullnull.app/.well-known/apple-app-site-association` と
    `webcredentials:www.nullnull.app` Associated Domains entitlement が必要
    (現状未デプロイ → 物理端末では未動作、Simulator では動作)。
- **Android (API 28+ 必須)**:
  - 新規ファイル:
    `android/app/src/main/kotlin/io/nurunuru/app/data/NosskeyManager.kt`
    (≈365 行, `androidx.credentials.CredentialManager` + PRF 拡張 JSON)。
  - 新規ファイル:
    `android/app/src/main/kotlin/io/nurunuru/app/data/signers/NosskeySigner.kt`
    (≈153 行, `AppSigner` 実装)。
  - 修正: `AuthViewModel.kt` に `generateNewAccountWithPasskey(activity,
    username)`, native `loginWithPasskey(activity)` (旧 Custom Tabs スタブを置換),
    `buildSigner(activity)` を追加。`checkStoredLogin` に
    `loginMethod == "nosskey"` ブランチ。
  - 修正: `ui/components/SignUpModal.kt` の `WelcomeStep` に
    `onNextWithPasskey` パラメータと「**パスキーで登録**」/
    「従来の方法で作成（nsec）」UI を追加。`SignUpModal` に
    `usingPasskey` 状態 + 5/6 ステップ progress 切替。
  - 修正: `ui/screens/LoginScreen.kt` でコメントアウトされていた
    「パスキーでログイン」ボタンを復活し、`NosskeyManager.loadStoredKeyInfo()` が
    存在するときだけ表示。Web round-trip スタブを native
    `CredentialManager` 経由のフローに置換。
  - 修正: `data/prefs/AppPreferences.kt` に `loginMethod` 追加 (plainPrefs)。
  - 既定 RP ID は `"www.nullnull.app"`。本番デプロイには
    `https://nullnull.app/.well-known/assetlinks.json` で
    `applicationId = io.nurunuru.app` を RP に紐付ける必要あり
    (現状未デプロイ → Emulator/Play Store-installed device の Google Password
    Manager 経路のみ動作確認可能)。
- **Wiki**: `docs/wiki/nips/nosskey.md` (新規, ≈186 行), 既存
  `docs/wiki/features/onboarding.md` に「Passkey (nosskey) sign-up path」
  セクション追加, `docs/wiki/nips/README.md` に Nosskey draft 行追加,
  `docs/wiki/decisions/adr-0010-passkey-prf-direct-method.md` (新規, ≈126 行)。
- **依存追加なし**: iOS は OS 同梱の `AuthenticationServices` のみ。Android は
  既にあった `androidx.credentials:1.2.2` + `credentials-play-services-auth` を
  そのまま使用。
- **互換性**: nsec / NIP-46 (iOS) / NIP-55 Amber (Android) / nostr-login (Web)
  の既存ログイン経路はすべて温存。

## [2026-05-24] fix | iOS real-device Passkey registration requires Associated Domains

- 実機テストで「パスキーの登録に失敗しました」が表示される問題を調査。原因は iOS native Passkey が `rpId = "www.nullnull.app"` を使う場合に必須となる Associated Domains / AASA が未設定だったため。
- iOS アプリ側:
  - `ios/NuruNuru/NuruNuru.entitlements` を新規追加し、`com.apple.developer.associated-domains = ["webcredentials:www.nullnull.app"]` を設定。
  - `ios/project.yml` に `CODE_SIGN_ENTITLEMENTS: NuruNuru/NuruNuru.entitlements` を追加し、XcodeGen 後の project に反映。
- Web 配信側:
  - `public/.well-known/apple-app-site-association` を新規追加。内容は `webcredentials.apps = ["66G7S3P755.io.nurunuru.app"]`。
  - `next.config.js` に `/.well-known/apple-app-site-association` と `/.well-known/assetlinks.json` の `Content-Type: application/json` header を追加。
  - 既存 `public/.well-known/assetlinks.json` の package 名を `app.nurunuru` から実際の Android `applicationId = io.nurunuru.app` に修正。証明書 fingerprint は `REPLACE_WITH_YOUR_SIGNING_CERTIFICATE_SHA256` のままなので、本番 Play/App signing fingerprint で差し替えが必要。
- UX: `SignUpSheet.generateAccountWithPasskey()` の fallback error を「実機では nullnull.app の webcredentials 設定が必要です」と明示する文言に改善。
- 検証: `cd ios && /opt/homebrew/bin/xcodegen generate --spec project.yml && xcodebuild -scheme NuruNuru -destination 'platform=iOS Simulator,name=iPhone 17' -skipPackagePluginValidation -quiet build` 成功 (warnings のみ)。
- 注意: 実機で再テストするには、Web 側の AASA file を `https://www.nullnull.app/.well-known/apple-app-site-association` にデプロイし、Apple Developer portal の App ID で Associated Domains capability を有効化した provisioning profile で再署名・再インストールする必要がある。iOS は AASA を cache するため、失敗が続く場合は app 削除→再インストール、または端末再起動を行う。

## [2026-05-24] fix | iOS Passkey RP ID follows canonical www.nullnull.app

- 実機で引き続き「webcredentials 設定が必要です」と表示される件を live URL で確認。
  `https://nullnull.app/.well-known/apple-app-site-association` は `https://www.nullnull.app/...` に 307 redirect し、その先が 404 だった。
- iOS native Passkey / Associated Domains は redirect や 404 に厳しく、アプリ entitlement が正しくても AASA が canonical host で 200 JSON 配信されていないと登録できない。
- 対応:
  - `NosskeyManager.defaultRpId` を `"nullnull.app"` から canonical host の `"www.nullnull.app"` に変更。
  - entitlement に `webcredentials:www.nullnull.app` を追加し、互換用に `webcredentials:nullnull.app` も残した。
  - `app/.well-known/apple-app-site-association/route.js` を追加し、Vercel / Next.js App Router で AASA を 200 JSON として返す route handler を追加。
  - `app/.well-known/assetlinks.json/route.js` も追加し、Android Digital Asset Links も App Router 側から返せるようにした。
  - `LoginView.swift` のエラー文言を `www.nullnull.app` に更新。
- 検証: iOS Simulator build OK (`xcodebuild ... build`, exit 0)。
- 実機再テスト手順: この Web 変更を Vercel にデプロイ後、`curl -i https://www.nullnull.app/.well-known/apple-app-site-association` が `200` + `Content-Type: application/json` + `webcredentials.apps = ["66G7S3P755.io.nurunuru.app"]` を返すことを確認し、アプリを削除→再インストールして AASA cache を更新する。

## [2026-05-24] fix | iOS Nosskey prompt count, nsec export, and passkey login

- iOS 実機テストで Passkey 認証が 4 回前後繰り返される問題を修正。
  - `NosskeyManager.createPasskeyWithSecret()` を追加し、登録時に得た PRF secret を `keyInfo` と一緒に返すようにした。
  - 登録リクエストの PRF 指定を `.checkForSupport` から `.inputValues("nostr-pwk")` に変更。iOS が registration PRF output を返せる場合は登録シート 1 回で secret 取得まで完了する。返せない環境では fallback assertion 1 回のみ。
  - `AuthViewModel.generateNewAccountWithPasskey()` から重複 `deriveSecretKey()` と `NosskeySigner.warmCache()` を削除し、同じ secret を `NosskeySigner.primeCache(secret:)` に投入。profile / relayList / tutorial 投稿直前の追加プロンプトを避ける。
  - `loginWithPasskey()` も取得済み secret を signer cache に投入するよう変更。
- ログアウト後に Passkey ログインできない問題を修正。
  - `logout()` で `NosskeyKeyInfo` を削除しないように変更。`credentialId/pubkey/salt` は非秘密 metadata であり、ログアウト後の「パスキーでログイン」に必要。
  - `LoginView` の初期ボタン群に「パスキーでログイン」を追加し、`AuthViewModel.loginWithPasskey()` に接続。
- ミニアプリタブ > セキュリティ設定で nosskey ユーザーの秘密鍵取得ができない問題を修正。
  - `AuthViewModel.getNsecForCurrentAccount() async` を追加。`loginMethod == "nosskey"` の場合は Passkey 認証で PRF secret を導出し、nsec encode 後に secret を zeroize。
  - `MiniAppsView` の秘密鍵表示を async 化し、「取得中…」表示を追加。
- 検証: iOS Simulator build OK (`xcodebuild ... build`, exit 0)。

## [2026-05-24] fix | Android Passkey RP ID and debug assetlinks for real-device test

- Android Nosskey実機テスト準備として、`NosskeyManager.RP_ID` を iOS/Web と同じ canonical host の `www.nullnull.app` に統一。
- ローカル debug keystore の SHA-256 fingerprint を取得し、`app/.well-known/assetlinks.json/route.js` と `public/.well-known/assetlinks.json` に追加。
  - Debug SHA-256: `45:CD:CB:AD:A9:F4:35:A0:A3:62:80:05:9C:02:FE:7A:B1:7C:CB:09:CE:05:E2:93:BB:C6:CF:08:27:04:05:60`
- `./gradlew assembleDebug` 成功。
- 接続済み Android 実機 `9DNBNF45Y9AQFEY9` に debug APK を `adb install -r` でインストール成功。
- 注意: production / Play Store 配布では Play App Signing の SHA-256 fingerprint を assetlinks に追加する必要がある。Proton Pass / 1Password / Bitwarden 等の外部 Passkey provider は、その provider が WebAuthn PRF/hmac-secret extension に対応している場合のみ Nosskey direct method で動作する。

## [2026-05-24] fix | Disable assetlinks cache during Android Passkey testing

- `https://www.nullnull.app/.well-known/assetlinks.json` が Vercel/CDN 上で古い placeholder fingerprint を返し続けるため、Android 実機テスト中は cache を無効化。
- `app/.well-known/assetlinks.json/route.js` と `next.config.js` の Cache-Control を `no-cache, no-store, must-revalidate` に変更。

## [2026-05-24] fix | Android Nosskey MainScreen signer crash

- Android 実機で「はじめる」を押した後に crash する問題を logcat で確認。
- Crash:
  - `java.lang.IllegalStateException: Key not unlocked in SecureKeyManager`
  - `InternalSigner.ensureKeys(InternalSigner.kt:21)`
  - `NostrClient.<init>(NostrClient.kt:40)`
  - `MainScreen.kt:102`
- 原因: Passkey/Nosskey 登録後の `MainScreen` が `hasInternalKey == false` の経路で `ExternalSigner` / prewarmed client を使う想定のままになっており、実際には `NostrClient` 初期化時に nsec/Keychain 前提の signer 経路に落ちていた。
- 修正:
  - `MainScreen.kt` で `app.prefs.loginMethod` を参照。
  - `loginMethod == "nosskey"` の場合は prewarmed external client を再利用せず、`authViewModel.buildSigner(activity)` で `NosskeySigner` を構築。
  - `Activity` を `LocalContext.current as? Activity` から渡す。
- 検証:
  - `cd android && ./gradlew assembleDebug` 成功。
  - 接続実機 `9DNBNF45Y9AQFEY9` に `adb install -r android/app/build/outputs/apk/debug/app-debug.apk` 成功。

## [2026-05-24] fix | Android Nosskey cache/export/login and Amber crash

- Android 実機テストで「はじめる」後クラッシュする問題を logcat crash buffer で確認。
  原因は passkey 登録後の MainScreen が `InternalSigner` / `SecureKeyManager` 前提の signer を使っていたこと (`Key not unlocked in SecureKeyManager`)。前段の `MainScreen` 修正に加え、今回 `AuthViewModel` の session signer cache を導入して nosskey signer を再利用するよう調整。
- Passkey 認証が 2 回以上出る問題を軽減。
  - `NosskeyManager.createPasskeyWithSecret()` を追加し、登録時または fallback assertion で得た PRF secret を `keyInfo` と一緒に返すように変更。
  - registration request の PRF extension に `eval.first = "nostr-pwk"` を入れ、provider が対応する場合は登録時の PRF output を再利用。
  - `NosskeySigner.primeCache(secret)` を追加し、登録 / ログイン直後の signer cache に同じ secret を投入。
  - `SignUpModal` の profile / tutorial signer 生成を `viewModel.buildSigner(activity)` 経由にし、cache 済み signer を使うよう変更。
- ログアウト後の passkey login を維持するため、`logout()` で `NosskeyKeyInfo` を削除しないよう変更。
- ミニアプリ > セキュリティ設定の nsec export を nosskey 対応。
  - `AuthViewModel.getNsecForCurrentAccount(activity)` を追加。nosskey 時は CredentialManager/PRF 認証で secret を導出し、nsec encode 後に zeroize。
  - `MiniAppsScreen.SecuritySettingsSection` を async export に変更し、「取得中…」表示を追加。
- Amber ログイン後クラッシュの原因になり得る `prefs.loginMethod == null` を修正。`loginWithAmber()` で `prefs.loginMethod = "amber"` を保存し、`buildSigner()` が `ExternalSigner` を選べるようにした。
- 検証: `cd android && ./gradlew assembleDebug` 成功。実機はこの時点で adb 接続が切れていたため再インストールは未実施。

## [2026-05-24] fix | logout cache cleanup and iOS relay-location permission

- iOS/Android 共通: ログアウト後、アプリを完全終了しないと一部キャッシュ/接続が残る問題を修正。
- iOS:
  - `NostrRepository.clearSessionCachesForLogout()` を追加し、NostrCache、quote/bookmark cache、in-flight tasks を明示的に破棄。
  - `MainTabView.performLogout()` を追加し、ログアウト前に repository cache clear + relay disconnect を実行してから `AuthViewModel.logout()` に遷移。
  - `MiniAppsView` のログアウト操作も `MainTabView` から渡された cleanup-aware logout closure を使うよう変更。
- Android:
  - `MainScreen` に `DisposableEffect(nostrClient)` を追加し、MainScreen が composition から外れる logout 時に relay socket を即時 disconnect。
  - `AuthViewModel.logout()` 既存の NostrCache/Rust DB clear と合わせて、再起動なしでも旧セッションが残りにくくした。
- iOS 新規登録リレー設定:
  - `SignUpRelayStep` の `requestGPSRelays()` が東京 fallback 固定で、CoreLocation permission request を実行していなかった問題を修正。
  - `SignUpLocationHelper` を追加し、「GPSで自動検出」選択時に `requestWhenInUseAuthorization()` → `requestLocation()` を行う。失敗/拒否時は東京 fallback に戻す。

## [2026-05-24] fix | Android logout resets in-process UI state

- Android でログアウト後、完全にアプリを再起動しないと旧タイムライン / 旧プロフィール / 旧接続状態が残る問題を修正。
- `MainScreen.performLogout()` を追加し、`AuthViewModel.logout()` の前に以下を即時実行:
  - `timelineVM.clearSearch()` / `homeVM.clearSearch()` / `talkVM.clearStateAfterCacheClear()`
  - `repository.clearAllCache()`
  - `nostrClient.disconnect()`
  - `app.nostrCache.clearAll()`
  - `app.clearPrewarmedClient()`
- `TimelineViewModel` / `HomeViewModel` / `TalkViewModel` / `ConnectionViewModel` の Compose `viewModel()` に `pubkeyHex + loginMethod` key を付与し、アカウント切替時に旧 ViewModel instance を再利用しないようにした。
- MiniApp `MiniAppsScreen` のログアウトも `MainScreen` から渡された cleanup-aware logout closure を使うよう変更。
- `NuruNuruApp.clearPrewarmedClient()` を追加し、Amber/external signer 用 prewarmed client を logout 時に明示 disconnect + null reset。
- 検証: `cd android && ./gradlew assembleDebug` 成功。接続済み Android 実機 `9DNBNF45Y9AQFEY9` に install + launch 済み、起動直後 crash なし。

## [2026-05-24] fix | Android 16 KB native page-size release preparation

- Google Play 製品版 AAB の警告「このアプリは 16 KB メモリのページサイズをサポートしていません」を調査。
- Release merged native libs の `PT_LOAD Align` を確認し、`libimage_processing_util_jni.so` と Rust FFI `libuniffi_nurunuru.so` が `0x1000` だったことを確認。`libjnidispatch.so` と `libnostr_sdk_ffi.so` は `0x4000`。
- 対応:
  - Android Gradle Plugin を `8.6.1` に更新。
  - CameraX を `1.4.2` に更新。
  - JNA を `5.17.0` に統一。
  - Rust Android target に `-Wl,-z,max-page-size=16384` を追加し、再ビルド後の `libuniffi_nurunuru.so` が 16 KB page size 対応になるようにした。
- 注意: Rust FFI `.so` は prebuilt artifact なので、AAB 再生成前に Rust FFI の Android release build と `android/libs/arm64-v8a/libuniffi_nurunuru.so` へのコピーが必要。

## [2026-05-24] feature | Native onboarding profile-share referral follow

- Android/iOS onboarding success page changed from public-key copy to profile sharing for Twitter/X and LINE share sheets.
- Shared profile links carry a referral pubkey; native deep-link handling stores it during logged-out onboarding.
- After the new user taps **はじめる**, a background kind:3 contact-list update follows the shared profile so the user starts with that account in their graph.
- Updated `docs/wiki/features/onboarding.md` with source references and install-link caveats.

## [2026-05-24] feature | Install-cross referral retention and invite preview

- Added `/p/<npub>` Web invite landing page with profile preview, app-open links, install links, and browser-side referral retention.
- Added iOS Universal Links for `/p/*` (`applinks:www.nullnull.app`, `applinks:nullnull.app`) and expanded AASA output.
- Added native pending referral persistence in Android/iOS preferences so onboarding survives app restarts before registration completion.
- Added invite preview cards to Android `LoginScreen` and iOS `LoginView`; users can dismiss the referral before starting.
- Validation: Android `./gradlew :app:compileDebugKotlin` and iOS simulator `xcodebuild ... build` succeeded.

## [2026-05-24] fix | Modern rich-card profile sharing

- Changed Android/iOS onboarding share payloads to share only the canonical HTTPS profile invite URL (`https://www.nullnull.app/p/<npub>`) instead of multiline text plus custom-scheme deep links.
- Split the Web invite page into a server metadata wrapper and client UI so `/p/<npub>` exposes Open Graph and Twitter Card metadata.
- Added `/p/<npub>/opengraph-image` dynamic thumbnail generation for LINE / X / Messages link-card previews.
- Kept custom-scheme deep links as app-open actions on the landing page and native handlers, but removed them from shared text.

## [2026-05-24] fix | Android onboarding profile setup does not block on relay publish

- Android profile setup now wraps initial kind:0 publish in an 8-second timeout and treats failure as best-effort.
- `セットアップを完了する` advances to the tutorial step even if selected relays are slow/offline, preventing users coming from profile referral links from getting stuck.
- Updated onboarding wiki behavior notes.

## [2026-05-24] fix | Android referral follow local graph seed

- Android onboarding now applies referral follow before relay sync and seeds `NostrCache` follow-list immediately.
- Referral contact-list publish is best-effort in the background, but the local graph is correct on first MainScreen render even if relay ACKs are slow.
- This fixes the completed shared-link onboarding flow where the user reached the app but the inviter did not appear as followed.

## [2026-05-24] feature | QR profile share uses referral cards and opens installed app profile

- Android/iOS home QR share buttons now share the canonical `https://www.nullnull.app/p/<npub>` invite URL instead of `nostr:<npub>`, matching onboarding rich-card sharing.
- Logged-in Android/iOS deep-link handling now opens the shared user's profile sheet instead of treating the link only as an onboarding referral.
- Logged-out behavior remains referral onboarding: the same link stores pending follow and shows invite preview.

## [2026-05-24] feature | Rich post sharing from overflow menus

- Added **投稿を共有** to Android/iOS post overflow menus before text copy.
- Native share sheets now share canonical HTTPS event URLs (`https://www.nullnull.app/e/<event-id>`) for modern link-card previews.
- Added Web event landing page plus Open Graph/Twitter metadata and dynamic thumbnail generation for blog/SNS embed previews.

## [2026-05-24] feature | Native post share links and event previews

- Added Android/iOS post-menu `投稿を共有` actions that share canonical `https://www.nullnull.app/e/<event-id>` URLs from timeline/home/search result rows.
- Added Web `/e/<event-id>` post preview route with Open Graph / Twitter Card metadata and dynamic thumbnail image for blog/SNS unfurl previews.
- Added Android App Links and iOS Universal Links for `/e/*`; logged-in native apps open shared post links into the post detail screen.

## [2026-05-25] fix | Long-form article Nostr references render as cards

- Web, Android, and iOS long-form article readers now detect `nostr:` references inside Markdown content instead of leaving them as plain links.
- Article bodies resolve note/nevent/naddr references into embedded cards and profile references into inline profile mentions/cards where repository context is available.
- Updated post-rendering wiki notes for long-form embedded Nostr behavior.

## [2026-05-25] platform | Android 16 KB page-size release support

- Added explicit Android JNI packaging and Rust linker notes for Android 15+ 16 KB memory page-size compatibility.
- Sanitized sync prompt path examples to avoid embedding a local personal username.
- Source references: android/app/build.gradle.kts, rust-engine/nurunuru-ffi/android/build.gradle.kts, rust-engine/.cargo/config.toml, docs/sync/prompts/.

## [2026-05-25] strategy | ThemaDAY management alignment as learning organization

- Added `docs/wiki/strategy/themaday-2026-05-25.md` documenting the management alignment after reviewing Block's “From Hierarchy to Intelligence”, store CSVs, and Zapstore country insights.
- Recorded the decision to treat `docs/wiki/` as a company world model, keep this week focused on first-post completion / share loop / store trust, and treat the Zapstore Australia 28,218 impressions / 0 downloads spike as an anomaly until validated.
- Added a Strategy / Operations section to the wiki index.
## [2026-05-25] fix | Timeline empty refresh guard

- Android/iOS/Rust の kind-1 timeline 経路を調査し、relay timeout / EOSE-without-events / 一時的な follow list 空扱いで既存タイムラインが空表示に置き換わる問題を修正。
- Android は `TimelineViewModel` で空 refresh 時に既存 posts を保持し、`NostrRepositoryTimeline.kt` で follow timeline の cached nostrdb fallback と 48h legacy window を追加。
- iOS は timeline cache を non-empty network result のみで更新し、full following fetch の empty result 時に cached following events を返す。
- Rust core は timeline filter fetch の片方が失敗しても取得済みイベントを返せるようにした。
- Source references: `android/app/src/main/kotlin/io/nurunuru/app/viewmodel/TimelineViewModel.kt`, `android/app/src/main/kotlin/io/nurunuru/app/data/NostrRepositoryTimeline.kt`, `ios/NuruNuru/Data/NostrRepository+Timeline.swift`, `rust-engine/nurunuru-core/src/engine.rs`.

## [2026-05-25] feature | Timeline infinite scroll pagination

- Added Android/iOS timeline load-more pagination so users can scroll back through older kind-1 timeline notes instead of being limited to the first 50 posts.
- Android uses `NostrClient.Filter.until` in `NostrRepositoryTimeline.kt`, `TimelineViewModel.loadMore`, and a LazyColumn footer trigger near the end of the list.
- iOS adds matching `NostrRepository+Timeline.swift` page fetch methods and `TimelineView` row `onAppear` triggers with loading-more state.
## [2026-05-26] fix | Timeline pagination gap cursor

- Fixed infinite-scroll pagination when a stale cached page, for example 10 hours old, is displayed under a fresh head page.
- Android/iOS now keep an explicit page cursor from the fresh contiguous head and do not move it backwards when merging older cached posts, so load-more fills the missing gap first.
- Load-more now prefetches earlier near the last 12 visible rows to reduce perceived wait.
- Source references: android TimelineViewModel, Android TimelineScreen, iOS TimelineViewModel, iOS TimelineView.
## [2026-05-26] change | Network-first timeline event cache policy

- Changed timeline UX policy from stale cache-first event rendering to network-first event pages.
- Android/iOS no longer render persisted timeline event cache as the normal first paint; profile/follow-list caches remain cache-first.
- Stale timeline event cache is retained only as fallback/offline support and for event/detail lookup, preventing old cached pages from being silently mixed under fresh posts.
- Source references: Android TimelineViewModel / NostrRepositoryTimeline / NostrCache, iOS TimelineViewModel / NostrRepository+Timeline / NostrCache.
## [2026-05-26] fix | Fast bounded timeline pagination

- Reduced startup and older-page latency by making Android/iOS timeline hot paths raw-first: cached profiles are applied immediately and engagement enrichment runs after render.
- Bounded older-page REQ windows to 6 hours so loading around 22 minutes ago cannot jump straight to 1 day ago and relays do less work.
- Triggered load-more earlier near the last 20 rows to hide WebSocket latency.
- Source references: Android NostrRepositoryTimeline and TimelineViewModel; iOS NostrRepository+Timeline and TimelineViewModel.
## [2026-05-26] fix | Active-author follow pagination

- Optimized follow timeline pagination by discovering active authors in the current time window and fetching smaller author chunks instead of relying on one 500-author REQ.
- Positioned recent reposts by repost time so a fresh repost of an old note does not create an apparent 31m-to-1d timeline gap.
- Source references: Android NostrRepositoryTimeline/PostContent, iOS NostrRepository+Timeline.
## [2026-05-26] fix | Selected relay repost-time continuity

- Fixed selected relay timeline continuity by applying the shared repost unwrap/repost-time ordering path to relay-specific pages.
- Repost timeline display now uses the repost event timestamp rather than the original event timestamp on Android/iOS, preventing 11m-to-1d jumps caused by fresh reposts of old notes.
- Source references: Android NostrRepositoryTimeline / NostrRepositoryLiveStream and iOS NostrRepository+Timeline / ScoredPost.
## [2026-05-26] fix | Sparse timeline pagination windows

- Android follow/global/selected-relay older-page fetches now skip several empty 6-hour windows before declaring the timeline exhausted.
- This fixes cases where sparse follow or relay timelines could not scroll past an empty bounded window.
- Source references: `android/app/src/main/kotlin/io/nurunuru/app/data/NostrRepositoryTimeline.kt`, `android/app/src/main/kotlin/io/nurunuru/app/data/NostrRepositoryLiveStream.kt`.


## [2026-05-28] strategy | ThemaDAY partnerships and developer initiatives

- Added docs/wiki/strategy/themaday-2026-05-28-partnerships.md to record that ぬるぬる was covered in and other stuff's Nostr Compass #24 and participated in pre-publication review.
- Framed external coverage as a trust surface and pre-publication fact-checking as a lightweight partnership pattern that preserves editorial independence.
- Captured near-term developer initiative guidance: source-backed NIP/status docs, external-contributor task boundaries, and feedback-loop routing without bypassing human review.
- Source references: Nostr Compass #24 njump event, andotherstuff/nostr-compass PR #95, culture/strategy/operations wiki pages.

## [2026-05-29] fix | Android timeline NIP-05 display parity

- Android timeline post headers now render `profile.nip05` directly under the display name, matching iOS.
- The verified checkmark remains beside the display name; the NIP-05 text is visible whenever present and uses LineGreen only when verified.
- Source references: `android/app/src/main/kotlin/io/nurunuru/app/ui/components/PostContent.kt`, `android/app/src/main/kotlin/io/nurunuru/app/ui/components/PostItem.kt`, `android/app/src/main/kotlin/io/nurunuru/app/ui/components/LongFormPostItem.kt`, `ios/NuruNuru/Views/Components/PostContent.swift`.

## [2026-06-02] strategy | ThemaDAY Home renewal alignment

- Added `docs/wiki/strategy/themaday-2026-06-02-product-eng-design.md` to record product / engineering / design alignment for Home renewal.
- Updated June roadmap and ADR-0015: Home now uses a Home-header account/profile icon for existing profile / my posts / likes, and the Home body is split into **アクティビティ** and **コンテンツ**.
- Recorded that relay-feed removal is already complete and iOS Rust FFI current release-planning scope is complete; future work is verification / separate expansion, not a current blocker.
- Existing Timeline following feed is planned to move into Home **コンテンツ**; Activity must not recreate relay-wide feed behavior.
- Source references: `docs/wiki/strategy/june-2026-roadmap.md`, `docs/wiki/decisions/adr-0015-home-tab-renewal.md`, `docs/wiki/decisions/adr-0019-ios-rust-ffi-write-path.md`, `docs/wiki/ui/android-ios-sync.md`.

## [2026-06-03] strategy | ThemaDAY マーケティング・成長戦略・コミュニケーション

- 北極星指標を Week-1 復帰率に固定 (提案)。
- AARRT (Acquisition / Activation / Anchoring / Recommend / Trust) フレームを採用 (提案)。
- コミュニケーションを「運営の声 × ぬるるの声」二重唱モデルとして整理 (提案)。
- 90日ロードマップ: 6/8 1.5.5 → 6/29 1.6.0 (Home renewal) → 7/27 Mini Apps α → 8/8 ぬるるの日 第1回 → 8/31 90日レビュー。
- Design Crit 即決3事項: (A) 北極星指標 / (B) 二重唱モデル / (C) ぬるる本体 npub の NIP-46 bunker 2-of-N。
- 対応 ADR 候補: bunker 構成は別 ADR 起票候補。

## [2026-06-03] strategy | ThemaDAY マーケティング・成長戦略・コミュニケーション (統合版 v2)

- Agent1 提案 + Agent2 批判レビューを統合し、本ページ themaday-2026-06-03-marketing-growth.md を v2 に差し替え。
- 戦略仮説を「初投稿後7日以内に戻る理由を作る」に再定義。
- ICP を 90日固定: (1) 日本Nostr既存 + (2) プライバシー意識ある一般 に集中 (NEW)。
- AARRT v2: A3 Anchoring の主役を「初投稿への反応ループ」に再定義。ぬるるは復帰理由の一つに格下げ。
- Manual cohort 観察プロトコル (5人/週) を北極星測定の spine として採用。
- 三声モデル (運営/ぬるる/開発者) + ぬるるは週3から開始 (毎日ではなく)。
- 危機対応マトリクス (5シナリオ) を Trust の一部として追加。
- Crit 議題を 3 → 5 に再構成: A (GO条件付) / B (GO頻度抑制) / D (GO ICP固定 NEW) / E (REVISE safe starter graph NEW) / C (REVISE 後送り)。
- C bunker 構成はマーケティング開始の blocker にしない方針へ修正。

## [2026-06-03] operations | Design Crit W23 follow-up for marketing strategy

- Created `docs/wiki/culture/crit-logs/2026-W23.md` with 5 Design Crit decisions: A Week-1 North Star, B three-voice cadence, D 90-day ICP, E safe starter graph, C Nuruh npub bunker deferral.
- Created `docs/wiki/quality/manual-cohort-observation.md` to operationalize Week-1 retention without product telemetry, following ADR-0014.
- Created `docs/wiki/decisions/adr-0020-safe-starter-graph.md` as Proposed ADR for first Home safe starter graph.
- Created `docs/wiki/operations/crisis-response.md` for trust-surface FAQ and crisis escalation scenarios.
- Updated `docs/wiki/index.md` with the new strategy, quality, operations, and recent ADR pages.

## [2026-06-03] strategy | News tab recommended labels with NIP-23 and NIP-32

- Updated ADR-0016 to clarify that News discovery uses NIP-32 recommended / おすすめ labels as boost signals for NIP-23 long-form articles inside a 2-hop trust graph.
- Added `docs/wiki/features/news.md` to define the News discovery model, user-facing copy, ranking outline, and manual QA checklist.
- Added `docs/wiki/nips/nip-32.md` to document current Birdwatch label support and the proposed News recommended-label semantics.
- Updated `docs/wiki/strategy/june-2026-roadmap.md` Theme 3 to include recommended-label discovery rules.
- Updated `docs/wiki/nips/README.md` NIP-32 row, `docs/wiki/index.md`, and related links.
- Implementation status is intentionally documented as design / implementation target for News ranking, not as already shipped source behavior.

## [2026-06-04] feature | News tab implementation with NIP-23 and null.news.category

- Implemented News tab across Web, Android, and iOS navigation: ホーム / トーク / タイムライン / ニュース / ミニ.
- News fetches NIP-23 kind 30023 published articles, hides kind 30024 drafts, deduplicates by 30023:<pubkey>:<d>, and sorts by published_at / created_at newest-first.
- Added null.news.category category filtering with t tag fallback; no ranking tab and no initial trusted labeler list.
- Added news source settings for npub / hex / NIP-05 sources; empty sources show latest relay kind 30023 articles.
- Source references: components/NewsTab.js, android/app/src/main/kotlin/io/nurunuru/app/data/NostrRepositoryNews.kt, android/app/src/main/kotlin/io/nurunuru/app/ui/screens/NewsScreen.kt, ios/NuruNuru/Views/Screens/MainTabView.swift.

## [2026-06-04] strategy | ThemaDAY 外部提携 & 開発者施策 (2人目コントリビュータ受領)

- Recorded 2026-06-04 PR #201 (commit 7d4b33f) merged by ocknamo: nosskey-sdk 0.1.2 follow-up — 4 files, -284/+120, includes Co-authored-by: Claude (LLM-assisted contribution).
- Added `docs/wiki/strategy/themaday-2026-06-04-partnerships-developers.md` (Proposed) defining 90-day partnership and developer initiative strategy.
- Inherited [[themaday-2026-05-28-partnerships]] 5-requirement gate (non-exclusive, source-verifiable, culture-preserving, user-benefiting, human-reviewed) and connected to [[themaday-2026-06-03-marketing-growth]] three-voice model + 90-day ICP.
- Introduced **Contribution Ladder (L0–L5)** as observation axis, replacing contributor-count KPI with "段の分布" + 文化適合 PR 率.
- Introduced **monthly Contributor Spotlight (1st Friday)** and **monthly Dev Hour (3rd Wednesday)** as the rhythm for the "開発者向けの声" track. PR #201 ocknamo becomes the first Spotlight target (2026-06-19).
- Explicitly **NOT doing in 90 days**: bounty, CLA, Hacktoberfest, partner badges, LLM-bot label, translation public call, contributor leaderboard, Discord/Slack, corporate sponsor acceptance.
- Proposed 5 GO-track Crit items for W23+1 (2026-06-08): CONTRIBUTING.md / CODE_OF_CONDUCT.md / ISSUE_TEMPLATE×4+general PR template / Contribution Ladder / bounty-Hacktoberfest non-adoption.
- Open Questions captured for CODE_OF_CONDUCT style, LLM-single-author PR policy, Dev Hour channel format, and external PR SLA against 1-person operation.
- Source references: docs/wiki/strategy/themaday-2026-06-04-partnerships-developers.md, GitHub commit 7d4b33f1ec024f8a0bb72762a9c0231f6b7d0a6d, components/SignUpModal.js, src/adapters/signing/NosskeySigner.ts, AGENTS.md, docs/wiki/culture/llm-onboarding.md.


## [2026-06-04] strategy | Synthesized developer initiatives after Agent2 critique

- Updated docs/wiki/strategy/themaday-2026-06-04-partnerships-developers.md from Proposed to Synthesized working copy.
- Reduced Phase 0 scope to Contributor Entrance MVP: CONTRIBUTING.md, general PR template, bug_report.md, wiki_update.md, and 3 curated L1/L2 good first issue candidates.
- Replaced numeric contributor / PR / wiki-PR targets with capacity-first observation: review burden, cultural-fit decision records, dependency-update verification, private vulnerability intake, and Dev Hour trigger conditions.
- Parked monthly Dev Hour and Wiki Walk until active contributor count / repeated-question triggers are met; Spotlight remains optional and requires contributor consent.
- Added private vulnerability intake guidance: secrets, signing, key derivation, encryption, and passkey reports should not be sent to public Issues.
- Added dependency-update verification path for PR #201-style changes, with stronger checks for key/signing/passkey/encryption dependencies.


## [2026-06-04] docs | Contributor Entrance MVP implemented

- Added root `CONTRIBUTING.md` with first-PR flow, Contribution Ladder L0–L5, five-principles checklist, build/test commands, wiki update rules, security intake guidance, dependency-update verification, and LLM-assisted contribution expectations.
- Added root `SECURITY.md` to direct secrets/signing/key-derivation/encryption/passkey reports away from public Issues and toward private vulnerability reporting / minimal security contact requests.
- Added general `.github/pull_request_template.md` for non-UI PRs, including verification done / not verified, security/privacy checklist, wiki update checklist, and LLM-assisted disclosure.
- Added `.github/ISSUE_TEMPLATE/bug_report.md` and `.github/ISSUE_TEMPLATE/wiki_update.md`; feature request and NIP support templates remain deferred per Contributor Entrance MVP scope.
- Updated `README.md` Japanese and English developer sections to link to CONTRIBUTING, SECURITY, and AGENTS.

## [2026-06-04] docs | Nosskey SDK 0.1.2 Web onboarding follow-up

- Updated Nosskey wiki docs for `nosskey-sdk@^0.1.2`, the `exportNostrKey(keyInfo, cid)` Web sign-up path, and the 5-step passkey onboarding flow without a backup step.
- Documented that Web currently preserves the two-prompt registration behavior (Passkey creation + PRF assertion) while true native-style one-prompt parity remains dependent on WebAuthn/SDK support.
- Source references: `components/SignUpModal.js`, `src/adapters/signing/NosskeySigner.ts`, `package.json`, `docs/wiki/nips/nosskey.md`, `docs/wiki/features/onboarding.md`.

## [2026-06-04] fix | Web passkey login single-prompt path

- Removed normal-login private-key pre-export from `components/LoginScreen.js` so passkey login performs only `createNostrKey()` unless an app `redirect_uri` requires an nsec.
- Removed passive private-key export from `app/page.js` Nosskey session restore to avoid unexpected authentication prompts on page load.
- Updated Nosskey / onboarding wiki notes to distinguish normal Web login from explicit export, app redirect, DM fallback, and signing paths.
- Source references: `components/LoginScreen.js`, `app/page.js`, `docs/wiki/nips/nosskey.md`, `docs/wiki/features/onboarding.md`.

## [2026-06-04] fix | Persist exported Web auto-sign key encrypted at rest

- Added encrypted persistent restore for explicitly exported Web private keys so auto-sign remains enabled after reopening the site without another `exportNostrKey()` passkey prompt.
- `lib/secure-key-store.js` now encrypts exported keys with AES-GCM using a non-extractable per-origin CryptoKey stored in IndexedDB and keeps raw key bytes only in the module-private in-memory map.
- `app/page.js` restores the encrypted key on reload only when `nurunuru_auto_sign` is enabled; logout clears the in-memory and persisted key via `clearStoredPrivateKey()`.
- Updated Web Nosskey settings to recognize persisted exported keys and removed the legacy `window.nostrPrivateKey` storage path from `NosskeySettings.tsx`.
- Source references: `lib/secure-key-store.js`, `lib/nostr.js`, `app/page.js`, `components/AccountSecuritySettings.js`, `src/ui/components/settings/NosskeySettings.tsx`, `docs/wiki/nips/nosskey.md`, `docs/wiki/features/onboarding.md`.

## [2026-06-04] fix | Android News category horizontal swipe

- Updated Android News so category content is backed by a HorizontalPager, enabling horizontal swipe between トップ / 国内 / エンタメ / スポーツ / 経済 / テック / Nostr.
- Kept category chip taps, pager swipes, selected category state, and category row scroll position synchronized.
- Verified Android debug build, installed the APK on connected real device 9DNBNF45Y9AQFEY9, and launched io.nurunuru.app.
- Source references: android/app/src/main/kotlin/io/nurunuru/app/ui/screens/NewsScreen.kt, docs/wiki/features/news.md.

## [2026-06-06] strategy | ThemaDAY 企業文化と open speech / scoped reach

- Added `docs/wiki/strategy/themaday-2026-06-06-company-culture.md` to record the approved culture-building strategy: open protocol, small trusted communities, speech/display separation, two-step-flow discovery, safe starter graph, and “小さな政府、小さなLINE”.
- Added `docs/wiki/decisions/adr-0021-open-speech-scoped-reach.md` as Accepted ADR for open speech with scoped reach and native posting parity.
- Recorded the user decision that native app posting restrictions are rejected: iOS / Android / Web remain write-capable; store and safety risk should be handled by scoped display, relay-feed removal, trust graph, mute/block/report, and NIP-70 rather than disabling native composers.
- Updated culture / feature docs to clarify that freedom of speech means publishing / quoting / exiting without platform permission, not an entitlement to appear in every timeline.
- Source references: docs/wiki/strategy/themaday-2026-06-06-company-culture.md, docs/wiki/decisions/adr-0021-open-speech-scoped-reach.md, docs/wiki/culture/not-doing.md, docs/wiki/culture/four-freedoms.md, docs/wiki/features/post-composer.md.

## [2026-06-06] fix | Scope client tag to post-source display events

- Updated Android generic publish helpers so `client` tags are opt-in instead of being added to every event.
- Kept Android kind 1 `publishNote()` client attribution for `via ...` display, while kind 10002 relay list metadata now publishes without `client`.
- Documented that `client` tags are for post-source attribution, not NIP-65 relay-list metadata.
- Source references: `android/app/src/main/kotlin/io/nurunuru/app/data/NostrRepository.kt`, `android/app/src/main/kotlin/io/nurunuru/app/data/NostrRepositoryActions.kt`, `android/app/src/main/kotlin/io/nurunuru/app/data/NostrRepositoryLiveStream.kt`, `android/app/src/main/kotlin/io/nurunuru/app/data/NostrRepositoryBackup.kt`, `docs/wiki/nips/nip-65.md`, `docs/wiki/ui/android-ios-sync.md`.

## [2026-06-06] fix | Japanese via labels for client tags

- Updated new client tag labels to show `via ぬるぬるiOS`, `via ぬるぬるAndroid`, and `via ぬるぬるweb` for newly published post-source attribution.
- Kept the Issue #200 scope rule: these labels are for post-source display and must not be added to kind 10002 relay-list metadata.
- Source references: `android/app/src/main/kotlin/io/nurunuru/app/data/NostrRepository.kt`, `ios/NuruNuru/Data/NostrRepository+Actions.swift`, `components/HomeTab.js`, `components/TimelineTab.js`, `components/miniapps/SchedulerApp.js`, `docs/wiki/ui/android-ios-sync.md`.
