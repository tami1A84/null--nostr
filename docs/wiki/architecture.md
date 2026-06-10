# Architecture

## Summary

null--nostr は、Web / Android / iOS の UI 層と、Nostr 処理・暗号・リレー管理を担う Rust Engine を組み合わせる構成です。ただし Web では現状 Rust bridge は stub で、Nostr 操作は `lib/nostr.js` を直接使います。

## Platform stack

| Layer | Technology |
|---|---|
| Web | Next.js 14, nostr-tools, rx-nostr, Tailwind CSS |
| Android | Kotlin, Jetpack Compose, CameraX, ExoPlayer/Media3 |
| iOS | Swift, SwiftUI, iOS 17+ Observation |
| Rust FFI | UniFFI → Kotlin/Swift bindings |
| Rust Core | `nurunuru-core`, nostr-sdk 0.44.x, nostrdb |
| Desktop | `nurunuru-napi` |

## Data access pattern

- Android: `NostrRepository` が ViewModel の単一データアクセスポイント。
- iOS: `NostrRepository` は `actor`。Android と同様にデータアクセスの中心。
- Web: `lib/nostr.js`、`lib/connection-manager.js`、`lib/cache.js` などが責務を分担。

## Cross-cutting concerns

- **Design tokens:** `design-tokens/constants.json` から Web / Android / iOS に生成。
- **Private keys:** Web は module closure、iOS は Keychain、Android は platform signer / Rust FFI 経由の制約を守る。
- **iOS Rust FFI:** 2026-06-02 時点で現行 release-planning scope は完了済み。read-only MLS diagnostics に加えて keygen / signing / signed raw-event publish contracts が利用可能な前提で扱う。ただし iOS NIP-46 signer は ADR-0023 で廃止され、Passkey/Nosskey は platform authorization path、private keys は Keychain-only という境界を維持する。
- **Relay limits:** Web は global 4 / per-relay 2 concurrent connection を守る。
- **Relay routing / health:** Rust core owns local `RelayRouter` health/cooldown snapshots for explicit relay fetch and targeted native publish paths. Web keeps an independent `connection-manager.js` health map for browser relay diagnostics.
- **Durable publish groundwork:** Rust publish paths enqueue fully-signed event JSON only in `db_path/publish_outbox.json` before network send, then mark items published/failed; no signer secrets or unsigned signing material are stored. Web has matching local groundwork in `lib/publish-outbox.js` using browser `localStorage` for fully signed event JSON only.
- **IO discipline:** Android の Rust FFI / file IO / uploads は `Dispatchers.IO`。


### Android native 16 KB page-size compatibility

Android release builds that include native libraries are configured for Android 15+ devices that use a 16 KB memory page size. The app and Rust FFI library modules keep JNI libraries uncompressed/page-aligned with packaging.jniLibs.useLegacyPackaging = false, and Rust Android targets are linked with -Wl,-z,max-page-size=16384.

Source references: android/app/build.gradle.kts, rust-engine/nurunuru-ffi/android/build.gradle.kts, rust-engine/.cargo/config.toml.


- iOS Rust FFI current release-planning scope is complete as of 2026-06-02; future Talk MLS expansion, if any, should be tracked separately.


## iOS Rust FFI note

iOS Rust FFI current release-planning scope is complete as of 2026-06-02. Rust keygen, internal signing contracts, and signed raw-event publishing are treated as available within the accepted security boundaries. NIP-46 signer is removed from the iOS app path by ADR-0023; Passkey/Nosskey remains a platform authorization path and private keys remain Keychain-only in app code. Future Talk MLS expansion, if any, should be tracked separately.

## Source references

- `rust-engine/nurunuru-core/src/relay.rs`
- `rust-engine/nurunuru-core/src/outbox.rs`

- `AGENTS.md`
- `lib/nostr.js`
- `lib/connection-manager.js`
- `android/app/src/main/kotlin/io/nurunuru/app/data/NostrRepository.kt`
- `ios/NuruNuru/Data/`
- `rust-engine/nurunuru-ffi/src/lib.rs`

## Related pages

- [[ui/design-tokens]]
- [[platforms/web]]
- [[platforms/android]]
- [[platforms/ios]]
- [[platforms/rust-engine]]


## Passkey / Nosskey signer abstraction (2026-05-23)

iOS / Android で Passkey ("PRF Direct Method") による新規登録に対応した。
詳細は [[nips/nosskey]] と [[decisions/adr-0010-passkey-prf-direct-method|ADR-0010]]。

| Platform | Manager | Signer | 共通プロトコル |
|---|---|---|---|
| iOS | `NosskeyManager` (`ios/NuruNuru/Data/NosskeyManager.swift`) | `NosskeySigner` | `EventSigner` (`ios/NuruNuru/Data/EventSigner.swift`) |
| Android | `NosskeyManager` (`android/.../data/NosskeyManager.kt`) | `NosskeySigner` (`.../data/signers/NosskeySigner.kt`) | 既存 `AppSigner` |
| Web | `nosskey-sdk@^0.0.4` の `NosskeyManager` クラス | SDK 内包 | 既存の `signEventNip07` 経路 |

iOS は `NostrRepository.init(... , signer: EventSigner? = nil)` から signer を
明示注入可能に変更。Android は `AuthViewModel.buildSigner(activity)` で
`loginMethod` に応じて Internal / Nosskey / External signer を切り替える。
