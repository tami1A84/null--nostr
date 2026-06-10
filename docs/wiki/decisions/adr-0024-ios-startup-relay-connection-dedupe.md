# ADR-0024: Deduplicate iOS startup relay connection attempts

## Status

Proposed — 2026-06-09

## Context

During iOS startup, logs show repeated connection attempts:

```text
fetchEvents: ensuring relay connections
Relay Connecting to 3 relays
```

The audit traced these logs to `NostrRepository.fetchEvents()` and `NostrClient.connect(relayUrls:)`. Startup work from Timeline, Home, explicit repository connect, and notification polling can overlap before the first compact relay pool is usable.

## Decision

Introduce a single deduped startup relay connection path for iOS.

The implementation should provide one repository-owned coordination point such as:

```swift
func ensureRelayConnections(reason: RelayConnectReason, waitForUsable: Bool) async
```

All startup fetch/publish paths must use this coordinator instead of directly starting physical relay connects. Concurrent callers should join the same in-flight task. Logs should include a sanitized reason and whether the caller started, joined, skipped, or retried.

## Alternatives Considered

### Keep per-relay dedupe only in `NostrClient.connect()`

Rejected. It avoids duplicate `SingleRelayClient` objects, but still allows repeated connect calls/logs and unclear startup behavior.

### Remove lazy `fetchEvents()` connection recovery

Rejected. It risks first-launch empty timelines if startup ordering changes. Lazy recovery is useful, but must be deduped.

### Make `repository.connect()` block until all relays finish

Rejected. It would hurt first paint and contradict local-first startup. The coordinator should allow warmup without blocking cached UI.

## Why this fits NuruNuru

- 第一条「日常を壊さない」: startup should feel calm and instant, not blocked by relay churn.
- 第三条「複雑さは裏側に隠す」: relay instability stays behind the UI.
- 第四条「一貫性は新機能より重い」: a stable startup pipeline is more important than adding new surfaces.

## Consequences

- Add `RelayConnectReason` and in-flight task state.
- Route `connect()`, `fetchEvents()`, and publish preflight through the same coordinator.
- Update logs from repeated `Connecting to 3 relays` to reasoned start/join/skip messages.
- Consider gating keep-alive `HomeView.task` network refresh so it does not compete with Timeline first paint.
- Acceptance: initial compact-pool physical connect should happen once; duplicate fetches should join the in-flight task.

## Source references

- `ios/NuruNuru/Data/NostrRepository.swift`
- `ios/NuruNuru/Data/NostrClient.swift`
- `ios/NuruNuru/Views/Screens/MainTabView.swift`
- `ios/NuruNuru/Views/Screens/HomeView.swift`
- `ios/NuruNuru/ViewModels/TimelineViewModel.swift`
- `docs/wiki/platforms/ios-phase0-audit.md`
