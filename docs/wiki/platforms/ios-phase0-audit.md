# iOS Phase 0 Audit — 4tab / NIP-46 signer removal / startup relay dedupe

## Summary

2026-06-09 user decision: the iOS zero-base target is a **4-tab root navigation** and **no NIP-46 signer**. Phase 0 also audits the observed startup relay-connection storm where `fetchEvents: ensuring relay connections` and `Relay Connecting to 3 relays` appear multiple times immediately after launch.

This page is an audit/spec handoff, not an implementation-complete claim. Source code remains the source of truth until Phase 1 lands.

## Decisions captured

- Root tabs: **ホーム / トーク / タイムライン / ミニアプリ**.
- No root **ろくなな** tab.
- No root **ニュース** tab in the iOS zero-base target.
- iOS **NIP-46 signer is removed**. Do not replace it with NIP-55/Amber.
- Startup relay connection attempts must be deduplicated before Timeline/Home/Talk polish work.

Related ADRs:

- [[../decisions/adr-0022-ios-four-tab-navigation]]
- [[../decisions/adr-0023-ios-remove-nip46-signer]]
- [[../decisions/adr-0024-ios-startup-relay-connection-dedupe]]

## Audit findings: 4-tab navigation

### Current code state

- `ios/NuruNuru/Views/Screens/MainTabView.swift` currently declares `BottomTab` as `case home, talk, timeline, miniapp`, so the active enum already has four cases.
- `MainTabView` still contains commented-out News/Rokunana tab scaffolding and compiled News helper types lower in the same file. They are not active root tabs but are still maintenance noise.
- `BottomTab.miniapp.label` currently returns `"ミニ"`; the new target copy is `"ミニアプリ"`.
- `activeTab` currently defaults to `.timeline`; the 4-tab spec should decide whether first launch/default session opens `.home` to match the tab order and LINE-style Home-centered UX.

### Documentation conflicts found

Several docs still described older 5-tab or News-root targets before this audit:

- `ios/GUARDRAILS.md` — 5-tab root and NIP-46 compliance text.
- `AGENTS.md` — iOS NIP-46 signer and old bottom-nav icon notes.
- `docs/wiki/ui/android-ios-sync.md` — 5 tabs with News/Mini copy.
- `docs/wiki/strategy/june-2026-roadmap.md` — Home/Talk/News/Mini roadmap language.
- `docs/wiki/features/news.md` — News as a main navigation surface.

Phase 0 updates these docs to make the new iOS target explicit. Android/Web parity follow-up remains a separate implementation coordination task because existing uncommitted work in this checkout includes News-tab files on Web/Android.

## Audit findings: NIP-46 signer removal

### Current code paths

The iOS code still contains NIP-46 signer plumbing that must be removed or safely retired in Phase 1:

- `ios/NuruNuru/Data/ExternalSigner.swift` implements the Nostr Connect remote signer client.
- `ios/NuruNuru/ViewModels/AuthViewModel.swift` owns `externalSigner`, `connectExternalSigner(uri:)`, and `loginWithExternalSigner(pubkeyHex:)`.
- `ios/NuruNuru/Views/Screens/LoginView.swift` exposes a Nostr Connect login button/sheet.
- `ios/NuruNuru/Data/NostrRepository.swift` has `externalSigner` constructor injection, NIP-46 signing branches, and `prefs.isExternalSigner` checks.
- `ios/NuruNuru/Data/AppPreferences.swift` persists `isExternalSigner`.

### Removal target

Supported iOS signer paths after Phase 1:

- Internal nsec signer backed by Keychain.
- RustInternalSigner for internal nsec accounts when the Rust signing rollout flag is enabled.
- Passkey/Nosskey signer via `EventSigner`.

Not supported on iOS after Phase 1:

- NIP-46 / Nostr Connect remote signer.
- NIP-55 / Amber.

### Migration requirement

Existing installations with `prefs.isExternalSigner == true` cannot silently keep publishing after NIP-46 removal because the app has no local signing key. Phase 1 must choose a safe migration path; recommended default:

1. Detect NIP-46 session at startup.
2. Stop write-capable session initialization.
3. Show calm re-login copy.
4. Offer Passkey/Nosskey, nsec import, or new local account.
5. Clear NIP-46 session flags only after user action or explicit logout.

Do not silently create a new account, export remote-signer material, or fall back to unsigned/partial events.

## Audit findings: startup relay connection duplication

### Observed logs

The repeated startup logs match the following call sites:

- `fetchEvents: ensuring relay connections ...` — `ios/NuruNuru/Data/NostrRepository.swift`, inside `NostrRepository.fetchEvents()`.
- `Relay Connecting to 3 relays: ...` — `ios/NuruNuru/Data/NostrClient.swift`, inside `NostrClient.connect(relayUrls:)`.

The `3` relay compact startup pool comes from `NostrRepository.buildRelayConnectionUrls()`:

- `wss://yabu.me`
- `wss://r.kojira.io`
- `wss://relay-jp.nostr.wirednet.jp`

### Likely startup sequence

```text
MainTabView.task
  ├─ timelineVM.startInitialLoadIfNeeded()
  │   └─ loadFreshData()
  │       ├─ fetchGlobalTimelineFast() -> fetchEvents()
  │       └─ fetchFollowingTimelineFast() -> fetchEvents() when cached follows exist
  ├─ repository.connect()
  │   └─ starts detached client.connect(relayUrls:) and returns early
  └─ startNotificationDotPollingIfNeeded()
      └─ fetchNotificationsWithContext() -> fetchEvents() family

HomeView.task also runs because HomeView is kept alive in the root ZStack
  └─ homeVM.loadProfile()
      ├─ fetchProfile()
      ├─ fetchFollowList()
      ├─ fetchBadges()
      ├─ fetchUserNotes()
      └─ fetchLikedEvents()
```

`TalkViewModel` is less likely to be a startup cause because the code intentionally avoids loading Talk groups in `init`; Talk loads when the tab is selected or a DM action starts.

### Root cause

The existing implementation has per-relay dedupe inside `NostrClient.connect()`, but not a single repository-level startup connection coordinator.

Contributing details:

- `NostrRepository.connect()` launches `client.connect()` in a detached task and returns immediately.
- `fetchEvents()` calls `client.connect()` when the aggregate client state is `.connecting`, `.disconnected`, `.failed`, or when the client is empty.
- `NostrClient.connect()` logs `Connecting to N relays` for every invocation, even when it only waits on already-connecting relay clients.
- `HomeView` is kept alive with opacity in the root ZStack, so `.task { await viewModel.loadProfile() }` can start network work even when Home is not the active tab.
- `TimelineViewModel.loadFreshData()` can start global and following fetches concurrently; each route may enter `fetchEvents()`.

## Phase 1 handoff: dedupe design

Add a repository-owned startup relay coordinator, either as a new actor or as actor-isolated state inside `NostrRepository`.

Minimum design:

```swift
actor RelayConnectionCoordinator {
    private var inFlightTask: Task<Void, Never>?
    private var lastAttemptAt: Date?
    private var lastUsableAt: Date?

    func ensureConnected(reason: RelayConnectReason, waitForUsable: Bool) async {
        if let task = inFlightTask {
            AppLogger.log("Relay", "ensureConnected joined in-flight reason=\(reason)")
            if waitForUsable { await task.value }
            return
        }

        if recentlyUsable { return }

        let task = Task {
            await client.connect(relayUrls: startupRelays)
        }
        inFlightTask = task
        if waitForUsable { await task.value }
        inFlightTask = nil
    }
}
```

Recommended repository API:

```swift
func ensureRelayConnections(reason: RelayConnectReason, waitForUsable: Bool) async
```

Recommended reasons:

- `appBootstrap`
- `timelineInitial`
- `homeInitial`
- `notificationPoll`
- `publish`
- `manualReconnect`
- `relaySettings`

### Fetch behavior change

`fetchEvents()` should not start a new physical connect while a startup connect is already in flight. It should join the in-flight task or use existing partial connections.

Suggested rule:

| Client state | `fetchEvents()` behavior |
|---|---|
| connected | fetch immediately |
| connecting + coordinator task exists | join or fetch after short wait, no new connect log |
| disconnected/failed | call coordinator with reason |
| empty | call coordinator with reason |

### Logging acceptance criteria

Startup logs should become reasoned and deduped:

```text
Relay ensureConnected start reason=appBootstrap relays=3 task=relay-warmup-001
Relay ensureConnected joined in-flight reason=timelineInitial task=relay-warmup-001
Relay ensureConnected joined in-flight reason=homeInitial task=relay-warmup-001
Relay connected relays=2/3 duration=420ms task=relay-warmup-001
```

Acceptance target:

- One physical compact-pool connection attempt during app bootstrap.
- `Relay Connecting to 3 relays` appears at most once for the initial compact pool.
- Later `fetchEvents()` calls may log joined/skipped reasons but must not create repeated physical connect attempts.
- Home cached paint and Timeline first paint must not block on all relays completing.

## Phase 1 task list

1. Add `RelayConnectReason` and coordinator/in-flight task state.
2. Route `repository.connect()`, `fetchEvents()`, and `publishEventAndReturnSigned()` through the same deduped ensure path.
3. Reorder or stagger startup so Home/Timeline/Notifications do not all trigger physical connect simultaneously.
4. Gate `HomeView.task` remote work with `loadProfileIfNeeded()` and/or active-tab bootstrap policy.
5. Remove iOS NIP-46 UI from Login.
6. Remove NIP-46 signing branches from Repository/AuthViewModel after migration handling is in place.
7. Set root tab copy/order to ホーム / トーク / タイムライン / ミニアプリ.
8. Decide default active tab (`.home` recommended unless Design Crit keeps `.timeline`).

## Open questions

- Should the default active tab become `.home` immediately in Phase 1?
- Should News remain as code-only/dead code, move into Mini Apps, or be removed from iOS entirely?
- What exact copy should be shown to existing NIP-46 users during forced re-login migration?
- Should Android/Web root navigation be brought back to the same 4-tab target in the same release train, or tracked as separate parity follow-up?


## Phase 1 implementation status — 2026-06-09

Implemented the first Phase 1 pass from this audit:

- `MainTabView.swift` now defaults to `ホーム`, keeps the active root enum to `home / talk / timeline / miniapp`, uses the bottom-nav copy `ミニアプリ`, and removes compiled News/Rokunana root-tab implementation noise from this file.
- iOS NIP-46/Nostr Connect signer code paths were removed from active app code: `ExternalSigner.swift` was deleted from the Xcode project, Login no longer exposes Nostr Connect UI, `AuthViewModel` no longer creates or logs into external signer sessions, and `NostrRepository` signs via internal Keychain/RustInternalSigner or Passkey/Nosskey only. Legacy `isExternalSigner` remains as a migration flag that blocks write-capable login and asks the user to re-login.
- `NostrRepository.ensureRelayConnections(reason:waitForUsable:)` now provides repository-level in-flight de-dupe for startup relay warmup. `connect()`, `fetchEvents()`, and publish paths route through the shared ensure path, so concurrent Timeline/Home/notification startup fetches join the same connect task instead of each calling `NostrClient.connect(relayUrls:)`.

Verification:

- `cd ios && xcodebuild -scheme NuruNuru -destination 'platform=iOS Simulator,name=iPhone 17' -skipPackagePluginValidation build` — succeeded.

## Source references

- `ios/NuruNuru/Views/Screens/MainTabView.swift`
- `ios/NuruNuru/Views/Screens/HomeView.swift`
- `ios/NuruNuru/ViewModels/TimelineViewModel.swift`
- `ios/NuruNuru/ViewModels/TalkViewModel.swift`
- `ios/NuruNuru/ViewModels/AuthViewModel.swift`
- `ios/NuruNuru/Data/NostrRepository.swift`
- `ios/NuruNuru/Data/NostrClient.swift`
- `ios/NuruNuru/Data/ExternalSigner.swift`
- `ios/NuruNuru/Data/AppPreferences.swift`
- `ios/GUARDRAILS.md`

## Related pages

- [[ios]]
- [[../ui/android-ios-sync]]
- [[../features/relay-management]]
- [[../nips/nip-46]]
