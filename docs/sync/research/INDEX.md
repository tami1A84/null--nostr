# Native → Web 同期 スコープ凍結インデックス

> **このファイルは Session 2 (Native 差分調査) の最終成果物**。
> Session 3 以降の実装担当者は、まず本インデックスで「対象 / 対象外 / 方向反転」を確認すること。

---

## 0. 凍結プロセス

1. Session 2 担当が `research/r03-*.md` 〜 `r10-*.md` を全て記入 (Native と Web の突合)
2. 各レポートの末尾「結論」を本ファイルの該当行に転記
3. 不明確な項目は **Session 2 完了前に解消** する (実装に持ち込まない)
4. 凍結後の追加・除外は別 PR で本ファイルを更新する形のみ許可

---

## 1. 同期対象一覧 (Session 2 で確定)

> Native (Android/iOS) → Web 同期がデフォルト。S10 系 (STT) のみ Web → Native の方向。

| ID | 機能 | 起源 (Native 実装) | Native 状態 | Web 現状 | 方向 | 想定セッション | 凍結状態 |
|---|---|---|---|---|---|---|---|
| F-03 | Reaction picker: Native と仕様一致 (Unicode quick row 削除) | `ReactionEmojiPicker.kt` v1.3+ | ✅ | ❓ Web 側に残っているか調査 | Native → Web | S3 | ⬜ |
| F-04a | Recommendation: アイコン/名前無しユーザー除外 | `RecommendationEngine.kt` | ✅ | ❓ | Native → Web | S4 | ⬜ |
| F-04b | Recommendation: Following 優先 + 背景ロード | `TimelineViewModel` (両 OS) | ✅ | ❓ | Native → Web | S4 | ⬜ |
| F-05a | 通知: 誕生日 (フォロー先 metadata.birthday) | `AuthViewModel`, `NotificationModal.kt` | ✅ | ❓ | Native → Web | S5 | ⬜ |
| F-05b | 通知: 相互フォロー Zap バッジ | `NotificationModal.kt` | ✅ | ❓ | Native → Web | S5 | ⬜ |
| F-05c | 通知: カスタム絵文字反応 | `NotifStyle` (Android) | 部分 (iOS 要確認) | ❓ | Native → Web | S5 (extension) | ⬜ |
| F-06 | MiniApp タブ: カテゴリ + 順序統一 + フルスクリーン | `SettingsScreen.kt` + `SettingsView.swift` | ✅ | ❓ | Native → Web | S6 | ⬜ |
| F-07a | SignUp: 手動リージョン選択 | `SignUpModal.kt` | ✅ | ❓ | Native → Web | S7 | ⬜ |
| F-07b | SignUp: relay 自動推奨 + geohash | `GeohashUtils.kt` | ✅ (Android) / 未確認 (iOS) | ❓ | Native → Web | S7 | ⬜ |
| F-08 | connection-manager 修正 (もし Native/Rust に同等修正があれば) | (要調査) | ❓ | (Web v1.4.8 で実装済) | 双方向 (調査結果次第) | S8 | ⬜ |
| F-09a | diVine 6.3s ループ動画 | `DivineVideoRecorder.kt` | ✅ (Android) / **対象外** (iOS, App Store) | ❓ Web に同等実装あるか | Android → Web | S9 | ⬜ |
| F-09b | ProofMode (OpenPGP) | `ProofModeManager.kt` | ✅ (Android) / **対象外** (iOS) | ❓ | Android → Web | S9 | ⬜ |
| F-10a | ElevenLabs STT: ストリーミング基盤 | `hooks/useSTT.js` (Web) | ❓ Android (`ElevenLabsSettings` あり/STT 未) / ❌ iOS (TTS のみ) | ✅ | **Web → Native (反転)** | S10A〜C | ⬜ |
| F-10b | ElevenLabs STT: PostModal / TalkTab UI 統合 | `PostModal.js`, `TalkTab.js` (Web) | ❌ | ✅ | **Web → Native (反転)** | S10D | ⬜ |
| F-10c | ElevenLabs STT: 言語切替永続化 | (Web) | ❌ | ✅ | **Web → Native (反転)** | S10D | ⬜ |

---

## 2. 同期対象外一覧

| ID | 機能 | 理由 |
|---|---|---|
| X-01 | Passkey 関連 (Web専用 `ee4e0ab`, `3a1e507` 等) | Native は WebAuthn 非サポート |
| X-02 | iOS Rokunana / Divine 動画 (Native 側の話) | App Store UGC 審査で iOS は除外中 |
| X-03 | Web 用サーバ proxy (`app/api/elevenlabs/token/route.js`) | Native はクライアント直接叩く (キーは Keychain / EncryptedSharedPreferences) |
| X-04 | Next.js 16 ビルド対応 | Web 専用 |
| X-05 | NIP-EE (MLS) Talk の Web 移植 | Native は `nurunuru-ffi` 経由で動作中、Web は WhiteNoise/MLS の JS 実装が未成熟 — v1.6+ 検討 |
| X-06 | (要確認: Session 2 で追加) | |

---

## 3. 後続検討 (v1.6+)

Session 2 で「Native でもまだ未完成」「再設計が必要」と判明した項目はここに退避。

| ID | 機能 | 退避理由 | 再検討時期 |
|---|---|---|---|
| -- | -- | -- | -- |

---

## 4. 凍結 sign-off

- [ ] Session 2 担当: ____________ / 日付: ______
- [ ] Web lead: ____________ / 日付: ______
- [ ] Native lead (Android/iOS): ____________ / 日付: ______

> 全 sign-off 後、本ファイルは **frozen** 扱いとなり、変更は別 PR を要する。
