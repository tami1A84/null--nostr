# Session 05: Birthday 通知 + 相互フォロー Zap 通知

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
git checkout -b sync/native-to-web-20260516/s05-<topic>
```


## 目的

Native (Android `NotificationModal.kt`) で既に動作する誕生日通知 + 相互フォロー Zap 通知を Web の `components/NotificationModal.js` に反映する。iOS 側は `HomeViewModel.swift` に birthday フィールドのみある状態 → 必要なら本セッションで補完する。

## 前提読み物

- `docs/sync/research/r05-birthday-notif.md` (Session 2)
- Native (Android): `ui/components/NotificationModal.kt`, `data/NostrRepositoryNotifications.kt`
- Native (iOS): `Views/Sheets/NotificationSheet.swift`, `Data/NostrRepository+Notifications.swift` (要拡張可能性)
- Web: `components/NotificationModal.js`, `lib/cache.js`, `lib/nostr.js`

## Native 仕様 (要点)

1. **誕生日通知**:
   - 自分のフォローしているユーザの誕生日 (kind 0 metadata の `birthday` フィールド) が今日と一致したら通知一覧に表示
   - 1 日 1 回まで (キャッシュキー: `nurunuru_birthday_notif_<YYYYMMDD>_<pubkey>`)
2. **相互フォロー Zap 通知**:
   - 受け取った Zap (kind 9735) の送信者が自分のフォロー、かつ自分も相手をフォロー (= 相互) の場合、通常 Zap 通知に "★相互フォロー" バッジを付与

## Web タスク

- `components/NotificationModal.js`:
  - 通知タイプに `BIRTHDAY`, `ZAP_MUTUAL` を追加
  - 各タイプのアイコン/スタイルを Native と一致させる (🎂 emoji + ★相互フォロー バッジ)
- `lib/nostr.js` の通知集約ループに以下を追加:
  - フォロー pubkey 一覧から kind 0 metadata を取り出し `birthday` が今日と一致するか判定
  - localStorage に "今日表示済み pubkey set" を保持
- 相互フォロー判定: `followedByMe(pubkey) && pubkey in followsMe`
- birthday フィールドの型正規化: string("MM-DD" / "YYYY-MM-DD" / "YYYY/MM/DD") / object {month, day} / object {year, month, day}

## iOS 補完タスク (必要時)

- iOS `NotificationSheet.swift` に Birthday/ZapMutual の表示が無ければ追加
- `Data/NostrRepository+Notifications.swift` を拡張 (Android と同じロジック)
- `UserDefaults` に "今日表示済み pubkey set" を保持
- ローカライズ済みラベル (`お誕生日おめでとう` / `★相互フォロー`)

## Acceptance Criteria

- [ ] Web でテストユーザ (誕生日を今日に設定) をフォロー → 通知タブに 🎂 が出る
- [ ] Web でテストユーザから Zap → 相互フォロー状態で "★相互フォロー" バッジ表示
- [ ] 翌日に再起動しても重複通知が出ない (キャッシュキーで防止)
- [ ] iOS 補完した場合は `NotificationSheet` が `@Observable` ViewModel 経由で更新される
- [ ] `vitest src/__tests__/birthday.test.ts` (新設) で正規化 golden test グリーン

## 落とし穴

- birthday フィールドの型は kind 0 metadata で string("MM-DD" / "YYYY-MM-DD") / object 各種混在 (Android `AuthViewModel` で吸収済み)。同等の正規化を Web でも入れる
- Web: notification の重複防止 cache key を Native と完全一致させる (キー名互換性は不要だが、振る舞いは一致)
- iOS: `NotificationSheet` が SwiftUI `.sheet` で開かれている前提

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
