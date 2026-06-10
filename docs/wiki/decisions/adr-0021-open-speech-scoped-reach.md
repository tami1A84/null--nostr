# ADR-0021: Open Speech, Scoped Reach, and Native Posting Parity

## Status

Accepted — 2026-06-06

## Context

Apple / Google control native app distribution, and centralized SNS services already hold strong network effects. null--nostr cannot win by becoming a larger LINE, X, Meta, or TikTok clone. It can win by implementing sovereign communication as Japanese daily UX: open protocol, portable keys, and small trusted communities.

A strategy discussion considered several product directions: relationship-scoped timelines, two-step-flow discovery, invitation / starter graph based onboarding, community boundaries, and possibly limiting posting on native apps while leaving PWA / Desktop write-capable. The user accepted the strategy except for native posting limits.

Native posting restrictions conflict with the charter's first principle (日常を壊さない), cross-platform consistency, and the mission of sovereign communication. Store and safety risk should be handled by what the app displays and recommends, not by removing the user's ability to write from iOS / Android.

## Decision

null--nostr adopts **open speech with scoped reach**:

1. **Publishing remains open.** Users should be able to publish from Web, Android, and iOS clients according to each platform's existing post-composer guardrails.
2. **Reach is scoped.** A Nostr event being publishable does not mean it is entitled to appear in every primary timeline.
3. **Native posting parity is required.** iOS / Android must not be made read-only or posting-disabled while PWA / Desktop remain write-capable, unless a future ADR explicitly overrides this decision for a narrow emergency.
4. **Primary surfaces are relationship-scoped.** Home content is follow-graph oriented. News / Mini Apps discovery should use 2-hop trust graph and curated / starter paths rather than arbitrary relay-wide feeds.
5. **Community boundaries are legitimate.** Invitation, starter graph, mute / block / report, relay choices, and community curation are allowed as display and onboarding mechanisms.
6. **Exit remains protected.** Users keep keys and can move to other clients / relays. A null--nostr display decision must not become protocol-level lock-in.

## Definitions

| Term | Meaning in null--nostr |
|---|---|
| Speech / publishing | Signing and sending a Nostr event from a user-controlled identity. |
| Reach / display | Whether null--nostr shows, ranks, recommends, or notifies about an event. |
| Curation | Human or graph-based choice about what appears in primary UI. |
| Community boundary | A product / social boundary that limits membership, visibility, or recommendation. |
| Native posting parity | Web, Android, and iOS all retain the ability to post within their platform guardrails. |

## Implementation guidance

### Keep

- Web / Android / iOS post composer flows.
- 140-character post limit on native / Web composer surfaces where already enforced.
- Replies, quote reposts, image upload paths, NIP-70 protected events, and relay-targeted publishing.
- Relay settings and NIP-65 relay metadata.
- Mute / block / report / safety filters.

### Do not introduce

- A policy where iOS / Android are read-only while PWA / Desktop can publish.
- A primary global relay feed that shows arbitrary latest relay posts.
- Automatic follow of starter accounts without a separate Design Crit and ADR.
- Copy implying that every published event deserves placement in every user's timeline.

### Safety posture

Store and community safety should be handled through:

- [[adr-0013-relay-feed-removal]]: no arbitrary relay-wide feed in primary UI.
- [[adr-0020-safe-starter-graph]]: safe first Home without forced follow.
- 2-hop trust graph discovery for News / Mini Apps.
- Mute / block / report label precedence.
- NIP-70 protected events and relay-targeted publishing where appropriate.
- Platform-specific UGC review / reporting affordances when required by store policy.

## Consequences

### Positive

- Preserves daily mobile posting, which is essential for LINE-like communication.
- Avoids turning iOS / Android users into second-class, read-only participants.
- Keeps the speech/display distinction clear: users can publish, but null--nostr can curate primary surfaces.
- Aligns with the four freedoms doctrine and the five-principle charter.
- Lets the project defend against spam / illegal content without becoming a central censor of publishing.

### Negative / risks

- Native apps must continue to handle UGC safety, reporting, and store review expectations.
- Users may still confuse scoped display with censorship unless copy is careful.
- Posting parity increases implementation burden across Web / Android / iOS.
- Community curation can become opaque if starter graph / 2-hop rules are not documented.

## Platform notes

### Web

Web remains a write-capable PWA. Secure key handling must continue to use `lib/secure-key-store.js`; private keys must not be exposed through `window.*`.

### Android

Android remains write-capable through `PostModal.kt` and repository publish paths. FFI, uploads, and file I/O must stay on `Dispatchers.IO`.

### iOS

iOS remains write-capable through `PostSheet.swift` and repository publish paths. Key material stays in Keychain / supported platform signer flows; ADR-0023 removes the iOS NIP-46 signer path and NIP-55 remains unsupported.

## Open questions

- How should beginner copy explain that a post can exist on Nostr while not appearing in a given community view?
- Which UI surfaces should expose starter graph provenance, if any?
- What minimal reporting affordances are needed for store review without making the product feel like a moderation bureaucracy?
- If a future platform policy forces temporary write restrictions, what emergency ADR process and sunset criteria are required?

## Source references

- User decision, 2026-06-06: 「ネイティブアプリの投稿制限はやめよう。それ以外は承認します。」
- `docs/wiki/strategy/themaday-2026-06-06-company-culture.md`
- `docs/wiki/culture/principles.md`
- `docs/wiki/culture/four-freedoms.md`
- `docs/wiki/culture/not-doing.md`
- `docs/wiki/decisions/adr-0013-relay-feed-removal.md`
- `docs/wiki/decisions/adr-0020-safe-starter-graph.md`
- `docs/wiki/features/post-composer.md`
- `android/app/src/main/kotlin/io/nurunuru/app/ui/components/PostModal.kt`
- `ios/NuruNuru/Views/Sheets/PostSheet.swift`
- `components/PostModal.js`

## Related pages

- [[../strategy/themaday-2026-06-06-company-culture]]
- [[../culture/principles]]
- [[../culture/four-freedoms]]
- [[../culture/not-doing]]
- [[adr-0013-relay-feed-removal]]
- [[adr-0020-safe-starter-graph]]
- [[../features/post-composer]]
