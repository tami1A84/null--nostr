# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.3.1] - 2026-03-10

### Fixed (Android)
- 投稿時にアプリが5秒フリーズしてANR（強制終了）する問題を修正
  - Rust FFI呼び出し（`createUnsignedEvent` / `publishRawEvent`）をIOスレッドに移動
- URLプレビューでYouTube・Spotifyなど一部URLを開くとクラッシュする問題を修正
  - microlink APIレスポンスの`null`フィールドを安全にパースするよう修正
- 画像・動画アップロード処理をIOスレッドに移動、エラーハンドリング強化
  - `PostModal` / `EditProfileModal` / `SignUpModal` / `DivineVideoRecorder`
- OkHttpClient のタイムアウト設定を追加（接続15秒・読み込み90秒・書き込み120秒）
- タイムラインキャッシュの読み書きをIOスレッドで実行するよう修正（ANR防止）

## [1.2.0] - 2026-03-10

### Fixed (Android)
- JNA/UniFFI ProGuardルール欠落によるリリースビルドのクラッシュ・タイムライン未表示を修正
  - `com.sun.jna.**` / `uniffi.nurunuru.**` / `rust.nostr.**` を R8 難読化対象外に設定

## [1.1.0] - 2026-03-09

### Added (Android)
- Rustエンジンによるおすすめタイムライン
- Pull-to-refresh・新着投稿ピルUI
- ハッシュタグタップ対応
- 外部ミニアプリをWebView（NIP-07署名プロキシ）で開く機能
- バッジ・カスタム絵文字のキャッシュファースト表示

### Fixed (Android)
- ホームタブの投稿・いいね更新が反映されない問題
- タブ切り替え時のスクロール状態保持

## [1.0.3] - 2025-12-22

### Changed
- Replaced nostr-login library with custom login modal
- New login modal with clear method selection:
  - NIP-07 Browser Extensions (Alby, nos2x)
  - Nostr Connect / NIP-46
  - Read-only mode (npub input)
  - Local key (nsec input)
- Improved login method display in settings (shows specific method)

### Removed
- nostr-login dependency (66 packages removed)
- Simplified codebase without external login library

### Fixed
- Login screen now reliably shows login methods
- No more automatic redirects to connect screen

## [1.0.2] - 2025-12-22

### Changed
- Simplified login system: Passkey (primary) + nostr-login (secondary)
- Removed direct NIP-07 browser extension login (now handled via nostr-login)
- Removed Amber/NIP-55 Android-specific login
- nostr-login now handles all alternative login methods:
  - Nostr Connect (NIP-46 remote signing)
  - Browser extensions (Alby, nos2x, Nostash)
  - Local nsec key input
  - Read-only mode (npub)

### Removed
- Android-specific login detection
- Direct NIP-07 extension login button

### Fixed
- Mobile browser incorrectly showing Android login options

## [1.0.1] - 2025-12-21

### Added
- NIP-55 Amber signer support for Android
- Android detection and optimized login flow
- nostr-login initialization fix (now loads on demand)

### Changed
- Login screen now shows Amber as primary option on Android
- Passkey login skipped on Android (requires Digital Asset Links)
- Login method display updated for Amber/nostr-login

### Fixed
- nostr-login button not responding (initialization timing issue)
- signEvent using window.nostr directly when Amber provides it

## [1.0.0] - 2025-12-21

### Added
- Initial release
- Timeline with relay/follow mode switching
- Fixed header during scroll
- Encrypted DM support (NIP-17/44/59)
- Like, repost, and Zap functionality
- NIP-05 verification badge
- Profile badges display (NIP-58)
- Custom emoji support (NIP-30)
- Follow list management with unfollow
- Mute list support (NIP-51)
- NIP-50 search capability
- Passkey login (Nosskey)
- NIP-46 remote signing (nostr-login)
- PWA support

### Technical
- Request throttling to prevent "too many concurrent REQs"
- Multi-relay badge fetching
- Profile and follow list caching
- Batch profile fetching
