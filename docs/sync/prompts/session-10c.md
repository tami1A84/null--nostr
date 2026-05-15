# Session 10C: ElevenLabs STT — iOS 実装

> このプロンプトは null--nostr の **Web → Native 同期** ワークフローの一部です。
> 親ブランチ: `sync/web-to-native-20260516`
> 設計書: `docs/sync/DESIGN.md` / プラン: `docs/sync/PLAN.md` / 進捗: `docs/sync/STATUS.md`
> 前提セッション: **S10A 完了後** (S10B と並行可)

## 前提 (必読)

- S10A の成果物 (`docs/sync/research/r10-*.md`, `docs/sync/fixtures/stt/*.json`) を熟読
- AGENTS.md: `actor` / Keychain / `@Observable` / iOS 17+
- ios/GUARDRAILS.md

## 作業ブランチを切る

```bash
git checkout sync/web-to-native-20260516
git pull --ff-only
git checkout -b sync/web-to-native-20260516/s10c-stt-ios
```

## 目的

iOS で ElevenLabs Scribe streaming STT を統合し、PostSheet と TalkView の入力欄からマイクボタンで音声入力できるようにする。

## タスク

1. **`ios/NuruNuru/Data/ElevenLabsSttService.swift` 新規作成**
   - `actor ElevenLabsSttService` で thread-safe 化
   - `URLSessionWebSocketTask` で接続
   - `AVAudioEngine` で PCM 16kHz mono 採取
   - `AsyncStream<SttEvent>` を public method として expose (`enum SttEvent { case partial(String), final(String), error(String) }`)
   - 無音 1 秒で auto-commit
2. **`ios/NuruNuru/Data/AppPreferences.swift` 拡張 + Keychain 保管**
   - 専用ヘルパー (例: `SecureKeyManager` または `KeychainStore`) で API キー read/write
   - `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`
   - 言語コードは UserDefaults で OK
3. **`ios/NuruNuru/Views/Sheets/PostSheet.swift` 編集**
   - ツールバーにマイクボタン (`Image(systemName: "mic")`)
   - タップ → ViewModel 経由で actor を起動
   - partial を別 `@State` にバインド、final で本文 `@Binding` に append
4. **`ios/NuruNuru/Views/Screens/TalkView.swift` 同様の配線**
5. **権限**:
   - `Info.plist` に `NSMicrophoneUsageDescription` を追加 (なければ)
   - `AVAudioSession.sharedInstance().requestRecordPermission` で動的取得
6. **AVAudioSession**:
   - `.playAndRecord` モード (録音中も TTS の出力が可能なら望ましい)
   - Bluetooth ヘッドセット対応 (`.allowBluetoothA2DP, .allowBluetooth`)
7. **テスト** (`docs/sync/TESTING.md` §1 S10C):
   - XCTest: `AVAudioSession` モックで PCM 16kHz が emit される
   - actor の race condition テスト (concurrent call で state が壊れない)
   - fixture `sample-ws-frames.json` を使った decode テスト

## Acceptance Criteria

- [ ] PostSheet でマイクボタン → 日本語音声 → リアルタイムで partial 表示 → 無音で final commit
- [ ] TalkView でも同様
- [ ] `xcodebuild ... build` グリーン
- [ ] `xcodebuild ... test` グリーン
- [ ] 既存 `ElevenLabsTTSService` (TTS) を破壊していない
- [ ] API キーが UserDefaults / ログに出ていない (Console.app で確認)

## 完了処理

1. `docs/sync/STATUS.md` の S10C 行を ✅ (iOS のみ)
2. `CHANGELOG.md` の未リリース節に `### Added (iOS): ElevenLabs STT (音声入力) を投稿/トーク入力欄に追加` を追記
3. `git commit -m "sync(s10c): iOS ElevenLabs STT 統合 — Web→Native"`
4. `docs/sync/PR_TEMPLATE.md` を貼って PR

## 落とし穴

- `@Observable` ViewModel から actor を呼ぶときは `await`、UI 更新は `@MainActor`
- AGENTS.md「@StateObject 不可」/ Combine 不可
- WebSocket の URL が `wss://` でない場合は ATS でブロックされる → `NSAppTransportSecurity` 設定不要 (wss は HTTPS 扱い)
- フォアグラウンド録音中にアプリがバックグラウンドへ → `AVAudioSession` interruption ハンドラで一時停止
