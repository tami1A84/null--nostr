import Foundation

/// ViewModel for TalkView — MLS group list and group chat.
/// Mirrors Android TalkViewModel.kt.
@Observable @MainActor final class TalkViewModel {

    // MARK: - Published State

    var groups:           [MlsGroup]    = []
    // Talk list loading is background-only for App Review stability.
    // Do not show a foreground spinner/skeleton while MLS/KeyPackage discovery runs.
    var isLoading:        Bool          = false
    var error:            String?       = nil
    var activeGroup:      MlsGroup?     = nil
    var messages:         [MlsMessage]  = []
    var messagesLoading:  Bool          = false
    var sendingMessage:   Bool          = false
    var showGroupInfo:    Bool          = false
    var showCreateGroup:  Bool          = false
    var followingProfiles: [UserProfile] = []
    var followingLoading: Bool          = false
    var stuckGroupIds: Set<String>      = []
    /// Issue #183: recovery state of the currently-open DM. When set to
    /// `.notRecoverable` the UI renders a banner offering "会話を作り直す"
    /// (AC2). `nil` = no banner.
    var recoveryStatus:        MlsRecoveryStatus? = nil
    /// Issue #183: true while `recreateActiveDmConversation` is in flight
    /// so the banner can disable its buttons.
    var recreatingConversation: Bool             = false
    // Raw MLS groups from Rust/relays. The visible list is collapsed by participant
    // public keys, but open/send still scans sibling group ids for interop recovery.
    private var allMlsGroups: [MlsGroup] = []
    private var autoRecoveredDivergedDmPartners: Set<String> = []
    private var autoRecoveredDmGroupIds: Set<String> = []
    private var dmRecoveryInFlight: Bool = false
    private var repairInFlightGroupIds: Set<String> = []

    // MARK: - Private

    private let repository:    NostrRepository
    let myPubkeyHex: String
    private var pollingTask:   Task<Void, Never>?
    private var canonicalDmGroupByConversationKey: [String: String] = [:]
    /// Session-only marker for explicit「新しくトークを作成」groups. A normal
    /// canonical pin still needs sibling scanning; only this fresh-create marker
    /// suppresses old sibling-history merge in the current session. After restart,
    /// repository hidden tombstones keep old siblings out of `allMlsGroups`.
    private var freshCreatedDmGroupIds: Set<String> = []
    private var mlsGroupRetryCooldownUntil: [String: Date] = [:]
    private var mlsPollHealth: [String: MlsPollHealth] = [:]
    private var lastMessageActivityAt: Date = Date()

    private struct MlsPollHealth {
        var polls = 0
        var relaySweeps = 0
        var errors = 0
        var lastFetched = 0
        var lastMerged = 0
        var lastRelaySweep = false
        var lastGap = false
    }

    private func normalizeMlsError(_ error: Error, fallback: String) -> String {
        if let mls = error as? MlsError, let d = mls.errorDescription, !d.isEmpty {
            return d
        }
        let raw = String(describing: error).lowercased()

        // MDK / FFI string-level normalization (defensive mapping)
        if raw.contains("invalid_base64_content") || raw.contains("invalid base64") {
            return "MLSイベント形式が不正です（base64）"
        }
        if raw.contains("malformed_content_too_short") || raw.contains("too_short") {
            return "MLSイベント形式が不正です（長さ不足）"
        }
        if raw.contains("missing_h_tag") {
            return "MLSイベント形式が不正です（hタグ不足）"
        }
        if raw.contains("group_id_mismatch") {
            return "MLSイベントのグループIDが一致しません"
        }
        if raw.contains("invalid_kind") {
            return "MLSイベント種別が不正です"
        }
        if raw.contains("pending proposal exists") || raw.contains("pending commit exists") {
            return "同期中です。少し待って再試行してください"
        }
        if raw.contains("no ffi client") || raw.contains("ffi unavailable") || raw.contains("mls not initialised") {
            return "MLSエンジンが利用できません"
        }
        if raw.contains("not admin") {
            return "管理者のみがこの操作を実行できます"
        }

        return fallback
    }

    private func setNormalizedError(_ error: Error, fallback: String, context: String) {
        let message = normalizeMlsError(error, fallback: fallback)
        self.error = message
        AppLogger.log("MLS", "TalkVM.\(context) normalizedError=\(message) raw=\(String(describing: error))")
    }

    func isGroupStuck(_ groupIdHex: String) -> Bool {
        stuckGroupIds.contains(groupIdHex)
    }

    // MARK: - Init

    init(repository: NostrRepository, myPubkeyHex: String) {
        self.repository   = repository
        self.myPubkeyHex  = myPubkeyHex
        // Heavy Marmot/MLS relay discovery is intentionally lazy.
        // MainTabView keeps TalkView alive even when the timeline is active, so starting
        // here would connect WhiteNoise/Marmot interop relays during timeline startup.
    }

    // MARK: - Group List

    /// 進行中の loadGroups を防ぐフラグ
    private var loadGroupsInFlight = false

    /// openGroup() が sibling DM selection を完了する前に送信される race を防ぐ。
    private var openingGroupInFlight = false

    func loadGroups() async {
        // 既に実行中なら重複呼び出しを無視
        guard !loadGroupsInFlight else {
            AppLogger.log("MLS", "TalkVM.loadGroups skipped (already in flight)")
            return
        }
        loadGroupsInFlight = true
        defer { loadGroupsInFlight = false }

        AppLogger.log("MLS", "TalkVM.loadGroups start")
        // App Review 2.1(a): never put Talk into a foreground loading state.
        // MLS/KeyPackage relay discovery can be slow or relay-dependent; keep the
        // existing empty screen visible and update groups when the background fetch
        // finishes.
        isLoading = false
        error     = nil

        // Cache-first Talk startup: paint locally-known Rust SQLite groups before
        // any relay Welcome/profile discovery. This makes the Talk tab usable
        // immediately after launch; the fetch below refines the list afterward.
        let localGroups = await repository.getLocalMlsGroups(myPubkeyHex: myPubkeyHex)
        if !localGroups.isEmpty {
            let filtered = localGroups.filter { !stuckGroupIds.contains($0.groupIdHex) }
            allMlsGroups = filtered
            groups = collapseDuplicateConversationGroups(filtered)
            AppLogger.log("MLS", "TalkVM.loadGroups local-first groups=\(localGroups.count) collapsed=\(groups.count)")
        }

        do {
            let fetched = try await repository.fetchMlsGroups(myPubkeyHex: myPubkeyHex)
            let filtered = fetched.filter { !stuckGroupIds.contains($0.groupIdHex) }
            allMlsGroups = filtered
            let previousGroups = groups
            let collapsed = collapseDuplicateConversationGroups(filtered)
            groups = collapsed
            AppLogger.log("MLS", "TalkVM.loadGroups success: groups=\(fetched.count) filtered=\(filtered.count) collapsed=\(collapsed.count)")

            if let active = activeGroup,
               let remapped = remapGroupForCollapsedConversation(activeGroupId: active.groupIdHex, previousGroups: previousGroups, in: collapsed) {
                activeGroup = remapped
            }
            isLoading = false
        } catch {
            AppLogger.log("MLS", "TalkVM.loadGroups error: \(error)")
            setNormalizedError(error, fallback: "トークの読み込みに失敗しました", context: "loadGroups")
            isLoading   = false
        }
    }

    // MARK: - Group Dedup (DM)

    private func allGroupsForLookup() -> [MlsGroup] {
        var byId: [String: MlsGroup] = [:]
        for g in allMlsGroups + groups { byId[g.groupIdHex] = g }
        return Array(byId.values)
    }

    private func conversationKey(_ group: MlsGroup) -> String {
        let members = Array(Set(group.memberPubkeys.map { $0.lowercased() })).sorted()
        if group.isDm {
            return "dm:" + (members.first(where: { $0 != myPubkeyHex.lowercased() }) ?? members.joined(separator: "|"))
        }
        return "group:" + members.joined(separator: "|")
    }

    /// UX: groupId が変わっても参加公開鍵セットが同じなら同じトークとして一覧表示する。
    private func collapseDuplicateConversationGroups(_ input: [MlsGroup]) -> [MlsGroup] {
        let grouped = Dictionary(grouping: input, by: { conversationKey($0) })
        for (key, entries) in grouped where entries.count > 1 {
            AppLogger.log("MLS", "TalkVM.collapseDuplicateConversationGroups: key=\(String(key.prefix(18))) duplicates=\(entries.count) display=one")
        }
        return grouped.values.compactMap { entries in
            entries.max { lhs, rhs in
                if lhs.lastMessageTime != rhs.lastMessageTime { return lhs.lastMessageTime < rhs.lastMessageTime }
                if lhs.createdAt != rhs.createdAt { return lhs.createdAt < rhs.createdAt }
                return lhs.groupIdHex < rhs.groupIdHex
            }
        }.sorted { lhs, rhs in
            if lhs.lastMessageTime != rhs.lastMessageTime { return lhs.lastMessageTime > rhs.lastMessageTime }
            return lhs.groupIdHex < rhs.groupIdHex
        }
    }

    private func remapGroupForCollapsedConversation(activeGroupId: String, previousGroups: [MlsGroup], in collapsed: [MlsGroup]) -> MlsGroup? {
        guard let previous = (previousGroups + allMlsGroups).first(where: { $0.groupIdHex == activeGroupId }) else {
            return collapsed.first(where: { $0.groupIdHex == activeGroupId })
        }
        return collapsed.first(where: { conversationKey($0) == conversationKey(previous) })
            ?? collapsed.first(where: { $0.groupIdHex == activeGroupId })
    }

    private func siblingConversationGroups(for group: MlsGroup, from source: [MlsGroup]? = nil) -> [MlsGroup] {
        let key = conversationKey(group)
        var byId: [String: MlsGroup] = [:]
        for g in (source ?? allGroupsForLookup()) where conversationKey(g) == key {
            byId[g.groupIdHex] = g
        }
        return Array(byId.values)
    }

    /// 同一相手の DM groupId を返す（重複移行期フォールバック用）。
    private func siblingDmGroupIds(for group: MlsGroup, from source: [MlsGroup]) -> [String] {
        siblingConversationGroups(for: group, from: source).filter { $0.isDm }.map { $0.groupIdHex }
    }

    // MARK: - Group Chat

    func openGroup(_ groupIdHex: String) async {
        guard !openingGroupInFlight else {
            AppLogger.log("MLS", "TalkVM.openGroup skipped (already opening) requested=\(groupIdHex)")
            return
        }
        openingGroupInFlight = true

        AppLogger.log("MLS", "TalkVM.openGroup start: group=\(groupIdHex)")
        // Do not drain retry queue before opening: it serializes on NostrRepository and
        // makes Talk appear frozen. Open local history first, then catch up.
        // 以前は stuckGroupIds で開封を拒否していたが、
        // 回復可能性を残すため開封自体は許可する。
        guard let group = allGroupsForLookup().first(where: { $0.groupIdHex == groupIdHex }) else {
            openingGroupInFlight = false
            AppLogger.log("MLS", "TalkVM.openGroup: group not found in local list")
            return
        }

        // First phase must be quick: open the tapped/collapsed group so the Talk screen
        // can start. Sending is allowed while catch-up continues in the background.
        messagesLoading = true
        activeGroup = group
        openingGroupInFlight = false
        startPolling(groupIdHex: groupIdHex)
        var finalGroup = group
        var pollingGroupId = groupIdHex

        do {
            let localMsgs = await repository.getLocalMlsMessages(groupIdHex: groupIdHex)
            messages = dedupeMessages(localMsgs)
            messagesLoading = false
            AppLogger.log("MLS", "TalkVM.openGroup local-first messages=\(localMsgs.count) group=\(groupIdHex)")

            // Do not block opening on relay/MLS catch-up. The user-visible chat must
            // be local-first and send-capable immediately; catch-up runs in polling.
            var msgs = localMsgs

            // DM重複移行期 / WhiteNoise interop:
            // 同一相手との DM が複数ある場合、表示上の最新 group（自分だけが送った
            // 新規 orphan group）と、WhiteNoise Android が実際に参加している group が
            // ずれることがある。ここがずれると iOS では送信済みに見えても Android には
            // 出ない。開封時は常に local MDK history を見て「相手のメッセージが復号
            // できている group」を送信先として選ぶ。
            // ただし明示的な「新しくトークを作成」は fresh boundary。create path で
            // fresh group を pin 済みなら、旧 sibling 履歴を混ぜずに新規トークの空履歴
            // を維持する（送信先だけ canonical pin として安定化）。
            let requestedConversationKey = conversationKey(group)
            let isExplicitFreshPinnedDm = group.isDm && freshCreatedDmGroupIds.contains(group.groupIdHex)
            if isExplicitFreshPinnedDm {
                AppLogger.log("MLS", "TalkVM.openGroup: fresh pinned DM; skip sibling merge group=\(groupIdHex)")
                canonicalDmGroupByConversationKey[requestedConversationKey] = group.groupIdHex
            } else {
                let siblingGroupsAll = siblingConversationGroups(for: group).sorted { lhs, rhs in
                    if lhs.lastMessageTime != rhs.lastMessageTime { return lhs.lastMessageTime > rhs.lastMessageTime }
                    return lhs.groupIdHex < rhs.groupIdHex
                }

                let maxSiblingScan = 12
                let siblingGroups = Array(siblingGroupsAll.prefix(maxSiblingScan))
                AppLogger.log("MLS", "TalkVM.openGroup: canonical scan requested=\(groupIdHex) key=\(String(conversationKey(group).prefix(18))) total=\(siblingGroupsAll.count) scanned=\(siblingGroups.count)")

                var localMessagesByGroup: [(group: MlsGroup, messages: [MlsMessage])] = []
                var seenGroupIds = Set<String>()
                for candidate in ([group] + siblingGroups) where seenGroupIds.insert(candidate.groupIdHex).inserted {
                    let local = candidate.groupIdHex == groupIdHex ? msgs : await self.repository.getLocalMlsMessages(groupIdHex: candidate.groupIdHex)
                    localMessagesByGroup.append((candidate, local))
                }

                // Cache-first means all local sibling histories are painted before any
                // relay-backed repair/canonical scan. This is especially important after
                // cold launch: the user should see SQLite history immediately, while MLS
                // catch-up runs as a background refinement.
                let cacheFirstMessages = localMessagesByGroup.flatMap { $0.messages }
                if shouldReplaceMessages(current: messages, incoming: cacheFirstMessages) {
                    messages = dedupeMessages(cacheFirstMessages)
                }
                messagesLoading = false
                AppLogger.log("MLS", "TalkVM.openGroup cache-first sibling local messages=\(cacheFirstMessages.count) groups=\(localMessagesByGroup.count) requested=\(groupIdHex)")

                var allMessagesByGroup: [(group: MlsGroup, messages: [MlsMessage], fetchOk: Bool)] = []
                for snapshot in localMessagesByGroup {
                    let candidate = snapshot.group
                    do {
                        var local = snapshot.messages
                        // If a DM has no peer message locally, do a real relay/MDK repair pass
                        // before deciding it is an orphan. WhiteNoise often has already sent
                        // the first message (e.g. group_id shown in Android logs) while iOS has
                        // not replayed that kind:445 yet. Choosing based on local-empty history
                        // sends replies to the wrong group. Keep this at 30s: prior
                        // successful WhiteNoise interop logs showed decrypt completing
                        // just after the old 10s timeout, and short timeouts caused UI
                        // failure exactly when MDK history became available.
                        if local.filter({ $0.senderPubkey != myPubkeyHex }).isEmpty {
                            do {
                                local = try await withTimeout(seconds: 30.0) {
                                    try await self.repository.fetchMlsMessages(groupIdHex: candidate.groupIdHex, repairFull: true)
                                }
                                AppLogger.log("MLS", "TalkVM.openGroup: DM canonical repair group=\(candidate.groupIdHex) messages=\(local.count) peer=\(local.filter { $0.senderPubkey != self.myPubkeyHex }.count)")
                            } catch {
                                AppLogger.log("MLS", "TalkVM.openGroup: DM canonical repair failed group=\(candidate.groupIdHex) err=\(self.normalizeMlsError(error, fallback: "repair_failed"))")
                            }
                        }
                        allMessagesByGroup.append((candidate, local, true))
                    } catch {
                        allMessagesByGroup.append((candidate, snapshot.messages, false))
                        let fetchError = normalizeMlsError(error, fallback: "fetch_failed")
                        AppLogger.log("MLS", "TalkVM.openGroup: DM canonical fetch failed group=\(candidate.groupIdHex) err=\(fetchError)")
                    }
                }

                struct DmCanonicalScore {
                    let historyCount: Int
                    let partnerCount: Int
                    let myCount: Int
                    let latestPartnerTs: Int64
                    let latestAnyTs: Int64
                    let groupTs: Int64
                    let fetchOk: Bool
                }

                func score(_ entry: (group: MlsGroup, messages: [MlsMessage], fetchOk: Bool)) -> DmCanonicalScore {
                    let partnerMsgs = entry.messages.filter { $0.senderPubkey != myPubkeyHex }
                    let myMsgs = entry.messages.filter { $0.senderPubkey == myPubkeyHex }
                    return DmCanonicalScore(
                        historyCount: entry.messages.count,
                        partnerCount: partnerMsgs.count,
                        myCount: myMsgs.count,
                        latestPartnerTs: partnerMsgs.map { $0.timestamp }.max() ?? 0,
                        latestAnyTs: entry.messages.map { $0.timestamp }.max() ?? 0,
                        groupTs: entry.group.lastMessageTime,
                        fetchOk: entry.fetchOk
                    )
                }

                for entry in allMessagesByGroup {
                    let s = score(entry)
                    let marker = entry.group.groupIdHex == groupIdHex ? "requested" : "sibling"
                    AppLogger.log("MLS", "TalkVM.openGroup: DM canonical stats role=\(marker) group=\(entry.group.groupIdHex) fetchOk=\(s.fetchOk) history=\(s.historyCount) partnerMessages=\(s.partnerCount) myMessages=\(s.myCount) latestPartnerTs=\(s.latestPartnerTs) latestAnyTs=\(s.latestAnyTs) lastMessageTime=\(s.groupTs)")
                }

                if let best = allMessagesByGroup.max(by: { lhs, rhs in
                    let l = score(lhs)
                    let r = score(rhs)
                    // WhiteNoise interop priority: any group with peer/partner messages wins
                    // over a newer self-only duplicate group, because only the former proves
                    // both clients share the MLS state and group id.
                    if (l.partnerCount > 0) != (r.partnerCount > 0) { return l.partnerCount == 0 && r.partnerCount > 0 }
                    if l.latestPartnerTs != r.latestPartnerTs { return l.latestPartnerTs < r.latestPartnerTs }
                    if l.historyCount != r.historyCount { return l.historyCount < r.historyCount }
                    if l.latestAnyTs != r.latestAnyTs { return l.latestAnyTs < r.latestAnyTs }
                    return l.groupTs < r.groupTs
                }) {
                    let bestScore = score(best)
                    if best.group.groupIdHex != groupIdHex {
                        finalGroup = best.group
                        pollingGroupId = best.group.groupIdHex
                        canonicalDmGroupByConversationKey[conversationKey(best.group)] = best.group.groupIdHex
                        AppLogger.log("MLS", "TalkVM.openGroup: canonical DM remap requested=\(groupIdHex) selected=\(best.group.groupIdHex) partnerMessages=\(bestScore.partnerCount) myMessages=\(bestScore.myCount) partnerTs=\(bestScore.latestPartnerTs) anyTs=\(bestScore.latestAnyTs)")
                    } else {
                        canonicalDmGroupByConversationKey[conversationKey(best.group)] = best.group.groupIdHex
                        AppLogger.log("MLS", "TalkVM.openGroup: canonical DM keep group=\(groupIdHex) partnerMessages=\(bestScore.partnerCount) myMessages=\(bestScore.myCount) partnerTs=\(bestScore.latestPartnerTs) anyTs=\(bestScore.latestAnyTs)")
                    }
                    // Display all local histories that share the participant key. The selected
                    // best group remains the send/poll target, avoiding orphan sends while keeping
                    // old groupId messages visible in the same Talk.
                    msgs = allMessagesByGroup.flatMap { $0.messages }
                }
            }

            if finalGroup.isDm, await repository.hasMlsStateGaps(groupIdHex: finalGroup.groupIdHex) {
                finalGroup = await recoverGapDmOnOpenIfNeeded(finalGroup)
                msgs = await repository.getLocalMlsMessages(groupIdHex: finalGroup.groupIdHex)
            }
            activeGroup = finalGroup
            messages = dedupeMessages(msgs)
            // Do not surface a timeout/error banner for slow sync. Empty local history is
            // still a valid opened chat; polling will update when MLS catches up.
            error = nil
            AppLogger.log("MLS", "TalkVM.openGroup local-ready: messages=\(msgs.count) active=\(finalGroup.groupIdHex)")
            // Issue #183: surface any cached recovery classification for this
            // group so a previously-detected NotRecoverable banner re-appears
            // when the user re-enters the same DM. The banner is cleared by
            // recreateActiveDmConversation() on success.
            let cachedRecovery = await repository.mlsRecoveryStatusFor(groupIdHex: finalGroup.groupIdHex)
            recoveryStatus = (cachedRecovery == .notRecoverable) ? .notRecoverable : nil
            recreatingConversation = false
            messagesLoading = false
            startPolling(groupIdHex: pollingGroupId)
        } catch {
            AppLogger.log("MLS", "TalkVM.openGroup error: \(error)")
            messagesLoading = false

            // Keep the selected group open even if local read fails, so Talk can start.
            activeGroup = group
            if messages.isEmpty { messages = [] }
            startPolling(groupIdHex: groupIdHex)

            // Same recovery UX as sendMessage(): if this DM is stuck, recreate to a fresh group.
            if let mlsErr = error as? MlsError,
               case .groupStateStuck = mlsErr,
               group.isDm {
                // 非fatal化: 旧グループを閉じずに表示継続し、次ポーリングでの回復を待つ。
                self.error = "同期中です。しばらく待って再読込してください"
            } else {
                // Opening is local-first. Slow relay sync is intentionally silent.
                AppLogger.log("MLS", "TalkVM.openGroup local-first nonfatal error hidden from UI: \(error)")
            }
        }
    }

    func closeGroup() {
        pollingTask?.cancel()
        pollingTask  = nil
        activeGroup  = nil
        messages     = []
        // Issue #183: drop the per-group recovery banner state on close.
        recoveryStatus = nil
        recreatingConversation = false
        // NOTE: keep error banner until user explicitly closes it.
    }

    /// Issue #183: user accepted "作り直す" on the recovery banner.
    /// Leaves the unrecoverable DM and opens a fresh one with the same peer.
    /// No-op when the active group is not a DM or has no recoverable partner pubkey.
    func recreateActiveDmConversation() async {
        guard let current = activeGroup, current.isDm else { return }
        guard let partner = current.memberPubkeys.first(where: { $0 != myPubkeyHex }) else {
            self.error = "相手の公開鍵が解決できません"
            return
        }
        if recreatingConversation { return }
        recreatingConversation = true
        defer { recreatingConversation = false }

        do {
            let fresh = try await withTimeout(seconds: 60.0) {
                await self.repository.recreateDmConversation(
                    oldGroupIdHex: current.groupIdHex,
                    partnerPubkey: partner,
                    myPubkeyHex: self.myPubkeyHex
                )
            }
            guard let fresh = fresh else {
                self.error = "会話を作り直せませんでした。相手の鍵情報を取得できない可能性があります。"
                return
            }

            // Refresh the group list so the new DM shows in the list, then jump into it.
            let groups = (try? await repository.fetchMlsGroups(myPubkeyHex: myPubkeyHex)) ?? []
            self.allMlsGroups = groups
            self.groups = self.collapseDuplicateConversationGroups(groups)

            pollingTask?.cancel()
            pollingTask = nil
            self.activeGroup     = fresh
            self.messages        = []
            self.messagesLoading = true
            self.recoveryStatus  = nil
            self.error           = nil

            let initial = (try? await repository.fetchMlsMessages(groupIdHex: fresh.groupIdHex, repairFull: false)) ?? []
            self.messages        = self.dedupeMessages(initial)
            self.messagesLoading = false
            startPolling(groupIdHex: fresh.groupIdHex)
        } catch {
            setNormalizedError(error, fallback: "会話を作り直せませんでした", context: "recreateDm")
        }
    }

    /// Issue #183: user dismissed the banner without recreating.
    /// Hides the banner; the next failed catch-up re-surfaces it.
    func dismissRecoveryBanner() {
        recoveryStatus = nil
    }

    // MARK: - Timeout Helper

    private enum TalkTimeoutError: LocalizedError, Equatable {
        case timedOut
        var errorDescription: String? { "送信がタイムアウトしました" }
    }

    private func withTimeout<T>(seconds: Double, operation: @escaping @Sendable () async throws -> T) async throws -> T {
        try await withThrowingTaskGroup(of: Result<T, Error>.self) { group in
            group.addTask {
                do { return .success(try await operation()) }
                catch { return .failure(error) }
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                return .failure(TalkTimeoutError.timedOut)
            }

            let first = try await group.next()!
            group.cancelAll()

            switch first {
            case .success(let value): return value
            case .failure(let error): throw error
            }
        }
    }

    // MARK: - Live Polling (10s interval)

    /// アクティブグループのメッセージを 3 秒ごとにポーリング。
    /// Android: LaunchedEffect + collect flow に対応。
    private func recoverGapDmOnOpenIfNeeded(_ group: MlsGroup) async -> MlsGroup {
        if group.isDm, await repository.hasMlsStateGaps(groupIdHex: group.groupIdHex) {
            AppLogger.log("MLS", "TalkVM.recoverGapDmOnOpenIfNeeded suppressed auto fresh DM group=\(group.groupIdHex)")
        }
        return group
    }

    private func startPolling(groupIdHex: String) {
        pollingTask?.cancel()
        pollingTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                guard !Task.isCancelled, let self else { break }
                guard self.activeGroup?.groupIdHex == groupIdHex else { break }
                let siblingGroups = self.activeGroup.map { self.siblingConversationGroups(for: $0) } ?? []
                let targets = siblingGroups.isEmpty ? [groupIdHex] : Array(siblingGroups.map { $0.groupIdHex }.prefix(24))
                var merged: [MlsMessage] = []
                var fetchedCount = 0
                for target in targets {
                    let fetched = (try? await self.repository.fetchMlsMessages(groupIdHex: target)) ?? []
                    fetchedCount += fetched.count
                    merged += fetched
                }
                let normalized = self.dedupeMessages(self.messages + merged)
                var health = self.mlsPollHealth[groupIdHex] ?? MlsPollHealth()
                health.polls += 1; health.relaySweeps += 1; health.lastFetched = fetchedCount; health.lastMerged = normalized.count; health.lastRelaySweep = true; health.lastGap = await self.repository.hasMlsStateGaps(groupIdHex: groupIdHex)
                self.mlsPollHealth[groupIdHex] = health
                AppLogger.log("MLS", "TalkVM.poll group=\(groupIdHex) fetched=\(fetchedCount) merged=\(normalized.count) current=\(self.messages.count) siblings=\(targets.count) relayFetchAll=true")

                // Incoming Android-created DMs arrive as Welcome events, not as messages in
                // the currently opened (old) group. While a Talk is open, periodically refresh
                // the MLS group list too so a new shared group appears without requiring the
                // user to leave the screen or pull-to-refresh. Do not auto-switch here; show it
                // in the list with gid so the user can explicitly open the matching Android gid.
                if health.polls % 5 == 0 {
                    do {
                        let discovered = try await self.repository.fetchMlsGroups(myPubkeyHex: self.myPubkeyHex)
                        var byId: [String: MlsGroup] = [:]
                        for g in self.allMlsGroups + discovered { byId[g.groupIdHex] = g }
                        self.allMlsGroups = Array(byId.values)
                        self.groups = self.collapseDuplicateConversationGroups(self.allMlsGroups)
                        AppLogger.log("MLS", "TalkVM.poll discovery refresh groups=\(discovered.count) mergedGroups=\(self.groups.count)")
                    } catch {
                        AppLogger.log("MLS", "TalkVM.poll discovery refresh failed err=\(error)")
                    }
                }

                if let active = self.activeGroup, active.isDm, health.lastGap, !normalized.isEmpty {
                    _ = await self.recoverGapDmOnOpenIfNeeded(active)
                    break
                }
                if self.shouldReplaceMessages(current: self.messages, incoming: normalized) { self.messages = normalized }
            }
        }
    }

    func repairCurrentGroup() async {
        guard let group = activeGroup else { return }
        repairInFlightGroupIds.insert(group.groupIdHex)
        let repaired = await repository.repairMlsGroupHistory(groupIdHex: group.groupIdHex)
        repairInFlightGroupIds.remove(group.groupIdHex)
        if shouldReplaceMessages(current: messages, incoming: repaired) {
            messages = repaired
        }
        let peerCount = repaired.filter { $0.senderPubkey != myPubkeyHex }.count
        if repaired.count > 0 && peerCount == 0 {
            stuckGroupIds.insert(group.groupIdHex)
            error = "このトークの暗号状態が相手とずれています。旧トークへの送信を止め、新しいトークを作成してください。"
        } else {
            stuckGroupIds.remove(group.groupIdHex)
            error = nil
        }

        // Issue #183: if a DM still has an MLS state gap after standard repair,
        // escalate to deep peer-epoch catch-up. Rust replays cached + freshly
        // fetched Kind-445 wrappers; on NotRecoverable we surface the banner
        // (AC1/AC2). Mirrors Android `repair source=…` escalation path.
        if group.isDm,
           activeGroup?.groupIdHex == group.groupIdHex,
           await repository.hasMlsStateGaps(groupIdHex: group.groupIdHex) {
            let deep = await repository.deepCatchUpMlsGroup(groupIdHex: group.groupIdHex)
            AppLogger.log(
                "MLS",
                "TalkVM.repair group=\(group.groupIdHex) escalated to deep catch-up " +
                "status=\(String(describing: deep?.status)) unresolved=\(String(describing: deep?.stillUnprocessable))"
            )
            if activeGroup?.groupIdHex == group.groupIdHex {
                switch deep?.status {
                case .notRecoverable:
                    recoveryStatus = .notRecoverable
                case .healthy, .recovering:
                    if recoveryStatus == .notRecoverable { recoveryStatus = nil }
                default:
                    break
                }
            }
        } else if group.isDm,
                  activeGroup?.groupIdHex == group.groupIdHex,
                  recoveryStatus == .notRecoverable,
                  !(await repository.hasMlsStateGaps(groupIdHex: group.groupIdHex)) {
            // Repair closed the gap on its own — drop the stale banner.
            recoveryStatus = nil
        }
    }

    private func recoverStuckDmIfNeeded(_ group: MlsGroup) async {
        guard group.isDm else { return }
        guard !dmRecoveryInFlight else { return }
        guard !autoRecoveredDmGroupIds.contains(group.groupIdHex) else { return }
        guard let partner = group.memberPubkeys.first(where: { $0 != myPubkeyHex }) else { return }

        dmRecoveryInFlight = true
        defer { dmRecoveryInFlight = false }

        autoRecoveredDmGroupIds.insert(group.groupIdHex)
        AppLogger.log("MLS", "TalkVM.recoverStuckDmIfNeeded start old=\(group.groupIdHex) partner=\(partner)")

        await repository.hideMlsGroupLocally(groupIdHex: group.groupIdHex)
        let siblings = siblingDmGroupIds(for: group, from: groups).filter { $0 != group.groupIdHex }
        for sid in siblings {
            await repository.hideMlsGroupLocally(groupIdHex: sid)
        }

        groups.removeAll { g in
            guard g.isDm else { return false }
            return g.memberPubkeys.contains(partner)
        }

        do {
            let fresh = try await repository.createMlsDmConversation(partnerPubkeyHex: partner, myPubkeyHex: myPubkeyHex)
            if !groups.contains(where: { $0.groupIdHex == fresh.groupIdHex }) {
                groups.insert(fresh, at: 0)
            }
            activeGroup = fresh
            messages = []
            startPolling(groupIdHex: fresh.groupIdHex)
            self.error = "旧DMの同期不整合を検出したため、新しいトークを作成しました。もう一度送信してください"
            AppLogger.log("MLS", "TalkVM.recoverStuckDmIfNeeded success old=\(group.groupIdHex) new=\(fresh.groupIdHex)")
        } catch {
            self.error = "同期不整合を検出しました。旧DMを退出して新しいトークを作成してください"
            AppLogger.log("MLS", "TalkVM.recoverStuckDmIfNeeded failed old=\(group.groupIdHex) err=\(error)")
        }
    }

    private func selectCanonicalDmGroup(_ group: MlsGroup, allowPinned: Bool = true) async -> (MlsGroup, String) {
        let key = conversationKey(group)
        let siblings = siblingConversationGroups(for: group)
        if allowPinned, let pinnedId = canonicalDmGroupByConversationKey[key], let pinned = siblings.first(where: { $0.groupIdHex == pinnedId }) { return (pinned, "canonical-pin") }
        var best = group
        var bestScore: [Int64] = [-1]
        for sibling in siblings {
            let local = await repository.getLocalMlsMessages(groupIdHex: sibling.groupIdHex)
            let peerCount = local.filter { $0.senderPubkey != myPubkeyHex }.count
            let myCount = local.filter { $0.senderPubkey == myPubkeyHex }.count
            let latestPeer = local.filter { $0.senderPubkey != myPubkeyHex }.map { $0.timestamp }.max() ?? 0
            let latestAny = local.map { $0.timestamp }.max() ?? 0
            let score: [Int64] = [peerCount > 0 ? 1 : 0, myCount > 0 ? 1 : 0, Int64(peerCount), latestPeer, Int64(myCount), Int64(local.count), latestAny, sibling.groupIdHex == group.groupIdHex ? 1 : 0]
            if bestScore.lexicographicallyPrecedes(score) { bestScore = score; best = sibling }
        }
        let bestLocal = await repository.getLocalMlsMessages(groupIdHex: best.groupIdHex)
        if bestLocal.contains(where: { $0.senderPubkey != myPubkeyHex }) { canonicalDmGroupByConversationKey[key] = best.groupIdHex }
        return (best, "peer-history")
    }

    func sendMessage(_ text: String) async {
        // Sending must remain possible during catch-up/sync. sendMlsMessage itself
        // validates whether the local MLS state can create an application message.
        if openingGroupInFlight || messagesLoading {
            AppLogger.log("MLS", "TalkVM.sendMessage: continuing during sync opening=\(openingGroupInFlight) messagesLoading=\(messagesLoading)")
        }
        guard var group = activeGroup, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            AppLogger.log("MLS", "TalkVM.sendMessage: skipped (no active group or empty text)")
            return
        }

        // Insert the optimistic bubble IMMEDIATELY before any heavy work
        // (DM canonicalize, catch-up, gap repair). The user must see their
        // message in the same frame as the tap — that is the entire premise
        // of the product name (ぬるぬる = ultra-smooth). The heavy work
        // below still runs; if a DM remap happens the optimistic message's
        // groupIdHex is rewritten in place so it stays visible in the canonical group.
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let tempId = "local_\(Date().timeIntervalSince1970)_\(UUID().uuidString)"
        let optimistic = MlsMessage(
            id: tempId,
            senderPubkey: myPubkeyHex,
            content: trimmed,
            timestamp: Int64(Date().timeIntervalSince1970),
            groupIdHex: group.groupIdHex
        )
        // NOTE: sendingMessage is intentionally NOT set here. The optimistic
        // bubble + cleared composer already confirm the send to the user.
        // The spinner on the send button reflects only the actual MLS send
        // call (below), never the pre-flight catch-up / repair / deep
        // catch-up chain — those can legitimately take tens of seconds
        // and must be invisible to the user. See docs/wiki/ui/android-ios-sync.md.
        lastMessageActivityAt = Date()
        messages.append(optimistic)

        func abortOptimisticSend(_ message: String) {
            messages.removeAll { $0.id == tempId }
            sendingMessage = false
            self.error = message
        }

        func keepOptimisticBubble(in groupIdHex: String) {
            var found = false
            for i in messages.indices where messages[i].id == tempId {
                found = true
                messages[i] = MlsMessage(
                    id: messages[i].id,
                    senderPubkey: messages[i].senderPubkey,
                    content: messages[i].content,
                    timestamp: messages[i].timestamp,
                    groupIdHex: groupIdHex,
                    senderProfile: messages[i].senderProfile
                )
            }
            if !found {
                messages.append(MlsMessage(
                    id: optimistic.id,
                    senderPubkey: optimistic.senderPubkey,
                    content: optimistic.content,
                    timestamp: optimistic.timestamp,
                    groupIdHex: groupIdHex,
                    senderProfile: optimistic.senderProfile
                ))
            }
        }

        // DM convergence: use a pinned canonical group when available; otherwise
        // prefer a sibling with peer history. Newest groupId alone is not stable.
        if group.isDm {
            let key = conversationKey(group)
            let siblings = siblingConversationGroups(for: group)
            let hadPinned = canonicalDmGroupByConversationKey[key] != nil
            var best = canonicalDmGroupByConversationKey[key].flatMap { pinned in siblings.first(where: { $0.groupIdHex == pinned }) } ?? group
            var bestMessages = messages.filter { $0.groupIdHex == best.groupIdHex || $0.groupIdHex.isEmpty }
            if !hadPinned {
                for sibling in siblings where sibling.groupIdHex != group.groupIdHex {
                    let local = await repository.getLocalMlsMessages(groupIdHex: sibling.groupIdHex)
                    let peerCount = local.filter { $0.senderPubkey != myPubkeyHex }.count
                    let bestPeerCount = bestMessages.filter { $0.senderPubkey != myPubkeyHex }.count
                    let latestPeer = local.filter { $0.senderPubkey != myPubkeyHex }.map { $0.timestamp }.max() ?? 0
                    let bestLatestPeer = bestMessages.filter { $0.senderPubkey != myPubkeyHex }.map { $0.timestamp }.max() ?? 0
                    if (peerCount > 0 && bestPeerCount == 0) ||
                        (peerCount > 0 && peerCount == bestPeerCount && latestPeer > bestLatestPeer) {
                        best = sibling
                        bestMessages = local
                    }
                }
            }
            canonicalDmGroupByConversationKey[key] = best.groupIdHex
            if best.groupIdHex != group.groupIdHex {
                AppLogger.log("MLS", "TalkVM.sendMessage: remap DM send target old=\(group.groupIdHex) new=\(best.groupIdHex) reason=\(hadPinned ? "canonical-pin" : "peer-history")")
                group = best
                activeGroup = best
                // Keep the optimistic bubble visible after canonical remap.
                keepOptimisticBubble(in: best.groupIdHex)
                startPolling(groupIdHex: best.groupIdHex)
            }
        }


        // Last-chance canonicalization: if the currently opened DM has no peer message
        // but sibling DM candidates exist, re-run openGroup's relay-backed selection before
        // creating a new kind:445. This prevents replies from going to iOS-only duplicate DMs.
        if group.isDm,
           messages.filter({ $0.senderPubkey != myPubkeyHex }).isEmpty,
           siblingDmGroupIds(for: group, from: allGroupsForLookup()).count > 1 {
            AppLogger.log("MLS", "TalkVM.sendMessage: preflight canonicalize DM group=\(group.groupIdHex)")
            await openGroup(group.groupIdHex)
            if let refreshed = activeGroup { group = refreshed }
        }
        if stuckGroupIds.contains(group.groupIdHex) {
            abortOptimisticSend("このトークは暗号状態がずれているため送信できません。新しいトークを作成してください。")
            AppLogger.log("MLS", "TalkVM.sendMessage blocked stuck group=\(group.groupIdHex)")
            return
        }

        // Best-effort relay catch-up before send. This must not block sending forever:
        // logs show stale retryable replay diagnostics even when local history is usable.
        // If catch-up succeeds, merge it; if it fails/times out, let mlsCreateMessage()
        // decide whether the current MDK state can actually send.
        do {
            let caughtUp = try await withTimeout(seconds: 3.0) {
                try await self.repository.fetchMlsMessages(groupIdHex: group.groupIdHex, repairFull: false)
            }
            let merged = dedupeMessages(messages + caughtUp)
            if shouldReplaceMessages(current: messages, incoming: merged) {
                messages = merged
            }
            AppLogger.log("MLS", "TalkVM.sendMessage fast preflight catchup ok group=\(group.groupIdHex) messages=\(caughtUp.count) peer=\(caughtUp.filter { $0.senderPubkey != self.myPubkeyHex }.count)")
        } catch {
            AppLogger.log("MLS", "TalkVM.sendMessage fast preflight catchup ignored group=\(group.groupIdHex) err=\(normalizeMlsError(error, fallback: "catchup_failed"))")
        }

        if group.isDm, await repository.hasMlsStateGaps(groupIdHex: group.groupIdHex) {
            canonicalDmGroupByConversationKey.removeValue(forKey: conversationKey(group))
            let (fallback, fallbackReason) = await selectCanonicalDmGroup(group, allowPinned: false)
            let fallbackHasGap = await repository.hasMlsStateGaps(groupIdHex: fallback.groupIdHex)
            if fallback.groupIdHex != group.groupIdHex && !fallbackHasGap {
                AppLogger.log("MLS", "TalkVM.sendMessage: gap canonical replaced old=\(group.groupIdHex) new=\(fallback.groupIdHex) reason=\(fallbackReason)")
                group = fallback; activeGroup = fallback; keepOptimisticBubble(in: fallback.groupIdHex); startPolling(groupIdHex: fallback.groupIdHex)
            } else if messages.count > 0 {
                AppLogger.log("MLS", "TalkVM.sendMessage ignoring residual MLS gap on established DM group=\(group.groupIdHex) fallback=\(fallback.groupIdHex) fallbackHasGap=\(fallbackHasGap) messages=\(messages.count)")
            } else {
                // Issue #183: standard repair/fallback could not close the gap. Escalate
                // to deep peer-epoch catch-up (Rust replays cached + freshly fetched
                // Kind-445 wrappers, AC1). If even that cannot recover the missing
                // Commit, surface the recovery banner (AC2) and abort the send.
                let deep = try? await withTimeout(seconds: 35.0) {
                    await self.repository.deepCatchUpMlsGroup(groupIdHex: group.groupIdHex)
                }
                if let deep = deep {
                    AppLogger.log(
                        "MLS",
                        "TalkVM.sendMessage deepCatchUp group=\(group.groupIdHex) status=\(deep.status) " +
                        "epoch=\(deep.epochBefore)->\(deep.epochAfter) " +
                        "commits=\(deep.commitsApplied) apps=\(deep.applicationMessagesApplied) " +
                        "unresolved=\(deep.stillUnprocessable)"
                    )
                    if deep.status == .notRecoverable {
                        recoveryStatus = .notRecoverable
                    } else if recoveryStatus == .notRecoverable {
                        recoveryStatus = nil
                    }
                }
                if await repository.hasMlsStateGaps(groupIdHex: group.groupIdHex) {
                    abortOptimisticSend("同期中です。相手に表示される状態を確認中です。少し待って再送してください")
                    AppLogger.log("MLS", "TalkVM.sendMessage abort unresolved MLS gap group=\(group.groupIdHex) fallback=\(fallback.groupIdHex) fallbackHasGap=\(fallbackHasGap)")
                    return
                }
            }
        }

        AppLogger.log("MLS", "TalkVM.sendMessage start: group=\(group.groupIdHex), len=\(text.count)")

        // Spinner only covers the actual MLS send. Healthy relays complete
        // this in well under a second; longer waits indicate a real network
        // issue and the spinner is the correct affordance for that.
        sendingMessage = true
        do {
            let msg = try await withTimeout(seconds: 30.0) {
                try await self.repository.sendMlsMessage(groupIdHex: group.groupIdHex, content: trimmed, myPubkeyHex: self.myPubkeyHex)
            }
            // Replace the optimistic bubble and defensively collapse any duplicate bubble.
            // Use the currently active group id (after canonical DM remap) as the UI group id.
            let uiMsg = MlsMessage(
                id: msg.id,
                senderPubkey: msg.senderPubkey,
                content: msg.content,
                timestamp: msg.timestamp,
                groupIdHex: group.groupIdHex,
                senderProfile: msg.senderProfile
            )
            messages.removeAll { existing in
                existing.id == tempId ||
                (existing.senderPubkey == uiMsg.senderPubkey &&
                 existing.content == uiMsg.content &&
                 abs(existing.timestamp - uiMsg.timestamp) <= 1)
            }
            messages.append(uiMsg)
            messages = dedupeMessages(messages)
            lastMessageActivityAt = Date()
            AppLogger.log("MLS", "TalkVM.sendMessage success group=\(group.groupIdHex)")
        } catch {
            // Stop false-positive UX: failed/timed-out send must not remain as sent bubble.
            messages.removeAll { $0.id == tempId }
            AppLogger.log("MLS", "TalkVM.sendMessage error: \(error)")

            if let mlsErr = error as? MlsError,
               case .groupStateStuck = mlsErr,
               group.isDm {
                // Never auto-create a replacement DM. Logs showed this creates a new
                // MLS group that WhiteNoise Android is not watching, so subsequent iOS
                // messages look sent locally but never appear remotely. Keep the shared
                // group and let explicit repair/catch-up resolve state gaps.
                let repaired = await repository.repairMlsGroupHistory(groupIdHex: group.groupIdHex)
                if shouldReplaceMessages(current: messages, incoming: repaired) { messages = repaired }
                self.error = "同期状態を修復しました。もう一度送信してください"
                AppLogger.log("MLS", "TalkVM.sendMessage groupStateStuck repaired_no_autocreate group=\(group.groupIdHex) messages=\(repaired.count)")
            } else if let te = error as? TalkTimeoutError, te == .timedOut {
                self.error = "送信がタイムアウトしました。通信状態を確認して再試行してください"
            } else {
                setNormalizedError(error, fallback: "送信保留中です。接続状態を確認して再試行してください", context: "sendMessage")
            }
        }
        sendingMessage = false
    }

    // MARK: - Group Management

    func showGroupInfoSheet()  { showGroupInfo    = true }
    func hideGroupInfo()       { showGroupInfo    = false }
    func showCreateGroupSheet(){ showCreateGroup  = true }
    func hideCreateGroup()     { showCreateGroup  = false }

    func leaveGroup() async {
        guard let group = activeGroup else { return }

        // まずローカル退出を確定し、失敗時/再起動/cache-first 表示での復活を防ぐ。
        await repository.markMlsGroupAsLeftLocally(groupIdHex: group.groupIdHex)
        freshCreatedDmGroupIds.remove(group.groupIdHex)

        // DM重複移行期では sibling も同時に退出済み扱いにして既存DM再利用を回避。
        if group.isDm {
            let siblingIds = siblingDmGroupIds(for: group, from: groups).filter { $0 != group.groupIdHex }
            for sid in siblingIds {
                await repository.markMlsGroupAsLeftLocally(groupIdHex: sid)
                freshCreatedDmGroupIds.remove(sid)
            }
            allMlsGroups.removeAll { g in conversationKey(g) == conversationKey(group) }
            groups.removeAll { g in conversationKey(g) == conversationKey(group) }
        } else {
            allMlsGroups.removeAll { $0.groupIdHex == group.groupIdHex }
            groups.removeAll { $0.groupIdHex == group.groupIdHex }
        }

        do {
            try await repository.leaveMlsGroup(groupIdHex: group.groupIdHex)
        } catch {
            // 非fatal: ローカルでは退出済み状態を維持する。
            AppLogger.log("MLS", "TalkVM.leaveGroup: leave publish failed (non-fatal): \(error)")
        }

        closeGroup()
        groups.removeAll { $0.groupIdHex == group.groupIdHex }
        allMlsGroups.removeAll { $0.groupIdHex == group.groupIdHex }
        showGroupInfo = false
    }

    /// グループにメンバーを追加する（管理者のみ）。
    func addMemberToGroup(groupIdHex: String, memberPubkey: String) async {
        do {
            try await repository.addMemberToGroup(groupIdHex: groupIdHex, memberPubkey: memberPubkey)
            // グループ一覧を更新してメンバーリストを反映
            await loadGroups()
        } catch MlsError.keyPackageNotFound {
            self.error = "相手のキーパッケージが見つかりません"
        } catch MlsError.invalidWelcomeEncoding {
            self.error = "Welcomeイベント形式が不正です（base64必須）"
        } catch MlsError.invalidWelcomeMissingKeyPackageRef {
            self.error = "Welcomeイベントに必要なKeyPackage参照がありません"
        } catch MlsError.invalidWelcomeRelays {
            self.error = "Welcomeイベントのrelay情報が不正です"
        } catch {
            setNormalizedError(error, fallback: "メンバーの追加に失敗しました", context: "addMemberToGroup")
        }
    }

    /// グループからメンバーを削除する（管理者のみ）。
    func removeMemberFromGroup(groupIdHex: String, memberPubkey: String) async {
        do {
            try await repository.removeMemberFromGroup(groupIdHex: groupIdHex, memberPubkey: memberPubkey)
            await loadGroups()
            // activeGroup のメンバーリストも即時更新
            if let idx = groups.firstIndex(where: { $0.groupIdHex == groupIdHex }) {
                activeGroup = groups[idx]
            }
        } catch {
            setNormalizedError(error, fallback: "メンバーの削除に失敗しました", context: "removeMemberFromGroup")
        }
    }

    // MARK: - DM / Group Creation

    func createDmConversation(pubkey: String) async {
        AppLogger.log("MLS", "TalkVM.createDmConversation start partner=\(pubkey)")
        do {
            // Interop safety: republish my latest key package / relay lists before starting DM.
            await repository.forceRepublishMyKeyPackageIfNeeded(myPubkeyHex: myPubkeyHex)
            // グループ未取得なら先にロードして既存DM検索可能にする (Android 同等)
            if allMlsGroups.isEmpty {
                let fetched = try await repository.fetchMlsGroups(myPubkeyHex: myPubkeyHex)
                allMlsGroups = fetched
                groups = collapseDuplicateConversationGroups(fetched)
            }
            // Explicit New Chat is now a force-fresh recovery action.
            // Existing local DMs with the same peer may be split from the other device,
            // so hide them locally and create one new shared MLS group.
            let existingDmGroups = allGroupsForLookup().filter { $0.isDm && $0.memberPubkeys.contains(pubkey) }
            if !existingDmGroups.isEmpty {
                AppLogger.log("MLS", "TalkVM.createDmConversation force fresh; hiding split existing groups=\(existingDmGroups.map { String($0.groupIdHex.prefix(12)) })")
                for g in existingDmGroups { await repository.hideMlsGroupLocally(groupIdHex: g.groupIdHex) }
                allMlsGroups.removeAll { $0.isDm && $0.memberPubkeys.contains(pubkey) }
                groups.removeAll { $0.isDm && $0.memberPubkeys.contains(pubkey) }
            }
            let group = try await repository.createMlsDmConversation(partnerPubkeyHex: pubkey, myPubkeyHex: myPubkeyHex)
            // Explicit New Talk is a hard reset for this DM key. Pin the freshly-created
            // group and keep only that visible conversation locally, otherwise the
            // canonical peer-history selector can remap the user back to the previous
            // sibling and make the new Talk inherit old history.
            canonicalDmGroupByConversationKey[conversationKey(group)] = group.groupIdHex
            freshCreatedDmGroupIds.insert(group.groupIdHex)
            allMlsGroups.removeAll { $0.isDm && $0.memberPubkeys.contains(pubkey) && $0.groupIdHex != group.groupIdHex }
            groups.removeAll { $0.isDm && $0.memberPubkeys.contains(pubkey) && $0.groupIdHex != group.groupIdHex }
            if !allMlsGroups.contains(where: { $0.groupIdHex == group.groupIdHex }) {
                allMlsGroups.insert(group, at: 0)
            }
            groups = collapseDuplicateConversationGroups(allMlsGroups)
            AppLogger.log("MLS", "TalkVM.createDmConversation created group=\(group.groupIdHex) freshPinned=true")
            await openGroup(group.groupIdHex)
        } catch {
            AppLogger.log("MLS", "TalkVM.createDmConversation failed err=\(error)")
            setNormalizedError(error, fallback: "トークの作成に失敗しました", context: "createDmConversation")
        }
    }

    func createGroupChat(name: String, members: [String]) async {
        hideCreateGroup()
        do {
            let group = try await repository.createMlsGroupChat(name: name, memberPubkeys: members, myPubkeyHex: myPubkeyHex)
            allMlsGroups.insert(group, at: 0)
            groups = collapseDuplicateConversationGroups(allMlsGroups)
            await openGroup(group.groupIdHex)
        } catch {
            setNormalizedError(error, fallback: "グループの作成に失敗しました", context: "createGroupChat")
        }
    }

    // MARK: - Following Profiles (for member picker)

    func loadFollowingProfiles() async {
        guard followingProfiles.isEmpty else { return }
        followingLoading = true
        let follows = await repository.fetchFollowList(pubkey: myPubkeyHex)
        let profiles = await repository.fetchProfiles(pubkeys: follows)
        followingProfiles = profiles
        followingLoading  = false
    }

    // MARK: - Message Diff

    /// Count 増減だけでなく、同件数でも内容・順序・IDが変わったら置き換える。
    private func shouldReplaceMessages(current: [MlsMessage], incoming: [MlsMessage]) -> Bool {
        let normalizedIncoming = dedupeMessages(incoming)
        if normalizedIncoming.count != incoming.count { return true }
        guard current.count == incoming.count else { return true }
        for (lhs, rhs) in zip(current, incoming) {
            if lhs.id != rhs.id { return true }
            if lhs.senderPubkey != rhs.senderPubkey { return true }
            if lhs.timestamp != rhs.timestamp { return true }
            if lhs.content != rhs.content { return true }
        }
        return false
    }

    private func dedupeMessages(_ input: [MlsMessage]) -> [MlsMessage] {
        var byKey: [String: MlsMessage] = [:]
        for m in input {
            // Same sender/content within the same second is the same UI message. This avoids
            // showing both the optimistic/sent bubble and the locally replayed MLS history copy.
            let key = "\(m.senderPubkey)|\(m.timestamp)|\(m.content.trimmingCharacters(in: .whitespacesAndNewlines))"
            byKey[key] = m
        }
        return Array(byKey.values).sorted { lhs, rhs in
            if lhs.timestamp != rhs.timestamp { return lhs.timestamp < rhs.timestamp }
            return lhs.id < rhs.id
        }
    }
}
