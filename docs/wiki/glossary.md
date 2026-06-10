# Glossary

## Summary

Project glossary for terms that are easy to confuse across Nostr, Marmot MLS, Blossom, and platform code.

## Terms

| Term | Meaning in this repository |
|---|---|
| Android/iOS parity | The effort to keep native screens visually and behaviorally aligned while using native UI frameworks. |
| Blossom | Blob storage ecosystem used for media/avatar uploads and fallback resolution. Current code uses Blossom auth kind 24242 for some paths. |
| BUD-03 | Blossom user server list behavior; iOS uses kind 10063 for user server list support. |
| Gift Wrap | NIP-59 outer event, kind 1059. Used by Web NIP-17 DMs and by native Marmot MLS Welcome delivery. |
| KeyPackage | Marmot MLS key package. Canonical kind is 30443; legacy fallback kind 443 exists during migration/interoperability. |
| LINE Seed JP | Required app typography on native platforms. |
| Marmot | MLS-based messaging protocol used by native Talk. Implemented in Rust core and native repositories. |
| MIP-00 | Marmot KeyPackage behavior. |
| MIP-02 | Marmot Welcome / join lifecycle behavior, including kind 1059 wrapped Welcome and key package cleanup/rotation. |
| MIP-03 | Marmot group message behavior, including kind 445 group messages. |
| MLS | Messaging Layer Security. Rust core owns MLS cryptographic/group state for Talk. |
| NIP-17 | Nostr private DM protocol. Web helpers exist; native Talk is currently Marmot MLS-oriented and treats NIP-17 as legacy/compatibility. |
| NIP-30 | Custom emoji lists/sets; kind 10030 and kind 30030. |
| NIP-46 | Nostr Connect remote signing. Web support remains; iOS app signer support was removed by ADR-0023. |
| NIP-51 | Lists such as mute list, bookmarks, and emoji list. |
| NIP-55 | Android Amber external signer path. Not used on iOS. |
| NIP-59 | Gift wrap protocol; key for NIP-17 and Marmot Welcome transport. |
| NIP-65 | Relay List Metadata / outbox model; kind 10002. |
| NIP-70 | Protected events using a `['-']` tag. |
| NIP-71 | Video event family used by ろくなな/OpenVine-compatible short video flows. |
| NIP-92 | Media metadata (`imeta`) used by renderers/upload flows. |
| NIP-98 | HTTP auth event, kind 27235, used by upload endpoints. |
| Outbox model | Routing model that uses users' relay lists for read/write delivery. |
| PostActions | Action row for posts. Current code has like/repost/zap and optional bookmark; no reply button. |
| Relay target publish | Publishing to an explicit selected relay list instead of broadcasting to all configured relays. |
| WhiteNoise | Marmot/MLS ecosystem implementation the native Talk code aims to interoperate with. |
| ろくなな | Short-video feature area using kind 34236 and related video metadata. |

## Source references

- `docs/wiki/nips/README.md`
- `docs/wiki/features/talk.md`
- `docs/wiki/features/image-upload.md`
- `docs/wiki/features/relay-management.md`
- `docs/wiki/ui/android-ios-sync.md`
- `android/app/src/main/kotlin/io/nurunuru/app/data/models/NostrModels.kt`
- `ios/NuruNuru/Models/NostrKind.swift`
- `rust-engine/nurunuru-core/src/mls.rs`

## Related pages

- [[index]]
- [[platforms/parity-matrix]]
- [[nips/README]]
