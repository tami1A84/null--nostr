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
| FFI ブリッジ (Mobile) | UniFFI v0.29 | **実装完了 (Step 10)** |

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
│       └── nip05/          # NIP-05 検証 API（唯一残存する API ルート）
├── components/             # React コンポーネント
├── lib/                    # JS ビジネスロジック
│   ├── nostr.js            # イベント署名・発行・購読 ← 正規実装・維持
│   ├── cache.js            # localStorage + LRU キャッシュ ← 維持
│   ├── recommendation.js   # フィードランキング (X風アルゴリズム) ← 維持
│   ├── filters.js          # Nostr Filter ファクトリ ← 維持
│   ├── connection-manager.js # リレー接続管理 ← 正規実装・維持
│   ├── rust-bridge.js      # 無効化済み (null を返すスタブ)
│   └── rust-engine-manager.js # 無効化済み (null/false を返すスタブ)
├── hooks/
│   ├── useProfile.js       # プロフィール取得フック（直接 JS fetch）
│   └── useNostrSubscription.js # WebSocket 購読フック（SSE 削除済み）
├── instrumentation.js      # 無効化済み (no-op)
├── next.config.js          # Next.js 設定
└── rust-engine/            # Rust コアエンジン（ネイティブアプリ向け）
    ├── Cargo.toml          # Workspace
    ├── nurunuru-core/      # コアライブラリ（実装済み）← ネイティブアプリで使う
    ├── nurunuru-ffi/       # UniFFI バインディング（完成）← Step 10 ✅
    │   ├── src/lib.rs      # proc-macro FFI ラッパー
    │   ├── src/nurunuru.udl# インターフェース定義（ドキュメント）
    │   ├── src/bin/uniffi-bindgen.rs  # バインディング生成バイナリ
    │   ├── bindgen/        # gen_swift.sh / gen_kotlin.sh / Makefile
    │   ├── ios/            # Package.swift + Swift async 拡張
    │   └── android/        # build.gradle.kts + Kotlin Coroutine 拡張
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
- `rust-engine/nurunuru-ffi` 実装完了 (UniFFI proc-macro, Step 10) ✅
  - `src/lib.rs` — `#[uniffi::export]` / `uniffi::setup_scaffolding!()` ラッパー完成
  - `src/nurunuru.udl` — 完全なインターフェース定義（ドキュメント）
  - `src/bin/uniffi-bindgen.rs` — Swift/Kotlin バインディング生成バイナリ
  - `bindgen/gen_swift.sh` + `gen_kotlin.sh` + `Makefile` — バインディング生成スクリプト
  - `ios/Package.swift` — iOS Swift Package 設定
  - `ios/Sources/NuruNuru/NuruNuruClient+Extensions.swift` — async/await 拡張
  - `android/build.gradle.kts` — Android ライブラリモジュール設定
  - `android/src/main/kotlin/io/nurunuru/NuruNuruBridge.kt` — Coroutine 拡張
- `rust-engine/nurunuru-napi/` 実装・ビルド完了

### Web 版 (nostr-tools ベース) の実装 ✅

- `lib/nostr.js` — イベント署名・発行・購読（NIP-07/Amber/NIP-46 対応）
- `lib/connection-manager.js` — WebSocket 接続管理（レート制限・重複排除）
- `lib/recommendation.js` — フィードスコアリング (JS 実装)
- `lib/filters.js` — Nostr Filter ファクトリ (JS 実装)
- `lib/cache.js` — localStorage + LRU キャッシュ

### サーバーサイド Rust API（削除済み）✅

Step 2〜8 で実装したサーバーサイド Rust API は、nostrdb 共有利用の設計上の問題があったため削除した。

| Step | 内容 | 状態 |
|---|---|---|
| Step 2 | フィード API (`/api/feed`) | ✅ 削除済み |
| Step 2.5 | nostrdb 書き込み (`/api/ingest`) | ✅ 削除済み |
| Step 3 | プロフィールキャッシュ (`/api/profile`) | ✅ 削除済み |
| Step 4 | リレー管理 (`/api/relay`) | ✅ 削除済み |
| Step 5 | イベント発行 (`/api/publish`) | ✅ 削除済み |
| Step 6 | フォロー/ミュート管理 (`/api/social`) | ✅ 削除済み |
| Step 7 | DM・検索 (`/api/dm`, `/api/search`) | ✅ 削除済み |
| Step 8 | SSE プロキシ (`/api/stream`) | ✅ 削除済み |

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

- 作業ブランチ: `claude/complete-nurunuru-ffi-hIhra`
- マージ先: `master`

---

## 今後のロードマップ

### Web 版: Rust API の差し戻し ✅

完了済み（2026-02-19）。

実施内容：
1. `components/TimelineTab.js` — `/api/feed`, `/api/ingest` 呼び出しを削除。JS/nostr-tools 直接フェッチのみ使用
2. `hooks/useProfile.js` — `/api/profile`, `/api/profile/batch` 呼び出しを削除。`fetchProfileCached` / `fetchProfilesBatch` を直接呼び出すよう変更
3. `lib/nostr.js` — `/api/publish`, `/api/social/mutes`, `/api/social/follows` 呼び出しを削除。connection-manager 経由の直接発行・relay fetch のみ使用
4. `instrumentation.js` — no-op に変更
5. `lib/rust-bridge.js` — null を返すスタブに変更
6. `lib/rust-engine-manager.js` — null/false を返すスタブに変更
7. `hooks/useNostrSubscription.js` — SSE トランスポートを削除し WebSocket のみ使用
8. `lib/nostr-sse.js` — 削除（`/api/stream` 廃止のため不要）
9. 廃止 API ルート削除: `feed`, `ingest`, `profile`, `publish`, `relay`, `social`, `dm`, `search`, `stream`, `rust-status`

### Step 10: nurunuru-ffi 完成 (ネイティブアプリ対応) ✅

**完了（2026-02-19）**

iOS / Android 向け UniFFI バインディングを完成させた。

```
nurunuru-ffi/
  ├─ src/lib.rs              — #[uniffi::export] ラッパー（proc-macro 方式）
  ├─ src/nurunuru.udl        — インターフェース定義（ドキュメント兼）
  ├─ src/bin/uniffi-bindgen.rs — uniffi::uniffi_bindgen_main() バイナリ
  ├─ bindgen/
  │   ├─ gen_swift.sh        — Swift バインディング生成スクリプト
  │   ├─ gen_kotlin.sh       — Kotlin バインディング生成スクリプト
  │   └─ Makefile            — ios-device / ios-sim / xcframework / android-all 等
  ├─ ios/
  │   ├─ Package.swift       — Swift Package (iOS 16+ / macOS 13+)
  │   └─ Sources/NuruNuru/NuruNuruClient+Extensions.swift — async/await 拡張
  └─ android/
      ├─ build.gradle.kts    — Android ライブラリモジュール
      └─ src/main/kotlin/io/nurunuru/NuruNuruBridge.kt — Coroutine 拡張

```

バインディング生成手順：
```bash
# iOS (macOS ホスト必須)
cd rust-engine/nurunuru-ffi/bindgen
make xcframework          # XCFramework → ios/ に出力

# Android
make android-all          # arm64-v8a + x86_64 .so → android/libs/ に出力
make kotlin               # Kotlin バインディング → bindgen/kotlin-out/ に出力
```

使い方（Swift）:
```swift
import NuruNuru
let client = try NuruNuruClient(secretKeyHex: nsec, dbPath: NuruNuruClient.defaultDbPath())
client.connect()
try await client.loginAsync(pubkeyHex: npub)
let feed = try await client.getRecommendedFeedAsync(limit: 50)
```

使い方（Kotlin/Android）:
```kotlin
val client = NuruNuruBridge.create(context, nsecKey)
client.connectAsync()
client.loginAsync(npubHex)
val feed = client.getRecommendedFeedAsync(50u)
```

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

ネイティブアプリ (Step 10 完了)
  ├─ iOS: Swift → nurunuru-ffi (UniFFI) → nurunuru-core
  └─ Android: Kotlin → nurunuru-ffi (UniFFI) → nurunuru-core
                                                    ↓
                                              デバイス内 nostrdb（個人用）
```
