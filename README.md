# ぬるぬる

**LINE風のNostrクライアント** — シンプルで可愛い、みんなのためのNostr

---

## Features

### Timeline

- おすすめタイムライン / フォロータイムライン切り替え
- 固定ヘッダー（スクロール中もタブ切り替え・検索可能）
- リアルタイム投稿表示
- いいね・リポスト（取消対応）・Zap（ワンタップ/長押しカスタム）
- 投稿の引用表示（nostr:note1/nevent1対応）
- 返信インジケーター表示
- カスタム絵文字表示（NIP-30対応）
- ユーザープロフィール表示
- フォロー/アンフォロー機能
- ミュート機能（NIP-51対応）
- 通報機能（NIP-56対応）
- Birdwatch（NIP-32対応、コンテキスト追加・評価）

### Talk

- NIP-17 暗号化DM（NIP-44/59対応）
  - kind 15 ファイルメッセージ対応
  - kind 10050 DMリレーリスト対応
- リアルタイムメッセージ送受信
- 会話リスト表示
- プロフィールからDM開始

### Home

- プロフィールの表示・編集
- NIP-05認証バッジ表示・検証
- プロフィールバッジ表示（NIP-58対応、最大3つ）
- フォローリスト管理（フォロー数表示・フォロー解除）
- 自分の投稿一覧（リポスト含む）
- 投稿の削除機能
- プロフィール画像アップロード（Blossom/nostr.build対応）
- 誕生日表示

### Search

- NIP-50 全文検索（[searchnos](https://github.com/darashi/searchnos) を使用）
- 公開鍵検索（npub1... / hex形式）
- NIP-05検索（user@domain.tld）
- イベントID検索（note1... / nevent1... / naddr1...）
- 検索結果からいいね・リポスト・Zap
- 最近の検索履歴

### Video & Audio

- **Divine Video**: 6.3秒のループ動画投稿（Kind 34236）
- **ProofMode**: PGP署名による動画の真正性検証（AI生成・改ざん防止）
- **ElevenLabs STT**: Scribe v2 を使用した高精度なリアルタイム音声入力（日本語対応）

### Mini Apps（Settings）

- デフォルトZap金額設定
- リレー設定
- 画像アップロードサーバー設定
- ミュートリスト管理
- カスタム絵文字セット管理（NIP-30）
- プロフィールバッジ管理（NIP-58）
  - 獲得済みバッジ表示（kind:8アワード）
  - プロフィールバッジ追加/削除（kind:30008）
- パスキー設定（秘密鍵エクスポート/自動署名）
- イベントバックアップ
  - 全イベントのJSON形式エクスポート
  - 保護イベント（NIP-70）の適切な処理
  - バックアップからのイベントインポート
- NIP-62削除リクエスト
  - 特定リレーへの削除リクエスト
  - 全リレーへの削除リクエスト

### PWA

- ホーム画面に追加してアプリのように使用可能
- オフラインキャッシュ対応

---

## Authentication

### Passkey（推奨）

[Nosskey SDK](https://github.com/ocknamo/nosskey-sdk) を使用したパスキー認証。
Face ID / Touch ID / Windows Hello を使って、パスワード不要で安全にログインできます。

- 秘密鍵はデバイスのセキュアエリアに保存
- 設定画面から秘密鍵のエクスポートが可能
- PRF対応ブラウザが必要（Chrome 109+ / Safari 17+ / Edge 109+）

### Other Methods

パスキー非対応ブラウザや、既存の秘密鍵を使いたい場合：

| Method | Description |
|--------|-------------|
| Browser Extension | Alby / nos2x など（NIP-07） |
| Nostr Connect | リモート署名（NIP-46） |
| Read-only | npub入力（署名不可） |
| Local Key | nsec直接入力（ブラウザに保存） |

---

## Setup

```bash
npm install
npm run dev
```

ブラウザで http://localhost:3000 を開きます。

---

## Tech Stack

| Category | Technology |
|----------|------------|
| Framework | Next.js 14.2 |
| UI | React 18.3, Tailwind CSS 3.4 |
| Protocol | nostr-tools 2.17, rx-nostr |
| Auth | [nosskey-sdk](https://github.com/ocknamo/nosskey-sdk) 0.0.4 |
| STT | ElevenLabs Scribe v2 |
| Core | Rust (NuruNuru Core), nostrdb |
| Search | [searchnos](https://github.com/darashi/searchnos) (NIP-50) |

---

## NIPs

| NIP | Description |
|-----|-------------|
| NIP-01 | Basic protocol |
| NIP-02 | Follow List（フォロー/アンフォロー） |
| NIP-05 | NIP-05認証・検証・検索 |
| NIP-07 | ブラウザ拡張機能（Alby, nos2x等） |
| NIP-09 | Event Deletion（投稿削除、いいね取消、リポスト取消） |
| NIP-11 | Relay Information Document（リレー情報取得） |
| NIP-17 | Private Direct Messages（kind 15/10050対応） |
| NIP-19 | bech32エンコード（npub/nsec/note/nevent/naddr） |
| NIP-25 | Reactions（いいね/いいね取消） |
| NIP-27 | Text Note References |
| NIP-30 | Custom Emoji |
| NIP-32 | Labeling（Birdwatchコンテキスト追加・評価） |
| NIP-42 | Client Authentication（リレー認証） |
| NIP-44 | Encrypted Payloads |
| NIP-46 | Nostr Connect（リモート署名） |
| NIP-50 | Search Capability（[searchnos](https://github.com/darashi/searchnos)使用） |
| NIP-51 | Mute List |
| NIP-56 | Reporting（通報機能） |
| NIP-57 | Lightning Zaps |
| NIP-58 | Badges（表示・プロフィールバッジ管理） |
| NIP-59 | Gift Wrap |
| NIP-62 | Request to Vanish（削除リクエスト） |
| NIP-65 | Relay List Metadata（リレーリスト・Outbox Model） |
| NIP-70 | Protected Events（保護イベントの検出・作成） |
| NIP-98 | HTTP Auth（画像アップロード用） |
| Kind 34236 | Short Loop Video (Divine) |

---

## Recommendation Algorithm

おすすめタイムラインは、Xの推奨フィードにインスパイアされたアルゴリズムを使用しています。

### Feed Composition

| 構成 | 比率 | 説明 |
|------|------|------|
| 2次ネットワーク | 50% | 友人の友人の投稿（発見重視） |
| 高エンゲージメント | 30% | ネットワーク外のバイラルコンテンツ |
| 1次ネットワーク | 20% | 直接フォローの重要な投稿 |

### Scoring System

最終スコアは以下の要素を掛け合わせて計算されます：

```
Score = Engagement × Social × Author × Geohash × Modifier × TimeDecay
```

#### Engagement Score

| アクション | 重み |
|----------|------|
| Zap | 100 |
| カスタムリアクション | 60 |
| Quote | 35 |
| Reply | 30 |
| Repost | 25 |
| Bookmark | 15 |
| Like | 5 |

#### Social Boost

| 関係性 | 倍率 |
|--------|------|
| 友人の友人（2次） | 3.0x |
| 相互フォロー | 2.5x |
| よくエンゲージするオーサー | 2.0x |
| 直接フォロー（1次） | 0.5x |

#### Time Decay

- 0-1時間: 1.5倍ブースト
- 1-6時間: 指数関数的減衰（半減期6時間）
- 48時間以上: 最小スコア

### Personalization

- **エンゲージメント履歴**: いいね・リポスト・返信したオーサーの投稿を優先
- **「興味がない」フィードバック**: マークした投稿・オーサーのスコアを減少
- **地域ブースト**: 同じGeohash地域の投稿を優先（最大2.0倍）

---

## Geolocation Relay

位置情報に基づいてリレー設定を自動化する機能を提供します。

### Auto Detection

GPS許可を与えることで、最適なリレーを自動検出・設定します。

1. **位置情報取得**: ブラウザのGeolocation APIを使用
2. **Geohashエンコード**: 緯度経度を文字列に圧縮
3. **最適リレー選択**: 距離とpriorityに基づいて選択
4. **NIP-65リスト生成**: inbox/outbox/discoverを自動設定

### Relay List Generation

| タイプ | 説明 | 個数 |
|--------|------|------|
| Outbox | 書き込み用（投稿ブロードキャスト） | 3-5個 |
| Inbox | 読み込み用（メンション受信） | 3-4個 |
| Discover | 発見用（グローバルリレー） | 2-3個 |

### Manual Region Selection

GPS共有を望まない場合、手動で地域を選択できます：

- 日本: 東京、大阪、名古屋、福岡、札幌
- グローバル: 北米西部/東部、中央ヨーロッパ、東南アジア、オセアニア

---

## Security

### Secure Key Storage

秘密鍵はモジュールプライベートなクロージャに保存され、グローバルスコープからアクセスできません。

- ページアンロード時に自動クリア
- ゼロフィルによる安全な削除
- `window` オブジェクトへの露出なし

### Input Validation (`lib/validation.js`)

入力検証とサニタイズのユーティリティを提供します。

| Function | Description |
|----------|-------------|
| `escapeHtml(str)` | HTML エンティティエスケープ |
| `sanitizeText(text)` | テキストサニタイズ |
| `validateUrl(url, options)` | URL検証 |
| `isValidRelayUrl(url)` | リレーURL検証 |
| `isValidPubkey(pubkey)` | 公開鍵検証 |
| `isValidEventId(eventId)` | イベントID検証 |
| `isValidNip05(nip05)` | NIP-05識別子検証 |
| `validateEventContent(content, kind)` | イベントコンテンツ検証 |
| `validateEventTags(tags)` | イベントタグ検証 |
| `validateProfile(profile)` | プロフィール検証 |

### Error Handling (`lib/errors.js`)

構造化されたエラーハンドリングを提供します。

| Error Class | Description |
|-------------|-------------|
| `NostrError` | 基本エラークラス |
| `NetworkError` | ネットワークエラー |
| `AuthError` | 認証エラー |
| `EncryptionError` | 暗号化エラー |
| `ValidationError` | 検証エラー |
| `RelayError` | リレーエラー |
| `EventError` | イベントエラー |
| `StorageError` | ストレージエラー |

### Security Utilities (`lib/security.js`)

セキュリティ関連のユーティリティを提供します。

| Function | Description |
|----------|-------------|
| `generateSecureToken()` | 暗号学的安全なトークン生成 |
| `getCsrfToken()` | CSRFトークン取得 |
| `validateCsrfToken(token)` | CSRFトークン検証 |
| `secureStore(key, value)` | 暗号化ストレージ保存 |
| `secureRetrieve(key)` | 暗号化ストレージ取得 |
| `analyzeContentSecurity(content)` | コンテンツセキュリティ分析 |
| `sanitizeContent(content)` | 危険なコンテンツの除去 |
| `getBrowserFingerprint()` | ブラウザフィンガープリント |
| `initSessionSecurity()` | セッションセキュリティ初期化 |

---

## Image Upload

| Server | Description |
|--------|-------------|
| nostr.build | デフォルト、NIP-98認証対応 |
| yabu.me | NIP-98認証対応 |
| Blossom | nostr.build / カスタムサーバー対応 |

---

## Performance

### Connection Management

- リクエストスロットリング（グローバル4 / リレー毎2同時接続）
- レート制限（10リクエスト/秒、バースト20）
- 指数バックオフリトライ（最大3回）
- 失敗リレー追跡と自動クールダウン（2分）

### Caching

- プロフィール・フォローリスト・ミュートリストのキャッシュ
- カスタム絵文字・バッジのキャッシュ
- バッチプロフィール取得
- バッジ定義の複数リレー検索
- リレー情報の1時間キャッシュ（NIP-11）

---

## Architecture

```
src/
  core/              Redux/Store logic
  adapters/          Platform adapters (Storage, Signing, Network)
  ui/                Modular React components & hooks
  platform/          Environment detection (Web/Electron)

app/
  layout.js          Next.js Root layout
  page.js            Main entry
  api/               Server-side API routes (ElevenLabs, etc.)

components/
  TimelineTab.js     Timeline view
  TalkTab.js         DM conversations (NIP-17)
  HomeTab.js         Profile & settings
  MiniAppTab.js      Mini applications
  PostItem.js        Post rendering
  DivineVideoRecorder.js Video recording with ProofMode

lib/
  nostr.js           Protocol operations
  connection-manager.js   WebSocket pool management
  cache.js           Data caching layer
  rust-bridge.js     Rust engine bridge
  recommendation.js  Recommendation algorithm

rust-engine/
  nurunuru-core/     Core logic in Rust
  nurunuru-ffi/      UniFFI bindings for Mobile/Native
  nurunuru-napi/     Node.js bindings for Desktop
```

---

## License

このプロジェクトの大部分は **Unlicense** ですが、以下のコードは **MPL-2.0** に基づいています。

- `components/DivineVideoRecorder.js`
- `lib/proofmode.js`
- 動画投稿機能

それ以外の部分は [The Unlicense](LICENSE) の下で自由に利用可能です。

---

Made with Nostr
