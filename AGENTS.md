# AGENTS.md

This file provides context and instructions for AI coding agents working on the **null--nostr (ぬるぬる)** project.

## Project Overview
null--nostr is a high-performance, LINE-like Nostr client designed for the Japanese community. It is built with Next.js (PWA) for Web, and features a shared Rust core for native platforms (iOS, Android, Desktop).

## Setup Commands
### Web (Next.js)
- Install dependencies: `npm install`
- Start dev server: `npm run dev` (available at http://localhost:3000)
- Build for production: `npm run build`
- Run tests: `npm run test`
- Run specific test: `npx vitest run src/__tests__/filename.test.ts`

### Android
- Build debug APK: `cd android && chmod +x gradlew && ./gradlew assembleDebug`

### Rust Core (Native development only)
- Build NAPI (Desktop): `npm run build:rust`
- Build XCFramework (iOS): `cd rust-engine/nurunuru-ffi/bindgen && make xcframework`
- Build Android (.so): `cd rust-engine/nurunuru-ffi/bindgen && make android-all`
- Generate Kotlin bindings: `cd rust-engine/nurunuru-ffi/bindgen && make kotlin`

## Architecture
### Platform Stack
- **Web (Vercel)**: Next.js + nostr-tools + direct WebSocket connections.
- **iOS/Android**: Swift/Kotlin → nurunuru-ffi (UniFFI) → nurunuru-core (Rust).
- **Desktop**: nurunuru-napi (napi-rs) → nurunuru-core (Rust).
- **Native Core**: `rust-engine/nurunuru-core` uses `nostr-sdk` and `nostrdb`.

### Design Tokens & Constants
- `design-tokens/constants.json` is the single source of truth for UI settings, weights, and error messages.
- Run `npm run tokens` to sync changes to `lib/constants.generated.js` (Web) and `android/app/src/main/kotlin/io/nurunuru/app/data/Constants.kt` (Android).

### Web Composition
- **Client**: `nostr-tools` SimplePool for relay connections.
- **Signing**: Supports NIP-07, Nosskey, Amber, and NIP-46.
- **Cache**: `localStorage` + in-memory LRU (`lib/cache.js`).
- **Server**: Static hosting + `/api/nip05` for SSRF-protected verification.
- **Note**: `lib/rust-bridge.js` and `lib/rust-engine-manager.js` are stubs in Web mode.

## Key Modules
- `lib/connection-manager.js`: Handles WebSocket pooling, rate limiting (10 req/s), and relay cooldowns.
- `lib/nostr.js`: Core Nostr protocol operations (Signing, Publishing, DM, Zap).
- `lib/cache.js`: Two-layer caching (In-memory LRU + localStorage).
- `lib/secure-key-store.js`: Securely holds private keys in a module-level closure. **NEVER expose to `window.nostrPrivateKey`**.
- `lib/recommendation.js`: X-inspired feed algorithm. Weights: Zap (100), Quote (35), Reply (30), Repost (25), Like (5).
- `lib/security.js`: CSRF, AES-GCM encrypted storage, and content sanitization.
- `lib/validation.js`: Input validation for URLs, pubkeys, and NIP-05.

## Security Guidelines
- **Private Keys**: Use `storePrivateKey()` and `getPrivateKeyBytes()` from `lib/secure-key-store.js`.
- **Sanitization**: Always use `sanitizeContent()` from `lib/security.js` before using `dangerouslySetInnerHTML`.
- **Logs**: Production builds automatically remove `console.log/warn/debug`. Use `console.error` for critical issues.

## Implementation Details & Constraints
### Web
- **Post Length**: 140 characters threshold for collapsing. Links are excluded from the count.
- **Connections**: Throttled to 4 global concurrent and 2 per-relay concurrent connections.

### Android
- **Post Length**: Strictly enforced 140 characters limit in `PostModal.kt`.
- **Performance**: Use `remember(post.event.id)` in `PostItem.kt`. Avoid item entrance animations in `TimelineScreen.kt`.
- **Modals**: Full-screen overlays using `Surface` implemented as siblings to `Scaffold` in a root `Box`.
- **TextField**: `BasicTextField` within a scrollable `Column` must NOT use `Modifier.weight(1f)` to avoid crashes.
- **Database**: `nostrdb` is stored in `context.filesDir/nostrdb_ndb`.
- **Signer**: Supports NIP-55 (Amber) via `ExternalSigner.kt`.

## Recommendation Algorithm
### Feed Composition
- 50% 2nd-degree network (Friends of friends)
- 30% High-engagement viral content
- 20% 1st-degree network (Direct follows)

### Scoring
`Score = Engagement × Social × Author × Geohash × Modifier × TimeDecay`
- Time Decay: 1.5x boost for <1h, 6h half-life.

## Default Relays
- wss://yabu.me (Main, JP)
- wss://relay-jp.nostr.wirednet.jp (JP)
- wss://r.kojira.io (JP)
- wss://relay.damus.io (Fallback)
- wss://search.nos.today (NIP-50 Search)

## NIPs Supported
NIP-01, 02, 05, 07, 09, 11, 17, 19, 25, 27, 30, 32, 42, 44, 46, 50, 51, 56, 57, 58, 59, 62, 65, 70, 98.
Custom: Kind 34236 (Short Loop Video).
