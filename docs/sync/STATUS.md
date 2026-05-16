# Native → Web 同期 進捗

> Branch: `sync/native-to-web-20260516`
> 各セッション完了時に該当行を更新してください。

## 0. スコープ凍結 (Session 2 完了時点)

- [ ] `docs/sync/research/INDEX.md` の sign-off 3 名分完了
- [ ] 凍結日: ____________

> 凍結前に S3〜S10D を着手しないこと (実装方向が変わるリスク)。

## 1. セッション進捗

> 列の意味: **Web** = Web 側の修正 / **Native** = Native 側の追加実装 (S10 のみ Web→Native の方向)

| # | セッション | Web | Native (And) | Native (iOS) | 担当 | 完了日 | メモ |
|---|---|---|---|---|---|---|---|
| 1 | キックオフ | -- | -- | -- | | | |
| 2 | Native 差分調査 + INDEX 凍結 | -- | -- | -- | | | レポートは `docs/sync/research/` |
| 3 | Reaction picker | ⬜ | -- | -- | | | Native → Web |
| 4 | Recommendation 改善 | ⬜ | -- | -- | | | Native → Web |
| 5 | Birthday 通知 | ⬜ | -- | (補完?) | | | Native → Web (iOS 側も補完が必要なら) |
| 6 | MiniApp タブ整理 | ⬜ | -- | -- | | | Native → Web |
| 7 | SignUp UX | ⬜ | -- | (SignUpView?) | | | Native → Web (iOS は SignUpView 新設の可能性) |
| 8 | connection-manager 修正調査 | -- | -- | -- | | | Rust core への反映可否を判定 |
| 9 | ProofMode / Divine | ⬜ | -- | N/A | | | Android → Web (iOS は対象外) |
| 10A | STT 調査・設計 | -- | -- | -- | | | **方向反転**: Web → Native |
| 10B | STT Android 実装 | -- | ⬜ | -- | | | `ElevenLabsSttService.kt` + UI |
| 10C | STT iOS 実装 | -- | -- | ⬜ | | | `ElevenLabsSttService.swift` + UI |
| 10D | STT UX / 権限 / エラー統合 | -- | ⬜ | ⬜ | | | 権限拒否 / API キー未設定モーダル |
| 11 | FFI 再ビルド + token sync | ✅ | ✅ | ✅ | goose (s11-finalize) | 2026-05-17 | Rust FFI 変更ゼロにつき再ビルド不要。`design-tokens/constants.json` に `CACHE_CONFIG.durations.notification` (86_400_000ms / 1day) を追加し source-of-truth と Android `Constants.CacheDuration.NOTIFICATION` 参照 (NostrCache.kt 7 箇所) を整合 → `npm run tokens:check` PASS。Android `assembleDebug` PASS (APK 58.4MB / commit 981416b)。iOS `xcodebuild build`+`test` PASS (14 tests)。Web `tokens:check` PASS / `npm run test`/`build` FAIL は親ブランチ既存の `@noble/hashes` v2.0.1 subpath 問題 (S11 範囲外、ブロッカー §3 参照)。スモーク 7 機能は各サブブランチ未マージなので grep で実装存在を確認 (PR マージ後に Session 12 で再検証要) |
| 12 | CHANGELOG / リリース | -- | -- | -- | | | v1.5.0 |

凡例: ⬜ 未着手 / 🟦 進行中 / ✅ 完了 / ❌ ブロック / N/A 対象外 / -- 該当なし

## 2. 実機検証進捗

[CHECKLIST.md §1](./CHECKLIST.md) の実機必須項目:

| 項目 | Web ブラウザ | Android 実機 | iOS 実機 | 検証日 | 担当 |
|---|---|---|---|---|---|
| S5 通知 (Birthday/Zap) | ⬜ | (既) | (既) | | |
| S5 Zap 受信 (Lightning) | ⬜ | (既) | (既) | | |
| S7 リージョン → geohash 自動セット | ⬜ | (既) | ⬜ (要 SignUpView) | | |
| S9 ProofMode 録画 (Web 追加) | ⬜ | (既) | N/A | | |
| S10D STT (マイク) | (既) | ⬜ | ⬜ | | |
| S11 NIP-EE (MLS) Talk | -- (要調査) | ⬜ | ⬜ | | | 親ブランチ単体ビルドの実機検証は Session 12 (PR マージ後) に集約 |
| S12 配布 | ⬜ | ⬜ | ⬜ | | |

## 3. ブロッカー

- **[S11 検出 / 2026-05-17] Web `@noble/hashes` v2.0.1 subpath import 失敗** — `@noble/hashes` 2.0.1 で `./utils` の exports エントリが削除され `./utils.js` のみが残ったため、`src/adapters/signing/MemorySigner.ts` の `import { bytesToHex, hexToBytes } from '@noble/hashes/utils'` および `src/__tests__/adapters/signing.test.ts` の同 import が解決できず、`npm run test` (2 suites fail / 148 tests pass) と `npm run build` (Next.js 型エラー) が失敗する。親ブランチ既存の問題で S3-S11 のいずれのセッションも `package.json` を変更していない。Session 12 で `import ... from '@noble/hashes/utils.js'` への変更 or `@noble/hashes` の pin 戻し で対処予定。

## 4. 参照

- 設計: `docs/sync/DESIGN.md`
- プラン: `docs/sync/PLAN.md`
- レビュー: `docs/sync/REVIEW.md`
- 統合チェック: `docs/sync/CHECKLIST.md`
- テスト: `docs/sync/TESTING.md`
- スコープ凍結: `docs/sync/research/INDEX.md`
- 各セッションプロンプト: `docs/sync/prompts/session-NN.md`
