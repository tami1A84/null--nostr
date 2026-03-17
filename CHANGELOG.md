# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.3.8] - 2026-03-18

### Added (Android)
- リレーライブストリーム: 特定リレー選択中も新着投稿ピルを表示 (EOSE後も WS 接続を維持し kind 1 をリアルタイム受信)
- `@メンション` (nostr:npub1/nprofile1) をプロフィールカードからインライン `@ユーザー名` テキストに変更、タップでプロフィールモーダルを表示

### Fixed (Android)
- GLOBALタブ (リレータブ) の新着ピルが表示されない問題: `HorizontalPager` に `beyondBoundsPageCount=1` を設定し、バックグラウンドページの Compose ツリーを維持
- リレー接続タイムアウト・0件取得時に自動解除して通常フィードへ復帰
- 検索モーダルでいいね・リポストが機能しない問題: ViewModel の投稿検索を `searchResults` / `relayPosts` にも拡張
- URLプレビューの OkHttp コネクションリーク修正 (`response.use {}` で確実にクローズ)
- ヘッダーのリレーピル内に ▼ ドロップダウンを統合、外部タップで閉じるように修正

### Changed (Android)
- ミニアプリタブのリレー単一選択ラジオボタンを削除

## [1.3.7] - 2026-03-15

### Added (Android)
- 高度検索コマンド対応 (NIP-50 + 標準フィルタ + クライアント側後処理):
  - `#タグ` — ハッシュタグで絞り込み (`#t` フィルタ)
  - `from:npub1...` / `from:user@domain` — 投稿者を指定 (NIP-05自動解決)
  - `since:YYYY-MM-DD` / `until:YYYY-MM-DD` — 期間指定
  - `-除外語` — キーワード除外 (日本語対応)
  - `"完全一致フレーズ"` — 完全一致フィルタ
  - `filter:image` / `filter:video` / `filter:link` — メディア種別絞り込み
  - 複数コマンドの組み合わせ対応 (`#japan since:2025-01-01 -spam` など)
- 検索画面にコマンド一覧を常時表示、タップで検索バーへ追記（カーソルは末尾に移動）
- NIP-50検索の `since` 1時間固定制限を撤廃、全期間対象に

### Changed (Android)
- 検索バーのプレースホルダーをコマンド表示に変更 (`#タグ  from:  since:  until:  -除外`)

## [1.3.6] - 2026-03-15

### Added (Android)
- バッジアワード (kind 8) 通知: バッジを授与されると通知一覧に🏅表示
- リアクション通知を「リアクション (👍)」と「絵文字リアクション」に分離、それぞれ個別にON/OFF可能
- キャッシュ設定にプロフィールバッジ (kind 8, 30009) の項目を追加
- キャッシュ設定の全デフォルト保持期間を1日に統一

### Changed (Android)
- おすすめタブ → リレータブ: アルゴリズム・スコアリングを撤廃し、メインリレーの最新 kind 1 を時系列表示。ミュートのみ適用。
- 通知モーダルのヘッダー右端に⚙設定アイコンを追加、種別ごとのON/OFFをモーダル内で完結
- 通知ライブポーリングを10秒間隔に短縮、新着ピル表示に対応
- タイムラインのローディングアニメーションをシマースケルトンに変更 (旧: バウンスドット)
- リレータブの新着ピル閾値を3件に設定、タップで先頭に挿入 (再フェッチなし)
- ミニアプリ「リレー設定」: NIP-65表記を整理、手動URL入力欄と「自分のリレーリストを読み込む」ボタンを追加

## [1.3.5] - 2026-03-13

### Fixed (Android)
- 外部ミニアプリで画像などのファイルアップロードができない問題: `WebChromeClient.onShowFileChooser()` を実装し、ファイルピッカーを起動可能に
- 外部ミニアプリ表示中に下部タブバーが残る問題: ミニアプリオープン中はタブバーをスライドアウト非表示に
- 外部ミニアプリ内リンク遷移後に戻れない問題: ← ボタンが WebView 内履歴を遡るように変更、✕ ボタンでいつでもミニアプリを閉じられるように追加

## [1.3.4] - 2026-03-13

### Added (Android)
- 外部ミニアプリの編集・削除機能: 長押しで BottomSheet が開き、名前・URL を編集、または削除が可能

### Fixed (Android)
- アップデート時に外部ミニアプリの登録が消える問題: 非機密設定を通常の SharedPreferences に移行し、暗号化ストレージの更新時リセットを回避
- 外部ミニアプリ追加後に即時反映されない問題: `mutableStateListOf` により追加と同時にリスト更新
- いいね・リポストの取り消しが機能しない問題: `react()` / `repost()` の戻り値（イベント ID）を `myLikeEventId` / `myRepostEventId` にセットすることで同一セッション内でのアンドゥを可能に

## [1.3.3] - 2026-03-12

### Added (Android)
- いいね・リポストの取り消し: 2回目のタップで Kind 5 (NIP-09) 削除イベントを送信し、ボタン状態と件数を即時更新
- 通知モーダル リニューアル: アバター右下に種別バッジ（❤️ リアクション / ⚡ Zap / 🔁 リポスト / 返信 / メンション）
- 通知の新着ピル: バックグラウンド30秒ポーリングで新着を検知、上から滑り込むアニメーション付きピル
- 通知にリポスト(Kind 6)・返信/メンション(Kind 1 #p) を追加
- 複数画像フルスクリーンビューア: スワイプでページ切り替え、ピンチ/ダブルタップズーム、ドット+枚数インジケーター
- nostr: bech32 リンク (nevent/nprofile) をカード表示 — 本文中の生テキストを非表示に
- カスタム絵文字ピッカー キャッシュファースト: 5分 TTL のインメモリキャッシュ、EmojiPicker / ReactionEmojiPicker で共有
- 画像複数枚投稿を並列アップロードに変更 (web版と同期)
- 3枚画像グリッドのレイアウト修正: 左1枚フル高さ + 右2枚スタック

### Fixed (Android)
- nevent カードの角が切れる問題: `Surface(shape=…)` に統一し `Modifier.clip` を削除
- 引用投稿でテキストとカードの間に大きな空白が生じる問題: 末尾の余分な改行をトリム
- nevent カードタップでプロフィール画面に遷移してしまう問題: カード全体のクリックを削除、著者行のみプロフィール遷移
- カスタム絵文字リアクションを通知一覧でも画像表示に統一

## [1.3.2] - 2026-03-11

### Added (Android)
- 動画タップでミュート解除: フィードの動画をタップで音声ON/OFF切り替え（右下に🔇/🔊アイコン）
- ProofModeバッジ: フィードの動画右上にレベル別バッジを表示
  - 🛡 ProofMode（青）= `verified_mobile`、✓ ProofMode（緑）= `verified_web`
- ミニアプリに「その他」カテゴリ追加: 外部ミニアプリを分類
- DivineVideoRecorder・ProofModeManager に MPL-2.0 ライセンス表記追加

### Fixed (Android)
- 動画に音声が録音されない問題を修正: `withAudioEnabled()` を常に有効化
- 投稿画面のツールバーがキーボードに隠れる問題を修正 (`imePadding` をツールバー直接に適用)
- ミニアプリのURL入力フォームがキーボードに隠れる問題を修正
- フィードの動画秒数表示: ハードコードの "6.3s" から `duration` タグの実際の値に変更
- ProofMode verificationタグ名の誤り修正 (`verification-level` → `verification`)
- Kind 34236 投稿成功時に "POST_FAILED" と表示されていたバグを修正

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
