# Session 07: SignUp UX: 手動リージョン選択 + リレー検出強化

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
git checkout -b sync/web-to-native-20260516/s07-<topic>
```

## 目的

Web の `aa2ddc2 feat: enhance signup with manual region selection and improved relay detection` (2026-02-22) を Android / iOS に反映する。

## 前提読み物

- `docs/sync/research/r07-signup.md` (Session 2)
- Web: `components/SignUpModal.js` + `lib/geohash.js`
- Android: `ui/components/SignUpModal.kt`, `data/GeohashUtils.kt`
- iOS: `Views/Screens/LoginView.swift` (SignUp フローは LoginView 内 sheet と思われる、要確認)

## Web 仕様 (要点)

1. ユーザがリージョン (例: 日本/北海道, 日本/関東, ... or 大陸単位) を **手動で選択可能**
2. リージョン → geohash prefix → 推奨リレー一覧 (例: 日本なら yabu.me / nostr.wirednet.jp / r.kojira.io) を自動セット
3. 既定: 端末ロケール (or 位置情報) から推定、ただし最終決定はユーザ
4. selected geohash を kind 0 metadata の `g` フィールドに保存

## Android タスク

- `SignUpModal.kt`:
  - 国/地方の選択ドロップダウンを追加 (一旦は **国**: 日本/グローバル の 2 択でもよい)
  - 選択 → `GeohashUtils.kt` で prefix 取得 → リレー候補表示
  - Sign up 完了時に kind 0 metadata の `g` に geohash 4-5 桁を保存
- AGENTS.md "BasicTextField + weight(1f)" の crash パターンを回避

## iOS タスク

- iOS の SignUp 動線を確認:
  - 既存 `LoginView.swift` 内に SignUp が無ければ新規 `SignUpView.swift` を切り出す
- リージョン選択を `Picker` または `Menu` で
- geohash 計算を Swift で実装 (新規 `Utilities/GeohashUtils.swift` を Web/`GeohashUtils.kt` 仕様に合わせて)
- kind 0 metadata 書き込みは `NostrRepository+Profiles.swift` の `updateProfile` 経由

## Acceptance Criteria

- [ ] 新規アカウント作成画面でリージョンが選べる
- [ ] 選択に応じて推奨リレー (yabu.me 等) が自動チェック
- [ ] 完了後の自分の kind 0 metadata に `g` フィールドがある (npub プロフィール ↻ で確認)
- [ ] Web と同じ region → geohash 対応表に基づいている

## 落とし穴

- iOS は Passkey に関連するロジック (`ee4e0ab`) は **移植しない** (Web 専用)
- Android はサインアップ完了直後の鍵保存とリレー初回接続の順序を破壊しない (`v1.4.7` で修正済)
- リージョン選択はあくまで補助。手動リレー編集 (RelaySettings) は引き続き有効

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
