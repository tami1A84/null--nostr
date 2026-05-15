# Session 12: CHANGELOG 統合 + リリース準備

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
git checkout -b sync/web-to-native-20260516/s12-<topic>
```

## 目的

`sync/web-to-native-20260516` ブランチを `main` へ向けてマージ可能な状態に整える。CHANGELOG / バージョン番号 / マトリクス / リリースアーティファクトの最終化を行う。

## タスク

### 1. CHANGELOG.md

- 新セクション `## [1.5.0] - 2026-MM-DD` を追加
- Session 3〜10 で記録された個別エントリを Android / iOS 別に整理
- 例:

```md
## [1.5.0] - 2026-MM-DD

### Added (Android)
- ElevenLabs STT による投稿・トーク音声入力 (Web v2.x 同期)
- 誕生日通知・相互フォロー Zap 通知 (Web v2.x 同期)

### Added (iOS)
- 同上

### Changed (Android)
- リアクションピッカーから Unicode 既定リアクションを削除 (カスタム絵文字のみ)
- Recommended フィードでアイコン無しユーザを除外 (品質向上)
- ホーム起動時に Following を優先表示し Recommended を後追いロード
- ミニアプリタブのカテゴリ・順序を Web と統一

### Changed (iOS)
- 同上

### Fixed
- (Session 8 の判定結果に応じて記載)
```

### 2. バージョン番号

- Android: `android/app/build.gradle.kts` の `versionCode` / `versionName`
- iOS: `ios/project.yml` (CFBundleShortVersionString / CFBundleVersion)
- Web: `package.json` の `version`

### 3. リリースアーティファクト (任意 / 必要時のみ)

- `cd android && ./gradlew assembleRelease`
- 成果物 `nurunuru-1.5.0-arm64-v8a.apk` を repo ルート + `release-artifacts/` に配置
- iOS は TestFlight 経由 (`xcodebuild archive` → App Store Connect)
- zapstore: `~/go/bin/zsp publish` (TTY 必須)

### 4. PR 作成

- `sync/web-to-native-20260516` を `main` へ向けて PR
- description テンプレ:

```md
# Web → Native 同期 v1.5.0

このブランチは Web v1.4.x で先行実装された機能を Android / iOS にバックポートします。

## 内容
- Session 3: Reaction picker (Unicode 削除)
- Session 4: Recommendation 改善
- ...

## DoD
- [x] design-tokens sync
- [x] Android assembleDebug
- [x] iOS xcodebuild
- [x] vitest

## 同期マトリクス
docs/sync/STATUS.md 参照
```

### 5. STATUS.md 最終化

- 全行のチェック状態を確定
- "次回 (v1.6) に持ち越し" 項目があれば末尾に列挙

## Acceptance Criteria

- [ ] CHANGELOG に v1.5.0 セクションが追加されている
- [ ] 3 プラットフォームのバージョン番号が一致
- [ ] PR が main へ作成されている
- [ ] STATUS.md が完了状態

## 落とし穴

- iOS の TestFlight ビルドは Xcode (TTY) 必須なので CLI で完結しない箇所あり
- zapstore publish も TTY 必須
- AGENTS.md "Publishing" 節を再確認

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
