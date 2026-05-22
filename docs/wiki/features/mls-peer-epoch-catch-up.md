# MLS Peer-Epoch Catch-Up

## Summary

Issue [#183](https://github.com/tami1A84/null--nostr/issues/183) added a
peer-epoch catch-up path for Marmot MLS DMs so an Android client whose local
MDK epoch fell behind the iOS peer can recover without recreating the
conversation. Before this work the only known recovery was the user-driven
"workaround A" (leave the DM on both ends, recreate from scratch) because
strong repair (`mlsClearPendingCommit` + `fetchMlsMessages(repairFull=true)`)
manipulates only local pending state — it cannot fast-forward MDK to a peer's
epoch.

The fix has three cooperating layers:

1. **Replay cache (Rust)** — a sidecar SQLite file
   `{mls_db_path}.replay.sqlite3` that stores every Kind-445 wrapper as it
   reaches `process_message_result`. MLS ciphertext only; never plaintext.
   30-day TTL, 2,000-row per-group cap.
2. **`catch_up_to_peer` API (Rust)** — replays caller-supplied + cached
   wrappers in `created_at` order across up to 8 retry passes, snapshots
   epoch before/after, and returns a typed status.
3. **Deep catch-up + recovery banner (Android)** — `TalkViewModel` escalates
   to deep catch-up whenever standard repair leaves a gap on a DM. When the
   Rust report is `NotRecoverable`, `TalkScreen` shows a banner offering
   "会話を作り直す" (recreate the DM) — automating workaround A as a single tap.

## Current behavior

### Receive path

- `MlsManager::process_message_result` calls
  `MlsManager::cache_kind445_event` *before* invoking
  `mdk.process_message`. The cache write is best-effort; failures are logged
  at debug level and never alter receive semantics.
- The cache schema (`replay_cache(event_id PK, group_id, created_at,
  cached_at, event_json)`) stores only what is needed to replay the wrapper
  later. Indexes cover the per-group ordered scan and the TTL prune.

### Catch-up

- `MlsManager::catch_up_to_peer(group_id_hex, caller_candidates)`:
  1. Snapshot `epoch_before`.
  2. Merge caller candidates and cache rows into a `BTreeMap<event_id, …>`
     so duplicates collapse on `event_id`.
  3. Replay up to 8 passes; each pass classifies events as Application,
     Commit, NeedsSelfUpdate, permanently Dropped, or "still retryable".
  4. Snapshot `epoch_after` and derive an `MlsCatchUpStatus`:

    | Status               | Meaning                                                  | UI action                          |
    |----------------------|----------------------------------------------------------|------------------------------------|
    | `Recovered`          | epoch advanced, no retryable remains                     | none                               |
    | `PartiallyRecovered` | progress made but residual retryables remain             | keep polling                       |
    | `NotRecoverable`     | no progress, missing Commit unavailable everywhere       | offer "会話を作り直す" banner       |
    | `NoSuchGroup`        | group not in MDK                                         | bail (user left the group)         |

- `catch_up_to_peer` never calls `clear_pending_commit` /
  `merge_pending_commit` — PR #180's receive-path invariants are preserved.

### Android orchestration

- `deepCatchUpMlsGroup(groupIdHex)` in `NostrRepositoryTalk.kt` pulls a wider
  Kind-445 window (limit 2,000, timeout 25 s, no
  `preHistoryWatermark` filter), serializes the events, and hands them to
  `rustClient.mlsCatchUpToPeer`. On success it also re-runs
  `fetchMlsMessages(repairFull = true)` so application messages decrypted by
  the catch-up surface in the UI on the next poll.
- `TalkViewModel.runMlsRepair` escalates to `deepCatchUpMlsGroup` whenever a
  DM still reports `mlsStateGapCount > 0` after a repair. The send-preflight
  path does the same after its existing `fullRepairBeforeBlock` step.
- A best-effort `pruneMlsReplayCache()` runs once when Talk is opened
  (piggy-backed on `ensureKeyPackagePublished`), bounded by the 30-day TTL.

### Recovery banner

- `TalkUiState.recoveryStatus` is set to
  `MlsRecoveryStatus.NotRecoverable` by the catch-up loop. The banner uses
  amber surface colors (`0xFFFFF7E0` / `0xFF6B5500`) so it sits above the
  message list but does not collide with bubble colors.
- Buttons: `作り直す` calls `recreateActiveDmConversation()` (leave + create
  fresh DM with the same partner pubkey, anchored at epoch 0). `後で` calls
  `dismissRecoveryBanner()` — the banner re-appears on the next failed
  catch-up if the gap persists.

## Platform notes

### Android

- All catch-up + cache operations run on `Dispatchers.IO`.
- The recovery status is in-memory only (no prefs persistence) so a fresh
  install / app restart starts with `Unknown` and re-evaluates on the next
  poll.
- The recreate path calls `leaveGroup` best-effort; the local SQLite is
  always marked-as-left even if the leave-Commit publish fails.

### iOS

- Full parity with Android since issue #190 (PR landing alongside this page).
- The recovery types `MlsRecoveryStatus { healthy, recovering, notRecoverable, unknown }`
  and `MlsDeepCatchUpResult` live in `ios/NuruNuru/Data/NostrRepository+Talk.swift`
  and mirror the Android `enum class MlsRecoveryStatus` / `data class MlsDeepCatchUpResult`
  one-to-one (`Healthy ↔ healthy`, `Recovering ↔ recovering`,
  `NotRecoverable ↔ notRecoverable`, `Unknown ↔ unknown`).
- `MlsFFIBridge` exposes `mlsCatchUpToPeer`, `mlsPruneReplayCache`,
  `mlsReplayCacheSize`. `MlsFFIStub` returns no-op results so the SwiftUI
  preview path keeps working without the XCFramework. The live
  implementation in `NuruNuruFFILiveClient` calls through to the
  UniFFI-generated `NuruNuruClient` and maps `NuruNuruFFILib.FfiMlsCatchUpStatus`
  to the app-side `FfiMlsCatchUpStatus`.
- `NostrRepository` runs catch-up on the actor (no detached `Task` holds
  the actor pointer). The cached `MlsRecoveryStatus` per group lives in an
  actor-isolated `mlsRecoveryStatuses` dictionary and is wiped on identity
  reset (mlsReset replay-cache sidecar already wipes the underlying file).
- `TalkViewModel` escalates to `deepCatchUpMlsGroup` after the standard
  preflight catch-up in `sendMessage` and after `repairMlsGroupHistory`
  fails to close a DM gap. It restores the cached `recoveryStatus` on
  `openGroup` and clears it on `closeGroup`.
- The SwiftUI banner is `private struct MlsRecoveryBanner` in
  `TalkView.swift`; copy text matches Android exactly per
  `docs/wiki/ui/android-ios-sync.md`:
  - Title: 「メッセージを完全に復元できません」
  - Body: 「相手の最新メッセージを取り戻すために必要なデータがリレーから取得できません。会話を作り直すと、相手と再び新しいメッセージをやり取りできます。」
  - Primary: 「作り直す」 (calls `recreateActiveDmConversation`)
  - Secondary: 「後で」 (calls `dismissRecoveryBanner`)
- The replay-cache prune piggy-backs on the first Talk-open per app
  session (`mlsReplayCachePrunedThisSession` gate) — mirrors Android's
  `ensureKeyPackagePublished` companion call.
- iOS recreate path uses `createMlsDmConversation` (not Android's
  `createDmGroup`) because the iOS repository API is the SwiftUI-friendly
  variant; both ultimately call the same `mls_create_group` +
  `mls_add_member` + Welcome publish flow.

### Rust

- Tests live in
  `rust-engine/nurunuru-core/tests/issue_183_catch_up.rs` (8 tests):
  cache persistence across reopen, caller-supplied recovery,
  cache-only recovery, NotRecoverable when Commit gone, NoSuchGroup
  short-circuit, `mls_reset` wipes the cache, prune no-op safety,
  and the "epoch advances even after an earlier failed decrypt" guarantee.

## Acceptance criteria mapping (issue #183)

- **AC1** — Recovery from a missed-Commit gap of ≥1 epoch without user
  intervention when the Commit is still retrievable: Layer 1 (cache) +
  Layer 2 (`catch_up_to_peer`).
- **AC2** — Actionable UI when recovery is impossible:
  `MlsRecoveryStatus.NotRecoverable` → `MlsRecoveryBanner`.
- **AC3** — No regression to PR #180 receive-path semantics: catch-up never
  touches pending state; cache write is best-effort.
- **AC4** — iOS parity: delivered in issue #190.
  `NuruNuruFFIBridge` exposes `mlsCatchUpToPeer` / `mlsPruneReplayCache` /
  `mlsReplayCacheSize`; `NostrRepository.deepCatchUpMlsGroup` +
  `recreateDmConversation`; `TalkViewModel.recreateActiveDmConversation` +
  `dismissRecoveryBanner`; `MlsRecoveryBanner` in `TalkView.swift`.

## Source references

- `rust-engine/nurunuru-core/src/types.rs`
  - `MlsCatchUpStatus`, `MlsCatchUpReport`
- `rust-engine/nurunuru-core/src/mls.rs`
  - `replay_cache_path_for`, `MlsManager::cache_kind445_event`,
    `MlsManager::catch_up_to_peer`, `is_permanent_process_error`,
    `MLS_REPLAY_CACHE_TTL_SECS`, `MLS_REPLAY_CACHE_MAX_PER_GROUP`
- `rust-engine/nurunuru-core/src/engine.rs`
  - `mls_catch_up_to_peer`, `mls_prune_replay_cache`,
    `mls_replay_cache_size`, replay-cache wipe inside `mls_reset`
- `rust-engine/nurunuru-ffi/src/lib.rs`
  - `FfiMlsCatchUpStatus`, `FfiMlsCatchUpReport`, FFI client methods
    `mls_catch_up_to_peer` / `mls_prune_replay_cache` /
    `mls_replay_cache_size`
- `rust-engine/nurunuru-core/tests/issue_183_catch_up.rs`
- `android/app/src/main/kotlin/io/nurunuru/app/data/NostrRepositoryTalk.kt`
  - `MlsRecoveryStatus`, `MlsDeepCatchUpResult`,
    `deepCatchUpMlsGroup`, `recreateDmConversation`,
    `pruneMlsReplayCache`, `mlsRecoveryStatusFor`
- `android/app/src/main/kotlin/io/nurunuru/app/viewmodel/TalkViewModel.kt`
  - `TalkUiState.recoveryStatus`, deep-catch-up escalation in
    `sendMessage` and `runMlsRepair`, `recreateActiveDmConversation`,
    `dismissRecoveryBanner`
- `android/app/src/main/kotlin/io/nurunuru/app/ui/screens/TalkScreen.kt`
  - `MlsRecoveryBanner`
- `ios/NuruNuru/Data/NuruNuruFFIBridge.swift`
  - `FfiMlsCatchUpStatus`, `FfiMlsCatchUpReport`, protocol additions
    `mlsCatchUpToPeer` / `mlsPruneReplayCache` / `mlsReplayCacheSize`,
    matching `MlsFFIStub` no-ops
- `ios/NuruNuru/Data/NuruNuruFFILiveClient.swift`
  - Live UniFFI bridge implementations + `bridgeCatchUpStatus` mapper
- `ios/NuruNuru/Data/NostrRepository+Talk.swift`
  - `MlsRecoveryStatus`, `MlsDeepCatchUpResult`,
    `deepCatchUpMlsGroup`, `pruneMlsReplayCache`, `mlsRecoveryStatusFor`,
    `clearMlsRecoveryStatus`, `recreateDmConversation`,
    one-shot prune piggy-back inside `fetchMlsGroups`
- `ios/NuruNuru/ViewModels/TalkViewModel.swift`
  - `recoveryStatus`, `recreatingConversation`,
    `recreateActiveDmConversation`, `dismissRecoveryBanner`,
    deep-catch-up escalation in `sendMessage` preflight and
    `repairCurrentGroup`, restore-on-open / clear-on-close hooks
- `ios/NuruNuru/Views/Screens/TalkView.swift`
  - `MlsRecoveryBanner` SwiftUI view (LINE Seed JP, native SwiftUI per
    `ios/GUARDRAILS.md`), rendered inside `GroupChatView` when
    `viewModel.recoveryStatus == .notRecoverable`

## Related pages

- [[features/talk-marmot-mls]]
- [[features/talk-debugging]]
- [[features/talk-ios-android-parity]]
- [[features/mls-db-encryption]]
- [[nips/nip-17]]

## Open questions

- Whether to expose the replay-cache size in the Settings / Diagnostics
  screen so users / support can confirm prune ran.

## Resolved

- iOS UX: resolved in issue #190 — iOS uses the same banner-driven recreate
  UX as Android. No automatic recreate; the user always confirms via
  「作り直す」.
