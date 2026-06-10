# AGENTS.md

Context and instructions for AI coding agents and developers working on **null--nostr (ぬるぬる)**.

---

## Project Overview

null--nostr is a LINE-style Nostr client for the Japanese community. It runs as a Next.js PWA on Web, a native Android app (Kotlin + Rust FFI), and a native iOS app (Swift/SwiftUI). The shared Rust core handles crypto and relay management.

---

## Culture (Charter v0.1)

ぬるぬるは「美意識を制度化する」ことを文化戦略の中核に据える。すべての設計・実装判断は
以下の文化憲章に従う。新しい NIP の採用や新機能 PR は、これらに抵触しないかを確認する。

- [docs/wiki/culture/principles.md](./docs/wiki/culture/principles.md) — 北極星 + 五箇条
- [docs/wiki/culture/not-doing.md](./docs/wiki/culture/not-doing.md) — やらないことリスト
- [docs/wiki/culture/design-crit.md](./docs/wiki/culture/design-crit.md) — Weekly Nuru Design Crit
- [docs/wiki/culture/release-quality.md](./docs/wiki/culture/release-quality.md) — 月曜リリース列車 + Nuru Production System
- [docs/wiki/culture/copy-style.md](./docs/wiki/culture/copy-style.md) — 日本語コピー規約
- [docs/wiki/culture/llm-onboarding.md](./docs/wiki/culture/llm-onboarding.md) — LLM コントリビュータ向け
- [docs/wiki/culture/four-freedoms.md](./docs/wiki/culture/four-freedoms.md) — 10年先の4軸自由ドクトリン

長期ミッション (Charter 北極星) は ADR-0008、Design Crit の制度化は ADR-0007 を参照。

---

## LLM Wiki

This repository maintains an LLM-generated project wiki under [`docs/wiki/`](./docs/wiki/).

### Purpose

The wiki is a persistent knowledge base for null--nostr. It summarizes architecture,
features, platform differences, NIP support, implementation notes, design decisions,
and open questions. Treat `AGENTS.md` as the rules/schema for agents, and `docs/wiki/`
as the expandable project knowledge base.

### Source of truth

The wiki is derived from source files, design documents, generated design-token outputs,
and explicit user/developer decisions. When wiki content conflicts with source code,
`design-tokens/constants.json`, build configuration, or platform guardrails, the source
files and guardrails win.

Primary sources include:

- Source code: `lib/`, `components/`, `android/`, `ios/NuruNuru/`, `rust-engine/`
- Design tokens: `design-tokens/constants.json`
- Platform docs: `ios/GUARDRAILS.md`, `docs/sync/`, README files, build configs
- Explicit decisions recorded in issues, PRs, or `docs/wiki/decisions/`

### Required wiki files

- `docs/wiki/index.md` — content-oriented catalog of wiki pages
- `docs/wiki/log.md` — chronological append-only update log
- `docs/wiki/overview.md` — high-level project overview
- `docs/wiki/architecture.md` — cross-platform architecture summary

### Update rules

When making meaningful changes to architecture, features, platform behavior, NIP support,
security constraints, design tokens, or Android/iOS/Web parity:

1. Read `docs/wiki/index.md` first to find relevant pages.
2. Update the relevant wiki page(s) in `docs/wiki/`.
3. Update `docs/wiki/index.md` if pages are added, renamed, or significantly changed.
4. Append an entry to `docs/wiki/log.md` using `## [YYYY-MM-DD] type | title`.
5. Include source file references in wiki pages.
6. Mark uncertain claims as `Open Questions` instead of presenting them as facts.

### Page conventions

Each wiki page should generally include:

- Title
- Summary
- Current behavior
- Platform notes where applicable
- Source references
- Related pages
- Open questions, if any

### Do not

- Treat wiki pages as more authoritative than source code or design tokens.
- Store secrets, private keys, credentials, tokens, or sensitive personal data in the wiki.
- Invent implementation details without checking source files.
- Bloat `AGENTS.md` with detailed feature specs; put detailed evolving knowledge in `docs/wiki/` and link from here when needed.

---

## Commands

### Web (Next.js)
```bash
npm install && npm run dev        # dev server at http://localhost:3000
npm run build                     # production build
npm run test                      # all tests (vitest)
npm run test:coverage             # with coverage report
npx vitest run src/__tests__/filename.test.ts   # single test
npm run tokens                    # sync design-tokens/constants.json → Web + Android + iOS
npm run tokens:check              # verify tokens are in sync (CI)
npm run wiki:lint                 # lint docs/wiki links, required sections, and AGENTS links
```

### Android
```bash
cd android && ./gradlew assembleDebug    # build debug APK
cd android && ./gradlew assembleRelease  # build release APK
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

### iOS
```bash
# Xcode プロジェクト再生成 (project.yml 変更後)
cd ios && /tmp/xcodegen_extracted/xcodegen/bin/xcodegen generate --spec project.yml

cd ios && xcodebuild -scheme NuruNuru -destination 'platform=iOS Simulator,name=iPhone 17' -skipPackagePluginValidation build
cd ios && xcodebuild -scheme NuruNuru -destination 'platform=iOS Simulator,name=iPhone 17' -skipPackagePluginValidation test
open ios/NuruNuru.xcodeproj                    # open in Xcode
```
iOS guardrails: [ios/GUARDRAILS.md](./ios/GUARDRAILS.md) | UI sync notes: [docs/wiki/ui/android-ios-sync.md](./docs/wiki/ui/android-ios-sync.md)

### Rust Engine (rebuild required when modifying lib.rs or engine.rs)
```bash
# 1. Regenerate Kotlin bindings
cd rust-engine/nurunuru-ffi && bash bindgen/gen_kotlin.sh

# 2. Cross-compile .so for Android arm64
AR_aarch64_linux_android=/home/n/Android/Sdk/ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar \
  cargo build --release --target aarch64-linux-android -p nurunuru-ffi

# 3. Copy the .so
cp rust-engine/target/aarch64-linux-android/release/libuniffi_nurunuru.so \
   rust-engine/nurunuru-ffi/android/libs/arm64-v8a/
```

NDK linker/compiler config is persisted in `rust-engine/.cargo/config.toml`. `CC_aarch64_linux_android` is set there; `AR_aarch64_linux_android` must be passed explicitly as an env var (cc-rs limitation).

### Publishing
```bash
# zapstore (requires TTY — run in terminal)
~/go/bin/zsp publish

# GitHub release
gh release create vX.Y.Z nurunuru-X.Y.Z-arm64-v8a.apk --title "..." --notes "..."
```

---

## Architecture

### Platform Stack

| Layer | Technology |
|---|---|
| Web | Next.js 14, nostr-tools, rx-nostr, Tailwind CSS |
| Android | Kotlin, Jetpack Compose, CameraX, ExoPlayer/Media3 |
| iOS | Swift, SwiftUI, iOS 17+ Observation |
| Rust FFI | UniFFI → `nurunuru-ffi/src/lib.rs` → Kotlin/Swift bindings |
| Rust Core | `nurunuru-core`, nostr-sdk 0.44.x, nostrdb |
| Desktop | `nurunuru-napi` (napi-rs) → `nurunuru-core` (Rust) |

### FFI Bridge

`rust-engine/nurunuru-ffi/src/lib.rs` → UniFFI → auto-generated `bindgen/kotlin-out/uniffi/nurunuru/nurunuru.kt` → loaded by Android via JNA.

- `parse_ffi_tags` silently skips unparseable tags (does not throw).
- `publishEvent(kind, content, tags)` supports arbitrary tag names including custom NIP tags.
- Generated `.kt` must be committed alongside any `lib.rs` API changes.

### iOS Layer
```
ios/NuruNuru/
  Theme/            # NuruColors, NuruTypography, NuruSpacing (auto-generated from tokens)
  Models/           # NostrEvent, ScoredPost, MlsGroup — same names as Android
  Data/             # NostrRepository (actor), NostrClient, ConnectionManager, SecureKeyManager
                    # NostrRepository+Talk, +Timeline, +Reactions, +Notifications, +LiveStream 等
                    # ImageUploadService (nostr.build/yabu.me/Blossom, NIP-98)
                    # NuruNuruFFIBridge (protocol + stub), NuruNuruFFILiveClient (XCFramework bridge)
  ViewModels/       # AuthViewModel, TimelineViewModel, HomeViewModel, TalkViewModel, ConnectionViewModel
  Views/Screens/    # LoginView, MainTabView, HomeView, TimelineView, TalkView, MiniAppsView, AppSettingsView
  Views/Components/ # PostRow, PostActions, PostContent, VideoPlayer, ImageViewerView, AvatarView 等
  Views/Sheets/     # PostSheet, SearchSheet, ZapSheet, NotificationSheet, CreateGroupSheet,
                    # GroupInfoSheet, BookmarkListSheet, QuoteRepostSheet, UserProfileSheet 等
  Views/MiniApps/   # BadgeSettingsView, EmojiSettingsView, ZapSettingsView, RelaySettingsView,
                    # ElevenLabsSettingsView, SchedulerView, CacheSettingsView 等
```
- `NostrRepository` is an `actor` — single data access point (same pattern as Android)
- `@Observable` ViewModels (iOS 17 Observation framework, not Combine)
- iOS signer paths are internal nsec/Keychain and Passkey/Nosskey; NIP-46 signer is removed and NIP-55/Amber is not used on iOS
- `NuruNuruFFIBridge` protocol + `NuruNuruFFIStub` fallback — Rust FFI は Phase 1 で統合予定
- Design must match Android pixel-for-pixel. See [ios/GUARDRAILS.md](./ios/GUARDRAILS.md)

### Web Layer (`lib/`)
- `nostr.js` — core protocol ops (publish, DM, zap, sign)
- `connection-manager.js` — WebSocket pool, rate limiting (10 req/s), relay cooldowns; max 4 global / 2 per-relay concurrent connections
- `cache.js` — two-layer (in-memory LRU + localStorage)
- `secure-key-store.js` — private keys in module-level closure; **never expose to `window.*`**
- `security.js` — CSRF, AES-GCM encrypted storage, content sanitization
- `validation.js` — input validation for URLs, pubkeys, NIP-05
- Web mode: `lib/rust-bridge.js` and `lib/rust-engine-manager.js` are stubs; all Nostr ops use `lib/nostr.js` directly

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

### Design Tokens

`design-tokens/constants.json` is the single source of truth for weights, colors, and limits. Run `npm run tokens` to sync to `lib/constants.generated.js` (Web), `android/app/src/main/kotlin/io/nurunuru/app/data/Constants.kt` (Android), and `ios/NuruNuru/Utilities/Constants.swift` (iOS).

---

## Key Files

### Android

| File | Purpose |
|---|---|
| `ui/components/PostModal.kt` | Post composer (text, images). Relay selection panel + NIP-70 `-` tag protection. `targetRelays` param routes to `publishNoteWithTagsToRelays`. Parallel image uploads via `async { }`. |
| `ui/components/VideoPlayer.kt` | ExoPlayer/Media3 video player with tap-to-unmute. |
| `ui/components/PostContent.kt` | Feed post rendering. `EmbeddedNostrContent` for nostr: bech32 cards. `PostImageGrid` for 1/2/3/4+ layouts. |
| `ui/components/ImageViewerDialog.kt` | Fullscreen pager viewer (`HorizontalPager`). Custom gesture handler: pinch=zoom, 1-finger-at-scale1=pass-to-pager. |
| `ui/components/NotificationModal.kt` | Notification list. `NotifStyle` per type. 30s background polling. Animated new-item pill (`Column > AnimatedVisibility`). |
| `ui/components/EmojiPicker.kt` | Custom emoji picker. Defines `EmojiPickerCache` (5-min TTL, duplicate-fetch guard via `fetching` map) and `fetchAndCacheEmojis()` shared suspend function. `individualOnly=true` hides emoji sets. |
| `ui/components/ReactionEmojiPicker.kt` | Reaction picker (NIP-25). Uses shared `EmojiPickerCache` + `fetchAndCacheEmojis` from `EmojiPicker.kt`. |
| `ui/screens/MiniAppsScreen.kt` | Mini Apps hub (search, favorites, categories, built-in/external mini apps). Account/security settings live in Home settings. |
| `data/NostrRepository.kt` | All Nostr I/O. Notifications include Kind 6 (repost) and Kind 1 #p (reply/mention). `enrichPosts()` tracks `myLikeEventId`/`myRepostEventId` for toggle-undo. |
| `ui/screens/MainScreen.kt` | Root navigation. Current sync target is 4 tabs: ホーム / トーク / タイムライン / ミニアプリ. |

### Web

| File | Purpose |
|---|---|
| `lib/nostr.js` | Core Nostr operations: signing, publishing, DM, Zap. |
| `lib/connection-manager.js` | WebSocket pooling, rate limiting (10 req/s), relay cooldowns. |
| `lib/secure-key-store.js` | Private key closure — never expose to `window`. |
| `lib/cache.js` | Two-layer cache: in-memory LRU + localStorage. |
| `lib/security.js` | CSRF, AES-GCM storage, `sanitizeContent()`. |

---

## Implementation Constraints

### Web
- Post length: 140 chars threshold for collapse; links excluded from count
- Always use `sanitizeContent()` from `lib/security.js` before `dangerouslySetInnerHTML`
- Production builds strip `console.log/warn/debug`; use `console.error` for critical issues only
- Max 4 global concurrent connections, 2 per-relay
- Private keys: Use `storePrivateKey()` / `getPrivateKeyBytes()` from `lib/secure-key-store.js`. Never assign to `window`

### Android
- **Post length**: 140 characters, strictly enforced in `PostModal.kt`.
- **Modals**: Full-screen `Surface` overlays as siblings to `Scaffold` inside a root `Box`.
- **BasicTextField**: Must NOT use `Modifier.weight(1f)` inside a scrollable `Column` — causes crash.
- **Compose performance**: Use `remember(post.event.id)` in `PostItem.kt`. No entrance animations in `TimelineScreen.kt`.
- **IO operations**: All Rust FFI calls, file I/O, and uploads must run on `Dispatchers.IO`.
- **AnimatedVisibility inside Box inside Column**: Kotlin resolves `ColumnScope.AnimatedVisibility` (outer receiver) over the top-level overload. Fix: wrap the call site in a `Column { }` to explicitly bring `ColumnScope` into scope, or extract to a standalone composable function.
- **Surface rounded corners**: Always pass `shape = RoundedCornerShape(…)` to `Surface` directly. Using only `Modifier.clip(shape)` causes the border to be drawn as a rectangle before clipping, cutting the corners visually.
- **Toggle like/repost**: `ScoredPost` carries `myLikeEventId`/`myRepostEventId`. On second tap, `TimelineViewModel` calls `repository.deleteEvent(eventId)` and decrements the counter.
- **Relay-targeted post**: Pass `targetRelays: List<String>?` to `NostrRepository.publishNote()`. Non-null list routes to `rustClient.publishNoteWithTagsToRelays()` (FFI `publish_note_with_tags_to_relays`). Null = broadcast to all relays.
- **NIP-70 protection**: `nip70Protected = true` in `publishNote()` appends `["-"]` tag before signing.
- **Font**: App-wide typography uses `LineSeedJP` (`FontFamily` in `Type.kt`) loaded from `res/font/line_seed_jp_rg.ttf` / `line_seed_jp_bd.ttf`.
- **Image uploads**: Use `async { }` inside `withContext(Dispatchers.IO)` for parallel uploads; collect with `awaitAll()`.
- **Zoom + pager gesture conflict**: In `ImageViewerDialog`, do NOT use `Modifier.transformable` — it consumes single-finger drags at `scale==1f`, blocking the `HorizontalPager`. Use `awaitEachGesture` with manual pointer-count branching instead.
- **nostrdb** stored at `context.filesDir/nostrdb_ndb`
- **NIP-55** (Amber) external signer via `ExternalSigner.kt`

### iOS
- Post length: strictly enforced 140-char limit in `PostSheet.swift`
- Font: LINE Seed JP only (bundled .ttf). Never fall back to system font for body text
- Private keys: Keychain only (`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`). Never in UserDefaults or logs
- Use `.id(post.event.id)` for stable list identity; no entrance animations on timeline
- Full-screen modals: `.fullScreenCover` for image viewer, `.sheet` for everything else
- `NostrRepository` must be an `actor` for thread-safe access
- `@Observable` for all ViewModels (iOS 17+). No Combine/ObservableObject
- NIP-46 signer is removed on iOS; do not add NIP-55/Amber. Use internal nsec/Keychain or Passkey/Nosskey signer paths
- SPM only for dependencies. Minimize third-party (prefer Apple frameworks)
- Minimum deployment target: iOS 17.0
- Tab bar: `.safeAreaInset(edge: .bottom, spacing: 0)` — do NOT use ZStack+ignoresSafeArea pattern
- Bottom nav icons for the 4-tab target: house/message/newspaper/square.grid.2x2 (NOT person.crop.circle for home; no 67 or News root tab)
- PostActions: no reply button. Current code shows like / repost / zap and may also show bookmark when a bookmark handler is supplied; like icon = thumbs-up (not heart).
- Collapse text: "もっと見る" / "閉じる" (NOT "続きを読む") — matches Android exact copy
- For pixel-perfect sync notes, see [docs/wiki/ui/android-ios-sync.md](./docs/wiki/ui/android-ios-sync.md)

---

## Default Relays

| Relay | Region |
|---|---|
| wss://yabu.me | JP (primary) |
| wss://relay-jp.nostr.wirednet.jp | JP |
| wss://r.kojira.io | JP |
| wss://relay.damus.io | Global (fallback) |
| wss://search.nos.today | NIP-50 Search |

---

## Search Architecture (Android)

`SearchQueryParser` (`data/SearchQueryParser.kt`) parses operator tokens from the raw query string before routing to the appropriate backend:

| Operator | Example | Backend |
|---|---|---|
| `#tag` | `#japan` | `#t` tag filter → relay REQ |
| `from:` | `from:npub1...` / `from:user@domain` | `authors` filter (NIP-05 resolved async) |
| `since:` / `until:` | `since:2025-01-01` | timestamp filter on relay REQ or searchnos |
| `-word` | `-spam` | client-side post-filter (Unicode-aware) |
| `"phrase"` | `"完全一致"` | client-side exact-match filter |
| `filter:image/video/link` | `filter:image` | client-side URL pattern filter |

Routing logic in `NostrRepository.advancedSearch()`:
- **Text present** → searchnos (NIP-50) with all structured filters combined in one REQ
- **Text absent** → standard relay REQ with tag/author/time filters only
- Client-side post-filter applied to all results for exclude/exact/media

---

## Supported NIPs

Code-backed NIP list is maintained in `docs/wiki/nips/README.md`. Update that page after checking source files when NIP support changes.
