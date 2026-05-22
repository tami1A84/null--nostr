# Release notes — Issue #181 MLS DB encryption

This file contains the short-form release notes intended for:

- GitHub release body (`gh release create`)
- zapstore release notes (`~/go/bin/zsp publish`)
- Google Play "What's new in this version"
- App Store "What's New" / TestFlight build notes

Copy the relevant section verbatim — do not edit per channel unless a length
limit applies.

---

## Short form (GitHub / zapstore — JP)

🔒 セキュリティ修正 (Issue #181)

Marmot (MLS) のローカル DB を SQLCipher で暗号化しました。これまで端末ディスク上に
平文で書かれていた MLS の署名鍵 / 暗号鍵 / KeyPackage 秘密素材が、ディスク窃取で
抜けるリスクを塞ぐ修正です。

⚠️ 初回起動時に旧 MLS DB は自動削除されます。
- 既存の Talk グループの **過去メッセージは復号できなくなります**。
- グループ一覧自体と新規メッセージは引き続き使えます。
- 過去会話の継続が必要な場合は、グループから再招待してもらってください。
- npub / nsec / 通常の Nostr 投稿には一切影響ありません。

クリーンインストールの方は影響ありません。

---

## Short form (GitHub / zapstore — EN)

🔒 Security fix (Issue #181)

The Marmot (MLS) local database is now encrypted with SQLCipher. Previously the
MLS signature keys, encryption keys, and KeyPackage private material were
written to disk in plaintext and could be exfiltrated by anyone with physical
or backup access to the device.

⚠️ The legacy plaintext MLS DB is purged automatically on first launch:
- Past messages in existing Talk groups will no longer be decryptable.
- Group lists and new messages continue to work.
- To continue a past conversation, ask to be re-invited to the group.
- Your npub / nsec / regular Nostr notes are unaffected.

Clean installs are unaffected.

---

## Long form (CHANGELOG.md pointer)

See [`CHANGELOG.md`](../../CHANGELOG.md) `[Unreleased] > Security` and
`Upgrade notes` sections for the full technical changelog, including:

- Key derivation strategy (HKDF-SHA256 for internal signer / random 32-byte
  for external signer)
- Per-platform secure storage (Android EncryptedSharedPreferences /
  iOS Keychain `…ThisDeviceOnly`)
- Content-based legacy purge (`MlsLegacyMigration`)
- Hard-fail guard (`mls_is_encrypted()` + `assertEncrypted()`)
- CI lint guard (`npm run lint:issue-181`)

---

## Per-platform release matrix

| Channel | Distributed artifact | Notes |
|---|---|---|
| zapstore | `nurunuru-X.Y.Z-arm64-v8a.apk` | Use JP short form above. |
| GitHub Release | Same APK + `.aab` + iOS `.ipa` (if shipping to TestFlight) | Use JP + EN short forms. |
| Google Play | `app-release.aab` | Use JP short form, truncate to 500 chars if needed. |
| App Store / TestFlight | `NuruNuru.ipa` | Use EN short form. App Review may ask about the data migration — point them to this file + CHANGELOG.md. |

---

## Talker-facing FAQ (for support replies)

**Q: アップデートしたら Talk グループの過去メッセージが見えなくなった。バグですか？**
A: いいえ、Issue #181 の暗号化対応に伴う仕様変更です。旧バージョンは MLS の鍵を
平文で保存していたため、新バージョンで暗号化形式に切り替える際に旧 DB を安全に
削除する必要がありました。新しいグループ・新しいメッセージは引き続き使えます。

**Q: 過去会話を取り戻せますか？**
A: 端末側の MLS state は復元できませんが、グループのメンバーに再招待してもらえば
新しい鍵で同じグループ ID に再参加でき、それ以降の会話は通常通り継続できます。

**Q: npub や投稿は消えていない？**
A: 消えていません。Issue #181 は MLS 専用の DB に限定された変更です。Nostr の
通常投稿・プロフィール・フォロー・通知・タイムライン履歴には一切影響ありません。

**Q: 自分の KeyPackage はどうなりますか？**
A: 起動時に新しい SQLCipher DB 上で再生成され、リレーに再 publish されます。
他のユーザーから新しく招待を受ける動作には影響ありません。
