# Session 10B: ElevenLabs STT — Android 実装

> このプロンプトは null--nostr の **Native → Web 同期** ワークフローの一部です (S10 系のみ **方向反転: Web → Native**)。
> 親ブランチ: `sync/native-to-web-20260516`
> 設計書: `docs/sync/DESIGN.md` / プラン: `docs/sync/PLAN.md` / 進捗: `docs/sync/STATUS.md`
> 統合チェック: `docs/sync/CHECKLIST.md` / テスト: `docs/sync/TESTING.md`
> 前提セッション: **S10A 完了後**

## 前提 (必読)

- S10A の成果物 (`docs/sync/research/r10-*.md`, `docs/sync/fixtures/stt/*.json`) を熟読
- AGENTS.md の制約を厳守 (Keychain / EncryptedSharedPreferences / actor / 140 char / LineSeedJP)
- iOS は NIP-46 のみ (Amber 不可)
- **方向**: Web (`hooks/useSTT.js`) が source of truth、Native (Android/iOS) を追従させる

## 作業ブランチを切る

```bash
git checkout sync/native-to-web-20260516
git pull --ff-only
git checkout -b sync/native-to-web-20260516/s10b-stt-android
```


## 目的

Android で ElevenLabs Scribe streaming STT を統合し、PostModal と TalkScreen の入力欄からマイクボタンで音声入力できるようにする (Web の `hooks/useSTT.js` と同じ振る舞い)。

## タスク

1. **`android/app/src/main/kotlin/io/nurunuru/app/data/ElevenLabsSttService.kt` 新規作成**
   - WebSocket クライアント (OkHttp)
   - PCM 16kHz mono 採取 (`AudioRecord`)
   - partial transcript を `Flow<SttEvent>` で emit (sealed class: `Partial(text)`, `Final(text)`, `Error(msg)`)
   - 無音 1 秒で auto-commit (S10A の閾値仕様に従う)
   - 全 IO は `Dispatchers.IO`
2. **`android/app/src/main/kotlin/io/nurunuru/app/data/prefs/AppPreferences.kt` 拡張**
   - `elevenlabsApiKey` を **EncryptedSharedPreferences** で保存 (`MasterKey` + AES256)
   - `elevenlabsLanguage` (`"jpn"` / `"eng"`)
3. **`android/app/src/main/kotlin/io/nurunuru/app/ui/components/PostModal.kt` 編集**
   - ツールバーにマイクアイコン追加
   - クリック → `ElevenLabsSttService` 開始 / 再クリックで停止
   - partial transcript を pending text として表示 (final で BasicTextField に append)
4. **`android/app/src/main/kotlin/io/nurunuru/app/ui/screens/TalkScreen.kt` 同様の配線**
5. **権限**:
   - `AndroidManifest.xml` に `android.permission.RECORD_AUDIO` 追加
   - 動的リクエスト (拒否時はトーストで誘導、S10D で正式モーダル化)
6. **テスト** (`docs/sync/TESTING.md` §1 S10B):
   - JUnit + Robolectric: 無音 1 秒で `onCommit` が呼ばれる
   - WS 切断時の自動再接続 (3 回まで指数バックオフ)
   - fixture `sample-ws-frames.json` を使った integration test

## Acceptance Criteria

- [ ] PostModal でマイクボタン → 日本語音声 → リアルタイムで partial 表示 → 無音で final commit → BasicTextField に追記
- [ ] TalkScreen でも同様
- [ ] `./gradlew assembleDebug` グリーン
- [ ] `./gradlew test` グリーン (新規テスト含む)
- [ ] 既存 `ElevenLabsSettings` の TTS 設定を破壊していない
- [ ] API キーが logcat / SharedPreferences (平文) に出ていない (`adb logcat | grep -i "elevenlabs"` で確認)
- [ ] Web 版 (`hooks/useSTT.js`) と同じ閾値・WS フレーム解釈になっている

## 完了処理

1. `docs/sync/STATUS.md` の S10B 行を ✅ (Android のみ)
2. `CHANGELOG.md` の未リリース節に `### Added (Android): ElevenLabs STT (音声入力) を投稿/トーク入力欄に追加` を追記
3. `git commit -m "sync(s10b): Android ElevenLabs STT 統合 — Web→Native"`
4. `docs/sync/PR_TEMPLATE.md` を貼って PR

## 落とし穴

- `AudioRecord` の buffer size は `AudioRecord.getMinBufferSize()` を使う
- ForegroundService は **不要** (前面で録音している間のみ動作)。ただし通知バッジで録音中を明示すると親切
- API キーログ漏洩: `Log.d` / `Log.i` でも `apiKey` を出さない (release では Proguard で除去されない)
- BasicTextField で composition 中の append は IME を壊す → AGENTS.md の IME バグ対応と同じく composition state 中はスキップ
