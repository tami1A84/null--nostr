# Session 06: MiniApp タブ構成・順序を Web と一致させる

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
git checkout -b sync/web-to-native-20260516/s06-<topic>
```

## 目的

Web の `6aa93b6` / `0a2b76f` (2026-02-23) の MiniApp タブ整理 (カテゴリ + フルスクリーンモーダル + 順序) を Android / iOS の "ミニアプリ" タブ・"設定" タブに反映する。

## 前提読み物

- `docs/sync/research/r06-miniapp-tab.md` (Session 2)
- Web: `components/MiniAppTab.js` (516 行), `components/miniapps/*`
- Android: `ui/screens/SettingsScreen.kt` (ミニアプリハブ。エンタメ/ツール/その他カテゴリ)
- iOS: `Views/Screens/SettingsView.swift` + `Views/MiniApps/*`

## Web 仕様 (要点)

1. カテゴリ: **エンタメ / ツール / その他** (Android と同じ概念のはず、順序を再確認)
2. 各 mini-app をフルスクリーンモーダルで開く (タブ移動ではない)
3. 順序 (Web 基準):
   - エンタメ: BadgeSettings, EmojiSettings, ZapSettings, EventBackupApp
   - ツール: RelaySettings, UploadSettings, MuteList, SchedulerApp, VanishRequest
   - その他: ElevenLabsSettings (≒ 設定エクストラ), プライバシーポリシーリンク, バージョン情報
4. 各 mini-app のヘッダ: 戻るアイコン + タイトル中央 + (右側 action 任意)

## Android タスク

- `SettingsScreen.kt` のカテゴリ・順序を Web と完全一致に
- 各 mini-app 画面を **フルスクリーンモーダル** として navigate (既に概ね実装済のはず、差分のみ調整)
- カテゴリ見出しのスタイルを Web と揃える (NuruTypography)

## iOS タスク

- `Views/Screens/SettingsView.swift` を Web と同順に
- 各 mini-app 画面は `.sheet` または `.fullScreenCover` (既存方針: ミニアプリは `.sheet` で OK、画像ビューアのみ `.fullScreenCover`)
- `Views/MiniApps/` 内の View 名・順序を統一

## Acceptance Criteria

- [ ] Web のミニアプリタブと Android / iOS のミニアプリタブで、カテゴリ名・並び・各カードの並びが完全に一致
- [ ] 各 mini-app への遷移が動作する
- [ ] スクショ 3 枚 (Web / Android / iOS) を並べて DoD 確認

## 落とし穴

- Android: `SettingsScreen.kt` 内にコラプシングヘッダー (v1.4.2) があるため、リスト挿入時に `LazyColumn` の sticky header の挙動を壊さない
- iOS: タブバー固定 (`.safeAreaInset(edge: .bottom)` 方式) を破壊しないこと

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
