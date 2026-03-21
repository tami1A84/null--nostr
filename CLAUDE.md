# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**null--nostr (ぬるぬる)** — LINE-style Nostr client for the Japanese community.
Full architecture and design details are in [AGENTS.md](./AGENTS.md). Read it before modifying shared logic.

## Commands

### Web (Next.js)
```bash
npm install && npm run dev        # dev server at http://localhost:3000
npm run build                     # production build
npm run test                      # all tests (vitest)
npm run test:coverage             # with coverage report
npx vitest run src/__tests__/filename.test.ts   # single test
npm run tokens                    # sync design-tokens/constants.json → Web + Android
npm run tokens:check              # verify tokens are in sync (CI)
```

### Android
```bash
cd android && ./gradlew assembleDebug    # build debug APK
cd android && ./gradlew assembleRelease  # build release APK
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

### Rust Engine (FFI rebuild)
When modifying `rust-engine/nurunuru-ffi/src/lib.rs` or `nurunuru-core/src/engine.rs`, you must rebuild the `.so` and regenerate Kotlin bindings:

```bash
# 1. Regenerate Kotlin bindings (from the nurunuru-ffi crate dir)
cd rust-engine/nurunuru-ffi && bash bindgen/gen_kotlin.sh

# 2. Cross-compile .so for Android arm64
AR_aarch64_linux_android=/home/n/Android/Sdk/ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar \
  cargo build --release --target aarch64-linux-android -p nurunuru-ffi

# 3. Copy the .so
cp rust-engine/target/aarch64-linux-android/release/libuniffi_nurunuru.so \
   rust-engine/nurunuru-ffi/android/libs/arm64-v8a/
```

NDK linker/compiler config is persisted in `rust-engine/.cargo/config.toml`. `CC_aarch64_linux_android` is set there; `AR_aarch64_linux_android` must be passed explicitly as an env var (cc-rs limitation).

### iOS
```bash
cd ios && xcodebuild -scheme NuruNuru -destination 'platform=iOS Simulator,name=iPhone 16' build
cd ios && xcodebuild -scheme NuruNuru -destination 'platform=iOS Simulator,name=iPhone 16' test
open ios/NuruNuru.xcodeproj                    # open in Xcode
```
Design doc: [ios/DESIGN.md](./ios/DESIGN.md) | Guardrails: [ios/GUARDRAILS.md](./ios/GUARDRAILS.md)

### zapstore Publishing
```bash
~/go/bin/zsp publish   # from repo root, requires nsec
```

## Architecture

### Platform Stack
- **Web (Vercel)**: Next.js 14 (app router) + `nostr-tools` + `rx-nostr` + direct WebSocket connections
- **Android**: Kotlin (Jetpack Compose) → `nurunuru-ffi` (UniFFI) → `nurunuru-core` (Rust)
- **iOS**: Swift (SwiftUI) → pure Swift Nostr layer (Phase 1) → `nurunuru-ffi` UniFFI (Phase 2)
- **Desktop**: `nurunuru-napi` (napi-rs) → `nurunuru-core` (Rust)
- Web mode: `lib/rust-bridge.js` and `lib/rust-engine-manager.js` are stubs; all Nostr ops use `lib/nostr.js` directly

### FFI Bridge
`rust-engine/nurunuru-ffi/src/lib.rs` → UniFFI → auto-generated `bindgen/kotlin-out/uniffi/nurunuru/nurunuru.kt` → loaded by the Android app via JNA.
The generated `.kt` file must be committed alongside any `lib.rs` API changes.

### iOS Layer
```
ios/NuruNuru/
  Theme/          # NuruColors, NuruTypography, NuruSpacing (auto-generated from tokens)
  Models/         # NostrEvent, ScoredPost, MlsGroup — same names as Android
  Data/           # NostrRepository (actor), NostrClient, ConnectionManager, SecureKeyManager
  ViewModels/     # AuthViewModel, TimelineViewModel, HomeViewModel, TalkViewModel
  Views/Screens/  # LoginView, MainTabView, HomeView, TimelineView, TalkView, SettingsView
  Views/Components/ # PostRow, PostActions, PostContent, PostSheet, ImageViewerView
  Views/Sheets/   # SearchSheet, ZapSheet, NotificationSheet, EmojiPickerSheet
  Views/MiniApps/ # BadgeSettingsView, EmojiSettingsView, ZapSettingsView
```
- `NostrRepository` is an `actor` — single data access point (same pattern as Android)
- `@Observable` ViewModels (iOS 17 Observation framework, not Combine)
- NIP-46 (Nostr Connect) replaces NIP-55 (Amber) for external signing on iOS
- Design must match Android pixel-for-pixel. See [ios/GUARDRAILS.md](./ios/GUARDRAILS.md)

### Web Layer (`lib/`)
- `nostr.js` — core protocol ops (publish, DM, zap, sign)
- `connection-manager.js` — WebSocket pool, rate limiting (10 req/s), relay cooldowns; max 4 global / 2 per-relay concurrent connections
- `cache.js` — two-layer (in-memory LRU + localStorage)
- `secure-key-store.js` — private keys in module-level closure; **never expose to `window.*`**
- `recommendation.js` — feed scoring (Zap 100, Quote 35, Reply 30, Repost 25, Like 5)
- `security.js` — CSRF, AES-GCM encrypted storage, content sanitization
- `validation.js` — input validation for URLs, pubkeys, NIP-05

### Android Layer
```
android/app/src/main/kotlin/io/nurunuru/app/
  data/           # NostrRepository, NostrClient, models, cache, prefs, signers
  ui/             # Compose screens, components, theme, icons, miniapps
  viewmodel/      # TimelineViewModel, TalkViewModel, HomeViewModel, AuthViewModel, ConnectionViewModel
  MainActivity.kt
  NuruNuruApp.kt
```
- `NostrRepository` is the single data access point for ViewModels
- `TimelineViewModel` drives both フォロー and おすすめ tabs

### Recommendation Algorithm
Implemented in Rust at `nurunuru-core/src/recommendation.rs` (`rank_feed()`) and orchestrated by `engine.rs` (`get_recommended_events_ordered()`):
- Feed composition: 50% 2nd-degree network (48h), 30% viral global (1h), 20% 1st-degree follows
- Score = `Engagement × Social × Author × Geohash × Modifier × TimeDecay` (1.5x boost <1h, 6h half-life)
- Both candidate sets fetched via `tokio::join!` in parallel
- Exposed to Android via `fetch_recommended_timeline(limit, user_geohash)` in FFI
- Android entry point: `NostrRepository.fetchRecommendedTimeline()` → `TimelineViewModel` (おすすめ tab)

### Design Tokens
`design-tokens/constants.json` is the source of truth for weights, colors, and limits. Run `npm run tokens` to sync to `lib/constants.generated.js` (Web), `android/app/src/main/kotlin/io/nurunuru/app/data/Constants.kt` (Android), and `ios/NuruNuru/Utilities/Constants.swift` (iOS).

## Key Constraints

### Web
- Post length: 140 chars threshold for collapse; links excluded from count
- Always use `sanitizeContent()` from `lib/security.js` before `dangerouslySetInnerHTML`
- Production builds strip `console.log/warn/debug`; use `console.error` for critical issues only

### Android
- Post length: strictly enforced 140-char limit in `PostModal.kt`
- Use `remember(post.event.id)` in `PostItem.kt`; avoid entrance animations in `TimelineScreen.kt`
- Full-screen modals: `Surface` as sibling to `Scaffold` inside root `Box`
- `BasicTextField` inside scrollable `Column` must NOT use `Modifier.weight(1f)` (crashes)
- `nostrdb` stored at `context.filesDir/nostrdb_ndb`
- NIP-55 (Amber) external signer via `ExternalSigner.kt`

### iOS
- Post length: strictly enforced 140-char limit in `PostSheet.swift`
- Font: LINE Seed JP only (bundled .ttf). Never fall back to system font for body text
- Private keys: Keychain only (`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`). Never in UserDefaults or logs
- Use `.id(post.event.id)` for stable list identity; no entrance animations on timeline
- Full-screen modals: `.fullScreenCover` for image viewer, `.sheet` for everything else
- `NostrRepository` must be an `actor` for thread-safe access
- `@Observable` for all ViewModels (iOS 17+). No Combine/ObservableObject
- NIP-46 (Nostr Connect) for external signing (no NIP-55 on iOS)
- SPM only for dependencies. Minimize third-party (prefer Apple frameworks)
- Minimum deployment target: iOS 17.0
