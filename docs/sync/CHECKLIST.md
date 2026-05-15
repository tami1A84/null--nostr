# Native → Web 同期 統合チェックリスト

> Branch: `sync/native-to-web-20260516`
> 各セッション完了時およびマージ前に本ファイルの該当行を確認する。

---

## 1. 実機検証必須項目

シミュレータ / エミュレータ / ローカルブラウザだけでは検出できない項目。

| セッション | 検証項目 | Web ブラウザ | Android 実機 | iOS 実機 | 理由 |
|---|---|---|---|---|---|
| S5 | 通知 (Birthday / Zap mutual) のフォアグラウンド表示 | 必須 | (既動作確認) | (既動作確認) | NotificationModal の polling 挙動が emulator で不安定 |
| S5 | Zap (Lightning) を実 wallet で受信 | 必須 | (既) | (既) | NWC / Alby 等の外部 wallet 動作確認 |
| S7 | NIP-05 / 位置情報からの geohash 推定 | 必須 | (既) | iOS 新 SignUpView 検証時必須 | 位置情報権限フローが実機固有 |
| S9 | ProofMode 録画 (Web 追加) | **必須** | (既) | N/A | MediaRecorder + Crypto API 真正性確認 |
| S10B | Android: ElevenLabs STT (マイク) | -- | **必須** | -- | RECORD_AUDIO + WS streaming |
| S10C | iOS: ElevenLabs STT (マイク) | -- | -- | **必須** | NSMicrophoneUsageDescription + AVAudioSession |
| S10D | Bluetooth ヘッドセット切替 (iOS) | --- | -- | 任意 | AVAudioSession ルーティング |
| S11 | NIP-EE (MLS) Talk 送受信 | -- (要新規実装) | 必須 | 必須 | UniFFI 経由の Rust 通信 + WhiteNoise interop |
| S12 | TestFlight / Play Internal / Vercel | 必須 | 任意 | 任意 | リリース前の最終疎通 |

> **App Store 審査リスク**: Session 9 (Rokunana / Divine 動画) は iOS では復活させないこと。CHANGELOG にも明記。

---

## 2. ファイル競合マトリクス

複数セッションが同一ファイルを触る場合は **直列化** する。並行実行する場合はサブブランチ分離 + 早期 rebase。

### 2.1 Web 競合ホットスポット (本同期作業のメインターゲット)

| ファイル | 触るセッション | 推奨順序 |
|---|---|---|
| `components/TimelineTab.js` | S4 (Recommendation), S5 (通知バッジ反映) | **S4 → S5** |
| `components/NotificationModal.js` | S5 (Birthday/Zap), 既存 v1.4.6 互換維持 | 単独 (S5) |
| `lib/recommendation.js` | S4 (アイコン無し除外, Following 優先) | 単独 |
| `lib/cache.js` | S5 (誕生日通知重複防止 cache key) | 単独 |
| `lib/geohash.js` | S7 (リージョン → prefix) | 単独 |
| `lib/proofmode.js` | S9 (Web に新規導入) | 単独 |
| `hooks/useSTT.js` | (既存、参照のみ — S10A〜D は Native 側) | -- |
| `components/PostModal.js` | S9 (Divine 動画) | 単独 |
| `components/MiniAppTab.js` + `components/miniapps/*` | S6 (Native と順序統一) | 単独 |
| `components/ReactionEmojiPicker.js` | S3 (Native と仕様一致) | 単独 |
| `components/SignUpModal.js` | S7 (リージョン選択) | 単独 |
| `components/DivineVideoRecorder.js` | S9 (Web 新規) | 単独 |

### 2.2 Native 競合ホットスポット (S10 系のみ)

| ファイル | 触るセッション | 推奨順序 |
|---|---|---|
| `android/app/.../ui/components/PostModal.kt` | S10B (STT マイク), S10D (UX) | **S10B → S10D** |
| `android/app/.../ui/screens/SettingsScreen.kt` | S10D (STT 設定誘導) | 単独 |
| `android/app/.../data/prefs/AppPreferences.kt` | S10B (STT API キー) | 単独 |
| `ios/NuruNuru/Views/Sheets/PostSheet.swift` | S10C (STT マイク), S10D (UX) | **S10C → S10D** |
| `ios/NuruNuru/Views/Screens/SettingsView.swift` | S10D | 単独 |
| `ios/NuruNuru/Data/AppPreferences.swift` | S10C (STT API キー) | 単独 |
| iOS 新設 `Views/Screens/SignUpView.swift` | S7 (もし新設するなら) | 単独 |

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
- [ ] Web 変更時: `npm run test` グリーン + `npm run build` 通過
- [ ] Android 変更時: `cd android && ./gradlew assembleDebug` グリーン
- [ ] iOS 変更時: `xcodebuild -scheme NuruNuru -destination 'platform=iOS Simulator,name=iPhone 17' -skipPackagePluginValidation build` グリーン
- [ ] Rust 変更時: `.so` / `.xcframework` 再生成 → 生成物を commit
- [ ] `docs/sync/STATUS.md` の Web / Native 列を更新 (✅ / N/A / ❌)
- [ ] `CHANGELOG.md` の未リリースセクションに **(Web)** / **(Android)** / **(iOS)** タグ付きで 1 行追加
- [ ] 新規/変更ファイルにテストを最低 1 ケース追加 (`docs/sync/TESTING.md` 参照)
- [ ] スクリーンショット (UI 変更時) を `docs/sync/screenshots/sNN-*.png` に保存
- [ ] PR description に `docs/sync/PR_TEMPLATE.md` を流用

### 3.2 調査セッション (S2 / S8)

- [ ] 結論を `research/INDEX.md` (S2) もしくは該当 `research/rNN-*.md` 末尾に「移植する / 部分移植 / 移植不要 / 方向反転」として明記
- [ ] commit hash または該当ファイル参照を最低 1 つ引用
- [ ] 後続セッションの Acceptance Criteria を更新 (必要な場合)

---

## 4. マージ順 (推奨)

```
S1 → S2 → (S3 ‖ S4 ‖ S5 ‖ S6 ‖ S7 ‖ S8 ‖ S9 ‖ S10A→B→C→D) → S11 → S12
```

並行可能なセッション間でも、**§2 の競合マトリクス** に従って同一ファイルを触る場合は直列化する。
