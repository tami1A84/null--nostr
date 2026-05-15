# Session 10: ElevenLabs STT (音声入力) — 4 サブセッションに分割 / **方向反転**

> このプロンプトは **インデックス** です。実作業は 10A → 10B/10C → 10D の順に行ってください。
>
> ⚠️ **方向反転**: 親プラン全体は Native → Web ですが、ElevenLabs STT は **Web 先行** (Web の `hooks/useSTT.js` で実装済み、Native は未統合) のため、本セッションのみ **Web → Native** の方向で扱います。

## 背景

旧プラン (1 セッション 3h で Android + iOS 同時実装) は REVIEW.md §2 で「リスクが高い」と指摘されたため、4 サブセッションに分割しました。

調査の結果:

- **Web**: `hooks/useSTT.js` + `PostModal` / `TalkTab` で STT 統合済み (実装フル)
- **Android**: `ElevenLabsSettings.kt` あり (TTS 設定のみ?)、STT 未統合
- **iOS**: `ElevenLabsTTSService` あり (TTS のみ)、STT 未統合

→ 方向は **Web → Android/iOS** に反転。

## サブセッション

| # | プロンプト | 内容 | 推定 |
|---|---|---|---|
| 10A | [session-10a.md](./session-10a.md) | API 仕様調査 (Web の useSTT を仕様書化) ・WS フォーマット設計・キー保管設計 | 1h |
| 10B | [session-10b.md](./session-10b.md) | Android 実装 (`ElevenLabsSttService.kt` + PostModal/TalkScreen 配線) | 2.5h |
| 10C | [session-10c.md](./session-10c.md) | iOS 実装 (`ElevenLabsSttService.swift` + PostSheet/TalkView 配線) | 2.5h |
| 10D | [session-10d.md](./session-10d.md) | UX / 権限 / エラー処理統合 (両 OS) | 1.5h |

## 依存関係

```
S10A ──┬──> S10B ──┐
       │           ├──> S10D
       └──> S10C ──┘
```

- 10A の出力 (API spec / fixture / 鍵保管方式) を 10B / 10C が共有
- 10B と 10C は **並行実行可** (異なるディレクトリ)
- 10D は両 OS の実装完了後

## 共通事項

- API キー保管: Android = EncryptedSharedPreferences / iOS = Keychain
  - **絶対に** UserDefaults / SharedPreferences (平文) / ログに出さない
- 録音: Android = `Dispatchers.IO` / iOS = `actor ElevenLabsSttService`
- UI: Web の `hooks/useSTT.js` 振る舞いを参照する (リアルタイム部分テキスト → 無音 1s で auto-commit)

## STATUS 更新

10A〜10D の各サブセッションが完了するごとに `docs/sync/STATUS.md` の対応行を更新。
