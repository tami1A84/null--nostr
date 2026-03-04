# ぬるぬる (null--nostr)

**LINE-like Nostr Client** — Simple, cute, and for everyone.
**LINE風のNostrクライアント** — シンプルで可愛い、みんなのためのNostr

---

## 🌟 Overview / 概要

null--nostr is a user-friendly Nostr client designed with a familiar chat-like interface. It focuses on performance, security, and a pleasant user experience.

「ぬるぬる」は、親しみやすいチャット風インターフェースを備えた、ユーザーフレンドリーなNostrクライアントです。パフォーマンス、セキュリティ、そして心地よいユーザー体験に重点を置いて開発されています。

---

## ✨ Features / 特徴

### 📱 Timeline / タイムライン
- **Mixed Feed**: Smart recommendation algorithm inspired by modern social media.
- **Birdwatch**: Community-driven context and labeling (NIP-32).
- **Rich Media**: Supports images, custom emojis, and short loop videos (Divine).
- **おすすめ & フォロー**: アルゴリズムによる推奨フィードとフォロー中の投稿。
- **Birdwatch**: コミュニティによるコンテキスト追加と評価。

### 💬 Talk / トーク
- **Secure DMs**: Encrypted private messaging (NIP-17/44/59).
- **Chat Interface**: Familiar and easy-to-use design.
- **安全なDM**: 暗号化されたプライベートメッセージ。
- **チャット画面**: 使い慣れた操作感のデザイン。

### 🔒 Security / セキュリティ
- **Passkey Support**: Secure login without passwords.
- **Safe Key Handling**: Private keys never exposed to the global scope.
- **パスキー対応**: パスワード不要の安全なログイン。
- **セキュアな鍵管理**: 秘密鍵は安全に保護され、外部に露出しません。

---

## 🚀 Quick Start / クイックスタート

### Web
```bash
npm install
npm run dev
```
Open [http://localhost:3000](http://localhost:3000) in your browser.

### Android
Check the `android/` directory for the Gradle project.

---

## 🛠 Tech Stack / 技術スタック

- **Frontend**: Next.js 14, React, Tailwind CSS
- **Core**: Rust (nurunuru-core), nostr-tools, rx-nostr
- **Native**: Kotlin (Android), Swift (iOS)

---

## 🤖 For AI Agents & Developers

Detailed technical documentation, architecture diagrams, and internal guidelines are located in **[AGENTS.md](./AGENTS.md)**. If you are an AI coding agent, please refer to that file for context.

詳細な技術ドキュメント、アーキテクチャ、内部ガイドラインは **[AGENTS.md](./AGENTS.md)** に集約されています。開発者やAIエージェントの方は、そちらを参照してください。

---

## 📄 License

Most of this project is under the [Unlicense](LICENSE), with some components under MPL-2.0.
プロジェクトの大部分は Unlicense ですが、一部のコンポーネントは MPL-2.0 に基づいています。

---

Made with Nostr
