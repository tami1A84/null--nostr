# null--nostr (ぬるぬる) — CLAUDE.md

> **AIへの指示書**。このファイルはセッション開始時に必ず読むこと。

> **重要**: 作業完了後は必ずこの CLAUDE.md を更新すること。
> 完了した Step を ✅ に変更し、新規実装ファイル・API エンドポイント・使い方を追記する。

---

## プロジェクト概要

**ぬるぬる**は日本語圏向けの高速 Nostr クライアント (Next.js PWA)。
現在、コアロジックを JS → Rust へ段階的に移行中。

### 技術スタック

| 層 | 技術 | 状態 |
|---|---|---|
| フロントエンド | Next.js 14 + Tailwind | 稼働中 |
| Nostr プロトコル | `nostr-tools` (JS) | 稼働中・移行対象 |
| Rust エンジン (コア) | `nostr-sdk` v0.44 + `nostrdb` v0.8 | 実装済み・接続済み |
| FFI ブリッジ | `napi-rs` v2 | **実装済み・稼働中** |

---

## リポジトリ構造

```
null--nostr/
├── app/                    # Next.js App Router ページ
│   └── api/
│       ├── feed/           # フィード API (Rust ランキング) ← Step 2
│       ├── ingest/         # イベント蓄積 API ← Step 2.5 完全稼働中
│       ├── profile/
│       │   ├── [pubkey]/   # 単一プロフィール取得 API ← Step 3
│       │   └── batch/      # バッチプロフィール取得 API ← Step 3
│       ├── nip05/          # NIP-05 検証 API
│       ├── relay/          # リレー管理 API ← Step 4
│       │   └── reconnect/  # 強制再接続 API ← Step 4
│       └── rust-status/    # Rust エンジン状態確認 API
├── components/             # React コンポーネント
├── lib/                    # JS ビジネスロジック（移行元）
│   ├── nostr.js            # イベント署名・発行・購読
│   ├── cache.js            # localStorage + LRU キャッシュ
│   ├── recommendation.js   # フィードランキング (X風アルゴリズム)
│   ├── filters.js          # Nostr Filter ファクトリ
│   ├── connection-manager.js # リレー接続管理
│   ├── rust-bridge.js      # Rust ↔ JS ブリッジ
│   └── rust-engine-manager.js # エンジンシングルトン管理 ← Step 2
├── instrumentation.js      # サーバー起動時エンジンロード
├── next.config.js          # instrumentationHook 有効化済み
└── rust-engine/            # Rust コアエンジン（移行先）
    ├── Cargo.toml          # Workspace
    ├── nurunuru-core/      # コアライブラリ（実装済み）
    ├── nurunuru-ffi/       # UniFFI バインディング（スキャフォルド済み）
    └── nurunuru-napi/      # napi-rs ブリッジ（稼働中）
        ├── Cargo.toml
        ├── build.rs
        ├── package.json
        └── src/lib.rs      # #[napi] ラッパー群
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
- **`rust-engine/nurunuru-napi/` 実装・ビルド完了**
  - `NuruNuruNapi` クラス（`#[napi]` ラッパー）
  - `nurunuru-napi.node` が生成済み（`npm run build:rust` で再ビルド可能）
- **Next.js への接続完了**
  - `instrumentation.js` — サーバー起動時に自動ロード・ログ出力
  - `lib/rust-bridge.js` — `getEngine()` 関数でサーバーサイドから取得可能
  - `app/api/rust-status/route.js` — 動作確認エンドポイント
  - `next.config.js` — `instrumentationHook: true` 設定済み

### `npm run dev` で確認できること

起動時ログ：
```
[rust-bridge] Rust engine loaded — exports: NuruNuruNapi
```

`http://localhost:3000/api/rust-status/` のレスポンス：
```json
{"rustEngine":{"available":true,"exports":["NuruNuruNapi"]},"runtime":"nodejs"}
```

### Step 2: フィード API ✅ 実装済み

アーキテクチャ：
```
ブラウザ (TimelineTab.js)
  ├─ WebSocket → リレー   (イベント受信・投稿はそのまま維持)
  │      ↓ 受信したイベントを
  └─ POST /api/ingest    → Rust → nostrdb に保存（Step 2.5 で完全稼働）

  └─ GET /api/feed       → Rust → nostrdb からランキング済みフィード返却
```

実装済みファイル：
- `lib/rust-engine-manager.js` — エンジンシングルトン管理
  - サーバーサイドキーで自動初期化（ユーザーの秘密鍵は不要）
  - `getOrCreateEngine()` / `loginUser(pubkey)` で利用
- `app/api/feed/route.js` — フィード取得 API
  - `GET /api/feed?pubkey=xxx&limit=50`
  - Rust `getRecommendedFeed` → `queryLocal` で完全イベント返却
  - エンジン未起動時は `{ posts: [], source: 'fallback' }` を返す
- `app/api/ingest/route.js` — イベント蓄積 API（完全稼働）
  - `POST /api/ingest` with `{ events: [...] }`
  - NIP-01 バリデーション + `engine.storeEvent()` で nostrdb に直接書き込み
  - エンジン未起動時は受け付けのみ（graceful degradation）
- `components/TimelineTab.js` の修正
  - `loadTimelineFull()` と `loadTimeline()` で `/api/feed` を最初に試行
  - Rust フィード成功時: ランキング済みポストを使用
  - 失敗時: 既存 JS アルゴリズムにフォールバック（変更なし）

### Step 2.5: nostrdb 直接書き込み ✅ 実装済み

**全コンポーネントが稼働中。**

実装の流れ：
```
ブラウザ (JS fetchEvents) → リレーからイベント受信
  ├─ 画面に表示（従来通り）
  └─ POST /api/ingest     ← ingestToNostrdb() (fire-and-forget)
        ↓
      engine.storeEvent(eventJson)
        ↓
      nostrdb に永続化
        ↓
      次回 /api/feed で Rust がランキングに使用
```

実装済みコンポーネント：
- `nurunuru-core/src/engine.rs` — `store_event(event: Event) -> Result<bool>`
  - `database().save_event()` で nostrdb に直接書き込み
  - 重複・置き換えイベントの場合は `false` を返す
- `nurunuru-napi/src/lib.rs` — `store_event(event_json: String) -> Result<bool>` napi ラッパー
- `app/api/ingest/route.js` — `engine.storeEvent()` 呼び出し、accepted/stored/duplicate を返却
- `components/TimelineTab.js` — `ingestToNostrdb()` ヘルパー（100件チャンク・fire-and-forget）
  - `loadTimelineQuick`: 初期表示ノートを ingest
  - `loadTimelineFull` JS fallback: ノート・リポスト・2次ネットワーク投稿・リアクションを ingest
  - `loadFollowingTimeline`: フォロー中フィードを ingest
  - `loadTimeline`（手動更新）: global/following 両モードで ingest

`POST /api/ingest` レスポンス例：
```json
{
  "accepted": 10,
  "stored": 8,
  "duplicate": 2,
  "invalid": 0,
  "total": 10,
  "engineAvailable": true
}
```

### Step 3: プロフィールキャッシュ移行 ✅ 実装済み

アーキテクチャ：
```
ブラウザ (hooks/useProfile.js)
  └─ fetchProfileViaApi(pubkey)
        ↓
      GET /api/profile/[pubkey]
        ├─ queryLocal (nostrdb) → 即時返却
        └─ engine.fetchProfile(pubkey) → リレー取得
      POST /api/profile/batch
        ├─ queryLocal (nostrdb) → 一括検索
        └─ engine.fetchProfilesJson(pubkeys) → バッチリレー取得
  エンジン未起動時: 既存 JS fetchProfileCached にフォールバック
```

実装済みファイル：
- `nurunuru-napi/src/lib.rs` — `fetch_profiles_json(pubkey_hexes)` napi バインディング追加
  - 複数 pubkey を一度のリレー購読でバッチ取得
- `app/api/profile/[pubkey]/route.js` — 単一プロフィール取得
  - `GET /api/profile/[pubkey]`
  - nostrdb → リレーの2段階戦略
  - レスポンス: `{ profile, source: 'nostrdb' | 'rust' | 'fallback' }`
- `app/api/profile/batch/route.js` — バッチプロフィール取得
  - `POST /api/profile/batch` with `{ pubkeys: string[] }` (最大200件)
  - nostrdb で一括検索 → 不足分をリレーバッチ取得
  - レスポンス: `{ profiles: { [pubkey]: UserProfile }, source: 'nostrdb' | 'rust' | 'mixed' | 'fallback' }`
- `hooks/useProfile.js` — API ルート経由に移行
  - `fetchProfileViaApi()`: `/api/profile/[pubkey]` を呼び出し
  - `fetchProfilesBatchViaApi()`: `/api/profile/batch` を呼び出し
  - `source: 'fallback'` 時は既存 JS にフォールバック（段階的移行を維持）

### Step 4: リレー接続移行 ✅ 実装済み

アーキテクチャ：
```
ブラウザ (WebSocket via connection-manager.js)   ← リアルタイム購読は JS のまま維持
  └─ GET  /api/relay            → Rust → リレー一覧 + 接続ステータス取得
  └─ POST /api/relay            → Rust → リレー追加 { url }
  └─ DELETE /api/relay          → Rust → リレー削除 { url }
  └─ POST /api/relay/reconnect  → Rust → 全リレー再接続
```

実装済みファイル：
- `nurunuru-core/src/types.rs` — `RelayInfo { url, status, connected }` 型追加
- `nurunuru-core/src/engine.rs` — リレー管理メソッド追加
  - `get_relay_list() -> Vec<RelayInfo>`
  - `add_relay(url) -> Result<()>`
  - `remove_relay(url) -> Result<()>`
  - `reconnect() -> Result<()>`
- `nurunuru-napi/src/lib.rs` — NAPI バインディング追加
  - `NapiRelayInfo` 構造体
  - `getRelayList()` / `addRelay(url)` / `removeRelay(url)` / `reconnect()`
- `app/api/relay/route.js` — リレー管理エンドポイント
  - `GET /api/relay` — リレー一覧 + 接続統計
  - `POST /api/relay` with `{ url }` — リレー追加
  - `DELETE /api/relay` with `{ url }` — リレー削除
- `app/api/relay/reconnect/route.js` — `POST /api/relay/reconnect` — 強制再接続
- `lib/rust-engine-manager.js` — リレー管理ヘルパー追加
  - `getRelayList()` / `addRelay(url)` / `removeRelay(url)` / `reconnectRelays()`

`GET /api/relay` レスポンス例：
```json
{
  "relays": [
    { "url": "wss://yabu.me", "status": "Connected", "connected": true },
    { "url": "wss://relay-jp.nostr.wirednet.jp", "status": "Connected", "connected": true },
    { "url": "wss://r.kojira.io", "status": "Connecting", "connected": false },
    { "url": "wss://relay.damus.io", "status": "Connected", "connected": true }
  ],
  "stats": { "connectedRelays": 3, "totalRelays": 4 },
  "source": "rust"
}
```

---

## ビルド手順

```bash
# 初回セットアップ
npm install
npm run build:rust   # Rust ツールチェーン必須（rustup で導入）

# 開発
npm run dev
```

`build:rust` の中身：`cd rust-engine/nurunuru-napi && npx napi build --release`

---

## 重要な設計方針

- **段階的移行**: Rust が使えない環境では既存 JS にフォールバックする
- **JS は壊さない**: `lib/` の既存コードは移行完了まで残す
- **nostrdb が正**：イベントの永続化・検索は全て nostrdb に集約する
- **napi-rs > UniFFI**: Web (Next.js) ターゲットは napi-rs を優先。
  モバイル (Android/iOS) は後で nurunuru-ffi (UniFFI) を使う
- **サーバーサイド限定**: `.node` ネイティブモジュールはサーバーサイドのみ。
  クライアント（ブラウザ）では動かない。API ルート経由で使う。
- **WebSocket はブラウザで維持**: リアルタイム購読は既存 JS のまま。
  Rust は「処理・キャッシュ・ランキング」に専念させる。

## エンジンの使い方（API ルート内）

### 低レベル: `rust-bridge.js` (モジュールロード)

```js
import { getEngine } from '@/lib/rust-bridge'
const mod = getEngine() // { NuruNuruNapi } or null
```

### 推奨: `rust-engine-manager.js` (シングルトン管理)

```js
// app/api/feed/route.js で実際に使用中
import { getOrCreateEngine, loginUser } from '@/lib/rust-engine-manager'

export async function GET(req) {
  const pubkey = new URL(req.url).searchParams.get('pubkey')
  const engine = await loginUser(pubkey) // 自動初期化 + リレー接続 + ログイン
  if (!engine) {
    return Response.json({ posts: [], source: 'fallback' })
  }
  const scored = await engine.getRecommendedFeed(50)
  // queryLocal でフルイベント取得
  const filter = JSON.stringify({ ids: scored.map(s => s.eventId) })
  const events = (await engine.queryLocal(filter)).map(j => JSON.parse(j))
  return Response.json({ posts: events, source: 'rust' })
}
```

### ingest API (Step 2.5〜)

```js
// app/api/ingest/route.js で実際に使用中
import { getOrCreateEngine } from '@/lib/rust-engine-manager'

// engine.storeEvent(eventJson) → nostrdb に直接書き込み
const isNew = await engine.storeEvent(JSON.stringify(event))
```

### profile API (Step 3〜)

```js
// app/api/profile/[pubkey]/route.js で実際に使用中
import { getOrCreateEngine } from '@/lib/rust-engine-manager'

// 単一プロフィール: nostrdb → リレーの順に検索
const localJson = await engine.queryLocal(JSON.stringify({ kinds: [0], authors: [pubkey] }))
// または
const napiProfile = await engine.fetchProfile(pubkey)

// バッチプロフィール: app/api/profile/batch/route.js
const profilesJson = await engine.fetchProfilesJson(pubkeys) // JSON string
```

### relay API (Step 4〜)

```js
// app/api/relay/route.js で実際に使用中
import { getOrCreateEngine } from '@/lib/rust-engine-manager'

// リレー一覧取得
const relays = await engine.getRelayList()
// → [{ url, status, connected }, ...]

// 接続統計
const stats = await engine.connectionStats()
// → { connectedRelays, totalRelays }

// リレー追加・削除・再接続
await engine.addRelay('wss://relay.example.com')
await engine.removeRelay('wss://relay.example.com')
await engine.reconnect()

// rust-engine-manager.js ヘルパー経由でも使用可能
import { getRelayList, addRelay, removeRelay, reconnectRelays } from '@/lib/rust-engine-manager'
```

## デフォルトリレー（日本）

```
wss://yabu.me              (メイン)
wss://relay-jp.nostr.wirednet.jp
wss://r.kojira.io
wss://relay.damus.io       (フォールバック)
wss://search.nos.today     (NIP-50 検索専用)
```

## ブランチ運用

- 作業ブランチ: `claude/complete-relay-migration-LTFSb`
- マージ先: `master`
