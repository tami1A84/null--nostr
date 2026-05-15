# Session 03: Reaction picker: Native 仕様に Web を寄せる

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
git checkout -b sync/native-to-web-20260516/s03-<topic>
```


## 目的

Native (`ReactionEmojiPicker.kt` + iOS 同等) で既に確立されている「Unicode クイックリアクション削除 / カスタム絵文字のみ表示」の仕様を Web の `components/ReactionEmojiPicker.js` に反映する。

## 前提読み物

- `docs/sync/research/r03-reaction-picker.md` (Session 2 の成果物)
- Native 実装: `android/app/src/main/kotlin/io/nurunuru/app/ui/components/ReactionEmojiPicker.kt`
- Web 対象: `components/ReactionEmojiPicker.js`

## Native 仕様 (要点)

- Reaction picker から Unicode のクイックリアクション (👍 / ❤️ / 🎉 等の固定行) を削除
- カスタム絵文字のみを表示
- ロング/シングルタップの挙動・絵文字キャッシュ (`fetchAndCacheEmojis`) は維持
- 自分の絵文字 (NIP-30) を最優先表示

## Web タスク

- `components/ReactionEmojiPicker.js`:
  - クイック Unicode リアクション行を削除
  - カスタム絵文字を最初に表示するレイアウトに調整
  - 絵文字キャッシュロジックは Web 既存実装を維持
- `lib/cache.js` または相当箇所のリアクション関連設定を確認
- 既存テスト `src/__tests__/components/ReactionEmojiPicker.test.tsx` (なければ新設) を更新

## Native タスク

- 原則 **変更不要** (Native が source of truth)
- 万一 Native 側に未対応の小修正があれば本セッションの範囲外として記録 (別セッションで対応)

## Acceptance Criteria

- [ ] Web の reaction picker を開いたときに Unicode クイック行が消えている
- [ ] カスタム絵文字を選んだときの送信 (`["+", emoji_shortcode]` Kind 7) が正常動作
- [ ] 既存の "自分の絵文字を最優先表示" 仕様を破壊していない
- [ ] スクショ 3 枚 (Web before / Web after / Native 参照) を `docs/sync/screenshots/s03-*.png` に保存
- [ ] `npm run test` グリーン

## 落とし穴

- Web: localStorage 旧データに quick reaction 配列が残っている可能性 → クリア時の挙動を確認
- Web: SSR で window/localStorage を触ると hydration mismatch → `useEffect` 内に閉じ込める

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
