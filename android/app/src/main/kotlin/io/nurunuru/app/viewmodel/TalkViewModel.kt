package io.nurunuru.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.nurunuru.app.data.NostrClient
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.*
import io.nurunuru.app.data.models.DmConversation
import io.nurunuru.app.data.models.MlsGroup
import io.nurunuru.app.data.models.MlsMessage
import io.nurunuru.app.data.models.UserProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

/**
 * Android Talk ViewModel for Marmot MLS conversations.
 *
 * Mirrors the iOS TalkViewModel policy:
 * - Lazy group loading so Talk/Marmot relays do not block timeline startup.
 * - Local-first open, relay catch-up in background.
 * - DM duplicate/canonical selection prefers the group that has peer messages.
 * - Before send, replay/catch up the active group so outbound Kind-445 is created from a current epoch.
 * - Failed/timed-out sends remove optimistic bubbles.
 */
data class TalkUiState(
    val groups: List<MlsGroup> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val activeGroupId: String? = null,
    val activeGroup: MlsGroup? = null,
    val messages: List<MlsMessage> = emptyList(),
    val messagesLoading: Boolean = false,
    val sendingMessage: Boolean = false,
    // Legacy (read-only; Talk UI is Marmot MLS only)
    @Suppress("DEPRECATION")
    val legacyConversations: List<DmConversation> = emptyList(),
    val showLegacy: Boolean = false,
    // Group management UI state
    val showGroupInfo: Boolean = false,
    val showCreateGroup: Boolean = false,
    // Following list for member picker (loaded on demand)
    val followingProfiles: List<UserProfile> = emptyList(),
    val followingLoading: Boolean = false
)

class TalkViewModel(
    private val repository: NostrRepository,
    @Suppress("unused") private val nostrClient: NostrClient,
    private val myPubkeyHex: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(TalkUiState(isLoading = false))
    val uiState: StateFlow<TalkUiState> = _uiState.asStateFlow()

    private var messageStreamJob: Job? = null
    private var didInitialLoad = false
    private var loadGroupsInFlight = false
    private var openingGroupInFlight = false
    // Raw MLS groups from Rust/relays. UI collapses duplicate conversations by participant
    // set, but open/send must still be able to scan every sibling group id.
    private var allMlsGroups: List<MlsGroup> = emptyList()
    private val autoRecoveredDivergedDmPartners = mutableSetOf<String>()
    private val canonicalDmGroupByConversationKey = mutableMapOf<String, String>()
    private val mlsGroupRetryCooldownUntil = mutableMapOf<String, Long>()
    private val mlsPollHealth = mutableMapOf<String, MlsPollHealth>()
    private val mlsAutoRepairCooldownUntil = mutableMapOf<String, Long>()
    private val mlsRepairInFlight = mutableSetOf<String>()
    private var lastMessageActivityAt = System.currentTimeMillis()

    private data class MlsPollHealth(
        var polls: Int = 0,
        var relaySweeps: Int = 0,
        var errors: Int = 0,
        var lastFetched: Int = 0,
        var lastRelayFetched: Int = 0,
        var lastNormalized: Int = 0,
        var lastGapCount: Int = 0,
        var lastRelaySweep: Boolean = false,
        var consecutiveEmptyRelayFetches: Int = 0
    )


    /** Called by TalkScreen when the tab is actually shown. */
    fun loadGroupsIfNeeded() {
        if (didInitialLoad) return
        didInitialLoad = true
        loadGroups()
    }

    /**
     * キャッシュクリア後にUIを即時リセットして再取得する。
     * Rust MLS 状態から再構築されるため、退出済みグループは leftIds フィルタで除外される。
     */
    fun clearStateAfterCacheClear() {
        messageStreamJob?.cancel()
        messageStreamJob = null
        allMlsGroups = emptyList()
        _uiState.update {
            it.copy(
                groups = emptyList(),
                messages = emptyList(),
                activeGroupId = null,
                activeGroup = null,
                showGroupInfo = false
            )
        }
        didInitialLoad = false
        loadGroupsIfNeeded()
    }

    fun loadGroups() {
        if (loadGroupsInFlight) return
        loadGroupsInFlight = true
        viewModelScope.launch {
            val cachedRaw = repository.getCachedMlsGroups().sortedByDescending { it.lastMessageTime }
            allMlsGroups = cachedRaw
            _uiState.update { it.copy(groups = collapseConversationGroups(cachedRaw), isLoading = true, error = null) }
            try {
                val fetchedRaw = repository.fetchMlsGroups().sortedByDescending { it.lastMessageTime }
                allMlsGroups = fetchedRaw
                val fetched = collapseConversationGroups(fetchedRaw)
                _uiState.update { state ->
                    val active = state.activeGroup?.let { current ->
                        allMlsGroups.firstOrNull { it.groupIdHex == current.groupIdHex }
                            ?: remapCollapsedConversation(current, fetched)
                            ?: current
                    }
                    state.copy(groups = fetched, activeGroup = active, isLoading = false)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = normalizeMlsError(e, "トークの読み込みに失敗しました"), isLoading = false) }
            } finally {
                loadGroupsInFlight = false
            }
        }
    }

    fun openGroup(groupIdHex: String) {
        if (openingGroupInFlight) return
        val requested = allGroupsForLookup().firstOrNull { it.groupIdHex == groupIdHex } ?: return
        openingGroupInFlight = true
        _uiState.update {
            it.copy(
                activeGroupId = requested.groupIdHex,
                activeGroup = requested,
                messagesLoading = true,
                error = null
            )
        }
        startMessageStream(requested.groupIdHex)

        viewModelScope.launch {
            try {
                var finalGroup = requested
                var finalMessages = repository.getLocalMlsMessages(requested.groupIdHex)
                if (finalMessages.isNotEmpty()) {
                    _uiState.update { it.copy(messages = dedupeMessages(finalMessages), messagesLoading = false) }
                }

                // UX rule: group id changes must not create a separate Talk when the
                // participant public keys are the same. Scan sibling group ids with the
                // same conversation key, choose the best send target, and merge all local
                // histories into one visible Talk.
                val candidates = siblingConversationGroups(requested)
                    .sortedWith(compareByDescending<MlsGroup> { it.lastMessageTime }.thenBy { it.groupIdHex })
                    .take(12)

                val scanned = candidates.map { candidate ->
                    var local = if (candidate.groupIdHex == requested.groupIdHex) {
                        finalMessages
                    } else {
                        repository.getLocalMlsMessages(candidate.groupIdHex)
                    }
                    if (local.none { it.senderPubkey != myPubkeyHex }) {
                        try {
                            local = withTimeout(30_000) {
                                repository.fetchMlsMessages(candidate.groupIdHex, repairFull = true)
                            }
                        } catch (_: Exception) {
                            local
                        }
                    }
                    candidate to local
                }

                scanned.maxWithOrNull(compareBy<Pair<MlsGroup, List<MlsMessage>>> { entry ->
                    entry.second.count { it.senderPubkey != myPubkeyHex }
                }.thenBy { entry ->
                    entry.second.filter { it.senderPubkey != myPubkeyHex }.maxOfOrNull { it.timestamp } ?: 0L
                }.thenBy { entry ->
                    entry.second.size
                }.thenBy { entry ->
                    entry.second.maxOfOrNull { it.timestamp } ?: 0L
                }.thenBy { entry ->
                    entry.first.lastMessageTime
                })?.let { best ->
                    finalGroup = best.first
                }
                finalMessages = scanned.flatMap { it.second }
                if (finalGroup.isDm) {
                }

                if (finalGroup.isDm && repository.mlsStateGapCount(finalGroup.groupIdHex) > 0) {
                    finalGroup = recoverGapDmOnOpenIfNeeded(finalGroup)
                    finalMessages = repository.getLocalMlsMessages(finalGroup.groupIdHex)
                }
                _uiState.update {
                    it.copy(
                        activeGroupId = finalGroup.groupIdHex,
                        activeGroup = finalGroup,
                        messages = dedupeMessages(finalMessages),
                        messagesLoading = false,
                        error = null
                    )
                }
                startMessageStream(finalGroup.groupIdHex)
            } catch (e: Exception) {
                _uiState.update { it.copy(messagesLoading = false) }
            } finally {
                openingGroupInFlight = false
            }
        }
    }

    fun closeGroup() {
        messageStreamJob?.cancel()
        messageStreamJob = null
        _uiState.update {
            it.copy(
                activeGroupId = null,
                activeGroup = null,
                messages = emptyList(),
                showGroupInfo = false
            )
        }
    }

    private suspend fun selectCanonicalDmGroup(group: MlsGroup, allowPinned: Boolean = true): Pair<MlsGroup, String> {
        val key = conversationKey(group)
        val siblings = siblingConversationGroups(group)
        val pinned = if (allowPinned) canonicalDmGroupByConversationKey[key]?.let { pinnedId -> siblings.firstOrNull { it.groupIdHex == pinnedId } } else null
        if (pinned != null) return pinned to "canonical-pin"
        val best = siblings.map { candidate ->
            val local = repository.getLocalMlsMessages(candidate.groupIdHex)
            val peerCount = local.count { it.senderPubkey != myPubkeyHex }
            val myCount = local.count { it.senderPubkey == myPubkeyHex }
            val latestPeer = local.filter { it.senderPubkey != myPubkeyHex }.maxOfOrNull { it.timestamp } ?: 0L
            val latestAny = local.maxOfOrNull { it.timestamp } ?: 0L
            candidate to listOf(if (peerCount > 0) 1L else 0L, if (myCount > 0) 1L else 0L, peerCount.toLong(), latestPeer, myCount.toLong(), local.size.toLong(), latestAny, if (candidate.groupIdHex == group.groupIdHex) 1L else 0L)
        }.maxWithOrNull { a, b ->
            val av = a.second; val bv = b.second
            for (i in av.indices) { val c = av[i].compareTo(bv[i]); if (c != 0) return@maxWithOrNull c }
            a.first.groupIdHex.compareTo(b.first.groupIdHex)
        }?.first ?: group
        val bestLocal = repository.getLocalMlsMessages(best.groupIdHex)
        if (bestLocal.any { it.senderPubkey != myPubkeyHex }) canonicalDmGroupByConversationKey[key] = best.groupIdHex
        return best to "peer-history"
    }

    fun sendMessage(groupIdHex: String, content: String) {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return

        viewModelScope.launch {
            var group = _uiState.value.activeGroup ?: allGroupsForLookup().firstOrNull { it.groupIdHex == groupIdHex } ?: return@launch

            // DM convergence: prefer a gap-free pinned canonical group or the
            // sibling that already has peer messages. Gap-bearing pins are invalidated.
            if (group.isDm) {
                val (best, reason) = selectCanonicalDmGroup(group, allowPinned = true)
                if (best.groupIdHex != group.groupIdHex) {
                    android.util.Log.w("TalkVM", "sendMessage: remap DM send target old=" + group.groupIdHex + " new=" + best.groupIdHex + " reason=" + reason)
                    group = best
                    _uiState.update { it.copy(activeGroupId = best.groupIdHex, activeGroup = best) }
                    startMessageStream(best.groupIdHex)
                }
            }


            // Last-chance DM canonicalization: do not send to a self-only orphan duplicate.
            if (group.isDm && _uiState.value.messages.none { it.senderPubkey != myPubkeyHex } && siblingDmGroupIds(group).size > 1) {
                openGroup(group.groupIdHex)
                delay(300)
                group = _uiState.value.activeGroup ?: group
            }

            // Catch up before send. If the current DM still has state gaps, never
            // send into the old split epoch: that creates messages visible only locally.
            try {
                val preGapCount = repository.mlsStateGapCount(group.groupIdHex)
                val caughtUp = withTimeout(15_000) {
                    // Send preflight must be non-destructive. A full repair replay clears
                    // pending state and replays old wrappers; logs showed this created a
                    // large transient gap immediately before Android sent, producing
                    // kind:445 messages that iOS fetched but could only report as
                    // state_not_ready. Normal polling/receive already keeps this group
                    // current; do an incremental catch-up here and let remaining
                    // non-stale gaps block the send rather than creating a ghost bubble.
                    repository.fetchMlsMessages(group.groupIdHex, repairFull = false)
                }
                if (shouldReplaceMessages(_uiState.value.messages, caughtUp)) {
                    _uiState.update { it.copy(messages = dedupeMessages(_uiState.value.messages + caughtUp)) }
                }
                var postGapCount = repository.mlsStateGapCount(group.groupIdHex)
                val partner = group.memberPubkeys.firstOrNull { it != myPubkeyHex }
                // If incremental polling still sees a gap, do one relay-backed full replay
                // before deciding to block. Logs from 23:10 showed Android had usable peer
                // history but kept two stale state_not_ready wrappers in the retry queue,
                // causing send abort while iOS considered the same group gap-free.
                if (group.isDm && partner != null && postGapCount > 0) {
                    val repaired = withTimeout(30_000) {
                        repository.fetchMlsMessages(group.groupIdHex, repairFull = true)
                    }
                    if (shouldReplaceMessages(_uiState.value.messages, repaired)) {
                        _uiState.update { it.copy(messages = dedupeMessages(_uiState.value.messages + repaired)) }
                    }
                    val repairedGap = repository.mlsStateGapCount(group.groupIdHex)
                    android.util.Log.d(
                        "TalkVM",
                        "sendMessage: fullRepairBeforeBlock group=" + group.groupIdHex +
                            " beforeGap=" + postGapCount + " afterGap=" + repairedGap +
                            " repaired=" + repaired.size
                    )
                    postGapCount = repairedGap
                }
                val gapCount = postGapCount
                android.util.Log.d(
                    "TalkVM",
                    "sendMessage: preflight group=" + group.groupIdHex +
                        " preGap=" + preGapCount + " postGap=" + postGapCount + " gapCount=" + gapCount
                )
                if (group.isDm && partner != null && gapCount > 0) {
                    canonicalDmGroupByConversationKey.remove(conversationKey(group))
                    val (fallback, fallbackReason) = selectCanonicalDmGroup(group, allowPinned = false)
                    val fallbackGap = repository.mlsStateGapCount(fallback.groupIdHex)
                    if (fallback.groupIdHex != group.groupIdHex && fallbackGap == 0) {
                        android.util.Log.w("TalkVM", "sendMessage: gap canonical replaced old=" + group.groupIdHex + " new=" + fallback.groupIdHex + " reason=" + fallbackReason)
                        group = fallback
                        _uiState.update { it.copy(activeGroupId = fallback.groupIdHex, activeGroup = fallback) }
                        startMessageStream(fallback.groupIdHex)
                    } else {
                        val peerHistoryCount = _uiState.value.messages.count { it.senderPubkey != myPubkeyHex }
                        val localHistoryCount = _uiState.value.messages.size
                        if (peerHistoryCount > 0 || localHistoryCount > 0) {
                            // Keep using the established DM. A residual gap here is usually an old relay replay;
                            // creating a new group makes iOS/Android watch different talks.
                            android.util.Log.w(
                                "TalkVM",
                                "sendMessage: ignoring residual MLS gap on established DM group=" + group.groupIdHex +
                                    " gapCount=" + gapCount + " fallback=" + fallback.groupIdHex +
                                    " fallbackGap=" + fallbackGap + " peerHistory=" + peerHistoryCount +
                                    " localHistory=" + localHistoryCount
                            )
                        } else {
                            _uiState.update { it.copy(error = "同期中です。少し待って再送してください") }
                            android.util.Log.w(
                                "TalkVM",
                                "sendMessage: abort empty DM unresolved MLS gap group=" + group.groupIdHex +
                                    " gapCount=" + gapCount + " fallback=" + fallback.groupIdHex +
                                    " fallbackGap=" + fallbackGap
                            )
                            return@launch
                        }
                    }
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(error = "同期中です。少し待ってから再送してください") }
                return@launch
            }


            lastMessageActivityAt = System.currentTimeMillis()
            val tempId = "local_${System.currentTimeMillis()}"
            val optimistic = MlsMessage(
                id = tempId,
                senderPubkey = myPubkeyHex,
                content = trimmed,
                timestamp = System.currentTimeMillis() / 1000,
                groupIdHex = group.groupIdHex
            )
            _uiState.update { it.copy(sendingMessage = true, messages = dedupeMessages(it.messages + optimistic)) }

            try {
                val success = withTimeout(30_000) { repository.sendMlsMessage(group.groupIdHex, trimmed) }
                if (success) {
                    lastMessageActivityAt = System.currentTimeMillis()
                    val latest = if (group.isDm) {
                        siblingConversationGroups(group).flatMap { g ->
                            if (g.groupIdHex == group.groupIdHex) {
                                repository.getLocalMlsMessages(g.groupIdHex).ifEmpty { repository.fetchMlsMessages(g.groupIdHex) }
                            } else {
                                repository.getLocalMlsMessages(g.groupIdHex)
                            }
                        }
                    } else {
                        repository.getLocalMlsMessages(group.groupIdHex).ifEmpty {
                            repository.fetchMlsMessages(group.groupIdHex)
                        }
                    }
                    _uiState.update { it.copy(messages = dedupeMessages(latest), error = null) }
                } else {
                    _uiState.update {
                        it.copy(
                            messages = it.messages.filterNot { msg -> msg.id == tempId },
                            error = "送信保留中です。接続状態を確認して再試行してください"
                        )
                    }
                }
            } catch (e: TimeoutCancellationException) {
                _uiState.update {
                    it.copy(
                        messages = it.messages.filterNot { msg -> msg.id == tempId },
                        error = "送信がタイムアウトしました。通信状態を確認して再試行してください"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        messages = it.messages.filterNot { msg -> msg.id == tempId },
                        error = normalizeMlsError(e, "送信保留中です。接続状態を確認して再試行してください")
                    )
                }
            } finally {
                _uiState.update { it.copy(sendingMessage = false) }
            }
        }
    }

    fun createDmConversation(partnerPubkey: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                // Interop safety: publish/refresh my KeyPackage and Marmot relay lists first.
                repository.forceRepublishMyKeyPackageIfNeeded()

                val currentGroups = if (allMlsGroups.isEmpty()) {
                    val fetched = repository.fetchMlsGroups()
                    allMlsGroups = fetched
                    _uiState.update { it.copy(groups = collapseConversationGroups(fetched)) }
                    fetched
                } else {
                    allMlsGroups
                }
                val existingDmGroups = currentGroups.filter { g -> g.isDm && g.memberPubkeys.contains(partnerPubkey) }
                if (existingDmGroups.isNotEmpty()) {
                    android.util.Log.w("TalkVM", "createDmConversation: force fresh DM; hiding split existing groups=" + existingDmGroups.map { it.groupIdHex.take(12) })
                    existingDmGroups.forEach { repository.hideMlsGroupLocally(it.groupIdHex) }
                    allMlsGroups = currentGroups.filterNot { g -> g.isDm && g.memberPubkeys.contains(partnerPubkey) }
                }

                val group = repository.createDmGroup(partnerPubkey)
                if (group != null) {
                    allMlsGroups = listOf(group)
                    _uiState.update { it.copy(groups = listOf(group), isLoading = false) }
                    openGroup(group.groupIdHex)
                } else {
                    _uiState.update { it.copy(isLoading = false, error = "相手のキーパッケージが見つかりません") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = normalizeMlsError(e, "トークの作成に失敗しました"), isLoading = false) }
            }
        }
    }

    fun createGroupChat(name: String, memberPubkeys: List<String>) {
        if (name.isBlank() || memberPubkeys.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, showCreateGroup = false) }
            try {
                val group = repository.createGroupChat(name, memberPubkeys)
                if (group != null) {
                    allMlsGroups = (listOf(group) + allGroupsForLookup()).distinctBy { it.groupIdHex }
                    val groups = collapseConversationGroups(allMlsGroups)
                    _uiState.update { it.copy(groups = groups, isLoading = false) }
                    openGroup(group.groupIdHex)
                } else {
                    _uiState.update { it.copy(isLoading = false, error = "グループの作成に失敗しました") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = normalizeMlsError(e, "グループの作成に失敗しました"), isLoading = false) }
            }
        }
    }

    fun leaveGroup() {
        val group = _uiState.value.activeGroup ?: return
        viewModelScope.launch {
            // First hide locally to prevent resurrection if leave publish fails.
            repository.hideMlsGroupLocally(group.groupIdHex)
            if (group.isDm) {
                siblingDmGroupIds(group).filter { it != group.groupIdHex }.forEach { repository.hideMlsGroupLocally(it) }
            }
            try {
                repository.leaveGroup(group.groupIdHex)
            } catch (_: Exception) {
                // Non-fatal: local hide remains authoritative for UI.
            }
            val partner = group.memberPubkeys.firstOrNull { it != myPubkeyHex }
            val groups = if (group.isDm && partner != null) {
                allGroupsForLookup().filterNot { it.isDm && it.memberPubkeys.contains(partner) }
            } else {
                allGroupsForLookup().filter { it.groupIdHex != group.groupIdHex }
            }
            allMlsGroups = groups
            _uiState.update {
                it.copy(
                    groups = collapseConversationGroups(groups),
                    activeGroupId = null,
                    activeGroup = null,
                    messages = emptyList(),
                    showGroupInfo = false
                )
            }
        }
    }

    fun addMember(memberPubkey: String) {
        val groupIdHex = _uiState.value.activeGroupId ?: return
        viewModelScope.launch {
            try {
                val success = repository.addMemberToGroup(groupIdHex, memberPubkey)
                if (success) {
                    val groups = repository.fetchMlsGroups()
                    val activeGroup = groups.firstOrNull { it.groupIdHex == groupIdHex }
                    _uiState.update { it.copy(groups = groups, activeGroup = activeGroup) }
                } else {
                    _uiState.update { it.copy(error = "メンバーの追加に失敗しました") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = normalizeMlsError(e, "メンバーの追加に失敗しました")) }
            }
        }
    }

    fun removeMember(memberPubkey: String) {
        val groupIdHex = _uiState.value.activeGroupId ?: return
        viewModelScope.launch {
            try {
                val success = repository.removeMemberFromGroup(groupIdHex, memberPubkey)
                if (success) {
                    val groups = repository.fetchMlsGroups()
                    val activeGroup = groups.firstOrNull { it.groupIdHex == groupIdHex }
                    _uiState.update { it.copy(groups = groups, activeGroup = activeGroup) }
                } else {
                    _uiState.update { it.copy(error = "メンバーの削除に失敗しました") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = normalizeMlsError(e, "メンバーの削除に失敗しました")) }
            }
        }
    }

    private suspend fun recoverGapDmOnOpenIfNeeded(group: MlsGroup): MlsGroup {
        // Do not auto-create a new DM on transient/replayed MLS gaps.
        // Auto fresh-DM churn caused Android to move to a group iOS was not watching.
        if (group.isDm && repository.mlsStateGapCount(group.groupIdHex) > 0) {
            android.util.Log.w("TalkVM", "recoverGapDmOnOpenIfNeeded: suppressed auto fresh DM group=" + group.groupIdHex)
        }
        return group
    }

    private fun startMessageStream(groupIdHex: String) {
        messageStreamJob?.cancel()
        messageStreamJob = viewModelScope.launch {
            while (true) {
                delay(3_000)
                if (_uiState.value.activeGroupId != groupIdHex) break
                try {
                    val base = _uiState.value.activeGroup ?: allGroupsForLookup().firstOrNull { it.groupIdHex == groupIdHex }
                    val fetchTargets = conversationFetchGroupIds(groupIdHex, base)
                    val messages = fetchConversationMlsMessages(fetchTargets, repairFull = false)
                    val normalized = dedupeMessages(_uiState.value.messages + messages)
                    val health = mlsPollHealth.getOrPut(groupIdHex) { MlsPollHealth() }
                    val relayFetched = fetchTargets.sumOf { repository.getMlsFetchStats(it)?.relayFetched ?: 0 }
                    health.polls++
                    health.relaySweeps++
                    health.lastFetched = messages.size
                    health.lastRelayFetched = relayFetched
                    health.lastNormalized = normalized.size
                    health.lastGapCount = repository.mlsStateGapCount(groupIdHex)
                    health.lastRelaySweep = true
                    health.consecutiveEmptyRelayFetches = if (relayFetched == 0) health.consecutiveEmptyRelayFetches + 1 else 0
                    android.util.Log.d("TalkVM", "poll group=" + groupIdHex + " fetched=" + messages.size + " relayFetched=" + relayFetched + " emptyRelay=" + health.consecutiveEmptyRelayFetches + " normalized=" + normalized.size + " current=" + _uiState.value.messages.size + " siblings=" + (base?.let { siblingConversationGroups(it).size } ?: 1) + " relayFetchAll=true health=" + mlsHealthSummary(groupIdHex))
                    if (shouldReplaceMessages(_uiState.value.messages, normalized)) {
                        _uiState.update { it.copy(messages = normalized) }
                    }
                    if (base != null && base.isDm && health.lastGapCount > 0 && normalized.isNotEmpty()) {
                        // Residual MLS gaps can coexist with already-applied application
                        // messages. Do not stop the stream before rendering the usable
                        // history; otherwise iOS-originated messages can be decrypted into
                        // MDK SQLite but never reach the Android UI. Keep polling and let
                        // manual pull / guarded auto-repair handle the remaining gap.
                        recoverGapDmOnOpenIfNeeded(base)
                    }
                    maybeAutoRepairMlsGroup(groupIdHex, health)
                } catch (_: Exception) { mlsPollHealth.getOrPut(groupIdHex) { MlsPollHealth() }.errors++ }
            }
        }
    }

    private fun conversationFetchGroupIds(groupIdHex: String, base: MlsGroup?): List<String> {
        val groups = base?.let { siblingConversationGroups(it) }
            ?.sortedWith(compareByDescending<MlsGroup> { it.lastMessageTime }.thenBy { it.groupIdHex })
            ?.take(24)
            ?.map { it.groupIdHex }
            .orEmpty()
        return (if (groups.isEmpty()) listOf(groupIdHex) else groups).distinct()
    }

    private suspend fun fetchConversationMlsMessages(groupIds: List<String>, repairFull: Boolean): List<MlsMessage> =
        if (groupIds.size <= 1) {
            repository.fetchMlsMessages(groupIds.first(), repairFull = repairFull)
        } else {
            coroutineScope {
                groupIds.map { gid ->
                    async(Dispatchers.IO) { repository.fetchMlsMessages(gid, repairFull = repairFull) }
                }.awaitAll().flatten()
            }
        }

    private fun mlsHealthSummary(groupIdHex: String): String {
        val h = mlsPollHealth[groupIdHex] ?: return "polls=0"
        val cooldownLeft = maxOf(0L, (mlsGroupRetryCooldownUntil[groupIdHex] ?: 0L) - System.currentTimeMillis())
        val autoRepairCooldownLeft = maxOf(0L, (mlsAutoRepairCooldownUntil[groupIdHex] ?: 0L) - System.currentTimeMillis())
        return "polls=${h.polls},sweeps=${h.relaySweeps},errors=${h.errors},fetched=${h.lastFetched},relayFetched=${h.lastRelayFetched},emptyRelay=${h.consecutiveEmptyRelayFetches},shown=${h.lastNormalized},gap=${h.lastGapCount},relaySweep=${h.lastRelaySweep},cooldownMs=$cooldownLeft,autoRepairCooldownMs=$autoRepairCooldownLeft"
    }

    private suspend fun maybeAutoRepairMlsGroup(groupIdHex: String, health: MlsPollHealth) {
        if (health.consecutiveEmptyRelayFetches < 5) return
        if (health.lastNormalized == 0) return
        if (health.lastGapCount > 0) return
        if (mlsRepairInFlight.contains(groupIdHex)) return

        val now = System.currentTimeMillis()
        val cooldownUntil = mlsAutoRepairCooldownUntil[groupIdHex] ?: 0L
        if (now < cooldownUntil) return

        mlsAutoRepairCooldownUntil[groupIdHex] = now + 60_000L
        health.consecutiveEmptyRelayFetches = 0
        android.util.Log.w("TalkVM", "autoRepair group=$groupIdHex reason=consecutive_empty_relay_fetches")
        runMlsRepair(groupIdHex, showLoading = false, source = "auto", clearPendingCommit = false)
    }

    private suspend fun repairConversationMessages(groupIdHex: String, clearPendingCommit: Boolean): List<MlsMessage> {
        val base = _uiState.value.activeGroup ?: allGroupsForLookup().firstOrNull { it.groupIdHex == groupIdHex }
        val targets = conversationFetchGroupIds(groupIdHex, base)
        if (!clearPendingCommit) return fetchConversationMlsMessages(targets, repairFull = true)

        return if (targets.size <= 1) {
            repository.repairMlsGroupHistory(targets.first())
        } else {
            coroutineScope {
                targets.map { gid ->
                    async(Dispatchers.IO) { repository.repairMlsGroupHistory(gid) }
                }.awaitAll().flatten()
            }
        }
    }

    private suspend fun runMlsRepair(groupIdHex: String, showLoading: Boolean, source: String, clearPendingCommit: Boolean) {
        if (!mlsRepairInFlight.add(groupIdHex)) {
            // Another repair is already in-flight for this group. Don't toggle
            // messagesLoading here (would cause StateFlow churn / UI flicker).
            // The caller (TalkScreen pull-to-refresh) has its own safety timeout
            // to end the refresh spinner if no loading transition is observed.
            android.util.Log.d("TalkVM", "repair source=$source group=$groupIdHex skipped (in-flight)")
            return
        }
        try {
            if (showLoading) _uiState.update { it.copy(messagesLoading = true) }
            val before = _uiState.value.messages
            val repaired = repairConversationMessages(groupIdHex, clearPendingCommit = clearPendingCommit)
            val merged = dedupeMessages(before + repaired)
            val base = _uiState.value.activeGroup ?: allGroupsForLookup().firstOrNull { it.groupIdHex == groupIdHex }
            val targets = conversationFetchGroupIds(groupIdHex, base)
            val relayFetched = targets.sumOf { repository.getMlsFetchStats(it)?.relayFetched ?: 0 }
            val historyCount = targets.sumOf { repository.getMlsFetchStats(it)?.historyCount ?: 0 }
            val health = mlsPollHealth.getOrPut(groupIdHex) { MlsPollHealth() }
            health.consecutiveEmptyRelayFetches = 0
            health.lastRelayFetched = relayFetched
            health.lastFetched = historyCount
            health.lastNormalized = merged.size
            android.util.Log.d("TalkVM", "repair source=$source group=$groupIdHex repaired=${repaired.size} merged=${merged.size} relayFetched=$relayFetched")
            if (_uiState.value.activeGroupId == groupIdHex) {
                _uiState.update { it.copy(messages = merged, messagesLoading = false, error = null) }
            }
        } catch (e: Exception) {
            android.util.Log.w("TalkVM", "repair source=$source group=$groupIdHex failed: ${e.message ?: e::class.java.simpleName}")
            if (showLoading && _uiState.value.activeGroupId == groupIdHex) {
                _uiState.update { it.copy(messagesLoading = false) }
            }
        } finally {
            mlsRepairInFlight.remove(groupIdHex)
        }
    }

    /**
     * Talk 一覧 (GroupListScreen) の pull-to-refresh から呼ばれる。
     * loadGroups() を強制的に再走させてキャッシュとリレーの両方を更新する。
     */
    fun refreshGroupList() {
        // loadGroups() は loadGroupsInFlight ガードを持っているので、
        // 既に進行中なら自然に no-op になる。
        loadGroups()
    }

    fun refreshCurrentGroup() {
        val groupId = _uiState.value.activeGroupId ?: return
        viewModelScope.launch {
            // 明示的なユーザ操作 (pull-to-refresh) は強いリペアを行う。
            // iOS が先行で epoch を進めた状態など、Android 側に取り残された
            // pending commit があると弱いリペアでは復号できないことがあるため、
            // GroupInfo「メッセージを修復」と同等の強さで再構築する。
            runMlsRepair(groupId, showLoading = true, source = "pull", clearPendingCommit = true)
        }
    }

    fun repairCurrentGroup() {
        val groupId = _uiState.value.activeGroupId ?: return
        viewModelScope.launch {
            runMlsRepair(groupId, showLoading = true, source = "manual", clearPendingCommit = true)
        }
    }

    fun ensureKeyPackagePublished() {
        viewModelScope.launch {
            try {
                repository.ensureKeyPackagePublished()
            } catch (_: Exception) {
                // Non-critical.
            }
        }
    }

    fun showGroupInfo() { _uiState.update { it.copy(showGroupInfo = true) } }
    fun hideGroupInfo() { _uiState.update { it.copy(showGroupInfo = false) } }
    fun showCreateGroup() {
        _uiState.update { it.copy(showCreateGroup = true) }
        loadFollowingProfiles()
    }
    fun hideCreateGroup() { _uiState.update { it.copy(showCreateGroup = false) } }

    private fun loadFollowingProfiles() {
        if (_uiState.value.followingLoading || _uiState.value.followingProfiles.isNotEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(followingLoading = true) }
            try {
                val pubkeys = repository.fetchFollowList(myPubkeyHex)
                val profiles = if (pubkeys.isNotEmpty()) repository.fetchProfiles(pubkeys.take(200)) else emptyMap()
                val profileList = pubkeys.take(200).map { pk -> profiles[pk] ?: UserProfile(pubkey = pk) }
                _uiState.update { it.copy(followingProfiles = profileList, followingLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(followingLoading = false, error = "フォローリストの取得に失敗しました") }
            }
        }
    }

    fun toggleLegacy() { _uiState.update { it.copy(showLegacy = !it.showLegacy) } }
    fun clearError() { _uiState.update { it.copy(error = null) } }

    private fun allGroupsForLookup(): List<MlsGroup> =
        (allMlsGroups + _uiState.value.groups).distinctBy { it.groupIdHex }

    private fun conversationKey(group: MlsGroup): String {
        val members = group.memberPubkeys.map { it.lowercase() }.distinct().sorted()
        return if (group.isDm) {
            "dm:" + (members.firstOrNull { it != myPubkeyHex.lowercase() } ?: members.joinToString("|"))
        } else {
            "group:" + members.joinToString("|")
        }
    }

    private fun visibleConversationGroups(input: List<MlsGroup>): List<MlsGroup> =
        input.filter { group -> !group.isDm || group.memberPubkeys.any { !it.equals(myPubkeyHex, ignoreCase = true) } }

    private fun collapseConversationGroups(input: List<MlsGroup>): List<MlsGroup> =
        visibleConversationGroups(input).groupBy { conversationKey(it) }
            .values
            .map { entries ->
                entries.maxWithOrNull(compareBy<MlsGroup> { it.lastMessageTime }.thenBy { it.createdAt }.thenBy { it.groupIdHex })
                    ?: entries.first()
            }
            .sortedWith(compareByDescending<MlsGroup> { it.lastMessageTime }.thenBy { it.groupIdHex })

    private fun remapCollapsedConversation(group: MlsGroup, collapsed: List<MlsGroup>): MlsGroup? =
        collapsed.firstOrNull { conversationKey(it) == conversationKey(group) }

    private fun siblingConversationGroups(group: MlsGroup): List<MlsGroup> {
        val key = conversationKey(group)
        return allGroupsForLookup().filter { conversationKey(it) == key }
            .distinctBy { it.groupIdHex }
    }

    private fun siblingDmGroupIds(group: MlsGroup): List<String> =
        siblingConversationGroups(group).filter { it.isDm }.map { it.groupIdHex }

    private fun dedupeMessages(input: List<MlsMessage>): List<MlsMessage> =
        input.associateBy { "${it.senderPubkey}|${it.timestamp}|${it.content.trim()}" }
            .values
            .sortedWith(compareBy<MlsMessage> { it.timestamp }.thenBy { it.id })

    private fun shouldReplaceMessages(current: List<MlsMessage>, incoming: List<MlsMessage>): Boolean {
        val normalizedIncoming = dedupeMessages(incoming)
        if (current.size != normalizedIncoming.size) return true
        return current.zip(normalizedIncoming).any { (lhs, rhs) ->
            lhs.id != rhs.id ||
                lhs.senderPubkey != rhs.senderPubkey ||
                lhs.timestamp != rhs.timestamp ||
                lhs.content != rhs.content
        }
    }

    private fun normalizeMlsError(error: Throwable, fallback: String): String {
        val raw = (error.message ?: error.toString()).lowercase()
        return when {
            "invalid_base64_content" in raw || "invalid base64" in raw -> "MLSイベント形式が不正です（base64）"
            "malformed_content_too_short" in raw || "too_short" in raw -> "MLSイベント形式が不正です（長さ不足）"
            "missing_h_tag" in raw -> "MLSイベント形式が不正です（hタグ不足）"
            "group_id_mismatch" in raw -> "MLSイベントのグループIDが一致しません"
            "invalid_kind" in raw -> "MLSイベント種別が不正です"
            "pending proposal exists" in raw || "pending commit exists" in raw -> "同期中です。少し待って再試行してください"
            "no ffi client" in raw || "ffi unavailable" in raw || "mls not initialised" in raw -> "MLSエンジンが利用できません"
            "not admin" in raw -> "管理者のみがこの操作を実行できます"
            else -> fallback
        }
    }

    class Factory(
        private val repository: NostrRepository,
        private val nostrClient: NostrClient,
        private val myPubkeyHex: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TalkViewModel(repository, nostrClient, myPubkeyHex) as T
    }
}
