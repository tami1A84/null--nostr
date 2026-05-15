# Web → Native 同期 テスト設計

> Branch: `sync/web-to-native-20260516`
> 各セッションの Acceptance Criteria を支える具体的なテスト計画。

---

## 0. 方針

- **同期した振る舞いを fixture 化** し、Web / Android / iOS で同一入力 → 同一出力を担保する。
- **Golden test** を活用 (Geohash, Birthday 正規化, Recommendation フィルタ)。
- UI は **snapshot より振る舞い** を優先する (テキスト存在 / クリック後の状態遷移)。
- 既存テスト構成:
  - Web: `vitest` (`src/__tests__/*.test.ts`)
  - Android: JUnit 4 + Robolectric (Compose UI は `createComposeRule`)
  - iOS: XCTest + Swift Testing (`NuruNuruTests/`)

---

## 1. セッション別テスト計画

### S3 — Reaction picker (Unicode 削除)

| プラットフォーム | テスト | 場所 |
|---|---|---|
| Web | (既に削除済み、回帰チェックのみ) | `src/__tests__/components/ReactionEmojiPicker.test.tsx` (新設可) |
| Android | Compose UI test: picker を開いて `hasText("👍")` などが **存在しない** ことを assert | `androidTest/.../ReactionEmojiPickerTest.kt` |
| iOS | XCTest: ViewInspector or ViewModel state で Unicode quick row が空であること | `NuruNuruTests/ReactionPickerTests.swift` |

### S4 — Recommendation (アイコン無し除外 + Following 優先)

**Golden fixture** (`docs/sync/fixtures/recommendation/`):

```json
{
  "input_metadatas": [
    { "pubkey": "p1", "name": "Alice", "picture": "https://example.com/a.png" },
    { "pubkey": "p2", "name": "",      "picture": "https://example.com/b.png" },
    { "pubkey": "p3", "name": "Carol", "picture": "" },
    { "pubkey": "p4", "name": "",      "picture": "" },
    { "pubkey": "p5", "name": "Erin",  "display_name": "E", "picture": null }
  ],
  "expected_excluded": ["p2", "p3", "p4", "p5"],
  "expected_included": ["p1"]
}
```

| プラットフォーム | テスト | 場所 |
|---|---|---|
| Web | `vitest`: fixture を読んで `filterRecommendedAuthors()` 呼出 | `src/__tests__/recommendation.test.ts` (拡張) |
| Android | JUnit: `RecommendationEngine.filterByQuality()` 単体テスト | `androidTest/.../RecommendationEngineTest.kt` |
| iOS | XCTest: 同等関数 | `NuruNuruTests/RecommendationTests.swift` |

**追加テスト** (Following 優先):

- ViewModel 単体テストで bootstrap → `loadFollowing()` が `loadRecommended()` より先に完了することを assert
- iOS: `@Observable` の `posts` プロパティ更新順序を XCTest で確認

### S5 — Birthday / 相互フォロー Zap 通知

**Birthday 正規化テーブル** (`docs/sync/fixtures/birthday/`):

| 入力 (`metadata.birthday`) | 期待 (MM-DD) |
|---|---|
| `"02-29"` | `"02-29"` |
| `"1990-02-29"` | `"02-29"` |
| `"1990/02/29"` | `"02-29"` |
| `{ "year": 1990, "month": 2, "day": 29 }` | `"02-29"` |
| `{ "month": 12, "day": 31 }` | `"12-31"` |
| `""` | null |
| `"invalid"` | null |

| プラットフォーム | テスト | 場所 |
|---|---|---|
| Web | `vitest`: `normalizeBirthday()` の golden test | `src/__tests__/birthday.test.ts` (新設) |
| Android | JUnit: `BirthdayUtils.normalize()` golden | `androidTest/.../BirthdayUtilsTest.kt` |
| iOS | XCTest: 同等関数 golden | `NuruNuruTests/BirthdayTests.swift` |

**重複通知防止**:

- 同日 2 回通知生成を試みて 2 回目は no-op になることを SharedPreferences / UserDefaults モックで確認

**相互フォロー判定**:

- Fixture: 自分=A, follow=B, B follow=A の Zap (kind 9735) → `isMutualZap == true`
- A → B 片想い → `isMutualZap == false`

### S6 — MiniApp タブ統一

**カテゴリ順序 fixture** (`docs/sync/fixtures/miniapps/order.json`):

```json
{
  "エンタメ": ["BadgeSettings", "EmojiSettings", "ZapSettings", "EventBackupApp"],
  "ツール":   ["RelaySettings", "UploadSettings", "MuteList", "SchedulerApp", "VanishRequest"],
  "その他":   ["ElevenLabsSettings", "PrivacyPolicy", "VersionInfo"]
}
```

| プラットフォーム | テスト | 場所 |
|---|---|---|
| Web | `vitest`: MiniAppTab.js が export する order 配列が fixture と一致 | `src/__tests__/miniapps.test.ts` |
| Android | JUnit: `SettingsScreen` 内の order 定数が fixture と一致 | `androidTest/.../SettingsScreenOrderTest.kt` |
| iOS | XCTest: `SettingsView` 内の同等定数 | `NuruNuruTests/SettingsOrderTests.swift` |

### S7 — SignUp UX (リージョン選択 + geohash)

**Geohash golden** (`docs/sync/fixtures/geohash/regions.json`):

| 入力 region | 期待 prefix (5 桁) | 期待 推奨リレー |
|---|---|---|
| `"JP/Hokkaido"` | `"xn0n5"` 等 | `["wss://yabu.me", "wss://relay-jp.nostr.wirednet.jp"]` |
| `"JP/Kanto"` | `"xn76u"` | 同上 |
| `"Global"` | `""` (or null) | `["wss://relay.damus.io", "wss://nos.lol"]` |

> **注**: 実際の prefix 値は `lib/geohash.js` の現状実装に合わせて Session 2 で確定。

| プラットフォーム | テスト | 場所 |
|---|---|---|
| Web | `vitest`: `regionToGeohash()` golden | `src/__tests__/geohash.test.ts` (新設) |
| Android | JUnit: `GeohashUtils.regionToGeohash()` golden | `androidTest/.../GeohashUtilsTest.kt` |
| iOS | XCTest: 新設 `GeohashUtils.regionToGeohash()` golden | `NuruNuruTests/GeohashUtilsTests.swift` |

### S8 — connection-manager 調査

- 実装テストはなし。
- 調査結果が「Rust 修正必要」だった場合のみ、対象セッション (新設) でテスト計画を追加。

### S9 — ProofMode / DivineVideoRecorder (Android のみ)

**ProofMode 同等性**:

- 同一バイト列に対し Web (`lib/proofmode.js`) / Android (`ProofModeManager.kt`) が **同一 SHA-256** を返すこと (golden)
- OpenPGP 署名は鍵が異なるため値比較不可 → 「検証可能」で OK

| テスト | 場所 |
|---|---|
| Android: SHA-256 一致テスト (fixture バイト列) | `androidTest/.../ProofModeManagerTest.kt` |
| Android: 6.3 秒上限の境界テスト (6.2s OK / 6.4s reject) | `androidTest/.../DivineVideoRecorderTest.kt` |

### S10A〜D — ElevenLabs STT

- **10A** (調査): テストなし
- **10B** (Android): JUnit + Robolectric
  - 無音 1 秒で `onCommit` が呼ばれる
  - WS 切断時の自動再接続
- **10C** (iOS): XCTest
  - `AVAudioSession` モックで PCM 16kHz mono が emit される
  - actor の race condition なし (concurrent call で state が壊れない)
- **10D** (UX/権限): UI test
  - 権限拒否時のフォールバックモーダル表示
  - API キー未設定時の誘導モーダル

### S11 — FFI 統合確認

- 既存テストスイート全実行:
  - `npm run test`
  - `cd android && ./gradlew test connectedAndroidTest`
  - `cd ios && xcodebuild ... test`
- スモークテスト (手動): CHECKLIST.md §1 「実機検証必須項目」全件

---

## 2. fixture ディレクトリ構成

```
docs/sync/fixtures/
├── recommendation/
│   └── icon-name-filter.json
├── birthday/
│   └── normalization.json
├── miniapps/
│   └── order.json
└── geohash/
    └── regions.json
```

> **同一 fixture を 3 プラから読む** ことで、振る舞いの ground truth を一元管理する。Web は import で、Android / iOS は `assets/` または `Resources/` 経由で。

---

## 3. CI 統合 (将来作業 — v1.5.0 では手動)

| ジョブ | コマンド | 想定環境 |
|---|---|---|
| web-test | `npm run test` | GitHub Actions ubuntu |
| web-tokens | `npm run tokens:check` | 同上 |
| android-build | `./gradlew assembleDebug` | macOS or Linux + JDK 17 + Android SDK |
| android-test | `./gradlew test` | 同上 |
| ios-build | `xcodebuild ... build` | macOS + Xcode 16+ |
| ios-test | `xcodebuild ... test` | 同上 |

> CI セットアップは **本同期作業のスコープ外**。v1.5.1 以降で別 PR。
