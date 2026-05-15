# Web → Native 同期ドキュメント

`sync/web-to-native-20260516` ブランチで進める Web → Android/iOS 同期作業の全資料。

## 構成

### コア
- [DESIGN.md](./DESIGN.md) — 設計書 (アーキテクチャ・ギャップ分析・スコープ凍結プロセス)
- [PLAN.md](./PLAN.md) — セッション分割 (15 セッション)・依存関係・推奨スケジュール
- [STATUS.md](./STATUS.md) — 進捗チェックリスト + 実機検証進捗
- [REVIEW.md](./REVIEW.md) — 設計・プランの批判的レビュー + 対応状況

### 実装支援
- [CHECKLIST.md](./CHECKLIST.md) — **実機 / 競合 / DoD 統合チェック**
- [TESTING.md](./TESTING.md) — テスト設計 (fixture / golden test)
- [PR_TEMPLATE.md](./PR_TEMPLATE.md) — セッション PR description テンプレ

### セッション別プロンプト
- [prompts/](./prompts/) — 各セッションを別 AI / 別 Goose セッションでそのまま実行できる自己完結プロンプト (15 ファイル)
  - 通常 12 セッション + STT 4 サブセッション (10A/B/C/D)

### スコープ管理
- [research/](./research/) — Web 仕様レポート (8 件)
- [research/INDEX.md](./research/INDEX.md) — **同期対象/対象外スコープ凍結** (Session 2 で sign-off)

### Fixture (テスト共通入出力)
- `fixtures/` 配下に `recommendation/` `birthday/` `miniapps/` `geohash/` `stt/` を配置 (Session 2 / 10A で生成)

## クイックスタート

1. `docs/sync/DESIGN.md` を読む (15 分)
2. `docs/sync/PLAN.md` でセッション一覧と依存関係を把握 (5 分)
3. `docs/sync/REVIEW.md` でリスクと注意点を確認 (5 分)
4. `docs/sync/CHECKLIST.md` を一読 (実機・競合の前提を理解)
5. `docs/sync/prompts/session-01.md` から開始
6. 各セッション完了ごとに `docs/sync/STATUS.md` を更新
7. PR は `docs/sync/PR_TEMPLATE.md` を流用

## ドキュメント間の関係

```
DESIGN.md         ← なぜ・何を同期するか
   │
   ├── PLAN.md         ← どの順番で・誰が実行するか
   │      │
   │      ├── prompts/session-NN.md  ← 1 セッションを単独で実行
   │      └── CHECKLIST.md           ← 各セッションが満たすべき横断要件
   │
   ├── TESTING.md      ← Acceptance Criteria を支えるテスト具体策
   ├── REVIEW.md       ← 計画自体の批判 + 対応状況
   └── research/INDEX.md  ← スコープ凍結 (S2 で sign-off)

STATUS.md         ← リアルタイムで全体進捗を可視化
PR_TEMPLATE.md    ← セッション PR で流用
```
