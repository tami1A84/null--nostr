# Per-session Prompts

各ファイルは Goose / 任意のチャット AI に貼り付けるだけで該当セッションを実行できる、自己完結したプロンプトです。

> **同期方向**: Native (Android/iOS) → Web (S10 系のみ Web → Native)

## 一覧

| # | ファイル | タイトル | 方向 |
|---|---|---|---|
| 1 | [session-01.md](./session-01.md) | キックオフ + ステータス初期化 | -- |
| 2 | [session-02.md](./session-02.md) | Native 差分調査 + `research/INDEX.md` 凍結 | -- |
| 3 | [session-03.md](./session-03.md) | Reaction picker: Native 仕様に Web を寄せる | Native → Web |
| 4 | [session-04.md](./session-04.md) | Recommendation: アイコン無し除外 + Following 優先 | Native → Web |
| 5 | [session-05.md](./session-05.md) | Birthday 通知 + 相互フォロー Zap 通知 | Native → Web |
| 6 | [session-06.md](./session-06.md) | MiniApp タブ構成・順序を Native と一致 | Native → Web |
| 7 | [session-07.md](./session-07.md) | SignUp UX: リージョン選択 + リレー検出強化 | Native → Web |
| 8 | [session-08.md](./session-08.md) | connection-manager v1.4.8 修正の Rust 反映調査 | -- |
| 9 | [session-09.md](./session-09.md) | ProofMode / DivineVideoRecorder (Android → Web, iOS 対象外) | Android → Web |
| 10  | [session-10.md](./session-10.md) | **(index)** ElevenLabs STT — 4 サブセッション | **Web → Native** |
| 10A | [session-10a.md](./session-10a.md) | STT API 仕様・WS フォーマット・キー保管設計 | Web → Native |
| 10B | [session-10b.md](./session-10b.md) | STT Android 実装 | Web → Android |
| 10C | [session-10c.md](./session-10c.md) | STT iOS 実装 | Web → iOS |
| 10D | [session-10d.md](./session-10d.md) | STT UX / 権限 / エラー処理統合 | Web → Native |
| 11 | [session-11.md](./session-11.md) | FFI 再ビルド + token sync + 動作確認 | -- |
| 12 | [session-12.md](./session-12.md) | CHANGELOG / リリース統合 | -- |

## 使い方

1. 親ブランチ `sync/native-to-web-20260516` をチェックアウト
2. 該当セッションのファイルを開いて全文をコピー
3. 新しい Goose セッションに貼り付け
4. 完了したら `docs/sync/STATUS.md` を更新
5. PR description には `docs/sync/PR_TEMPLATE.md` を流用

## 並行実行のヒント

- Session 1 → 2 を順次 (S2 は INDEX.md 凍結 sign-off を伴う)
- Session 3〜9, 10A は依存無しなので並行ワーカーで分担可
  - ただし [CHECKLIST.md §2](../CHECKLIST.md) の競合マトリクスに従って同一ファイルを触るセッションは直列化
- Session 10B / 10C は 10A 完了後に並行可
- Session 10D は 10B + 10C 完了後
- Session 11 は 3〜10D 全部終わってから
- Session 12 は 11 の後

## 参考ドキュメント

- 設計: [../DESIGN.md](../DESIGN.md)
- プラン: [../PLAN.md](../PLAN.md)
- 進捗: [../STATUS.md](../STATUS.md)
- 統合チェック: [../CHECKLIST.md](../CHECKLIST.md)
- テスト: [../TESTING.md](../TESTING.md)
- レビュー: [../REVIEW.md](../REVIEW.md)
- スコープ凍結: [../research/INDEX.md](../research/INDEX.md)
