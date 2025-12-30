# ぬるぬる

LINE風UIのNostrクライアント

---

## Overview

シンプルで直感的なNostr体験を提供するWebアプリケーション。
パスキー認証による安全なログイン、リアルタイム通信、暗号化DMをサポート。

## Features

### Timeline
- リレー/フォロー切り替え
- リアルタイム投稿表示
- いいね・リポスト・Zap
- 引用投稿表示
- カスタム絵文字

### Direct Messages
- NIP-17暗号化DM
- リアルタイム送受信
- 会話リスト管理

### Profile
- プロフィール編集・画像アップロード
- NIP-05認証
- バッジ表示
- フォロー管理

### Search
- NIP-50全文検索
- 検索履歴

### Settings
- Zap金額設定
- リレー管理
- ミュートリスト
- カスタム絵文字セット
- パスキー管理

## Authentication

| Method | Description |
|--------|-------------|
| Passkey | Face ID / Touch ID / Windows Hello |
| NIP-07 | Alby, nos2x等ブラウザ拡張 |
| NIP-46 | nsec.app等リモート署名 |
| Read-only | npub閲覧専用 |
| Local | nsec直接入力 |

## Quick Start

```bash
npm install
npm run dev
```

http://localhost:3000

## Tech Stack

| Category | Technology |
|----------|------------|
| Framework | Next.js 14 |
| UI | React 18, Tailwind CSS |
| Protocol | nostr-tools |
| Auth | nosskey-sdk |
| Mobile | Capacitor |

## NIPs

01, 02, 05, 07, 09, 17, 19, 25, 27, 30, 44, 46, 50, 51, 57, 58, 59, 98

## Architecture

```
app/           Next.js App Router
components/    UI Components
lib/           Core Libraries
  nostr.js           Protocol operations
  connection-manager.js   WebSocket management
  cache.js           Data caching
  secure-key-store.js    Key management
```

### Connection Management

- スロットリング: 4並列 / 2並列per relay
- レート制限: 10req/s, burst 20
- 指数バックオフリトライ
- 失敗リレー自動クールダウン

## Image Upload

- nostr.build
- yabu.me
- Blossom

すべてNIP-98認証対応

## License

MIT
