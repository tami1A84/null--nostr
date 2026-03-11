# AGENTS.md

Context and instructions for AI coding agents and developers working on **null--nostr (ぬるぬる)**.

---

## Project Overview

null--nostr is a LINE-style Nostr client for the Japanese community. It runs as a Next.js PWA on Web and as a native Android app (Kotlin + Rust FFI). The shared Rust core handles crypto, relay management, and the recommendation algorithm.

---

## Commands

### Web (Next.js)
```bash
npm install && npm run dev        # dev server at http://localhost:3000
npm run build                     # production build
npm run test                      # all tests
npx vitest run src/__tests__/filename.test.ts   # single test
npm run tokens                    # sync design-tokens/constants.json → Web + Android
```

### Android
```bash
cd android && ./gradlew assembleDebug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk

./gradlew assembleRelease         # release build (signed)
```

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

NDK config lives in `rust-engine/.cargo/config.toml`. `AR_aarch64_linux_android` must be passed as env var.

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
| Rust FFI | UniFFI → `nurunuru-ffi/src/lib.rs` → Kotlin bindings |
| Rust Core | `nurunuru-core`, nostr-sdk 0.44.x, nostrdb |

### FFI Bridge

`rust-engine/nurunuru-ffi/src/lib.rs` → UniFFI → auto-generated `bindgen/kotlin-out/uniffi/nurunuru/nurunuru.kt` → loaded by Android via JNA.

- `parse_ffi_tags` silently skips unparseable tags (does not throw).
- `publishEvent(kind, content, tags)` supports arbitrary tag names including custom NIP tags.
- Generated `.kt` must be committed alongside any `lib.rs` API changes.

### Recommendation Algorithm

Implemented in Rust at `nurunuru-core/src/recommendation.rs` (`rank_feed()`) and orchestrated by `engine.rs` (`get_recommended_events_ordered()`).

**Feed composition:** 50% 2nd-degree network (48h) + 30% viral global (1h) + 20% 1st-degree follows

**Scoring:** `Score = Engagement × Social × Author × Geohash × Modifier × TimeDecay`
- Weights: Zap 100, Quote 35, Reply 30, Repost 25, Like 5
- Time decay: 1.5x boost for <1h, 6h half-life

Android entry point: `NostrRepository.fetchRecommendedTimeline()` → `TimelineViewModel` (おすすめ tab)

### Design Tokens

`design-tokens/constants.json` is the single source of truth for weights, colors, and limits. Run `npm run tokens` to sync to Android and Web.

---

## Key Files

### Android

| File | Purpose |
|---|---|
| `ui/components/PostModal.kt` | Post composer (text, images, video). Kind 34236 tag assembly. Parallel image uploads via `async { }`. |
| `ui/components/DivineVideoRecorder.kt` | CameraX video recorder, 6.3s loop. MPL-2.0. |
| `data/ProofModeManager.kt` | ProofMode: PGP signing (Bouncy Castle), frame hashes, Play Integrity. MPL-2.0. |
| `ui/components/VideoPlayer.kt` | ExoPlayer/Media3 video player with tap-to-unmute. |
| `ui/components/PostContent.kt` | Feed post rendering. `EmbeddedNostrContent` for nostr: bech32 cards. `PostImageGrid` for 1/2/3/4+ layouts. |
| `ui/components/ImageViewerDialog.kt` | Fullscreen pager viewer (`HorizontalPager`). Custom gesture handler: pinch=zoom, 1-finger-at-scale1=pass-to-pager. |
| `ui/components/NotificationModal.kt` | Notification list. `NotifStyle` per type. 30s background polling. Animated new-item pill (`Column > AnimatedVisibility`). |
| `ui/components/EmojiPicker.kt` | Custom emoji picker. Defines `EmojiPickerCache` (5-min TTL `ConcurrentHashMap`, `internal object`). |
| `ui/components/ReactionEmojiPicker.kt` | Reaction picker (NIP-25). Uses shared `EmojiPickerCache` from `EmojiPicker.kt`. |
| `ui/screens/SettingsScreen.kt` | Mini-app hub (エンタメ / ツール / その他 categories). |
| `data/NostrRepository.kt` | All Nostr I/O. Notifications include Kind 6 (repost) and Kind 1 #p (reply/mention). `enrichPosts()` tracks `myLikeEventId`/`myRepostEventId` for toggle-undo. |
| `ui/screens/MainScreen.kt` | Root navigation (ホーム / トーク / タイムライン / ミニアプリ). |

### Web

| File | Purpose |
|---|---|
| `lib/nostr.js` | Core Nostr operations: signing, publishing, DM, Zap. |
| `lib/connection-manager.js` | WebSocket pooling, rate limiting (10 req/s), relay cooldowns. |
| `lib/recommendation.js` | Feed algorithm (mirrors Rust scoring). |
| `lib/secure-key-store.js` | Private key closure — never expose to `window`. |
| `lib/cache.js` | Two-layer cache: in-memory LRU + localStorage. |
| `lib/security.js` | CSRF, AES-GCM storage, `sanitizeContent()`. |

---

## Android Implementation Constraints

- **Post length**: 140 characters, strictly enforced in `PostModal.kt`.
- **Modals**: Full-screen `Surface` overlays as siblings to `Scaffold` inside a root `Box`.
- **BasicTextField**: Must NOT use `Modifier.weight(1f)` inside a scrollable `Column` — causes crash.
- **Compose performance**: Use `remember(post.event.id)` in `PostItem.kt`. No entrance animations in `TimelineScreen.kt`.
- **IO operations**: All Rust FFI calls, file I/O, and uploads must run on `Dispatchers.IO`.
- **Video recording**: `pointerInput` key must include permission state so the lambda re-creates after permission grant.
- **Kind 34236 required tags**: `d` (unique ID), `url`, `m`, `duration`, `imeta`, `thumb`, `x`, `verification`, `proofmode`.
- **AnimatedVisibility inside Box inside Column**: Kotlin resolves `ColumnScope.AnimatedVisibility` (outer receiver) over the top-level overload. Fix: wrap the call site in a `Column { }` to explicitly bring `ColumnScope` into scope, or extract to a standalone composable function.
- **Surface rounded corners**: Always pass `shape = RoundedCornerShape(…)` to `Surface` directly. Using only `Modifier.clip(shape)` causes the border to be drawn as a rectangle before clipping, cutting the corners visually.
- **Toggle like/repost**: `ScoredPost` carries `myLikeEventId`/`myRepostEventId`. On second tap, `TimelineViewModel` calls `repository.deleteEvent(eventId)` and decrements the counter.
- **Image uploads**: Use `async { }` inside `withContext(Dispatchers.IO)` for parallel uploads; collect with `awaitAll()`.
- **Zoom + pager gesture conflict**: In `ImageViewerDialog`, do NOT use `Modifier.transformable` — it consumes single-finger drags at `scale==1f`, blocking the `HorizontalPager`. Use `awaitEachGesture` with manual pointer-count branching instead.

---

## ProofMode (NIP-71 Video Verification)

Verification levels, in order:

| Level | Requirements |
|---|---|
| `verified_mobile` | Play Integrity API token + PGP signature + frame hashes |
| `verified_web` | PGP signature (Bouncy Castle Ed25519) + frame hashes + sensor data |
| `basic_proof` | Frame hashes only |
| `unverified` | Fallback on error |

Tags added to Kind 34236 events: `["x", sha256]`, `["verification", level]`, `["proofmode", json]`, `["pgp_fingerprint", fp]`, `["device_attestation", token]` (mobile only).

Note: `verified_mobile` requires the app to be distributed via Google Play Store. Sideloaded APKs fall back to `verified_web`.

---

## Web Implementation Constraints

- **Connections**: Max 4 global concurrent, 2 per-relay.
- **Logs**: `console.log/warn/debug` are stripped in production. Use `console.error` for critical issues only.
- **Sanitization**: Always use `sanitizeContent()` before `dangerouslySetInnerHTML`.
- **Private keys**: Use `storePrivateKey()` / `getPrivateKeyBytes()` from `lib/secure-key-store.js`. Never assign to `window`.

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

## Supported NIPs

NIP-01, 02, 05, 07, 09, 11, 17, 19, 25, 27, 30, 32, 42, 44, 46, 50, 51, 57, 58, 59, 62, 65, 70, 71, 98

Custom: Kind 34236 (Short Loop Video with ProofMode)
