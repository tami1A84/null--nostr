# Web → Native 同期 統合チェックリスト

> Branch: `sync/web-to-native-20260516`
> 各セッション完了時およびマージ前に本ファイルの該当行を確認する。

---

## 1. 実機検証必須項目

シミュレータ / エミュレータでは検出できない項目。**これらを含むセッションは必ず実機検証**。

| セッション | 検証項目 | Android 実機 | iOS 実機 | 理由 |
|---|---|---|---|---|
| S5 | 通知 (Birthday / Zap mutual) のフォアグラウンド表示 | 必須 | 必須 | NotificationModal の 30s polling 挙動が emulator で不安定 |
| S5 | Zap (Lightning) を実 wallet で受信 | 必須 | 必須 | NWC / Alby 等の外部 wallet 動作確認 |
| S7 | NIP-05 / 位置情報からの geohash 推定 | 必須 | 必須 | 位置情報権限フローが実機固有 |
| S7 | Android: Amber (NIP-55) 経由のサインアップ署名 | **必須** | N/A | Amber は Android 実機のみ動作 |
| S7 | iOS: NIP-46 (Nostr Connect) 接続 | N/A | **必須** | リレー経由 NIP-46 hand-shake が sim でタイムアウトしがち |
| S9 | カメラ + ProofMode 録画 (Android) | **必須** | N/A | CameraX + 真正性メタデータ |
| S10 | ElevenLabs STT (マイク) | **必須** | **必須** | RECORD_AUDIO / NSMicrophoneUsageDescription + WS streaming |
| S10 | Bluetooth ヘッドセット切替 (iOS) | --- | 任意 | AVAudioSession ルーティング |
| S11 | NIP-EE (MLS) Talk 送受信 | 必須 | 必須 | UniFFI 経由の Rust 通信 + WhiteNoise interop |
| S12 | TestFlight / Play Internal 配布 | 任意 | 任意 | リリース前の最終疎通 |

> **App Store 審査リスク**: Session 9 (Rokunana / Divine 動画) は iOS では復活させないこと。CHANGELOG にも明記。

---

## 2. ファイル競合マトリクス

複数セッションが同一ファイルを触る場合は **直列化** する。並行実行する場合はサブブランチ分離 + 早期 rebase。

### 2.1 Android 競合ホットスポット

| ファイル | 触るセッション | 推奨順序 |
|---|---|---|
| `viewmodel/TimelineViewModel.kt` | S4 (Recommendation), S5 (通知バッジ反映) | **S4 → S5** |
| `data/NostrRepositoryNotifications.kt` | S5 (Birthday/Zap通知) | 単独 |
| `data/NostrRepositoryTimeline.kt` | S4 (Recommendation), S7 (relay 検出経路の見直し) | **S4 → S7** |
| `ui/screens/SettingsScreen.kt` | S6 (MiniApp 整理), S10 (ElevenLabs STT 設定) | **S6 → S10** |
| `ui/components/PostModal.kt` | S9 (Divine), S10 (STT マイク) | **S9 → S10** |
| `ui/components/NotificationModal.kt` | S5 (Birthday/Zap), 既存 v1.4.6 PullToRefresh fix | 単独 |
| `ui/components/SignUpModal.kt` | S7 (リージョン選択) | 単独 |
| `data/prefs/AppPreferences.kt` | S6, S7, S10 | **S6 → S7 → S10** (rebase 必須) |
| `data/RecommendationEngine.kt` | S4 (アイコン無し除外) | 単独 |
| `data/GeohashUtils.kt` | S7 (リージョン → prefix) | 単独 |
| `ui/components/ReactionEmojiPicker.kt` | S3 (Unicode 削除) | 単独 |
| `data/ProofModeManager.kt` / `DivineVideoRecorder.kt` | S9 | 単独 |

### 2.2 iOS 競合ホットスポット

| ファイル | 触るセッション | 推奨順序 |
|---|---|---|
| `ViewModels/TimelineViewModel.swift` | S4, S5 | **S4 → S5** |
| `Data/NostrRepository+Notifications.swift` | S5 | 単独 |
| `Data/NostrRepository+Recommendation.swift` | S4 | 単独 |
| `Views/Screens/SettingsView.swift` | S6, S10 | **S6 → S10** |
| `Views/Sheets/PostSheet.swift` | S10 (STT) | 単独 |
| `Views/Sheets/NotificationSheet.swift` | S5 | 単独 |
| `Views/Screens/LoginView.swift` (or 新 SignUpView) | S7 | 単独 |
| `Data/AppPreferences.swift` | S6, S7, S10 | **S6 → S7 → S10** (rebase 必須) |
| `Utilities/Constants.swift` (生成) | (S11 の `npm run tokens`) | S11 の最後に commit |

### 2.3 Rust / FFI

| ファイル | 触るセッション | 注意 |
|---|---|---|
| `rust-engine/nurunuru-core/src/engine.rs` | S8 (調査のみ) → 必要なら S11 直前 | S8 は read-only |
| `rust-engine/nurunuru-ffi/src/lib.rs` | S11 まで触らない | 触る場合は `.kt` / `.swift` 生成と `.so` / `.xcframework` ビルドを **同 commit** に含める |
| `rust-engine/nurunuru-ffi/bindgen/kotlin-out/uniffi/nurunuru/nurunuru.kt` | (生成物) | AGENTS.md 「Generated .kt must be committed」遵守 |

### 2.4 設計トークン

`design-tokens/constants.json` を変更したセッションは、PR 内で `npm run tokens` を必ず実行し、生成物 3 ファイル (`lib/constants.generated.js` / `Constants.kt` / `Constants.swift`) を **同 commit** に含める。

---

## 3. セッション横断 DoD

### 3.1 コード変更を含むセッション

- [ ] 該当 `prompts/session-NN.md` の Acceptance Criteria を全て満たす
- [ ] AGENTS.md の制約違反なし (140 char / Keychain / actor / @Observable / LineSeedJP / Compose crash パターン)
- [ ] `design-tokens/constants.json` を変更した場合、`npm run tokens:check` グリーン
- [ ] Web 側に変更がある場合、`npm run test` グリーン
- [ ] Android 変更時: `cd android && ./gradlew assembleDebug` グリーン
- [ ] iOS 変更時: `xcodebuild -scheme NuruNuru -destination 'platform=iOS Simulator,name=iPhone 17' -skipPackagePluginValidation build` グリーン
- [ ] Rust 変更時: `.so` / `.xcframework` 再生成 → 生成物を commit
- [ ] `docs/sync/STATUS.md` の Android / iOS 列を更新 (✅ / N/A / ❌)
- [ ] `CHANGELOG.md` の未リリースセクションに **(Android)** / **(iOS)** タグ付きで 1 行追加
- [ ] 新規/変更ファイルにテストを最低 1 ケース追加 (`docs/sync/TESTING.md` 参照)
- [ ] スクリーンショット (UI 変更時) を `docs/sync/screenshots/sNN-*.png` に保存
- [ ] PR description に `docs/sync/PR_TEMPLATE.md` を流用

### 3.2 調査セッション (S2 / S8)

- [ ] 結論を `research/INDEX.md` (S2) もしくは該当 `research/rNN-*.md` 末尾に「移植する / 部分移植 / 移植不要」として明記
- [ ] commit hash を最低 1 つ引用
- [ ] 後続セッションの Acceptance Criteria を更新 (必要な場合)

---

## 4. マージ順 (推奨)

```
S1 → S2 → (S3 ‖ S4 ‖ S5 ‖ S6 ‖ S7 ‖ S8 ‖ S9 ‖ S10A→B→C→D) → S11 → S12
```

並行可能なセッション間でも、**§2 の競合マトリクス** に従って同一ファイルを触る場合は直列化する。
