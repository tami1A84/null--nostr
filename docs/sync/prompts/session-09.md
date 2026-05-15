# Session 09: ProofMode / DivineVideoRecorder: Web → Android 差分反映 (iOS 対象外)

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
git checkout -b sync/web-to-native-20260516/s09-<topic>
```

## 目的

Web の `43d1514` / `122298c` / `006ce80` 系 (2026-02-23) で導入された **diVine 互換 6.3 秒ループ動画 + ProofMode (C2PA-like 真正性証明)** の最終仕様を Android に反映する。**iOS は App Store 審査の都合で Rokunana を除外しているため対象外**。

## 前提読み物

- `docs/sync/research/r09-divine-proofmode.md` (Session 2)
- Web: `components/DivineVideoRecorder.js`, `lib/proofmode.js`, `components/PostItem.js`, `components/PostModal.js`, `components/TimelineTab.js`, `components/UserProfileView.js`
- Android: `ui/components/DivineVideoRecorder.kt`, `data/ProofModeManager.kt`, `ui/components/PostContent.kt`

## Web 仕様 (要点)

1. **6.3 秒ループ動画**: VP9/H.264, max 6.3s, ループ表示, kind 1 投稿に `["imeta", ...]` で添付
2. **ProofMode**: 録画時に SHA-256 + メタデータ (位置情報除外) を OpenPGP で署名 → 投稿に `["proof", "<base64>"]` タグ付与
3. **再生 UI**: PostItem / PostContent 内でタップでミュート解除、長押しでプロフィール

## Android タスク

- `DivineVideoRecorder.kt`:
  - 録画時間 6.3s に統一 (現状の値を確認)
  - 出力フォーマット (H.264, mp4) を Web と一致させる
- `ProofModeManager.kt`:
  - SHA-256 計算 → OpenPGP/PGP 署名 → tag 生成までを Web の `lib/proofmode.js` と一致させる
  - 位置情報メタデータの除外を確認
- `PostContent.kt` の動画再生 UI:
  - タップでミュート解除のジェスチャ
  - PostItem 内での "ループ動画バッジ" 表示

## iOS タスク

- **対象外**。`docs/sync/STATUS.md` の S9 行 iOS 列に "N/A" と記入し、理由 ("App Store UGC 審査で Rokunana 除外中") を備考に書く

## Acceptance Criteria

- [ ] Android で 6.3s ループ動画を撮影 → 投稿 → タイムラインで再生できる
- [ ] 投稿 event に `["proof", "..."]` タグが含まれる (rust client log で確認)
- [ ] 位置情報が proof tag に含まれていない
- [ ] iOS は対象外を STATUS.md / CHANGELOG.md に明記

## 落とし穴

- Android: CameraX の解像度プリセットによっては 6.3s で 50MB を超える → ビットレート制限を入れる
- ProofMode: OpenPGP 鍵は **Nostr 秘密鍵とは別** (Web 実装では別途生成・キャッシュ)。Native でも同様に
- Rust FFI は変更不要 (これらは Native アプリ層で完結)

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
