# Session 05: Birthday 通知 + 相互フォロー Zap 通知

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
git checkout -b sync/web-to-native-20260516/s05-<topic>
```

## 目的

Web の `26ef9ea feat: add zap and mutual follow birthday notifications` (2026-02-23) を Android / iOS の通知センターに反映する。

## 前提読み物

- `docs/sync/research/r05-birthday-notif.md` (Session 2)
- `components/NotificationModal.js`, `lib/cache.js`, `lib/nostr.js` の該当 diff
- Android: 既に `AuthViewModel.kt` 等に birthday フィールドあり (kind 0 metadata)
- iOS: `HomeViewModel.swift` に birthday フィールドあり

## Web 仕様 (要点)

1. **誕生日通知**:
   - 自分のフォローしているユーザの誕生日 (kind 0 metadata の `birthday` フィールド) が今日と一致したら通知一覧に表示
   - 1 日 1 回まで (キャッシュキー: `nurunuru_birthday_notif_<YYYYMMDD>_<pubkey>`)
2. **相互フォロー Zap 通知**:
   - 受け取った Zap (kind 9735) の送信者が自分のフォロー、かつ自分も相手をフォロー (= 相互) の場合、通常 Zap 通知に "★相互フォロー" バッジを付与

## Android タスク

- `ui/components/NotificationModal.kt`:
  - 通知タイプ enum に `BIRTHDAY`, `ZAP_MUTUAL` を追加
  - `NotifStyle` per type のスタイル定義を追加
  - 誕生日アイコン (例: 🎂 emoji or vector) を割り当て
- `data/NostrRepositoryNotifications.kt` の通知集約ループに以下を追加:
  - フォロー pubkey 一覧から kind 0 metadata を取り出し `birthday` が今日と一致するか判定
  - SharedPreferences に "今日表示済み pubkey set" を保持
- 相互フォロー判定: `followedByMe(pubkey) && pubkey in followsMe` (followsMe は自分の pubkey の `#p` を持つ kind 3)

## iOS タスク

- `Views/Sheets/NotificationSheet.swift`:
  - 通知タイプに `birthday`, `zapMutual` を追加
  - ローカライズ済みラベル (`お誕生日おめでとう` / `★相互フォロー`) を NuruColors / NuruTypography で
- `Data/NostrRepository+Notifications.swift`:
  - 同等ロジック。`UserDefaults` に "今日表示済み pubkey set" を保持
- 通知タップ時にユーザプロファイルを開く動線は既存と同じ

## Acceptance Criteria

- [ ] テストユーザ (誕生日を今日に設定) をフォローし、通知タブに 🎂 が出る
- [ ] テストユーザから Zap を受け取り、相互フォロー状態で "★相互フォロー" バッジが表示
- [ ] 翌日に再起動しても重複通知が出ない (キャッシュキーで防止)
- [ ] iOS は NotificationSheet が `@Observable` ViewModel 経由で更新されること

## 落とし穴

- birthday フィールドの型は kind 0 metadata で string("MM-DD" / "YYYY-MM-DD") / object 各種混在 (Web の `00f6928` で吸収済み)。同等の正規化を Native でも入れる
- Android: NotificationModal 既存の "30s background polling" を破壊しないこと
- iOS: NotificationSheet が SwiftUI `.sheet` で開かれている前提 (full screen でない)

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
