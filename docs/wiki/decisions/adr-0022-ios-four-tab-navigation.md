# ADR-0022: iOS root navigation is four tabs

## Status

Accepted — 2026-06-09

## Context

The iOS zero-base plan needs a stable root navigation target before deeper UX/performance work. Older project docs and partial implementation history alternated between:

- ホーム / トーク / ろくなな / タイムライン / ミニアプリ
- ホーム / トーク / タイムライン / ニュース / ミニ
- ホーム / トーク / ニュース / ミニアプリ

The user clarified on 2026-06-09 that the target is four tabs: **ホーム / トーク / タイムライン / ミニアプリ**.

## Decision

iOS root navigation uses exactly these four tabs, in this order:

```text
ホーム / トーク / タイムライン / ミニアプリ
```

Rokunana and News are not root tabs in the iOS zero-base target. Rokunana remains governed by ADR-0018 as dead-but-preserved unless a future ADR changes its destination. News may remain as feature/code exploration, but it must not appear in the iOS root tab bar without a new decision.

The bottom-nav copy is **ミニアプリ**, not **ミニ**, for this target.

## Alternatives Considered

### Keep News as a fifth root tab

Rejected. It widens startup surface area, competes with Timeline, and contradicts the newly clarified four-tab target.

### Replace Timeline with News

Rejected for iOS zero-base. Timeline remains a first-class root destination for Nostr posts.

### Restore Rokunana as a root tab

Rejected by ADR-0018 and this ADR. It remains code-only/dead-preserved for now.

## Why this fits NuruNuru

- 第一条「日常を壊さない」: root navigation becomes smaller and easier to understand.
- 第三条「複雑さは裏側に隠す」: experimental/discovery surfaces do not crowd the main app shell.
- 第四条「一貫性は新機能より重い」: this creates a clear parity target for future Android/Web cleanup.

## Consequences

- `MainTabView` should expose only four active root cases.
- Any News/Rokunana code left in the repo must have no root UI entry point.
- Docs/guardrails must stop describing iOS as 5-tab.
- Existing News-tab docs become feature/history docs, not current iOS root-navigation authority.
- A follow-up parity decision is needed if Android/Web currently expose News in root navigation.

## Source references

- User decision on 2026-06-09.
- `ios/NuruNuru/Views/Screens/MainTabView.swift`
- `docs/wiki/platforms/ios-phase0-audit.md`
- `docs/wiki/decisions/adr-0018-rokunana-root-tab-removal.md`
- `ios/GUARDRAILS.md`
