# ThemaDAY 2026-06-02 — Product / Engineering / Design Home Renewal Alignment

## Summary

> 2026-06-09 update: iOS NIP-46 signer references in this historical meeting note are superseded by ADR-0023; iOS signer paths now target internal nsec/Keychain and Passkey/Nosskey.

2026-06-02 の ThemaDAY では、製品開発・エンジニアリング・デザインの3リーダー視点で、6月の Home renewal 実行方針を再同期した。

ユーザー決定により、前回まで未完了扱いだった以下の前提を更新する。

1. **リレーフィード削除は完了済み**。6/8 release train の未完了リスクとして扱わない。
2. **iOS Rust FFI は現行スコープ完了済み**。6/8 release train の新規 blocker として扱わない。
3. Home tab は LINE Home renewal 2026 を参照し、**「アクティビティ」/「コンテンツ」** の2層構造へ変更する。
4. 既存 Home のプロフィール、自分の投稿一覧、いいね一覧は、Home header 位置に置くアイコン内の account/profile hub へ移設する。
5. 既存 Timeline のフォローフィードは、Home の **コンテンツ** エリアへ移設する。

結論: 6月の主戦場は、未完了作業を増やすことではなく、完了済みの安全対策と iOS FFI を前提に、Home を「読む・戻る・自分を見る」中心へ整理することである。

## Inputs reviewed

- User decision on 2026-06-02:
  - リレーフィードはすでに削除完了。
  - iOS Rust FFI もすでに完了。
  - Home header 位置のアイコン内へ、既存プロフィール / 自分の投稿一覧 / いいね一覧を移設。
  - Home tab は「アクティビティ」と「コンテンツ」の2層構造へ変更。
  - 既存 Timeline フォローフィードはコンテンツエリアへ移設。
- LINE Home renewal 2026:
  - https://guide.line.me/ja/update/home-renewal2026.html
- June roadmap and ADRs:
  - [[june-2026-roadmap]]
  - [[../decisions/adr-0015-home-tab-renewal]]
  - [[../decisions/adr-0019-ios-rust-ffi-write-path]]

## Updated current behavior

### Relay feed

Relay feed removal is complete. Future planning should not spend W23/W24 capacity on debating whether to remove it. Remaining work, if any, is verification and dead-code cleanup only.

### iOS Rust FFI

The iOS Rust FFI work relevant to the current release-planning discussion is complete. Historical 2026-06-02 guardrails included NIP-46 for external signing; as of ADR-0023 (2026-06-09), the current iOS signer boundary is Keychain-only private keys plus Passkey/Nosskey platform authorization, with no NIP-46 signer and no secret leakage into logs or global state.

### Home tab

Home is no longer planned as a generic aggregation page. It is a two-layer daily surface:

| Layer | Role | Initial content |
|---|---|---|
| **アクティビティ** | 日々の動き・反応・戻る理由 | Notifications / reactions / replies / account activity candidates. Exact first implementation remains design/engineering scope. |
| **コンテンツ** | 読む・眺める中心 | Existing Timeline following feed moves here. Future News/Mini Apps discovery must not be mixed into this feed without Design Crit. |

Header behavior:

- Home header includes an account/profile icon.
- Existing profile view, my posts list, and likes list move into that icon’s account/profile hub.
- This keeps the Home body focused on activity/content rather than profile management.
- Rokunana remains absent from Home in June per ADR-0018.

## Leader discussion

### Product lead

- Treat relay-feed removal and iOS Rust FFI as completed inputs, not live risks.
- Protect June’s objective: onboarding improvement plus Home clarity.
- Avoid adding News / Mini Apps discovery into Home until the two-layer Home structure is stable.
- The account/profile icon is acceptable because it hides account complexity while keeping it reachable.

### Engineering lead

- Home implementation should separate header account hub from body tabs/sections.
- The follow feed should move from Timeline to Home Content with minimal repository churn first; deeper ViewModel cleanup can follow.
- The Activity layer needs a bounded initial data source. Do not create a new broad relay query to populate it.
- iOS Rust FFI completion should be documented as current-scope completion, while keeping security boundaries explicit.

### Design lead

- The Home body should read as two calm areas: **アクティビティ** and **コンテンツ**.
- The header icon should feel like “自分の場所”, not a noisy settings drawer.
- Copy should avoid Nostr words. “フォローの投稿” can remain in Content if it helps users understand what they are reading.
- Profile / my posts / likes belong behind the header icon to keep Home from becoming a personal dashboard.

## Decisions

1. **Relay feed is marked complete** for strategy planning.
2. **iOS Rust FFI is marked complete for current release-planning scope**; future Rust/Talk expansion, if any, should be tracked separately.
3. **Home adopts a two-layer body: アクティビティ / コンテンツ**.
4. **Follow feed moves to Home コンテンツ**.
5. **Profile / my posts / likes move behind a Home-header account/profile icon**.
6. **Rokunana remains dead-but-preserved with no June UI entry point**.
7. **News and Mini Apps must not be mixed into Home Content until their own trust/safety model is ready**.

## Implementation notes

### Header account/profile icon

The icon should open an account/profile hub containing:

- existing profile card / profile detail;
- my posts list;
- likes list;
- account-related affordances already appropriate for Home settings, as implementation allows.

Do not expose private key export or signer state in a casual partial sheet. Follow platform guardrails: iOS fullScreenCover where required for sensitive settings, Android full-screen dialog/surface where required, Web full-screen modal/portal for sensitive settings.

### Activity layer

The exact first implementation is still open. Candidate inputs include notifications, reactions, replies, mentions, follow activity, or account status. The key rule is that Activity must not become a relay-wide feed replacement.

### Content layer

The existing Timeline following feed moves here. It remains follow-graph based and must preserve timeline constraints: stable identity, no entrance animation, pagination continuity, and existing PostActions copy/icon rules.

## Action items

| Owner | Action | Due |
|---|---|---:|
| Product | Update June roadmap and ADR-0015 to reflect completed relay feed / iOS FFI and new Home structure | 2026-06-02 |
| Design | Prepare Home mock with header account icon + アクティビティ / コンテンツ structure | 2026-06-03 Design Crit |
| Engineering | Plan minimal follow-feed move into Home Content without reintroducing relay feed behavior | W23/W24 |
| iOS lead | Ensure iOS Rust FFI completion docs keep Keychain/NIP-46/Passkey boundaries explicit | W23 |
| QA | Add Home two-layer checks to manual real-device QA | W24 |

## Open questions

- What is the minimal first data source for **アクティビティ**?
- Should the header account/profile hub include settings immediately, or keep settings as a separate gear while profile/posts/likes move behind the icon?
- Should Content use the label **フォローの投稿** under the **コンテンツ** layer, or should the layer label be enough?
- What is the exact Android/iOS/Web presentation style for the account/profile hub so it remains pixel-aligned without weakening sensitive-setting guardrails?

## Source references

- User decision, 2026-06-02.
- LINE Home renewal 2026: https://guide.line.me/ja/update/home-renewal2026.html
- `docs/wiki/strategy/june-2026-roadmap.md`
- `docs/wiki/decisions/adr-0015-home-tab-renewal.md`
- `docs/wiki/decisions/adr-0019-ios-rust-ffi-write-path.md`
- `docs/wiki/ui/android-ios-sync.md`
- `ios/GUARDRAILS.md`

## Related pages

- [[june-2026-roadmap]]
- [[themaday-2026-06-01-management]]
- [[../decisions/adr-0015-home-tab-renewal]]
- [[../decisions/adr-0018-rokunana-root-tab-removal]]
- [[../decisions/adr-0019-ios-rust-ffi-write-path]]
