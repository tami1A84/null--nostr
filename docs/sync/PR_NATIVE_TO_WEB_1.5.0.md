# Native → Web 同期 v1.5.0

このブランチは Native (Android v1.4.9 / iOS 1.0.4) の先行実装を Web に同期します。
例外として音声入力は Web 先行のため、Native 側へ OS 標準 STT (Android SpeechRecognizer / iOS SFSpeechRecognizer + AVAudioEngine) で部分同期しました。Native の ElevenLabs Scribe streaming 完全統合は v1.6 へ持ち越します。

## 内容

| Session | 方向 | 主な変更 |
|---|---|---|
| S3 Reaction picker | Native → Web | `components/ReactionEmojiPicker.js` から Unicode 既定リアクション quick row を削除しカスタム絵文字中心へ |
| S4 Recommendation | Native → Web | `lib/recommendation.js` / `components/HomeTab.js` でアイコン/表示名なしユーザーを除外、Following 優先 + Recommended 背景ロード |
| S5 通知 | Native → Web | `components/NotificationModal.js` に誕生日、相互フォロー Zap、カスタム絵文字リアクション通知を追加 |
| S6 MiniApp タブ | Native → Web | `components/MiniAppTab.js` をエンタメ/ツール分類と Native 順序へ整理 |
| S7 SignUp UX | Native → Web (+ iOS 補完) | `components/SignUpModal.js` に手動リージョン選択、推奨リレー自動セット、geohash/NIP-65 公開を追加。iOS は `RelayDiscovery.swift` 既存実装で補完 |
| S8 connection-manager | 調査結論 | Web v1.4.8 系の修正は Web 固有と判定。Rust core / Native への追加反映は不要 |
| S9 ProofMode / Divine | Android → Web (iOS 対象外) | `lib/proofmode.js` と `components/DivineVideoRecorder.js` を新規追加し 6.3 秒ループ動画 + verified_web タグへ同期 |
| S10A–D 音声入力 | **Web → Native (反転)** | Android `PostModal.kt` / iOS `PostSheet.swift`, `QuoteRepostSheet.swift` に OS 標準 STT を統合。権限拒否/利用不可時の日本語エラーメッセージを統一 |
| S11 FFI / tokens / build | 全 OS | Rust FFI 変更ゼロにつき再ビルド不要。`design-tokens/constants.json` に `CACHE_CONFIG.durations.notification` を追加し Android `Constants.CacheDuration.NOTIFICATION` を source-of-truth から生成 |
| S12 リリース準備 | 全 OS | CHANGELOG v1.5.0、Web/Android/iOS バージョン bump、STATUS finalization、PR body 整備、@noble/hashes v2.0.1 subpath import を `.js` 付与で修正、Next.js `outputFileTracingRoot` 明示 |

## バージョン

| Platform | 旧 | 新 |
|---|---|---|
| Web (`package.json`) | 1.0.0 | **1.5.0** |
| Android (`versionCode` / `versionName`) | 22 / 1.4.9 | **23 / 1.5.0** |
| iOS (`MARKETING_VERSION` / `CURRENT_PROJECT_VERSION`) | 1.0.4 / 5 | **1.5.0 / 6** |

`ios/project.yml`、`ios/NuruNuru/Info.plist`、`ios/NuruNuru.xcodeproj/project.pbxproj` の 3 箇所を同期 (xcodegen 未実行環境のため pbxproj も手動更新)。

## DoD

- [x] CHANGELOG.md に `## [1.5.0] - 2026-05-17` セクション追加
- [x] Web / Android / iOS バージョン番号一致
- [x] STATUS.md finalization
- [x] `npm run tokens:check` — PASS
- [x] `npm run test` — PASS (7 files / 189 passed / 7 skipped)
- [x] `npm run build` — PASS (Next.js 15.5.14)
- [x] `cd android && ./gradlew assembleDebug` — PASS
- [x] `cd ios && xcodebuild -scheme NuruNuru -destination 'platform=iOS Simulator,name=iPhone 17' -skipPackagePluginValidation build` — PASS

検証ログ: `/tmp/null-nostr-s12-logs/{npm_test,npm_build,android_assembleDebug,ios_build}.log`

## v1.6 持ち越し

- Native ElevenLabs Scribe streaming STT サービス化 (`ElevenLabsSttService.kt` / `.swift`、API キー Keychain/EncryptedSharedPreferences、401/429/timeout、自動再接続、Talk 入力欄統合)
- Web 版 NIP-EE (MLS) Talk 移植の再調査
- `docs/sync/research/INDEX.md` の正式 sign-off と r03-r10 TODO レポート清書
- 実機スモーク (Birthday/Zap 通知、Lightning 受信、SignUp geohash、Web ProofMode 録画、Native STT)
- TestFlight / zapstore publish / GitHub Release の配布アーティファクト

## 同期マトリクス

`docs/sync/STATUS.md` 参照。
