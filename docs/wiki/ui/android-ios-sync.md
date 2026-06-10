# Android / iOS UI Sync

## Summary

Android と iOS は LINE 風 UI を platform native に実装しつつ、見た目・文言・挙動を可能な限り揃えます。このページの内容はコードを優先して更新する必要があります。

## Current behavior

### Typography and list behavior

- Android app-wide typography は `LineSeedJP`。
- iOS body text は LINE Seed JP のみ。system font fallback を避ける。
- Timeline は entrance animation を避ける。
- iOS list identity は `.id(post.event.id)` を使う。

### Navigation

- iOS tab bar は `.safeAreaInset(edge: .bottom, spacing: 0)`。
- Bottom nav target is 4 tabs: ホーム / トーク / タイムライン / ミニアプリ.
- Bottom nav icons are `house` / `message` / `newspaper` (or shared Timeline glyph) / `square.grid.2x2`. No `67` or News root icon in the iOS zero-base target.
- Home icon に `person.crop.circle` を使わない。





### News surface

- As of ADR-0022, News is **not** an iOS root tab.
- Existing News implementation/docs are treated as feature/history or future non-root surface until a new ADR restores it.
- If News is retained later, category tabs remain トップ / 国内 / エンタメ / スポーツ / 経済 / テック / Nostr and ranking tab remains absent unless re-decided.

### Home renewal two-layer structure (2026-06-02)

- Home header includes an account/profile icon. Existing profile, my posts, and likes list move into that icon's account/profile hub.
- Home body uses two layers: **アクティビティ** and **コンテンツ**.
- Existing Timeline following feed moves to Home **コンテンツ**. It must preserve stable identity, no entrance animations, PostActions rules, image grid behavior, and pagination continuity.
- **アクティビティ** must not become a relay-wide feed replacement. Use bounded account/social activity sources only.
- Rokunana has no Home entry in June; keep ADR-0018 dead-but-preserved behavior.

### Home settings and Mini Apps responsibility split

- Home tab gear settings own account/security UI across platforms: login status card, short pubkey/npub display, logout confirmation, auto-sign controls, and explicit nsec export warnings.
- Mini Apps tab must stay focused on app discovery/launch: search, favorites, category tabs, built-in mini-app rows, and external mini-app management. Do not render login status, logout, auto-sign, or private-key export controls inside Mini Apps.
- iOS Home settings is presented with fullScreenCover so private-key export never appears in a partially exposed sheet. Android uses a full-screen Dialog; Web uses a full-screen portal modal.
- iOS has extra Rust FFI diagnostic/write-path controls inside the Home settings security section. This is an intentional platform-specific section, not an Android/Web parity requirement.
- Android must enable FLAG_SECURE only while the nsec value is visible and clear it when the section closes/disposes. iOS marks the nsec text privacySensitive and clears exported nsec on disappear/background.

### Talk chat screen

- Native Talk chat should visually follow LINE dark chat: black header/background, compact 56dp/pt header, right-side search / call / calendar / menu affordances, green outgoing bubbles, dark-gray incoming bubbles, and small outside timestamps for both sides. Read-status labels (e.g. 「既読」) are not rendered because real read-receipts cannot yet be verified end-to-end over MLS.
- Incoming bubbles render avatar + bubble only. Do **not** render the sender's display name above the bubble — the avatar already carries identity and the duplicated name (often appearing on every consecutive message) breaks the LINE silhouette. The display name remains available in the group info sheet.
- Sending must feel instantaneous (ぬるぬる = ultra-smooth). The optimistic message bubble must be appended to `messages` in the same UI frame as the send tap, **before** any DM canonicalize / MLS catch-up / gap repair work. Auto-scroll-to-bottom after send is non-animated (`scrollToItem` on Android, and on iOS a `proxy.scrollTo` wrapped in a `Transaction` with `disablesAnimations = true` so no enclosing `.animation(...)` modifier can attach an implicit ease) — the optimistic bubble is already at the bottom, so any easing only adds perceived latency. If a later DM remap chooses a different canonical group, rewrite the optimistic message's `groupIdHex` in place rather than dropping and re-adding the bubble.
- The send affordance itself must not animate between the mic and paper-plane states. Do **not** add `.animation(...)` modifiers to the send button on iOS, and do **not** wrap the send button color/tint in `animateColorAsState` on Android. The state must flip on the same frame as the tap; any easing reads as the app "thinking" before it sends. Failed sends still surface via `abortOptimisticSend(...)` (bubble removed + error toast), so the only thing easing would communicate is fake latency.
- The send-button spinner (`ProgressView` on iOS, `CircularProgressIndicator` on Android) must reflect **only the actual MLS send call** — that is, the `withTimeout(30s) { repository.sendMlsMessage(...) }` block. It must **not** cover the pre-flight catch-up / repair / deep-catch-up chain. Those timeouts can legitimately stack to 60+ seconds, and a spinner that long contradicts the optimistic bubble that already confirmed the send to the user. Concretely: `sendingMessage = true` is set immediately before the send call, not at the top of `sendMessage(...)`. The optimistic bubble + cleared composer are the affordance for "your message was accepted"; the spinner is the affordance for "the network round-trip is in flight" only. `abortOptimisticSend(...)` still defensively clears `sendingMessage` for any path that flips it true.
- Header action icons must be the same glyph family on both platforms. iOS uses SF Symbols `magnifyingglass` / `phone` / `calendar` / `line.3.horizontal`; Android mirrors them with `Icons.Outlined.Search` / `Call` / `CalendarToday` / `Menu`. Do not substitute Unicode text glyphs (⌕ ☎ 31 ☰) — they render with system font fallbacks and break cross-platform parity.
- Composer left-side icons are 3 distinct glyphs in this order: add (`plus` / `Icons.Outlined.Add`), camera (`camera` / `Icons.Outlined.PhotoCamera`), and photo library (`photo` / `NuruIcons.Image`). The camera and photo slots must use different icons — duplicating the photo glyph in the camera slot is a regression.
- Explicitly creating a new 1:1 Talk is a fresh DM boundary, not a request to reopen/merge the previous sibling DM. Older sibling DM group ids for the same peer may remain in the local MLS SQLite DB for cryptographic recovery, but they must be locally hidden from the visible group list and must not be merged into the newly-created Talk after restart. Canonical DM selection can still prefer peer-history siblings for existing duplicate DMs, but the explicit fresh-create path pins the new group as canonical.
- Opening a Talk must be cache-first. The tapped group's local MLS SQLite history is painted immediately, `messagesLoading` is cleared, and only then may sibling local-history consolidation / relay-backed repair / canonical scanning refine the view. Relay catch-up must never be a prerequisite for showing cached Talk history after launch.
- Leaving/exiting an MLS Talk is authoritative locally. iOS and Android must keep an exited group hidden during both cache-first startup and later relay-backed refresh, even if that makes the Talk list empty. Relay/SQLite presence alone must not resurrect an exited group.
- When a Talk conversation is open, the global bottom tab bar is hidden so the composer sits at the bottom like LINE. The tab bar returns on the Talk list and other tabs.
- Debug identifiers must not appear in the chat header, message composer, or conversation list. The MLS group ID is shown only in the group information sheet.

### Post headers

- Timeline post headers must show NIP-05 directly under the user display name when `UserProfile.nip05` is present. The verification checkmark stays beside the name only when NIP-05 resolves to the author pubkey. The NIP-05 text remains visible even before/without verification, colored LineGreen when verified and tertiary text otherwise. `_@domain` is displayed as `domain`.

### PostActions

現行コードでは PostActions は **常時 3 ボタンではありません**。

- Android `PostActions.kt` は like / repost / zap を常時表示し、`onBookmark` が渡された場合は bookmark も表示する。
- iOS `PostActions.swift` も like / repost / zap / bookmark の並びを持つ。
- Reply button は PostActions にはない。返信は詳細画面や投稿シートの `replyTo*` 経由で扱う。
- Repost は通常タップで kind 6 repost、長押しで quote repost を起動できる。
- Like は thumbs-up 系アイコン。heart ではない。
- 投稿表示対象イベントの末尾に `client` tag がある場合 `via ...` 表示を行う。新規投稿で付ける表示名は iOS `ぬるぬるiOS`、Android `ぬるぬるAndroid`、Web `ぬるぬるweb`。`client` tag は投稿元クライアント表示用であり、kind 10002 relay list metadata などの設定・リスト系イベントには付けない。

### Copy and modals

- Collapse text は「もっと見る」/「閉じる」。 「続きを読む」は使わない。
- Full-screen image viewer は iOS `.fullScreenCover`、Android fullscreen overlay / dialog 方針を守る。

## Source references

- `android/app/src/main/kotlin/io/nurunuru/app/ui/components/PostContent.kt`
- `ios/NuruNuru/Views/Components/PostContent.swift`
- `android/app/src/main/kotlin/io/nurunuru/app/ui/components/PostActions.kt`
- `ios/NuruNuru/Views/Components/PostActions.swift`
- `android/app/src/main/kotlin/io/nurunuru/app/ui/screens/MainScreen.kt`
- `ios/NuruNuru/Views/Screens/MainTabView.swift`
- `ios/GUARDRAILS.md`

- `android/app/src/main/kotlin/io/nurunuru/app/ui/screens/TalkScreen.kt`

- `android/app/src/main/kotlin/io/nurunuru/app/ui/components/TalkComponents.kt`

- `android/app/src/main/kotlin/io/nurunuru/app/ui/components/GroupInfoModal.kt`

- `ios/NuruNuru/Views/Screens/TalkView.swift`

- `ios/NuruNuru/Views/Sheets/GroupInfoSheet.swift`

- components/SettingsModal.js
- components/AccountSecuritySettings.js
- components/MiniAppTab.js
- android/app/src/main/kotlin/io/nurunuru/app/ui/components/AccountSecuritySettings.kt
- android/app/src/main/kotlin/io/nurunuru/app/ui/screens/MiniAppsScreen.kt
- ios/NuruNuru/Views/Screens/AppSettingsView.swift
- ios/NuruNuru/Views/Components/AccountSecuritySettingsView.swift
- ios/NuruNuru/Views/Screens/MiniAppsView.swift
## Related pages

- [[platforms/android]]
- [[platforms/ios]]
- [[features/timeline]]
- [[features/post-composer]]


## QA

- Home settings / Mini Apps split verification: [[quality/qa-2026-06-02]].


## Relay diagnostics sync note

- iOS `RelaySettingsView` exposes Rust publish diagnostics (pending signed outbox count, manual retry, RelayRouter health) behind `NURUNURU_FFI_AVAILABLE`.
- Android Mini Apps relay settings has an equivalent local diagnostics card with pending signed outbox count, manual retry, and RelayRouter health.
- Web Relay Settings now has a parity diagnostics card backed by browser-local signed publish outbox state and `connection-manager.js` relay health. Keep copy/layout aligned when any platform changes; raw signed event JSON/content must not be shown.
