import Foundation

/// MLS / トーク関連メソッド群 — グループ取得・メッセージ送受信・グループ管理。
/// Android の NostrRepositoryTalk.kt に相当。
///
/// 設計方針:
///   - Rust SQLite (MDK) を single source of truth とし、アプリ側キャッシュは保持しない。
///   - processedIds は relay イベントの二重復号防止のみに使用（セッション内）。
///   - processedWelcomeIds は Welcome の再処理を防止。
///   - Marmot MIP-00〜03 準拠。WhiteNoise 互換。
extension NostrRepository {


    private func mlsDisplayContent(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.first == "{", let data = raw.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let content = obj["content"] as? String else {
            return raw
        }
        return content
    }

    private func mlsLogPrefix(_ value: String) -> String {
        value.count <= 8 ? value : String(value.prefix(8)) + "…"
    }

    private func mlsRedactedError(_ error: Error) -> String {
        let raw = String(describing: error).lowercased()
        if raw.contains("gift-wrap unwrap failed") || raw.contains("extract_rumor") {
            return "gift_wrap_unwrap_failed"
        }
        if raw.contains("no signer") { return "no_signer" }
        if raw.contains("hmac") { return "hmac_error" }
        if raw.contains("not_mls_welcome_rumor") { return "not_mls_welcome_rumor" }
        if raw.contains("missing key package") || raw.contains("keypackage") || raw.contains("key package") {
            return "missing_or_stale_key_package"
        }
        if raw.contains("no matching key package") || raw.contains("key package not found") || raw.contains("init key") {
            return "missing_or_stale_key_package"
        }
        if raw.contains("process_welcome") || raw.contains("welcome") {
            let compact = raw.replacingOccurrences(of: "\n", with: " ").prefix(260)
            return "welcome_accept_failed:\(compact)"
        }
        if raw.contains("content") || raw.contains("payload") || raw.contains("plaintext") || raw.contains("secret") || raw.contains("private") {
            return "redacted_error"
        }
        return "mls_error"
    }

    private func mlsWelcomeRejectDiagnostic(_ ev: NostrEvent, myPubkeyHex: String, error: Error) -> String {
        let pTags = ev.getTagValues("p").map { mlsLogPrefix($0) }.joined(separator: ",")
        let addressedToMe = ev.getTagValues("p").contains { $0.caseInsensitiveCompare(myPubkeyHex) == .orderedSame }
        return "id=\(ev.id) kind=\(ev.kind) from=\(mlsLogPrefix(ev.pubkey)) p=[\(pTags)] mine=\(addressedToMe) contentLen=\(ev.content.count) retryAfter=60s err=\(mlsRedactedError(error))"
    }

    /// Marmot/WhiteNoise interop relays shared by Welcome rediscovery and Kind-445 fanout.
    ///
    /// After an iOS reinstall the local MLS DB is empty, so the app must rediscover historical
    /// Kind-1059 Welcomes from the relays WhiteNoise uses for account inbox/group fanout before
    /// it can subscribe to that group's Kind-445 #h feed. `groupIdHex` remains the Nostr group id.
    private var mlsInteropRelayUrls: [String] {
        [
            "wss://auth.nostr1.com",
            "wss://relay.0xchat.com",
            "wss://relay.damus.io",
            "wss://relay.primal.net",
            "wss://nos.lol",
            "wss://yabu.me",
            "wss://r.kojira.io",
            "wss://relay.nostr.wirednet.jp",
            "wss://relay-jp.nostr.wirednet.jp",
            "wss://relay.nostr.band",
            "wss://purplepag.es"
        ]
    }

    private var defaultMlsKeyPackageRelays: [String] {
        [
            "wss://relay.0xchat.com",
            "wss://auth.nostr1.com",
            "wss://relay.damus.io",
            "wss://relay.primal.net",
            "wss://nos.lol",
            "wss://relay.nostr.wirednet.jp",
            "wss://yabu.me",
            "wss://r.kojira.io"
        ]
    }

    private var defaultMlsInboxRelays: [String] {
        ["wss://auth.nostr1.com", "wss://yabu.me", "wss://r.kojira.io", "wss://relay.damus.io"]
    }

    private func myMlsKeyPackageRelays() -> [String] {
        let configured = prefs.mlsKeyPackageRelays
        return canonicalRelayUrls(configured.isEmpty ? (Array(prefs.selectedRelays.prefix(3)) + defaultMlsKeyPackageRelays) : configured)
    }

    private func myMlsInboxRelays() -> [String] {
        let configured = prefs.mlsInboxRelays
        return canonicalRelayUrls(configured.isEmpty ? (Array(prefs.selectedRelays.prefix(3)) + defaultMlsInboxRelays) : configured)
    }

    private func mlsDiscoveryRelayUrls(_ extra: [String] = []) -> [String] {
        canonicalRelayUrls(extra + prefs.selectedRelays + myMlsKeyPackageRelays() + myMlsInboxRelays() + mlsInteropRelayUrls)
    }

    private func mlsRelayUrls(_ relays: [String] = []) -> [String] {
        canonicalRelayUrls(relays + prefs.selectedRelays + myMlsInboxRelays() + mlsInteropRelayUrls)
    }

    private func isCurrentAccountMlsGroup(_ group: FfiMlsGroupInfo, account: String? = nil) -> Bool {
        let pk = (account ?? prefs.publicKeyHex ?? "").lowercased()
        guard !pk.isEmpty else { return false }
        return group.memberPubkeys.contains { $0.lowercased() == pk }
    }

    func isMlsGroupBroken(groupIdHex: String) -> Bool {
        mlsBrokenGroupIds.contains(groupIdHex)
    }

    private func markMlsGroupBroken(groupIdHex: String, reason: String) {
        mlsBrokenGroupIds.insert(groupIdHex)
        mlsRetryableStateCount[groupIdHex] = 0
        mlsRetryableEventCooldownUntil[groupIdHex] = [:]
        AppLogger.log("MLS", "markMlsGroupBroken group=\(groupIdHex) reason=\(reason)")
    }

    // MARK: - Group Fetch

    /// MLS グループ一覧をリレー + Rust SQLite から取得する。
    ///
    /// 1. Kind-1059 Welcome (NIP-59 gift-wrapped) を取得し未処理分を process
    ///    （Kind-444 legacy は互換 fallback としてのみ扱う）
    /// 2. mlsListGroups() で Rust SQLite のグループ一覧取得
    /// 3. Welcome process 直後の group を含めてメンバープロファイルエンリッチ
    func fetchMlsGroups(myPubkeyHex: String) async throws -> [MlsGroup] {
        guard let ffi = ensureMlsClient() else {
            AppLogger.log("MLS", "fetchMlsGroups: FFI unavailable")
            return []
        }

        // 1. Welcome イベント取得 → 未処理分のみ process
        //    WhiteNoise 等がデフォルト外リレーに publish する可能性あり
        //    → グローバルリレーも含めた広い範囲から取得
        //    FFI/App boundary の groupIdHex は常に Nostr group id (Kind 445 `h`)。
        //    internal MLS group id は Rust 内部に閉じ込める。
        var joinedGroupsById: [String: FfiMlsGroupInfo] = [:]
        do {
            let welcomeRelays = mlsRelayUrls()
            await client.connect(relayUrls: welcomeRelays)

            // Keep our current install inviteable, but do it asynchronously so
            // restoration of already-joined conversations is not delayed.
            Task { await self.ensureKeyPackagePublished(ffi: ffi, myPubkeyHex: myPubkeyHex) }

            // Issue #183: piggy-back a one-shot prune of the Rust replay-cache
            // sidecar on the first Talk open per session. Best-effort; safe to
            // call when the cache file does not yet exist.
            if !mlsReplayCachePrunedThisSession {
                mlsReplayCachePrunedThisSession = true
                Task { await self.pruneMlsReplayCache() }
            }

            // Interop bootstrap: when NuruNuru is opened only to recover existing
            // WhiteNoise/Marmot groups, users may never invoke createDm/createGroup,
            // so our MLS KeyPackage might never be published. A peer can create a
            // Welcome for this device only after it can fetch this device's latest
            // KeyPackage. Ensure it exists during Talk group sync as well.
            // async above; do not block group restore on KeyPackage publication

            // Primary path: Marmot MIP-02 Welcome delivery is kind:1059 addressed to
            // the KeyPackage owner pubkey via #p. Pass the complete signed 1059
            // event JSON to Rust so it can NIP-59 unwrap and process/join.
            // Also fetch canonical/plain Welcome rumors (444) and the newer Marmot
            // ecosystem alias (10444) in the same pass. Some relays contain many
            // unrelated NIP-17 kind:14 gift-wraps addressed to us; Rust now rejects
            // those as not_mls_welcome_rumor, but querying 444/10444 directly avoids
            // depending on a specific wrapping shape for WhiteNoise interop.
            let welcomeKinds = [NostrKind.mlsWelcome, NostrKind.mlsWelcomeInner, NostrKind.mlsWelcomeInnerMarmot]
            let f = NostrFilter(ids: nil, authors: nil, kinds: welcomeKinds, since: nil, until: nil, limit: 500, tags: ["#p": [myPubkeyHex]], search: nil)
            var welcomeEvents = await fetchEvents(filters: [f], timeoutSeconds: 8.0)
            if welcomeEvents.isEmpty {
                let broad = NostrFilter(ids: nil, authors: nil, kinds: welcomeKinds, since: nil, until: nil, limit: 500, tags: nil, search: nil)
                let broadEvents = await fetchEvents(filters: [broad], timeoutSeconds: 5.0)
                let mine = broadEvents.filter { $0.getTagValues("p").contains(myPubkeyHex) }
                AppLogger.log("MLS", "fetchMlsGroups: welcome #p fallback broad=\(broadEvents.count) mine=\(mine.count) kinds=\(welcomeKinds)")
                welcomeEvents = mine
            }

            let welcomeKindCounts = Dictionary(grouping: welcomeEvents, by: { $0.kind }).mapValues { $0.count }
            AppLogger.log("MLS", "fetchMlsGroups: welcome candidates total=\(welcomeEvents.count) byKind=\(welcomeKindCounts)")

            var newCount = 0
            let now = Int64(Date().timeIntervalSince1970)
            var persistedRejectedWelcomeRetryAfter = prefs.mlsRejectedWelcomeRetryAfterById
            // Prune expired persisted gates opportunistically. Keep only future retry windows.
            persistedRejectedWelcomeRetryAfter = persistedRejectedWelcomeRetryAfter.filter { $0.value > now }
            if persistedRejectedWelcomeRetryAfter.count != prefs.mlsRejectedWelcomeRetryAfterById.count {
                prefs.mlsRejectedWelcomeRetryAfterById = persistedRejectedWelcomeRetryAfter
            }
            for ev in welcomeEvents {
                guard !processedWelcomeIds.contains(ev.id) else { continue }
                if let retryAfter = rejectedWelcomeRetryAfter[ev.id], retryAfter > now {
                    continue
                }
                if let retryAfter = persistedRejectedWelcomeRetryAfter[ev.id], retryAfter > now {
                    rejectedWelcomeRetryAfter[ev.id] = retryAfter
                    continue
                }
                guard let eventJSON = encodeEventJSON(ev) else { continue }

                // Gate before processing, not only in catch. NostrRepository actors are reentrant
                // across relay fetch awaits and multiple repository instances can exist during SwiftUI
                // lifecycle churn; persisting the gate prevents the same rejected Welcome from being
                // unwrapped/processed repeatedly on every loadGroups tick. The gate is removed on success.
                let retryAfterOnFailure = now + 60
                rejectedWelcomeRetryAfter[ev.id] = retryAfterOnFailure
                persistedRejectedWelcomeRetryAfter[ev.id] = retryAfterOnFailure
                prefs.mlsRejectedWelcomeRetryAfterById = persistedRejectedWelcomeRetryAfter

                do {
                    try validateWelcomeEventForMip02(ev)
                    let joined = try ffi.mlsProcessWelcome(welcomeEventJSON: eventJSON)
                    processedWelcomeIds.insert(ev.id)
                    rejectedWelcomeRetryAfter.removeValue(forKey: ev.id)
                    persistedRejectedWelcomeRetryAfter.removeValue(forKey: ev.id)
                    prefs.mlsRejectedWelcomeRetryAfterById = persistedRejectedWelcomeRetryAfter
                    joinedGroupsById[joined.groupIdHex] = joined
                    AppLogger.log("MLS", "fetchMlsGroups: welcome process success id=\(ev.id) kind=\(ev.kind) group=\(joined.groupIdHex)")
                    // MIP-02 follow-up work can be slow (relay catch-up, self-update,
                    // KeyPackage rotation). Do not block the Talk group list on it; otherwise
                    // a successful Welcome join still leaves the UI spinning for 20–40s and
                    // the repository actor blocks user sends during "sync".
                    Task { [weak self] in
                        guard let self else { return }
                        await self.rotateConsumedKeyPackageAfterWelcomeIfNeeded(welcomeEvent: ev, ffi: ffi)
                        await self.postWelcomeBestEffortCatchUpAndSelfUpdate(group: joined, ffi: ffi)
                    }
                    newCount += 1
                } catch {
                    // HMAC / process_welcome failures for old or non-matching 1059 events are common on relays.
                    // Keep them retryable, but back off per event so Talk UI actions are not delayed by
                    // reprocessing the same rejected Welcomes every loadGroups tick.
                    rejectedWelcomeRetryAfter[ev.id] = retryAfterOnFailure
                    persistedRejectedWelcomeRetryAfter[ev.id] = retryAfterOnFailure
                    prefs.mlsRejectedWelcomeRetryAfterById = persistedRejectedWelcomeRetryAfter
                    AppLogger.log("MLS", "fetchMlsGroups: welcome processing rejected \(mlsWelcomeRejectDiagnostic(ev, myPubkeyHex: myPubkeyHex, error: error))")
                }
            }
            if newCount > 0 {
                AppLogger.log("MLS", "fetchMlsGroups: processed \(newCount) new Welcome(s) of \(welcomeEvents.count)")
            }
        } catch {
            AppLogger.log("MLS", "fetchEventsFromRelay (Welcome) failed: \(mlsRedactedError(error))")
        }

        // 2. Rust SQLite からグループ一覧
        var ffiGroupsAll = try ffi.mlsListGroups()
        let beforeAccountFilter = ffiGroupsAll.count
        // MLS SQLite can contain groups from a previous login/account because the
        // iOS MDK database path is app-global. Never expose groups that do not
        // include the currently logged-in Nostr pubkey; a freshly generated key
        // must not see/decrypt prior account conversations.
        ffiGroupsAll = ffiGroupsAll.filter { g in
            g.memberPubkeys.contains { $0.caseInsensitiveCompare(myPubkeyHex) == .orderedSame }
        }
        if beforeAccountFilter != ffiGroupsAll.count {
            AppLogger.log("MLS", "fetchMlsGroups: account-filtered stale groups removed=\(beforeAccountFilter - ffiGroupsAll.count) account=\(mlsLogPrefix(myPubkeyHex))")
        }
        if !joinedGroupsById.isEmpty {
            var byId = Dictionary(uniqueKeysWithValues: ffiGroupsAll.map { ($0.groupIdHex, $0) })
            for (groupId, joined) in joinedGroupsById where byId[groupId] == nil {
                // mlsProcessWelcome returns an active group immediately. If list refresh
                // lags for any runtime, include/enrich the returned Nostr group id so UI
                // can show the newly joined group in this fetch cycle.
                byId[groupId] = (try? ffi.mlsGetGroupInfo(groupIdHex: groupId)) ?? joined
            }
            ffiGroupsAll = Array(byId.values)
        }
        var hidden = prefs.hiddenMlsGroupIds
        // invalid DM（相手不在 = 自分だけ）を一覧から除外。
        // WhiteNoise interop recovery: do NOT apply local hidden tombstones to valid
        // peer DMs. Earlier auto-recovery hid the real shared WhiteNoise group and
        // made iOS send to a newly-created orphan group instead. Explicit leave still
        // hides non-DM groups; DM deletion must be revisited after interop is stable.
        var ffiGroups = ffiGroupsAll.filter { g in
            if g.isDm {
                return g.memberPubkeys.contains(where: { $0 != myPubkeyHex })
            }
            guard !hidden.contains(g.groupIdHex) else { return false }
            return true
        }

        // Recovery safety: a previous stuck-DM auto recovery could locally hide every
        // otherwise valid Rust/MDK group for the account. If that happens, Talk becomes
        // permanently empty after reinstall even though mlsListGroups still returns valid
        // Nostr groups. Do not expose internal MLS ids here; groupIdHex remains the Nostr
        // group id from FFI. Prune only stale local-hide tombstones for groups that still
        // have a peer member (or are non-DM groups) and only when the visible list would be
        // empty, preserving normal explicit leave/orphan hiding in non-empty states.
        if ffiGroups.isEmpty, !hidden.isEmpty {
            let validHiddenGroups = ffiGroupsAll.filter { g in
                hidden.contains(g.groupIdHex) && (!g.isDm || g.memberPubkeys.contains(where: { $0 != myPubkeyHex }))
            }
            if !validHiddenGroups.isEmpty {
                for g in validHiddenGroups { hidden.remove(g.groupIdHex) }
                prefs.hiddenMlsGroupIds = hidden
                ffiGroups = ffiGroupsAll.filter { g in
                    if g.isDm {
                        return g.memberPubkeys.contains(where: { $0 != myPubkeyHex })
                    }
                    return true
                }
                AppLogger.log("MLS", "fetchMlsGroups: pruned stale hidden tombstones=\(validHiddenGroups.count) after zero-visible guard")
            }
        }

        AppLogger.log("MLS", "fetchMlsGroups: ffi.mlsListGroups -> \(ffiGroups.count)/\(ffiGroupsAll.count) visible groups")

        // 3. メンバープロファイルエンリッチ
        let allPubkeys  = Array(Set(ffiGroups.flatMap { $0.memberPubkeys }))
        let profiles    = await fetchProfiles(pubkeys: allPubkeys)
        let profileMap  = Dictionary(uniqueKeysWithValues: profiles.map { ($0.pubkey, $0) })

        // 4. 各グループの最新メッセージを Rust SQLite から取得
        return ffiGroups.map { ffiG in
            let lastMsg = (try? ffi.mlsGetMessageHistory(groupIdHex: ffiG.groupIdHex, limit: 1))?.first
            return MlsGroup(
                groupIdHex:    ffiG.groupIdHex,
                name:          ffiG.name,
                description:   ffiG.description,
                adminPubkeys:  ffiG.adminPubkeys,
                memberPubkeys: ffiG.memberPubkeys,
                relays:        ffiG.relays,
                createdAt:     Int64(ffiG.createdAt),
                epoch:         Int64(ffiG.epoch),
                disappearingMessageSecs: ffiG.disappearingMessageSecs.map(Int64.init),
                isDm:          ffiG.isDm,
                memberProfiles: Dictionary(
                    uniqueKeysWithValues: ffiG.memberPubkeys.compactMap { pk in
                        profileMap[pk].map { (pk, $0) }
                    }
                ),
                lastMessage:     lastMsg?.content ?? "",
                lastMessageTime: lastMsg.map { Int64($0.timestamp) } ?? Int64(ffiG.createdAt)
            )
        }.sorted { $0.lastMessageTime > $1.lastMessageTime }
    }

    // MARK: - Message Fetch

    /// Return local MDK history only. Used by Talk UI to render immediately while
    /// relay catch-up continues in the background. Talk is Marmot MLS only; this
    /// does not read or display NIP-17/NIP-44 DM payloads.
    func getLocalMlsMessages(groupIdHex: String) async -> [MlsMessage] {
        guard let ffi = ensureMlsClient(),
              let groupInfo = try? ffi.mlsGetGroupInfo(groupIdHex: groupIdHex),
              let account = prefs.publicKeyHex,
              groupInfo.memberPubkeys.contains(where: { $0.caseInsensitiveCompare(account) == .orderedSame }) else {
            return []
        }
        let history = (try? ffi.mlsGetMessageHistory(groupIdHex: groupIdHex, limit: 300)) ?? []
        let cached = Array((mlsApplicationMessageCache[groupIdHex] ?? [:]).values)
        let visible = mergeHistoryAndLive(history: history, live: cached)
            .filter { !$0.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        let senderPubkeys = Array(Set(visible.map { $0.senderPubkey }))
        // Local-first rendering must not wait on metadata/profile relays.
        let profileMap: [String: UserProfile] = Dictionary(uniqueKeysWithValues: senderPubkeys.compactMap { pk in
            cache.getCachedProfile(pk).map { (pk, $0) }
        })
        return visible.map { msg in
            let sender = profileMap[msg.senderPubkey]
            return MlsMessage(
                id: stableMessageId(groupIdHex: groupIdHex, senderPubkey: msg.senderPubkey, timestamp: Int64(msg.timestamp), content: msg.content),
                senderPubkey: msg.senderPubkey,
                content: mlsDisplayContent(msg.content),
                timestamp: Int64(msg.timestamp),
                groupIdHex: groupIdHex,
                senderProfile: sender
            )
        }.sorted { lhs, rhs in
            if lhs.timestamp != rhs.timestamp { return lhs.timestamp < rhs.timestamp }
            return lhs.id < rhs.id
        }
    }

    /// グループのメッセージを取得する。
    ///
    /// Marmot / MDK 準拠方針:
    /// - kind:445 イベントを時系列で収集し、MDK に順次適用する
    /// - 独自の強制 recovery/quarantine でイベントを捨てない
    /// - retryable (state_not_ready など) は未処理のまま次回ポーリングへ回す
    /// - Rust SQLite を single source of truth として履歴を返す
    func fetchMlsMessages(groupIdHex: String, repairFull: Bool = false) async throws -> [MlsMessage] {
        guard let ffi = ensureMlsClient() else {
            AppLogger.log("MLS", "fetchMlsMessages: FFI unavailable for group=\(groupIdHex)")
            return []
        }

        // Applied-event handling:
        // - Successfully applied application messages are cached and merged into UI results.
        // - Already-applied events are skipped on later polls to avoid MDK/OpenMLS
        //   state_not_ready loops from replaying the same ciphertext forever.
        // - Retryable events use cooldown; they are retried only after the cooldown or
        //   after new state progress, not on every foreground polling tick.
        var processedIds = repairFull ? Set<String>() : (mlsAppliedEventIds[groupIdHex] ?? [])
        var liveDecrypted: [FfiDecryptedMessage] = []
        let cachedApplicationsAtStart = Array((mlsApplicationMessageCache[groupIdHex] ?? [:]).values)
        var retryCooldowns = mlsRetryableEventCooldownUntil[groupIdHex] ?? [:]
        let nowSec = Int64(Date().timeIntervalSince1970)

        // 旧独自の quarantine 状態はこの準拠版では使用しないためクリアしておく。
        mlsUnprocessableEventAttempts[groupIdHex] = [:]
        mlsUnprocessableSenderAttempts[groupIdHex] = [:]
        mlsUnprocessableEpochAttempts[groupIdHex] = [:]
        mlsUnprocessableStreak[groupIdHex] = 0

        guard let groupInfo = try? ffi.mlsGetGroupInfo(groupIdHex: groupIdHex),
              let account = prefs.publicKeyHex,
              groupInfo.memberPubkeys.contains(where: { $0.caseInsensitiveCompare(account) == .orderedSame }) else {
            AppLogger.log("MLS", "fetchMlsMessages: blocked stale cross-account group=\(groupIdHex) account=\(mlsLogPrefix(prefs.publicKeyHex ?? ""))")
            return []
        }

        let baselineHistory = (try? ffi.mlsGetMessageHistory(groupIdHex: groupIdHex, limit: 300)) ?? []
        let baselineLatestTs = baselineHistory.map { Int64($0.timestamp) }.max() ?? 0

        // Do not return stale local history only. Android/iOS interop can leave old
        // retryable diagnostics in the session while new peer kind:445 events are already
        // available on relays. Polling must still fetch recent relay events so Android ->
        // iOS messages appear.

        // Issue #178 #2: no merge_pending_commit on the receive path — only safe after a confirmed publish.

        // Foreground polling is incremental only. Explicit repairFull is the only
        // unbounded replay path, used when a DM has diverged and local history contains
        // only my messages. This keeps normal chat rendering fast while still allowing
        // recovery from the broken "initially OK then only local messages" state.
        // Keep a wider incremental window for cross-client MLS interop.
        // Android may publish through a relay whose delivery is delayed, and local MDK
        // history can advance with our own sent bubble before the peer event is fetched.
        // A 90s window caused iOS polling to miss Android kind:445 events after active
        // conversation sends; 10 minutes is still bounded but covers relay lag/retry.
        let safetyWindowSecs: Int64 = 600
        let sinceForFetch: Int64? = repairFull ? nil : (baselineLatestTs > 0 ? max(0, baselineLatestTs - safetyWindowSecs) : max(0, nowSec - 900))
        let isFullReplay = repairFull
        if repairFull {
            retryCooldowns.removeAll()
            mlsRetryableStateCount[groupIdHex] = 0
            AppLogger.log("MLS", "fetchMlsMessages: REPAIR full replay start history=\(baselineHistory.count) cached=\(cachedApplicationsAtStart.count) group=\(groupIdHex)")
        }
        mlsDidFullCatchUp.insert(groupIdHex)
        let groupIdCandidates = mlsGroupIdQueryCandidates(groupIdHex)
        let sortedEvents = await collectGroupMessageEvents(groupIdHex: groupIdHex, ffi: ffi, since: sinceForFetch)
        AppLogger.log("MLS", "fetchMlsMessages[MARMOT]: fetched kind445=\(sortedEvents.count) baseline=\(baselineHistory.count) baselineLatest=\(baselineLatestTs) full=\(isFullReplay) since=\(sinceForFetch.map(String.init) ?? "nil") group=\(groupIdHex)")

        var appliedCount = 0
        var stateOnlyCount = 0
        var droppedCount = 0
        var retryableIds = Set<String>()
        var retryLoggedIds = Set<String>()

        // Relay は順序保証がないため、retryable unprocessable は processedIds に入れず、
        // 同一 fetch 内でも state update/commit 適用後に未処理 queue を再走査する。
        // processedIds に入れるのは「適用済み」または「再試行しても無意味な drop」のみ。
        var shouldRescanAfterStateUpdate = true
        var pass = 0
        let maxPasses = repairFull ? max(1, min(sortedEvents.count + 1, 10)) : max(1, min(sortedEvents.count + 1, 2))

        while shouldRescanAfterStateUpdate && pass < maxPasses {
            pass += 1
            shouldRescanAfterStateUpdate = false
            var stateUpdateProcessedInPass = false

            for ev in sortedEvents {
                // Already applied in this process: skip MDK replay, but application
                // messages remain visible via mlsApplicationMessageCache.
                guard !processedIds.contains(ev.id) else { continue }
                if !repairFull, let until = retryCooldowns[ev.id], until > nowSec {
                    retryableIds.insert(ev.id)
                    continue
                }

                guard ev.kind == NostrKind.mlsGroupMessage else {
                    processedIds.insert(ev.id)
                    retryableIds.remove(ev.id)
                    droppedCount += 1
                    AppLogger.log("MLS", "fetchMlsMessages: drop invalid kind id=\(ev.id) kind=\(ev.kind) expected=\(NostrKind.mlsGroupMessage)")
                    continue
                }

                if let invalidReason = invalidMlsOuterPayloadReason(ev.content) {
                    processedIds.insert(ev.id)
                    retryableIds.remove(ev.id)
                    droppedCount += 1
                    AppLogger.log("MLS", "fetchMlsMessages: drop invalid kind445 payload id=\(ev.id) reason=\(invalidReason)")
                    continue
                }

                let evH = ev.getTagValue("h") ?? ""
                if !groupIdCandidates.contains(where: { $0.caseInsensitiveCompare(evH) == .orderedSame }) {
                    // Do not mark mismatched #h events as processed. Alias probes can
                    // discover additional WhiteNoise h values later in the same session.
                    droppedCount += 1
                    AppLogger.log("MLS", "fetchMlsMessages: ignore mismatched h-tag id=\(ev.id) h=\(evH) expectedAny=\(mlsGroupCandidateLog(groupIdCandidates)) group=\(groupIdHex)")
                    continue
                }
                if evH.caseInsensitiveCompare(groupIdHex) != .orderedSame {
                    AppLogger.log("MLS", "fetchMlsMessages: found legacy/alias h-tag id=\(ev.id) h=\(evH) localGroup=\(groupIdHex); processing uses local Nostr group id")
                }

                guard let eventJSON = encodeEventJSON(ev) else {
                    processedIds.insert(ev.id)
                    retryableIds.remove(ev.id)
                    droppedCount += 1
                    AppLogger.log("MLS", "fetchMlsMessages: drop unencodable event id=\(ev.id)")
                    continue
                }

                do {
                    let result = try ffi.mlsProcessMessageResult(groupIdHex: groupIdHex, eventJSON: eventJSON)
                    switch result {
                    case .application(let msg):
                        liveDecrypted.append(msg)
                        mlsApplicationMessageCache[groupIdHex, default: [:]][ev.id] = msg
                        processedIds.insert(ev.id)
                        retryCooldowns.removeValue(forKey: ev.id)
                        retryableIds.remove(ev.id)
                        appliedCount += 1
                        AppLogger.log("MLS", "fetchMlsMessages: application id=\(ev.id) sender=\(mlsLogPrefix(msg.senderPubkey)) len=\(msg.content.count)")

                    // Issue #178 #5: structured commit delta — no group-info re-query needed.
                    case .commit(let gid, let added, let removed, let epochAfter):
                        processedIds.insert(ev.id)
                        retryCooldowns.removeValue(forKey: ev.id)
                        retryableIds.remove(ev.id)
                        stateOnlyCount += 1
                        stateUpdateProcessedInPass = true
                        retryCooldowns = retryCooldowns.filter { $0.value <= nowSec }
                        AppLogger.log("MLS", "fetchMlsMessages: commit id=\(ev.id) group=\(gid) added=\(added.count) removed=\(removed.count) epoch=\(epochAfter)")

                    // Issue #178 #6: surface the pending-proposal signal; recovery commit runs on the publish path.
                    case .needsSelfUpdate(let gid, let reason):
                        processedIds.insert(ev.id)
                        retryCooldowns.removeValue(forKey: ev.id)
                        retryableIds.remove(ev.id)
                        stateOnlyCount += 1
                        stateUpdateProcessedInPass = true
                        AppLogger.log("MLS", "fetchMlsMessages: needsSelfUpdate id=\(ev.id) group=\(gid) reason=\(reason)")

                    case .stateUpdate(let kind):
                        // protocol-shape mismatch は再試行不要として処理済み化
                        if isNonRetryableMlsUnprocessable(kind) {
                            processedIds.insert(ev.id)
                            retryableIds.remove(ev.id)
                            droppedCount += 1
                            AppLogger.log("MLS", "fetchMlsMessages: drop non-retryable state-update kind=\(kind) id=\(ev.id)")
                            continue
                        }

                        // retryable unprocessable は processedIds に入れず、後続 state update 後または次回 fetch で再試行する。
                        if kind.hasPrefix("unhandled:Unprocessable") {
                            retryableIds.insert(ev.id)
                            retryCooldowns[ev.id] = nowSec + (repairFull ? 60 : 30)
                            if retryLoggedIds.insert(ev.id).inserted {
                                AppLogger.log("MLS", "fetchMlsMessages: retryable state-update kind=\(kind) id=\(ev.id) cooldown=\(repairFull ? 60 : 30)s repair=\(repairFull)")
                            }
                        } else {
                            processedIds.insert(ev.id)
                            retryCooldowns.removeValue(forKey: ev.id)
                            retryableIds.remove(ev.id)
                            stateOnlyCount += 1
                            stateUpdateProcessedInPass = true
                            // Issue #178 #2: no merge here — pending slot is for *our* commits, post-publish only.
                            // New state may unblock previously state_not_ready events.
                            retryCooldowns = retryCooldowns.filter { $0.value <= nowSec }
                            AppLogger.log("MLS", "fetchMlsMessages: state-only kind=\(kind) id=\(ev.id)")
                        }
                    }
                } catch {
                    // Issue #178 #7: never clear_pending_commit on a receive error — it tears
                    // down our own in-flight commits. Match Android: drop permanent / requeue retryable.
                    let permanent = isPermanentMlsProcessDropError(error)
                    if permanent {
                        processedIds.insert(ev.id)
                        retryCooldowns.removeValue(forKey: ev.id)
                        retryableIds.remove(ev.id)
                        droppedCount += 1
                        AppLogger.log("MLS", "fetchMlsMessages: process drop (permanent) id=\(ev.id) err=\(mlsRedactedError(error))")
                    } else {
                        retryableIds.insert(ev.id)
                        retryCooldowns[ev.id] = nowSec + (repairFull ? 60 : 30)
                        if retryLoggedIds.insert(ev.id).inserted {
                            AppLogger.log("MLS", "fetchMlsMessages: process failed (will retry) id=\(ev.id) cooldown=\(repairFull ? 60 : 30)s err=\(mlsRedactedError(error))")
                        }
                    }
                }
            }

            // Commit/proposal 等で state が進んだ場合、同じ relay fetch で既に見えている
            // retryable event を即時再試行する。進展がなければ次回 polling まで保持。
            shouldRescanAfterStateUpdate = stateUpdateProcessedInPass && sortedEvents.contains { !processedIds.contains($0.id) }
        }

        let retryableCount = retryableIds.count
        // Not every retryable replay should block outbound sends. Logs from the real
        // WhiteNoise interop failure showed this pattern:
        //   baseline history already contains the peer's application message,
        //   full replay then sees the same old wrapper event as state_not_ready,
        //   sendMlsMessage blocks forever even though the group can display peer history.
        // Treat retryables at or before the persisted history watermark as stale replay
        // diagnostics; only newer retryables indicate a real unresolved epoch gap.
        let historyWatermark = baselineLatestTs
        let retryableCreatedAtById = Dictionary(uniqueKeysWithValues: sortedEvents.map { ($0.id, $0.createdAt) })
        let blockingRetryableIds = retryableIds.filter { (retryableCreatedAtById[$0] ?? Int64.max) > historyWatermark }
        let blockingRetryableCount = blockingRetryableIds.count
        if retryableCount != blockingRetryableCount {
            AppLogger.log("MLS", "fetchMlsMessages: stale retryables ignored for send block stale=\(retryableCount - blockingRetryableCount) blocking=\(blockingRetryableCount) historyWatermark=\(historyWatermark) group=\(groupIdHex)")
        }
        AppLogger.log("MLS", "fetchMlsMessages: applied=\(appliedCount) stateOnly=\(stateOnlyCount) retryable=\(retryableCount) blockingRetryable=\(blockingRetryableCount) dropped=\(droppedCount) passes=\(pass)")

        if repairFull {
            // A repair pass intentionally replays from scratch. Preserve only successful
            // application/state results from this pass; stale skip caches are a common
            // source of unrecoverable one-sided histories.
            AppLogger.log("MLS", "fetchMlsMessages: REPAIR full replay done applied=\(appliedCount) stateOnly=\(stateOnlyCount) retryable=\(retryableCount) blockingRetryable=\(blockingRetryableCount) dropped=\(droppedCount)")
        }
        mlsAppliedEventIds[groupIdHex] = processedIds
        mlsProcessedIds[groupIdHex] = processedIds
        mlsRetryableEventCooldownUntil[groupIdHex] = retryCooldowns.filter { $0.value > nowSec }
        // Do not let old replay diagnostics permanently poison the send path. Both
        // Android and iOS can display persisted history while older wrapper events still
        // replay as state_not_ready; treat retryable state as telemetry only here.
        mlsRetryableStateCount[groupIdHex] = 0
        mlsDidFullCatchUp.insert(groupIdHex)

        // Issue #178 #2: merge_pending_commit lives on the publish-success path, not here.
        let history = (try? ffi.mlsGetMessageHistory(groupIdHex: groupIdHex, limit: 300)) ?? []
        AppLogger.log("MLS", "fetchMlsMessages: history count=\(history.count) group=\(groupIdHex)")

        let merged = mergeHistoryAndLive(history: history, live: cachedApplicationsAtStart + liveDecrypted)

        let visible = merged.filter { !$0.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        let droppedEmpty = merged.count - visible.count
        if droppedEmpty > 0 {
            AppLogger.log("MLS", "fetchMlsMessages: dropped empty-content messages=\(droppedEmpty)")
        }

        return mapFfiMessagesToMlsMessages(visible, groupIdHex: groupIdHex)
    }

    private func mapFfiMessagesToMlsMessages(_ messages: [FfiDecryptedMessage], groupIdHex: String) -> [MlsMessage] {
        let senderPubkeys = Array(Set(messages.map { $0.senderPubkey }))
        let profileMap: [String: UserProfile] = Dictionary(uniqueKeysWithValues: senderPubkeys.compactMap { pk in
            cache.getCachedProfile(pk).map { (pk, $0) }
        })
        return messages.map { msg in
            MlsMessage(
                id: stableMessageId(groupIdHex: groupIdHex, senderPubkey: msg.senderPubkey, timestamp: Int64(msg.timestamp), content: msg.content),
                senderPubkey: msg.senderPubkey,
                content: mlsDisplayContent(msg.content),
                timestamp: Int64(msg.timestamp),
                groupIdHex: groupIdHex,
                senderProfile: profileMap[msg.senderPubkey]
            )
        }.sorted { lhs, rhs in
            if lhs.timestamp != rhs.timestamp { return lhs.timestamp < rhs.timestamp }
            return lhs.id < rhs.id
        }
    }


    /// Explicit full repair for a diverged MLS group. This is intentionally separate
    /// from foreground polling so normal Talk UI stays fast. Use when local history has
    /// only my messages or the user taps repair.
    func repairMlsGroupHistory(groupIdHex: String) async -> [MlsMessage] {
        guard let ffi = ensureMlsClient(),
              let gi = try? ffi.mlsGetGroupInfo(groupIdHex: groupIdHex),
              isCurrentAccountMlsGroup(gi) else { return [] }
        mlsBrokenGroupIds.remove(groupIdHex)
        try? ffi.mlsClearPendingCommit(groupIdHex: groupIdHex)
        mlsAppliedEventIds[groupIdHex] = []
        mlsProcessedIds[groupIdHex] = []
        mlsRetryableEventCooldownUntil[groupIdHex] = [:]
        mlsRetryableStateCount[groupIdHex] = 0
        do {
            let repaired = try await fetchMlsMessages(groupIdHex: groupIdHex, repairFull: true)
            let account = prefs.publicKeyHex ?? ""
            let peerCount = repaired.filter { $0.senderPubkey.lowercased() != account.lowercased() }.count
            if gi.isDm && peerCount == 0 && repaired.count > 0 {
                AppLogger.log("MLS", "repairMlsGroupHistory no peer messages yet group=\(groupIdHex) total=\(repaired.count) nonTerminal=true")
            } else {
                mlsRepairFailureCount[groupIdHex] = 0
                mlsBrokenGroupIds.remove(groupIdHex)
            }
            return repaired
        } catch {
            let n = (mlsRepairFailureCount[groupIdHex] ?? 0) + 1
            mlsRepairFailureCount[groupIdHex] = n
            AppLogger.log("MLS", "repairMlsGroupHistory failed group=\(groupIdHex) attempts=\(n) err=\(mlsRedactedError(error)) nonTerminal=true")
            return await getLocalMlsMessages(groupIdHex: groupIdHex)
        }
    }


    func hasMlsStateGaps(groupIdHex: String) -> Bool {
        (mlsRetryableStateCount[groupIdHex] ?? 0) > 0
    }

    // MARK: - Send Message

    /// MLS グループにメッセージを送信する (Kind 445)。
    ///
    /// Marmot 準拠方針:
    /// - 送信前に pending commit を merge し、最新 state へ追従
    /// - mlsCreateMessage 失敗時は独自 recovery commit を発行せずエラーを返す
    /// - raw kind:445 は MDK 署名済み payload をそのまま publish
    @discardableResult
    func sendMlsMessage(groupIdHex: String, content: String, myPubkeyHex: String) async throws -> MlsMessage {
        guard let ffi = ensureMlsClient() else {
            AppLogger.log("MLS", "sendMlsMessage: FFI unavailable for group=\(groupIdHex)")
            throw MlsError.noFfiClient
        }

        let groupInfo = try? ffi.mlsGetGroupInfo(groupIdHex: groupIdHex)
        guard groupInfo.map({ isCurrentAccountMlsGroup($0) }) ?? true else {
            throw MlsError.groupNotFound
        }
        let groupRelays = groupInfo?.relays ?? []

        // WhiteNoise Android interop recovery: restore the pre-speedup slow path.
        // Resolving member inbox relays before every send is slower, but it guarantees
        // that the already-signed MDK kind:445 JSON is published to the relays where
        // WhiteNoise is actually reading this MLS group. The fast cached-only path caused
        // iOS messages to be accepted locally but not appear on WhiteNoise Android.
        let inboxRelays = await resolveInboxRelaysForMembers(groupInfo?.memberPubkeys ?? [])
        let publishRelays = mlsRelayUrls(groupRelays + inboxRelays)

        if !publishRelays.isEmpty {
            await client.connect(relayUrls: publishRelays)
        }

        // Do NOT merge a local pending commit here. If a self-update/recovery commit
        // has not been observed by WhiteNoise yet, merging it before an application
        // message advances iOS to an epoch the peer cannot decrypt. The ViewModel now
        // performs an explicit relay catch-up before send; mlsCreateMessage is the
        // only source of truth for whether the post-catch-up state can send.

        // Stale retryable/self-update diagnostics must not hard-block user sends.
        // Let MDK/OpenMLS be the authority: mlsCreateMessage() will throw if the local
        // state truly cannot create an application message. Hard-blocking here caused
        // iOS to be unable to send even while Android history was visible.
        if (mlsRetryableStateCount[groupIdHex] ?? 0) > 0 {
            AppLogger.log("MLS", "sendMlsMessage[MARMOT]: continuing despite retryable state_not_ready group=\(groupIdHex) count=\(mlsRetryableStateCount[groupIdHex] ?? 0)")
        }
        if mlsSelfUpdatePublishedThisSession.contains(groupIdHex) {
            AppLogger.log("MLS", "sendMlsMessage[MARMOT]: continuing after local self-update publish group=\(groupIdHex)")
        }

        let data: FfiEncryptedMessageData
        do {
            data = try ffi.mlsCreateMessage(groupIdHex: groupIdHex, content: content)
        } catch {
            let raw = String(describing: error).lowercased()
            if raw.contains("pending proposal exists") || raw.contains("pending commit exists") {
                // Do not auto-clear pending commits/proposals here. WhiteNoise/MDK
                // interop is stateful; clearing local pending state can make iOS create
                // an application message from an epoch Android does not have. Surface
                // the stuck state and let normal receive/repair paths reconcile first.
                throw MlsError.groupStateStuck
            }
            throw error
        }

        let outgoingEventId = extractEventId(from: data.content) ?? ""
        let outgoingHTag = extractTagValue(from: data.content, tag: "h") ?? ""
        let outgoingPubkey = extractPubkey(from: data.content) ?? ""
        AppLogger.log("MLS", "sendMlsMessage[MARMOT]: publish kind445 event=\(outgoingEventId) h=\(outgoingHTag) requestedGroup=\(groupIdHex) eph=\(mlsLogPrefix(outgoingPubkey)) relays=\(publishRelays.count)")
        do {
            try await publishMlsKind445(data: data, groupRelays: publishRelays)
        } catch {
            if let eid = extractEventId(from: data.content), let account = prefs.publicKeyHex {
                mlsRetryStore.enqueueMessage(
                    accountPubkey: account,
                    groupIdHex: groupIdHex,
                    eventId: eid,
                    relayUrls: publishRelays,
                    signedEventJSON: data.content,
                    lastErrorKind: classifyMlsRetryError(error)
                )
                AppLogger.log("MLS", "sendMlsMessage: queued message retry event=\(eid) group=\(groupIdHex) relays=\(publishRelays.count)")
            }
            throw error
        }

        let sentEventId = extractEventId(from: data.content) ?? UUID().uuidString
        // MDK create_message() already persists the inner unsigned kind:9 message into
        // local history before returning the signed outer kind:445 event JSON. Do not
        // add a second app-side live cache entry here: it produces the duplicate iOS
        // bubbles seen in Talk and also hides whether the real Marmot history path is
        // working. Use MDK history as the single source of truth for our sent bubble.
        mlsAppliedEventIds[groupIdHex, default: []].insert(sentEventId)
        mlsProcessedIds[groupIdHex, default: []].insert(sentEventId)

        let history = (try? ffi.mlsGetMessageHistory(groupIdHex: groupIdHex, limit: 20)) ?? []
        let sentFromHistory = history
            .filter { $0.senderPubkey.caseInsensitiveCompare(myPubkeyHex) == .orderedSame && $0.content == content }
            .max { lhs, rhs in lhs.timestamp < rhs.timestamp }
        let sentTimestamp = Int64(sentFromHistory?.timestamp ?? UInt64(Date().timeIntervalSince1970))

        return MlsMessage(
            id: stableMessageId(groupIdHex: groupIdHex, senderPubkey: myPubkeyHex, timestamp: sentTimestamp, content: content),
            senderPubkey: myPubkeyHex,
            content: content,
            timestamp: sentTimestamp,
            groupIdHex: groupIdHex
        )
    }

    // MARK: - Group Creation

    /// 1:1 DM グループを作成。
    func createMlsDmConversation(partnerPubkeyHex: String, myPubkeyHex: String) async throws -> MlsGroup {
        guard let ffi = ensureMlsClient() else {
            return makeFallbackDmGroup(partner: partnerPubkeyHex, me: myPubkeyHex)
        }
        await ensureKeyPackagePublished(ffi: ffi, myPubkeyHex: myPubkeyHex)

        let seedRelays = Array(prefs.selectedRelays.prefix(3))

        // 先に相手 KeyPackage を確認（失敗時に自己だけDMを作らない）
        guard let kpEvent = await fetchLatestKeyPackage(pubkey: partnerPubkeyHex, ffi: ffi) else {
            AppLogger.log("MLS", "createDm: key package not found partner=\(mlsLogPrefix(partnerPubkeyHex))")
            throw MlsError.keyPackageNotFound
        }
        // Safety: Welcome 1059 must be addressed to the KeyPackage event owner.
        // Never add a member with a KeyPackage whose event pubkey differs from the requested partner.
        guard extractPubkey(from: kpEvent)?.lowercased() == partnerPubkeyHex.lowercased() else {
            AppLogger.log("MLS", "createDm: key package owner mismatch requested=\(mlsLogPrefix(partnerPubkeyHex)) owner=\(mlsLogPrefix(extractPubkey(from: kpEvent) ?? ""))")
            throw MlsError.keyPackageNotFound
        }

        // KeyPackage が取得できた場合のみグループ作成
        let ffiGroup = try ffi.mlsCreateGroup(name: "", adminPubkeys: [myPubkeyHex], relays: seedRelays)

        // MUST: never mutate peer-signed KeyPackage event JSON.
        let keyPackageEventJSON = kpEvent
        do {
            let result = try ffi.mlsAddMember(groupIdHex: ffiGroup.groupIdHex, keyPackageEventJSON: keyPackageEventJSON)
            guard result.welcomeEventData.recipientPubkey.lowercased() == partnerPubkeyHex.lowercased() else {
                AppLogger.log("MLS", "createDm: welcome recipient mismatch partner=\(mlsLogPrefix(partnerPubkeyHex)) recipient=\(mlsLogPrefix(result.welcomeEventData.recipientPubkey))")
                throw MlsError.keyPackageNotFound
            }
            AppLogger.log("MLS", "createDm: mlsAddMember success group=\(ffiGroup.groupIdHex) partner=\(mlsLogPrefix(partnerPubkeyHex))")

            // WhiteNoise 互換: 相手 inbox/kp relay + global fallback まで publish 範囲を拡張。
            let inboxRelays = await resolveInboxRelaysForMembers([partnerPubkeyHex])
            let publishRelays = mlsRelayUrls(ffiGroup.relays + seedRelays + inboxRelays)

            try await publishMlsKind445(data: result.commitEventData, groupRelays: publishRelays)
            AppLogger.log("MLS", "createDm: commit publish success group=\(ffiGroup.groupIdHex)")
            // MIP-02 timing (fork prevention): Welcome MUST be sent only after Commit relay ACK.
            try await publishMlsWelcome(data: result.welcomeEventData, groupRelays: publishRelays)
            AppLogger.log("MLS", "createDm: welcome publish success group=\(ffiGroup.groupIdHex)")
            recordConsumedKeyPackageEventId(fromEventJSON: keyPackageEventJSON)
            try? ffi.mlsMergePendingCommit(groupIdHex: ffiGroup.groupIdHex)
        } catch {
            // addMember/publish失敗時の orphan DM を残さない
            hideMlsGroupLocally(groupIdHex: ffiGroup.groupIdHex)
            AppLogger.log("MLS", "createDm: addMember/publish failed -> hide orphan group=\(ffiGroup.groupIdHex) err=\(mlsRedactedError(error))")
            throw error
        }

        let resolved = (try? enrichFfiGroup(ffiGroup, ffi: ffi)) ?? bridgeFfiGroup(ffiGroup)

        // Safety guard: DM must include partner member after addMember/welcome flow.
        // If not, treat as creation failure and hide the orphan group locally.
        if !resolved.memberPubkeys.contains(partnerPubkeyHex) {
            hideMlsGroupLocally(groupIdHex: resolved.groupIdHex)
            AppLogger.log("MLS", "createDm: invalid orphan DM detected (partner missing) group=\(resolved.groupIdHex) partner=\(mlsLogPrefix(partnerPubkeyHex))")
            throw MlsError.groupStateStuck
        }

        return resolved
    }

    /// 名前付きグループチャットを作成。
    func createMlsGroupChat(name: String, memberPubkeys: [String], myPubkeyHex: String) async throws -> MlsGroup {
        guard let ffi = ensureMlsClient() else {
            return makeFallbackGroupChat(name: name, members: memberPubkeys, me: myPubkeyHex)
        }
        await ensureKeyPackagePublished(ffi: ffi, myPubkeyHex: myPubkeyHex)

        let seedRelays = Array(prefs.selectedRelays.prefix(3))
        let ffiGroup = try ffi.mlsCreateGroup(name: name, adminPubkeys: [myPubkeyHex], relays: seedRelays)

        let inboxRelays = await resolveInboxRelaysForMembers(memberPubkeys)
        let publishRelays = mlsRelayUrls(ffiGroup.relays + seedRelays + inboxRelays)

        let kpEvents = await fetchKeyPackages(pubkeys: memberPubkeys, ffi: ffi)
        for kpEvent in kpEvents {
            guard let owner = extractPubkey(from: kpEvent), memberPubkeys.contains(where: { $0.lowercased() == owner.lowercased() }) else {
                AppLogger.log("MLS", "createGroup: skip key package owner mismatch owner=\(mlsLogPrefix(extractPubkey(from: kpEvent) ?? ""))")
                continue
            }
            // MUST: never mutate peer-signed KeyPackage event JSON.
            let keyPackageEventJSON = kpEvent
            do {
                let result = try ffi.mlsAddMember(groupIdHex: ffiGroup.groupIdHex, keyPackageEventJSON: keyPackageEventJSON)
                guard result.welcomeEventData.recipientPubkey.lowercased() == owner.lowercased() else {
                    AppLogger.log("MLS", "createGroup: welcome recipient mismatch owner=\(mlsLogPrefix(owner)) recipient=\(mlsLogPrefix(result.welcomeEventData.recipientPubkey))")
                    throw MlsError.keyPackageNotFound
                }
                try await publishMlsKind445(data: result.commitEventData, groupRelays: publishRelays)
                // MIP-02 timing (fork prevention): Welcome MUST be sent only after Commit relay ACK.
                try await publishMlsWelcome(data: result.welcomeEventData, groupRelays: publishRelays)
                recordConsumedKeyPackageEventId(fromEventJSON: keyPackageEventJSON)
                try? ffi.mlsMergePendingCommit(groupIdHex: ffiGroup.groupIdHex)
            } catch {
                AppLogger.log("MLS", "createGroup: addMember failed (continue next): \(mlsRedactedError(error))")
            }
        }
        return (try? enrichFfiGroup(ffiGroup, ffi: ffi)) ?? bridgeFfiGroup(ffiGroup)
    }

    // MARK: - Member Management

    func addMemberToGroup(groupIdHex: String, memberPubkey: String) async throws {
        guard let ffi = ensureMlsClient() else { return }
        let seedRelays = Array(prefs.selectedRelays.prefix(3))
        let groupRelays = (try? ffi.mlsGetGroupInfo(groupIdHex: groupIdHex))?.relays ?? seedRelays
        let inboxRelays = await resolveInboxRelaysForMembers([memberPubkey])
        let publishRelays = mlsRelayUrls(groupRelays + seedRelays + inboxRelays)

        guard let kpEvent = await fetchLatestKeyPackage(pubkey: memberPubkey, ffi: ffi) else {
            throw MlsError.keyPackageNotFound
        }
        // Safety: Welcome 1059 recipient is the KeyPackage event owner.
        guard extractPubkey(from: kpEvent)?.lowercased() == memberPubkey.lowercased() else {
            AppLogger.log("MLS", "addMemberToGroup: key package owner mismatch requested=\(mlsLogPrefix(memberPubkey)) owner=\(mlsLogPrefix(extractPubkey(from: kpEvent) ?? ""))")
            throw MlsError.keyPackageNotFound
        }
        // MUST: never mutate peer-signed KeyPackage event JSON.
        let keyPackageEventJSON = kpEvent
        let addResult = try ffi.mlsAddMember(groupIdHex: groupIdHex, keyPackageEventJSON: keyPackageEventJSON)
        guard addResult.welcomeEventData.recipientPubkey.lowercased() == memberPubkey.lowercased() else {
            AppLogger.log("MLS", "addMemberToGroup: welcome recipient mismatch requested=\(mlsLogPrefix(memberPubkey)) recipient=\(mlsLogPrefix(addResult.welcomeEventData.recipientPubkey))")
            throw MlsError.keyPackageNotFound
        }
        try await publishMlsKind445(data: addResult.commitEventData, groupRelays: publishRelays)
        // MIP-02 timing (fork prevention): Welcome MUST be sent only after Commit relay ACK.
        try await publishMlsWelcome(data: addResult.welcomeEventData, groupRelays: publishRelays)
        recordConsumedKeyPackageEventId(fromEventJSON: keyPackageEventJSON)
        try? ffi.mlsMergePendingCommit(groupIdHex: groupIdHex)
    }

    func removeMemberFromGroup(groupIdHex: String, memberPubkey: String) async throws {
        guard let ffi = ensureMlsClient() else { return }
        let groupRelays = (try? ffi.mlsGetGroupInfo(groupIdHex: groupIdHex))?.relays ?? []
        let ffiMsg = try ffi.mlsRemoveMember(groupIdHex: groupIdHex, memberPubkeyHex: memberPubkey)
        try await publishMlsKind445(data: ffiMsg, groupRelays: groupRelays)
        try? ffi.mlsMergePendingCommit(groupIdHex: groupIdHex)
    }

    /// ローカルでグループを非表示化する（再起動後も維持）。
    /// leave publish の成否に関係なく UI 復活を防ぐために使用。
    func hideMlsGroupLocally(groupIdHex: String) {
        mlsProcessedIds.removeValue(forKey: groupIdHex)
        var hidden = prefs.hiddenMlsGroupIds
        hidden.insert(groupIdHex)
        prefs.hiddenMlsGroupIds = hidden
    }

    func leaveMlsGroup(groupIdHex: String) async throws {
        // 退出publishが失敗しても、再起動時に復活しないよう先にローカル非表示を確定。
        hideMlsGroupLocally(groupIdHex: groupIdHex)

        guard let ffi = ensureMlsClient() else {
            AppLogger.log("MLS", "leaveMlsGroup: FFI unavailable, local-hide only group=\(groupIdHex)")
            return
        }

        let groupRelays = (try? ffi.mlsGetGroupInfo(groupIdHex: groupIdHex))?.relays ?? []
        try? ffi.mlsMergePendingCommit(groupIdHex: groupIdHex)
        let data = try ffi.mlsLeaveGroup(groupIdHex: groupIdHex)
        do {
            try await publishMlsKind445(data: data, groupRelays: groupRelays)
        } catch {
            // 退出イベント配信失敗は非fatal（ローカルでは退出状態を維持）。
            AppLogger.log("MLS", "leaveMlsGroup: publish failed (non-fatal) group=\(groupIdHex) err=\(mlsRedactedError(error))")
        }
    }

    // MARK: - Key Package

    func forceRepublishMyKeyPackageIfNeeded(myPubkeyHex: String) async {
        guard let ffi = ensureMlsClient() else { return }
        do {
            try await publishKeyPackage(ffi: ffi)
            AppLogger.log("MLS", "forceRepublishMyKeyPackageIfNeeded: republished key package + relay lists")
        } catch {
            AppLogger.log("MLS", "forceRepublishMyKeyPackageIfNeeded: republish failed: \(mlsRedactedError(error))")
        }
    }

    func ensureKeyPackagePublished(ffi: MlsFFIBridge, myPubkeyHex: String) async {
        guard !keyPackagePublished else { return }
        let f = NostrFilter(
            ids: nil,
            authors: [myPubkeyHex],
            kinds: [NostrKind.mlsKeyPackage, NostrKind.mlsKeyPackageLegacy],
            since: nil,
            until: nil,
            limit: 1,
            tags: nil,
            search: nil
        )
        let events = await fetchEvents(filters: [f], timeoutSeconds: 3.0)
        let nonConsumed = events.filter { !isConsumedKeyPackageEventId($0.id) }

        // Do not trust an arbitrary relay-visible KeyPackage as "ours" unless this
        // installation also has the matching local MDK init-key material. After
        // reinstall/DB reset, stale public KeyPackages can still be fetched from
        // relays; Welcomes created from those fail as missing_or_stale_key_package.
        // Publish a fresh local KeyPackage instead so WhiteNoise can invite this
        // device using key material that MDK can actually consume.
        if let json = selectBestKeyPackageEventsByAuthor(events: nonConsumed.compactMap { encodeEventJSON($0) })[myPubkeyHex],
           let eventId = extractEventId(from: json) {
            let hasRelays = extractTagValue(from: json, tag: "relays") != nil
            let hasLocalMaterialRef = prefs.mlsKeyPackageEventJsonById[eventId] != nil || prefs.mlsKeyPackageHashRefById[eventId] != nil
            if hasRelays && hasLocalMaterialRef {
                // The locally backed KeyPackage is valid for MDK, but it may have
                // been published by an older build using first-OK generic relay
                // fanout. Republish once per session through the new targeted
                // WhiteNoise discovery path so 30443/443/10051/10050 are present
                // on WhiteNoise's Key Package Relays.
                AppLogger.log("MLS", "ensureKeyPackagePublished: existing local-backed key package id=\(eventId); republishing discovery relays")
            } else {
                AppLogger.log("MLS", "ensureKeyPackagePublished: ignoring stale relay key package id=\(eventId) hasRelays=\(hasRelays) localRef=\(hasLocalMaterialRef)")
            }
        } else {
            AppLogger.log("MLS", "ensureKeyPackagePublished: no usable relay key package; publishing fresh")
        }
        do {
            try await publishKeyPackage(ffi: ffi)
        } catch {
            AppLogger.log("MLS", "ensureKeyPackagePublished: publish failed err=\(mlsRedactedError(error))")
        }
    }

    private func mlsStableKeyPackageDTag(fallback: String = "") -> String {
        if let existing = prefs.mlsKeyPackageStableDTag, !existing.isEmpty { return existing }
        let generated = fallback.isEmpty ? UUID().uuidString.lowercased() : fallback
        prefs.mlsKeyPackageStableDTag = generated
        return generated
    }

    private func applyStableKeyPackageDTag(_ tags: [[String]], fallback: String = "") -> [[String]] {
        let dTag = mlsStableKeyPackageDTag(fallback: fallback)
        var out = tags.filter { $0.first != "d" }
        out.insert(["d", dTag], at: 0)
        return out
    }

    private func publishKeyPackage(ffi: MlsFFIBridge) async throws {
        let kpData = try ffi.mlsCreateKeyPackage()
        // Gossip model: publish KeyPackages to our advertised KeyPackage relays,
        // spread relay-list advertisements broadly, and advertise inbox relays
        // separately via kind:10050. This avoids requiring fixed relay overlap
        // with WhiteNoise while keeping relay lists user-configurable.
        let keyPackageRelays = myMlsKeyPackageRelays()
        let inboxRelays = myMlsInboxRelays()
        let discoveryRelays = mlsDiscoveryRelayUrls(keyPackageRelays + inboxRelays)
        await client.connect(relayUrls: discoveryRelays)

        var tags30443 = applyStableKeyPackageDTag(kpData.tags, fallback: kpData.dTag).map { t -> [String] in
            t.first == "relays" ? ["relays"] + keyPackageRelays : t
        }
        if !tags30443.contains(where: { $0.first == "relays" }) {
            tags30443.append(["relays"] + keyPackageRelays)
        }

        let signed30443 = try signer.signEvent(kind: Int(kpData.kind), tags: tags30443, content: kpData.content)
        try await publishSignedMlsDiscoveryEvent(signed30443, to: keyPackageRelays, context: "keypackage30443")
        persistPublishedKeyPackageEvent(id: signed30443.id, json: encodeEventJSON(signed30443), hashRef: kpData.hashRef)

        // Issue #178 #3: kind:443 publish removed (read path still accepts it for migration).

        // Marmot MIP-00 / Kind 10051 — publish preferred KeyPackage relays.
        let kpRelayTags = keyPackageRelays.map { ["relay", $0] }
        let signed10051 = try signer.signEvent(kind: NostrKind.mlsKeyPackageRelays, tags: kpRelayTags, content: "")
        try await publishSignedMlsDiscoveryEvent(signed10051, to: discoveryRelays, context: "keypackageRelays10051")

        // NIP-17 kind:10050 MUST use ["relay", url] tags. These are the inbox relays
        // where peers should send MLS Welcome gift-wraps and private DM wrappers.
        let dmRelayTags = inboxRelays.map { ["relay", $0] }
        let signed10050 = try signer.signEvent(kind: NostrKind.dmRelayList, tags: dmRelayTags, content: "")
        try await publishSignedMlsDiscoveryEvent(signed10050, to: discoveryRelays, context: "inboxRelays10050")

        AppLogger.log("MLS", "publishKeyPackage: published kind=\(kpData.kind) keyPackageRelays=\(keyPackageRelays.count) inboxRelays=\(inboxRelays.count) discoveryRelays=\(discoveryRelays.count) kp=\(keyPackageRelays.joined(separator: ",")) inbox=\(inboxRelays.joined(separator: ","))")
        keyPackagePublished = true
        Task { await self.cleanupSupersededOwnKeyPackages(keepIds: [signed30443.id]) }
    }

    private func publishSignedMlsDiscoveryEvent(_ event: NostrEvent, to relays: [String], context: String) async throws {
        guard let json = encodeEventJSON(event) else { throw MlsError.invalidPayload }
        try await client.publishRawEventJSON(json, to: relays)
        AppLogger.log("MLS", "publishMlsDiscovery: \(context) id=\(event.id) relays=\(relays.count)")
    }


    private func persistPublishedKeyPackageEvent(id: String, json: String?, hashRef: [UInt8]) {
        if let json {
            var map = prefs.mlsKeyPackageEventJsonById
            map[id] = json
            prefs.mlsKeyPackageEventJsonById = map
        }
        if !hashRef.isEmpty {
            var hashMap = prefs.mlsKeyPackageHashRefById
            hashMap[id] = hashRef
            prefs.mlsKeyPackageHashRefById = hashMap
        }
    }

    private func cleanupSupersededOwnKeyPackages(keepIds: Set<String>) async {
        guard let myPubkey = prefs.publicKeyHex else { return }
        let relays = mlsDiscoveryRelayUrls(myMlsKeyPackageRelays() + myMlsInboxRelays())
        let f = NostrFilter(ids: nil, authors: [myPubkey], kinds: [NostrKind.mlsKeyPackage, NostrKind.mlsKeyPackageLegacy], since: nil, until: nil, limit: 80, tags: nil, search: nil)
        var merged: [String: NostrEvent] = [:]
        for relay in relays.prefix(12) {
            for ev in await client.fetchEventsFromRelay(relay, filters: [f], timeoutSeconds: 3.0) { merged[ev.id] = ev }
        }
        let stableD = mlsStableKeyPackageDTag()
        var deleted = 0
        for ev in merged.values {
            guard !keepIds.contains(ev.id), !isConsumedKeyPackageEventId(ev.id) else { continue }
            let superseded = ev.kind == NostrKind.mlsKeyPackage && ev.getTagValue("d") != stableD
            let legacy = ev.kind == NostrKind.mlsKeyPackageLegacy
            if superseded || legacy {
                await publishConsumedKeyPackageDeleteBestEffort(eventId: ev.id)
                recordConsumedKeyPackageEventId(ev.id)
                deleted += 1
            }
        }
        if deleted > 0 { AppLogger.log("MLS", "cleanupSupersededOwnKeyPackages: deleted=\(deleted)") }
    }

    // MARK: - Publish Helpers

    /// Kind-445 (MLS message/commit/proposal) を publish。
    /// data.content は MDK がエフェメラル鍵で署名済みの完全なイベントJSON。
    /// groupRelays を指定するとグループのリレーにも publish する（WhiteNoise 互換）。
    private func publishMlsKind445(data: FfiEncryptedMessageData, groupRelays: [String] = []) async throws {
        try await publishMlsRawEventJSON(data.content, groupRelays: groupRelays)
    }

    private func publishMlsRawEventJSON(_ rawEventJSON: String, groupRelays: [String] = []) async throws {
        guard let eventData = rawEventJSON.data(using: .utf8),
              let eventObj = (try? JSONSerialization.jsonObject(with: eventData)) as? [String: Any] else {
            throw MlsError.invalidPayload
        }

        // Validate minimum outer shape for kind:445 before publish.
        // NOTE: rawEventJSON is already signed by MDK. Do NOT mutate tags/content here.
        if (eventObj["kind"] as? Int) == NostrKind.mlsGroupMessage {
            guard let tagsAny = eventObj["tags"] as? [[Any]],
                  tagsAny.contains(where: { ($0.first as? String) == "h" && $0.count >= 2 && (($0[1] as? String)?.isEmpty == false) }) else {
                throw MlsError.invalidPayload
            }
        }

        // グループリレー + 選択リレー + WhiteNoise interop relay の統合セット。
        // Welcome 1059 は recipient の inbox relay に届く必要があるため、auth.nostr1.com も除外しない。
        //
        // Reliability note: MLS/Marmot group messages are stateful. Always publish the
        // already-signed MDK raw event JSON as-is to the resolved WhiteNoise relay set.
        // Do not rewrite tags/content/signature here; WhiteNoise Android expects this
        // exact Marmot JSON shape (outer kind:445 with #h, inner decrypted kind:9).
        let allRelays = mlsRelayUrls(groupRelays)
        if !groupRelays.isEmpty {
            await client.connect(relayUrls: groupRelays)
        }
        AppLogger.log("MLS", "publishMlsRawEventJSON: targetRelays=\(allRelays.count) reliableFanout=true rawJson=as_mdk_signed")
        try await client.publishRawEventJSON(rawEventJSON, to: allRelays, waitForAllRelays: true)
    }

    /// Marmot MIP-02: Welcome publish.
    ///
    /// WhiteNoise / Marmot interop expects the Welcome rumor (kind 444) to be
    /// delivered as a NIP-59 gift-wrap (kind 1059). The inner rumor itself is not
    /// a publishable signed event, so we use giftWrappedEventJson as the primary
    /// publish path and skip raw kind-444 rumor publishing.
    private func publishMlsWelcome(data: FfiWelcomeEventData, groupRelays: [String] = []) async throws {
        if !data.giftWrappedEventJson.isEmpty {
            do {
                try await publishMlsRawEventJSON(data.giftWrappedEventJson, groupRelays: groupRelays)
                AppLogger.log("MLS", "publishMlsWelcome: kind1059 gift-wrap publish ok recipient=\(mlsLogPrefix(data.recipientPubkey))")
            } catch {
                AppLogger.log("MLS", "publishMlsWelcome: kind1059 gift-wrap publish failed: \(mlsRedactedError(error))")
                throw error
            }

            // Interop safety: also publish legacy kind:444 only when inner rumor content is base64 welcome payload.
            // Avoid publishing JSON-looking rumors as kind:444 content (peers reject with invalid base64).
            if !data.content.isEmpty, Data(base64Encoded: data.content) != nil {
                do {
                    var tags = data.tags
                    if !tags.contains(where: { $0.first == "p" && $0.count >= 2 && $0[1] == data.recipientPubkey }) {
                        tags.append(["p", data.recipientPubkey])
                    }
                    try await publishEvent(kind: NostrKind.mlsWelcomeInner, tags: tags, content: data.content)
                    AppLogger.log("MLS", "publishMlsWelcome: legacy kind444 publish ok recipient=\(mlsLogPrefix(data.recipientPubkey))")
                } catch {
                    AppLogger.log("MLS", "publishMlsWelcome: legacy kind444 publish failed (non-fatal): \(mlsRedactedError(error))")
                }
            }
            return
        }

        if !data.content.isEmpty, Data(base64Encoded: data.content) != nil {
            // gift-wrap missing fallback
            var tags = data.tags
            if !tags.contains(where: { $0.first == "p" && $0.count >= 2 && $0[1] == data.recipientPubkey }) {
                tags.append(["p", data.recipientPubkey])
            }
            try await publishEvent(kind: NostrKind.mlsWelcomeInner, tags: tags, content: data.content)
            AppLogger.log("MLS", "publishMlsWelcome: fallback legacy kind444 publish recipient=\(mlsLogPrefix(data.recipientPubkey))")
        } else if !data.content.isEmpty {
            AppLogger.log("MLS", "publishMlsWelcome: skipped non-base64 legacy kind444 publish recipient=\(mlsLogPrefix(data.recipientPubkey))")
        }
    }

    // MARK: - Key Package Helpers

    private func isConsumedKeyPackageEventId(_ eventId: String) -> Bool {
        prefs.mlsConsumedKeyPackageEventIds.contains(eventId)
    }

    private func recordConsumedKeyPackageEventId(_ eventId: String) {
        guard !eventId.isEmpty else { return }
        var consumed = prefs.mlsConsumedKeyPackageEventIds
        consumed.insert(eventId)
        prefs.mlsConsumedKeyPackageEventIds = consumed
    }

    private func recordConsumedKeyPackageEventId(fromEventJSON eventJSON: String) {
        guard let data = eventJSON.data(using: .utf8),
              let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let eventId = obj["id"] as? String,
              !eventId.isEmpty else { return }
        recordConsumedKeyPackageEventId(eventId)
    }

    private func publishConsumedKeyPackageDeleteBestEffort(eventId: String) async {
        guard !eventId.isEmpty else { return }
        do {
            try await publishDelete(eventId: eventId)
            AppLogger.log("MLS", "KeyPackage consumed delete published id=\(eventId)")
        } catch {
            AppLogger.log("MLS", "KeyPackage consumed delete failed (best-effort) id=\(eventId) err=\(mlsRedactedError(error))")
        }
    }

    private func fetchLatestKeyPackage(pubkey: String, ffi: MlsFFIBridge) async -> String? {
        let f = NostrFilter(
            ids: nil,
            authors: [pubkey],
            kinds: [NostrKind.mlsKeyPackage, NostrKind.mlsKeyPackageLegacy],
            since: nil,
            until: nil,
            limit: 4,
            tags: nil,
            search: nil
        )

        let preferredRelays = await resolveKeyPackageRelaysForMembers([pubkey])[pubkey] ?? []
        if !preferredRelays.isEmpty {
            await client.connect(relayUrls: preferredRelays)
            var merged: [String: NostrEvent] = [:]
            var perRelayHits: [String: Int] = [:]
            for relay in preferredRelays.prefix(12) {
                let events = await client.fetchEventsFromRelay(relay, filters: [f], timeoutSeconds: 4.0)
                perRelayHits[relay] = events.count
                for ev in events { merged[ev.id] = ev }
            }
            let evs = merged.values
                .filter { !isConsumedKeyPackageEventId($0.id) }
                .compactMap { encodeEventJSON($0) }
            if let best = selectBestKeyPackageEventsByAuthor(events: evs)[pubkey] {
                AppLogger.log("MLS", "fetchLatestKeyPackage: author=\(mlsLogPrefix(pubkey)) via10051 relays=\(preferredRelays.count) totalEvents=\(evs.count) hits=\(perRelayHits.values.reduce(0, +)) found=yes")
                return best
            }
            AppLogger.log("MLS", "fetchLatestKeyPackage: author=\(mlsLogPrefix(pubkey)) via10051 relays=\(preferredRelays.count) totalEvents=\(evs.count) hits=\(perRelayHits.values.reduce(0, +)) found=no")
        }

        // Discovery fallback: include WhiteNoise relay set + global ecosystem relays.
        let discoveryRelays = mlsDiscoveryRelayUrls()
        await client.connect(relayUrls: discoveryRelays)

        var mergedFallback: [String: NostrEvent] = [:]
        for relay in discoveryRelays {
            let events = await client.fetchEventsFromRelay(relay, filters: [f], timeoutSeconds: 4.0)
            for ev in events { mergedFallback[ev.id] = ev }
        }
        for ev in await fetchEvents(filters: [f], timeoutSeconds: 5.0) {
            mergedFallback[ev.id] = ev
        }

        let evs = mergedFallback.values
            .filter { !isConsumedKeyPackageEventId($0.id) }
            .compactMap { encodeEventJSON($0) }
        if let best = selectBestKeyPackageEventsByAuthor(events: evs)[pubkey] {
            AppLogger.log("MLS", "fetchLatestKeyPackage: author=\(mlsLogPrefix(pubkey)) viaFallback relays=\(discoveryRelays.count) events=\(evs.count) found=true")
            return best
        }

        // Final fallback (interop): if strict pre-selection yields none, return freshest raw candidate
        // and let mlsAddMember perform final protocol-level acceptance.
        let rawCandidates = mergedFallback.values
            .filter { $0.pubkey == pubkey && ($0.kind == NostrKind.mlsKeyPackage || $0.kind == NostrKind.mlsKeyPackageLegacy) }
            .filter { !isConsumedKeyPackageEventId($0.id) }
            .sorted { lhs, rhs in
                if lhs.createdAt != rhs.createdAt { return lhs.createdAt > rhs.createdAt }
                return lhs.id < rhs.id
            }
        if let raw = rawCandidates.first, let rawJSON = encodeEventJSON(raw) {
            AppLogger.log("MLS", "fetchLatestKeyPackage: author=\(mlsLogPrefix(pubkey)) viaFallback relays=\(discoveryRelays.count) events=\(evs.count) strict=false rawFallback=true kind=\(raw.kind) id=\(raw.id)")
            return rawJSON
        }

        AppLogger.log("MLS", "fetchLatestKeyPackage: author=\(mlsLogPrefix(pubkey)) viaFallback relays=\(discoveryRelays.count) events=\(evs.count) found=false")
        return nil
    }

    private func fetchKeyPackages(pubkeys: [String], ffi: MlsFFIBridge) async -> [String] {
        let f = NostrFilter(
            ids: nil,
            authors: pubkeys,
            kinds: [NostrKind.mlsKeyPackage, NostrKind.mlsKeyPackageLegacy],
            since: nil,
            until: nil,
            limit: pubkeys.count * 4,
            tags: nil,
            search: nil
        )
        var collected: [NostrEvent] = []
        let kpRelayMap = await resolveKeyPackageRelaysForMembers(pubkeys)
        let kpRelays = canonicalRelayUrls(kpRelayMap.values.flatMap { $0 })
        if !kpRelays.isEmpty {
            await client.connect(relayUrls: kpRelays)
            for relay in kpRelays.prefix(10) {
                let evs = await client.fetchEventsFromRelay(relay, filters: [f], timeoutSeconds: 4.0)
                collected.append(contentsOf: evs)
            }
        }
        collected.append(contentsOf: await fetchEvents(filters: [f], timeoutSeconds: 5.0))

        let evs = Dictionary(uniqueKeysWithValues: collected.map { ($0.id, $0) }).values
            .filter { !isConsumedKeyPackageEventId($0.id) }
            .compactMap { encodeEventJSON($0) }
        let bestPerAuthor = selectBestKeyPackageEventsByAuthor(events: evs)
        AppLogger.log("MLS", "fetchKeyPackages: authors=\(pubkeys.count) kpRelays=\(kpRelays.count) found=\(bestPerAuthor.count)")
        return Array(bestPerAuthor.values)
    }

    /// Android parity selection policy:
    /// - choose latest kind:30443 by created_at per author
    /// - fallback to latest kind:443 by created_at per author
    /// - do not apply extra iOS-only prefilters here (final accept/reject is done by mlsAddMember)
    private func selectBestKeyPackageEventsByAuthor(events: [String]) -> [String: String] {
        struct Candidate {
            let eventJSON: String
            let pubkey: String
            let kind: Int
            let createdAt: Int64
            let id: String
        }

        func parse(_ eventJSON: String) -> Candidate? {
            guard let obj = (try? JSONSerialization.jsonObject(with: Data(eventJSON.utf8))) as? [String: Any],
                  let kind = obj["kind"] as? Int,
                  (kind == NostrKind.mlsKeyPackage || kind == NostrKind.mlsKeyPackageLegacy),
                  let pubkey = obj["pubkey"] as? String, !pubkey.isEmpty,
                  let id = obj["id"] as? String, !id.isEmpty else {
                return nil
            }

            let createdAt: Int64
            if let n = obj["created_at"] as? NSNumber {
                createdAt = n.int64Value
            } else if let i = obj["created_at"] as? Int {
                createdAt = Int64(i)
            } else {
                return nil
            }

            return Candidate(eventJSON: eventJSON, pubkey: pubkey, kind: kind, createdAt: createdAt, id: id)
        }

        let parsed = events.compactMap(parse)
        let byAuthor = Dictionary(grouping: parsed, by: { $0.pubkey })
        var best: [String: String] = [:]

        func latest(_ candidates: [Candidate]) -> Candidate? {
            candidates.sorted { lhs, rhs in
                if lhs.createdAt != rhs.createdAt { return lhs.createdAt > rhs.createdAt }
                return lhs.id < rhs.id
            }.first
        }

        for (author, candidates) in byAuthor {
            let k30443 = candidates.filter { $0.kind == NostrKind.mlsKeyPackage }
            if let selected = latest(k30443) {
                best[author] = selected.eventJSON
                continue
            }

            let k443 = candidates.filter { $0.kind == NostrKind.mlsKeyPackageLegacy }
            if let selected = latest(k443) {
                best[author] = selected.eventJSON
            }
        }

        return best
    }


    // MARK: - MIP-02 Welcome Validation / Post-Join Actions

    /// Validate transport-level Welcome event constraints before handing off to Rust.
    ///
    /// - For gift-wrapped kind:1059, outer payload is opaque and validated during unwrapping.
    /// - For legacy/plain kind:444, enforce MIP-02 required tags and base64 constraints.
    private func validateWelcomeEventForMip02(_ ev: NostrEvent) throws {
        switch ev.kind {
        case NostrKind.mlsWelcome:
            // kind:1059 gift-wrap. Inner rumor validation is delegated to Rust MDK.
            return
        case NostrKind.mlsWelcomeInner, NostrKind.mlsWelcomeInnerMarmot:
            // Interop-first: do not pre-reject legacy kind:444 or Marmot alias kind:10444
            // by iOS-side shape checks. Delegate full validation/acceptance to Rust MDK
            // (mlsProcessWelcome), because peers may publish equivalent payload shapes
            // across ecosystem versions.
            return
        default:
            throw MlsError.invalidWelcomeKind
        }
    }

    /// MIP-02 post-join behavior:
    /// 1) Best-effort catch-up of outstanding commits/messages
    /// 2) Self-update commit creation/publish/merge as soon as practical
    /// 3) Persist tracking so we can enforce retry within 24h window
    private func postWelcomeBestEffortCatchUpAndSelfUpdate(group: FfiMlsGroupInfo, ffi: MlsFFIBridge) async {
        let now = Int64(Date().timeIntervalSince1970)
        var joinedMap = prefs.mlsJoinedAtByGroupId
        if joinedMap[group.groupIdHex] == nil {
            joinedMap[group.groupIdHex] = now
            prefs.mlsJoinedAtByGroupId = joinedMap
        }

        // 1) Catch-up best-effort before any local epoch-advancing operation.
        // Marmot MIP-02 says clients should continue catch-up attempts and avoid
        // application sends until they are on the current group epoch. Processing
        // outstanding commits first prevents NuruNuru from self-updating from a stale
        // epoch, which is the common cause of WhiteNoise seeing later iOS messages as
        // undecryptable/no-display.
        do {
            _ = try await fetchMlsMessages(groupIdHex: group.groupIdHex, repairFull: true)
        } catch {
            AppLogger.log("MLS", "postWelcome: catch-up failed (non-fatal) group=\(group.groupIdHex) err=\(mlsRedactedError(error))")
        }

        // 2) WhiteNoise interop: do NOT automatically self-update immediately after
        // processing a Welcome. Device logs show the self-update commit is published and
        // iOS can process it locally, but WhiteNoise Android then does not display the
        // following iOS application message. Keep this install on the Welcome/post-commit
        // epoch until the user explicitly performs membership changes or WhiteNoise sends
        // a commit that advances both sides.
        AppLogger.log("MLS", "postWelcome: automatic self-update suppressed for WhiteNoise interop group=\(group.groupIdHex)")

        // 3) Do not sweep self-update deadlines in the foreground for interop DMs.
    }

    // MARK: - Group Enrich / Bridge

    private func enrichFfiGroup(_ ffiGroup: FfiMlsGroupInfo, ffi: MlsFFIBridge) throws -> MlsGroup {
        let fresh         = (try? ffi.mlsGetGroupInfo(groupIdHex: ffiGroup.groupIdHex)) ?? ffiGroup
        let memberPubkeys = fresh.memberPubkeys
        let profiles: [(String, UserProfile)] = memberPubkeys.compactMap { pk in
            cache.getCachedProfile(pk).map { (pk, $0) }
        }
        return MlsGroup(
            groupIdHex: fresh.groupIdHex, name: fresh.name, description: fresh.description,
            adminPubkeys: fresh.adminPubkeys, memberPubkeys: memberPubkeys,
            relays: fresh.relays, createdAt: Int64(fresh.createdAt),
            epoch: Int64(fresh.epoch), disappearingMessageSecs: fresh.disappearingMessageSecs.map(Int64.init), isDm: fresh.isDm,
            memberProfiles: Dictionary(uniqueKeysWithValues: profiles)
        )
    }

    /// Create/publish/merge a post-Welcome self-update commit.
    ///
    /// Contract note: groupIdHex is the Nostr group id (Kind 445 h tag), not
    /// the internal MLS group id. The required order is:
    /// mlsCreateRecoveryCommit -> publish Kind 445 -> mlsMergePendingCommit.
    /// If publish fails, clear the pending commit so the retry source
    /// (mlsGroupsNeedingSelfUpdate) can create a fresh commit later instead
    /// of leaving the group stuck behind an unpublished pending commit.
    private func publishAndMergeSelfUpdateCommit(
        groupIdHex: String,
        memberPubkeys: [String],
        relays: [String],
        ffi: MlsFFIBridge,
        context: String
    ) async throws {
        let inboxRelays = await resolveInboxRelaysForMembers(memberPubkeys)
        let targetRelays = mlsRelayUrls(relays + inboxRelays)

        // Marmot MIP-02/MIP-03 order is important:
        // create self-update Commit -> publish kind:445 Group Event -> merge pending commit.
        // If publish fails, clear the unpublished pending commit so a later retry creates
        // a fresh Commit from the still-current local state instead of forking silently.
        let commit = try ffi.mlsCreateRecoveryCommit(groupIdHex: groupIdHex)
        do {
            try await publishMlsKind445(data: commit, groupRelays: targetRelays)
        } catch {
            do {
                try ffi.mlsClearPendingCommit(groupIdHex: groupIdHex)
                AppLogger.log("MLS", "\(context): self-update publish failed; cleared pending commit group=\(groupIdHex) err=\(mlsRedactedError(error))")
            } catch {
                AppLogger.log("MLS", "\(context): self-update publish failed; clear pending also failed group=\(groupIdHex) err=\(mlsRedactedError(error))")
            }
            if let account = prefs.publicKeyHex {
                mlsRetryStore.enqueueSelfUpdate(
                    accountPubkey: account,
                    groupIdHex: groupIdHex,
                    relayUrls: targetRelays,
                    lastErrorKind: classifyMlsRetryError(error)
                )
                AppLogger.log("MLS", "\(context): queued self-update retry group=\(groupIdHex) relays=\(targetRelays.count)")
            }
            throw error
        }

        try ffi.mlsMergePendingCommit(groupIdHex: groupIdHex)
        mlsSelfUpdatePublishedThisSession.insert(groupIdHex)

        var doneMap = prefs.mlsSelfUpdateCompletedAtByGroupId
        doneMap[groupIdHex] = Int64(Date().timeIntervalSince1970)
        prefs.mlsSelfUpdateCompletedAtByGroupId = doneMap
    }

    /// MIP-02 MUST within 24h: retry self-update for joined groups without completion marker.
    private func enforceSelfUpdateDeadlineIfNeeded(ffi: MlsFFIBridge) async {
        let now = Int64(Date().timeIntervalSince1970)

        let candidateGroups: [String]
        if let ids = try? ffi.mlsGroupsNeedingSelfUpdate(thresholdSecs: 86_400), !ids.isEmpty {
            candidateGroups = ids
        } else {
            let joined = prefs.mlsJoinedAtByGroupId
            let done = prefs.mlsSelfUpdateCompletedAtByGroupId
            candidateGroups = joined.compactMap { (groupId, joinedAt) in
                if done[groupId] != nil { return nil }
                return (now - joinedAt) >= 60 ? groupId : nil
            }
        }

        for groupId in candidateGroups {
            do {
                let gi = try ffi.mlsGetGroupInfo(groupIdHex: groupId)
                try await publishAndMergeSelfUpdateCommit(
                    groupIdHex: groupId,
                    memberPubkeys: gi.memberPubkeys,
                    relays: gi.relays,
                    ffi: ffi,
                    context: "enforceSelfUpdateDeadlineIfNeeded"
                )
                AppLogger.log("MLS", "enforceSelfUpdateDeadlineIfNeeded: self-update success group=\(groupId)")
            } catch {
                AppLogger.log("MLS", "enforceSelfUpdateDeadlineIfNeeded: retry failed group=\(groupId) err=\(mlsRedactedError(error))")
            }
        }
    }


    /// MIP-02 key package lifecycle:
    /// Rotate consumed key package after successful welcome processing.
    /// - consumed kind:30443 -> publish replacement using SAME d
    /// - consumed kind:443   -> publish fresh kind:30443 with random d
    private func rotateConsumedKeyPackageAfterWelcomeIfNeeded(
        welcomeEvent: NostrEvent,
        ffi: MlsFFIBridge
    ) async {
        guard let consumedEventId = welcomeEvent.getTagValue("e"), !consumedEventId.isEmpty else {
            return
        }
        recordConsumedKeyPackageEventId(consumedEventId)
        keyPackagePublished = false
        let consumedHashRef = prefs.mlsKeyPackageHashRefById[consumedEventId]

        let relayHints = welcomeEvent.tags.first(where: { $0.first == "relays" }).map { Array($0.dropFirst()) } ?? []
        let candidateRelays = mlsRelayUrls(relayHints)
        await client.connect(relayUrls: candidateRelays)

        let byId = NostrFilter(ids: [consumedEventId], authors: nil, kinds: [NostrKind.mlsKeyPackage, NostrKind.mlsKeyPackageLegacy], since: nil, until: nil, limit: 1, tags: nil, search: nil)
        let consumedEvent = await fetchEvents(filters: [byId], timeoutSeconds: 4.0).first

        // Build raw JSON once so we can both (a) inspect kind/d and (b) ask Rust to delete consumed init_key material.
        // Prefer relay-fetched event; fallback to locally persisted keypackage map.
        let localConsumedJson = prefs.mlsKeyPackageEventJsonById[consumedEventId]
        let consumedEventJSON = consumedEvent.flatMap { encodeEventJSON($0) } ?? localConsumedJson

        do {
            let kpData = try ffi.mlsCreateKeyPackage()
            let relayUrls = myMlsKeyPackageRelays()
            var tags30443 = applyStableKeyPackageDTag(kpData.tags, fallback: kpData.dTag).map { t -> [String] in t.first == "relays" ? ["relays"] + relayUrls : t }
            if !tags30443.contains(where: { $0.first == "relays" }) { tags30443.append(["relays"] + relayUrls) }

            // Keep every replacement in the same install/account stable replaceable slot.
            // Canonical rotation publish (kind:30443; same d when consumed was 30443).
            let signed30443 = try await publishEventAndReturnSigned(kind: Int(kpData.kind), tags: tags30443, content: kpData.content)
            persistPublishedKeyPackageEvent(id: signed30443.id, json: encodeEventJSON(signed30443), hashRef: kpData.hashRef)
            // Issue #178 #3: kind:443 mirror removed on rotation (WhiteNoise dropped 443).

            let kpRelayTags = relayUrls.map { ["relay", $0] }
            try await publishEvent(kind: NostrKind.mlsKeyPackageRelays, tags: kpRelayTags, content: "")
            let dmRelayTags = relayUrls.map { ["relay", $0] }
            try await publishEvent(kind: NostrKind.dmRelayList, tags: dmRelayTags, content: "")
            await publishConsumedKeyPackageDeleteBestEffort(eventId: consumedEventId)

            // MIP-02: after successful welcome processing + replacement KeyPackage publish,
            // delete consumed init_key/private keypackage material from local storage.
            do {
                if let consumedHashRef, !consumedHashRef.isEmpty {
                    try ffi.mlsDeleteConsumedKeyPackageByHashRef(hashRef: consumedHashRef)
                    AppLogger.log("MLS", "rotateConsumedKeyPackage: deleted consumed key package by hash_ref id=\(consumedEventId)")
                } else if let consumedEventJSON {
                    try ffi.mlsDeleteConsumedKeyPackageFromEventJSON(eventJSON: consumedEventJSON)
                    AppLogger.log("MLS", "rotateConsumedKeyPackage: deleted consumed key package by event id=\(consumedEventId)")
                }
            } catch {
                AppLogger.log("MLS", "rotateConsumedKeyPackage: consumed key package delete failed id=\(consumedEventId) err=\(mlsRedactedError(error))")
            }

            AppLogger.log("MLS", "rotateConsumedKeyPackage: rotated consumedId=\(consumedEventId) consumedKind=\(consumedEvent?.kind ?? -1)")
        } catch {
            let ownerPubkey = consumedEvent?.pubkey
                ?? consumedEventJSON.flatMap { extractPubkey(from: $0) }
                ?? prefs.publicKeyHex
                ?? ""
            if let account = prefs.publicKeyHex, !ownerPubkey.isEmpty {
                mlsRetryStore.enqueueKeyPackageRotation(
                    accountPubkey: account,
                    keyPackageOwnerPubkey: ownerPubkey,
                    keyPackageEventId: consumedEventId,
                    relayUrls: candidateRelays,
                    lastErrorKind: classifyMlsRetryError(error)
                )
                AppLogger.log("MLS", "rotateConsumedKeyPackage: queued rotation retry consumedId=\(consumedEventId) owner=\(mlsLogPrefix(ownerPubkey)) relays=\(candidateRelays.count)")
            }
            AppLogger.log("MLS", "rotateConsumedKeyPackage: rotate failed id=\(consumedEventId) err=\(mlsRedactedError(error))")
        }
    }

    func bridgeFfiGroup(_ g: FfiMlsGroupInfo) -> MlsGroup {
        MlsGroup(
            groupIdHex: g.groupIdHex, name: g.name, description: g.description,
            adminPubkeys: g.adminPubkeys, memberPubkeys: g.memberPubkeys,
            relays: g.relays, createdAt: Int64(g.createdAt), epoch: Int64(g.epoch),
            disappearingMessageSecs: g.disappearingMessageSecs.map(Int64.init),
            isDm: g.isDm
        )
    }

    // MARK: - Quick Profile Fetch (non-blocking)

    private func quickFetchProfiles(pubkeys: [String]) async -> [UserProfile] {
        guard !pubkeys.isEmpty else { return [] }

        var byKey: [String: UserProfile] = [:]
        var missing: [String] = []

        for pk in pubkeys {
            if let cached = cache.getCachedProfile(pk) {
                byKey[pk] = cached
            } else {
                missing.append(pk)
            }
        }

        // Relay fallback for missing profiles (improves cross-client avatar compatibility)
        if !missing.isEmpty {
            let f = NostrFilter(
                ids: nil,
                authors: missing,
                kinds: [NostrKind.metadata],
                since: nil,
                until: nil,
                limit: missing.count,
                tags: nil,
                search: nil
            )
            let events = await fetchEvents(filters: [f], timeoutSeconds: 5.0)
            var latest: [String: NostrEvent] = [:]
            for ev in events {
                if (latest[ev.pubkey]?.createdAt ?? 0) < ev.createdAt {
                    latest[ev.pubkey] = ev
                }
            }
            for ev in latest.values {
                guard let data = ev.content.data(using: .utf8),
                      let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { continue }

                let picture = (obj["picture"] as? String)
                    ?? (obj["image"] as? String)
                    ?? (obj["avatar"] as? String)
                    ?? (obj["icon"] as? String)

                func birthdayString(_ raw: Any?) -> String? {
                    if let s = raw as? String, !s.isEmpty { return s }
                    if let dict = raw as? [String: Any],
                       let month = dict["month"] as? Int,
                       let day = dict["day"] as? Int,
                       (1...12).contains(month),
                       (1...31).contains(day) {
                        if let year = dict["year"] as? Int, year > 0 {
                            return String(format: "%04d-%02d-%02d", year, month, day)
                        }
                        return String(format: "%02d-%02d", month, day)
                    }
                    return nil
                }

                let p = UserProfile(
                    pubkey: ev.pubkey,
                    name: obj["name"] as? String,
                    displayName: (obj["display_name"] as? String) ?? (obj["displayName"] as? String),
                    about: obj["about"] as? String,
                    picture: picture,
                    nip05: obj["nip05"] as? String,
                    banner: obj["banner"] as? String,
                    lud16: obj["lud16"] as? String,
                    website: obj["website"] as? String,
                    birthday: birthdayString(obj["birthday"]) ?? birthdayString(obj["birthdate"]) ?? birthdayString(obj["birth"]),
                    geohash: obj["geohash"] as? String
                )
                byKey[ev.pubkey] = p
                cache.setCachedProfile(ev.pubkey, p)
            }
        }

        return pubkeys.compactMap { byKey[$0] }
    }

    // MARK: - Relay/Fetch Helpers

    /// Ensure a small, high-value relay set is connected before MLS fetch/publish paths.
    ///
    /// `client.connect()` waits for every requested relay handshake. Logs showed repeated
    /// `relay.nostr.wirednet.jp` handshakes timing out, which serialized Talk polling and
    /// delayed Android→iOS visibility. Keep this helper bounded and prefer the faster JP/global
    /// relays; `fetchFromRelays` can still opportunistically connect individual relays later.
    private func ensureGroupRelaysConnected(_ relays: [String]) async {
        let targets = Array(scoreAndSortRelays(relays).prefix(6))
        guard !targets.isEmpty else { return }
        await client.connect(relayUrls: targets)
    }

    private func relayCacheFresh(_ cachedAt: Date, ttl: TimeInterval = 300) -> Bool {
        Date().timeIntervalSince(cachedAt) < ttl
    }

    private func relayUrlsFromTags(_ tags: [[String]], allowedMarkers: Set<String>? = nil) -> [String] {
        var out: [String] = []
        for t in tags where t.count >= 2 {
            let name = t[0]
            guard name == "r" || name == "relay" else { continue }
            if let allowedMarkers, t.count >= 3 {
                let marker = t[2].lowercased()
                guard allowedMarkers.contains(marker) else { continue }
            }
            let relay = canonicalRelayUrl(t[1])
            if !relay.isEmpty { out.append(relay) }
        }
        return canonicalRelayUrls(out)
    }

    private func fetchFromRelays(_ relays: [String], filters: [NostrFilter], timeoutSeconds: Double, limit: Int = 16) async -> [NostrEvent] {
        let targets = Array(canonicalRelayUrls(relays).prefix(limit))
        guard !targets.isEmpty else { return [] }
        await client.connect(relayUrls: targets)
        var merged: [String: NostrEvent] = [:]
        await withTaskGroup(of: [NostrEvent].self) { group in
            for relay in targets {
                group.addTask { await self.client.fetchEventsFromRelay(relay, filters: filters, timeoutSeconds: timeoutSeconds) }
            }
            for await events in group {
                for ev in events { merged[ev.id] = ev }
            }
        }
        return Array(merged.values)
    }

    private func isMlsWritableRelay(_ relay: String) -> Bool {
        // Logs show these relays either reject Marmot MLS kinds or hang handshakes.
        // Including them in reliable fanout makes sends slow and can keep the UI waiting
        // without improving WhiteNoise delivery.
        let blocked: Set<String> = [
            "wss://purplepag.es",
            "wss://search.nos.today",
            "wss://relay.nostr.wirednet.jp"
        ]
        return !blocked.contains(canonicalRelayUrl(relay))
    }

    private func scoreAndSortRelays(_ relays: [String]) -> [String] {
        let priority: [String: Int] = [
            "wss://auth.nostr1.com": 115,
            "wss://yabu.me": 100,
            "wss://r.kojira.io": 95,
            "wss://relay.damus.io": 90,
            "wss://nos.lol": 85,
            "wss://relay.primal.net": 80,
            "wss://relay-jp.nostr.wirednet.jp": 60,
            // Logs show relay.nostr.wirednet.jp repeatedly hitting handshake timeouts.
            // Keep it available as a fallback, but never let it dominate hot Talk paths.
            "wss://relay.nostr.wirednet.jp": -50
        ]
        return canonicalRelayUrls(relays).sorted { lhs, rhs in
            let l = (mlsRelayHitScores[lhs] ?? 0) + (priority[lhs] ?? 0)
            let r = (mlsRelayHitScores[rhs] ?? 0) + (priority[rhs] ?? 0)
            if l != r { return l > r }
            return lhs < rhs
        }
    }

    private func recordMlsRelayHits(_ events: [NostrEvent], relays: [String]) {
        guard !events.isEmpty else { return }
        for relay in relays { mlsRelayHitScores[relay, default: 0] += events.count }
    }

    /// Resolve KeyPackage relays for members (Marmot kind 10051 preferred, NIP-65 write fallback).
    private func resolveKeyPackageRelaysForMembers(_ memberPubkeys: [String]) async -> [String: [String]] {
        let authors = Array(Set(memberPubkeys.filter { !$0.isEmpty }))
        guard !authors.isEmpty else { return [:] }
        var result: [String: [String]] = [:]
        var missing: [String] = []
        for pk in authors {
            if let cached = mlsKeyPackageRelayCache[pk], relayCacheFresh(cached.cachedAt) { result[pk] = cached.relays } else { missing.append(pk) }
        }
        guard !missing.isEmpty else { return result }
        let discoveryRelays = mlsDiscoveryRelayUrls()
        let kpFilter = NostrFilter(ids: nil, authors: missing, kinds: [NostrKind.mlsKeyPackageRelays], since: nil, until: nil, limit: missing.count * 3, tags: nil, search: nil)
        let kpEvents = await fetchFromRelays(discoveryRelays, filters: [kpFilter], timeoutSeconds: 3.0, limit: 18)
        for ev in kpEvents { for relay in relayUrlsFromTags(ev.tags) { result[ev.pubkey, default: []].append(relay) } }
        let stillMissing = missing.filter { result[$0]?.isEmpty ?? true }
        if !stillMissing.isEmpty {
            let nip65Filter = NostrFilter(ids: nil, authors: stillMissing, kinds: [NostrKind.relayList], since: nil, until: nil, limit: stillMissing.count * 2, tags: nil, search: nil)
            let nip65Events = await fetchFromRelays(discoveryRelays, filters: [nip65Filter], timeoutSeconds: 3.0, limit: 18)
            for ev in nip65Events {
                let write = relayUrlsFromTags(ev.tags, allowedMarkers: ["write", "readwrite"])
                let all = write.isEmpty ? relayUrlsFromTags(ev.tags) : write
                for relay in all { result[ev.pubkey, default: []].append(relay) }
            }
        }
        for pk in missing {
            let canonical = scoreAndSortRelays(result[pk] ?? [])
            if !canonical.isEmpty { result[pk] = canonical; mlsKeyPackageRelayCache[pk] = (canonical, Date()) }
        }
        AppLogger.log("MLS", "resolveKeyPackageRelaysForMembers: members=\(authors.count) fetched=\(missing.count) resolvedAuthors=\(result.count)")
        return result
    }

    /// Resolve Marmot Welcome/MLS inbox relays for members. kind:10050 is used only as a relay-list signal; Talk displays Marmot MLS only.
    private func resolveInboxRelaysForMembers(_ memberPubkeys: [String]) async -> [String] {
        let authors = Array(Set(memberPubkeys.filter { !$0.isEmpty }))
        guard !authors.isEmpty else { return [] }
        var byAuthor: [String: [String]] = [:]
        var missing: [String] = []
        for pk in authors {
            if let cached = mlsInboxRelayCache[pk], relayCacheFresh(cached.cachedAt) { byAuthor[pk] = cached.relays } else { missing.append(pk) }
        }
        if !missing.isEmpty {
            let discoveryRelays = mlsDiscoveryRelayUrls()
            let inboxFilter = NostrFilter(ids: nil, authors: missing, kinds: [NostrKind.dmRelayList], since: nil, until: nil, limit: missing.count * 3, tags: nil, search: nil)
            let inboxEvents = await fetchFromRelays(discoveryRelays, filters: [inboxFilter], timeoutSeconds: 3.0, limit: 18)
            for ev in inboxEvents { let relays = relayUrlsFromTags(ev.tags); if !relays.isEmpty { byAuthor[ev.pubkey, default: []].append(contentsOf: relays) } }
            let nip65Filter = NostrFilter(ids: nil, authors: missing, kinds: [NostrKind.relayList], since: nil, until: nil, limit: missing.count * 2, tags: nil, search: nil)
            let nip65Events = await fetchFromRelays(discoveryRelays, filters: [nip65Filter], timeoutSeconds: 3.0, limit: 18)
            for ev in nip65Events {
                let read = relayUrlsFromTags(ev.tags, allowedMarkers: ["read", "readwrite"])
                let all = read.isEmpty ? relayUrlsFromTags(ev.tags) : read
                if !all.isEmpty { byAuthor[ev.pubkey, default: []].append(contentsOf: all) }
            }
            for pk in missing { let canonical = scoreAndSortRelays(byAuthor[pk] ?? []); if !canonical.isEmpty { mlsInboxRelayCache[pk] = (canonical, Date()); byAuthor[pk] = canonical } }
        }
        let relays = scoreAndSortRelays(byAuthor.values.flatMap { $0 })
        AppLogger.log("MLS", "resolveInboxRelaysForMembers: members=\(authors.count) resolvedAuthors=\(byAuthor.count) relays=\(relays.count)")
        return relays
    }

    /// Collect kind-445 events for one group from app relay pool + group-specific relays + member inbox relays.
    /// Returns time-ascending list.
    private func collectGroupMessageEvents(groupIdHex: String, ffi: MlsFFIBridge, since: Int64? = nil) async -> [NostrEvent] {
        let groupIdCandidates = mlsGroupIdQueryCandidates(groupIdHex)
        let hFilter = NostrFilter(
            ids: nil,
            authors: nil,
            kinds: [NostrKind.mlsGroupMessage],
            since: since,
            until: nil,
            limit: 500,
            tags: ["#h": groupIdCandidates],
            search: nil
        )
        let broadFilter = NostrFilter(
            ids: nil,
            authors: nil,
            kinds: [NostrKind.mlsGroupMessage],
            since: since,
            until: nil,
            limit: 500,
            tags: nil,
            search: nil
        )
        let incremental = since != nil
        let recoveryEventIds = mlsDiagnosticRecoveryEventIds()
        let idFilter = recoveryEventIds.isEmpty ? nil : NostrFilter(
            ids: recoveryEventIds,
            authors: nil,
            kinds: [NostrKind.mlsGroupMessage],
            since: nil,
            until: nil,
            limit: recoveryEventIds.count,
            tags: nil,
            search: nil
        )

        // 1) App-managed relay pool (#h + optional diagnostic event-id probe)
        var mergedById = Dictionary(uniqueKeysWithValues: await fetchEvents(filters: [hFilter], timeoutSeconds: 5.0).map { ($0.id, $0) })
        if let idFilter {
            let idEvents = await fetchEvents(filters: [idFilter], timeoutSeconds: 5.0)
            for ev in idEvents { mergedById[ev.id] = ev }
            AppLogger.log("MLS", "collectGroupMessageEvents: diagnostic idProbe appPool ids=\(recoveryEventIds.count) found=\(idEvents.count)")
        }

        // 2) Group-specific relays + member inbox relays + global fallback relays
        var resolvedRelayCount = 0
        if let gi = try? ffi.mlsGetGroupInfo(groupIdHex: groupIdHex) {
            let inboxRelays = await resolveInboxRelaysForMembers(gi.memberPubkeys)
            var allRelays = mlsRelayUrls(gi.relays + inboxRelays)

            // hostname normalization for wirednet variants
            if allRelays.contains(canonicalRelayUrl("wss://relay.nostr.wirednet.jp"))
                && !allRelays.contains(canonicalRelayUrl("wss://relay-jp.nostr.wirednet.jp")) {
                allRelays.append(canonicalRelayUrl("wss://relay-jp.nostr.wirednet.jp"))
            }
            if allRelays.contains(canonicalRelayUrl("wss://relay-jp.nostr.wirednet.jp"))
                && !allRelays.contains(canonicalRelayUrl("wss://relay.nostr.wirednet.jp")) {
                allRelays.append(canonicalRelayUrl("wss://relay.nostr.wirednet.jp"))
            }

            allRelays = scoreAndSortRelays(allRelays)
            await ensureGroupRelaysConnected(allRelays)
            resolvedRelayCount = allRelays.count

            // Live Talk correctness over speed: Android publishes Marmot kind:445 to a
            // broad fanout set, and the fastest relay is not always in our top-8 scored
            // prefix. Query the full resolved set with a moderate timeout so active iOS
            // polling sees Android messages instead of waiting for a later repair pass.
            let hEvents = await fetchFromRelays(allRelays, filters: [hFilter], timeoutSeconds: incremental ? 3.0 : 3.0, limit: incremental ? 24 : 24)
            recordMlsRelayHits(hEvents, relays: allRelays)
            for ev in hEvents { mergedById[ev.id] = ev }

            if let idFilter {
                let idEvents = await fetchFromRelays(allRelays, filters: [idFilter], timeoutSeconds: 1.8, limit: 12)
                recordMlsRelayHits(idEvents, relays: allRelays)
                for ev in idEvents { mergedById[ev.id] = ev }
                AppLogger.log("MLS", "collectGroupMessageEvents: diagnostic idProbe relays ids=\(recoveryEventIds.count) found=\(idEvents.count)")
            }

            let normalizedCandidates = Set(groupIdCandidates.map { $0.lowercased() })
            // Broad kind:445 scans are expensive and can accidentally mix unrelated DM traffic.
            // Use them only as a conservative fallback when full repair/initial #h lookup finds nothing.
            if !incremental && hEvents.isEmpty {
                let relayBroad = await fetchFromRelays(allRelays, filters: [broadFilter], timeoutSeconds: 3.0, limit: 24)
                recordMlsRelayHits(relayBroad, relays: allRelays)
                for ev in relayBroad where normalizedCandidates.contains((ev.getTagValue("h") ?? "").lowercased()) {
                    mergedById[ev.id] = ev
                }
            }

            AppLogger.log("MLS", "collectGroupMessageEvents: groupRelays=\(gi.relays.count) inboxRelays=\(inboxRelays.count) interopRelays=\(mlsInteropRelayUrls.count) queried=\(allRelays.count) ids=\(groupIdCandidates.map { $0.count }.map(String.init).joined(separator: ",")) values=\(mlsGroupCandidateLog(groupIdCandidates)) total=\(mergedById.count)")
        }

        // 3) App-pool broad supplement as final fallback.
        let normalizedCandidates = Set(groupIdCandidates.map { $0.lowercased() })
        let broadEvents: [NostrEvent]
        if !incremental && mergedById.isEmpty {
            broadEvents = await fetchEvents(filters: [broadFilter], timeoutSeconds: 3.0)
            for ev in broadEvents where normalizedCandidates.contains((ev.getTagValue("h") ?? "").lowercased()) {
                mergedById[ev.id] = ev
            }
        } else {
            broadEvents = []
        }

        AppLogger.log("MLS", "collectGroupMessageEvents: broadPool=\(broadEvents.count) matched=\(mergedById.count) group=\(groupIdHex) incremental=\(incremental) candidates=\(mlsGroupCandidateLog(groupIdCandidates)) relays=\(resolvedRelayCount)")

        return Array(mergedById.values).sorted { lhs, rhs in
            if lhs.createdAt != rhs.createdAt { return lhs.createdAt < rhs.createdAt }
            return lhs.id < rhs.id
        }
    }


    /// Candidate values for Marmot/WhiteNoise h tag queries.
    ///
    /// MDK/NuruNuru normally exposes the 32-byte Nostr group id (64 hex) used by Kind-445 h.
    /// Some WhiteNoise diagnostics print the 16-byte MLS group id instead. That value is not
    /// derivable from the public Nostr group id, so include a persisted/debug alias list in
    /// addition to the historical prefix fallback. Querying aliases is safe: message processing
    /// still happens against the local Rust/MDK group id and logs alias/mismatch cases clearly.
    private func mlsGroupIdQueryCandidates(_ groupIdHex: String) -> [String] {
        let trimmed = groupIdHex.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !trimmed.isEmpty else { return [] }
        var out: [String] = [trimmed]
        if trimmed.count == 64 {
            let legacy16Byte = String(trimmed.prefix(32))
            if legacy16Byte.allSatisfy({ $0.isHexDigit }) {
                out.append(legacy16Byte)
            }
        }


        return Array(NSOrderedSet(array: out.filter { !$0.isEmpty && $0.allSatisfy(\.isHexDigit) })) as? [String] ?? out
    }

    private func mlsGroupCandidateLog(_ values: [String]) -> String {
        values.map { value in
            if value.count <= 12 { return value }
            return "\(value.count):\(value.prefix(8))…\(value.suffix(4))"
        }.joined(separator: ",")
    }

    private func mlsDiagnosticRecoveryEventIds() -> [String] {
        []
    }

    // MARK: - Relay URL Normalization

    /// Canonicalize relay URLs to reduce duplicates such as `wss://yabu.me` and `wss://yabu.me/`.
    private func canonicalRelayUrls(_ urls: [String]) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for raw in urls {
            let normalized = canonicalRelayUrl(raw)
            guard !normalized.isEmpty else { continue }
            if seen.insert(normalized).inserted {
                out.append(normalized)
            }
        }
        return out
    }

    private func canonicalRelayUrl(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, var comp = URLComponents(string: trimmed) else { return "" }
        let scheme = (comp.scheme ?? "wss").lowercased()
        guard scheme == "wss" || scheme == "ws" else { return "" }
        comp.scheme = scheme
        comp.host = comp.host?.lowercased()
        // Root path is normalized away so both host and host/ are treated as same relay.
        if comp.path == "/" { comp.path = "" }
        if comp.path.isEmpty {
            // keep as scheme://host[:port] without trailing slash
            let portPart = comp.port.map { ":\($0)" } ?? ""
            let host = comp.host ?? ""
            if host.isEmpty { return "" }
            return "\(scheme)://\(host)\(portPart)"
        }
        return comp.string ?? trimmed
    }


    // MARK: - MLS Retry Drain (foreground-first)

    /// Foreground-first retry drain for Marmot self-update / KeyPackage rotation / message retry.
    /// BGAppRefreshTask is intentionally not registered here; iOS background execution is
    /// best-effort and should be evaluated only after this foreground drain is stable.
    func drainMlsRetryQueue(trigger: String, maxItems: Int = 4) async {
        guard let account = prefs.publicKeyHex, !account.isEmpty else { return }
        guard let ffi = ensureMlsClient() else { return }

        let summary = mlsRetryStore.summary(accountPubkey: account)
        guard summary.queued > 0 else { return }

        let lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
        var items = mlsRetryStore.dueItems(accountPubkey: account, limit: maxItems)
        if lowPower {
            // Low Power Mode: avoid proactive crypto except user-visible message retry.
            items = items.filter { $0.queueType == .message }
        }
        guard !items.isEmpty else { return }

        AppLogger.log("MLS", "retry drain start trigger=\(trigger) queued=\(summary.queued) due=\(items.count) lowPower=\(lowPower)")

        for item in items {
            switch item.queueType {
            case .selfUpdate:
                await drainSelfUpdateRetry(item, ffi: ffi, trigger: trigger)
            case .keyPackageRotation:
                await drainKeyPackageRotationRetry(item, ffi: ffi, trigger: trigger)
            case .message:
                await drainMessageRetry(item, trigger: trigger)
            }
        }
    }

    private func drainSelfUpdateRetry(_ item: MlsRetryQueueItem, ffi: MlsFFIBridge, trigger: String) async {
        guard let groupIdHex = item.groupIdHex, !groupIdHex.isEmpty else {
            mlsRetryStore.markTerminal(itemId: item.id, reason: "missing_group_id")
            return
        }
        do {
            let gi = try ffi.mlsGetGroupInfo(groupIdHex: groupIdHex)
            try await publishAndMergeSelfUpdateCommit(
                groupIdHex: groupIdHex,
                memberPubkeys: gi.memberPubkeys,
                relays: gi.relays,
                ffi: ffi,
                context: "retryDrain/\(trigger)"
            )
            mlsRetryStore.markSucceeded(itemId: item.id)
            AppLogger.log("MLS", "retry drain self-update success group=\(groupIdHex)")
        } catch {
            let kind = classifyMlsRetryError(error)
            if isTerminalMlsRetry(kind) {
                mlsRetryStore.markTerminal(itemId: item.id, reason: kind.rawValue)
            } else {
                mlsRetryStore.markFailed(itemId: item.id, relayUrls: item.relayUrls, errorKind: kind, foreground: true)
            }
            AppLogger.log("MLS", "retry drain self-update failed group=\(groupIdHex) err=\(mlsRedactedError(error))")
        }
    }

    private func drainMessageRetry(_ item: MlsRetryQueueItem, trigger: String) async {
        guard let signedEventJSON = item.signedEventJSON, !signedEventJSON.isEmpty else {
            mlsRetryStore.markTerminal(itemId: item.id, reason: "missing_signed_event_json")
            return
        }
        let relays = mlsRetryStore.availableRelays(for: item)
        guard !relays.isEmpty else {
            mlsRetryStore.markFailed(itemId: item.id, relayUrls: item.relayUrls, errorKind: .notConnected, foreground: true)
            return
        }
        do {
            await client.connect(relayUrls: relays)
            try await client.publishRawEventJSON(signedEventJSON, to: relays, waitForAllRelays: true)
            mlsRetryStore.markSucceeded(itemId: item.id)
            AppLogger.log("MLS", "retry drain message success event=\(item.eventId ?? "") group=\(item.groupIdHex ?? "") relays=\(relays.count)")
        } catch {
            let kind = classifyMlsRetryError(error)
            if isTerminalMlsRetry(kind) {
                mlsRetryStore.markTerminal(itemId: item.id, reason: kind.rawValue)
            } else {
                mlsRetryStore.markFailed(itemId: item.id, relayUrls: relays, errorKind: kind, foreground: true)
            }
            AppLogger.log("MLS", "retry drain message failed event=\(item.eventId ?? "") err=\(mlsRedactedError(error))")
        }
    }

    private func drainKeyPackageRotationRetry(_ item: MlsRetryQueueItem, ffi: MlsFFIBridge, trigger: String) async {
        guard let account = prefs.publicKeyHex, !account.isEmpty else { return }
        let owner = item.keyPackageOwnerPubkey ?? account
        guard owner == account else {
            // iOS can only rotate the current account's KeyPackage. A different owner
            // would imply using somebody else's private init key, so classify terminal.
            mlsRetryStore.markTerminal(itemId: item.id, reason: "owner_not_current_account")
            AppLogger.log("MLS", "retry drain keypackage terminal owner_not_current_account owner=\(mlsLogPrefix(owner))")
            return
        }

        do {
            try await publishReplacementKeyPackageForRetry(item: item, ffi: ffi)
            mlsRetryStore.markSucceeded(itemId: item.id)
            AppLogger.log("MLS", "retry drain keypackage success owner=\(mlsLogPrefix(owner)) consumed=\(item.keyPackageEventId ?? "")")
        } catch {
            let kind = classifyMlsRetryError(error)
            if isTerminalMlsRetry(kind) {
                mlsRetryStore.markTerminal(itemId: item.id, reason: kind.rawValue)
            } else {
                mlsRetryStore.markFailed(itemId: item.id, relayUrls: item.relayUrls, errorKind: kind, foreground: true)
            }
            AppLogger.log("MLS", "retry drain keypackage failed owner=\(mlsLogPrefix(owner)) err=\(mlsRedactedError(error))")
        }
    }

    private func publishReplacementKeyPackageForRetry(item: MlsRetryQueueItem, ffi: MlsFFIBridge) async throws {
        let kpData = try ffi.mlsCreateKeyPackage()
        let relayUrls = canonicalRelayUrls((item.relayUrls.isEmpty ? myMlsKeyPackageRelays() : item.relayUrls) + ["wss://relay.damus.io", "wss://nos.lol"])
        var tags30443 = applyStableKeyPackageDTag(kpData.tags, fallback: kpData.dTag).map { t -> [String] in t.first == "relays" ? ["relays"] + relayUrls : t }
        if !tags30443.contains(where: { $0.first == "relays" }) { tags30443.append(["relays"] + relayUrls) }

        if let consumedId = item.keyPackageEventId,
           let consumedJSON = prefs.mlsKeyPackageEventJsonById[consumedId],
           extractKind(from: consumedJSON) == NostrKind.mlsKeyPackage,
           let d = extractTagValue(from: consumedJSON, tag: "d"), !d.isEmpty {
            tags30443.removeAll { $0.first == "d" }
            tags30443.insert(["d", d], at: 0)
        } else if !kpData.dTag.isEmpty {
            tags30443.removeAll { $0.first == "d" }
            tags30443.insert(["d", kpData.dTag], at: 0)
        }

        let signed30443 = try await publishEventAndReturnSigned(kind: Int(kpData.kind), tags: tags30443, content: kpData.content)
        persistPublishedKeyPackageEvent(id: signed30443.id, json: encodeEventJSON(signed30443), hashRef: kpData.hashRef)

        let kpRelayTags = relayUrls.map { ["relay", $0] }
        try await publishEvent(kind: NostrKind.mlsKeyPackageRelays, tags: kpRelayTags, content: "")
        let dmRelayTags = relayUrls.map { ["relay", $0] }
        try await publishEvent(kind: NostrKind.dmRelayList, tags: dmRelayTags, content: "")

        if let consumedId = item.keyPackageEventId {
            await publishConsumedKeyPackageDeleteBestEffort(eventId: consumedId)
            if let hashRef = prefs.mlsKeyPackageHashRefById[consumedId], !hashRef.isEmpty {
                try? ffi.mlsDeleteConsumedKeyPackageByHashRef(hashRef: hashRef)
            } else if let consumedJSON = prefs.mlsKeyPackageEventJsonById[consumedId] {
                try? ffi.mlsDeleteConsumedKeyPackageFromEventJSON(eventJSON: consumedJSON)
            }
        }
        keyPackagePublished = true
    }

    private func classifyMlsRetryError(_ error: Error) -> MlsRetryLastErrorKind {
        let raw = String(describing: error).lowercased()
        if raw.contains("invalid") || raw.contains("malformed") || raw.contains("missing_h_tag") || raw.contains("group_id_mismatch") {
            return .invalidPayload
        }
        if raw.contains("notconnected") || raw.contains("not connected") { return .notConnected }
        if raw.contains("rate") || raw.contains("too many") { return .rateLimited }
        if raw.contains("auth") { return .authRequired }
        if raw.contains("reject") || raw.contains("blocked") || raw.contains("policy") { return .relayRejected }
        if raw.contains("keynotunlocked") || raw.contains("secret key is not unlocked") || raw.contains("signing") { return .signingKeyMissing }
        if raw.contains("group not") || raw.contains("left group") || raw.contains("deleted") { return .groupUnavailable }
        return .transientNetwork
    }

    private func isTerminalMlsRetry(_ kind: MlsRetryLastErrorKind) -> Bool {
        switch kind {
        case .invalidPayload, .signingKeyMissing, .groupUnavailable:
            return true
        case .transientNetwork, .relayRejected, .rateLimited, .authRequired, .notConnected, .unknown:
            return false
        }
    }

    // MARK: - Fallbacks

    private func makeFallbackDmGroup(partner: String, me: String) -> MlsGroup {
        MlsGroup(
            groupIdHex: String([me, partner].sorted().joined().prefix(64)),
            name: "", description: "", adminPubkeys: [me],
            memberPubkeys: [me, partner], relays: prefs.selectedRelays,
            createdAt: Int64(Date().timeIntervalSince1970), epoch: 0,
            disappearingMessageSecs: nil,
            isDm: true
        )
    }

    private func makeFallbackGroupChat(name: String, members: [String], me: String) -> MlsGroup {
        var allMembers = members
        if !allMembers.contains(me) { allMembers.insert(me, at: 0) }
        return MlsGroup(
            groupIdHex: UUID().uuidString.replacingOccurrences(of: "-", with: ""),
            name: name, description: "", adminPubkeys: [me],
            memberPubkeys: allMembers, relays: prefs.selectedRelays,
            createdAt: Int64(Date().timeIntervalSince1970), epoch: 0,
            disappearingMessageSecs: nil,
            isDm: false
        )
    }

    // MARK: - JSON Helpers

    private func encodeEventJSON(_ event: NostrEvent) -> String? {
        guard let data = try? JSONEncoder().encode(event) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func decodeEventJSON(_ json: String) -> NostrEvent? {
        try? JSONDecoder().decode(NostrEvent.self, from: Data(json.utf8))
    }

    private func isNonRetryableMlsUnprocessable(_ kind: String) -> Bool {
        guard kind.hasPrefix("unhandled:Unprocessable") else { return false }
        return kind.hasSuffix(":missing_h_tag")
            || kind.hasSuffix(":group_id_mismatch")
            || kind.hasSuffix(":invalid_kind")
    }

    /// Issue #178 #7: classify receive errors so we can drop unrecoverable
    /// ones without ever touching pending state. Mirrors Android's policy.
    private func isPermanentMlsProcessDropError(_ error: Error) -> Bool {
        let raw = String(describing: error).lowercased()
        return raw.contains("invalid_kind")
            || raw.contains("missing_h_tag")
            || raw.contains("group_id_mismatch")
            || raw.contains("invalid_base64")
            || raw.contains("malformed")
            || raw.contains("invalid welcome json")
            || raw.contains("not_mls_welcome_rumor")
    }

    private func invalidMlsOuterPayloadReason(_ content: String) -> String? {
        guard let decoded = Data(base64Encoded: content) else {
            return "invalid_base64"
        }
        if decoded.count < 28 {
            return "too_short_\(decoded.count)"
        }
        return nil
    }

    private func mergeHistoryAndLive(history: [FfiDecryptedMessage], live: [FfiDecryptedMessage]) -> [FfiDecryptedMessage] {
        if live.isEmpty { return history }
        var seen = Set(history.map { "\($0.senderPubkey)-\($0.timestamp)-\($0.content)" })
        var out = history
        for m in live {
            let k = "\(m.senderPubkey)-\(m.timestamp)-\(m.content)"
            if !seen.contains(k) {
                seen.insert(k)
                out.append(m)
            }
        }
        return out.sorted { $0.timestamp < $1.timestamp }
    }

    private func extractEventId(from json: String) -> String? {
        (try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])?["id"] as? String
    }

    private func extractPubkey(from json: String) -> String? {
        (try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])?["pubkey"] as? String
    }

    private func extractKind(from json: String) -> Int? {
        (try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])?["kind"] as? Int
    }

    private func extractTagValue(from json: String, tag: String) -> String? {
        guard let obj  = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any],
              let tags = obj["tags"] as? [[Any]] else { return nil }
        return tags.first { ($0.first as? String) == tag && $0.count > 1 }?
            .dropFirst().first as? String
    }

    /// Stable message ID for SwiftUI diffing.
    /// Avoids index-based IDs that change on every re-fetch.
    private func stableMessageId(groupIdHex: String, senderPubkey: String, timestamp: Int64, content: String) -> String {
        let normalized = content.trimmingCharacters(in: .whitespacesAndNewlines)
        let raw = "\(groupIdHex)|\(senderPubkey)|\(timestamp)|\(normalized)"
        // FNV-1a 64-bit (deterministic, no extra framework dependency)
        var hash: UInt64 = 0xcbf29ce484222325
        for b in raw.utf8 {
            hash ^= UInt64(b)
            hash = hash &* 0x100000001b3
        }
        return String(format: "mls_%016llx", hash)
    }

    // MARK: - Issue #183: peer-epoch deep catch-up

    /// Issue #183: deep peer-epoch catch-up for a DM (or group).
    ///
    /// Called when standard repair (`fetchMlsMessages(repairFull: true)`)
    /// still leaves an MLS state gap. Pulls a wider Kind-445 window
    /// (no since/cooldown filters, higher limit, longer timeout) and hands
    /// the raw events + every cached wrapper Rust has stored over to
    /// `mlsCatchUpToPeer`. The Rust side replays them in `created_at`
    /// order across up to 8 retry passes, applies any missing Commits,
    /// and reports whether the local epoch is now usable.
    ///
    /// Receive-path semantics (AC3): this function never invokes
    /// `mlsClearPendingCommit` / `mlsMergePendingCommit`. PR #180's
    /// invariant (the receive path must not tear down our own in-flight
    /// commits) is preserved.
    ///
    /// Returns nil when the FFI is unavailable or the group is unknown to
    /// MDK; callers should treat that as `MlsRecoveryStatus.unknown` and
    /// fall back to standard polling.
    func deepCatchUpMlsGroup(groupIdHex: String) async -> MlsDeepCatchUpResult? {
        guard let ffi = ensureMlsClient() else { return nil }

        // Group must be visible in MDK and belong to the current account.
        guard let groupInfo = try? ffi.mlsGetGroupInfo(groupIdHex: groupIdHex),
              isCurrentAccountMlsGroup(groupInfo) else {
            AppLogger.log("MLS", "deepCatchUpMlsGroup(\(groupIdHex)): unknown / cross-account group")
            return nil
        }

        // Resolve the same relay set used by fetchMlsMessages so we hit the
        // relays the peer publishes to. WhiteNoise interop requires the
        // member-inbox set.
        let groupRelays = groupInfo.relays
        let inboxRelays = await resolveInboxRelaysForMembers(groupInfo.memberPubkeys)
        let allRelays   = mlsRelayUrls(groupRelays + inboxRelays)

        if !allRelays.isEmpty {
            await client.connect(relayUrls: allRelays)
        }

        // Wider pull than repairFull: no since filter, larger limit, longer timeout.
        // This is the "look harder for the missing Commit" pass.
        let filter = NostrFilter(
            ids: nil,
            authors: nil,
            kinds: [NostrKind.mlsGroupMessage],
            since: nil,
            until: nil,
            limit: 2_000,
            tags: ["#h": [groupIdHex]],
            search: nil
        )

        let rawEvents: [NostrEvent]
        if !allRelays.isEmpty {
            rawEvents = await fetchFromRelays(allRelays, filters: [filter], timeoutSeconds: 25.0, limit: 2_000)
        } else {
            rawEvents = await fetchEvents(filters: [filter], timeoutSeconds: 20.0)
        }

        // Dedup by event id and serialize for the FFI hand-off.
        var seen = Set<String>()
        let unique = rawEvents.filter { seen.insert($0.id).inserted }
        let candidatesJson = unique.compactMap { encodeEventJSON($0) }

        AppLogger.log("MLS", "deepCatchUpMlsGroup(\(groupIdHex)): relays=\(allRelays.count) fetched=\(unique.count)")

        let report: FfiMlsCatchUpReport
        do {
            report = try ffi.mlsCatchUpToPeer(groupIdHex: groupIdHex, candidateEventsJson: candidatesJson)
        } catch {
            AppLogger.log("MLS", "deepCatchUpMlsGroup(\(groupIdHex)) FFI failed: \(mlsRedactedError(error))")
            return nil
        }

        let mapped: MlsRecoveryStatus
        switch report.status {
        case .recovered:          mapped = .healthy
        case .partiallyRecovered: mapped = .recovering
        case .notRecoverable:     mapped = .notRecoverable
        case .noSuchGroup:        mapped = .unknown
        }
        mlsRecoveryStatuses[groupIdHex] = mapped

        // If Rust advanced the local epoch, drop the session-only processed-ids
        // cache so the next standard pull picks up newly decryptable application
        // messages and the UI sees them on the next stream tick.
        if report.epochAfter > report.epochBefore {
            mlsProcessedIds.removeValue(forKey: groupIdHex)
            mlsAppliedEventIds.removeValue(forKey: groupIdHex)
            mlsRetryableStateCount.removeValue(forKey: groupIdHex)
            mlsRetryableEventCooldownUntil.removeValue(forKey: groupIdHex)
            _ = try? await fetchMlsMessages(groupIdHex: groupIdHex, repairFull: true)
        }

        AppLogger.log(
            "MLS",
            "deepCatchUpMlsGroup(\(groupIdHex)): status=\(report.status) " +
            "epoch=\(report.epochBefore)->\(report.epochAfter) " +
            "apps=\(report.applicationMessagesApplied) commits=\(report.commitsApplied) " +
            "unresolved=\(report.stillUnprocessable) cacheHits=\(report.cacheHits)"
        )

        return MlsDeepCatchUpResult(
            groupIdHex: groupIdHex,
            status: mapped,
            epochBefore: report.epochBefore,
            epochAfter: report.epochAfter,
            candidatesConsidered: Int(report.candidatesConsidered),
            applicationMessagesApplied: Int(report.applicationMessagesApplied),
            commitsApplied: Int(report.commitsApplied),
            stillUnprocessable: Int(report.stillUnprocessable),
            cacheHits: Int(report.cacheHits)
        )
    }

    /// Issue #183: best-effort prune of the Rust replay-cache sidecar.
    /// Returns the number of rows removed (0 on error / when the file
    /// doesn't exist yet). Safe to call at most once per app session.
    @discardableResult
    func pruneMlsReplayCache() async -> UInt64 {
        guard let ffi = ensureMlsClient() else { return 0 }
        do {
            return try ffi.mlsPruneReplayCache()
        } catch {
            AppLogger.log("MLS", "pruneMlsReplayCache failed: \(mlsRedactedError(error))")
            return 0
        }
    }

    /// Issue #183: most recent recovery classification for the group.
    /// Defaults to `.unknown` until `deepCatchUpMlsGroup` runs at least once.
    func mlsRecoveryStatusFor(groupIdHex: String) -> MlsRecoveryStatus {
        mlsRecoveryStatuses[groupIdHex] ?? .unknown
    }

    /// Issue #183: clear the cached recovery status for a group (used after
    /// the user successfully recreates the conversation).
    func clearMlsRecoveryStatus(groupIdHex: String) {
        mlsRecoveryStatuses.removeValue(forKey: groupIdHex)
    }

    /// Issue #183 fallback (AC2): recreate the DM with `partnerPubkey` from
    /// scratch when `deepCatchUpMlsGroup` reports `.notRecoverable`.
    ///
    /// This is the user-facing equivalent of the issue report's "workaround A"
    /// (leave the DM on both ends and recreate). We:
    ///
    /// 1. Leave the old group locally (best-effort publish a leave Commit so
    ///    the peer can prune their side).
    /// 2. Create a fresh DM group anchored at epoch 0 — both ends realign.
    /// 3. Clear the cached recovery status for the old group so a stale
    ///    banner does not linger in the UI.
    ///
    /// Returns the new `MlsGroup` on success, or nil when the partner's
    /// KeyPackage could not be fetched (caller should surface a user-facing
    /// "相手の鍵情報を取得できません" error).
    func recreateDmConversation(
        oldGroupIdHex: String,
        partnerPubkey: String,
        myPubkeyHex: String
    ) async -> MlsGroup? {
        // Best-effort leave on the old group. Do not fail the recreate if
        // the leave commit cannot publish — the local hide is already done,
        // and that is what the local UI cares about.
        try? await leaveMlsGroup(groupIdHex: oldGroupIdHex)
        clearMlsRecoveryStatus(groupIdHex: oldGroupIdHex)
        mlsProcessedIds.removeValue(forKey: oldGroupIdHex)
        mlsAppliedEventIds.removeValue(forKey: oldGroupIdHex)
        mlsRetryableStateCount.removeValue(forKey: oldGroupIdHex)
        mlsRetryableEventCooldownUntil.removeValue(forKey: oldGroupIdHex)

        do {
            let fresh = try await createMlsDmConversation(
                partnerPubkeyHex: partnerPubkey,
                myPubkeyHex: myPubkeyHex
            )
            AppLogger.log("MLS", "recreateDmConversation: old=\(oldGroupIdHex) new=\(fresh.groupIdHex)")
            return fresh
        } catch {
            AppLogger.log(
                "MLS",
                "recreateDmConversation(\(oldGroupIdHex)) failed: \(mlsRedactedError(error))"
            )
            return nil
        }
    }
}

// MARK: - Issue #183: peer-epoch recovery types (cross-platform parity)

/// Recovery classification for an MLS group, mirroring Android's
/// `MlsRecoveryStatus` enum. UI surfaces a banner only on `.notRecoverable`.
///
/// - `.healthy`        last catch-up reported Recovered.
/// - `.recovering`     last catch-up reported PartiallyRecovered — keep polling.
/// - `.notRecoverable` the missing Commit is not retrievable from any
///                     configured relay and is not in the local replay
///                     cache. UI prompts the user to recreate the
///                     conversation (AC2).
/// - `.unknown`        never attempted catch-up for this group.
enum MlsRecoveryStatus: Sendable, Equatable {
    case healthy
    case recovering
    case notRecoverable
    case unknown
}

/// Issue #183: deep-catch-up result mirrored to the app layer.
/// Strictly read-only — the wrapper writes only into the in-memory status map.
struct MlsDeepCatchUpResult: Sendable {
    let groupIdHex: String
    let status: MlsRecoveryStatus
    let epochBefore: UInt64
    let epochAfter: UInt64
    let candidatesConsidered: Int
    let applicationMessagesApplied: Int
    let commitsApplied: Int
    let stillUnprocessable: Int
    let cacheHits: Int
}

// MARK: - MLS Errors

enum MlsError: LocalizedError {
    case keyPackageNotFound
    case groupNotFound
    case notAdmin
    case selfRemoveFailed
    case noFfiClient
    case invalidPayload
    case invalidDisappearingMessageDuration
    case groupStateStuck
    case invalidWelcomeKind
    case invalidWelcomeEncoding
    case invalidWelcomeMissingKeyPackageRef
    case invalidWelcomeRelays

    var errorDescription: String? {
        switch self {
        case .keyPackageNotFound: return "相手のキーパッケージが見つかりません"
        case .groupNotFound:      return "グループが見つかりません"
        case .notAdmin:           return "管理者のみがこの操作を実行できます"
        case .selfRemoveFailed:   return "グループの退出に失敗しました"
        case .noFfiClient:        return "MLS エンジンが初期化されていません"
        case .invalidPayload:     return "MLS イベント形式が不正です"
        case .invalidDisappearingMessageDuration: return "消えるメッセージの有効期限が不正です"
        case .groupStateStuck:    return "グループ状態が破損しています（再作成が必要）"
        case .invalidWelcomeKind: return "Welcomeイベント種別が不正です"
        case .invalidWelcomeEncoding: return "Welcomeイベントのエンコード形式が不正です（base64必須）"
        case .invalidWelcomeMissingKeyPackageRef: return "WelcomeイベントにKeyPackage参照(eタグ)がありません"
        case .invalidWelcomeRelays: return "Welcomeイベントに有効なrelaysタグがありません"
        }
    }
}