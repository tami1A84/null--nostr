# Session 04: Recommendation: アイコン無しユーザ除外 + Following 優先ロード

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
git checkout -b sync/web-to-native-20260516/s04-<topic>
```

## 目的

Web の以下 2 commit を Android / iOS に反映:

- `e75003d feat: filter out users without icons or names from recommended feed` (2026-03-02)
- `5510a50 Prioritize Following feed and background load Recommended` (2026-02-24)

## 前提読み物

- `docs/sync/research/r04-recommendation.md` (Session 2)
- `lib/recommendation.js` (708 行) の "Negative signals" / "Author quality signals" 節
- Android: `data/RecommendationEngine.kt`, `viewmodel/TimelineViewModel.kt`
- iOS: `Data/RecommendationConfig.swift`, `Data/NostrRepository+Recommendation.swift`, `ViewModels/TimelineViewModel.swift`

## Web 仕様 (要点)

1. **アイコン無し / 表示名無しユーザの投稿を recommended から除外**
   - `metadata.picture` が空 OR null OR 無効 URL → 除外
   - `metadata.name` AND `metadata.display_name` が両方空 → 除外
2. **Following タブを優先表示し、Recommended はバックグラウンドで取得**
   - 起動直後: Following のみフェッチ → 先に表示
   - その後 Recommended をバックグラウンドで取得 → タブ切替時に即時表示

## Android タスク

- `RecommendationEngine.kt` のフィルタ関数に `metadata.picture.isNullOrBlank()` / `(name + display_name).isBlank()` ガードを追加
- `TimelineViewModel.kt` を修正:
  - 初期ロードは Following のみ
  - Following ロード完了後に `viewModelScope.launch(Dispatchers.IO) { loadRecommended() }` で後追い
  - タブ切替時に Recommended が空ならローディング表示
- `enrichPosts()` のキャッシュ整合性を破壊しない

## iOS タスク

- `NostrRepository+Recommendation.swift` に同等フィルタを追加
- `TimelineViewModel.swift` を `@Observable` のままで以下のように:
  ```swift
  @MainActor func bootstrap() async {
      await loadFollowing()
      Task.detached(priority: .background) { [weak self] in
          await self?.loadRecommended()
      }
  }
  ```
- actor `NostrRepository` への呼び出しは必ず `await`

## Acceptance Criteria

- [ ] Recommended にアイコン無しユーザの投稿が出ない (テストアカウントで確認)
- [ ] アプリ起動 1 秒以内に Following が描画開始
- [ ] Following 描画後 5 秒以内に Recommended が裏で読み込み完了
- [ ] Web の `vitest src/__tests__/recommendation.test.ts` 相当の Android JUnit / Xcode test を 1 ケース追加

## 落とし穴

- Android: `Dispatchers.IO` を忘れると ANR (AGENTS.md "IO operations" 節)
- iOS: `Task.detached` 内から ViewModel のプロパティに書く時は `@MainActor` 経由 / `await MainActor.run { ... }`

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
