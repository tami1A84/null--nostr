# Web → Native 同期 進捗

> Branch: `sync/web-to-native-20260516`
> 各セッション完了時に該当行を更新してください。

## 0. スコープ凍結 (Session 2 完了時点)

- [ ] `docs/sync/research/INDEX.md` の sign-off 3 名分完了
- [ ] 凍結日: ____________

> 凍結前に S3〜S10D を着手しないこと (実装方向が変わるリスク)。

## 1. セッション進捗

| # | セッション | Android | iOS | 担当 | 完了日 | メモ |
|---|---|---|---|---|---|---|
| 1 | キックオフ | -- | -- | | | |
| 2 | Web 差分調査 + INDEX 凍結 | -- | -- | | | レポートは `docs/sync/research/` |
| 3 | Reaction picker | ⬜ | ⬜ | | | |
| 4 | Recommendation 改善 | ⬜ | ⬜ | | | |
| 5 | Birthday 通知 | ⬜ | ⬜ | | | |
| 6 | MiniApp タブ整理 | ⬜ | ⬜ | | | |
| 7 | SignUp UX | ⬜ | ⬜ | | | iOS は新規 SignUpView 検討 |
| 8 | connection-manager 修正調査 | ⬜ | ⬜ | | | Rust core への反映可否を判定 |
| 9 | ProofMode / Divine | ⬜ | N/A | | | iOS は App Store 審査の都合で対象外 |
| 10A | STT 調査・設計 | -- | -- | | | API 仕様 / キー保管 / WS フォーマット |
| 10B | STT Android 実装 | ⬜ | -- | | | `ElevenLabsSttService.kt` + UI |
| 10C | STT iOS 実装 | -- | ⬜ | | | `ElevenLabsSttService.swift` + UI |
| 10D | STT UX / 権限 / エラー統合 | ⬜ | ⬜ | | | 権限拒否 / API キー未設定モーダル |
| 11 | FFI 再ビルド + token sync | ⬜ | ⬜ | | | スモーク 6 機能 |
| 12 | CHANGELOG / リリース | -- | -- | | | v1.5.0 |

凡例: ⬜ 未着手 / 🟦 進行中 / ✅ 完了 / ❌ ブロック / N/A 対象外

## 2. 実機検証進捗

[CHECKLIST.md §1](./CHECKLIST.md) の実機必須項目:

| 項目 | Android 実機 | iOS 実機 | 検証日 | 担当 |
|---|---|---|---|---|
| S5 通知 (Birthday/Zap) | ⬜ | ⬜ | | |
| S5 Zap 受信 (Lightning) | ⬜ | ⬜ | | |
| S7 Amber (NIP-55) サインアップ | ⬜ | N/A | | |
| S7 NIP-46 (Nostr Connect) | N/A | ⬜ | | |
| S9 カメラ + ProofMode | ⬜ | N/A | | |
| S10D STT (マイク) | ⬜ | ⬜ | | |
| S11 NIP-EE (MLS) Talk | ⬜ | ⬜ | | |
| S12 TestFlight / Play Internal | ⬜ | ⬜ | | |

## 3. ブロッカー

- (なし — 発生時に追記)

## 4. 参照

- 設計: `docs/sync/DESIGN.md`
- プラン: `docs/sync/PLAN.md`
- レビュー: `docs/sync/REVIEW.md`
- 統合チェック: `docs/sync/CHECKLIST.md`
- テスト: `docs/sync/TESTING.md`
- スコープ凍結: `docs/sync/research/INDEX.md`
- 各セッションプロンプト: `docs/sync/prompts/session-NN.md`
