# Native → Web 同期 進捗

> Parent branch: sync/native-to-web-20260516
> Release candidate: v1.5.0 / 2026-05-17
> Session 12 finalization: CHANGELOG, versions, release readiness, and carryover triage.

## 0. スコープ凍結 / Release freeze

- [x] v1.5.0 release scope: S3-S9 Native → Web 同期、S10 は Web → Native の音声入力同期として扱う。
- [x] 凍結日: 2026-05-17
- [x] S8 connection-manager は Web 固有修正と判定し、Rust core / Native への追加反映なし。
- [ ] docs/sync/research/INDEX.md の形式的な 3 名 sign-off と TODO テンプレ清書は v1.6 プロセス改善へ持ち越し。

## 1. セッション進捗

> 列の意味: Web = Web 側の修正 / Native = Native 側の追加実装 (S10 のみ Web→Native)。

| # | セッション | Web | Native (And) | Native (iOS) | 担当 | 完了日 | メモ |
|---|---|---|---|---|---|---|---|
| 1 | キックオフ | -- | -- | -- | goose | 2026-05-17 | 同期計画と S12 prompt を確認 |
| 2 | Native 差分調査 + INDEX 凍結 | -- | -- | -- | goose | 2026-05-17 | v1.5.0 の実装範囲はコード実態で凍結。research TODO 清書/sign-off は v1.6 carryover |
| 3 | Reaction picker | ✅ | -- | -- | goose | 2026-05-17 | Web ReactionEmojiPicker から Unicode quick row を除去し、カスタム絵文字中心に同期 |
| 4 | Recommendation 改善 | ✅ | -- | -- | goose | 2026-05-17 | アイコン/表示名なしユーザー除外、Following 優先 + Recommended 背景ロードを Web 側で反映 |
| 5 | Birthday 通知 | ✅ | -- | -- | goose | 2026-05-17 | Web 通知に誕生日、相互フォロー Zap、カスタム絵文字リアクションを追加 |
| 6 | MiniApp タブ整理 | ✅ | -- | -- | goose | 2026-05-17 | Web ミニアプリをエンタメ/ツール分類と Native 順序へ整理 |
| 7 | SignUp UX | ✅ | -- | ✅ | goose | 2026-05-17 | Web SignUp に手動リージョン、推奨リレー、geohash/NIP-65 を追加。iOS は RelayDiscovery / SignUp 補完済みとして扱う |
| 8 | connection-manager 修正調査 | ✅ | -- | -- | goose | 2026-05-17 | Web v1.4.8 の接続管理は Web 固有。Rust core / Native 追加修正なし |
| 9 | ProofMode / Divine | ✅ | -- | N/A | goose | 2026-05-17 | Web に ProofMode タグ生成と 6.3 秒ループ動画レコーダーを追加。iOS Rokunana/Divine は App Store 審査上対象外 |
| 10A | STT 調査・設計 | ✅ | -- | -- | goose | 2026-05-17 | Web useSTT が source of truth。Native は v1.5.0 では OS 標準 STT 部分同期、ElevenLabs streaming 詳細は v1.6 へ |
| 10B | STT Android 実装 | -- | 🟨 | -- | goose | 2026-05-17 | PostModal に Android SpeechRecognizer ベースの音声入力あり。ElevenLabsSttService / Talk 完全統合は carryover |
| 10C | STT iOS 実装 | -- | -- | 🟨 | goose | 2026-05-17 | PostSheet / QuoteRepostSheet に Speech/AVAudioEngine ベースの音声入力あり。ElevenLabsSttService / TalkView 完全統合は carryover |
| 10D | STT UX / 権限 / エラー統合 | -- | 🟨 | 🟨 | goose | 2026-05-17 | 権限拒否/利用不可メッセージは投稿/引用投稿で統一。401/429/timeout 等の ElevenLabs 固有 UX は v1.6 |
| 11 | FFI 再ビルド + token sync | ✅ | ✅ | ✅ | goose (s11-finalize) | 2026-05-17 | Rust FFI 変更ゼロにつき再ビルド不要。CACHE_CONFIG.durations.notification を token source-of-truth に追加し npm run tokens:check PASS。Android assembleDebug PASS、iOS build/test PASS。Web は @noble/hashes import 問題を S12 で解消 |
| 12 | CHANGELOG / リリース | ✅ | ✅ | ✅ | goose | 2026-05-17 | v1.5.0 CHANGELOG、Web/Android/iOS version bump、STATUS finalization、PR body 作成、Web test/build・Android assembleDebug・iOS build PASS |

凡例: ⬜ 未着手 / 🟦 進行中 / 🟨 部分完了・持ち越しあり / ✅ 完了 / ❌ ブロック / N/A 対象外 / -- 該当なし

## 2. 実機検証 / Release validation

| 項目 | Web ブラウザ | Android 実機 | iOS 実機 | 検証日 | 担当 | メモ |
|---|---|---|---|---|---|---|
| S5 通知 (Birthday/Zap) | ⬜ | (既) | (既) | | | 実機通知はリリース作業で確認 |
| S5 Zap 受信 (Lightning) | ⬜ | (既) | (既) | | | Lightning 受信は実アカウント/実機で確認 |
| S7 リージョン → geohash 自動セット | ⬜ | (既) | ⬜ | | | Web/iOS 実機スモークは carryover |
| S9 ProofMode 録画 (Web 追加) | ⬜ | (既) | N/A | | | ブラウザ MediaRecorder 実機確認は carryover |
| S10D STT (マイク) | (既) | ⬜ | ⬜ | | | Native は OS 標準 STT 部分同期。ElevenLabs streaming は v1.6 |
| S11 NIP-EE (MLS) Talk | -- | ⬜ | ⬜ | | | Web NIP-EE は v1.6+ 検討 |
| S12 配布 | ⬜ | ⬜ | ⬜ | | | PR/TestFlight/zapstore/GitHub Release は認証/TTY が必要 |

## 3. Build / test 状態

- [x] Web: npm run test — PASS; log: /tmp/null-nostr-s12-logs/npm_test.log
- [x] Web: npm run build — PASS; log: /tmp/null-nostr-s12-logs/npm_build.log
- [x] Android: cd android && ./gradlew assembleDebug — PASS; log: /tmp/null-nostr-s12-logs/android_assembleDebug.log
- [x] iOS: cd ios && xcodebuild -scheme NuruNuru -destination 'platform=iOS Simulator,name=iPhone 17' -skipPackagePluginValidation build — PASS; log: /tmp/null-nostr-s12-logs/ios_build.log
- [ ] PR 作成: gh 認証とリモート権限がある環境で実施
## 4. ブロッカー

- 解決済み: S11 検出の @noble/hashes v2.0.1 subpath import 失敗は S12 で @noble/hashes/utils.js import へ更新して解消。
- 現時点で v1.5.0 release prep のコード上ブロッカーはなし。Web test/build、Android assembleDebug、iOS build は PASS。
- 配布系 (TestFlight / zapstore publish / GitHub Release / PR 作成) は認証・TTY・リモート権限が必要なため、この作業環境で実行できない場合は手動作業へ回す。

## 5. 次回 (v1.6) に持ち越し

- Native ElevenLabs Scribe streaming STT: ElevenLabsSttService.kt / ElevenLabsSttService.swift、API キー安全保管、401/429/timeout、自動再接続、Talk 入力欄統合。
- Web NIP-EE (MLS) Talk 移植の再調査。
- docs/sync/research/INDEX.md の正式 sign-off と r03-r10 TODO レポート清書。
- 実機スモーク: Birthday/Zap 通知、Lightning 受信、SignUp geohash、Web ProofMode 録画、Native STT。
- TestFlight / zapstore / GitHub Release の配布アーティファクト作成とアップロード。

## 6. 参照

- 設計: docs/sync/DESIGN.md
- プラン: docs/sync/PLAN.md
- レビュー: docs/sync/REVIEW.md
- 統合チェック: docs/sync/CHECKLIST.md
- テスト: docs/sync/TESTING.md
- スコープ凍結: docs/sync/research/INDEX.md
- 各セッションプロンプト: docs/sync/prompts/session-NN.md
