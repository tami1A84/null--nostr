<!-- docs/sync/PR_TEMPLATE.md -->
<!-- 各セッションのサブブランチ → 親ブランチ PR に流用してください。 -->

## このセッション

- セッション #: `sNN`
- セッションタイトル: `...`
- プロンプト: `docs/sync/prompts/session-NN.md`
- 関連 research: `docs/sync/research/rNN-*.md`
- 同期方向: Native → Web (S10 系のみ Web → Native)

## 仕様変更点 (1 行)

> 例: Recommended フィードでアイコンまたは表示名が無いユーザーを Web 側で除外し、Following を先に表示・Recommended を背景ロードする (Native と一致)。

## 影響範囲

- [ ] Web (`lib/` / `components/` / ...)
- [ ] Android (`android/app/...`)
- [ ] iOS (`ios/NuruNuru/...`)
- [ ] Rust core / FFI (`rust-engine/...`)
- [ ] design-tokens (`design-tokens/constants.json`)

## DoD (`docs/sync/CHECKLIST.md §3` を参照)

- [ ] Acceptance Criteria (プロンプト記載) 全部 ✅
- [ ] AGENTS.md 制約遵守 (140 char / actor / @Observable / Keychain / LineSeedJP / Compose crash パターン)
- [ ] `npm run tokens:check` グリーン (tokens 変更時)
- [ ] `npm run test` グリーン (Web 変更時)
- [ ] `npm run build` 通過 (Web 変更時)
- [ ] `./gradlew assembleDebug` グリーン (Android 変更時)
- [ ] `xcodebuild ... build` グリーン (iOS 変更時)
- [ ] FFI 再ビルド + `.kt` / `.swift` 生成物 commit (Rust 変更時)
- [ ] 新規/変更ファイルにテスト最低 1 ケース (`docs/sync/TESTING.md` 参照)
- [ ] `docs/sync/STATUS.md` 更新
- [ ] `CHANGELOG.md` に **(Web)** / **(Android)** / **(iOS)** タグ付きで 1 行追記
- [ ] スクリーンショット (UI 変更時) を `docs/sync/screenshots/sNN-*.png` に保存

## 実機検証 (`docs/sync/CHECKLIST.md §1`)

- [ ] Web ブラウザ: ブラウザ `____` / OS `____`
- [ ] Android 実機: 機種 `____` / OS `____`
- [ ] iOS 実機: 機種 `____` / iOS `____`
- [ ] 該当なし (理由: `____`)

## 競合リスク (`docs/sync/CHECKLIST.md §2`)

このセッションが触ったファイルで、他セッションも同時進行しているもの:

- `____________` ← 直列化済み / 要 rebase

## レビュー観点

- 観点 1: ...
- 観点 2: ...

## スクリーンショット

(該当時、`docs/sync/screenshots/sNN-*.png` を貼付。Native との pixel 比較推奨)
