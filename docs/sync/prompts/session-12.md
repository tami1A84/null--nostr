# Session 12: CHANGELOG 統合 + リリース準備

> このプロンプトは null--nostr の **Native → Web 同期** ワークフローの一部です。
> 親ブランチ: `sync/native-to-web-20260516`
> 設計書: `docs/sync/DESIGN.md` / プラン: `docs/sync/PLAN.md` / 進捗: `docs/sync/STATUS.md`

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
git checkout -b sync/native-to-web-20260516/s12-<topic>
```


## 目的

`sync/native-to-web-20260516` ブランチを `main` へ向けてマージ可能な状態に整える。CHANGELOG / バージョン番号 / マトリクス / リリースアーティファクトの最終化を行う。

## タスク

### 1. CHANGELOG.md

- 新セクション `## [1.5.0] - 2026-MM-DD` を追加
- Session 3〜10 で記録された個別エントリを Web / Android / iOS 別に整理
- 例:

```md
## [1.5.0] - 2026-MM-DD

### Added (Web)
- Recommended フィードでアイコン無しユーザを除外 (Native 仕様と一致)
- ホーム起動時に Following を優先表示し Recommended を後追いロード
- 誕生日通知・相互フォロー Zap 通知 (Native v1.4.x 同期)
- ミニアプリタブのカテゴリ・順序を Native と統一
- SignUp に手動リージョン選択 + 推奨リレー自動セット
- ProofMode + 6.3s ループ動画 (Web 新規実装、Android v1.4.x 同期)

### Added (Android) / (iOS)
- ElevenLabs STT (音声入力) を投稿/トーク入力欄に追加 (Web 同期)

### Changed (Web)
- リアクションピッカーから Unicode 既定リアクションを削除 (Native と一致)

### Changed (Android) / (iOS)
- STT のエラー/権限 UX を統一

### Fixed
- (Session 8 の判定結果に応じて記載)
```

### 2. バージョン番号

- Web: `package.json` の `version` を v1.5.0 に上げる
- Android: `android/app/build.gradle.kts` の `versionCode` / `versionName` (v1.5.0 = Native 主導の同期版)
- iOS: `ios/project.yml` (CFBundleShortVersionString / CFBundleVersion)

### 3. リリースアーティファクト (任意 / 必要時のみ)

- Web: Vercel deploy または `npm run build` 成果物
- `cd android && ./gradlew assembleRelease` (Android STT 入った時のみ)
- 成果物 `nurunuru-1.5.0-arm64-v8a.apk` を repo ルート + `release-artifacts/` に配置
- iOS は TestFlight 経由 (`xcodebuild archive` → App Store Connect)
- zapstore: `~/go/bin/zsp publish` (TTY 必須)

### 4. PR 作成

- `sync/native-to-web-20260516` を `main` へ向けて PR
- description テンプレ:

```md
# Native → Web 同期 v1.5.0

このブランチは Native (Android v1.4.9 / iOS 1.0.4) の先行実装を Web に同期します。
例外として ElevenLabs STT のみ Web → Native の方向で Native 側に追加実装しました。

## 内容
- Session 3: Reaction picker (Native と仕様一致, Web 修正)
- Session 4: Recommendation 改善 (Web 修正)
- Session 5: Birthday/Mutual Zap 通知 (Web 修正 + iOS 補完)
- Session 6: MiniApp タブ統一 (Web 修正)
- Session 7: SignUp UX (Web + iOS 新設)
- Session 8: connection-manager 調査結論
- Session 9: ProofMode (Web 新規, Android 同期)
- Session 10A-D: ElevenLabs STT (Native 新規, Web 同期)

## DoD
- [x] design-tokens sync
- [x] vitest + npm run build
- [x] Android assembleDebug
- [x] iOS xcodebuild

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

1. `docs/sync/STATUS.md` の該当行をチェック (Web / Android / iOS それぞれ)
2. `CHANGELOG.md` に **(Web)** / **(Android)** / **(iOS)** タグ付きで 1 行追加
3. ビルド確認:
   - Web: `npm run test && npm run build`
   - Android: `cd android && ./gradlew assembleDebug`
   - iOS: `cd ios && xcodebuild -scheme NuruNuru -destination 'platform=iOS Simulator,name=iPhone 17' -skipPackagePluginValidation build`
4. `git commit -m "sync(s12): CHANGELOG + release prep — Native→Web"`
5. サブブランチを親へ PR
