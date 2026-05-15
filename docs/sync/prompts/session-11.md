# Session 11: FFI 再ビルド + token sync + 動作確認

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
git checkout -b sync/web-to-native-20260516/s11-<topic>
```

## 目的

Session 3〜10 で Rust core / FFI に変更が入った場合の再ビルド、design-tokens 同期、3 プラットフォームでの最終動作確認をまとめて行う。

## タスク

### Rust FFI 再ビルド (もし `lib.rs` / `engine.rs` を変更したセッションがあれば)

```bash
# 1. Kotlin bindings 再生成
cd rust-engine/nurunuru-ffi && bash bindgen/gen_kotlin.sh

# 2. Android arm64 cross-compile
AR_aarch64_linux_android=/home/n/Android/Sdk/ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar \
  cargo build --release --target aarch64-linux-android -p nurunuru-ffi

# 3. .so コピー
cp rust-engine/target/aarch64-linux-android/release/libuniffi_nurunuru.so \
   rust-engine/nurunuru-ffi/android/libs/arm64-v8a/

# 4. iOS XCFramework 再生成 (必要なら)
# (手順は ios/Makefile or 既存スクリプト参照)
```

### Token sync

```bash
npm run tokens
git diff lib/constants.generated.js android/app/src/main/kotlin/io/nurunuru/app/data/Constants.kt ios/NuruNuru/Utilities/Constants.swift
```

差分があれば commit。

### 全プラットフォーム build & test

```bash
npm run test
cd android && ./gradlew assembleDebug && cd ..
cd ios && xcodebuild -scheme NuruNuru -destination 'platform=iOS Simulator,name=iPhone 17' -skipPackagePluginValidation build && cd ..
cd ios && xcodebuild -scheme NuruNuru -destination 'platform=iOS Simulator,name=iPhone 17' -skipPackagePluginValidation test && cd ..
```

### 結合確認 (smoke)

| 機能 | Android | iOS |
|---|---|---|
| アカウント新規作成 (Session 7) | ⬜ | ⬜ |
| Following → Recommended ロード順 (S4) | ⬜ | ⬜ |
| 誕生日通知 (S5) | ⬜ | ⬜ |
| Reaction picker (S3) | ⬜ | ⬜ |
| MiniApp タブ順序 (S6) | ⬜ | ⬜ |
| ElevenLabs STT (S10) | ⬜ | ⬜ |

## Acceptance Criteria

- [ ] 全 build / test がグリーン
- [ ] `rust-engine/nurunuru-ffi/bindgen/kotlin-out/uniffi/nurunuru/nurunuru.kt` が最新 (FFI 変更があれば)
- [ ] `design-tokens/constants.json` と生成物が一致
- [ ] スモーク 6 機能を 3 プラットフォームで確認

## 落とし穴

- AR_aarch64_linux_android は **必ず** 環境変数で渡す (cc-rs 制約 / AGENTS.md 参照)
- Rust 変更時 `.kt` の commit 忘れは AGENTS.md "Generated .kt must be committed" に違反

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
