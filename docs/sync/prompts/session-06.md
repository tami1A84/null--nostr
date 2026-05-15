# Session 06: MiniApp タブ構成・順序を Native と一致させる

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
git checkout -b sync/native-to-web-20260516/s06-<topic>
```


## 目的

Native (Android `SettingsScreen.kt` / iOS `SettingsView.swift` + `Views/MiniApps/`) で確立されている MiniApp タブのカテゴリ・順序・フルスクリーンモーダル方式を Web の `components/MiniAppTab.js` に反映する。

## 前提読み物

- `docs/sync/research/r06-miniapp-tab.md` (Session 2)
- Native (Android): `ui/screens/SettingsScreen.kt` (ミニアプリハブ。エンタメ/ツール/その他カテゴリ)
- Native (iOS): `Views/Screens/SettingsView.swift` + `Views/MiniApps/*`
- Web: `components/MiniAppTab.js`, `components/miniapps/*`

## Native 仕様 (要点)

1. カテゴリ: **エンタメ / ツール / その他**
2. 各 mini-app をフルスクリーンモーダルで開く (タブ移動ではない)
3. 順序 (Native 基準, Session 2 で確定):
   - エンタメ: BadgeSettings, EmojiSettings, ZapSettings, EventBackupApp
   - ツール: RelaySettings, UploadSettings, MuteList, SchedulerApp, VanishRequest
   - その他: ElevenLabsSettings (≒ 設定エクストラ), プライバシーポリシーリンク, バージョン情報
4. 各 mini-app のヘッダ: 戻るアイコン + タイトル中央 + (右側 action 任意)

## Web タスク

- `components/MiniAppTab.js` のカテゴリ・順序を Native と完全一致に
- 各 mini-app 画面を **フルスクリーンモーダル** として開く (`<dialog>` または React Portal で全画面)
- カテゴリ見出しのスタイルを Native と揃える (Tailwind class を Constants.kt の値と一致)
- 順序定数を `docs/sync/fixtures/miniapps/order.json` から読み込めるようにする (テスト共有)

## Native タスク

- 原則 **変更不要**

## Acceptance Criteria

- [ ] Web のミニアプリタブと Android / iOS のミニアプリタブで、カテゴリ名・並び・各カードの並びが完全に一致
- [ ] 各 mini-app への遷移が動作する
- [ ] スクショ 3 枚 (Web / Android / iOS) を並べて DoD 確認 (`docs/sync/screenshots/s06-*.png`)
- [ ] `vitest src/__tests__/miniapps.test.ts` (fixture と export order の比較) グリーン

## 落とし穴

- Web: フルスクリーンモーダル時のスクロール禁止 (`document.body.style.overflow = 'hidden'`) を忘れない
- Web: モーダル内 navigation の戻るボタンがブラウザバックと整合 (history.pushState)

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
