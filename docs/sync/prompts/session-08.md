# Session 08: connection-manager v1.4.8 修正の Rust 反映調査

> このプロンプトは null--nostr の **Web → Native 同期** ワークフローの一部です。
> 親ブランチ: `sync/web-to-native-20260516`
> 設計書: `docs/sync/DESIGN.md` / プラン: `docs/sync/PLAN.md` / 進捗: `docs/sync/STATUS.md`

## 前提 (必読)

- リポジトリルート: `/Users/miharashouhei/null--nostr`
- 親ブランチ `sync/web-to-native-20260516` がチェックアウトされていること
- AGENTS.md の制約を厳守: 投稿140文字 / Keychain / actor / LineSeedJP / Compose の crash パターン
- 同期は **逐語コピーではない** ─ 各プラットフォームのイディオムで再現する
- iOS は NIP-46 のみ (Amber 不可) / iOS は Rokunana を App Store 審査で除外中 (Session 9 注意)

## 作業ブランチを切る

```bash
git checkout sync/web-to-native-20260516
git pull --ff-only
git checkout -b sync/web-to-native-20260516/s08-<topic>
```

## 目的

Web の `c02bdb8 Release v1.4.8` (2026-05-14) で `lib/connection-manager.js` に入った修正内容を確認し、Rust core (`nurunuru-core/src/engine.rs`) の接続層に **同等の修正が必要かどうか** を判定する。

## 前提読み物

- `docs/sync/research/r08-connection-manager.md` (Session 2)
- AGENTS.md "Web Layer" 節 (4 global / 2 per-relay / 10 req/s)

## 調査タスク (実装より調査優先)

1. `git log -p lib/connection-manager.js | head -300` で v1.4.8 の差分を読む
2. 何のバグ・改善か特定 (再接続 / バックオフ / プール枯渇 / レート制限 / cooldown 等)
3. Rust core 該当箇所を探す:
   - `grep -n "Pool\|connect\|backoff\|cooldown\|rate" rust-engine/nurunuru-core/src/engine.rs`
   - 実態は nostr-sdk 0.44.x が担っているため、SDK バージョン更新で解決済の可能性も
4. 同等修正が必要なら **修正 PR の方針** を本セッションのレポートに書く (実装は別 PR)
5. 必要なし (Web 固有 / SDK 側で吸収) と判断したら、その旨を記録

## 出力

`docs/sync/research/r08-connection-manager.md` に以下を追記:

```md
## 結論
- [ ] Rust core 修正が必要 → 別ブランチで対応
- [ ] Rust core 修正は不要 (理由: ...)
- [ ] 部分的に必要 (どこ: ...)

## もし必要な場合の修正方針
...
```

## Acceptance Criteria

- [ ] 上記レポートが完成
- [ ] 必要な場合は新規 issue / TODO を `STATUS.md` のブロッカー欄に記録
- [ ] 不要な場合も "なぜ不要か" を 3 行で説明

## 注意

- 本セッションでは Rust コードを **変更しない** (調査のみ)
- 実装が必要になったら Session 11 か別セッションで対応

## 完了処理

1. `docs/sync/STATUS.md` の該当行をチェック (Android / iOS それぞれ)
2. `CHANGELOG.md` に **(Android)** / **(iOS)** タグ付きで 1 行追加
3. ビルド確認:
   - Android: `cd android && ./gradlew assembleDebug`
   - iOS: `cd ios && xcodebuild -scheme NuruNuru -destination 'platform=iOS Simulator,name=iPhone 17' -skipPackagePluginValidation build`
4. `git commit -m "sync(s<NN>): <topic> — Web→Native"`
5. サブブランチを親へ PR

## 質問テンプレ (実装中に詰まったら)

- 「Web の `<file>:<line>` の挙動が分からない」→ `git log -p` で当該変更の commit を読む
- 「Compose で `AnimatedVisibility` を使うと crash する」→ AGENTS.md の "AnimatedVisibility inside Box inside Column" 節
- 「iOS で `@StateObject` を使ってよいか」→ NG。iOS 17 `@Observable` を使う
