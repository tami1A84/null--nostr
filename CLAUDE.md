# null--nostr (ぬるぬる) — CLAUDE.md

> **AIへの指示書**。このファイルはセッション開始時に必ず読むこと。

---

## プロジェクト概要

**ぬるぬる**は日本語圏向けの高速 Nostr クライアント (Next.js PWA)。
現在、コアロジックを JS → Rust へ段階的に移行中。

### 技術スタック

| 層 | 技術 | 状態 |
|---|---|---|
| フロントエンド | Next.js 14 + Tailwind | 稼働中 |
| Nostr プロトコル | `nostr-tools` (JS) | 稼働中・移行対象 |
| Rust エンジン (コア) | `nostr-sdk` v0.44 + `nostrdb` v0.8 | 実装済み・未接続 |
| FFI ブリッジ | `napi-rs` (予定) | **次のステップ** |

---

## リポジトリ構造

```
null--nostr/
├── app/                    # Next.js App Router ページ
├── components/             # React コンポーネント
├── lib/                    # JS ビジネスロジック（移行元）
│   ├── nostr.js            # イベント署名・発行・購読
│   ├── cache.js            # localStorage + LRU キャッシュ
│   ├── recommendation.js   # フィードランキング (X風アルゴリズム)
│   ├── filters.js          # Nostr Filter ファクトリ
│   ├── connection-manager.js # リレー接続管理
│   └── ...
├── rust-engine/            # Rust コアエンジン（移行先）
│   ├── Cargo.toml          # Workspace
│   ├── nurunuru-core/      # コアライブラリ（実装済み）
│   └── nurunuru-ffi/       # UniFFI バインディング（スキャフォルド済み）
└── CLAUDE.md               # このファイル
```

---

## 現在の移行状況

### 完了済み ✅

- `rust-engine/nurunuru-core` の実装（全 13 テスト pass）
  - `engine.rs` — `NuruNuruEngine` (nostr-sdk Client + nostrdb バックエンド)
  - `recommendation.rs` — フィードスコアリング (JS の `recommendation.js` 完全移植)
  - `filters.rs` — Filter ファクトリ (JS の `filters.js` 完全移植)
  - `relay.rs` — リレーURL検証 + ジオハッシュ近接選択
  - `config.rs` — 全設定値 (JS の `constants.js` 対応)
  - `error.rs` — 日本語エラーメッセージ
- `rust-engine/nurunuru-ffi` スキャフォルド (UniFFI proc-macro)

### 未実装・次のステップ 🔲

**Step 1: napi-rs ブリッジ（JS ↔ Rust / Node.js/Next.js 直結）**

Web アプリとして使い続けるなら UniFFI より `napi-rs` が最適。
`.node` ネイティブモジュールとして Next.js から直接呼べる。

```
rust-engine/nurunuru-napi/   ← 新規作成
├── Cargo.toml               (napi-rs 依存)
├── build.rs
└── src/
    └── lib.rs               (nurunuru-core をラップした #[napi] 関数群)
```

**Step 2: キャッシュ移行（localStorage → nostrdb）**

`lib/cache.js` の `setCachedProfile` / `getCachedProfile` などを、
napi-rs 経由で nostrdb の `query_local()` に差し替える。

**Step 3: レコメンド移行**

`lib/recommendation.js` の `sortByRecommendation` / `getRecommendedPosts` を
napi-rs 経由で Rust の `get_recommended_feed()` に差し替える。

**Step 4: リレー接続移行**

`lib/connection-manager.js` を Rust の `NuruNuruEngine::connect()` に差し替える。

---

## 次の作業指示（AIへ）

### napi-rs ブリッジを作る手順

1. **`rust-engine/nurunuru-napi/` を新規作成**

   ```toml
   # Cargo.toml
   [dependencies]
   nurunuru-core = { path = "../nurunuru-core" }
   napi = { version = "2", features = ["async", "tokio_rt"] }
   napi-derive = "2"
   tokio = { version = "1", features = ["rt-multi-thread"] }

   [build-dependencies]
   napi-build = "2"
   ```

2. **`src/lib.rs` に `#[napi]` 関数を実装**

   対象関数（優先順）:
   - `query_local(filter_json: String) -> Vec<String>` — DB から直接イベント取得
   - `get_recommended_feed(limit: u32) -> Vec<ScoredPost>` — フィードランキング
   - `fetch_profile(pubkey_hex: String) -> Option<UserProfile>` — プロフィール

3. **`package.json` に napi-rs ビルドスクリプトを追加**

   ```json
   "scripts": {
     "build:rust": "cd rust-engine/nurunuru-napi && cargo build --release && napi build --platform --release"
   }
   ```

4. **Next.js から呼び出す**

   ```js
   // lib/rust-bridge.js
   let engine = null
   try {
     engine = require('../rust-engine/nurunuru-napi/index.node')
   } catch {
     engine = null // フォールバック: 既存JS実装を使う
   }
   export { engine }
   ```

---

## 重要な設計方針

- **段階的移行**: Rust が使えない環境では既存 JS にフォールバックする
- **JS は壊さない**: `lib/` の既存コードは移行完了まで残す
- **nostrdb が正**：イベントの永続化・検索は全て nostrdb に集約する
- **napi-rs > UniFFI**: Web (Next.js) ターゲットは napi-rs を優先。
  モバイル (Android/iOS) は後で nurunuru-ffi (UniFFI) を使う

## デフォルトリレー（日本）

```
wss://yabu.me              (メイン)
wss://relay-jp.nostr.wirednet.jp
wss://r.kojira.io
wss://relay.damus.io       (フォールバック)
wss://search.nos.today     (NIP-50 検索専用)
```

## ブランチ運用

- 作業ブランチ: `claude/rust-backend-migration-YT6oe`
- マージ先: `master`
