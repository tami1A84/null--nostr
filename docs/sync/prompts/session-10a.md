# Session 10A: ElevenLabs STT — API 仕様・WS フォーマット・キー保管設計

> このプロンプトは null--nostr の **Web → Native 同期** ワークフローの一部です。
> 親ブランチ: `sync/web-to-native-20260516`
> 設計書: `docs/sync/DESIGN.md` / プラン: `docs/sync/PLAN.md` / 進捗: `docs/sync/STATUS.md`
> 統合チェック: `docs/sync/CHECKLIST.md` / テスト: `docs/sync/TESTING.md`

## 前提 (必読)

- リポジトリルート: `/Users/miharashouhei/null--nostr`
- 親ブランチ `sync/web-to-native-20260516` がチェックアウトされていること
- AGENTS.md の制約を厳守 (Keychain / EncryptedSharedPreferences / actor)
- iOS は NIP-46 のみ (Amber 不可)
- このセッションは **設計のみ**。コード変更は最小限 (新規 `docs/` ファイルのみ)

## 作業ブランチを切る

```bash
git checkout sync/web-to-native-20260516
git pull --ff-only
git checkout -b sync/web-to-native-20260516/s10a-stt-design
```

## 目的

S10B (Android) と S10C (iOS) の実装を並行で進められるよう、**API 仕様・データ構造・鍵保管・エラーハンドリング** を 1 ドキュメントに固める。

## 前提読み物

- `hooks/useSTT.js` (Web 実装、116 行)
- `app/api/elevenlabs/token/route.js` (Web 用 server proxy。Native は使わない)
- `components/PostModal.js` の STT 統合部分
- `components/TalkTab.js` の STT 統合部分
- ElevenLabs Scribe streaming API ドキュメント (`@elevenlabs/react` の依存先)

## タスク

1. **`docs/sync/research/r10-elevenlabs-stt.md` を完成させる** (Session 2 で着手済み)
   - エンドポイント URL / 認証方式
   - 入力音声フォーマット (PCM 16kHz mono を想定)
   - WebSocket メッセージスキーマ (partial transcript / final transcript / commit / error)
   - 言語コード (`jpn` / `eng`)
   - 無音検出パラメータ (Web 実装の閾値を確認)
2. **`docs/sync/research/r10-stt-key-storage.md` を新規作成**
   - Android: `EncryptedSharedPreferences` (`AndroidX Security`) を使用
   - iOS: Keychain (`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`)
   - キー入力 UI の動線 (ElevenLabsSettings 画面)
3. **`docs/sync/research/r10-stt-error-spec.md` を新規作成**
   - 権限拒否 / API キー未設定 / 401 / 429 / WS タイムアウト / WS 切断時の自動再接続
   - ユーザー向けメッセージ (日本語)
4. **`docs/sync/fixtures/stt/sample-ws-frames.json` を作成**
   - ElevenLabs から想定される WS フレームのサンプル (S10B/C のテストで再利用)
5. `research/INDEX.md` の F-10a / F-10b / F-10c 行に「対象 ✅」を記入

## Acceptance Criteria

- [ ] 上記 4 ドキュメント (`r10-elevenlabs-stt.md`, `r10-stt-key-storage.md`, `r10-stt-error-spec.md`, `sample-ws-frames.json`) が揃う
- [ ] S10B / S10C 担当が「これを読めば実装できる」状態
- [ ] `research/INDEX.md` に「対象」記入 + 凍結 sign-off 欄チェック

## 完了処理

1. `docs/sync/STATUS.md` の S10A 行を ✅
2. `git commit -m "docs(sync/s10a): STT API/key/error spec を確定"`
3. PR を親ブランチへ
