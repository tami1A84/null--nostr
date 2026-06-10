# iOS Guardrails — null--nostr (ぬるぬる)

These rules are non-negotiable. Every PR and code review must verify compliance.

---

## 1. Design Parity with Android

- **Every screen, component, and flow must match the Android version exactly** in layout, color, spacing, corner radius, font size, and interaction behavior.
- Use the same design tokens from `design-tokens/constants.json`. Do not hardcode values.
- Auto-generate `NuruColors.swift`, `NuruSpacing.swift`, and `Constants.swift` from the shared token files (extend `npm run tokens` script).
- If Android has it, iOS must have it. If Android doesn't, iOS shouldn't either.

## 2. Font: LINE Seed JP Only

- Bundle `LineSeedJP-Rg.ttf` and `LineSeedJP-Bd.ttf` in the app.
- Register in Info.plist under `UIAppFonts`.
- Never fall back to system font for body text. Use `.custom("LineSeedJP", ...)` everywhere.
- Match Android's weight mapping: Regular → normal, Bold → semibold/bold.

## 3. Security — Non-Negotiable

- **Private keys**: Keychain only, with `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`.
- **Never** log, print, or paste private keys. No `print(nsec)`, no `os_log` of key material.
- **Never** store keys in UserDefaults, files, or SwiftData.
- Sanitize all user-generated content before rendering.
- Use `SecureField` for nsec input.
- Zeroize key material in memory when no longer needed.

## 4. Post Constraints

- 140-character limit: strictly enforced in UI (disable send button) and in publish function.
- Max 3 images per post. Parallel upload via `TaskGroup`.
- Links excluded from character count (same as Android/Web).

## 5. Networking Constraints

- Rate limit: 10 requests/second, burst 20 (match `ConnectionManager` in Android).
- Max 4 concurrent WebSocket connections globally, 2 per relay.
- Relay cooldown: 120 seconds after 3 consecutive failures.
- EOSE timeout: 15 seconds.
- Request timeout: 15 seconds.
- All values from `Constants.swift` (auto-generated).

## 6. Performance Rules

- `LazyVStack` for all lists. Never load full dataset into `VStack`.
- No entrance animations on timeline (causes jank on scroll).
- Use `.id(post.event.id)` for stable list identity.
- Nuke for image loading (disk + memory cache). No manual image caching.
- All network I/O on background tasks. Never block main actor.
- Profile/timeline skeleton count: match Android (timeline 5, profile 3).

## 7. Architecture Rules

- **@Observable** (iOS 17 Observation framework) for ViewModels. No Combine/ObservableObject.
- **NostrRepository as actor**: thread-safe data access, single source of truth.
- **No God objects**: Repository is split by concern (timeline, notifications, profiles, reactions, actions, talk) — same as Android.
- **No SwiftUI `.task` for pagination**: use `.onAppear` on sentinel item.
- **Async/await everywhere**: no completion handlers, no Combine for new code.

## 8. Rust FFI Phase 1

- iOS Rust FFI Phase 1 starts with read-only diagnostics only.
- Live helper: mlsIsEncrypted() -> Bool? via MlsFFIBridge / MlsFFILiveClient.
- Phase 1.1 may additionally call read-only MLS diagnostics: mlsListGroups() count and mlsGroupsNeedingSelfUpdate(thresholdSecs:) count.
- Phase 1.2 diagnostic polish may show only sanitized per-helper statuses such as ok / unavailable / failed and a local check time; raw Rust errors must not be displayed.
- true means the live UniFFI client reports the MLS DB is encrypted.
- false means the live UniFFI client reports plaintext; Talk must stay stopped/safe.
- nil means FFI unavailable, disabled, key material locked, MLS manager not bound, or stub/fallback active.
- Do not add signing, publishing, key generation, or private-key export to Phase 1.
- Do not log private keys, public keys, relay auth challenges, or DB paths from diagnostic UI code.

## 9. Navigation Rules

- 4-tab bottom navigation at root. Tab order: ホーム / トーク / タイムライン / ミニアプリ.
- Modals as `.sheet` (half/full). Image viewer as `.fullScreenCover`.
- No `NavigationLink` for modals — use `@State` booleans + `.sheet`.
- Deep link scheme: `nurunuru://`

## 10. NIP Compliance

- Same NIP support as Android where product-aligned, excluding iOS-only signer removals: 01, 02, 05, 07, 09, 11, 17, 19, 25, 27, 30, 32, 42, 44, 50, 51, 57, 58, 59, 62, 65, 70, 71, 98.
- NIP-46 signer is removed on iOS. Do not add NIP-55 (Amber) as a replacement.
- Verify NIP-05 with 5-second timeout.

## 11. Code Quality

- No force unwraps (`!`) except in tests or previews.
- No `Any` or `AnyObject` in public APIs — use generics or protocols.
- All public API has Swift DocC comments.
- Naming: match Android model names exactly (`ScoredPost`, `MlsGroup`, `NotificationItem`, etc.) for cross-platform consistency.
- Minimum iOS deployment target: 17.0.

## 12. Dependencies — Minimal

- Prefer Apple frameworks over third-party:
  - URLSession over Alamofire
  - CryptoKit over CommonCrypto
  - AVFoundation over third-party camera libs
  - SwiftData over Realm/CoreData (if needed)
- SPM only. No CocoaPods, no Carthage.
- Every new dependency must be justified and approved.

## 13. Build & CI

- Xcode project must build with `xcodebuild` from CLI (no manual Xcode-only steps).
- All tests runnable via `xcodebuild test`.
- No warnings in release builds (`SWIFT_TREAT_WARNINGS_AS_ERRORS = YES`).
- Scheme: NuruNuru (debug) and NuruNuru (release).

## 14. Localization

- All user-visible strings in `Localizable.strings` (or String Catalogs).
- Japanese is the primary language. English as fallback.
- Same string keys as Android where possible.

## 15. Checklist for Every New Screen/Component

- [ ] Matches Android equivalent pixel-for-pixel (compare screenshots)
- [ ] Uses design tokens, not hardcoded values
- [ ] Uses LINE Seed JP font
- [ ] Dark mode AND light mode tested
- [ ] No security violations (key exposure, unsanitized content)
- [ ] Performance: lazy loading, no jank
- [ ] Accessible (VoiceOver labels on interactive elements)
