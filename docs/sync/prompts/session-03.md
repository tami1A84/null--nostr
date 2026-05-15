# Session 03: Reaction picker: Unicode quick reaction 削除

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
git checkout -b sync/web-to-native-20260516/s03-<topic>
```

## 目的

Web で `35c7cc0 feat: remove quick Unicode emoji reactions from picker` (2026-02-23) の挙動を Android / iOS のリアクションピッカーに反映する。

## 前提読み物

- `docs/sync/research/r03-reaction-picker.md` (Session 2 の成果物)
- AGENTS.md "Compose performance" / "AnimatedVisibility inside Box inside Column"

## Web 仕様 (要点)

- Reaction picker から Unicode のクイックリアクション (👍 / ❤️ / 🎉 等の固定行) を削除
- カスタム絵文字のみを表示
- ロング/シングルタップの挙動・絵文字キャッシュ (`fetchAndCacheEmojis`) は維持

## Android タスク

- `android/app/src/main/kotlin/io/nurunuru/app/ui/components/ReactionEmojiPicker.kt`
  - クイック Unicode リアクション行を削除 (恒久的に消す前にコメントで残しても可)
  - カスタム絵文字を最初に表示するレイアウトに調整
  - `EmojiPickerCache` (`EmojiPicker.kt` 定義) は **共通利用** であることを保つ
- `android/app/src/main/kotlin/io/nurunuru/app/data/prefs/AppPreferences.kt` に "Unicode quick reactions" スイッチがあれば削除 (or 非表示)

## iOS タスク

- iOS のリアクションピッカー実装ファイルを特定 (`grep -r "ReactionPicker\|EmojiReaction" ios/NuruNuru`)
- 同様に Unicode quick row を削除
- カスタム絵文字のみを表示

## Acceptance Criteria

- [ ] Android / iOS 双方で reaction picker を開いたときに Unicode クイック行が消えている
- [ ] カスタム絵文字を選んだときの送信 (`["+", emoji_shortcode]` Kind 7) が正常動作
- [ ] 既存の "自分の絵文字を最優先表示" 仕様 (Android v1.3.9) を破壊していない
- [ ] スクショ 2 枚 (Android / iOS) を `docs/sync/screenshots/s03-*.png` に保存

## 落とし穴

- Android: ピッカーのレイアウトが `LazyVerticalGrid` の columns 数で詰まる場合あり。tablet 幅を確認
- iOS: `@Observable` ViewModel の単一プロパティ更新で grid が再描画されることを確認

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
