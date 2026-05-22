# null--nostr LLM Wiki Log

LLM Wiki の時系列ログです。追記専用として扱います。

## [2026-05-23] feat | iOS MLS peer-epoch catch-up parity (issue #190)

- Wired the Issue #183 Rust FFI (`mls_catch_up_to_peer`,
  `mls_prune_replay_cache`, `mls_replay_cache_size`) through
  `NuruNuruFFIBridge` + `MlsFFIStub` + `NuruNuruFFILiveClient`. Added
  Swift mirrors `FfiMlsCatchUpStatus` and `FfiMlsCatchUpReport`.
- Added `NostrRepository.deepCatchUpMlsGroup`,
  `pruneMlsReplayCache`, `mlsRecoveryStatusFor`,
  `clearMlsRecoveryStatus`, and `recreateDmConversation` plus the
  `MlsRecoveryStatus` / `MlsDeepCatchUpResult` Swift types — names and
  semantics mirror Android one-to-one. The replay-cache prune now
  piggy-backs on the first `fetchMlsGroups` call per session via a
  `mlsReplayCachePrunedThisSession` gate.
- Added `TalkViewModel.recoveryStatus`, `recreatingConversation`,
  `recreateActiveDmConversation`, and `dismissRecoveryBanner`. Deep
  catch-up is escalated after the standard preflight in `sendMessage`
  and after `repairCurrentGroup` leaves a DM gap; the cached banner
  state is restored on `openGroup` and cleared on `closeGroup`.
- Added the SwiftUI `MlsRecoveryBanner` to `TalkView.swift` with copy
  matching Android exactly (「メッセージを完全に復元できません」 +
  「作り直す」 / 「後で」). Native SwiftUI per `ios/GUARDRAILS.md`.
- Rebuilt the `NuruNuruFFI.xcframework` (device + sim slices) so the
  new UniFFI symbols are linkable; verified
  `_uniffi_uniffi_nurunuru_fn_method_nurunuruclient_mls_catch_up_to_peer`
  / `mls_prune_replay_cache` / `mls_replay_cache_size` are exported
  from both slices. iOS Simulator (iPhone 17) Debug build succeeded
  with no new errors.
- Closes the AC4 requirement from issue #183.

## [2026-05-23] fix | Android MLS peer-epoch catch-up (issue #183)

- Added a sidecar SQLite replay cache (`{mls_db_path}.replay.sqlite3`, 30-day
  TTL, 2,000-row per-group cap) so peer Kind-445 wrappers survive relay aging
  and app process death. Cache writes are best-effort and never alter
  PR #180's receive-path semantics.
- Added `MlsManager::catch_up_to_peer(group_id_hex, candidates)` which
  replays caller-supplied + cached wrappers in `created_at` order across up
  to 8 retry passes and returns a typed `MlsCatchUpReport` with status
  `Recovered` / `PartiallyRecovered` / `NotRecoverable` / `NoSuchGroup`.
  Never touches pending-commit state, so PR #180's invariants are preserved.
- FFI: added `mls_catch_up_to_peer`, `mls_prune_replay_cache`,
  `mls_replay_cache_size` plus `FfiMlsCatchUpReport` /
  `FfiMlsCatchUpStatus`; regenerated Kotlin bindings and cross-compiled the
  arm64-v8a `.so`.
- Android: `NostrRepositoryTalk.deepCatchUpMlsGroup` orchestrates the wider
  Kind-445 relay pull and the FFI catch-up call; `recreateDmConversation`
  automates "workaround A" (leave + create fresh DM). `TalkViewModel`
  escalates to deep catch-up after every standard repair and after the
  send-preflight fullRepair fallback; new `recoveryStatus` UI state plus
  `MlsRecoveryBanner` in `TalkScreen` prompts the user with
  「メッセージを完全に復元できません — 作り直す / 後で」 when the missing
  Commit is no longer retrievable from configured relays and is not in the
  cache (AC2).
- Tests: new `rust-engine/nurunuru-core/tests/issue_183_catch_up.rs`
  (8 tests, all passing); existing 47-test core suite still green.
- Wiki: new `docs/wiki/features/mls-peer-epoch-catch-up.md` and updated
  `docs/wiki/index.md`.

## [2026-05-21] setup | Initial LLM Wiki scaffold

- `AGENTS.md` に LLM Wiki 運用ルールを追加。
- `docs/wiki/` 配下に初期構成を作成。
- Core / Platforms / Features / UI / NIPs / Decisions の最小ページを追加。
- 真実の源泉はソースコード・design tokens・設計文書であり、Wiki は派生ナビゲーション層であることを明記。

## [2026-05-21] docs | Code-backed Wiki corrections and NIP audit

- Corrected stale PostActions documentation: current code has like / repost / zap plus optional bookmark, with no reply button.
- Replaced the short Supported NIPs line with a code-backed NIP support table in `docs/wiki/nips/README.md`.
- Added high-priority pages: `features/image-upload`, `features/talk`, `features/relay-management`, `ui/post-row`, `nips/nip-46`, `nips/nip-57`, `nips/nip-65`, `nips/nip-70`, `nips/nip-71`, and `nips/nip-98`, plus `lint-report`.
- Updated platform and feature pages with source references from Android, iOS, Web, and Rust code.

## [2026-05-21] docs | Detailed NIP boundary pages

- Added detailed pages for `nip-17`, `nip-25`, `nip-30`, `nip-51`, `nip-58`, and `nip-59`.
- Clarified the native Talk boundary: NIP-17 remains legacy/compatibility while Talk is Marmot MLS-oriented; NIP-59 kind 1059 is used for Marmot Welcome delivery.
- Updated `index.md`, `nips/README.md`, and `lint-report.md` to reflect the new pages and remaining doc targets.

## [2026-05-21] docs | Wiki maintenance, parity, glossary, and ADRs

- Removed stale `ios/DESIGN.md` and `ios/SYNC_PLAN.md` links from `AGENTS.md`, replacing them with existing guardrail/wiki links.
- Added `logs/` to `.gitignore` after identifying it as local Android/iOS Talk/Marmot diagnostic output.
- Added `docs/wiki/platforms/parity-matrix.md` and `docs/wiki/glossary.md`.
- Added ADRs for native Marmot MLS Talk, iOS NIP-46 external signing, design tokens, PostActions, and Web Rust bridge stubs.
- Updated `docs/wiki/nips/README.md` with a `Level` column and separated official numbered NIPs from ecosystem/BUD/project-specific protocols.
- Added `scripts/wiki-lint.mjs` for basic Wiki health checks.

## [2026-05-21] tooling | Wiki lint npm script

- Added `npm run wiki:lint` to `package.json` and documented it in `AGENTS.md`.
- Verified the lint script directly with Node because this tool environment does not expose `npm` on PATH.

## [2026-05-21] docs | Low-priority wiki completion

- Added GitHub Actions workflow `.github/workflows/wiki-lint.yml` to run `npm run wiki:lint` on relevant PR/push changes.
- Split Talk documentation into Marmot MLS internals, relay strategy, debugging guidance, and Android/iOS parity pages.
- Added detailed pages for `nip-04`, `nip-18`, `nip-23`, and `nip-44`.
- Updated `index.md`, `nips/README.md`, and `lint-report.md` with the new pages and remaining future targets.

## [2026-05-22] culture | NuruNuru Charter v0.1 と4軸自由ドクトリンを起票

- Theme Day「企業文化・カルチャー構築」の成果として `docs/wiki/culture/` を新設。
- 北極星 + 五箇条を [[culture/principles]] に明文化 (Charter v0.1)。
- [[culture/not-doing]] にやらないことリストを起票 (体験 / 日本語 / 一貫性 / 鍵 / 設計判断 / NIP / 経済 / 配布 / 短期 KPI)。
- [[culture/design-crit]] に Weekly Nuru Design Crit の運用 (沈黙批評 → 発話批評 → 4 ラベル) を定義し、[[decisions/adr-0007-design-crit-ritual]] として制度化を起票。
- [[culture/copy-style]] に日本語コピー規約 (直訳禁止 / 既存採用語保護 / 場面別ガイド) を起票。
- [[culture/llm-onboarding]] に LLM コントリビュータ向けの編集前チェックリストと出力規約を起票。
- ユーザーからの「業界の10年先を行く」「経済 / 配布の自由も視野に入れる」という方針を [[culture/four-freedoms]] に整理し、[[decisions/adr-0008-four-freedoms-mission]] として長期ミッションを Proposed で起票。
- 新規 ADR テンプレート [[decisions/_template]] を追加。
- `.github/pull_request_template_ui.md` に UI 変更 PR チェックリストを追加。
- `AGENTS.md` に Culture セクションを追加し、Wiki から AGENTS へのエントリを確立。
- `docs/wiki/index.md` に Culture セクションを追加し、ADR-0007/0008 とテンプレートをリストに追加。
- 本件はコード変更を伴わない文化憲章 (Proposed)。Phase 1 (Talk Marmot 完成) → Phase 2 (経済) → Phase 3 (配布) のロードマップは [[culture/four-freedoms]] を参照。

## [2026-05-22] security | Issue #181 MLS DB encryption (Android verified, iOS shipped)

- Rust core: `mls_db_path_for(db_path)` as single source of truth for the on-disk MLS SQLite path. `bind_mls_for_pubkey` errors hard on `had_key && bind_failed` (B5). New `mls_is_encrypted() -> Option<bool>` (B7) lifted through UniFFI + napi-rs for app-layer assertion.
- FFI: `NuruNuruClient::new_with_mls_db_key` / `new_read_only_with_mls_db_key` validate 32-byte key length before SQLCipher bind. `derive_mls_db_key_from_secret(secret_hex, app_salt)` exposes HKDF-SHA256 derivation to Kotlin + Swift.
- Android: new `MlsDbKeyStore` (HKDF for internal signer, `EncryptedSharedPreferences` + `MasterKey` for external signer, `synchronized` lock + `commit()` for B4 race). New `MlsLegacyMigration` (content-based plaintext detection via FFI `mls_db_path_for`, runs every launch — B1+B2). `NuruNuruApp.onCreate()` purges before `initEngine()` (M5). Logout clears external key before `prefs.clear()`.
- iOS: mirror `MlsDbKeyStore` (Keychain `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` + `NSLock`) and `MlsLegacyMigration` (`isExcludedFromBackup` on DB/WAL/SHM — M6 partial). `MlsFFILiveClient` uses new encrypted ctors with `inout Data` zeroize via `Data.resetBytes(in:)` (B3). `mlsIsEncrypted() -> Bool?` lifted to `MlsFFIBridge` protocol + stub.
- Verified: a physical Android device runtime confirms plaintext purge + SQLCipher header (`53 51 4c…00` → `21 1d c0…c4`) + `MLS DB encrypted (SQLCipher) — issue #181 guard OK` log. iOS xcodebuild for iPhone 17 simulator returns BUILD SUCCEEDED.
- Bindings: `gen_swift.sh` switched to debug build (workspace `release` profile has `strip = true` which removes UniFFI metadata `.symtab`, causing silent missing-types in bindgen output).
- Wiki: added [[features/mls-db-encryption]] (threat model + verification trace) and [[decisions/adr-0009-mls-db-encryption]] (rationale + alternatives + consequences). Updated `index.md`.
- Open: CI lint to block legacy unkeyed ctor reintroduction (M2), Settings UI status indicator (M4), release notes + CHANGELOG (M6 user-facing).

## [2026-05-23] security | Issue #181 follow-up (M2 CI guard + M6 release notes; M4 dropped)

- M2 (CI guard): added `scripts/issue-181-guard.mjs` + `npm run lint:issue-181`. Walks `ios/NuruNuru/` and `android/app/src/main/kotlin/`, fails on reintroduction of unkeyed `NuruNuruClient(secretKeyHex:)` / `NuruNuruClient.newReadOnly(pubkeyHex:)` ctors. Skips generated `bindgen/` directories. Verified: 214 files scanned, 141 596 pattern checks, 0 violations on clean tree; negative test with 2 injected violations reports both with file:line + remediation hint.
- M6 (release notes): added `[Unreleased] > Security` + `Upgrade notes` to `CHANGELOG.md` documenting the SQLCipher migration and the unavoidable past-message loss on upgrade. Added `docs/release-notes/issue-181-mls-db-encryption.md` with JP + EN short forms for zapstore / GitHub Release / Google Play / TestFlight, plus a support-facing FAQ ("過去メッセージが見えなくなった理由").
- M4 (Settings UI "encrypted ✓"): dropped by product decision. The runtime guard already hard-fails on missing encryption (`MLS DB encrypted (SQLCipher) — issue #181 guard OK` line is mandatory), so a green checkmark would be redundant UI noise without an actionable user signal.
- Verification log unchanged: a physical Android device + a physical iPhone 12 mini both show the guard line on cold launch + SQLCipher random-bytes header on disk. Talk receive-loop logic (PR #180) is NOT touched by this change — `git diff HEAD --stat -- ios/NuruNuru/Data/NostrRepository+Talk.swift android/app/src/main/kotlin/io/nurunuru/app/data/NostrRepositoryTalk.kt` returns empty.
- Unrelated open issue surfaced during log analysis: iOS-sent kind:445 messages occasionally retry-queue on Android as `state_not_ready` (MDK epoch lag). Tracked separately — not an Issue #181 regression.

## [2026-05-23] ux | Android Talk pull-to-refresh and auto-repair

- Added Android open-conversation pull-to-refresh for Talk MLS history catch-up, aligned with the existing Material3 pull-to-refresh pattern used by Timeline.
- Added guarded Android Talk auto-repair after repeated empty Kind-445 relay fetches for an already-populated conversation, using relay fetch stats from `NostrRepositoryTalk.kt`.
- Kept explicit Group Info "メッセージを修復" as the stronger manual repair path while pull/auto refresh avoid clearing pending commits.
## [2026-05-23] fix | Android Talk pull-to-refresh top-edge fallback

- Root cause: Material3 `PullToRefreshContainer` only receives downward drags via `nestedScrollConnection` when the inner scrollable is already at scroll position 0. `GroupChatScreen` runs `listState.animateScrollToItem(messages.size - 1)` on every message update so the LazyColumn is almost always scrolled toward the newest message; the user's pull gesture was consumed by the list as a normal upward scroll and never reached `PullToRefresh`.
- Fix: added a `pointerInput` top-edge drag detector around the message-area `Box` in `TalkScreen.kt`. Touches that start within ~120dp of the conversation viewport top and accumulate ~72dp of downward travel call `pullRefreshState.startRefresh()` directly, which triggers the existing `refreshCurrentGroup()` → `runMlsRepair(source = "pull", clearPendingCommit = false)` path. The Material3 `nestedScrollConnection` is kept as the secondary path for the case where the user has scrolled to the oldest message.
- Visual layout is unchanged (oldest → newest top → bottom, auto-scroll to newest); only the gesture surface is extended.
- Verified by rebuild + reinstall: `BUILD SUCCESSFUL`, versionName=1.5.0, on a physical Android device. iOS parity for this fallback is tracked separately.
## [2026-05-23] ux | Android Talk pull-to-refresh redesigned for LINE-grade parity

- Removed the temporary TopBar refresh icon and the `gid:xxx msg:N` debug subtitle on the conversation screen. Both were diagnostic, not aligned with the LINE-grade visual language.
- Changed conversation auto-scroll to only follow new messages when the user is already within 3 items of the list bottom (`lastVisibleIndex >= totalItems - 3`). While the user is scrolled up to read history, the LazyColumn stays put, so the Material3 `PullToRefreshContainer.nestedScrollConnection` can receive downward drags and pull-to-refresh works naturally from any scroll position.
- The conversation `pointerInput` top-edge fallback is retained as a secondary trigger.
- Conversation pull-to-refresh now performs a STRONG repair (`clearPendingCommit = true`) instead of the weak `repairFull=true`-only path. iOS frequently advances MLS epoch ahead of Android; an explicit user-initiated refresh should clear any stranded Android pending commit so iOS-originated messages decrypt. This matches the strength of the Group Info「メッセージを修復」action.
- Added pull-to-refresh to the Talk list (GroupListScreen) across all three filter pages (すべて / 友だち / グループ) via a shared `PullToRefreshState` and `refreshGroupList()` on the ViewModel. Achieves iOS Talk-list parity.
- Verified by rebuild + reinstall: `BUILD SUCCESSFUL`, versionName=1.5.0, on a physical Android device.
## [2026-05-23] fix | Android Talk render decrypted iOS messages despite residual MLS gaps

- Root cause for "iOS new message fetched but not shown on Android": Android was successfully fetching Kind-445 events and could apply at least one iOS-originated application message, but `TalkViewModel.startMessageStream()` stopped the polling loop on a residual DM MLS gap before writing the normalized message list into `_uiState.messages`. Logs showed `application id=... len=3` followed by residual `state_not_ready` retryables, so the relay/decrypt path was not the only issue; the UI render path was dropping usable history.
- Fix: update `_uiState.messages` before handling residual DM gap diagnostics, and do not break the stream solely because `mlsStateGapCount() > 0` when usable normalized history exists. Manual pull and guarded auto-repair remain responsible for reducing the remaining gap.
- Kept the LINE-grade Talk UX changes: no TopBar refresh icon, no debug `gid:/msg:` subtitle, Android Talk-list pull-to-refresh added, and conversation pull-to-refresh uses strong repair.
- Verified by rebuild + reinstall + launch on a physical Android device: versionName=1.5.0.
