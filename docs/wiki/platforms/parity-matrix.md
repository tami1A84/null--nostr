# Platform Parity Matrix

## Summary

This matrix tracks broad feature and protocol parity across Web, Android, iOS, and Rust. It is a navigation aid, not a substitute for source-code verification.

## Feature parity

| Area | Web | Android | iOS | Notes |
|---|---|---|---|---|
| Timeline | Yes | Yes | Yes | Native timelines include follow/recommended flows; rendering details differ by platform. |
| News | Yes | Yes | Yes | NIP-23 kind 30023 articles, null.news.category category filtering, source settings, no ranking tab. |
| Post composer | Yes | Yes | Yes | 140-char behavior is strict on native; Web collapse threshold is also 140 with links excluded from count. |
| Target relay publish | Partial | Yes | Yes | Android/iOS composer/repository support explicit target relays; Web has relay list helpers and generic publish. |
| NIP-70 protected posts | Helpers | Yes | Yes | `['-']` tag before signing. |
| Image upload | Yes | Yes | Yes | nostr.build/yabu.me/Blossom paths; auth details vary. |
| PostActions | Yes | Yes | Yes | Like/repost/zap, bookmark where handler/UI is available; no reply button in native PostActions. |
| Bookmarks | Yes | Yes | Yes | NIP-51 kind 10003. |
| Reactions | Yes/partial UI | Yes | Yes | Native custom reaction pickers; Web helpers need UI-by-UI verification. |
| Custom emoji | Partial | Yes | Yes | Native emoji settings/pickers; Web parity should be checked per component. |
| Badges | Partial | Yes | Yes | Native profile badge fetch/settings; Web support is less central. |
| Notifications | Partial | Yes | Yes | Android/iOS have notification sheets/modals; exact coverage differs. |
| Search | Yes | Yes | Yes | Android has advanced parser/routing; Web/iOS include search helpers. |
| Talk / messaging | NIP-17 helpers | Marmot MLS | Marmot MLS | Do not confuse Web NIP-17 helper support with native Talk behavior. |
| External signing | NIP-07 / NIP-46 / Amber bridge helpers | NIP-55 Amber | Removed remote signer; internal nsec + Passkey/Nosskey | iOS uses no NIP-46 signer and no NIP-55. |
| Relay management | Yes | Yes | Yes | NIP-65/outbox helpers and settings exist; connection pooling strongest on Web. |
| ろくなな short video | Partial/constants | Yes | Yes | kind 34236 / OpenVine-compatible flow. |

## Protocol parity highlights

| Protocol | Web | Android | iOS | Rust |
|---|---|---|---|---|
| NIP-17 | Active helpers | Legacy/native boundary | Legacy/native boundary | Legacy helpers |
| NIP-46 | Yes | Not main native path | Removed as iOS signer | Rust supports unsigned/publish primitives; Web may retain NIP-46. |
| NIP-55 | Amber bridge helpers | Yes | No | N/A |
| NIP-57 | Yes | Yes | Yes | Filters/helpers |
| NIP-65 | Yes | Yes | Yes | Filters/helpers |
| Marmot MLS | No native Talk UI | Yes | Yes | Core implementation |
| NIP-98 / Blossom auth | Yes | Yes | Yes | Signing primitives |

## Source references

- `docs/wiki/nips/README.md`
- `docs/wiki/features/talk.md`
- `docs/wiki/features/image-upload.md`
- `docs/wiki/features/relay-management.md`
- `docs/wiki/ui/android-ios-sync.md`
- `lib/nostr.js`
- `android/app/src/main/kotlin/io/nurunuru/app/data/`
- `ios/NuruNuru/Data/`
- `rust-engine/nurunuru-core/src/`

## Related pages

- [[platforms/web]]
- [[platforms/android]]
- [[platforms/ios]]
- [[features/talk]]
- [[nips/README]]
