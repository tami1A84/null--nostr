# Per-session Prompts

各ファイルは Goose / 任意のチャット AI に貼り付けるだけで該当セッションを実行できる、自己完結したプロンプトです。

## 一覧

| # | ファイル | タイトル |
|---|---|---|
| 1 | [session-01.md](./session-01.md) | キックオフ + ステータス初期化 |
| 2 | [session-02.md](./session-02.md) | Web 差分調査 (各機能の Web 仕様確定) |
| 3 | [session-03.md](./session-03.md) | Reaction picker: Unicode quick reaction 削除 |
| 4 | [session-04.md](./session-04.md) | Recommendation: アイコン無しユーザ除外 + Following 優先ロード |
| 5 | [session-05.md](./session-05.md) | Birthday 通知 + 相互フォロー Zap 通知 |
| 6 | [session-06.md](./session-06.md) | MiniApp タブ構成・順序を Web と一致させる |
| 7 | [session-07.md](./session-07.md) | SignUp UX: 手動リージョン選択 + リレー検出強化 |
| 8 | [session-08.md](./session-08.md) | connection-manager v1.4.8 修正の Rust 反映調査 |
| 9 | [session-09.md](./session-09.md) | ProofMode / DivineVideoRecorder: Web → Android 差分反映 (iOS 対象外) |
| 10 | [session-10.md](./session-10.md) | ElevenLabs STT (投稿/トークの音声入力) |
| 11 | [session-11.md](./session-11.md) | FFI 再ビルド + token sync + 動作確認 |
| 12 | [session-12.md](./session-12.md) | CHANGELOG 統合 + リリース準備 |

## 使い方

1. 親ブランチ `sync/web-to-native-20260516` をチェックアウト
2. 該当セッションのファイルを開いて全文をコピー
3. 新しい Goose セッションに貼り付け
4. 完了したら `docs/sync/STATUS.md` を更新

## 並行実行のヒント

- Session 1 → 2 を順次
- Session 3〜10 は依存無しなので 3 並行ワーカーで分担可能
- Session 11 は 3〜10 が完了してから
- Session 12 は 11 の後
