# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## ぬるぬる (null--nostr)

日本語圏向け高速 Nostr クライアント (Next.js 14 PWA)。

---

## コマンド

```bash
npm run dev           # 開発サーバー (localhost:3000)
npm run build         # プロダクションビルド
npm run test          # Vitest テスト実行
npm run test:watch    # テストウォッチモード
npm run test:coverage # カバレッジレポート生成
```

テスト1件だけ実行:
```bash
npx vitest run src/__tests__/対象ファイル.test.ts
```

Rust ビルド（ネイティブアプリ開発時のみ・rustup 必須）:
```bash
npm run build:rust                      # nurunuru-napi (デスクトップ向け)
cd rust-engine/nurunuru-ffi/bindgen
make xcframework    # iOS XCFramework
make android-all   # Android .so (arm64 + x86_64)
make kotlin        # Kotlin バインディング生成
```

---

## アーキテクチャ

### プラットフォーム別スタック

| プラットフォーム | スタック |
|---|---|
| **Web (Vercel)** | Next.js + nostr-tools + WebSocket 直接接続 |
| **iOS** | Swift → nurunuru-ffi (UniFFI) → nurunuru-core |
| **Android** | Kotlin → nurunuru-ffi (UniFFI) → nurunuru-core |
| **Desktop (Tauri等)** | nurunuru-napi (napi-rs) → nurunuru-core |

### Web 版の構成

```
ブラウザ
  ├─ nostr-tools SimplePool → wss://yabu.me 他 (リレーと直接 WebSocket)
  ├─ 署名: NIP-07 / Nosskey / Amber / NIP-46 (秘密鍵はブラウザ外 or secure-key-store)
  └─ localStorage + in-memory LRU キャッシュ (lib/cache.js)

Next.js サーバー (静的 + /api/nip05 のみ)
  └─ NIP-05 検証プロキシ (SSRF 対策済み)
```

**`lib/rust-bridge.js` と `lib/rust-engine-manager.js` は Web では null/false を返すスタブ。**
**Rust エンジンは Web サーバーでは一切使わない。**

---

## 主要モジュール

### `lib/connection-manager.js`
WebSocket プールのコアロジック。

- シングルトン SimplePool (グローバル上限 4 並列、リレー毎 2 並列)
- トークンバケット方式のレートリミット (10 req/s、バースト 20)
- リクエスト重複排除 (TTL: 実行中 30s、完了結果 5s)
- 失敗リレーのクールダウン (3 回失敗→2 分待機)
- SSL エラー検出で恒久無効化

### `lib/nostr.js`
Nostr プロトコル操作の全域担当。署名・発行・プロフィール取得・DM・Zap など。
connection-manager 経由でのみリレーと通信する。

### `lib/cache.js`
二層キャッシュ:
1. In-memory LRU (プロフィール 500 件上限)
2. localStorage (プロフィール 5 分、フォローリスト 10 分、タイムライン 30 秒)

### `lib/secure-key-store.js`
秘密鍵をモジュールスコープのクロージャー内に保持。`window` オブジェクトには絶対に露出させない。
ページアンロード時にゼロフィルで消去。**`window.nostrPrivateKey` への代入は禁止。**

### `lib/recommendation.js`
X 風フィードアルゴリズム。エンゲージメント重み: Zap=100、Quote=35、Reply=30、Repost=25、Like=5。
時間減衰: 1 時間以内 1.5x ブースト、半減期 6 時間。

### `lib/security.js`
CSRF トークン (sessionStorage)、AES-GCM 暗号化ストレージ、コンテンツサニタイズ、セッションタイムアウト (30 分)。

### `lib/validation.js`
URL プロトコルホワイトリスト、hex バリデーション、NIP-05 フォーマット、コンテンツ長制限。

---

## セキュリティ方針

- **秘密鍵**: `lib/secure-key-store.js` の `storePrivateKey()` / `getPrivateKeyBytes()` を使う
- **CSP ヘッダー**: `next.config.js` で設定済み。`frame-src 'none'` (iframe 禁止)、`connect-src wss: https:`
- **プロダクションビルド**: `console.log` / `console.warn` / `console.debug` は自動削除 (`removeConsole`)。`console.error` のみ残す
- **dangerouslySetInnerHTML**: 使用前に必ず `lib/security.js` の `sanitizeContent()` か LongFormPostItem の `sanitizeHtml()` を通す

---

## デフォルトリレー

```
wss://yabu.me                        (メイン、日本)
wss://relay-jp.nostr.wirednet.jp     (日本)
wss://r.kojira.io                    (日本)
wss://relay.damus.io                 (フォールバック)
wss://search.nos.today               (NIP-50 検索専用)
```

環境変数 (`NEXT_PUBLIC_DEFAULT_RELAY` 等) で上書き可能。`.env.example` 参照。

---

## Rust コア (ネイティブアプリ向け)

`rust-engine/nurunuru-core/` に nostr-sdk + nostrdb を使ったコアが実装済み (テスト 13 件 pass)。
`rust-engine/nurunuru-ffi/` に UniFFI バインディング完成済み。

使い方 (Swift):
```swift
let client = try NuruNuruClient(secretKeyHex: nsec, dbPath: NuruNuruClient.defaultDbPath())
client.connect()
let feed = try await client.getRecommendedFeedAsync(limit: 50)
```

使い方 (Kotlin):
```kotlin
val client = NuruNuruBridge.create(context, nsecKey)
client.connectAsync()
val feed = client.getRecommendedFeedAsync(50u)
```

**nostrdb は「1デバイス = 1ユーザー = 1インスタンス」の個人ローカルキャッシュ。サーバーで共有しない。**

---

## ブランチ運用

- 作業ブランチ: `claude/fix-android-stability-EL3mv`
- マージ先: `master`

---

## Android Kotlin 開発プラン (Web版との完全同期)

> **目標**: `android/` の Kotlin/Compose アプリを Web 版 (nullnull.app) と機能・デザイン完全一致させる。
> **ルール**: フェーズを順番に完結させてからコミット。一度に大きく変えない。

---

### フェーズ 0 — 投稿バグ修正 (最優先)

**症状**: 「投稿する」を押してもノートがリレーに到達しない。
**根本原因の仮説**:
1. リレーがイベントを拒否しているが、エラーが画面に表示されない（ポストが1件以上ある場合、`uiState.error` が LazyColumn に隠れる）
2. Schnorr 署名の純 Kotlin 実装 (`Secp256k1Impl`) が特定の秘密鍵でエラーになる可能性

**作業手順**:
1. **エラー表示を修正** — `TimelineScreen` に `Snackbar` を追加し、`uiState.error` を常に表示する
2. **ログ強化** — `NostrClient.OK` ハンドラで拒否理由を `Log.e` 出力（既存は `Log.w`）
3. **投稿 Toast** — 投稿成功/失敗を `Toast` または `Snackbar` でユーザーに通知
4. **実機テスト** — Logcat で `NostrClient: Event rejected by ...` を確認し、拒否理由を特定
5. 拒否理由に応じた修正 (例: `pow` 必要 → 未対応リレー除外、`bad sig` → Secp256k1Impl 修正)

**完了条件**: 「投稿する」押下後にノートがタイムラインに表示される。

---

### フェーズ 1 — エラー/フィードバック UI

全画面共通の UX 改善:
- `TimelineScreen`: 投稿成功/失敗を `Snackbar` で通知（投稿リスト有無に関わらず表示）
- `HomeScreen`: いいね/リポスト/返信のアクションを `HomeViewModel` に接続（現状 `{}` のまま）
- `TimelineScreen` / `HomeScreen`: `uiState.error` を常に Snackbar で表示（既存のリスト内表示は廃止）

---

### フェーズ 2 — PostItem: 返信コンテキスト表示

Web 版と同様に「@username への返信」をポスト本文上部に表示する。

**実装**:
- `ScoredPost` に `replyToProfile: UserProfile?` フィールド追加
- `NostrRepository.enrichPosts()` で `e` タグ (`reply`) の親イベント作者プロフィールを取得
- `PostItem` で `replyToProfile != null` 時に `"@displayName への返信"` を水色テキストで表示
- NIP-10 準拠: `tags` の `["e", id, relay, "reply"]` を正しくパース

---

### フェーズ 3 — プロフィール画面

アバタータップ → ユーザープロフィール画面に遷移する。

**実装**:
- `ProfileScreen.kt` 新規作成（バナー、アバター、bio、フォロー/フォロワー数、投稿一覧）
- `MainScreen` に `NavHost` または `profilePubkey` の State を追加してプロフィール表示を管理
- `onProfileClick` コールバックを全 `PostItem` で接続
- `ProfileViewModel.kt` 新規作成（プロフィール + 投稿フェッチ、フォロー/アンフォロー）
- `NostrRepository` に `followUser()` / `unfollowUser()` 追加（kind:3 更新）

---

### フェーズ 4 — NIP-05 非同期検証

ぬるぬる識別子の ✓ バッジを正しく表示する。

**実装**:
- `NostrRepository.verifyNip05()` 追加: `https://<domain>/.well-known/nostr.json?name=<local>` を OkHttp で GET し pubkey 一致確認
- プロフィールキャッシュに `nip05Verified` フラグを保存 (TTL: 24h)
- `/api/nip05` プロキシは Web 専用。Android では OkHttp で直接 HTTPS GET
- `UserProfile.nip05Verified` は `parseProfile()` では常に false → `verifyNip05()` 呼出後に更新

---

### フェーズ 5 — NIP-57 Zap

⚡ ボタンをタップして Lightning Zap を送る。

**実装**:
- `NostrRepository.fetchLnurlPay()` — `lud16` から LNURL-pay エンドポイントを解決
- `NostrRepository.createZapRequest()` — kind:9734 イベント作成
- `ZapBottomSheet` — sats 額入力シート（100, 500, 1000, カスタム）
- `TimelineViewModel.zapPost()` — Zap フロー実行（失敗時は Snackbar 通知）
- `onZap` を `TimelineScreen` / `HomeScreen` で接続

---

### フェーズ 6 — デザイン調整・Web 版完全同期

- **PostItem**: ポストのメニュー (`...`) を自分以外のポストにも表示（ミュート/報告用）
- **PostItem**: 長いコンテンツは「もっと見る」で展開
- **HomeScreen**: フォロー/フォロワー数を実際に取得して表示 (`followersCount` フィールド追加)
- **設定画面**: ミニアプリ → 設定 に改名し、プロフィール編集 (kind:0) を追加
- **テーマ**: ダークモード / ライトモード 切り替え対応
- **パフォーマンス**: `enrichPosts()` の fetchReactionCounts / fetchRepostCounts をページロード後に lazy fetch（初期表示を速くする）

---

### 実装ルール (Android Kotlin)

1. **新しいファイルは作らない** → 既存ファイルの編集を優先する
2. **ViewModelFactory は既存パターンに従う** → `ViewModelProvider.Factory` + `companion object`
3. **コルーチン** → `viewModelScope.launch` のみ使う。UIスレッドをブロックしない
4. **エラーハンドリング** → ユーザー向けメッセージは日本語。ログは `Log.e(TAG, ...)`
5. **NIP準拠** → Web版 (`lib/nostr.js`) と同じ NIP 番号・タグ構造を使う
6. **秘密鍵** → `AppPreferences.privateKeyHex` 以外に保存・渡さない
7. **テスト** → 各フェーズ完了後に実機またはエミュレーターで動作確認してからコミット
8. **コミット単位** → フェーズ単位でコミット。メッセージ: `feat(android): フェーズN - 機能名`
