# NIPs

## Summary

null--nostr は複数の Nostr Implementation Possibilities を扱います。このディレクトリは NIP ごとの実装状況、関連ファイル、platform 差分、注意点を蓄積する場所です。

> Source of truth: 実際の対応状況はソースコードが正です。この一覧は 2026-06-09 時点でコード参照と upstream audit に基づき更新しています。`Level` は「完全対応」ではなく、現在の実装範囲を短く示します。

## Upstream audit baseline

- Audit date: 2026-06-09
- `nostr-protocol/nips`: `7a2197c00d1bbff19b32d19851f4dffe4810b8ed` — 2026-06-06 `nip50: add autocomplete:true/false search extension (#2357)`
- `nostr-protocol/registry-of-kinds`: `d93db0c028184f317497763837e9524246507acb` — 2026-06-03 `add bookmarks, podcasts and other kinds.`
- Upstream NIPs counted in `README.md`: 94
- Upstream NIPs marked `unrecommended`: NIP-03, NIP-04, NIP-06, NIP-08, NIP-15, NIP-26, NIP-28, NIP-31, NIP-72, NIP-90, NIP-96, NIP-BE, NIP-EE.
- Upstream `README.md` says its Event Kinds table is not exhaustive; use `nostr-protocol/registry-of-kinds` or compatible YAML registries for machine-readable kind audits.

## Classification rules

| Classification | Meaning |
|---|---|
| Core / Active | User-facing or repository-level implementation is present. |
| Read/render | Events or tags are parsed/rendered but not fully authored. |
| Helper | Utility, signer, auth, parser, upload, or relay helper exists. |
| Compatibility | Deprecated/unrecommended or legacy path retained for interoperability. |
| Constants-only | Kind constants exist, but no full UI/repository flow is claimed. |
| Ecosystem / project-specific | Not an official numbered NIP, or official context is adjacent but implementation follows an ecosystem protocol. |
| Unsupported / not claimed | Upstream NIP exists, but null--nostr has no code-backed support claim in this audit. |

## Official numbered NIPs

| NIP | Level | Status in this repo | Main evidence |
|---|---|---|---|
| NIP-01 | Core | Core event model, kind 0/1 publish/fetch, relay REQ/EVENT/OK handling. | `lib/nostr.js`, `NostrClient.kt`, `NostrClient.swift`, `rust-engine/.../engine.rs` |
| NIP-02 | Active | Contact lists kind 3: fetch/follow/unfollow by republishing full list. | `NostrRepositoryActions.kt`, `NostrRepository+Actions.swift`, `lib/nostr.js`, `filters.rs` |
| NIP-04 | Compatibility/helper | Legacy encryption helpers and signer/browser APIs. | `InternalSigner.kt`, `ExternalSigner.kt`, `NostrBrowserApp.kt`, `rust-engine/nurunuru-ffi/src/lib.rs`, `lib/nostr.js` |
| NIP-05 | Active | Identifier verify/resolve and profile/search support. | `Nip05Utils.kt`, `NostrRepository+Profiles.swift`, `lib/nostr.js`, `lib/validation.js` |
| NIP-07 | Web/browser active | Web extension signing and native mini-app browser `window.nostr` bridges. | `lib/nostr.js`, `NostrBrowserApp.kt`, `NostrBrowserView.swift` |
| NIP-09 | Active | Deletion kind 5; unlike/unrepost via delete. | `NostrRepositoryActions.kt`, `NostrRepository+Actions.swift`, `lib/nostr.js`, `engine.rs` |
| NIP-10 | Active | Reply markers/tags and reply display heuristics. | `PostModal.kt`, `PostSheet.swift`, `PostContent.swift`, `PostDetailScreen.kt` |
| NIP-11 | Web helper | Relay information document helpers on Web. | `lib/nostr.js` |
| NIP-17 | Legacy/native boundary + Web helpers | Web supports NIP-17 helpers; native Talk is Marmot MLS-oriented and treats NIP-17 as legacy/compatibility. | [[nip-17]] |
| NIP-18 | Active | Repost kind 6 and quote repost via kind 1 + `q` tag / `nostr:note1`. | `PostActions.kt`, `QuoteRepostModal.kt`, `QuoteRepostSheet.swift`, `NostrRepository+Actions.swift` |
| NIP-19 | Active | Bech32 `npub`/`note`/`nevent`/`nprofile`/`naddr` parsing/rendering. | `NostrKeyUtils.kt`, `PostContent.kt`, `PostContent.swift`, `lib/nostr.js` |
| NIP-23 | Active/native rendering | Long-form articles kind 30023 are fetched/rendered. | `LongFormPostItem.swift`, `NostrKind.*LONG_FORM`, `NostrRepositoryTimeline.kt` |
| NIP-25 | Active | Reactions kind 7 including custom emoji reactions. | [[nip-25]] |
| NIP-27 | Active | Text notes render and create `nostr:` mentions for profiles/events. | `PostContent.kt`, `PostSheet.swift`, `lib/nostr.js` |
| NIP-30 | Active/native strong | Custom emoji lists/sets kind 10030/30030 and emoji picker/cache. | [[nip-30]] |
| NIP-32 | Active/helper + News categories | Labeling kind 1985 helpers exist for Birdwatch/context; News reads null.news.category self-label tags for NIP-23 category filtering. | [[nip-32]] |
| NIP-42 | Web helper | Relay auth kind 22242 on Web. | `lib/nostr.js` |
| NIP-44 | Active/helper | Encryption for NIP-17/Web NIP-46/private mute lists; signer APIs. | `InternalSigner.kt`, `ExternalSigner.kt`, `InternalSigner.swift`, `ExternalSigner.swift`, `lib/nip46.js` |
| NIP-46 | Web active / iOS signer removed | Nostr Connect remains Web-backed; iOS NIP-46 signer is removed by ADR-0023 and any remaining iOS code is migration/removal debt. | [[nip-46]] |
| NIP-50 | Active, extension gap noted | Search filters and searchnos routing. Upstream now documents `autocomplete:true/false`; null--nostr has not yet claimed autocomplete handling. | `SearchQueryParser.kt`, `NostrRepositoryTimeline.kt`, `NostrRepository+Profiles.swift`, `lib/nostr.js` |
| NIP-51 | Active | Lists: mute list kind 10000, bookmarks kind 10003, emoji list kind 10030. | [[nip-51]] |
| NIP-55 | Android active | Android Amber external signer. Not used on iOS. | `ExternalSigner.kt`, `LoginScreen.kt`, `MainActivity.kt`, `lib/nostr.js` |
| NIP-56 | Active/helper | Reporting kind 1984. | `NostrRepositoryActions.kt`, `lib/nostr.js` |
| NIP-57 | Active | Zap request/receipt, LNURL invoice generation, zap totals. | [[nip-57]] |
| NIP-58 | Active/native | Badges kind 8/30008/30009. | [[nip-58]] |
| NIP-59 | Active/transport | Gift wraps kind 1059 for Web NIP-17 and Marmot MLS Welcome delivery. | [[nip-59]] |
| NIP-5A | Official upstream, docs/strategy tracked | Static Websites / nsites: kind 15128 root manifest, 34128 legacy deprecated manifest, 35128 named manifest. Distinct from Scroll mini-app kinds 1227/10027. | [[nip-5a]] |
| NIP-62 | Active/helper | Request to Vanish kind 62. | `VanishRequest.kt`, `VanishRequestView.swift`, `NostrRepository+Actions.swift`, `lib/nostr.js` |
| NIP-65 | Active | Relay List Metadata kind 10002 / outbox model. | [[nip-65]] |
| NIP-70 | Active | Protected events `['-']` tag in composer and import/export handling. | [[nip-70]] |
| NIP-71 | Active/native feature | Short video / `ろくなな` primarily uses NIP-71 addressable short video kind 34236; upstream also defines 21/22/34235. | [[nip-71]] |
| NIP-92 | Read/render | `imeta` URL extraction/rendering for media. | `NostrEvent.swift`, `PostContent.swift`, `NostrRepository+Rokunana.swift` |
| NIP-96 | Compatibility/upload helper, upstream unrecommended | Legacy HTTP File Storage Integration compatibility remains for nostr.build/share.yabu.me style endpoints; upstream marks NIP-96 unrecommended and replaced by Blossom. | `ImageUploadService.swift`, `NostrRepository+Backup.swift`, `ImageUploadUtils.kt`, `lib/nostr.js` |
| NIP-98 | Active/upload auth | HTTP auth kind 27235 for uploads; Blossom uses kind 24242 auth in code. | [[nip-98]] |

## Lettered / draft / ecosystem NIPs

| Spec | Level | Status | Main evidence |
|---|---|---|---|
| Scroll mini-app protocol | Ecosystem / project-specific | Scroll mini-app definitions/favorites kind 1227/10027. Do not label this as official NIP-5A nsites. | `ScrollRunner.kt`, `ScrollsApp.kt`, `NostrRepositoryScrolls.kt`, `ScrollsView.swift` |
| NIP-B7 | Blossom-related | Blossom blob upload/fallback handling; see [[nip-b7]] for kind 10063 / 24242 notes. | `ImageUploadService.swift`, `AvatarView.swift`, `ImageUploadUtils.kt` |
| Nosskey (draft NIP) | Active across 3 platforms | Passkey-derived Nostr keys via WebAuthn PRF extension. New-user onboarding default on Web; opt-in primary button on iOS 18+ / Android API 28+. | [[nosskey]] |

## Blossom / BUD specs

| Spec | Level | Status | Main evidence |
|---|---|---|---|
| BUD-03 | iOS/settings + upload ecosystem | Blossom user server list kind 10063 and fallback server discovery. | `ImageUploadService.swift`, `AvatarView.swift`, `MiniAppsView.swift` |

## Project-specific / non-NIP protocol support

| Protocol | Level | Status | Main evidence |
|---|---|---|---|
| Marmot MLS | Native Talk core | Native Talk uses MLS groups/messages with key packages, welcomes, and group messages. NIP-17 models remain legacy/deprecated; upstream NIP-EE is superseded by Marmot and is not the active target. | `NostrRepositoryTalk.kt`, `NostrRepository+Talk.swift`, `rust-engine/nurunuru-core/src/mls.rs` |
| NIP-ProofMode | Android feature | Android video proof mode manager exists. | `ProofModeManager.kt` |

## Kind registry notes

See [[kind-registry]] for the audit-specific kind summary. High-priority clarifications from the 2026-06-09 audit include NIP-46 Nostr Connect kind 24133, NIP-B7 kind 10063 / 24242, NIP-5A kind 15128 / 34128 / 35128, NIP-98 kind 27235, and NIP-71 kind 21 / 22 / 34235 / 34236.

## Unsupported / not claimed upstream NIPs

The following upstream NIPs exist but are not claimed as active null--nostr support in this audit. Some may have constants-only references or legacy-adjacent code, but they require source review before being promoted: NIP-03, NIP-06, NIP-08, NIP-13, NIP-14, NIP-15, NIP-22, NIP-26, NIP-28, NIP-29, NIP-31, NIP-34, NIP-35, NIP-36, NIP-37, NIP-38, NIP-39, NIP-40, NIP-43, NIP-45, NIP-47, NIP-48, NIP-49, NIP-53, NIP-54, NIP-60, NIP-61, NIP-64, NIP-66, NIP-67, NIP-68, NIP-69, NIP-72, NIP-73, NIP-75, NIP-77, NIP-78, NIP-7D, NIP-84, NIP-85, NIP-86, NIP-87, NIP-88, NIP-89, NIP-90, NIP-99, NIP-A0, NIP-A4, NIP-B0, NIP-BE, NIP-C0, NIP-CC, NIP-C7, NIP-F4.

## Detailed NIP pages

- [[kind-registry]] — 2026-06-09 kind registry audit notes.
- [[nip-17]] — Private Direct Messages / native Talk legacy boundary.
- [[nip-18]] — reposts and quote repost behavior.
- [[nip-23]] — long-form content.
- [[nip-25]] — reactions and custom reactions.
- [[nip-30]] — custom emoji lists and sets.
- [[nip-44]] — versioned encryption.
- [[nip-46]] — Nostr Connect; Web active, iOS signer removed by ADR-0023.
- [[nip-50]] — search capability / searchnos / feedback-loop MCP.
- [[nip-51]] — lists: mute, bookmarks, emoji list.
- [[nip-57]] — Lightning Zaps.
- [[nip-58]] — badges.
- [[nip-59]] — gift wrap.
- [[nip-5a]] — Static Websites / nsites.
- [[nip-65]] — Relay List Metadata / outbox model.
- [[nosskey]] — Passkey-derived Nostr keys (PRF Direct Method, draft NIP).
- [[nip-70]] — protected events.
- [[nip-71]] — short video / ろくなな.
- [[nip-98]] — HTTP upload auth and Blossom auth.
- [[nip-b7]] — Blossom.

## Recommended future pages

- `nip-04.md` / `nip-44.md` — encryption helpers and signer APIs.
- `nip-18.md` — repost and quote repost behavior.
- `nip-23.md` — long-form articles.

## Open questions

- Some constants in `NostrKind` are declared for completeness but may not have full UI flows. Treat this table as code-backed by explicit implementation references, not merely by constants.
- Web has broader utility functions than native in some areas; platform parity should be checked per feature before claiming full support.
- NIP-50 `autocomplete:true/false` is newly noted upstream; decide whether product search should emit or ignore it.
- Android parity for Blossom user server list kind 10063 should be checked before claiming full cross-platform BUD-03 / NIP-B7 parity.
- Shared kind constants for Web NIP-46 Nostr Connect kind 24133 and official NIP-5A nsite kinds are follow-up code hygiene, not required to claim current behavior. iOS signer removal is tracked by ADR-0023.

## Source references

- `android/app/src/main/kotlin/io/nurunuru/app/data/models/NostrModels.kt`
- `ios/NuruNuru/Models/NostrKind.swift`
- `lib/nostr.js`
- `lib/nip46.js`
- `rust-engine/nurunuru-core/src/filters.rs`
- `rust-engine/nurunuru-core/src/engine.rs`
- `rust-engine/nurunuru-ffi/src/lib.rs`

## Related pages

- [[../platforms/parity-matrix]]
- [[../glossary]]
- [[../features/post-composer]]
- [[../features/search]]
- [[../features/image-upload]]
- [[../features/talk]]
- [[../features/relay-management]]
