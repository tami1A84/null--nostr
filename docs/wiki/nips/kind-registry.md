# Kind Registry Audit

## Summary

This page records the 2026-06-09 event-kind audit for null--nostr. It explains which upstream registry sources were checked and highlights kind numbers that affect null--nostr documentation or follow-up code hygiene.

## Current behavior

- null--nostr stores platform kind constants in Android NostrKind, iOS NostrKind, Web helpers/constants, and Rust filter/publish paths.
- The canonical project support claims remain source-backed in [[README]].
- Upstream nostr-protocol/nips says its README Event Kinds table is not exhaustive and points implementers to nostr-protocol/registry-of-kinds for machine-readable registry data.

## Audit baseline

- Audit date: 2026-06-09
- nostr-protocol/nips: 7a2197c00d1bbff19b32d19851f4dffe4810b8ed
- nostr-protocol/registry-of-kinds: d93db0c028184f317497763837e9524246507acb
- Upstream README kind rows parsed for this audit: 172

## High-priority kind notes

| kind | Upstream meaning | null--nostr status |
|---:|---|---|
| 0 | User Metadata | Core profile fetch/publish. |
| 1 | Short Text Note | Core post/timeline publish/fetch. |
| 3 | Follows | Follow list publish/fetch. |
| 4 | Encrypted Direct Messages | Legacy compatibility only. |
| 5 | Event Deletion Request | Delete/unlike/unrepost helper. |
| 6 | Repost | Active repost support. |
| 7 | Reaction | Active reaction/custom emoji reaction support. |
| 8 | Badge Award | Native badge support. |
| 13 | Seal | NIP-59/NIP-17 helper and native constants. |
| 14 / 15 | NIP-17 direct/file messages | Compatibility/boundary; native Talk is Marmot MLS-oriented. |
| 16 | Generic Repost | Constants/partial support; kind 6 is the main repost path. |
| 21 / 22 | NIP-71 regular video / portrait short video | Parsed by iOS video code; not primary publish kind. |
| 40-44 | NIP-28 public chat | Upstream NIP-28 is unrecommended; constants are legacy/compatibility. |
| 62 | Request to Vanish | Helper support. |
| 443 / 444 / 445 | Marmot KeyPackage / Welcome / Group Event | Native Talk MLS ecosystem kinds. |
| 1059 | Gift Wrap | NIP-59 and Marmot Welcome delivery. |
| 1063 | NIP-94 File Metadata | Media/upload-adjacent; full feature support not claimed. |
| 1984 | Reporting | Helper support. |
| 1985 | Label | Birdwatch/context helpers and News self-label category filtering. |
| 9734 / 9735 | Zap request / zap receipt | Active zap support. |
| 10000 / 10003 / 10030 | Mute / bookmark / emoji lists | Active NIP-51 list support. |
| 10002 | Relay List Metadata | Active NIP-65/outbox support. |
| 10050 | DM receiving relay list | NIP-17 compatibility/boundary. |
| 10051 | Marmot KeyPackage relay list | Native Talk MLS ecosystem kind. |
| 10063 | Blossom user server list | iOS/settings + upload ecosystem support; Android parity open. |
| 15128 / 34128 / 35128 | NIP-5A nsite manifests | Official NIP-5A; distinct from Scroll mini-app kinds 1227/10027. |
| 22242 | Relay client authentication | Web helper. |
| 24133 | Nostr Connect | NIP-46 event kind; code paths exist in Web/iOS signer flows. |
| 24242 | Blossom mediaserver blob/auth ecosystem | Used by Blossom upload auth paths. |
| 27235 | HTTP Auth | NIP-98 upload auth paths. |
| 30008 / 30009 | Profile badge set / badge definition | Native badge support. |
| 30023 / 30024 | Long-form content / draft | News/timeline render; News hides drafts. |
| 30030 | Emoji sets | Custom emoji support. |
| 31925 | Calendar Event RSVP | Native scheduler/Chronostr-adjacent constant. |
| 31926-31928 | Chronostr scheduler kinds | Project/ecosystem-specific scheduler constants, not upstream NIP-52 parity claims. |
| 34235 / 34236 | NIP-71 addressable video / addressable short video | ろくなな primarily uses 34236. |

## Platform notes

### Android

- Main constants live in `android/app/src/main/kotlin/io/nurunuru/app/data/models/NostrModels.kt`.
- Follow-up hygiene: add official NIP-5A nsite constants and NIP-46 Nostr Connect kind 24133 only if product code needs them; constants alone should not be treated as support.
- `ADDRESSABLE_SHORT_VIDEO = 34236` maps to upstream “Addressable Short Video Event”; consider aliasing to `ADDRESSABLE_SHORT_VIDEO` in a future code-only cleanup.

### iOS

- Main constants live in `ios/NuruNuru/Models/NostrKind.swift`.
- iOS already has `blossomUserServerList = 10063`.
- `addressableShortVideo = 34236` is behaviorally correct for ろくなな but could be aliased to upstream naming in a future cleanup.

### Web

- Web has broader utility helpers for NIP-07, NIP-46, NIP-50, upload auth, and relay metadata.
- A dedicated `lib/nostr-kinds.js` is optional future hygiene if magic-number drift becomes a maintenance issue.

## Source references

- `android/app/src/main/kotlin/io/nurunuru/app/data/models/NostrModels.kt`
- `ios/NuruNuru/Models/NostrKind.swift`
- `lib/nostr.js`
- `lib/nip46.js`
- `rust-engine/nurunuru-core/src/filters.rs`
- `rust-engine/nurunuru-core/src/engine.rs`

## Related pages

- [[README]]
- [[nip-5a]]
- [[nip-71]]
- [[nip-b7]]
- [[nip-98]]

## Open questions

- Should Android and iOS add aliases for upstream NIP-71 names while retaining the current `VIDEO_LOOP` / `videoLoop` names for compatibility?
- Should `NostrKind` include official NIP-5A nsite manifest kinds now, or only when Mini Apps actually launch nsites?
- Should Web centralize kind constants to reduce magic numbers?
