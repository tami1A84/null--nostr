<div align="center">

<img src="public/favicon-512.png" width="120" alt="ぬるぬる" />

# ぬるぬる — null--nostr

**LINE風デザインのNostrクライアント**
*A LINE-style Nostr client for everyday humans*

[![Latest Release](https://img.shields.io/github/v/release/tami1A84/null--nostr?label=Android&color=4CAF50)](https://github.com/tami1A84/null--nostr/releases/latest)
[![License](https://img.shields.io/badge/license-Unlicense%20%2F%20MPL--2.0-blue)](#ライセンス--license)
[![Platform](https://img.shields.io/badge/platform-Android%20%7C%20Web-lightgrey)](#インストール--installation)
[![Nostr](https://img.shields.io/badge/Nostr-protocol-purple)](https://nostr.com)

[🇯🇵 日本語](#日本語) · [🇬🇧 English](#english)

</div>

---

## 日本語

Nostrは、特定の企業やサーバーに依存しない自由なSNSプロトコルです。アカウントは暗号鍵で管理され、どのサービスにも縛られません。**ぬるぬる**はその入り口を、LINEのような使い慣れた画面で提供します。

### インストール

**Android（推奨）**

[最新リリース](https://github.com/tami1A84/null--nostr/releases/latest) から `.apk` をダウンロードしてインストール。
[zapstore](https://zapstore.dev) 経由でのインストールにも対応しています。

**Web（ローカル開発）**

```bash
npm install && npm run dev
# → http://localhost:3000
```

### 主な機能

| 機能 | 説明 |
|------|------|
| **タイムライン** | おすすめ・フォロー中を切り替え。画像・動画・カスタム絵文字に対応 |
| **検索** | NIP-50全文検索。`#タグ` `from:npub` `filter:image` などのコマンド対応 |
| **いいね / リアクション** | 2回タップで取り消し（NIP-09）。カスタム絵文字リアクション（NIP-25） |
| **通知** | リアクション・Zap・リポスト・返信・メンションをリアルタイムで受信 |
| **投稿** | 最大3枚の並列画像アップロード。投稿先リレーの選択、NIP-70保護に対応 |
| **トーク** | NIP-EE (MLS) による E2E暗号化DM・グループチャット。LINE風UI |
| **ログイン** | パスキー / nsec / 外部署名アプリ（Amber, NIP-55）に対応 |

### 対応NIP

NIP-01, 02, 05, 07, 09, 11, 17, 19, 25, 27, 30, 32, 42, 44, 46, 50, 51, 57, 58, 59, 62, 65, 70, 71, 98, NIP-EE (MLS)

### ライセンス

本プロジェクトは [Unlicense](LICENSE) のもとで公開されています。
動画機能（`DivineVideoRecorder.kt`, `ProofModeManager.kt`）は [Mozilla Public License 2.0](https://mozilla.org/MPL/2.0/) が適用されます（[Divine](https://github.com/verse-app/divine) ベース）。

---

## English

Nostr is an open, censorship-resistant social protocol. Your identity is a cryptographic key pair — no company, no central server, no lock-in. **null--nostr** makes Nostr feel as familiar as LINE.

### Installation

**Android (recommended)**

Download the latest `.apk` from [Releases](https://github.com/tami1A84/null--nostr/releases/latest) and install directly.
Also available via [zapstore](https://zapstore.dev).

**Web (local dev)**

```bash
npm install && npm run dev
# → http://localhost:3000
```

### Features

| Feature | Description |
|---------|-------------|
| **Timeline** | Recommended & Following feeds. Images, videos, custom emoji |
| **Search** | NIP-50 full-text search with `#tag` `from:npub` `filter:image` commands |
| **Likes / Reactions** | Second tap undoes a like or repost (NIP-09). Custom emoji reactions (NIP-25) |
| **Notifications** | Reactions, Zaps, reposts, replies, mentions — real-time |
| **Posts** | Up to 3 parallel image uploads. Per-relay targeting, NIP-70 protection |
| **Talk** | E2E encrypted DMs & group chats via NIP-EE (MLS). LINE-style UI |
| **Login** | Passkey / nsec / external signer (Amber, NIP-55) |

### Supported NIPs

NIP-01, 02, 05, 07, 09, 11, 17, 19, 25, 27, 30, 32, 42, 44, 46, 50, 51, 57, 58, 59, 62, 65, 70, 71, 98, NIP-EE (MLS)

### License

Released under the [Unlicense](LICENSE).
Video components (`DivineVideoRecorder.kt`, `ProofModeManager.kt`) are licensed under [MPL-2.0](https://mozilla.org/MPL/2.0/), based on [Divine](https://github.com/verse-app/divine).

### For Developers

Architecture, internal guidelines, and AI agent instructions: [AGENTS.md](./AGENTS.md)
