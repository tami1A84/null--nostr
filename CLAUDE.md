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

- 作業ブランチ: `claude/refactor-and-docs-zzwXG`
- マージ先: `master`
