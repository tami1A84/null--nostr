# Web → Native 同期 設計・プラン レビュー

> 作成日: 2026-05-16  
> 対象ブランチ: `sync/web-to-native-20260516`  
> 対象成果物: `docs/sync/DESIGN.md`, `docs/sync/PLAN.md`, `docs/sync/prompts/session-01.md`〜`session-12.md`

## 総評

設計書・プラン・セッション別プロンプトは、ユーザー要件である「新しいブランチを切る」「Web版の修正をNativeへ同期させる設計書とプランを作成する」「各セッションごとのプロンプトを生成する」を満たしている。

特に、Webコミット履歴から対象差分を抽出し、Android/iOSの既存ファイルとの対応表、12セッションの実行順、進捗管理用STATUS、Session 2用research skeletonまで用意している点は実作業に移しやすい。

## 良い点

- **ブランチと成果物が明確**: `sync/web-to-native-20260516` 上に `docs/sync/` 配下で集約されている。
- **Web差分の起点が明示**: 直近90日commitをもとにWeb先行実装を抽出している。
- **Android/iOS双方を対象化**: iOSはNIP-46のみ、Rokunana除外などプラットフォーム差も明記されている。
- **実行可能なセッション分割**: 12セッションに分け、依存関係とAcceptance Criteriaを置いている。
- **調査→実装→統合の流れ**: Session 2でWeb仕様を確定してからSession 3以降へ進む構造は妥当。
- **STATUS運用がある**: Android/iOS別に進捗を追えるため、片側だけ完了した状態も管理できる。

## 改善推奨・注意点

### 1. 「Web版の修正」の範囲をさらに固定する

現状は直近90日ベースで抽出されているが、実装フェーズ前に以下を確定すると差し戻しが減る。

- 同期対象の基準commitまたはtag
- 「Webのみ」「Nativeへ移植」「Nativeへ移植しない」の最終判定
- Web側で未完成/実験的な機能の除外基準

推奨: Session 2完了時に `docs/sync/research/INDEX.md` を追加し、対象/非対象の最終一覧を1表にまとめる。

### 2. STTセッションは見積もりを増やす

ElevenLabs STTは録音権限、WebSocket、音声フォーマット、APIキー保管、Post/Talk双方のUI統合が必要で、Android+iOSを3時間で完了するのはリスクが高い。

推奨: Session 10を以下に分割する。

- 10A: 調査・API仕様・セキュア保管設計
- 10B: Android実装
- 10C: iOS実装
- 10D: UX/権限/エラー処理統合

### 3. Session 8は「調査のみ」と「実装必要時」を明確に分ける

connection-managerの修正はWeb固有の可能性があり、Rust/nostr-sdkの責務範囲と混同しやすい。

推奨: Session 8の完了条件を「Rust変更不要/必要/一部必要」の判定までに限定し、実装が必要な場合は別セッションまたはSession 11前の新セッションに切る。

### 4. 実機検証が必要な項目を明示する

以下はシミュレータ/エミュレータだけでは不十分。

- Android Amber/NIP-55関連
- カメラ/録音/STT
- iOS Keychain/NIP-46外部署名
- Push/通知に近いUX
- App Store審査影響があるRokunana/動画系

推奨: `docs/sync/PLAN.md` に「実機必須チェック」表を追加する。

### 5. PR粒度とマージ順を固定する

サブブランチ戦略は書かれているが、複数セッションが同じファイルを触る可能性がある。

競合しやすいファイル例:

- Android: `TimelineViewModel.kt`, `NostrRepositoryNotifications.kt`, `SettingsScreen.kt`, `PostModal.kt`
- iOS: `TimelineViewModel.swift`, `NostrRepository+Notifications.swift`, `SettingsView.swift`, `PostSheet.swift`

推奨: PLANに「同一ファイルを触るセッションは直列化する」注意を追加する。

### 6. テスト設計をもう一段具体化する

Acceptance Criteriaはあるが、テストファイル名・fixture・モック方針が未定。

推奨:

- Recommendation: Android/iOSでmetadataなしユーザー除外のunit test
- Birthday: 日付正規化 `YYYY-MM-DD` / `MM-DD` / object のunit test
- Geohash: Web/Android/iOSで同一入力→同一prefixのgolden test
- Reaction picker: UI snapshotまたは簡易Composable/View test

## 結論

現時点の成果物は、設計・計画・セッションプロンプトとして十分に実用可能。実装フェーズへ進む前に、Session 2で対象範囲を固定し、STT/実機検証/同一ファイル競合の扱いをPLANへ追記すると、より安全に進められる。
