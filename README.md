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
| NIP-17 | Private Direct Messages |
| NIP-19 | bech32エンコード（npub/nsec/note/nevent/naddr） |
| NIP-25 | Reactions（いいね/いいね取消） |
| NIP-27 | Text Note References |
| NIP-30 | Custom Emoji |
| NIP-32 | Labeling（Birdwatchコンテキスト追加・評価） |
| NIP-44 | Encrypted Payloads |
| NIP-46 | Nostr Connect（nsec.app等リモート署名） |
| NIP-50 | Search Capability（[searchnos](https://github.com/darashi/searchnos)使用） |
| NIP-51 | Mute List |
| NIP-56 | Reporting（通報機能） |
| NIP-57 | Lightning Zaps |
| NIP-58 | Badges（表示・プロフィールバッジ管理） |
| NIP-59 | Gift Wrap |
| NIP-98 | HTTP Auth（画像アップロード用） |

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
  nip46.js           Nostr Connect
  imageUtils.js      Image processing
```

---

## License

MIT License

---

Made with Nostr
