# Session 10D: ElevenLabs STT — UX / 権限 / エラー処理統合 (両 OS)

> このプロンプトは null--nostr の **Native → Web 同期** ワークフローの一部です (S10 系のみ **方向反転: Web → Native**)。
> 親ブランチ: `sync/native-to-web-20260516`
> 設計書: `docs/sync/DESIGN.md` / プラン: `docs/sync/PLAN.md` / 進捗: `docs/sync/STATUS.md`
> 統合チェック: `docs/sync/CHECKLIST.md` / テスト: `docs/sync/TESTING.md`
> 前提セッション: **S10B + S10C 完了後**

## 前提 (必読)

- S10A の成果物 (`docs/sync/research/r10-*.md`, `docs/sync/fixtures/stt/*.json`) を熟読
- AGENTS.md の制約を厳守 (Keychain / EncryptedSharedPreferences / actor / 140 char / LineSeedJP)
- iOS は NIP-46 のみ (Amber 不可)
- **方向**: Web (`hooks/useSTT.js`) が source of truth、Native (Android/iOS) を追従させる

## 作業ブランチを切る

```bash
git checkout sync/native-to-web-20260516
git pull --ff-only
git checkout -b sync/native-to-web-20260516/s10d-stt-ux
```


## 目的

S10B / S10C で「動く状態」になった STT に、ユーザー向けの **エラー処理 / 権限フロー / API キー誘導** を統一仕様で実装する (Web の振る舞いを参考に)。

## タスク

1. **権限拒否時のフォールバックモーダル**
   - Android: `ui/components/PermissionDeniedModal.kt` (既存があれば再利用)
   - iOS: `Views/Components/PermissionDeniedSheet.swift`
   - メッセージ (両 OS 共通文言):
     - 「マイクへのアクセスが許可されていません」
     - 「設定アプリでマイクを有効にすると音声入力が使えます」
     - ボタン: 「設定を開く」(Android: `ACTION_APPLICATION_DETAILS_SETTINGS` / iOS: `UIApplication.openSettingsURLString`)
2. **API キー未設定時の誘導**
   - マイクボタンタップ時にキー未設定なら誘導モーダル → ElevenLabsSettings 画面へ jump
3. **エラートースト/バナー** (S10A の error spec 準拠)
   - 401: 「API キーが無効です」
   - 429: 「使用上限に達しました」(retry-after を尊重)
   - WS タイムアウト: 「接続が切れました。もう一度お試しください」
   - WS 切断: 自動再接続 3 回まで silent retry、4 回目で上記メッセージ
4. **言語切替 UI** (S10A 仕様)
   - PostModal/PostSheet ツールバーに言語 chip (jpn / eng) を追加 — 既存 ElevenLabsSettings からも変更可
5. **録音中 UX**
   - マイクボタンに pulse アニメ (Android: `infiniteRepeatable` / iOS: `.symbolEffect(.pulse, isActive:)`)
   - 既存 IME 挙動を破壊しない (AGENTS.md 「日本語入力中の composition」)
6. **テスト** (`docs/sync/TESTING.md` §1 S10D):
   - 権限拒否時のフォールバックモーダル表示
   - API キー未設定時の誘導モーダル
   - 401 / 429 / timeout の各エラーバナー

## Acceptance Criteria

- [ ] 両 OS で 6 つの UX フロー (権限拒否 / キー未設定 / 401 / 429 / timeout / 自動再接続) が一致した日本語メッセージで動作
- [ ] PostModal / PostSheet / TalkScreen / TalkView の 4 箇所 全てでマイクボタンが同じ振る舞い
- [ ] Android `./gradlew assembleDebug` + テスト グリーン
- [ ] iOS `xcodebuild ... build` + test グリーン
- [ ] 実機で 1 回ずつ STT セッションが完走する (CHECKLIST.md §1)

## 完了処理

1. `docs/sync/STATUS.md` の S10D 行 (Android + iOS 両方) を ✅
2. `CHANGELOG.md` の未リリース節に `### Changed: STT のエラー/権限 UX を統一` を追記
3. `git commit -m "sync(s10d): STT UX/権限統合 (両 OS) — Web→Native"`
4. PR を親ブランチへ

## 落とし穴

- 権限拒否 → 設定アプリ往復後、PostModal/Sheet を開き直したら自動で再判定して状態更新する
- iOS の `.symbolEffect` は iOS 17 専用 (deployment target 一致)
- Android の `PermissionDeniedModal` で activity を跨ぐ場合は `rememberLauncherForActivityResult` を使う
