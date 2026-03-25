<div align="center">

<img src="public/favicon-512.png" width="120" alt="ぬるぬる" />

# ぬるぬる — null--nostr

**シンプルで使いやすいNostrクライアント**
*A simple and intuitive Nostr client*

[![Latest Release](https://img.shields.io/github/v/release/tami1A84/null--nostr?label=Android&color=4CAF50)](https://github.com/tami1A84/null--nostr/releases/latest)
[![License](https://img.shields.io/badge/license-Unlicense-blue)](#ライセンス--license)
[![Platform](https://img.shields.io/badge/platform-Android-lightgrey)](#インストール--installation)
[![Nostr](https://img.shields.io/badge/Nostr-protocol-purple)](https://nostr.com)

[🇯🇵 日本語](#日本語) · [🌐 English](#english)

</div>

---

## 日本語

Nostrは、特定の企業やサーバーに依存しない自由なSNSプロトコルです。アカウントは暗号鍵で管理され、どのサービスにも縛られません。**ぬるぬる**はその入り口を、シンプルで使い慣れた画面で提供します。

### インストール

[最新リリース](https://github.com/tami1A84/null--nostr/releases/latest) から `.apk` をダウンロードしてインストール。
[zapstore](https://zapstore.dev) 経由でのインストールにも対応しています。

### 主な機能

タイムラインはフォロー中とおすすめを切り替えて表示できます。画像・動画・カスタム絵文字・長文記事（NIP-23）に対応しています。

投稿は最大3枚の並列画像アップロードが可能で、投稿先リレーをイベント単位で選択できます。NIP-70（保護投稿）にも対応しています。

いいね・リアクションはカスタム絵文字に対応しており、2回タップで取り消しができます。リポストは長押しで引用リポストに切り替わります。

通知はリアクション・Zap・リポスト・返信・メンション・誕生日をリアルタイムで受信します。

トークはNIP-EE（MLS）によるE2E暗号化のDMとグループチャットに対応しています。旧来のNIP-17 DMの読み取りも可能です。

Zapはインボイス生成・送信に対応し、金額はBIP-177（₿）表記で表示します。

検索はNIP-50全文検索で、`#タグ` `from:npub` `filter:image` などのコマンドに対応しています。

ミニアプリとしてカスタム絵文字管理、プロフィールバッジ設定、リレー設定、ミュートリスト、Zap設定、調整くん（スケジュール調整）、音声入力、バックアップ、削除リクエスト、キャッシュ設定を提供しています。

ログインはnsec・外部署名アプリ（Amber、NIP-55）に対応しています。

<div align="center">
<img src="https://blossom.primal.net/07c92011f73f301d3dfe0e642f8d6c09547b934e4b8cb51ab0781f4288e5e370.png" width="23%" />
<img src="https://blossom.primal.net/08e58108054026805ba15fec73907598354647af5051505483eab71d74e5e265.png" width="23%" />
<img src="https://blossom.primal.net/51245134a6f922b4b22eeb65f0ee7c6aa96a5021596917e34b18a792b73ea540.png" width="23%" />
<img src="https://blossom.primal.net/3251a2a0816281515f2e115527037f4f0ce257bc555649717ddfaaee24f51dd4.png" width="23%" />
</div>

### 対応NIP

| NIP | 内容 |
|-----|------|
| NIP-01 | 基本プロトコル・テキストノート |
| NIP-02 | フォローリスト（kind 3） |
| NIP-05 | DNS識別子（`user@domain` 認証） |
| NIP-09 | イベント削除・リアクション取り消し |
| NIP-11 | リレー情報 |
| NIP-17 | プライベートDM（Gift Wrap） |
| NIP-19 | bech32エンコード（npub / nsec / note / nevent など） |
| NIP-23 | 長文記事（kind 30023） |
| NIP-25 | リアクション・カスタム絵文字リアクション |
| NIP-27 | テキスト内ノート参照 |
| NIP-30 | カスタム絵文字 |
| NIP-32 | ラベリング（Birdwatch） |
| NIP-42 | リレー認証 |
| NIP-44 | 暗号化コンテンツ |
| NIP-46 | Nostr Connect（外部署名） |
| NIP-50 | 全文検索 |
| NIP-51 | リスト（ブックマーク kind 10003・ミュートリスト kind 10000） |
| NIP-55 | Amber外部署名アプリ |
| NIP-57 | Zap（Lightning決済） |
| NIP-58 | バッジ（プロフィールバッジ） |
| NIP-59 | Gift Wrap |
| NIP-62 | 削除リクエスト（Vanish） |
| NIP-65 | リレーリスト（read / write） |
| NIP-70 | 保護投稿 |
| NIP-71 | 動画イベント |
| NIP-98 | HTTP認証 |
| NIP-EE | MLS E2E暗号化トーク・グループチャット |

### ライセンス

本プロジェクトは [Unlicense](LICENSE) のもとで公開されています。

### 連絡先

`npub194dkgpxl2vk7pqkeualh7sjh5m6rldumh80gm5av0h67d494qzcqum2u20`

---

## English

Nostr is an open, censorship-resistant social protocol. Your identity is a cryptographic key pair — no company, no central server, no lock-in. **null--nostr** makes Nostr feel simple and accessible.

### Installation

Download the latest `.apk` from [Releases](https://github.com/tami1A84/null--nostr/releases/latest) and install directly.
Also available via [zapstore](https://zapstore.dev).

### Features

The timeline supports Following and Recommended feeds with images, videos, custom emoji, and long-form articles (NIP-23).

Posts support up to 3 parallel image uploads, per-event relay targeting, and NIP-70 protected posts.

Likes and reactions support custom emoji. Double-tap to undo. Long-press the repost button for quote repost.

Notifications deliver reactions, Zaps, reposts, replies, mentions, and birthdays in real time.

Talk provides E2E encrypted DMs and group chats via NIP-EE (MLS), with read-only support for legacy NIP-17 DMs.

Zaps support invoice generation and sending, with amounts displayed in BIP-177 (₿) notation.

Search uses NIP-50 full-text with commands: `#tag`, `from:npub`, `filter:image`.

Mini-apps cover custom emoji, profile badges, relay settings, mute list, Zap settings, scheduling, voice input, backup, vanish request, and cache settings.

Login supports nsec and external signers (Amber, NIP-55).

<div align="center">
<img src="https://blossom.primal.net/07c92011f73f301d3dfe0e642f8d6c09547b934e4b8cb51ab0781f4288e5e370.png" width="23%" />
<img src="https://blossom.primal.net/08e58108054026805ba15fec73907598354647af5051505483eab71d74e5e265.png" width="23%" />
<img src="https://blossom.primal.net/51245134a6f922b4b22eeb65f0ee7c6aa96a5021596917e34b18a792b73ea540.png" width="23%" />
<img src="https://blossom.primal.net/3251a2a0816281515f2e115527037f4f0ce257bc555649717ddfaaee24f51dd4.png" width="23%" />
</div>

### Supported NIPs

| NIP | Description |
|-----|-------------|
| NIP-01 | Basic protocol, text notes |
| NIP-02 | Follow list (kind 3) |
| NIP-05 | DNS identifier verification |
| NIP-09 | Event deletion, reaction undo |
| NIP-11 | Relay information |
| NIP-17 | Private DMs (Gift Wrap) |
| NIP-19 | bech32 encoding (npub / nsec / note / nevent etc.) |
| NIP-23 | Long-form content (kind 30023) |
| NIP-25 | Reactions and custom emoji reactions |
| NIP-27 | In-text note references |
| NIP-30 | Custom emoji |
| NIP-32 | Labeling (Birdwatch) |
| NIP-42 | Relay authentication |
| NIP-44 | Encrypted content |
| NIP-46 | Nostr Connect (remote signing) |
| NIP-50 | Full-text search |
| NIP-51 | Lists (bookmarks kind 10003, mute list kind 10000) |
| NIP-55 | Amber external signer |
| NIP-57 | Zaps (Lightning payments) |
| NIP-58 | Badges |
| NIP-59 | Gift Wrap |
| NIP-62 | Request to Vanish |
| NIP-65 | Relay list (read / write) |
| NIP-70 | Protected events |
| NIP-71 | Video events |
| NIP-98 | HTTP Auth |
| NIP-EE | MLS E2E encrypted talk and group chats |

### License

Released under the [Unlicense](LICENSE).

### Contact

`npub194dkgpxl2vk7pqkeualh7sjh5m6rldumh80gm5av0h67d494qzcqum2u20`

### For Developers

Architecture, internal guidelines, and AI agent instructions: [AGENTS.md](./AGENTS.md)
