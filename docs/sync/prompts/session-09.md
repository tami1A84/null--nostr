# Session 09: ProofMode / DivineVideoRecorder: Android → Web 反映 (iOS 対象外)

> このプロンプトは null--nostr の **Native → Web 同期** ワークフローの一部です。
> 親ブランチ: `sync/native-to-web-20260516`
> 設計書: `docs/sync/DESIGN.md` / プラン: `docs/sync/PLAN.md` / 進捗: `docs/sync/STATUS.md`
> 統合チェック: `docs/sync/CHECKLIST.md` / テスト: `docs/sync/TESTING.md`

## 前提 (必読)

- リポジトリルート: `/Users/miharashouhei/null--nostr`
- 親ブランチ `sync/native-to-web-20260516` がチェックアウトされていること
- AGENTS.md の制約を厳守: 投稿140文字 / Keychain / actor / LineSeedJP / Compose の crash パターン
- 同期は **逐語コピーではない** ─ Web のイディオム (React + Tailwind + nostr-tools) で再現する
- iOS は NIP-46 のみ (Amber 不可) / iOS は Rokunana を App Store 審査で除外中 (Session 9 注意)
- **方向**: Native (Android/iOS) が source of truth、Web を追従させる (S10 系のみ Web → Native)

## 作業ブランチを切る

```bash
git checkout sync/native-to-web-20260516
git pull --ff-only
git checkout -b sync/native-to-web-20260516/s09-<topic>
```


## 目的

Android で実装済みの **diVine 互換 6.3 秒ループ動画 + ProofMode (C2PA-like 真正性証明)** 仕様を Web (`lib/proofmode.js` + `components/DivineVideoRecorder.js` 等) に反映する。**iOS は App Store 審査の都合で Rokunana を除外しているため対象外**。

## 前提読み物

- `docs/sync/research/r09-divine-proofmode.md` (Session 2)
- Native (Android): `ui/components/DivineVideoRecorder.kt`, `data/ProofModeManager.kt`, `ui/components/PostContent.kt`
- Web: 既存があれば `components/DivineVideoRecorder.js`, `lib/proofmode.js`, `components/PostItem.js`, `components/PostModal.js`

## Native (Android) 仕様 (要点)

1. **6.3 秒ループ動画**: H.264, max 6.3s, ループ表示, kind 1 投稿に `["imeta", ...]` で添付
2. **ProofMode**: 録画時に SHA-256 + メタデータ (位置情報除外) を OpenPGP で署名 → 投稿に `["proof", "<base64>"]` タグ付与
3. **再生 UI**: PostItem / PostContent 内でタップでミュート解除、長押しでプロフィール

## Web タスク

- `components/DivineVideoRecorder.js` (なければ新設):
  - MediaRecorder API で録画 (max 6.3s)
  - 出力フォーマット (H.264 mp4) を Native と一致させる
- `lib/proofmode.js` (なければ新設):
  - SHA-256 計算 (Web Crypto API `crypto.subtle.digest`)
  - OpenPGP 署名 (`openpgp` JS ライブラリ。鍵は IndexedDB に保管、Nostr 秘密鍵とは別)
  - `["proof", "<base64>"]` タグ生成
  - 位置情報メタデータの除外を確認
- `components/PostItem.js` の動画再生 UI:
  - タップでミュート解除のジェスチャ
  - PostItem 内での "ループ動画バッジ" 表示

## iOS タスク

- **対象外**。`docs/sync/STATUS.md` の S9 行 iOS 列に "N/A" と記入し、理由 ("App Store UGC 審査で Rokunana 除外中") を備考に書く

## Native タスク

- 原則 **変更不要**

## Acceptance Criteria

- [ ] Web ブラウザで 6.3s ループ動画を撮影 → 投稿 → タイムラインで再生できる
- [ ] 投稿 event に `["proof", "..."]` タグが含まれる (ブラウザ DevTools のネットワーク or サーバログで確認)
- [ ] 位置情報が proof tag に含まれていない
- [ ] iOS は対象外を STATUS.md / CHANGELOG.md に明記
- [ ] `vitest src/__tests__/proofmode.test.ts` で SHA-256 一致テストグリーン

## 落とし穴

- Web: MediaRecorder のブラウザ互換 (Safari の対応状況。Codec 指定時は `isTypeSupported` で確認)
- Web: 6.3s 上限の境界 (6.2s OK / 6.4s reject) を録画タイマーで強制
- Web: OpenPGP 鍵は **Nostr 秘密鍵とは別**。IndexedDB or 専用ストアに保管 (localStorage は容量上限あり)
- Web: `crypto.subtle` は HTTPS 必須

## 完了処理

1. `docs/sync/STATUS.md` の該当行をチェック (Web / Android / iOS それぞれ)
2. `CHANGELOG.md` に **(Web)** / **(Android)** / **(iOS)** タグ付きで 1 行追加
3. ビルド確認:
   - Web: `npm run test && npm run build`
   - Android (変更時): `cd android && ./gradlew assembleDebug`
   - iOS (変更時): `cd ios && xcodebuild -scheme NuruNuru -destination 'platform=iOS Simulator,name=iPhone 17' -skipPackagePluginValidation build`
4. `git commit -m "sync(s<NN>): <topic> — Native→Web"`
5. サブブランチを親へ PR (template: `docs/sync/PR_TEMPLATE.md`)

## 質問テンプレ (実装中に詰まったら)

- 「Native の `<file>:<line>` の挙動が分からない」→ Android/iOS のソースを直接読む。`grep -r "FunctionName" android/app ios/NuruNuru`
- 「Web で同等ロジックをどこに置くか」→ `docs/sync/DESIGN.md §6 ファイル対応マッピング` を参照
- 「Compose で `AnimatedVisibility` を使うと crash する」→ AGENTS.md の "AnimatedVisibility inside Box inside Column" 節 (Native 側で確認時)
- 「iOS で `@StateObject` を使ってよいか」→ NG。iOS 17 `@Observable` を使う
