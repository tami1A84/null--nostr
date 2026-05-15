# Session 02: Web 差分調査 (各機能の Web 仕様確定)

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
git checkout -b sync/web-to-native-20260516/s02-<topic>
```

## 目的

Session 3〜10 の前提となる **Web 側の最終仕様** を確定する。実装はせず、各機能を 1 ページのレポートにまとめ `docs/sync/research/sNN-<topic>.md` に出力する。

## 対象機能 (1機能 1レポート)

| ID | レポートファイル | 対象 |
|---|---|---|
| R-03 | `research/r03-reaction-picker.md` | `components/ReactionEmojiPicker.js` の現在の挙動 (Unicode 既定リアクションが削除された経緯) |
| R-04 | `research/r04-recommendation.md` | `lib/recommendation.js` の "アイコン無し除外" + `components/TimelineTab.js` の "Following 優先 → Recommended 後追い" |
| R-05 | `research/r05-birthday-notif.md` | `components/NotificationModal.js` で誕生日 / 相互フォロー Zap 通知がどのように生成されるか |
| R-06 | `research/r06-miniapp-tab.md` | `components/MiniAppTab.js` のカテゴリ・順序・各 mini-app へのナビ動線 |
| R-07 | `research/r07-signup.md` | `components/SignUpModal.js` の手動リージョン選択 + リレー検出強化、`lib/geohash.js` の利用 |
| R-08 | `research/r08-connection-manager.md` | `lib/connection-manager.js` の v1.4.8 で何が変わったか (`git log -p lib/connection-manager.js | head -200`) |
| R-09 | `research/r09-divine-proofmode.md` | `components/DivineVideoRecorder.js` + `lib/proofmode.js` の現状仕様 |
| R-10 | `research/r10-elevenlabs-stt.md` | `hooks/useSTT.js` + 各 `*Tab.js` での音声入力統合 |

## レポート 1 件あたりのテンプレ

```md
# R-NN: <機能名> Web 仕様レポート

## 1. ファイル
- 主要ファイル: lib/foo.js, components/Bar.js
- 関連 commit: <hash> <hash>

## 2. 振る舞いまとめ (3-5 行)

## 3. 状態モデル / データフロー

## 4. UI/UX 詳細 (該当する場合)

## 5. 既知の制約・エッジケース

## 6. Native 移植時の論点
- Android: ...
- iOS: ...

## 7. テストすべき観点
```

## Acceptance Criteria

- [ ] 8 件のレポートが `docs/sync/research/` に存在
- [ ] 各レポートが上記テンプレを満たす
- [ ] 各レポートに 1 つ以上の commit hash 引用がある
- [ ] レポート末尾に "結論: 移植する / 部分移植 / 移植不要" を明記

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
