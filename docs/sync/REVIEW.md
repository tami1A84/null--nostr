# Native → Web 同期 設計・プラン レビュー

> 作成日: 2026-05-16  
> 対象ブランチ: `sync/native-to-web-20260516`  
> 対象成果物: `docs/sync/DESIGN.md`, `docs/sync/PLAN.md`, `docs/sync/prompts/session-01.md`〜`session-12.md`

## 総評

設計書・プラン・セッション別プロンプトは、ユーザー要件である「新しいブランチを切る」「Native (Android/iOS) の修正を Web へ同期させる設計書とプランを作成する」「各セッションごとのプロンプトを生成する」を満たしている。

特に、Native 実装と Web 実装の対応表、12 セッションの実行順、進捗管理用 STATUS、Session 2 用 research skeleton まで用意している点は実作業に移しやすい。

## 良い点

- **ブランチと成果物が明確**: `sync/native-to-web-20260516` 上に `docs/sync/` 配下で集約されている。
- **同期方向が明示**: Native 先行、Web 追従。例外 (STT は Web 先行) も明示。
- **Web/Android/iOS 三方を対象化**: iOS は NIP-46 のみ、Rokunana 除外などプラットフォーム差も明記されている。
- **実行可能なセッション分割**: 12 セッションに分け、依存関係と Acceptance Criteria を置いている。
- **調査→実装→統合の流れ**: Session 2 で Native 仕様 ↔ Web 現状を突合してから Session 3 以降へ進む構造は妥当。
- **STATUS 運用がある**: Web / Android / iOS 別に進捗を追えるため、片側だけ完了した状態も管理できる。

## 改善推奨・注意点

### 1. 「Native 実装」の範囲をさらに固定する

Session 2 完了時点で以下を確定すると差し戻しが減る:

- Native 側の基準 commit / tag (Android v1.4.9 / iOS 1.0.4 build 5)
- 「Native → Web 移植」「Native → Web 移植不要」「方向反転 (Web → Native)」の最終判定
- Native 内部で Android/iOS が食い違っている機能の仕様寄せ先

推奨: Session 2 完了時に `docs/sync/research/INDEX.md` を凍結 sign-off。

### 2. STT セッションは方向が逆 + 見積もりを増やす

ElevenLabs STT は Web が先行している (`hooks/useSTT.js`)。S10A〜D は **Web → Native** の方向で扱う。録音権限、WebSocket、音声フォーマット、API キー保管、Post/Talk 双方の UI 統合が必要で、Android+iOS を 3 時間で完了するのはリスクが高い。

推奨: Session 10 を以下に分割する。

- 10A: 調査・API 仕様・セキュア保管設計
- 10B: Android 実装
- 10C: iOS 実装
- 10D: UX / 権限 / エラー処理統合

### 3. Session 8 は「調査のみ」と「実装必要時」を明確に分ける

connection-manager の修正は Web 固有の可能性があり、Rust/nostr-sdk の責務範囲と混同しやすい。

推奨: Session 8 の完了条件を「Rust 変更不要 / 必要 / 一部必要」の判定までに限定し、実装が必要な場合は別セッションまたは Session 11 前の新セッションに切る。

### 4. 実機検証が必要な項目を明示する

以下はシミュレータ / エミュレータ / ローカル開発サーバだけでは不十分:

- Android Amber/NIP-55 関連 (既動作だが回帰テスト要)
- カメラ/録音/STT
- iOS Keychain/NIP-46 外部署名
- Push/通知に近い UX
- App Store 審査影響がある Rokunana/動画系
- Web PWA でのカメラ/マイク/位置情報権限

推奨: `docs/sync/PLAN.md` に「実機必須チェック」表を追加する。

### 5. PR 粒度とマージ順を固定する

サブブランチ戦略は書かれているが、複数セッションが同じファイルを触る可能性がある。

競合しやすいファイル例:

- Web: `components/TimelineTab.js`, `components/NotificationModal.js`, `components/MiniAppTab.js`, `components/PostModal.js`
- Android: `ui/components/PostModal.kt` (S10B/D), `SettingsScreen.kt` (S10D)
- iOS: `Views/Sheets/PostSheet.swift` (S10C/D), `SettingsView.swift` (S10D)

推奨: PLAN に「同一ファイルを触るセッションは直列化する」注意を追加する。

### 6. テスト設計をもう一段具体化する

Acceptance Criteria はあるが、テストファイル名・fixture・モック方針が未定。

推奨:

- Recommendation: Web で metadata なしユーザー除外の vitest unit test (Native と同 fixture)
- Birthday: 日付正規化 `YYYY-MM-DD` / `MM-DD` / object の vitest unit test
- Geohash: Web/Android/iOS で同一入力→同一 prefix の golden test
- Reaction picker: Web で snapshot または DOM 検査

## 結論

現時点の成果物は、設計・計画・セッションプロンプトとして十分に実用可能。実装フェーズへ進む前に、Session 2 で対象範囲を固定し、STT/実機検証/同一ファイル競合の扱いを PLAN へ追記すると、より安全に進められる。


---

## 対応状況 (Agent 3 最終化, 2026-05-16)

レビュー指摘 6 点をすべて成果物へ反映済み。

| # | 指摘 | 反映先 |
|---|---|---|
| 1 | 同期対象スコープを Session 2 終了時に固定する | `docs/sync/research/INDEX.md` (sign-off 欄付き) + `DESIGN.md §5.4` (凍結プロセス) + `STATUS.md §0` (凍結チェックポイント) |
| 2 | STT セッションを分割 (3h は過小評価, 方向は Web → Native) | `session-10.md` を index 化 + `session-10a.md` (設計) / `session-10b.md` (Android) / `session-10c.md` (iOS) / `session-10d.md` (UX) を作成。`PLAN.md §1` の見積を 22.5h に更新 |
| 3 | Session 8 完了条件を「Rust 変更不要/必要/一部必要」の判定に限定 | `prompts/session-08.md` 既存タスク内に「結論を 3 択で記載」と明記済み。`PLAN.md §1` で「調査のみ」と明記 |
| 4 | 実機検証必須項目を明示 | `docs/sync/CHECKLIST.md §1` + `PLAN.md §6` + `STATUS.md §2` (実機検証進捗テーブル) |
| 5 | PR 粒度・マージ順 + 同一ファイル直列化 | `docs/sync/CHECKLIST.md §2` (Web/Native/Rust 競合マトリクス + マージ順) + `PLAN.md §2.1` (競合回避節) |
| 6 | テスト設計の具体化 (fixture / golden) | `docs/sync/TESTING.md` (fixture 構成, golden test, セッション別テスト計画) — Native 実装から正解値抽出方針も明記 |

## 追加対応 (2026-05-16, 方向反転)

- 元レビュー時点では「Web → Native」と誤認していた。本最終化で全 34 ドキュメントを **Native → Web** に反転。
- ブランチ名も `sync/web-to-native-20260516` → `sync/native-to-web-20260516` に改名。
- 例外: STT (S10 系) のみ Web → Native の方向で残す (コード調査の結果 Web 先行確定)。INDEX.md で方向欄に明示。

## 残課題 (本同期作業のスコープ外)

- CI セットアップ (GitHub Actions): v1.5.1 以降で別 PR を予定 — TESTING.md §3 を参照
- `docs/sync/screenshots/` ディレクトリは UI 変更セッション完了時に作成 (空ディレクトリは git に乗らないため事前生成不要)
