# Session 01: キックオフ + ステータス初期化

> このプロンプトは null--nostr の **Native → Web 同期** ワークフローの一部です。
> 親ブランチ: `sync/native-to-web-20260516`
> 設計書: `docs/sync/DESIGN.md` / プラン: `docs/sync/PLAN.md` / 進捗: `docs/sync/STATUS.md`
> 統合チェック: `docs/sync/CHECKLIST.md` / テスト: `docs/sync/TESTING.md`

## 前提 (必読)

- リポジトリルート: `/Users/miharashouhei/null--nostr`
- 親ブランチ `sync/native-to-web-20260516` がチェックアウトされていること
- AGENTS.md の制約を厳守: 投稿140文字 / Keychain / actor / LineSeedJP / Compose の crash パターン
- 同期は **逐語コピーではない** ─ Web のイディオム (React + Tailwind + nostr-tools) で再現する
- iOS は NIP-46 のみ (Amber 不可) / iOS は Rokunana を App Store 審査で除外中 (Session 9 注意)
- **方向**: Native (Android/iOS) が source of truth、Web を追従させる (S10 系のみ Web → Native)

## 作業ブランチを切る

```bash
git checkout sync/native-to-web-20260516
git pull --ff-only
git checkout -b sync/native-to-web-20260516/s01-<topic>
```


## 目的

Native → Web 同期作業の起点。STATUS.md 初期化、design-tokens 同期確認、3 プラットフォームともローカルでビルド可能なことを確認する。

## タスク

1. ブランチ確認: `git status` で `sync/native-to-web-20260516` にいることを確認
2. `docs/sync/STATUS.md` を読み、内容に合意できるか確認 (担当者列に名前を記入)
3. `design-tokens` 同期チェック: `npm run tokens:check` がグリーンであることを確認
4. Web ビルド確認: `npm run build` がエラー無く通ること
5. Web テスト: `npm run test` がグリーン
6. Android ビルド: `cd android && ./gradlew assembleDebug`
7. iOS ビルド: `cd ios && xcodebuild -scheme NuruNuru -destination 'platform=iOS Simulator,name=iPhone 17' -skipPackagePluginValidation build`
8. ベースライン commit: 現状の build 成果物バージョンを STATUS.md にメモ (Web v1.0.0 / Android v1.4.9 / iOS 1.0.4 build 5)

## Acceptance Criteria

- [ ] 全 4 ビルド/テストがグリーン
- [ ] STATUS.md に "ベースライン取得日: 2026-MM-DD" を追記
- [ ] 既知の壊れたビルド・テストは "ブロッカー" 節に列挙

## 注意

- ビルドが壊れていた場合、本セッションでは **修正しない** (新規ブランチ + 別 PR で対応)
- ローカル `local.properties` (Android SDK パス) や `ios/build*` キャッシュが原因の場合がある

## 完了処理

1. `docs/sync/STATUS.md` の該当行をチェック (Web / Android / iOS それぞれ)
2. `CHANGELOG.md` に **(Web)** / **(Android)** / **(iOS)** タグ付きで 1 行追加
3. ビルド確認:
   - Web: `npm run test && npm run build`
   - Android (変更時): `cd android && ./gradlew assembleDebug`
   - iOS (変更時): `cd ios && xcodebuild -scheme NuruNuru -destination 'platform=iOS Simulator,name=iPhone 17' -skipPackagePluginValidation build`
4. `git commit -m "sync(s<NN>): <topic> — Native→Web"`
5. サブブランチを親へ PR (template: `docs/sync/PR_TEMPLATE.md`)

## 質問テンプレ (実装中に詰まったら)

- 「Native の `<file>:<line>` の挙動が分からない」→ Android/iOS のソースを直接読む。`grep -r "FunctionName" android/app ios/NuruNuru`
- 「Web で同等ロジックをどこに置くか」→ `docs/sync/DESIGN.md §6 ファイル対応マッピング` を参照
- 「Compose で `AnimatedVisibility` を使うと crash する」→ AGENTS.md の "AnimatedVisibility inside Box inside Column" 節 (Native 側で確認時)
- 「iOS で `@StateObject` を使ってよいか」→ NG。iOS 17 `@Observable` を使う
