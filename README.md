# ぬるぬる

**LINE風のNostrクライアント** — シンプルで可愛い、みんなのためのNostr

---

## Features

### Timeline

- リレータイムライン / フォロータイムライン切り替え
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
| Nostr Connect | nsec.app などのリモート署名（NIP-46） |
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
| Protocol | nostr-tools 2.17 |
| Auth | [nosskey-sdk](https://github.com/ocknamo/nosskey-sdk) 0.0.4 |
| Search | [searchnos](https://github.com/darashi/searchnos) (NIP-50) |
| Mobile | Capacitor 8.0 |

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
| NIP-46 | Nostr Connect（nsec.app等リモート署名） |
| NIP-50 | Search Capability（[searchnos](https://github.com/darashi/searchnos)使用） |
| NIP-51 | Mute List |
| NIP-55 | Amber Android Signer |
| NIP-56 | Reporting（通報機能） |
| NIP-57 | Lightning Zaps |
| NIP-58 | Badges（表示・プロフィールバッジ管理） |
| NIP-59 | Gift Wrap |
| NIP-62 | Request to Vanish（削除リクエスト） |
| NIP-70 | Protected Events（保護イベントの検出・作成） |
| NIP-98 | HTTP Auth（画像アップロード用） |

---

## NIP-11: Relay Information Document

リレーの情報ドキュメントを取得・管理する機能を提供します。

### Functions

| Function | Description |
|----------|-------------|
| `fetchRelayInfo(relayUrl)` | リレー情報ドキュメントの取得 |
| `getRelaySupportedNips(relayUrl)` | サポートNIP一覧取得 |
| `relaySupportsNip(relayUrl, nip)` | 特定NIPサポート確認 |
| `getRelayLimitations(relayUrl)` | リレー制限情報取得 |
| `relayRequiresAuth(relayUrl)` | 認証要否確認 |
| `relayRequiresPayment(relayUrl)` | 支払い要否確認 |
| `getRelayInfoForDisplay(relayUrl)` | 表示用リレー情報取得 |
| `clearRelayInfoCache(relayUrl?)` | キャッシュクリア |

### Features

- **1時間キャッシュ**による効率化
- Accept: `application/nostr+json` ヘッダー対応
- 制限情報（`limitation`）の取得
- 対応言語・国情報の取得
- 支払い情報・投稿ポリシーの取得

---

## NIP-42: Client Authentication

リレーとの認証を管理する機能を提供します。

### Functions

| Function | Description |
|----------|-------------|
| `handleAuthChallenge(relayUrl, challenge)` | AUTHチャレンジ処理 |
| `createAuthEvent(relayUrl, challenge)` | 認証イベント作成 (kind 22242) |
| `authenticateWithRelay(relayUrl, challenge)` | リレー認証実行 |
| `getPendingAuthChallenge(relayUrl)` | 保留中チャレンジ取得 |
| `markRelayAuthenticated(relayUrl, pubkey)` | 認証状態管理 |
| `isRelayAuthenticated(relayUrl)` | 認証状態確認 |
| `getRelayAuthPubkey(relayUrl)` | 認証済み公開鍵取得 |
| `clearRelayAuth(relayUrl?)` | 認証状態クリア |
| `isAuthRequiredMessage(message)` | auth-requiredメッセージ解析 |

### Features

- **10分間有効なチャレンジ管理**
- **24時間有効な認証状態管理**
- kind 22242 認証イベントの自動作成・署名
- `auth-required` CLOSEDメッセージの解析
- リレーごとの認証状態追跡

---

## NIP-62: Request to Vanish

リレーからのデータ削除をリクエストする機能を提供します。

### Functions

| Function | Description |
|----------|-------------|
| `createVanishRequest(options)` | 削除リクエストイベント作成 (kind 62) |
| `requestVanish(options)` | 削除リクエスト送信 |
| `requestVanishFromRelay(relayUrl, reason?)` | 特定リレーへの削除リクエスト |
| `requestGlobalVanish(reason?, additionalRelays?)` | 全リレーへの削除リクエスト |
| `isVanishRequest(event)` | イベント種類判定 |
| `getVanishTargetRelays(event)` | 対象リレー取得 |
| `isGlobalVanishRequest(event)` | グローバルリクエスト判定 |

### Features

- `ALL_RELAYS` タグによるグローバル削除リクエスト
- 特定リレーを指定した削除リクエスト
- 削除理由（reason）の指定
- 複数リレーへの同時ブロードキャスト

### Usage Example

```javascript
import { requestVanishFromRelay, requestGlobalVanish } from './lib/nostr'

// 特定リレーからの削除
await requestVanishFromRelay('wss://relay.example.com', '個人情報の削除')

// 全リレーからの削除（注意: 不可逆）
await requestGlobalVanish('アカウント削除のため')
```

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
app/
  layout.js          Root layout
  page.js            Main entry

components/
  TimelineTab.js     Timeline view
  TalkTab.js         DM conversations
  HomeTab.js         Profile & settings
  MiniAppTab.js      Mini applications
  PostItem.js        Post rendering
  ...

lib/
  nostr.js           Protocol operations
  connection-manager.js   WebSocket pool management
  cache.js           Data caching layer
  secure-key-store.js     Secure key storage
  validation.js      Input validation & sanitization
  errors.js          Custom error classes
  security.js        Security utilities
  nip46.js           Nostr Connect
  imageUtils.js      Image processing
  constants.js       Application constants
```

---

## License

MIT License

---

Made with Nostr
