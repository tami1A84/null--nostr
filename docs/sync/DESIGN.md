# Native → Web 同期 設計書

> **Branch**: `sync/native-to-web-20260516`
> **作成日**: 2026-05-16
> **対象**: Android (Kotlin/Compose) v1.4.9 と iOS (Swift/SwiftUI) 1.0.4 で実装済みの機能・修正を Web (Next.js) v1.0.0 に同期する作業全体
> **方向**: Native → Web (ネイティブが正、Web が追従)

---

## 1. 背景と目的

null--nostr は Web (Next.js PWA) / Android (Kotlin + Rust FFI) / iOS (Swift + UniFFI XCFramework) の 3 プラットフォームを並行で開発している。**現在はネイティブアプリ (Android v1.4.9 / iOS 1.0.4 build 5) が機能・UX で先行**しており、Web (v1.0.0) はまだ追いついていない領域がある。歴史的経緯では Web プロトタイプ → Native 移植のフェーズもあったが、本同期作業の対象期間 (直近) では Native が source of truth。

このドキュメントは以下 2 点を明確化する:

1. **どの Native 機能・修正が Web 未同期か** (gap analysis)
2. **どの順序で・どのセッションで同期するか** (delivery plan)

各セッションは [`prompts/session-N.md`](./prompts/) として独立したプロンプト化済みで、別 Goose セッションへ貼り付けて並行実行可能。

---

## 2. 用語

| 用語 | 意味 |
|---|---|
| **Web** | `lib/`, `components/`, `app/`, `hooks/`, `src/` (Next.js 15 + nostr-tools) |
| **Android** | `android/app/src/main/kotlin/io/nurunuru/app/` (Kotlin + Compose) |
| **iOS** | `ios/NuruNuru/` (Swift + SwiftUI, iOS 17+) |
| **FFI Core** | `rust-engine/nurunuru-core` + `nurunuru-ffi` (UniFFI bindings) |
| **同期** | Native 側の振る舞い (UX / 仕様 / バグ修正) を、Web のイディオム (React / Next.js / nostr-tools) で再現すること。コードの逐語コピーではない |

---

## 3. アーキテクチャ前提

```text
                ┌───────────────────────┐
                │   design-tokens/      │
                │   constants.json      │ ← single source of truth
                └──────────┬────────────┘
                           │  npm run tokens
        ┌──────────────────┼──────────────────┐
        ▼                  ▼                  ▼
 lib/constants.gen.js   Constants.kt      Constants.swift
        │                  │                  │
        ▼                  ▼                  ▼
   Web (Next.js)      Android (Compose)   iOS (SwiftUI)
        │                  │                  │
        │           ┌──────┴──────┐           │
        │           ▼             ▼           │
        │   nurunuru-ffi (UniFFI) │           │
        │           │             │           │
        │           ▼             ▼           │
        │   nurunuru-core (Rust, nostr-sdk 0.44)
        │
        └─→ nostr-tools, rx-nostr (JS) ────────────┘
```

### 3.1 同期の 3 レイヤー

| レイヤー | 同期方法 |
|---|---|
| **L1. Tokens / Constants** | `design-tokens/constants.json` を更新し `npm run tokens` で 3 プラットフォームに伝播 |
| **L2. Domain Logic** | 可能な限り `nurunuru-core` に集約 → FFI 経由で Android / iOS 共有。Web は同等ロジックを `lib/nostr.js` 等に保持 |
| **L3. UI / UX** | Web のイディオム (React + Tailwind) で実装。Native 側 (Compose / SwiftUI) のスペックを **pixel-spec** として参照する |

### 3.2 プラットフォーム制約 (AGENTS.md より厳守)

| 制約 | Web | Android | iOS |
|---|---|---|---|
| 投稿文字数 | 140 (collapse 閾値) | 140 (`PostModal.kt` 厳守) | 140 (`PostSheet.swift` 厳守) |
| 鍵保管 | `secure-key-store.js` クロージャ。`window.*` 露出禁止 | `SecureKeyManager.kt` (Keystore) | Keychain `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` 専用 |
| 外部署名 | NIP-07 / NIP-46 / Nosskey | NIP-55 (Amber) + NIP-46 | **NIP-46 のみ (Amber 不可)** |
| データレイヤ | `lib/cache.js` (LRU+localStorage) | `NostrRepository` 単一窓口 | `NostrRepository` を **`actor`** 化 |
| ViewModel | (なし、コンポーネントローカル) | Jetpack `ViewModel` + `StateFlow` | iOS 17 `@Observable` (Combine 不可) |
| フォント | Tailwind デフォルト + LINE Seed JP | `LineSeedJP` (`res/font/...`) | LINE Seed JP のみ (system font 禁止) |
| 接続上限 | 4 global / 2 per-relay | nurunuru-core 経由 | nurunuru-core 経由 |

---

## 4. Native → Web ギャップ分析

### 4.1 Native 先行実装 (代表例)

| 機能 | Android 状態 | iOS 状態 | Web 状態 |
|---|---|---|---|
| **Reaction picker UX**: ロング/シングルタップ + キャッシュ | ✅ `ReactionEmojiPicker.kt` | ✅ | ❓ Unicode quick row が残っている可能性 → S2 で確認 |
| **Recommendation: アイコン/名前無し除外** | ✅ `RecommendationEngine.kt` | ✅ `NostrRepository+Recommendation.swift` | ❓ |
| **Recommendation: Following 優先 + 背景ロード** | ✅ `TimelineViewModel` | ✅ `TimelineViewModel` | ❓ |
| **誕生日通知 (kind 0 birthday)** | ✅ `AuthViewModel`, `NotificationModal` | 部分 (`HomeViewModel` のみ) → S5 で完成 | ❓ Web は実装あり/なし要確認 |
| **相互フォロー Zap 通知バッジ** | ✅ | ✅ | ❓ |
| **MiniApp タブ: カテゴリ + フルスクリーン** | ✅ `SettingsScreen.kt` (エンタメ/ツール/その他) | ✅ `SettingsView.swift` + `Views/MiniApps/` | ❓ Web 順序が一致しているか要確認 |
| **SignUp: 手動リージョン + relay 推奨** | ✅ `SignUpModal.kt` + `GeohashUtils.kt` | 部分 (要 `SignUpView.swift` 新設) | ❓ |
| **NIP-EE (MLS) Talk** | ✅ `TalkViewModel` 経由 `nurunuru-ffi` | ✅ 同様 | ❌ Web 未対応 (調査して優先度判定) |
| **ProofMode (OpenPGP) + Divine 6.3s ループ** | ✅ `ProofModeManager.kt` + `DivineVideoRecorder.kt` | **対象外** (App Store 審査) | ❓ Web に類似があるか要確認 |
| **Outbox model (NIP-65)** | ✅ `OutboxModel.kt` + `RelayDiscovery.kt` | ✅ `NostrRepository+Backup.swift` | 部分 (`lib/outbox.js`) |
| **Notification 30s polling + animated pill** | ✅ `NotificationModal.kt` | ✅ `NotificationSheet` | ❓ |
| **Birdwatch / 長文ノート / URLPreview** | ✅ | ✅ | ✅ (大体揃っている) |
| **NIP-46 (Nostr Connect) 外部署名** | ✅ | ✅ `ExternalSigner.swift` | 部分 (Web は NIP-07 中心) |

> ❓ = 本セッションで差分調査が必要な項目。**Session 2** で Native 実装と Web 実装を突合し、移植要否を確定する。

### 4.2 例外: Web 先行 / Web 専用 (同期対象外)

| 機能 | 理由 |
|---|---|
| ElevenLabs **STT** (音声入力 hooks/useSTT) | Web 先行実装。**S10 系は逆方向で扱う** (Web → Android/iOS) — INDEX.md でフラグ管理 |
| Passkey / WebAuthn | Web 専用 (Native 非対応) |
| Next.js 16 ビルド対応 | Web 専用 |
| サーバ proxy (`app/api/*`) | Web 専用 (Native はクライアント直叩き) |

> ⚠️ **STT について**: 当初プランでは Native → Web として組まれていたが、コード調査では Web 側が先行している。Session 2 で確定し、**逆方向 (Web → Android/iOS)** として S10A〜D を扱う。INDEX.md の方向欄で明示。

---

## 5. 同期戦略

### 5.1 原則

1. **Source of Truth は Native 実装** (Android/iOS が一致していれば Native 仕様、片方しかない場合はそれを起点に)
2. **片プラットフォーム単独 PR を許可**: Web 単独 PR、または Android↔iOS 差分解消を伴う Web 同期。マトリクス更新を必須にする
3. **constants.json 経由で動かす**: 数値・ラベル・閾値は `design-tokens/constants.json` に集約し、Native 値を Web に伝播
4. **段階的に小さく**: 1 セッション = 1〜3 機能。差分が大きい機能 (例: NIP-EE Talk, STT) は調査セッションを分離する
5. **テスト**: 既存 `vitest` (Web) / Android Unit テスト / Xcode テストはそれぞれ同期したロジックに対して 1 ケース以上追加する。Native と Web で同 fixture を共有
6. **AGENTS.md 制約を破らない**: actor / @Observable / Keychain / 140 char / LINE Seed JP / 外部署名種別

### 5.2 リスク

| リスク | 影響 | 緩和策 |
|---|---|---|
| Native の挙動が Android と iOS で食い違っている | Web 側の正解が分からない | Session 2 で Android/iOS 双方を読み、差分があれば仕様寄せ先を判断 |
| iOS Rokunana を App Store 審査で除外している | DivineVideoRecorder 系の "Native source" は Android のみ | iOS 側に同機能があるか毎回確認、無ければ Android を仕様とする |
| Web に Passkey 等の Native 非対応機能がある | Web 起点の機能を消してしまう | INDEX.md の「対象外」節に明示 |
| Rust FFI 変更が必要になる | Android `.so` / iOS `.xcframework` の再生成 + Web は対象外 | 専用セッション (Session 11) で集約 |
| connection-manager v1.4.8 の修正が Web 固有か Native にも要反映か | Web 修正の方向決定が逆 | Session 8 (調査セッション) で結論を出す |
| ElevenLabs STT は Web 先行 | 方向が逆 | Session 10 系を逆方向 (Web→Native) として扱う旨 INDEX.md で明示 |

### 5.3 完了の定義 (Definition of Done)

各セッションで以下を満たす:

- [ ] 仕様変更点が 1 行で記述された PR description
- [ ] 該当機能の Web スクリーンショット (該当する場合) + Native との pixel 比較
- [ ] `design-tokens/constants.json` に変更があれば `npm run tokens:check` がグリーン
- [ ] Web: `npm run build` + `npm run test` 成功
- [ ] Native 側に副次的変更が入った場合: `./gradlew assembleDebug` / `xcodebuild ... build` 成功
- [ ] CHANGELOG.md にエントリ追加 (Web セクション。Native 側にも同期した場合は (Android)/(iOS) タグ)
- [ ] `docs/sync/STATUS.md` のチェックリストを更新

---


### 5.4 スコープ凍結プロセス (Session 2 → Session 3 以降の橋渡し)

実装セッション (S3〜S10) で「結局 Native の何をどこまで Web に持ってくるのか」が曖昧にならないよう、
**Session 2 終了時点で対象/対象外を確定** させる。手順:

1. Session 2 担当が `docs/sync/research/r03-*.md` 〜 `r10-*.md` を全件記入 (Native 実装と Web 実装の突合)
2. 各レポート末尾の「結論 (移植する / 部分移植 / 移植不要 / 方向反転)」を `docs/sync/research/INDEX.md` の対応行に転記
3. 不明確な項目は **Session 2 完了前に解消** する (Native/Web 双方を読み直す or 関係者に質問)
4. 全行が確定したら INDEX.md の sign-off 欄に Session 2 担当 + Web lead + Native lead がチェック
5. **凍結後**: 追加・除外は別 PR で INDEX.md を更新する形のみ許可

> Session 3〜10 の担当者は、自分のセッションを始める前に必ず INDEX.md の該当行を確認すること。
> **特に S10 (STT) は方向が逆 (Web → Native) なので注意。**

## 6. ファイル対応マッピング

> **読み方**: Native (Android/iOS) 列が Source of Truth。Web 列がそれに合わせて修正される対象。

| Native (Android) | Native (iOS) | Web (修正対象) |
|---|---|---|
| `data/NostrRepository.kt` (+ ファミリ) + Rust core | `Data/NostrRepository.swift` (+ extension) + Rust core | `lib/nostr.js` |
| `data/RecommendationEngine.kt` | `Data/RecommendationConfig.swift` + `NostrRepository+Recommendation.swift` | `lib/recommendation.js` |
| `data/cache/NostrCache.kt` | `Data/NostrCache.swift` | `lib/cache.js` |
| (Rust 経由) | (Rust 経由) | `lib/connection-manager.js` |
| `data/OutboxModel.kt` + `RelayDiscovery.kt` | `Data/RelayDiscovery.swift` + `NostrRepository+Backup.swift` | `lib/outbox.js` |
| `data/GeohashUtils.kt` | (要確認、必要なら新設) | `lib/geohash.js` |
| `data/ProofModeManager.kt` | (Rokunana 除外中なので未実装) | `lib/proofmode.js` |
| `data/ExternalSigner.kt` (Amber + NIP-46) | `Data/ExternalSigner.swift` (NIP-46 のみ) | `lib/nip46.js` |
| (新設) `data/SttService.kt` 等 | `Data/ElevenLabsSttService.swift` (新設) | `hooks/useSTT.js` ← **Web 先行** |
| `ui/screens/TimelineScreen.kt` + `viewmodel/TimelineViewModel.kt` | `Views/Screens/TimelineView.swift` + `ViewModels/TimelineViewModel.swift` | `components/TimelineTab.js` |
| `ui/screens/HomeScreen.kt` + `viewmodel/HomeViewModel.kt` | `Views/Screens/HomeView.swift` + `ViewModels/HomeViewModel.swift` | `components/HomeTab.js` |
| `ui/components/PostModal.kt` | `Views/Sheets/PostSheet.swift` | `components/PostModal.js` |
| `ui/components/NotificationModal.kt` | `Views/Sheets/NotificationSheet.swift` | `components/NotificationModal.js` |
| `ui/screens/SettingsScreen.kt` + `ui/miniapps/*` | `Views/Screens/SettingsView.swift` + `Views/MiniApps/*` | `components/MiniAppTab.js` + `miniapps/*` |
| `ui/components/ReactionEmojiPicker.kt` | (該当 picker) | `components/ReactionEmojiPicker.js` |
| `ui/components/SignUpModal.kt` | `Views/Screens/LoginView.swift` (or 新設 SignUpView) | `components/SignUpModal.js` |

---

## 7. 関連ドキュメント

- [PLAN.md](./PLAN.md) — セッション分割プラン
- [STATUS.md](./STATUS.md) — 進捗チェックリスト
- [prompts/](./prompts/) — 各セッション用プロンプト
- [/AGENTS.md](../../AGENTS.md) — プロジェクト全体ガードレール
- [/ios/GUARDRAILS.md](../../ios/GUARDRAILS.md) — iOS 個別制約
