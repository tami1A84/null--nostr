# ぬるぬる (null--nostr)

[English follows Japanese]

---

**LINE風のNostrクライアント** — シンプルで可愛い、みんなのためのNostr

## 🌟 概要

「ぬるぬる」は、親しみやすいチャット風インターフェースを備えた、ユーザーフレンドリーなNostrクライアントです。パフォーマンス、セキュリティ、そして心地よいユーザー体験に重点を置いて開発されています。

## ✨ 特徴

### 📱 タイムライン
- **推奨フィード**: アルゴリズムによる「おすすめ」と「フォロー中」の切り替え。
- **Birdwatch**: コミュニティによるコンテキスト追加と評価 (NIP-32)。
- **リッチメディア**: 画像、カスタム絵文字、ループ動画 (Divine) に対応。

### 💬 トーク
- **安全なDM**: NIP-17/44/59 に基づく暗号化されたプライベートメッセージ。
- **チャット画面**: 使い慣れた操作感のデザイン。

### 🔒 セキュリティ
- **パスキー対応**: パスワード不要の安全なログイン。
- **セキュアな鍵管理**: 秘密鍵は安全に保護され、外部に露出しません。

## 🚀 クイックスタート

### Web
```bash
npm install
npm run dev
```
ブラウザで [http://localhost:3000](http://localhost:3000) を開きます。

### Android
`android/` ディレクトリ配下の Gradle プロジェクトを参照してください。

## 🛠 技術スタック

- **Frontend**: Next.js 14, React, Tailwind CSS
- **Core**: Rust (nurunuru-core), nostr-tools, rx-nostr
- **Native**: Kotlin (Android), Swift (iOS)

## 🤖 AIエージェント・開発者の方へ

詳細な技術ドキュメント、アーキテクチャ、内部ガイドラインは **[AGENTS.md](./AGENTS.md)** に集約されています。AIコーディングエージェントとして作業する場合は、必ずそのファイルを参照してください。

---

## 📄 License

プロジェクトの大部分は [Unlicense](LICENSE) ですが、一部のコンポーネントは MPL-2.0 に基づいています。

---

# null--nostr

**LINE-like Nostr Client** — Simple, cute, and for everyone.

## 🌟 Overview

null--nostr is a user-friendly Nostr client designed with a familiar chat-like interface. It focuses on performance, security, and a pleasant user experience.

## ✨ Features

### 📱 Timeline
- **Mixed Feed**: Smart recommendation algorithm for "Recommended" and "Following" feeds.
- **Birdwatch**: Community-driven context and labeling (NIP-32).
- **Rich Media**: Supports images, custom emojis, and short loop videos (Divine).

### 💬 Talk
- **Secure DMs**: Encrypted private messaging based on NIP-17/44/59.
- **Chat Interface**: Familiar and easy-to-use design.

### 🔒 Security
- **Passkey Support**: Secure login without passwords.
- **Safe Key Handling**: Private keys never exposed to the global scope.

## 🚀 Quick Start

### Web
```bash
npm install
npm run dev
```
Open [http://localhost:3000](http://localhost:3000) in your browser.

### Android
Refer to the Gradle project in the `android/` directory.

## 🛠 Tech Stack

- **Frontend**: Next.js 14, React, Tailwind CSS
- **Core**: Rust (nurunuru-core), nostr-tools, rx-nostr
- **Native**: Kotlin (Android), Swift (iOS)

## 🤖 For AI Agents & Developers

Detailed technical documentation, architecture diagrams, and internal guidelines are located in **[AGENTS.md](./AGENTS.md)**. If you are an AI coding agent, please refer to that file for context.

---

## 📄 License

Most of this project is under the [Unlicense](LICENSE), with some components under MPL-2.0.

---

Made with Nostr
