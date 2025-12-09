# ぬるぬる 💚

LINE風のNostrクライアント - シンプルで可愛い、みんなのためのNostr

## ✨ 機能

### 📱 タイムライン
- リアルタイム投稿表示
- いいね・リポスト（取消対応）・Zap（ワンタップ/長押しカスタム）
- 投稿の引用表示（nostr:note1/nevent1対応）
- カスタム絵文字表示（NIP-30対応）
- ユーザープロフィール表示
- ミュート機能（NIP-51対応）

### 💬 トーク
- NIP-17 暗号化DM（NIP-44/59対応）
- リアルタイムメッセージ送受信
- 会話リスト表示

### 🏠 ホーム
- プロフィールの表示・編集
- NIP-05認証バッジ表示
- 自分の投稿一覧（リポスト含む）
- 投稿の削除機能（⋮メニュー）
- プロフィール画像アップロード（Blossom/nostr.build対応）

### 🔍 検索
- NIP-50 全文検索
- 検索結果からいいね・リポスト・Zap
- 最近の検索履歴

### ⚙️ 設定（MiniApp）
- デフォルトZap金額設定
- リレー設定（タイムライン用/書き込み用）
- 画像アップロードサーバー設定
- ミュートリスト管理

## 🛠️ セットアップ

```bash
npm install
npm run dev
```

## 🚀 Vercelデプロイ

1. GitHubにプッシュ
2. Vercelでインポート
3. Deploy!

## 📋 技術スタック

- Next.js 14
- React 18
- Tailwind CSS
- nostr-tools

## 📝 NIPs対応

| NIP | 機能 |
|-----|------|
| NIP-01 | Basic protocol |
| NIP-05 | NIP-05認証（表示のみ） |
| NIP-07 | ブラウザ拡張機能ログイン |
| NIP-09 | Event Deletion（投稿削除、いいね取消、リポスト取消） |
| NIP-17 | Private Direct Messages |
| NIP-25 | Reactions（いいね/いいね取消） |
| NIP-27 | Text Note References |
| NIP-30 | Custom Emoji（表示のみ） |
| NIP-44 | Encrypted Payloads |
| NIP-50 | Search Capability |
| NIP-51 | Mute List |
| NIP-57 | Lightning Zaps |
| NIP-59 | Gift Wrap |
| NIP-98 | HTTP Auth（Blossom用） |

## 🖼️ 画像アップロード

- **nostr.build** - デフォルト、認証不要
- **Blossom** - NIP-98認証対応
  - Primal (`blossom.primal.net`)
  - nostr.build (`blossom.nostr.build`)
  - カスタムサーバー

## 📄 ライセンス

MIT License
