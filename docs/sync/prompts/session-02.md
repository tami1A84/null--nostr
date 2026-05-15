# Session 02: Native 差分調査 (各機能の Native 仕様確定)

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
git checkout -b sync/native-to-web-20260516/s02-<topic>
```


## 目的

Session 3〜10 の前提となる **Native (Android/iOS) 側の最終仕様** を確定する。実装はせず、各機能を 1 ページのレポートにまとめ `docs/sync/research/rNN-<topic>.md` に出力する。Native 実装と Web 現状の突合を行い、移植要否と方向を確定する。

## 対象機能 (1 機能 1 レポート)

| ID | レポートファイル | 対象 |
|---|---|---|
| R-03 | `research/r03-reaction-picker.md` | Native の Reaction picker 仕様 (Unicode quick row 削除済) と Web 現状の差 |
| R-04 | `research/r04-recommendation.md` | Native の `RecommendationEngine.kt` / iOS `NostrRepository+Recommendation` の "アイコン無し除外" + "Following 優先 → Recommended 後追い" と Web 現状 |
| R-05 | `research/r05-birthday-notif.md` | Native の誕生日 / 相互フォロー Zap 通知ロジックと Web 現状 |
| R-06 | `research/r06-miniapp-tab.md` | Native の `SettingsScreen.kt` / `SettingsView.swift` のカテゴリ・順序・各 mini-app への遷移と Web 現状 |
| R-07 | `research/r07-signup.md` | Native の `SignUpModal.kt` / iOS SignUp 動線、`GeohashUtils.kt` の利用と Web 現状 |
| R-08 | `research/r08-connection-manager.md` | Web の `lib/connection-manager.js` v1.4.8 修正内容と、Rust core / Native 側に同等修正が必要かの判定 |
| R-09 | `research/r09-divine-proofmode.md` | Android の `DivineVideoRecorder.kt` + `ProofModeManager.kt` 仕様 (iOS は対象外)、Web に同等実装があるかの確認 |
| R-10 | `research/r10-elevenlabs-stt.md` | **方向反転**: Web 先行の `hooks/useSTT.js` + 各 `*Tab.js` 統合と、Native 側の現状 (Android `ElevenLabsSettings` あり/STT 未, iOS TTS のみ) |

## レポート 1 件あたりのテンプレ

```md
# R-NN: <機能名> Native 仕様レポート

## 1. ファイル
- Native (Android): android/app/.../...
- Native (iOS): ios/NuruNuru/...
- Web 現状: lib/foo.js, components/Bar.js
- 関連 commit/version: <hash> または Android v1.4.X, iOS 1.0.4

## 2. 振る舞いまとめ (3-5 行)

## 3. 状態モデル / データフロー

## 4. UI/UX 詳細 (該当する場合)

## 5. Web 現状とのギャップ

## 6. 移植時の論点
- データ層: ...
- UI 層: ...

## 7. テストすべき観点
```

## Acceptance Criteria

- [ ] 8 件のレポートが `docs/sync/research/` に存在
- [ ] 各レポートが上記テンプレを満たす
- [ ] 各レポートに Native 実装のファイル参照が最低 1 つある
- [ ] レポート末尾に "結論: 移植する / 部分移植 / 移植不要 / 方向反転 (Web → Native)" を明記
- [ ] `research/INDEX.md` の該当行に結論を転記し、sign-off 欄を埋める

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
