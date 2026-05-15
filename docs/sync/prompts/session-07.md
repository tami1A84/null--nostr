# Session 07: SignUp UX: 手動リージョン選択 + リレー検出強化

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
git checkout -b sync/native-to-web-20260516/s07-<topic>
```


## 目的

Native (Android `SignUpModal.kt` + `GeohashUtils.kt`) のリージョン選択 + 推奨リレー自動セット仕様を Web の `components/SignUpModal.js` に反映する。iOS は SignUp 動線が未完成な可能性があるため、必要なら `Views/Screens/SignUpView.swift` を新設する。

## 前提読み物

- `docs/sync/research/r07-signup.md` (Session 2)
- Native (Android): `ui/components/SignUpModal.kt`, `data/GeohashUtils.kt`
- Native (iOS): `Views/Screens/LoginView.swift` (SignUp フローの所在を要確認)
- Web: `components/SignUpModal.js` + `lib/geohash.js`

## Native 仕様 (要点)

1. ユーザがリージョン (例: 日本/北海道, 日本/関東, ... or 大陸単位) を **手動で選択可能**
2. リージョン → geohash prefix → 推奨リレー一覧 (例: 日本なら yabu.me / nostr.wirednet.jp / r.kojira.io) を自動セット
3. 既定: 端末ロケール (or 位置情報) から推定、ただし最終決定はユーザ
4. selected geohash を kind 0 metadata の `g` フィールドに保存

## Web タスク

- `components/SignUpModal.js`:
  - 国/地方の選択ドロップダウンを追加 (一旦は **国**: 日本/グローバル の 2 択でもよい)
  - 選択 → `lib/geohash.js` で prefix 取得 → リレー候補表示
  - Sign up 完了時に kind 0 metadata の `g` に geohash 4-5 桁を保存
- `lib/geohash.js` に `regionToGeohash()` 関数があるか確認、無ければ Native 仕様 (`GeohashUtils.kt`) と合わせて実装

## iOS 補完タスク (必要時)

- iOS の SignUp 動線確認:
  - 既存 `LoginView.swift` 内に SignUp が無ければ新規 `SignUpView.swift` を切り出す
- リージョン選択を `Picker` または `Menu` で
- geohash 計算を Swift で実装 (新規 `Utilities/GeohashUtils.swift` を Android `GeohashUtils.kt` 仕様に合わせて)
- kind 0 metadata 書き込みは `NostrRepository+Profiles.swift` の `updateProfile` 経由

## Acceptance Criteria

- [ ] Web で新規アカウント作成画面でリージョンが選べる
- [ ] 選択に応じて推奨リレー (yabu.me 等) が自動チェック
- [ ] 完了後の自分の kind 0 metadata に `g` フィールドがある (npub プロフィール ↻ で確認)
- [ ] Web/Android/iOS で同 region → 同 geohash 対応 (`docs/sync/fixtures/geohash/regions.json` を共有)
- [ ] iOS 補完した場合: `xcodebuild ... build` グリーン

## 落とし穴

- Web は Passkey 関連ロジックを残してよい (Web 専用機能なので削除しない)
- iOS は NIP-46 のみ (Amber 関連は移植しない)
- リージョン選択はあくまで補助。手動リレー編集 (RelaySettings) は引き続き有効

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
