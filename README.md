# ぬるぬる — null--nostr

<p align="center">
  <img src="public/favicon-512.png" width="96" alt="ぬるぬる" />
</p>

<p align="center">
  LINE風のデザインで使えるNostrクライアント
  <br />
  <a href="https://github.com/tami1A84/null--nostr/releases/latest">
    <img src="https://img.shields.io/github/v/release/tami1A84/null--nostr?label=Android&color=4CAF50" alt="Latest Release" />
  </a>
  <img src="https://img.shields.io/badge/license-Unlicense%20%2F%20MPL--2.0-blue" alt="License" />
  <img src="https://img.shields.io/badge/platform-Android%20%7C%20Web-lightgrey" alt="Platform" />
</p>

<p align="center">
  <a href="#日本語">🇯🇵 日本語</a> &nbsp;·&nbsp;
  <a href="#english">🇬🇧 English</a>
</p>

---

## 日本語

### ぬるぬるとは

「ぬるぬる」はNostrプロトコル向けのクライアントアプリです。LINEのような使い慣れたチャット画面で、分散型ソーシャルネットワーク「Nostr」を体験できます。

Nostrは、特定の企業やサーバーに依存しない自由なSNSプロトコルです。アカウントは暗号鍵で管理され、サービスに縛られません。ぬるぬるはその入り口を、できるだけシンプルにすることを目指しています。

### 主な機能

**タイムライン**
- おすすめとフォロー中を切り替えて表示
- アルゴリズムによる推奨フィード（フォロワーネットワーク + エンゲージメント）
- 画像・カスタム絵文字・ループ動画（NIP-71）に対応
- 複数画像をスワイプで閲覧（ピンチ / ダブルタップでズーム）
- nevent / nprofile リンクをインラインカードとして表示

**検索**
- NIP-50全文検索（search.nos.today）
- 検索コマンド対応: `#タグ` `from:npub` `since:` `until:` `-除外語` `"完全一致"` `filter:image` `filter:video` `filter:link`
- 複数コマンドの組み合わせ可能

**いいね・リアクション**
- 2回目のタップでいいね・リポストを取り消し（NIP-09）
- カスタム絵文字でリアクション（NIP-25）
- リアクション選択時に自分の絵文字リストを即座に表示（事前ロード済み）

**通知**
- リアクション・Zap・リポスト・返信・メンションを一覧表示
- リアルタイム更新：新着を上部ピルで通知
- 種別アイコンバッジ付きの見やすいデザイン

**投稿**
- 投稿先リレーを選択して送信（特定のリレーのみへの投稿に対応）
- NIP-70 プロテクト対応：`-` タグで他クライアントからの再共有を防止
- 最大3枚の画像を並列アップロード

**動画投稿**
- 最大6.3秒のループ動画を撮影・投稿
- ProofMode対応: PGP署名とフレームハッシュで動画の真正性を証明
- タップで音声のミュート解除

**トーク（DM / グループ）**
- NIP-EE (MLS) による End-to-End 暗号化: 1対1DM・グループチャットに対応
- 旧DM (NIP-17/44/59) は読み取り専用で互換表示
- LINE風のチャット画面・キャッシュファースト表示

**セキュリティ**
- パスキーログイン（パスワード不要）
- 外部署名アプリ（Amber等）対応
- 秘密鍵はデバイス内に安全に保管

**ミニアプリ**
- カスタム絵文字: セット内絵文字を ♥ でお気に入り登録・即座にリアクション選択へ反映
- プロフィールバッジ、スケジュール調整など
- 外部WebアプリをNostrセッション付きで起動

### インストール

**Android（推奨）**

[Releases](https://github.com/tami1A84/null--nostr/releases/latest) から最新の `.apk` をダウンロードしてインストールしてください。

zapstore経由でのインストールも対応しています。

**Web**

```bash
npm install
npm run dev
```

ブラウザで `http://localhost:3000` を開きます。

### 対応NIP

NIP-01, 02, 05, 07, 09, 11, 17, 19, 25, 27, 30, 32, 42, 44, 46, 50, 51, 57, 58, 59, 62, 65, 70, 71, 98, NIP-EE (MLS)

### ライセンス

本プロジェクトは [Unlicense](LICENSE) のもとで公開されています。
動画機能（`DivineVideoRecorder.kt`, `ProofModeManager.kt`）は [Mozilla Public License 2.0](https://mozilla.org/MPL/2.0/) が適用されます（[Divine](https://github.com/verse-app/divine) をベースにしています）。

---

## English

### What is null--nostr?

null--nostr is a Nostr client with a LINE-inspired chat interface. It makes the decentralized social network Nostr accessible to everyday users through a familiar, comfortable design.

Nostr is an open protocol for censorship-resistant social networking. Your account is a cryptographic key pair — no company, no central server, no lock-in. null--nostr aims to be the easiest way in.

### Features

**Timeline**
- Switch between Recommended and Following feeds
- Algorithm-driven feed (2nd-degree network + engagement scoring)
- Images, custom emoji, and short loop videos (NIP-71)
- Swipe through multiple images (pinch / double-tap to zoom)
- nevent / nprofile links rendered as inline embedded cards

**Search**
- NIP-50 full-text search via search.nos.today
- Search commands: `#tag` `from:npub` `since:` `until:` `-exclude` `"exact phrase"` `filter:image` `filter:video` `filter:link`
- Combine multiple commands freely

**Likes & Reactions**
- Second tap cancels a like or repost (NIP-09 deletion)
- Custom emoji reactions (NIP-25)
- Emoji list pre-loaded at startup for instant reaction picker display

**Notifications**
- Reactions, Zaps, reposts, replies, and mentions in one list
- Real-time updates: new items announced via animated pill banner
- Per-type icon badges for at-a-glance clarity

**Posts**
- Select target relays before posting (publish to specific relays only)
- NIP-70 protection: add `-` tag to prevent reposting by other clients
- Upload up to 3 images in parallel

**Video Posts**
- Record and post loop videos up to 6.3 seconds
- ProofMode: PGP signatures and per-frame SHA-256 hashes to verify authenticity
- Tap to unmute audio in feed

**Talk (DMs & Groups)**
- End-to-end encrypted messaging via NIP-EE (MLS): 1-on-1 DMs and group chats
- Legacy DMs (NIP-17/44/59) displayed read-only for compatibility
- LINE-style chat interface with cache-first message loading

**Security**
- Passkey login (passwordless)
- External signer support (Amber, NIP-55)
- Private keys stored securely on-device, never exposed

**Mini Apps**
- Custom emoji: heart-tap to favorite emojis from sets, instantly available in reaction picker
- Built-in tools: profile badges, scheduler, relay settings, backup
- Launch external web apps with a Nostr session

### Installation

**Android (recommended)**

Download the latest `.apk` from [Releases](https://github.com/tami1A84/null--nostr/releases/latest) and install it directly.

Also available via zapstore.

**Web**

```bash
npm install
npm run dev
```

Open `http://localhost:3000` in your browser.

### Supported NIPs

NIP-01, 02, 05, 07, 09, 11, 17, 19, 25, 27, 30, 32, 42, 44, 46, 50, 51, 57, 58, 59, 62, 65, 70, 71, 98, NIP-EE (MLS)

### License

This project is released under the [Unlicense](LICENSE).
The video components (`DivineVideoRecorder.kt`, `ProofModeManager.kt`) are licensed under the [Mozilla Public License 2.0](https://mozilla.org/MPL/2.0/), based on [Divine](https://github.com/verse-app/divine).

### For Developers

Architecture, internal guidelines, and AI agent instructions are in [AGENTS.md](./AGENTS.md).
