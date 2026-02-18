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
│       ├── publish/        # イベント発行 API ← Step 5
│       ├── relay/          # リレー管理 API ← Step 4
│       │   └── reconnect/  # 強制再接続 API ← Step 4
│       ├── social/
│       │   ├── follows/    # フォローリスト取得・更新 API ← Step 6
│       │   └── mutes/      # ミュートリスト取得・更新 API ← Step 6
│       ├── dm/             # DM 取得・発行 API ← Step 7
│       ├── search/         # NIP-50 検索 API ← Step 7
│       ├── stream/         # SSE リアルタイム配信 API ← Step 8
│       └── rust-status/    # Rust エンジン状態確認 API
├── components/             # React コンポーネント
├── lib/                    # JS ビジネスロジック（移行元）
│   ├── nostr.js            # イベント署名・発行・購読
│   ├── cache.js            # localStorage + LRU キャッシュ
│   ├── recommendation.js   # フィードランキング (X風アルゴリズム)
│   ├── filters.js          # Nostr Filter ファクトリ
│   ├── connection-manager.js # リレー接続管理
│   ├── rust-bridge.js      # Rust ↔ JS ブリッジ
│   ├── rust-engine-manager.js # エンジンシングルトン管理 ← Step 2
│   └── nostr-sse.js        # SSE クライアント ← Step 8
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

### publish API (Step 5〜)

```js
// app/api/publish/route.js で実際に使用中
import { getOrCreateEngine } from '@/lib/rust-engine-manager'

// 署名済みイベントを全リレーにブロードキャスト
// engine.publishEvent(eventJson) → nostr-sdk が署名検証 → relay pool に送出
const eventId = await engine.publishEvent(JSON.stringify(signedEvent))
// → イベント ID の hex 文字列

// lib/nostr.js の publishEvent() から自動呼び出し (透過的)
// ブラウザ側コードの変更は不要 — Rust broadcast が優先され、失敗時は JS fallback
```

### social API (Step 6〜)

```js
// app/api/social/follows/route.js, app/api/social/mutes/route.js で実際に使用中
import { getOrCreateEngine } from '@/lib/rust-engine-manager'

// フォローリスト取得: nostrdb → リレーの2段階
const localFilter = JSON.stringify({ kinds: [3], authors: [pubkey], limit: 1 })
const localEvents = await engine.queryLocal(localFilter)
// localEvents[0] から p タグをパース → follows: string[]
// 未キャッシュ時:
const follows = await engine.fetchFollowList(pubkey)
// → string[] (followed pubkey hex 一覧)

// ミュートリスト取得: nostrdb → リレーの2段階
const muteFilter = JSON.stringify({ kinds: [10000], authors: [pubkey], limit: 1 })
const muteEvents = await engine.queryLocal(muteFilter)
// muteEvents[0] から p/e/t/word タグをパース → { pubkeys, eventIds, hashtags, words }
// 未キャッシュ時:
const mutedPubkeys = await engine.fetchMuteList(pubkey)
// → string[] (muted pubkey hex 一覧、pubkeys のみ)

// rust-engine-manager.js ヘルパー経由でも使用可能
import { getFollowList, getMuteList } from '@/lib/rust-engine-manager'
const follows = await getFollowList(pubkey)   // string[] | null
const mutes  = await getMuteList(pubkey)     // string[] | null (pubkeys のみ)

// フォロー/ミュートリスト更新: ブラウザで署名 → /api/social/* POST → /api/publish 委譲
// POST /api/social/follows { event: signedKind3Event }
// POST /api/social/mutes   { event: signedKind10000Event }
// → /api/publish に委譲し全リレーに broadcast
```

### DM API (Step 7〜)

```js
// app/api/dm/route.js で実際に使用中
import { getOrCreateEngine, loginUser } from '@/lib/rust-engine-manager'

// DM (gift wrap) 取得: nostrdb → リレーの2段階
// ⚠️ 返却されるのは暗号化された gift wrap (kind 1059) のまま
// 復号 (seal→rumor) はブラウザで decryptNip44() を使って実施
const localFilter = JSON.stringify({ kinds: [1059], '#p': [pubkey], limit: 50 })
const localEvents = await engine.queryLocal(localFilter)
// 未キャッシュ時 (loginUser で pubkey コンテキストを設定してから fetch):
await engine.login(pubkey)
const dmJsons = await engine.fetchDms(since, limit) // → string[] (event JSON)

// rust-engine-manager.js ヘルパー経由でも使用可能
import { fetchDms, searchEvents } from '@/lib/rust-engine-manager'
const giftWraps = await fetchDms(pubkey, since, 50)  // NostrEvent[] | null

// DM gift wrap 発行: ブラウザで署名 → POST /api/dm → /api/publish 委譲
// POST /api/dm { event: signedKind1059GiftWrap }
// → /api/publish に委譲し全リレーに broadcast
```

### search API (Step 7〜)

```js
// app/api/search/route.js で実際に使用中
import { getOrCreateEngine } from '@/lib/rust-engine-manager'

// NIP-50 全文検索 (search.nos.today 経由)
const eventJsons = await engine.search(query, limit) // → string[] (event JSON)
// 結果は自動的に nostrdb にキャッシュ保存される

// rust-engine-manager.js ヘルパー経由でも使用可能
import { searchEvents } from '@/lib/rust-engine-manager'
const results = await searchEvents('日本語クエリ', 50) // NostrEvent[] | null
```

### stream API (Step 8〜)

```js
// app/api/stream/route.js で実際に使用中
// ブラウザ側は lib/nostr-sse.js / hooks/useNostrSubscription.js を使用

// ─── サーバーサイド (API Route 内) ───
import { getOrCreateEngine } from '@/lib/rust-engine-manager'

// 購読開始: REQ を全リレーに送信、sub_id を返す
const subId = await engine.subscribeStream(filterJson) // filterJson = JSON.stringify(filter)

// イベントのポーリング (50ms ごとに呼び出す)
const events = await engine.pollSubscription(subId, 50) // → string[] (event JSON)

// 購読終了: CLOSE 送信 + バッファ削除
await engine.unsubscribeStream(subId)

// ─── ブラウザサイド (React コンポーネント内) ───
import { subscribeSSE } from '@/lib/nostr-sse'

// subscribeManaged() と同一インターフェースで置き換え可能
const sub = subscribeSSE({ kinds: [1], limit: 50 }, {
  onEvent: (event) => { /* ... */ },
  onEose: () => { /* ... */ },
  onError: (err) => { /* ... */ },
  autoReconnect: true,
})
// sub.close() で購読終了

// ─── React Hook ───
import { useNostrSubscription } from '@/hooks/useNostrSubscription'

// transport: 'auto' (デフォルト) — Rust エンジン稼働時は SSE、未稼働時は WebSocket
const { isConnected, eventCount, activeTransport } = useNostrSubscription(filter, {
  transport: 'auto',  // 'auto' | 'sse' | 'websocket'
  onEvent: (event) => { /* ... */ },
})
// activeTransport → 'sse' | 'websocket' | null
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

- 作業ブランチ: `claude/rust-sse-proxy-streaming-opdfQ`
- マージ先: `master`

---

## 現状の正直な評価と残り課題

### 何が達成されたか

Step 1〜7 で「Rust エンジンのキャッシュ・ランキング・リレー管理・ソーシャルリスト管理・DM取得・NIP-50検索層」が完成した。
ただし「JS からの完全移行」ではなく **「Rust が最適化レイヤーとして追加された」** が正確な表現。

| 機能 | 現状 |
|---|---|
| フィードランキング | ✅ Rust (nostrdb + recommendation.rs) |
| イベント永続化 | ✅ Rust (nostrdb 直接書き込み) |
| プロフィールキャッシュ | ✅ Rust (nostrdb → リレーの2段階) |
| リレー管理 | ✅ Rust (add/remove/reconnect) |
| イベント発行 | ✅ Rust (/api/publish → engine.publishEvent) |
| リアルタイム購読 | ✅ Rust (/api/stream SSE → engine.subscribeStream) |
| フォロー/ミュートリスト取得 | ✅ Rust (/api/social/follows, /api/social/mutes) |
| フォロー/ミュートリスト編集 | ✅ Rust (/api/social/* → /api/publish 委譲) |
| DM 取得 (kind 1059) | ✅ Rust (/api/dm → engine.fetchDms / nostrdb) |
| DM 暗号化・復号 | ❌ JS (NIP-44 — **移行不可**・ブラウザ責務) |
| DM 送信 | ✅ Rust (/api/dm POST → /api/publish 委譲) |
| 検索 (NIP-50) | ✅ Rust (/api/search → engine.search) |
| 画像アップロード | ❌ JS (外部 API — 移行不要) |
| イベント署名 | ❌ JS (NIP-07/Amber/NIP-46 — **移行不可**・ブラウザ責務) |

### 移行できない機能（設計上）

**イベント署名は永久にブラウザ責務**。
秘密鍵は NIP-07 拡張 (Alby, nos2x) や Amber が保持するため、
サーバーサイドの Rust エンジンが署名することは**セキュリティ上不可能かつ不適切**。

→ 「署名はブラウザ、発行は Rust エンジン経由」が正しいアーキテクチャ。

---

## 次フェーズのロードマップ

### Step 5: イベント発行の API 化 ✅ 実装済み

アーキテクチャ：
```
ブラウザ (NIP-07 / Amber / NIP-46)
  └─ signEvent(event) → signedEvent
        ↓
  POST /api/publish { event: signedEvent }
        ↓
  engine.publishEvent(eventJson) → client.send_event(&event)
        ↓
  接続中の全リレーに broadcast
        ↓
  { id, relays: ['wss://...'], source: 'rust' }
```

実装済みファイル：
- `nurunuru-core/src/engine.rs` — `publish_raw_event(event: Event) -> Result<EventId>`
  - `client.send_event(&event)` — 署名済みイベントをそのまま送出
  - nostr-sdk が署名を自動検証してから broadcast
- `nurunuru-napi/src/lib.rs` — `publishEvent(eventJson: String) -> Result<String>`
  - `Event::from_json()` でデシリアライズ → `publish_raw_event()` 呼び出し
  - 成功時: イベント ID の hex 文字列を返す
- `app/api/publish/route.js` — `POST /api/publish { event }` エンドポイント
  - NIP-01 フィールドバリデーション (id/pubkey/sig の形式チェック)
  - Rust 側でも署名検証 → 全リレーに broadcast
  - レスポンス: `{ id, relays: ['wss://...'], source: 'rust' }`
  - エンジン未起動時: `503 { error, source: 'unavailable' }`
- `lib/nostr.js` の `publishEvent()` を修正
  - まず `/api/publish` を試行 (Rust broadcast)
  - 失敗時: 既存 `publishManaged()` JS フォールバック維持

`POST /api/publish` レスポンス例：
```json
{
  "id": "a1b2c3...64hex...",
  "relays": ["wss://yabu.me", "wss://relay-jp.nostr.wirednet.jp"],
  "source": "rust"
}
```

### Step 6: フォロー/ミュートリスト管理の API 化 ✅ 実装済み

アーキテクチャ：
```
ブラウザ (lib/nostr.js fetchFollowListCached / fetchMuteListCached)
  └─ GET  /api/social/follows?pubkey=xxx
        ├─ queryLocal (nostrdb) → 即時返却
        └─ engine.fetchFollowList(pubkey) → リレー取得
  └─ GET  /api/social/mutes?pubkey=xxx
        ├─ queryLocal (nostrdb) → 全タグ型で即時返却
        └─ engine.fetchMuteList(pubkey) → リレー取得（pubkey のみ）
  └─ POST /api/social/follows { event: signedKind3Event }
        └─ /api/publish に委譲 → Rust が全リレーに broadcast
  └─ POST /api/social/mutes { event: signedKind10000Event }
        └─ /api/publish に委譲 → Rust が全リレーに broadcast
```

実装済みファイル：
- `nurunuru-napi/src/lib.rs` — `fetchMuteList(pubkeyHex)` NAPI バインディング追加
  - `engine.fetch_mute_list(pk)` を呼び出し、`Vec<String>` (muted pubkeys) を返す
- `app/api/social/follows/route.js` — フォローリスト取得・更新
  - `GET /api/social/follows?pubkey=xxx` — nostrdb → リレーの2段階取得
  - `POST /api/social/follows { event }` — kind 3 イベントを `/api/publish` に委譲
  - レスポンス: `{ follows: string[], source: 'nostrdb' | 'rust' | 'fallback' }`
- `app/api/social/mutes/route.js` — ミュートリスト取得・更新
  - `GET /api/social/mutes?pubkey=xxx` — nostrdb → リレーの2段階取得
  - `POST /api/social/mutes { event }` — kind 10000 イベントを `/api/publish` に委譲
  - レスポンス: `{ mutes: { pubkeys, eventIds, hashtags, words }, source: '...' }`
- `lib/rust-engine-manager.js` — ソーシャルリストヘルパー追加
  - `getFollowList(pubkey)` / `getMuteList(pubkey)`
- `lib/nostr.js` の `fetchFollowListCached()` / `fetchMuteListCached()` を API 経由に
  - `/api/social/follows` / `/api/social/mutes` を最初に試行
  - `source: 'fallback'` 時は既存 JS 実装にフォールバック

`GET /api/social/follows` レスポンス例：
```json
{
  "follows": ["abc123...", "def456..."],
  "source": "nostrdb"
}
```

`GET /api/social/mutes` レスポンス例：
```json
{
  "mutes": {
    "pubkeys": ["abc123..."],
    "eventIds": [],
    "hashtags": ["spam"],
    "words": ["NG ワード"]
  },
  "source": "nostrdb"
}
```

### Step 7: DM 取得・検索の API 化 ✅ 実装済み

アーキテクチャ：
```
ブラウザ (components/TalkTab.js)
  └─ GET  /api/dm?pubkey=xxx&limit=50   → nostrdb (kind 1059) → relay fetch
        ↓ 返却: 生の gift wrap イベント群
        ↓ (decryptNip44 による復号はブラウザで実施 — 秘密鍵はサーバーに渡さない)

ブラウザ (components/SearchModal.js)
  └─ GET  /api/search?q=xxx&limit=50    → engine.search() → NIP-50 リレー
        ↓ 返却: マッチするイベント群 (nostrdb にも自動保存)

ブラウザ → POST /api/dm { event: kind1059 }
  └─ /api/publish に委譲 → Rust が全リレーに broadcast
```

実装済みファイル：
- `app/api/dm/route.js` — DM 取得・発行
  - `GET /api/dm?pubkey=xxx&since=xxx&limit=50` — nostrdb → relay の2段階取得
  - `POST /api/dm { event: signedKind1059 }` — gift wrap を `/api/publish` に委譲
  - レスポンス: `{ events: NostrEvent[], source: 'nostrdb' | 'rust' | 'fallback' }`
- `app/api/search/route.js` — NIP-50 全文検索
  - `GET /api/search?q=xxx&limit=50` — engine.search() → search.nos.today
  - 返却イベントは nostrdb にバックグラウンド保存
  - レスポンス: `{ results: NostrEvent[], source: 'rust' | 'fallback' }`
- `lib/rust-engine-manager.js` — DM/検索ヘルパー追加
  - `fetchDms(pubkey, since, limit)` — nostrdb → relay の2段階
  - `searchEvents(query, limit)` — NIP-50 検索
- `components/TalkTab.js` — DM 取得を API 経由に
  - `loadConversations()`: `/api/dm?pubkey=xxx&limit=50` を最初に試行
  - `openChat()`: `/api/dm?pubkey=xxx&limit=200` を最初に試行
  - 失敗時: 既存 `fetchEvents(kind 1059)` JS フォールバック維持
- `components/SearchModal.js` — 検索を API 経由に
  - `handleSearch()` テキスト検索: `/api/search?q=xxx` を最初に試行
  - 失敗時: 既存 `searchNotes()` JS フォールバック維持

**重要な設計方針**: DM の復号 (seal + rumor 2段階 NIP-44) はブラウザ責務のまま。
サーバーは暗号化された gift wrap (kind 1059) を返すだけ。秘密鍵はサーバーに渡さない。

`GET /api/dm` レスポンス例：
```json
{
  "events": [
    { "id": "abc...", "kind": 1059, "pubkey": "...", "content": "...(encrypted)...", ... }
  ],
  "source": "nostrdb"
}
```

`GET /api/search` レスポンス例：
```json
{
  "results": [
    { "id": "abc...", "kind": 1, "content": "検索ヒットしたノート", ... }
  ],
  "source": "rust"
}
```

### Step 8: リアルタイム配信の Rust SSE プロキシ化 ✅ 実装済み

アーキテクチャ：
```
現在:
  ブラウザ ──WebSocket──→ リレー (nostr-tools SimplePool)

移行後:
  ブラウザ ──SSE──→ /api/stream ──WebSocket──→ リレー (Rust nostr-sdk)
                                    ↓
                               nostrdb に蓄積
```

実装済みファイル：
- `nurunuru-core/src/engine.rs` — SSE 購読メソッド追加
  - `subscribe_stream(filter) -> String` — フィルタ購読開始、sub_id を返す
    - `client.notifications()` reciever を先に取得、`client.subscribe(filter, None)` で REQ 送信
    - バックグラウンドタスクで `RelayPoolNotification::Event` をフィルタリング
    - `Weak<Mutex<VecDeque<String>>>` を使ってライフタイム管理（unsubscribe で自動終了）
  - `poll_subscription(sub_id, max_count) -> Vec<String>` — バッファからイベントをドレイン
  - `unsubscribe_stream(sub_id)` — バッファ削除 + CLOSE 送信
- `nurunuru-napi/src/lib.rs` — NAPI バインディング追加
  - `subscribeStream(filterJson: String) -> Promise<String>`
  - `pollSubscription(subscriptionId: String, maxCount: u32) -> Promise<Vec<String>>`
  - `unsubscribeStream(subscriptionId: String) -> Promise<()>`
- `app/api/stream/route.js` — SSE エンドポイント（新規）
  - `GET /api/stream?filter=xxx` → `text/event-stream`
  - `export const runtime = 'nodejs'`（Node.js Runtime 必須）
  - 50ms ポーリングループ + 25秒ハートビート
  - disconnect 時に `engine.unsubscribeStream()` でクリーンアップ
- `lib/nostr-sse.js` — SSE クライアント（新規）
  - `subscribeSSE(filter, callbacks)` — `EventSource` API ラッパー
  - `subscribeManaged()` と同一インターフェース（透過的移行）
  - 指数バックオフ付き自動再接続（最大10回）
  - イベント重複排除（seenEventIds Set）
  - `isSseAvailable()` — Rust エンジン稼働確認
- `hooks/useNostrSubscription.js` — SSE/WebSocket 自動選択
  - `transport: 'auto'` (デフォルト) — Rust エンジン稼働時は SSE 優先
  - `transport: 'sse'` — SSE 固定
  - `transport: 'websocket'` — WebSocket 固定（既存動作）
  - `activeTransport` 状態を返す（'sse' | 'websocket'）

`GET /api/stream` の動作確認：
```bash
curl -N "http://localhost:3000/api/stream?filter=%7B%22kinds%22%3A%5B1%5D%2C%22limit%22%3A10%7D"
# → text/event-stream
# : connected
# data: {"id":"abc...","kind":1,"content":"..."}
# : heartbeat
```

> **設計方針**: バックグラウンドタスクの終了は `Weak<Arc>` パターンで管理。
> `unsubscribeStream` がバッファの strong Arc を drop すると、次の `Weak::upgrade()` が
> `None` を返してタスクが自動終了。JoinHandle の abort() 不要でシンプル。

### Step 9: nostr-tools 依存削除 🔲

Step 5〜8 完了後に実施。

- `package.json` から `nostr-tools` を削除
- `lib/connection-manager.js` を削除
- `lib/nostr.js` を大幅削減（署名ロジックのみ残す）
- `lib/recommendation.js` を削除（Rust に完全移行済み）
- `lib/filters.js` を削除（Rust に移行済み）

削除後も残るもの：
- `lib/nostr.js` — 署名 (NIP-07 / Amber / NIP-46) のみ
- `lib/secure-key-store.js` — 鍵管理（移行不可）
- `lib/imageUtils.js` — 画像アップロード（外部 API、Rust 不要）
- `lib/cache.js` — nostrdb でカバーできない UI キャッシュ（localStorage）

### Step 10: nurunuru-ffi 完成 (モバイル対応) 🔲

**目標**: iOS / Android 向け UniFFI バインディングを完成させる。

```
nurunuru-ffi/
  ├─ src/lib.rs       — #[uniffi::export] ラッパー
  ├─ nurunuru.udl     — UniFFI 定義ファイル
  └─ bindgen/         — Swift / Kotlin バインディング生成
```

実装予定：
- `nurunuru-ffi/src/lib.rs` — uniffi::export ラッパー (napi と同じ機能を expose)
- `nurunuru-ffi/nurunuru.udl` — 型・メソッド定義
- iOS: Swift Package として配布
- Android: AAR / Kotlin bindings として配布
- 前提: `nurunuru-core` の API は napi/ffi 両対応で変更不要

---

## 移行完了の定義

以下が全て達成された時点で「Rust への完全移行」と言える：

- [ ] `nostr-tools` が `package.json` から削除されている
- [ ] `lib/connection-manager.js` が削除されている
- [ ] `lib/recommendation.js` が削除されている
- [ ] 全てのイベント発行が `/api/publish` 経由
- [ ] 全てのリアルタイム購読が SSE (`/api/stream`) 経由
- [ ] フォロー/ミュートリストが `/api/social` 経由
- [ ] DM・検索が `/api/dm` / `/api/search` 経由
- [ ] イベント署名のみ `lib/nostr.js` に残る（仕様上正しい）
- [ ] `nurunuru-ffi` で iOS/Android 対応

---

## アーキテクチャ目標図（完成形）

```
ブラウザ
  ├─ 署名のみ: nostr.js (NIP-07 / Amber / NIP-46)
  ├─ EventSource → /api/stream   [SSE] ← Rust がリレーから受信してプッシュ
  ├─ POST /api/publish           ← 署名済みイベントを Rust 経由でブロードキャスト
  ├─ GET  /api/feed              ← nostrdb からランキング済みフィード
  ├─ GET  /api/profile/[pubkey]  ← nostrdb プロフィールキャッシュ
  ├─ GET  /api/social/follows    ← nostrdb フォローリスト
  ├─ GET  /api/dm                ← nostrdb DM
  ├─ GET  /api/search            ← Rust NIP-50 検索
  └─ GET  /api/relay             ← Rust リレー状態

サーバー (Rust NuruNuruEngine)
  ├─ nostrdb       ← 全イベント永続化・クエリ
  ├─ recommendation ← フィードスコアリング
  └─ nostr-sdk Client ──WebSocket──→ リレー群
       ├─ wss://yabu.me
       ├─ wss://relay-jp.nostr.wirednet.jp
       ├─ wss://r.kojira.io
       └─ wss://search.nos.today (NIP-50)
```
