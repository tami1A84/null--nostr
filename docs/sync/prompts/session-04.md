# Session 04: Recommendation: アイコン無しユーザ除外 + Following 優先ロード

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
git checkout -b sync/native-to-web-20260516/s04-<topic>
```


## 目的

Native の以下 2 仕様を Web に反映:

1. アイコン/表示名無しユーザーを Recommended から除外 (`RecommendationEngine.kt` / `NostrRepository+Recommendation.swift`)
2. Following タブを優先ロード → Recommended は背景ロード (両 OS の `TimelineViewModel`)

## 前提読み物

- `docs/sync/research/r04-recommendation.md` (Session 2)
- Native (Android): `data/RecommendationEngine.kt`, `viewmodel/TimelineViewModel.kt`
- Native (iOS): `Data/RecommendationConfig.swift`, `Data/NostrRepository+Recommendation.swift`, `ViewModels/TimelineViewModel.swift`
- Web: `lib/recommendation.js`, `components/TimelineTab.js`

## Native 仕様 (要点)

1. **アイコン無し / 表示名無しユーザの投稿を recommended から除外**
   - `metadata.picture` が空 OR null OR 無効 URL → 除外
   - `metadata.name` AND `metadata.display_name` が両方空 → 除外
2. **Following タブを優先表示し、Recommended はバックグラウンドで取得**
   - 起動直後: Following のみフェッチ → 先に表示
   - その後 Recommended をバックグラウンドで取得 → タブ切替時に即時表示

## Web タスク

- `lib/recommendation.js`:
  - フィルタ関数 (例: `filterRecommendedAuthors`) に `!metadata.picture || metadata.picture.trim() === ''` / `(name + display_name).trim() === ''` ガードを追加
  - Native と同じ fixture (`docs/sync/fixtures/recommendation/icon-name-filter.json`) でテスト
- `components/TimelineTab.js`:
  - 初期 useEffect は Following のみフェッチ
  - Following 描画後に `setTimeout(() => loadRecommended(), 0)` または直接 background fetch
  - タブ切替時に Recommended が空ならローディング表示

## Native タスク

- 原則 **変更不要** (Native が source of truth)

## Acceptance Criteria

- [ ] Web の Recommended にアイコン無しユーザの投稿が出ない
- [ ] アプリ起動 1 秒以内に Following が描画開始
- [ ] Following 描画後 5 秒以内に Recommended が裏で読み込み完了
- [ ] `vitest src/__tests__/recommendation.test.ts` (拡張) がグリーン

## 落とし穴

- Web: SSR と CSR で `metadata` オブジェクトの取得タイミングが違うため、初回レンダリング時の null 安全に注意
- Web: `useEffect` の依存配列で `pubkey` 配列が ref 比較で再フェッチを誘発しないよう、`pubkey.join(',')` キャッシュ

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
