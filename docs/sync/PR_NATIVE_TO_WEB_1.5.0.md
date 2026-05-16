# Native → Web 同期 v1.5.0

このブランチは Native (Android v1.4.9 / iOS 1.0.4) の先行実装を Web に同期します。
例外として音声入力は Web 先行の方向で Native 側に部分同期しました。Native の ElevenLabs Scribe streaming 完全統合は v1.6 へ持ち越します。

## 内容
- Session 3: Reaction picker (Native と仕様一致, Web 修正)
- Session 4: Recommendation 改善 (Web 修正)
- Session 5: Birthday/Mutual Zap 通知 (Web 修正)
- Session 6: MiniApp タブ統一 (Web 修正)
- Session 7: SignUp UX (Web + iOS 補完)
- Session 8: connection-manager 調査結論 (Web 固有、Rust/Native 追加反映なし)
- Session 9: ProofMode (Web 新規, Android 同期)
- Session 10A-D: 音声入力 (Native は OS 標準 STT 部分同期。ElevenLabs streaming は v1.6 carryover)
- Session 11: token sync / build 確認
- Session 12: CHANGELOG、version bump、STATUS finalization、@noble/hashes v2 import 修正

## DoD
- [x] CHANGELOG v1.5.0
- [x] Web / Android / iOS version bump
- [x] STATUS.md finalization
- [ ] npm run test
- [ ] npm run build
- [ ] Android assembleDebug
- [ ] iOS xcodebuild

## 同期マトリクス
docs/sync/STATUS.md 参照
