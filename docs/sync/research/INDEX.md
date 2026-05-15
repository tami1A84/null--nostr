# Web → Native 同期 スコープ凍結インデックス

> **このファイルは Session 2 (Web 差分調査) の最終成果物**。
> Session 3 以降の実装担当者は、まず本インデックスで「対象 / 対象外」を確認すること。

---

## 0. 凍結プロセス

1. Session 2 担当が `research/r03-*.md` 〜 `r10-*.md` を全て記入
2. 各レポートの末尾「結論」を本ファイルの該当行に転記
3. 不明確な項目は **Session 2 完了前に解消** する (実装に持ち込まない)
4. 凍結後の追加・除外は別 PR で本ファイルを更新する形のみ許可

---

## 1. 同期対象一覧 (Session 2 で確定)

| ID | 機能 | 起源 commit | Web 状態 | 対象 (Android) | 対象 (iOS) | 想定セッション | 凍結状態 |
|---|---|---|---|---|---|---|---|
| F-03 | Reaction picker: Unicode quick reaction 削除 | `35c7cc0` | 完了 | ⬜ | ⬜ | S3 | ⬜ |
| F-04a | Recommendation: アイコン/名前無しユーザー除外 | `e75003d` | 完了 | ⬜ | ⬜ | S4 | ⬜ |
| F-04b | Recommendation: Following 優先 + 背景ロード | `5510a50` | 完了 | ⬜ | ⬜ | S4 | ⬜ |
| F-05a | 通知: 誕生日 (フォロー先 metadata.birthday) | `26ef9ea` | 完了 | ⬜ | ⬜ | S5 | ⬜ |
| F-05b | 通知: 相互フォロー Zap バッジ | `26ef9ea` | 完了 | ⬜ | ⬜ | S5 | ⬜ |
| F-05c | 通知: カスタム絵文字反応 | `7b91f49` | 完了 | ⬜ (一部 NotifStyle あり) | ⬜ | S5 (extension) | ⬜ |
| F-06 | MiniApp タブ: カテゴリ + 順序統一 + フルスクリーン | `6aa93b6`, `0a2b76f` | 完了 | ⬜ | ⬜ | S6 | ⬜ |
| F-07a | SignUp: 手動リージョン選択 | `aa2ddc2` | 完了 | ⬜ | ⬜ | S7 | ⬜ |
| F-07b | SignUp: relay 自動推奨 + geohash | `aa2ddc2` | 完了 | ⬜ | ⬜ | S7 | ⬜ |
| F-08 | connection-manager v1.4.8 修正 | `c02bdb8` | 完了 | (調査結果次第) | (同) | S8 | ⬜ |
| F-09a | diVine 6.3s ループ動画 | `43d1514`, `122298c` | 完了 | ⬜ | **対象外** (App Store) | S9 | ⬜ |
| F-09b | ProofMode (OpenPGP) | `43d1514` | 完了 | ⬜ | **対象外** | S9 | ⬜ |
| F-10a | ElevenLabs STT: ストリーミング基盤 | `84017cc`, `e529291` | 完了 | ⬜ | ⬜ | S10A〜C | ⬜ |
| F-10b | ElevenLabs STT: PostModal / TalkTab UI 統合 | `8b109c9` | 完了 | ⬜ | ⬜ | S10D | ⬜ |
| F-10c | ElevenLabs STT: 言語切替永続化 | `84017cc` | 完了 | ⬜ | ⬜ | S10D | ⬜ |

---

## 2. 同期対象外一覧

| ID | 機能 | 起源 commit | 除外理由 |
|---|---|---|---|
| X-01 | Passkey 関連 (`ee4e0ab`, `3a1e507` 等) | -- | Native は WebAuthn 非サポート |
| X-02 | iOS Rokunana / Divine 動画 | -- | App Store UGC 審査で iOS は除外中 |
| X-03 | Web 用サーバ proxy (`app/api/elevenlabs/token/route.js`) | -- | Native はクライアント直接叩く (キーは Keychain / EncryptedSharedPreferences) |
| X-04 | Next.js 16 ビルド対応 (`5f25713` 等) | -- | Web 専用 |
| X-05 | (要確認: Session 2 で追加) | | |

---

## 3. 後続検討 (v1.6+)

Session 2 で「Web にもまだ未完成」「再設計が必要」と判明した項目はここに退避。

| ID | 機能 | 退避理由 | 再検討時期 |
|---|---|---|---|
| -- | -- | -- | -- |

---

## 4. 凍結 sign-off

- [ ] Session 2 担当: ____________ / 日付: ______
- [ ] Android lead: ____________ / 日付: ______
- [ ] iOS lead: ____________ / 日付: ______

> 全 sign-off 後、本ファイルは **frozen** 扱いとなり、変更は別 PR を要する。
