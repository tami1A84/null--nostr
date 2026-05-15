# Session 10: ElevenLabs STT (投稿/トークの音声入力)

> このプロンプトは null--nostr の **Web → Native 同期** ワークフローの一部です。
> 親ブランチ: `sync/web-to-native-20260516`
> 設計書: `docs/sync/DESIGN.md` / プラン: `docs/sync/PLAN.md` / 進捗: `docs/sync/STATUS.md`

## 前提 (必読)

- リポジトリルート: `/Users/miharashouhei/null--nostr`
- 親ブランチ `sync/web-to-native-20260516` がチェックアウトされていること
- AGENTS.md の制約を厳守: 投稿140文字 / Keychain / actor / LineSeedJP / Compose の crash パターン
- 同期は **逐語コピーではない** ─ 各プラットフォームのイディオムで再現する
- iOS は NIP-46 のみ (Amber 不可) / iOS は Rokunana を App Store 審査で除外中 (Session 9 注意)

## 作業ブランチを切る

```bash
git checkout sync/web-to-native-20260516
git pull --ff-only
git checkout -b sync/web-to-native-20260516/s10-<topic>
```

## 目的

Web の `84017cc` / `e529291` / `8b109c9` (2026-02-23) で導入された **ElevenLabs Speech-to-Text** による投稿モーダル・トーク入力欄の音声入力を Android / iOS に反映する。Android は既に `ElevenLabsSettings.kt` を持つが STT は未統合 (要確認)。iOS は `ElevenLabsTTSService.swift` (TTS のみ) しかない。

## 前提読み物

- `docs/sync/research/r10-elevenlabs-stt.md` (Session 2)
- Web: `hooks/useSTT.js`, `components/PostModal.js`, `components/TalkTab.js`, `components/TimelineTab.js`, `app/api/elevenlabs/token/route.js`
- Android: `ui/miniapps/ElevenLabsSettings.kt`, `data/prefs/AppPreferences.kt`
- iOS: `Data/ElevenLabsTTSService.swift`, `Views/MiniApps/ElevenLabsSettingsView.swift`

## Web 仕様 (要点)

1. ElevenLabs Scribe API (`@elevenlabs/react`) を使ったストリーミング STT
2. リアルタイムで部分文字列が見えること、無音検出で自動 commit
3. 言語選択 (jpn/eng) を localStorage に永続化
4. 投稿モーダル / トーク入力欄のマイクアイコンから起動
5. **API キー**: ユーザが ElevenLabsSettings で自分のキーを入れる (Web 側はサーバ proxy で短命トークン発行)

## Android タスク

- 新規 `data/ElevenLabsSttService.kt`:
  - WebSocket で ElevenLabs Scribe streaming endpoint に接続
  - PCM 16kHz mono を送信、partial transcript を Flow<String> で emit
  - 無音検出 (1秒間音量閾値以下) で auto-commit
- `ui/components/PostModal.kt` のマイクアイコン:
  - クリック → STT セッション開始 / 停止 toggle
  - 部分文字列を BasicTextField のテキストに append (重複防止に "committed" / "pending" 分離)
- `ui/screens/TalkScreen.kt` の入力欄に同様
- `AppPreferences.kt` に言語 ("jpn"/"eng") + API キーの永続化
- 録音権限 (`RECORD_AUDIO`) の動的リクエスト

## iOS タスク

- 新規 `Data/ElevenLabsSttService.swift`:
  - URLSessionWebSocketTask で同様に streaming
  - AVAudioEngine で 16kHz mono PCM 採取
  - actor 化推奨 (`actor ElevenLabsSttService`)
- `Views/Sheets/PostSheet.swift` のマイクボタン
- `Views/Screens/TalkView.swift` 入力欄
- `AppPreferences.swift` / Keychain (API キー) で永続化
- 録音権限 (`NSMicrophoneUsageDescription`) を Info.plist に追加 (既にあれば確認)

## Acceptance Criteria

- [ ] Android: PostModal のマイクで日本語音声が認識され、リアルタイムで textfield に流入
- [ ] iOS: PostSheet のマイクで同様に動作
- [ ] 無音 1 秒で自動 commit
- [ ] ElevenLabs API キー未設定時は "API キーを設定してください" モーダル → ElevenLabsSettings へ誘導
- [ ] 録音権限拒否時のフォールバック表示

## 落とし穴

- Android: `Dispatchers.IO` で WS 通信、UI 更新は `withContext(Dispatchers.Main)`
- iOS: `AVAudioSession` の `.playAndRecord` モード設定。Bluetooth ヘッドセット対応も
- API キーを **絶対に** logs / UserDefaults に出さない (Keychain / EncryptedSharedPreferences)
- 既存 `ElevenLabsSettings` の TTS 設定を破壊しない

## 完了処理

1. `docs/sync/STATUS.md` の該当行をチェック (Android / iOS それぞれ)
2. `CHANGELOG.md` に **(Android)** / **(iOS)** タグ付きで 1 行追加
3. ビルド確認:
   - Android: `cd android && ./gradlew assembleDebug`
   - iOS: `cd ios && xcodebuild -scheme NuruNuru -destination 'platform=iOS Simulator,name=iPhone 17' -skipPackagePluginValidation build`
4. `git commit -m "sync(s<NN>): <topic> — Web→Native"`
5. サブブランチを親へ PR

## 質問テンプレ (実装中に詰まったら)

- 「Web の `<file>:<line>` の挙動が分からない」→ `git log -p` で当該変更の commit を読む
- 「Compose で `AnimatedVisibility` を使うと crash する」→ AGENTS.md の "AnimatedVisibility inside Box inside Column" 節
- 「iOS で `@StateObject` を使ってよいか」→ NG。iOS 17 `@Observable` を使う
