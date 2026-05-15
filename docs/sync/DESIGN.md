# Web → Native 同期 設計書

> **Branch**: `sync/web-to-native-20260516`
> **作成日**: 2026-05-16
> **対象**: Web (Next.js) で先行実装された機能・修正を Android (Kotlin/Compose) と iOS (Swift/SwiftUI) に同期する作業全体

---

## 1. 背景と目的

null--nostr は Web (Next.js PWA) / Android (Kotlin + Rust FFI) / iOS (Swift + UniFFI XCFramework) の 3 プラットフォームを並行で開発している。歴史的に **Web 版が機能のプロトタイピング先行** となっており、安定した機能から Android → iOS の順でネイティブ移植してきた。

このドキュメントは以下 2 点を明確化する:

1. **どの Web 機能・修正が Native 未同期か** (gap analysis)
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
| **同期** | Web 側の振る舞い (UX / 仕様 / バグ修正) を、各プラットフォームのイディオムを尊重しながら再現すること。コードの逐語コピーではない |

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
| **L2. Domain Logic** | 可能な限り `nurunuru-core` に集約 → FFI 経由で Android / iOS 共有。Web は `lib/nostr.js` に同等ロジックを保持 |
| **L3. UI / UX** | プラットフォームのイディオムで実装 (Compose / SwiftUI)。Web 側のスペックを **pixel-spec** として参照する |

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

## 4. Web → Native ギャップ分析

### 4.1 直近 90 日の Web 主要変更 (commit 抽出)

| Commit | 日付 | カテゴリ | Native 移植状態 |
|---|---|---|---|
| `c02bdb8` Release v1.4.8 | 2026-05-14 | `lib/connection-manager.js` パッチ | ⚠️ Web 専用変更だが、Rust 接続層に同等修正が必要か要確認 |
| `c1d8f60` constants 同期 / Next.js 16 ビルド修正 | 2026-03-04 | tokens | ✅ `npm run tokens` で同期済 |
| `04f939f` design-token 導入 | 2026-03-04 | tokens | ✅ |
| `e75003d` recommended feed: アイコン無しユーザを除外 | 2026-03-02 | `lib/recommendation.js` | ⚠️ Android `RecommendationEngine.kt` / iOS `NostrRepository+Recommendation` 要反映 |
| `5510a50` Following 優先 + Recommended バックグラウンドロード | 2026-02-24 | `components/TimelineTab.js` | ⚠️ Android `TimelineViewModel` / iOS `TimelineViewModel` 要反映 |
| `3953d31` HomeTab クラッシュ修正 + デスクトップで video 隠す | 2026-02-24 | `components/HomeTab.js` | ⚠️ Android `HomeScreen.kt` 要確認 |
| `0a2cac9` 投稿フォーム最適化 + 低帯域動画録画 | 2026-02-24 | PostModal / DivineVideoRecorder | ⚠️ Android `PostModal.kt` / DivineVideoRecorder 要反映、iOS は Rokunana が App Store 提出のため除外中 |
| `43d1514` diVine 互換 6s ループ動画 + ProofMode | 2026-02-23 | DivineVideoRecorder, PostItem, PostModal, TimelineTab, UserProfileView, lib/nostr, lib/proofmode | ⚠️ Android: ProofMode/DivineVideoRecorder ファイルあり、内容差分要確認。iOS: 未実装 (Rokunana のみ) |
| `00f6928` 誕生日メタデータ型対応 + カメラ信頼性向上 | 2026-02-23 | DivineVideoRecorder, NotificationModal, TimelineTab | ⚠️ Android `AuthViewModel` 等に birthday あり、iOS `HomeViewModel` のみ。両方差分要確認 |
| `8b109c9` STT リアルタイム表示 + 自動コミット | 2026-02-23 | hooks/useSTT, PostModal, TalkTab, TimelineTab | ⚠️ Android: `ElevenLabsSettings.kt` あり (STT 未統合か要確認)、iOS: `ElevenLabsTTSService` あり (STT 未) |
| `0a2b76f` MiniApp タブ整理 + UI ポリッシュ | 2026-02-23 | MiniAppTab, miniapps/* | ⚠️ Android `SettingsScreen.kt` (ミニアプリハブ) と iOS Mini Apps の構成・順序要照合 |
| `6aa93b6` MiniApp タブを近代UI + フルスクリーンモーダル化 | 2026-02-23 | MiniAppTab, MiniAppModal, miniapps/* | 同上 |
| `35c7cc0` Quick Unicode reaction を picker から削除 | 2026-02-23 | ReactionEmojiPicker | ⚠️ Android `ReactionEmojiPicker.kt` / iOS リアクションピッカー要確認 |
| `e529291` ElevenLabs STT 追加 | 2026-02-23 | hooks/useSTT, PostModal, TalkTab, TimelineTab | 未同期 (上記 `8b109c9` 参照) |
| `84017cc` ElevenLabs STT 投稿用 | 2026-02-23 | 同上 | 未同期 |
| `26ef9ea` Zap & 相互フォロー誕生日通知 | 2026-02-23 | NotificationModal, TimelineTab, lib/cache, lib/nostr | ⚠️ Android `NotificationModal.kt` 要拡張、iOS `NotificationSheet` 要拡張 |
| `3cbf598` サインアップフロー: 生体認証回数低減 + プロフィール設定 | 2026-02-23 | LoginScreen, SignUpModal | ⚠️ Native はそもそも Passkey 非対応だが、プロフィール設定 UX は反映余地あり |
| `7b91f49` カスタム絵文字通知システム | 2026-02-23 | NotificationModal, TimelineTab | ⚠️ Android `NotificationModal` に既に類似 (NotifStyle) あり、差分要確認 |
| `3a1e507` Passkey プロンプト抑制 + リレー永続性改善 | 2026-02-23 | MiniAppTab, SignUpModal | Native: リレー永続性のみ反映 |
| `aa2ddc2` サインアップ: 手動リージョン選択 + リレー検出強化 | 2026-02-22 | SignUpModal, lib/geohash | ⚠️ Android `SignUpModal.kt` / `GeohashUtils.kt` 既存、差分要確認。iOS は SignUp 実装要確認 |
| `ee4e0ab` Passkey vs 非 Passkey ユーザ判別 | 2026-02-22 | LoginScreen | Web 専用 (Native は対象外) |

### 4.2 機能カテゴリ別ギャップマトリクス

| 機能 | Web | Android | iOS | 同期優先度 |
|---|---|---|---|---|
| design tokens / 文字数定数 | ✅ | ✅ (生成済) | ✅ (生成済) | 確認のみ |
| Recommendation: アイコン無しユーザ除外 | ✅ | ❓ 要差分 | ❓ 要差分 | **High** |
| Recommendation: Following 優先 → Recommended 後追い | ✅ | ❓ 要差分 | ❓ 要差分 | **High** |
| Birthday 通知 (誕生日 + 相互フォロー Zap) | ✅ | 部分 (フィールドあり、通知未?) | 部分 (HomeViewModel にフィールドのみ) | **High** |
| Custom emoji notification | ✅ | 部分 (NotifStyle) | ❓ | Medium |
| ElevenLabs **STT** (投稿/トーク音声入力) | ✅ | ❓ (Settings あり、統合未) | ❓ (TTS あり、STT 未) | Medium |
| MiniApp tab 構成 (カテゴリ・順序) | ✅ | ❓ 要差分 | ❓ 要差分 | Medium |
| Reaction picker: Unicode quick reaction 削除 | ✅ | ❓ 要差分 | ❓ 要差分 | **High** (UX 一貫性) |
| diVine 6.3s ループ動画 + ProofMode | ✅ | ✅ (ファイルあり) | ⚠️ Rokunana として一部、App Store 審査で除外中 | Low (iOS は据置) |
| Passkey 関連 | ✅ | N/A | N/A | 対象外 |
| SignUp: 手動リージョン選択・リレー検出 | ✅ | 部分 | ❓ | Medium |
| `lib/connection-manager.js` v1.4.8 修正内容 | ✅ | Rust 接続層 (要該当箇所確認) | Rust 接続層 (同上) | Medium (調査含む) |
| Login flow: Passkey プロンプト抑制 | ✅ | N/A | N/A | 対象外 |
| URLPreview / BirdwatchDisplay / LongFormPostItem | ✅ | ✅ (ファイルあり) | ✅ (ファイルあり) | 差分巡検 |
| Outbox model (NIP-65) | ✅ `lib/outbox.js` | ✅ `OutboxModel.kt` + `RelayDiscovery.kt` | ✅ `NostrRepository+Backup.swift` + `RelayDiscovery.swift` | 差分巡検 |

> ❓ = 本セッションで差分調査が必要な項目。**High** = ユーザ可視差分が出やすい / 実装コストが小〜中 / 早期に解消すべき。

---

## 5. 同期戦略

### 5.1 原則

1. **Source of Truth は明示する**: Web を仕様の起点としつつ、ロジックは可能なら Rust core に寄せる。
2. **片プラットフォーム単独 PR を許可**: Android 先行 → iOS 追随、もしくは逆も可。ただしマトリクス更新を必須にする。
3. **constants.json 経由で動かす**: 数値・ラベル・閾値は `design-tokens/constants.json` に集約。
4. **段階的に小さく**: 1 セッション = 1〜3 機能。差分が大きい機能 (例: STT) は調査セッションを分離する。
5. **テスト**: 既存 `vitest` (Web) / Android Unit テスト / Xcode テストはそれぞれ同期したロジックに対して 1 ケース以上追加する。
6. **AGENTS.md 制約を破らない**: actor / @Observable / Keychain / 140 char / LINE Seed JP / 外部署名種別。

### 5.2 リスク

| リスク | 影響 | 緩和策 |
|---|---|---|
| Web の挙動が "実は Web でも未完成" だった | 移植の意味が無くなる | 同期前に Web 側を E2E で確認 (vitest + 手動) |
| iOS Rokunana を App Store 審査で除外している | DivineVideoRecorder 系を iOS で復活させると審査リジェクト | iOS は引き続き対象外、ドキュメントに明記 (Session 9 参照) |
| Amber (NIP-55) 抑制ロジックを iOS に持ち込まない | 仕様混入 | iOS は NIP-46 のみ。Login 系セッションでは iOS は見送り |
| Rust FFI 変更が必要になる | Android `.so` / iOS `.xcframework` の再生成 | 専用セッション (Session 11) で集約 |
| `lib/connection-manager.js` の v1.4.8 修正が WS 層 (Rust) と無関係 | 移植不要の可能性 | Session 8 (調査セッション) で結論を出す |

### 5.3 完了の定義 (Definition of Done)

各セッションで以下を満たす:

- [ ] 仕様変更点が 1 行で記述された PR description
- [ ] 該当機能の Android / iOS スクリーンショット (該当する場合)
- [ ] `design-tokens/constants.json` に変更があれば `npm run tokens:check` がグリーン
- [ ] Android: `./gradlew assembleDebug` 成功
- [ ] iOS: `xcodebuild -scheme NuruNuru -destination 'platform=iOS Simulator,name=iPhone 17' -skipPackagePluginValidation build` 成功
- [ ] CHANGELOG.md にエントリ追加 (Android = Android セクション、iOS = iOS セクション)
- [ ] `docs/sync/STATUS.md` のチェックリストを更新

---


### 5.4 スコープ凍結プロセス (Session 2 → Session 3 以降の橋渡し)

実装セッション (S3〜S10) で「結局 Web の何をどこまで持ってくるのか」が曖昧にならないよう、
**Session 2 終了時点で対象/対象外を確定** させる。手順:

1. Session 2 担当が `docs/sync/research/r03-*.md` 〜 `r10-*.md` を全件記入
2. 各レポート末尾の「結論 (移植する / 部分移植 / 移植不要)」を `docs/sync/research/INDEX.md` の対応行に転記
3. 不明確な項目は **Session 2 完了前に解消** する (Web 側を読み直す or 関係者に質問)
4. 全行が確定したら INDEX.md の sign-off 欄に Session 2 担当 + Android lead + iOS lead がチェック
5. **凍結後**: 追加・除外は別 PR で INDEX.md を更新する形のみ許可

> Session 3〜10 の担当者は、自分のセッションを始める前に必ず INDEX.md の該当行を確認すること。

## 6. ファイル対応マッピング

| Web | Android | iOS |
|---|---|---|
| `lib/nostr.js` | `data/NostrRepository.kt` (+ ファミリ) + Rust core | `Data/NostrRepository.swift` (+ extension) + Rust core |
| `lib/recommendation.js` | `data/RecommendationEngine.kt` | `Data/RecommendationConfig.swift` + `NostrRepository+Recommendation.swift` |
| `lib/cache.js` | `data/cache/NostrCache.kt` | `Data/NostrCache.swift` |
| `lib/connection-manager.js` | (Rust 経由) | (Rust 経由) |
| `lib/outbox.js` | `data/OutboxModel.kt` + `RelayDiscovery.kt` | `Data/RelayDiscovery.swift` + `NostrRepository+Backup.swift` |
| `lib/geohash.js` | `data/GeohashUtils.kt` | (要確認、必要なら新設) |
| `lib/proofmode.js` | `data/ProofModeManager.kt` | (Rokunana 除外中なので未実装) |
| `lib/nip46.js` | `data/ExternalSigner.kt` (Amber + NIP-46) | `Data/ExternalSigner.swift` (NIP-46 のみ) |
| `hooks/useSTT.js` | (新設) `data/SttService.kt` 等 | `Data/ElevenLabsSttService.swift` (新設) |
| `components/TimelineTab.js` | `ui/screens/TimelineScreen.kt` + `viewmodel/TimelineViewModel.kt` | `Views/Screens/TimelineView.swift` + `ViewModels/TimelineViewModel.swift` |
| `components/HomeTab.js` | `ui/screens/HomeScreen.kt` + `viewmodel/HomeViewModel.kt` | `Views/Screens/HomeView.swift` + `ViewModels/HomeViewModel.swift` |
| `components/PostModal.js` | `ui/components/PostModal.kt` | `Views/Sheets/PostSheet.swift` |
| `components/NotificationModal.js` | `ui/components/NotificationModal.kt` | `Views/Sheets/NotificationSheet.swift` |
| `components/MiniAppTab.js` + `miniapps/*` | `ui/screens/SettingsScreen.kt` + `ui/miniapps/*` | `Views/Screens/SettingsView.swift` + `Views/MiniApps/*` |
| `components/ReactionEmojiPicker.js` | `ui/components/ReactionEmojiPicker.kt` | (該当 picker) |
| `components/SignUpModal.js` | `ui/components/SignUpModal.kt` | `Views/Screens/LoginView.swift` (or 新設 SignUpView) |

---

## 7. 関連ドキュメント

- [PLAN.md](./PLAN.md) — セッション分割プラン
- [STATUS.md](./STATUS.md) — 進捗チェックリスト
- [prompts/](./prompts/) — 各セッション用プロンプト
- [/AGENTS.md](../../AGENTS.md) — プロジェクト全体ガードレール
- [/ios/GUARDRAILS.md](../../ios/GUARDRAILS.md) — iOS 個別制約