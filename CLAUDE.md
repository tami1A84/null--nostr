# null--nostr (ぬるぬる) — CLAUDE.md

> **AIへの指示書**。このファイルはセッション開始時に必ず読むこと。

> **重要**: 作業完了後は必ずこの CLAUDE.md を更新すること。
> 完了した Step を ✅ に変更し、新規実装ファイル・API エンドポイント・使い方を追記する。

---

## プロジェクト概要

**ぬるぬる**は日本語圏向けの高速 Nostr クライアント (Next.js PWA)。

### 技術スタック

| 層 | 技術 | 状態 |
|---|---|---|
| フロントエンド | Next.js 14 + Tailwind | 稼働中 |
| Nostr プロトコル (Web) | `nostr-tools` (JS) + WebSocket | **正規の実装・維持** |
| Rust エンジン (コア) | `nostr-sdk` v0.44 + `nostrdb` v0.8 | 実装済み・**ネイティブアプリ向け** |
| FFI ブリッジ (Web) | `napi-rs` v2 | 実装済み・**Web では使わない方針へ変更** |
| FFI ブリッジ (Mobile) | UniFFI | スキャフォルド済み・Step 10 で完成予定 |

---

## ⚠️ 重要なアーキテクチャ方針（2026-02 改訂）

### nostrdb は「個人ローカル向け」であり、サーバー共有 DB ではない

nostrdb の設計思想：
- **1デバイス = 1ユーザー = 1インスタンス**（個人のローカルキャッシュ）
- モバイルアプリ (iOS/Android) やデスクトップアプリ向け
- 複数ユーザーが共有することを**想定していない**

以前の実装（Step 2〜8）は Railway 上でサーバーサイドに nostrdb を置き、全ユーザーが同一インスタンスを共有していた。これは **設計上の誤り**。

### プラットフォーム別の正しいアーキテクチャ

| プラットフォーム | 正しいアーキテクチャ |
|---|---|
| **Web (Next.js / Vercel)** | ブラウザが `nostr-tools` で各リレーに直接 WebSocket 接続 |
| **Native iOS/Android** | デバイス内 nostrdb + `nurunuru-ffi` (UniFFI) |
| **Desktop (Tauri 等)** | ローカル nostrdb + `nurunuru-napi` |

### Web 版の正しいスタック（現在の方針）

```
ブラウザ
  ├─ nostr-tools (SimplePool, WebSocket)  ← リレーと直接通信
  ├─ NIP-07 / Amber / NIP-46              ← 署名（秘密鍵はブラウザ外に出ない）
  └─ localStorage / LRU キャッシュ        ← UI キャッシュ

サーバー (Next.js / Vercel)
  └─ 署名・リレー通信は一切しない
     （静的ページ配信 + 最小限の API のみ）
```

### `nostr-tools` は削除しない（Step 9 はキャンセル）

`nostr-tools` + WebSocket 直接接続は Web クライアントとして **正しい設計**。
以前の Step 9（nostr-tools 削除）は誤った方向性だったためキャンセル。

### Rust コア (`nurunuru-core`) の位置づけ

- **捨てない** — ネイティブアプリ向けの実装として価値がある
- **Web サーバーからは使わない** — Railway デプロイは廃止
- **行き先は nurunuru-ffi (Step 10)** — iOS/Android ネイティブアプリ

---

## リポジトリ構造

```
null--nostr/
├── app/                    # Next.js App Router ページ
│   └── api/
│       ├── feed/           # フィード API (⚠️ Rust 版・Web では非推奨)
│       ├── ingest/         # イベント蓄積 API (⚠️ Rust 版・Web では非推奨)
│       ├── profile/
│       │   ├── [pubkey]/   # プロフィール取得 API (⚠️ Rust 版・Web では非推奨)
│       │   └── batch/      # バッチプロフィール取得 API (⚠️ Rust 版・Web では非推奨)
│       ├── nip05/          # NIP-05 検証 API
│       ├── publish/        # イベント発行 API (⚠️ Rust 版・Web では非推奨)
│       ├── relay/          # リレー管理 API (⚠️ Rust 版・Web では非推奨)
│       │   └── reconnect/  # 強制再接続 API
│       ├── social/
│       │   ├── follows/    # フォローリスト取得・更新 API (⚠️ Rust 版・Web では非推奨)
│       │   └── mutes/      # ミュートリスト取得・更新 API (⚠️ Rust 版・Web では非推奨)
│       ├── dm/             # DM 取得・発行 API (⚠️ Rust 版・Web では非推奨)
│       ├── search/         # NIP-50 検索 API (⚠️ Rust 版・Web では非推奨)
│       ├── stream/         # SSE リアルタイム配信 API (⚠️ Rust 版・Web では非推奨)
│       └── rust-status/    # Rust エンジン状態確認 API
├── components/             # React コンポーネント
├── lib/                    # JS ビジネスロジック
│   ├── nostr.js            # イベント署名・発行・購読 ← 正規実装・維持
│   ├── cache.js            # localStorage + LRU キャッシュ ← 維持
│   ├── recommendation.js   # フィードランキング (X風アルゴリズム) ← 維持
│   ├── filters.js          # Nostr Filter ファクトリ ← 維持
│   ├── connection-manager.js # リレー接続管理 ← 正規実装・維持
│   ├── rust-bridge.js      # Rust ↔ JS ブリッジ (⚠️ Web では不要になる予定)
│   ├── rust-engine-manager.js # エンジンシングルトン管理 (⚠️ Web では不要になる予定)
│   └── nostr-sse.js        # SSE クライアント (⚠️ Rust SSE 廃止後は不要)
├── instrumentation.js      # サーバー起動時エンジンロード (⚠️ 廃止予定)
├── next.config.js          # Next.js 設定
└── rust-engine/            # Rust コアエンジン（ネイティブアプリ向け）
    ├── Cargo.toml          # Workspace
    ├── nurunuru-core/      # コアライブラリ（実装済み）← ネイティブアプリで使う
    ├── nurunuru-ffi/       # UniFFI バインディング（スキャフォルド済み）← Step 10
    └── nurunuru-napi/      # napi-rs ブリッジ（デスクトップ/Tauri 向け）
        ├── Cargo.toml
        ├── build.rs
        ├── package.json
        └── src/lib.rs
```

---

## 現在の実装状況

### Rust コア実装 ✅

- `rust-engine/nurunuru-core` の実装（全 13 テスト pass）
  - `engine.rs` — `NuruNuruEngine` (nostr-sdk Client + nostrdb バックエンド)
  - `recommendation.rs` — フィードスコアリング
  - `filters.rs` — Filter ファクトリ
  - `relay.rs` — リレーURL検証 + ジオハッシュ近接選択
  - `config.rs` — 全設定値
  - `error.rs` — 日本語エラーメッセージ
- `rust-engine/nurunuru-ffi` スキャフォルド (UniFFI proc-macro)
- `rust-engine/nurunuru-napi/` 実装・ビルド完了

### Web 版 (nostr-tools ベース) の実装 ✅

- `lib/nostr.js` — イベント署名・発行・購読（NIP-07/Amber/NIP-46 対応）
- `lib/connection-manager.js` — WebSocket 接続管理（レート制限・重複排除）
- `lib/recommendation.js` — フィードスコアリング (JS 実装)
- `lib/filters.js` — Nostr Filter ファクトリ (JS 実装)
- `lib/cache.js` — localStorage + LRU キャッシュ

### サーバーサイド Rust API（実装済み・将来廃止予定）

Step 2〜8 で実装したが、nostrdb の共有利用という設計上の問題があるため、
Web 版では段階的に JS/nostr-tools ベースに差し戻す。

| Step | 内容 | 状態 |
|---|---|---|
| Step 2 | フィード API (`/api/feed`) | ⚠️ 廃止予定 |
| Step 2.5 | nostrdb 書き込み (`/api/ingest`) | ⚠️ 廃止予定 |
| Step 3 | プロフィールキャッシュ (`/api/profile`) | ⚠️ 廃止予定 |
| Step 4 | リレー管理 (`/api/relay`) | ⚠️ 廃止予定 |
| Step 5 | イベント発行 (`/api/publish`) | ⚠️ 廃止予定 |
| Step 6 | フォロー/ミュート管理 (`/api/social`) | ⚠️ 廃止予定 |
| Step 7 | DM・検索 (`/api/dm`, `/api/search`) | ⚠️ 廃止予定 |
| Step 8 | SSE プロキシ (`/api/stream`) | ⚠️ 廃止予定 |

---

## ビルド手順

```bash
# 初回セットアップ
npm install

# 開発 (Web 版・nostr-tools ベース)
npm run dev

# Rust ビルド（ネイティブアプリ開発時のみ）
npm run build:rust   # Rust ツールチェーン必須（rustup で導入）
```

`build:rust` の中身：`cd rust-engine/nurunuru-napi && npx napi build --release`

---

## 重要な設計方針

- **nostr-tools + WebSocket は Web 版の正規実装**: 削除しない。リレーとの直接通信が正しい。
- **nostrdb はサーバーで共有しない**: 個人デバイス内でのみ使う（ネイティブアプリ向け）
- **署名はブラウザ責務**: 秘密鍵は NIP-07/Amber/NIP-46 が管理。サーバーに渡さない。
- **Rust コアは捨てない**: `nurunuru-core` の実装は nurunuru-ffi (Step 10) で活かす
- **Railway デプロイは廃止**: Rust エンジンをサーバーで動かす構成はやめる

---

## デフォルトリレー（日本）

```
wss://yabu.me              (メイン)
wss://relay-jp.nostr.wirednet.jp
wss://r.kojira.io
wss://relay.damus.io       (フォールバック)
wss://search.nos.today     (NIP-50 検索専用)
```

---

## ブランチ運用

- 作業ブランチ: `claude/nostr-relay-websocket-PIB7K`
- マージ先: `master`

---

## 今後のロードマップ

### Web 版: Rust API の差し戻し 🔲

Step 2〜8 で追加したサーバーサイド Rust API を JS/nostr-tools ベースに戻す。
各コンポーネントは `source: 'fallback'` 時に既存 JS 実装を使うフォールバックが
すでに実装されているため、Rust API を無効化するだけでよい。

優先順位：
1. `components/TimelineTab.js` — `/api/feed`, `/api/ingest` への呼び出しを削除
2. `hooks/useProfile.js` — `/api/profile` への呼び出しを削除
3. `lib/nostr.js` — `/api/publish`, `/api/social`, `/api/dm`, `/api/search` への呼び出しを削除
4. `instrumentation.js` / `lib/rust-bridge.js` / `lib/rust-engine-manager.js` を無効化
5. 上記 Rust API ルートを削除

### Step 10: nurunuru-ffi 完成 (ネイティブアプリ対応) 🔲

**目標**: iOS / Android 向け UniFFI バインディングを完成させる。
これが Rust コア (`nurunuru-core`) の本来の行き先。

```
nurunuru-ffi/
  ├─ src/lib.rs       — #[uniffi::export] ラッパー
  ├─ nurunuru.udl     — UniFFI 定義ファイル
  └─ bindgen/         — Swift / Kotlin バインディング生成
```

実装予定：
- `nurunuru-ffi/src/lib.rs` — uniffi::export ラッパー
- `nurunuru-ffi/nurunuru.udl` — 型・メソッド定義
- iOS: Swift Package として配布
- Android: AAR / Kotlin bindings として配布
- 前提: `nurunuru-core` の API は変更不要

---

## アーキテクチャ目標図（Web 版の完成形）

```
ブラウザ
  ├─ 署名: nostr.js (NIP-07 / Amber / NIP-46)
  ├─ WebSocket → リレー群 (nostr-tools SimplePool)
  │     ├─ wss://yabu.me
  │     ├─ wss://relay-jp.nostr.wirednet.jp
  │     ├─ wss://r.kojira.io
  │     └─ wss://search.nos.today (NIP-50)
  └─ localStorage / LRU キャッシュ (lib/cache.js)

サーバー (Next.js / Vercel)
  └─ 静的ページ配信のみ（Rust エンジン不使用）

ネイティブアプリ (将来 Step 10)
  ├─ iOS: Swift → nurunuru-ffi (UniFFI) → nurunuru-core
  └─ Android: Kotlin → nurunuru-ffi (UniFFI) → nurunuru-core
                                                    ↓
                                              デバイス内 nostrdb（個人用）
```
