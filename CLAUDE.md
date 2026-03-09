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
npm run test                      # all tests
npx vitest run src/__tests__/filename.test.ts   # single test
```

### Android
```bash
cd android && ./gradlew assembleDebug    # build APK
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

## Architecture

### FFI Bridge
`rust-engine/nurunuru-ffi/src/lib.rs` → UniFFI → auto-generated `bindgen/kotlin-out/uniffi/nurunuru/nurunuru.kt` → loaded by the Android app via JNA.
The generated `.kt` file must be committed alongside any `lib.rs` API changes.

### Recommendation Algorithm
Implemented in Rust at `nurunuru-core/src/recommendation.rs` (`rank_feed()`) and orchestrated by `engine.rs` (`get_recommended_events_ordered()`):
- Network candidates: follows + 2nd-degree, 48h window
- Viral candidates: global, 1h window
- Both fetched via `tokio::join!` in parallel
- Exposed to Android via `fetch_recommended_timeline(limit, user_geohash)` in FFI
- Android entry point: `NostrRepository.fetchRecommendedTimeline()` → called from `TimelineViewModel` for the おすすめ tab

### Design Tokens
`design-tokens/constants.json` is the source of truth for weights, colors, and limits. Run `npm run tokens` to sync to Android/Web.
