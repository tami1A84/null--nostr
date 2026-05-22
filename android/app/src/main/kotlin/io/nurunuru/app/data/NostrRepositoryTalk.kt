package io.nurunuru.app.data

import io.nurunuru.app.data.models.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import uniffi.nurunuru.FfiEncryptedMessageData
import java.util.UUID

private fun mlsLogPrefix(value: String?): String {
    val v = value.orEmpty()
    return if (v.length <= 8) v else v.take(8) + "…"
}

private fun mlsRedactedError(e: Throwable): String {
    val lower = e.message.orEmpty().lowercase()
    return when {
        "hmac" in lower -> "hmac_error"
        "process_welcome" in lower || "welcome" in lower -> "welcome_error"
        "content" in lower || "payload" in lower || "plaintext" in lower || "secret" in lower || "private" in lower -> "redacted_error"
        else -> "mls_error"
    }
}

private fun mlsProcessErrorKind(e: Throwable): String {
    val lower = e.message.orEmpty().lowercase()
    return when {
        "invalid_base64" in lower || "invalid base64" in lower -> "invalid_base64"
        "malformed_content_too_short" in lower || "too_short" in lower -> "malformed_content_too_short"
        "missing_h_tag" in lower || "missing h" in lower -> "missing_h_tag"
        "group_id_mismatch" in lower || "group mismatch" in lower || "wrong h" in lower -> "group_id_mismatch"
        "invalid_kind" in lower || "invalid kind" in lower || "wrong kind" in lower -> "invalid_kind"
        "invalid event" in lower || "not a nostr event" in lower || "bad signature" in lower || "invalid signature" in lower -> "invalid_event"
        "json" in lower || "deserialize" in lower -> "invalid_json"
        "state_not_ready" in lower || "state" in lower || "epoch" in lower || "pending" in lower || "proposal" in lower || "commit" in lower -> "state_not_ready"
        "decrypt" in lower || "ratchet" in lower || "sender" in lower || "member" in lower || "not found" in lower || "missing" in lower -> "retryable_mls_state"
        else -> "mls_error"
    }
}

private fun mlsLogPrefixes(values: List<String>): List<String> = values.map { mlsLogPrefix(it) }

private fun mlsDisplayContent(raw: String): String {
    if (!raw.trimStart().startsWith("{")) return raw
    return try {
        Json.parseToJsonElement(raw).jsonObject["content"]?.jsonPrimitive?.contentOrNull ?: raw
    } catch (_: Exception) { raw }
}


/**
 * Marmot/WhiteNoise interop relays.
 *
 * WhiteNoise diagnostics show Welcome inbox delivery and group-message fanout often use
 * auth.nostr1.com / relay.primal.net in addition to the user's selected relays. After an
 * Android reinstall the local MLS DB is empty, so rediscovering historical Kind-1059
 * Welcomes from these relays is required before subscribing to the group's Kind-445 #h feed.
 */
private val MLS_INTEROP_RELAYS = listOf(
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
)

private val DEFAULT_MLS_KEY_PACKAGE_RELAYS = listOf(
    "wss://relay.0xchat.com",
    "wss://auth.nostr1.com",
    "wss://relay.damus.io",
    "wss://relay.primal.net",
    "wss://nos.lol",
    "wss://relay.nostr.wirednet.jp",
    "wss://yabu.me",
    "wss://r.kojira.io"
)

private val DEFAULT_MLS_INBOX_RELAYS = listOf(
    "wss://relay.0xchat.com",
    "wss://auth.nostr1.com",
    "wss://yabu.me",
    "wss://r.kojira.io"
)

// ─── MLS Groups (Marmot MIP-00〜03, WhiteNoise 互換) ─────────────────────────

private fun NostrRepository.isCurrentAccountMlsGroup(memberPubkeys: List<String>): Boolean {
    val account = myPubkeyHex.trim().lowercase()
    if (account.isEmpty()) return false
    return memberPubkeys.any { it.equals(account, ignoreCase = true) }
}

//
// 設計方針:
//   - Rust SQLite (MDK) を single source of truth とし、アプリ側キャッシュは保持しない。
//   - mlsProcessedIds は relay イベントの二重復号防止のみに使用（セッション内）。
//   - processedWelcomeIds は Welcome の再処理を防止。

/** キャッシュファースト用: Rust 未接続でも即時表示可能なグループ一覧。 */
fun NostrRepository.getCachedMlsGroups(): List<MlsGroup> {
    val pubkey = prefs.publicKeyHex ?: return emptyList()
    val raw = cache.getCachedMlsGroups(pubkey) ?: return emptyList()
    return try {
        val allGroups = json.decodeFromString<List<MlsGroup>>(raw)
        val leftIds = cache.getLeftGroupIds()
        allGroups.filter { it.groupIdHex !in leftIds }
    } catch (_: Exception) { emptyList() }
}

/** Rust SQLite のローカル履歴のみ即時返す（ネットワーク不要）。 */
suspend fun NostrRepository.getLocalMlsMessages(groupIdHex: String): List<MlsMessage> {
    val rustClient = client.getRustClient() ?: return emptyList()
    return withContext(Dispatchers.IO) {
        try {
            val groupInfo = try { rustClient.mlsGetGroupInfo(groupIdHex) } catch (_: Exception) { null }
            if (groupInfo == null || !isCurrentAccountMlsGroup(groupInfo.memberPubkeys)) {
                android.util.Log.w("NostrRepository", "getLocalMlsMessages($groupIdHex): blocked stale cross-account group")
                return@withContext emptyList()
            }
            val history = rustClient.mlsGetMessageHistory(groupIdHex, 300u)
            val senderPubkeys = history.map { it.senderPubkey }.distinct()
            val profiles = fetchProfiles(senderPubkeys)
            history.map { msg ->
                MlsMessage(
                    id = "${msg.senderPubkey}_${msg.timestamp}",
                    senderPubkey = msg.senderPubkey,
                    content = mlsDisplayContent(msg.content),
                    timestamp = msg.timestamp.toLong(),
                    groupIdHex = groupIdHex,
                    senderProfile = profiles[msg.senderPubkey]
                )
            }.sortedBy { it.timestamp }
        } catch (e: Exception) {
            android.util.Log.w("NostrRepository", "getLocalMlsMessages($groupIdHex): ${mlsRedactedError(e)}")
            emptyList()
        }
    }
}

suspend fun NostrRepository.fetchMlsGroups(): List<MlsGroup> {
    val pubkey = prefs.publicKeyHex ?: return emptyList()
    val rustClient = client.getRustClient() ?: return emptyList()

    return withContext(Dispatchers.IO) {
        try {
            // 1. Welcome イベントを取得 → 未処理分のみ process
            //    Marmot (MIP-02): Kind 1059 (NIP-59 gift-wrapped)
            //      - recipient は KeyPackage owner (= 自分の pubkey) の #p tag
            //      - 受信した signed 1059 event JSON をそのまま Rust に渡す
            //      - Rust 側 mlsProcessWelcome が unwrap → Kind 444 rumor process → join まで行う
            //    Legacy (NIP-EE): Kind 444 (raw rumor) も互換用に受ける
            //    WhiteNoise 等がデフォルト外リレーに publish する可能性あり
            //    → グローバルリレーも含めた広い範囲から取得
            val welcomeRelays = mlsPublishRelays()
            val giftWrapWelcomeFilter = NostrClient.Filter(
                kinds = listOf(NostrKind.MLS_WELCOME),
                tags = mapOf("p" to listOf(pubkey)),
                // Reinstall recovery needs historical Welcomes, not only the last few inbox items.
                limit = 200
            )
            val legacyWelcomeFilter = NostrClient.Filter(
                kinds = listOf(NostrKind.MLS_WELCOME_INNER, NostrKind.MLS_WELCOME_INNER_MARMOT),
                tags = mapOf("p" to listOf(pubkey)),
                limit = 500
            )
            val welcomeEvents = (client.fetchEventsFrom(welcomeRelays, giftWrapWelcomeFilter.copy(limit = 500), timeoutMs = 8_000) +
                client.fetchEventsFrom(welcomeRelays, legacyWelcomeFilter, timeoutMs = 8_000))
                .distinctBy { it.id }
                .sortedBy { it.createdAt }
            val joinedGroupIds = mutableSetOf<String>()
            var newWelcomeCount = 0
            var skippedWelcomeCount = 0
            var failedWelcomeCount = 0
            for (event in welcomeEvents) {
                if (event.kind != NostrKind.MLS_WELCOME && event.kind != NostrKind.MLS_WELCOME_INNER && event.kind != NostrKind.MLS_WELCOME_INNER_MARMOT) continue
                if (event.kind == NostrKind.MLS_WELCOME && event.getTagValues("p").none { it.equals(pubkey, ignoreCase = true) }) {
                    // 1059 の recipient は KeyPackage event owner pubkey。自分宛て以外は unwrap しない。
                    skippedWelcomeCount++
                    continue
                }
                if (processedWelcomeIds.contains(event.id)) {
                    skippedWelcomeCount++
                    continue
                }
                try {
                    val eventJson = json.encodeToString(NostrEvent.serializer(), event)
                    val joined = rustClient.mlsProcessWelcome(eventJson)
                    // FFI/App 境界の groupIdHex は Nostr group id。internal MLS group id は扱わない。
                    joinedGroupIds.add(joined.groupIdHex)
                    processedWelcomeIds.add(event.id)
                    rotateOwnKeyPackageAfterWelcome()
                    newWelcomeCount++
                    android.util.Log.d(
                        "NostrRepository",
                        "fetchMlsGroups: processed Welcome kind=${event.kind} event=${event.id} groupIdHex=${joined.groupIdHex} joinedAt=${event.createdAt}"
                    )
                } catch (e: Exception) {
                    failedWelcomeCount++
                    // 失敗した Welcome はセッション内 processed に入れず、次回 fetch で再試行可能にする。
                    android.util.Log.w(
                        "NostrRepository",
                        "fetchMlsGroups: failed to process Welcome kind=${event.kind} event=${event.id}: ${mlsRedactedError(e)}"
                    )
                }
            }
            if (newWelcomeCount > 0 || failedWelcomeCount > 0) {
                android.util.Log.d(
                    "NostrRepository",
                    "fetchMlsGroups: welcomes fetched=${welcomeEvents.size} processed=$newWelcomeCount skipped=$skippedWelcomeCount failed=$failedWelcomeCount joinedGroups=${joinedGroupIds.size}"
                )
            }

            // Welcome process 後の post-join self-update。
            // FFI/App 境界の groupIdHex は Nostr group id のまま扱う。
            // mlsGroupsNeedingSelfUpdate() も Nostr group id を返す contract なので retry source として使う。
            publishPendingMlsSelfUpdates(joinedGroupIds)

            // 2. Rust SQLite からグループ一覧（process success 後なので新規 join 済み group も含まれる）
            val ffiGroups = rustClient.mlsListGroups()
            val leftIds = cache.getLeftGroupIds()
            val activeGroups = ffiGroups.filter { ffi ->
                ffi.groupIdHex !in leftIds &&
                    isCurrentAccountMlsGroup(ffi.memberPubkeys) &&
                    (!ffi.isDm || ffi.memberPubkeys.any { !it.equals(myPubkeyHex, ignoreCase = true) })
            }

            // 3. メンバープロファイルエンリッチ
            val allPubkeys = activeGroups.flatMap { it.memberPubkeys }.distinct()
            val profiles = fetchProfiles(allPubkeys)

            // 4. 各グループの最新メッセージを Rust SQLite から取得
            activeGroups.map { ffi ->
                val lastMsg = try {
                    rustClient.mlsGetMessageHistory(ffi.groupIdHex, 1u).firstOrNull()
                } catch (_: Exception) { null }
                MlsGroup(
                    groupIdHex = ffi.groupIdHex,
                    name = ffi.name,
                    description = ffi.description,
                    adminPubkeys = ffi.adminPubkeys,
                    memberPubkeys = ffi.memberPubkeys,
                    relays = ffi.relays,
                    createdAt = ffi.createdAt.toLong(),
                    epoch = ffi.epoch.toLong(),
                    isDm = ffi.isDm,
                    memberProfiles = ffi.memberPubkeys.mapNotNull { pk ->
                        profiles[pk]?.let { pk to it }
                    }.toMap(),
                    lastMessage = lastMsg?.content?.let { mlsDisplayContent(it) } ?: "",
                    lastMessageTime = lastMsg?.timestamp?.toLong() ?: ffi.createdAt.toLong()
                )
            }.sortedByDescending { it.lastMessageTime }.also { result ->
                // 次回起動時の即時表示用にキャッシュ永続化
                try { cache.setCachedMlsGroups(pubkey, json.encodeToString(result)) } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "fetchMlsGroups failed: ${mlsRedactedError(e)}")
            getCachedMlsGroups()
        }
    }
}

/**
 * グループのメッセージを取得する。
 *
 * 1. グループのリレーリストを取得（グループメタデータ → デフォルトリレー fallback）
 * 2. Kind-445 を**グループのリレー＋デフォルトリレー**から取得
 * 3. 未処理分を mlsProcessMessageResult で復号または state update 適用
 * 4. Rust SQLite から全履歴を返す（= single source of truth）
 * - Commit/Proposal は StateUpdate として処理済みにする
 * - relay out-of-order で今は処理できないイベントは processedIds に入れず retry queue に残す
 */
suspend fun NostrRepository.fetchMlsMessages(groupIdHex: String, repairFull: Boolean = false): List<MlsMessage> {
    val rustClient = client.getRustClient() ?: return emptyList()

    return withContext(Dispatchers.IO) {
        try {
            // FFI/App 境界の groupIdHex は Nostr group id。internal MLS group id は扱わない。
            if (repairFull) {
                mlsProcessedIds.remove(groupIdHex)
                mlsMessageRetryQueues.remove(groupIdHex)
                mlsRetryableStateCounts.remove(groupIdHex)
                mlsRetryableEventCooldownUntil.remove(groupIdHex)
                // Do not clear pending commits as part of normal repair/catch-up.
                // Clearing pending state before replay can make this client create
                // application messages from an epoch peers have not observed. Explicit
                // manual repair paths may still clear pending state when requested.
            }
            val processedIds = mlsProcessedIds.getOrPut(groupIdHex) {
                java.util.concurrent.ConcurrentHashMap.newKeySet()
            }
            val retryQueue = if (repairFull) {
                // Full repair must be deterministic and finite. Replaying stale retryQueue
                // items after the fetched relay events can keep old out-of-order wrappers
                // alive forever and make newly-created application messages diverge from
                // the peer. Use the fresh relay result only.
                java.util.concurrent.ConcurrentHashMap<String, NostrEvent>()
            } else {
                mlsMessageRetryQueues.getOrPut(groupIdHex) {
                    java.util.concurrent.ConcurrentHashMap<String, NostrEvent>()
                }
            }

            // 1. グループのリレーリストを取得。ログアウト/別アカウント後に
            // 旧アカウントの MLS SQLite 履歴を表示しないよう memberPubkeys を検証する。
            val groupInfo = try { rustClient.mlsGetGroupInfo(groupIdHex) } catch (_: Exception) { null }
            if (groupInfo == null || !isCurrentAccountMlsGroup(groupInfo.memberPubkeys)) {
                android.util.Log.w("NostrRepository", "fetchMlsMessages($groupIdHex): blocked stale cross-account group")
                return@withContext emptyList()
            }
            val groupRelays = groupInfo.relays
            val inboxRelays = resolveMlsInboxRelaysForMembers(groupInfo.memberPubkeys)

            // デフォルトリレー + グループリレー + peer inbox + Marmot interop fallback を統合（重複排除）。
            // iOS/WhiteNoise は group relay だけでなく sender/recipient inbox relay に Kind-445 を fanout する。
            // Android 側も polling で peer inbox を読むことで iOS→Android が relay miss になる経路を防ぐ。
            val allRelays = mlsPublishRelays(groupRelays + inboxRelays)

            android.util.Log.d("NostrRepository",
                "fetchMlsMessages($groupIdHex): groupRelays=$groupRelays, inboxRelays=$inboxRelays, allRelays=$allRelays")

            // 2. Kind-445 をグループのリレーから取得して未処理分を process
            // After app restart, relay echoes that are older than the persisted MDK
            // history can replay as state_not_ready and poison gap detection.
            // If history exists, only process events newer than the history watermark.
            val preHistoryWatermark = try {
                rustClient.mlsGetMessageHistory(groupIdHex, 300u).maxOfOrNull { it.timestamp.toLong() } ?: 0L
            } catch (_: Exception) { 0L }
            val filter = NostrClient.Filter(
                kinds = listOf(NostrKind.MLS_GROUP_MESSAGE),
                tags = mapOf("h" to listOf(groupIdHex)),
                limit = if (repairFull) 500 else 150
            )

            // 特定リレーから取得（自動追加・接続も行われる）
            val events = if (allRelays.isNotEmpty()) {
                client.fetchEventsFrom(allRelays, filter, timeoutMs = if (repairFull) 15_000 else 8_000)
            } else {
                client.fetchEvents(filter, timeoutMs = if (repairFull) 12_000 else 5_000)
            }.distinctBy { it.id }
                .filter { event -> repairFull || (mlsRetryableEventCooldownUntil[groupIdHex]?.get(event.id) ?: 0L) <= System.currentTimeMillis() }
                .filter { event -> repairFull || preHistoryWatermark == 0L || event.createdAt >= (preHistoryWatermark - 5L) }

            if (events.isNotEmpty()) recordMlsRelayHits(allRelays, events.size)
            android.util.Log.d("NostrRepository",
                "fetchMlsMessages($groupIdHex): fetched ${events.size} Kind-445 events")

            var applied = 0
            var stateOnly = 0
            var dropped = 0
            var retryable = 0
            var duplicates = 0

            fun processCandidate(event: NostrEvent): MlsProcessPolicy {
                val invalidReason = event.mlsKind445InvalidReason(groupIdHex)
                if (invalidReason != null) {
                    android.util.Log.w(
                        "NostrRepository",
                        "fetchMlsMessages($groupIdHex): drop invalid envelope id=${event.id} reason=$invalidReason h=${event.getTagValues("h")} author=${mlsLogPrefix(event.pubkey)} createdAt=${event.createdAt}"
                    )
                    return MlsProcessPolicy.Dropped
                }

                return try {
                    val eventJson = json.encodeToString(NostrEvent.serializer(), event)
                    when (val result = rustClient.mlsProcessMessageResult(groupIdHex, eventJson)) {
                        is uniffi.nurunuru.FfiMlsProcessResult.Application -> {
                            applied++
                            android.util.Log.d(
                                "NostrRepository",
                                "fetchMlsMessages($groupIdHex): application id=${event.id} sender=${mlsLogPrefix(result.message.senderPubkey)} len=${result.message.content.length}"
                            )
                            MlsProcessPolicy.Processed
                        }
                        // Issue #178 #5: structured commit delta — no group-info re-query needed.
                        is uniffi.nurunuru.FfiMlsProcessResult.Commit -> {
                            stateOnly++
                            android.util.Log.d(
                                "NostrRepository",
                                "fetchMlsMessages($groupIdHex): commit id=${event.id} added=${result.addedPubkeys.size} removed=${result.removedPubkeys.size} epoch=${result.epochAfter}"
                            )
                            MlsProcessPolicy.StateUpdated
                        }
                        // Issue #178 #6: pending proposal — schedule a self-update so the group does not fork.
                        is uniffi.nurunuru.FfiMlsProcessResult.NeedsSelfUpdate -> {
                            stateOnly++
                            android.util.Log.w(
                                "NostrRepository",
                                "fetchMlsMessages($groupIdHex): needsSelfUpdate id=${event.id} reason=${result.reason} — scheduling recovery commit"
                            )
                            scheduleMlsSelfUpdate(groupIdHex)
                            MlsProcessPolicy.StateUpdated
                        }
                        is uniffi.nurunuru.FfiMlsProcessResult.StateUpdate -> {
                            val kind = result.kind
                            android.util.Log.d(
                                "NostrRepository",
                                "fetchMlsMessages($groupIdHex): stateUpdate id=${event.id} kind=$kind"
                            )
                            when {
                                kind.startsWith("unhandled:Unprocessable:missing_h_tag") ||
                                    kind.startsWith("unhandled:Unprocessable:group_id_mismatch") ||
                                    kind.startsWith("unhandled:Unprocessable:invalid_kind") -> MlsProcessPolicy.Dropped
                                kind.startsWith("unhandled:Unprocessable") -> MlsProcessPolicy.Retryable
                                else -> {
                                    stateOnly++
                                    MlsProcessPolicy.StateUpdated
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    val errKind = mlsProcessErrorKind(e)
                    val permanent = e.isPermanentMlsProcessDropError()
                    val action = if (permanent) "drop" else "retry"
                    android.util.Log.w(
                        "NostrRepository",
                        "fetchMlsMessages($groupIdHex): process $action id=${event.id} err=$errKind h=${event.getTagValues("h")} author=${mlsLogPrefix(event.pubkey)} createdAt=${event.createdAt}"
                    )
                    if (permanent) MlsProcessPolicy.Dropped else MlsProcessPolicy.Retryable
                }
            }

            fun handleEvent(event: NostrEvent): MlsProcessPolicy {
                if (event.id.isBlank()) {
                    dropped++
                    return MlsProcessPolicy.Dropped
                }
                if (processedIds.contains(event.id)) {
                    duplicates++
                    retryQueue.remove(event.id)
                    return MlsProcessPolicy.AlreadyProcessed
                }

                val policy = processCandidate(event)
                when (policy) {
                    MlsProcessPolicy.Processed,
                    MlsProcessPolicy.StateUpdated,
                    MlsProcessPolicy.Dropped -> {
                        // invalid shape / wrong h-tag / invalid kind / unrecoverable process errors are dropped idempotently.
                        processedIds.add(event.id)
                        retryQueue.remove(event.id)
                        if (policy == MlsProcessPolicy.Dropped) dropped++
                    }
                    MlsProcessPolicy.Retryable -> {
                        // Relay out-of-order / state gap. Keep retryables in the active
                        // pass queue even during repairFull so the later history-watermark
                        // pruning can decide whether they are truly blocking. Previously
                        // repairFull counted every retryable as a hard gap while the queue
                        // was empty, so Talk stayed blocked even when local history was usable.
                        retryQueue[event.id] = event
                        if (!repairFull) {
                            val groupCooldowns = mlsRetryableEventCooldownUntil.getOrPut(groupIdHex) {
                                java.util.concurrent.ConcurrentHashMap<String, Long>()
                            }
                            groupCooldowns[event.id] = System.currentTimeMillis() + 30_000L
                        }
                        retryable++
                    }
                    MlsProcessPolicy.AlreadyProcessed -> Unit
                }
                return policy
            }

            for (event in events.sortedWith(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })) {
                handleEvent(event)
            }

            // state update 適用後は out-of-order で保留していたイベントを再走査する。
            var retryPasses = 0
            var progressed: Boolean
            do {
                progressed = false
                val pending = retryQueue.values
                    .filter { !processedIds.contains(it.id) }
                    .sortedWith(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
                if (pending.isEmpty()) break

                for (event in pending) {
                    val beforeProcessed = processedIds.contains(event.id)
                    val policy = handleEvent(event)
                    if (!beforeProcessed && (policy == MlsProcessPolicy.Processed ||
                            policy == MlsProcessPolicy.StateUpdated ||
                            policy == MlsProcessPolicy.Dropped)) {
                        progressed = true
                    }
                }
                retryPasses++
            } while (progressed && retryPasses < 4)

            android.util.Log.d("NostrRepository",
                "fetchMlsMessages($groupIdHex): applied=$applied stateOnly=$stateOnly dropped=$dropped retryable=$retryable duplicates=$duplicates retryQueued=${retryQueue.size}")

            // 3. Rust SQLite から全履歴（= single source of truth）
            var history = rustClient.mlsGetMessageHistory(groupIdHex, 300u)
            android.util.Log.d("NostrRepository", "fetchMlsMessages($groupIdHex): history count=${history.size}")
            mlsFetchStats[groupIdHex] = MlsFetchStats(
                relayFetched = events.size,
                historyCount = history.size,
                applied = applied,
                stateOnly = stateOnly,
                dropped = dropped,
                retryable = retryable,
                duplicates = duplicates,
                updatedAtMs = System.currentTimeMillis()
            )

            // Retryable state_not_ready can be a stale replay diagnostic for an
            // already-persisted application message. Do not let old wrapper events
            // permanently poison TalkVM send-target selection / send preflight.
            // Only retryables newer than the local history watermark represent a
            // real unresolved epoch gap that should block sending. This mirrors the
            // iOS Talk implementation and fixes reinstall cases where Android keeps
            // polling one old Kind-445 as state_not_ready while MDK history is usable.
            val historyWatermark = history.maxOfOrNull { it.timestamp.toLong() } ?: 0L
            val staleRetryIds = retryQueue.values
                .filter { it.createdAt <= historyWatermark }
                .map { it.id }
                .toSet()
            if (staleRetryIds.isNotEmpty()) {
                // Remove stale retryables, not just from the counter. TalkVM uses
                // mlsStateGapCount(), which also looks at retryQueue.size; leaving stale
                // entries here kept gap=1 and caused Android sends to abort even after
                // history count had advanced.
                staleRetryIds.forEach { id ->
                    retryQueue.remove(id)
                    mlsRetryableEventCooldownUntil[groupIdHex]?.remove(id)
                }
            }
            val blockingRetryQueued = retryQueue.values.count { it.createdAt > historyWatermark }
            // Only retryables newer than local history should block sending. This applies
            // to full repair too; otherwise historical state_not_ready diagnostics make
            // mlsStateGapCount() remain >0 forever and Android aborts every send.
            val blockingRetryable = blockingRetryQueued
            if (retryable != blockingRetryable || staleRetryIds.isNotEmpty()) {
                android.util.Log.d(
                    "NostrRepository",
                    "fetchMlsMessages($groupIdHex): stale retryables ignored for send block stale=" +
                        staleRetryIds.size +
                        " blocking=$blockingRetryQueued historyWatermark=$historyWatermark"
                )
            }
            mlsRetryableStateCounts[groupIdHex] = blockingRetryable

            // If this process previously marked Kind-445 events as processed but the MDK
            // history is empty, the UI becomes permanently blank: later polls see only
            // duplicates and never replay those events into SQLite. This happened after
            // outbound send registered its own event id before it was visible in history.
            // Clear the session-only skip cache and do one full replay.
            if (!repairFull && history.isEmpty() && duplicates > 0 && processedIds.isNotEmpty()) {
                android.util.Log.w(
                    "NostrRepository",
                    "fetchMlsMessages($groupIdHex): empty history with duplicates=$duplicates; clearing processedIds and replaying"
                )
                mlsProcessedIds.remove(groupIdHex)
                retryQueue.clear()
                return@withContext fetchMlsMessages(groupIdHex, repairFull = true)
            }

            // 4. プロファイルエンリッチ
            val senderPubkeys = history.map { it.senderPubkey }.distinct()
            val profiles = fetchProfiles(senderPubkeys)

            history.map { msg ->
                MlsMessage(
                    id = "${msg.senderPubkey}_${msg.timestamp}",
                    senderPubkey = msg.senderPubkey,
                    content = mlsDisplayContent(msg.content),
                    timestamp = msg.timestamp.toLong(),
                    groupIdHex = groupIdHex,
                    senderProfile = profiles[msg.senderPubkey]
                )
            }.sortedBy { it.timestamp }
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "fetchMlsMessages($groupIdHex) failed: ${mlsRedactedError(e)}")
            emptyList()
        }
    }
}


/**
 * Welcome process 後、および前回 publish/merge に失敗した group の self-update を publish する。
 *
 * Flow は Marmot P4-S2 の policy に合わせる:
 *   mlsCreateRecoveryCommit(groupIdHex) -> publish Kind 445 -> success なら mlsMergePendingCommit(groupIdHex)
 * publish fail 時は pending commit を clear し、次回 mlsGroupsNeedingSelfUpdate() から再作成できるようにする。
 *
 * groupIdHex は FFI/App 境界 contract どおり Nostr group id。internal MLS group id は扱わない。
 */
private suspend fun NostrRepository.publishPendingMlsSelfUpdates(
    joinedGroupIds: Set<String> = emptySet()
) = withContext(Dispatchers.IO) {
    // Disabled for Marmot/WhiteNoise interop stability. Background self-update commits
    // advance this device epoch and make peers miss later application messages.
    android.util.Log.w("NostrRepository", "publishPendingMlsSelfUpdates: suppressed for interop joined=" + joinedGroupIds.size)
    return@withContext
}

/**
 * Issue #178 #6: prepare a recovery self-update when MDK reports a pending
 * Proposal. Debounced to once per group per minute; the prepared commit sits
 * in MDK as pending and the next publish path picks it up.
 */
private val mlsScheduledSelfUpdateAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

private val mlsSelfUpdateScope = CoroutineScope(
    SupervisorJob() + Dispatchers.IO + CoroutineName("mls-self-update")
)

internal fun NostrRepository.scheduleMlsSelfUpdate(groupIdHex: String) {
    val now = System.currentTimeMillis()
    val previous = mlsScheduledSelfUpdateAt[groupIdHex]
    if (previous != null && now - previous < 60_000L) {
        return
    }
    mlsScheduledSelfUpdateAt[groupIdHex] = now
    mlsSelfUpdateScope.launch {
        try {
            val rustClient = client.getRustClient() ?: return@launch
            val commit = rustClient.mlsCreateRecoveryCommit(groupIdHex)
            android.util.Log.i(
                "NostrRepository",
                "scheduleMlsSelfUpdate: prepared groupIdHex=$groupIdHex contentLen=${commit.content.length}"
            )
        } catch (e: Exception) {
            android.util.Log.w(
                "NostrRepository",
                "scheduleMlsSelfUpdate: failed groupIdHex=$groupIdHex: ${mlsRedactedError(e)}"
            )
        }
    }
}

data class MlsFetchStats(
    val relayFetched: Int = 0,
    val historyCount: Int = 0,
    val applied: Int = 0,
    val stateOnly: Int = 0,
    val dropped: Int = 0,
    val retryable: Int = 0,
    val duplicates: Int = 0,
    val updatedAtMs: Long = 0L
)

private val mlsMessageRetryQueues = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, NostrEvent>>()
private val mlsRetryableStateCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
private val mlsRetryableEventCooldownUntil = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, Long>>()
private val mlsRelayHitScores = java.util.concurrent.ConcurrentHashMap<String, Int>()
private val mlsFetchStats = java.util.concurrent.ConcurrentHashMap<String, MlsFetchStats>()

// ─── Issue #183: per-group recovery status ───────────────────────────────────
// In-memory only — the source of truth for "is this group recoverable?" is
// the Rust `catch_up_to_peer` result. We cache the most recent classification
// so the UI can render the banner without re-running catch-up every poll.
private val mlsRecoveryStatuses = java.util.concurrent.ConcurrentHashMap<String, MlsRecoveryStatus>()

/**
 * Issue #183: client-facing classification of a group's catch-up state.
 * Mirrors [uniffi.nurunuru.FfiMlsCatchUpStatus] but stays inside the data
 * layer so TalkViewModel does not have to import the FFI enum directly.
 *
 * - [Healthy]            no gap or last catch-up reported Recovered.
 * - [Recovering]         last catch-up reported PartiallyRecovered — keep polling.
 * - [NotRecoverable]     last catch-up reported NotRecoverable — the missing
 *                        Commit is not retrievable from configured relays and
 *                        is not in the local replay cache. UI should prompt
 *                        the user to recreate the conversation (AC2).
 * - [Unknown]            never attempted catch-up for this group.
 */
enum class MlsRecoveryStatus { Healthy, Recovering, NotRecoverable, Unknown }

/**
 * Issue #183: deep-catch-up result mirrored to the app layer.
 * Strictly read-only — the wrapper writes only into the in-memory status map.
 */
data class MlsDeepCatchUpResult(
    val groupIdHex: String,
    val status: MlsRecoveryStatus,
    val epochBefore: Long,
    val epochAfter: Long,
    val candidatesConsidered: Int,
    val applicationMessagesApplied: Int,
    val commitsApplied: Int,
    val stillUnprocessable: Int,
    val cacheHits: Int
)


fun NostrRepository.hasMlsStateGaps(groupIdHex: String): Boolean = mlsStateGapCount(groupIdHex) > 0

fun NostrRepository.getMlsFetchStats(groupIdHex: String): MlsFetchStats? = mlsFetchStats[groupIdHex]

fun NostrRepository.mlsStateGapCount(groupIdHex: String): Int =
    maxOf(mlsRetryableStateCounts[groupIdHex] ?: 0, mlsMessageRetryQueues[groupIdHex]?.size ?: 0)

/**
 * Issue #183: most recent recovery classification for the group. Defaults to
 * [MlsRecoveryStatus.Unknown] until [deepCatchUpMlsGroup] runs at least once.
 */
fun NostrRepository.mlsRecoveryStatusFor(groupIdHex: String): MlsRecoveryStatus =
    mlsRecoveryStatuses[groupIdHex] ?: MlsRecoveryStatus.Unknown

/**
 * Issue #183: clear the cached recovery status for a group (used after the
 * user successfully recreates the conversation).
 */
fun NostrRepository.clearMlsRecoveryStatus(groupIdHex: String) {
    mlsRecoveryStatuses.remove(groupIdHex)
}


private enum class MlsProcessPolicy {
    Processed,
    StateUpdated,
    Retryable,
    Dropped,
    AlreadyProcessed
}

private fun String.isHex64(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

/**
 * Pre-FFI validation for Kind-445 envelope only. MLS payload is never logged or inspected here.
 * Drop malformed/wrong-group events idempotently; retry only events that have a valid envelope.
 */
private fun NostrEvent.mlsKind445InvalidReason(groupIdHex: String): String? {
    if (kind != NostrKind.MLS_GROUP_MESSAGE) return "invalid_kind:${kind}"
    if (!id.isHex64()) return "invalid_id"
    if (!pubkey.isHex64()) return "invalid_pubkey"
    // Some relay/client parsing paths can leave sig empty even for otherwise valid
    // Nostr events. Do not pre-drop MLS Kind-445 here: Rust/MDK will validate the
    // full event JSON and decide whether the envelope is processable. Pre-dropping
    // caused all fetched MLS messages to be marked dropped=17 reason=invalid_sig,
    // leaving Talk stuck on stale local history.
    val hTags = getTagValues("h")
    if (hTags.isEmpty()) return "missing_h_tag"
    if (hTags.none { it.equals(groupIdHex, ignoreCase = true) }) return "group_id_mismatch:${hTags.joinToString(",")}"
    return null
}

/**
 * Classify FFI failures without logging payload/plaintext/secrets.
 * Retryable means relay out-of-order or local state not caught up yet; keep it out of processedIds.
 */
private fun Exception.isPermanentMlsProcessDropError(): Boolean {
    val msg = (message ?: "").lowercase()
    val permanentSignals = listOf(
        "invalid_base64_content", "invalid base64", "malformed_content_too_short", "too_short",
        "invalid kind", "wrong kind", "missing h", "wrong h", "group mismatch",
        "bad signature", "invalid signature", "not a nostr event", "invalid event json"
    )
    return permanentSignals.any { msg.contains(it) }
}

private fun Exception.isRetryableMlsProcessError(): Boolean = !isPermanentMlsProcessDropError()

suspend fun NostrRepository.sendMlsMessage(groupIdHex: String, content: String): Boolean {
    val rustClient = client.getRustClient() ?: return false
    return withContext(Dispatchers.IO) {
        try {
            // グループのリレーを動的追加（相手側クライアントが見れるように）
            val groupInfo = try { rustClient.mlsGetGroupInfo(groupIdHex) } catch (_: Exception) { null }
            val groupRelays = groupInfo?.relays ?: emptyList()
            val inboxRelays = resolveMlsInboxRelaysForMembers(groupInfo?.memberPubkeys.orEmpty())
            val publishRelays = mlsPublishRelays(groupRelays + inboxRelays)
            for (relay in groupRelays) {
                try { client.addRelay(relay) } catch (_: Exception) { }
            }

            // Marmot MIP-02 interop: after Welcome/join, MDK can require a
            // self-update before this installation's application messages are
            // decryptable by the peer. Logs showed Android kind:445 events were
            // fetched by iOS but stayed state_not_ready/redacted_error while
            // iOS->Android worked. If Rust says this group needs self-update,
            // publish+merge that commit first, wait a moment so relays order the
            // commit before the following application message, then create the
            // app message from the advanced epoch.
            // Interop hardening: do NOT create/merge a self-update commit immediately
            // before an application message. Peers may receive the following app message
            // before the commit and then cannot decrypt it. iOS suppresses the same
            // automatic self-update for WhiteNoise/Marmot interop.
            val needsSelfUpdate = try {
                rustClient.mlsGroupsNeedingSelfUpdate(0uL).any { it.equals(groupIdHex, ignoreCase = true) }
            } catch (_: Exception) { false }
            if (needsSelfUpdate) {
                android.util.Log.w("NostrRepository", "sendMlsMessage: self-update needed but suppressed for interop group=" + groupIdHex)
            }

            val ffiMsg = rustClient.mlsCreateMessage(groupIdHex, content)
            val published = publishMlsKind445(ffiMsg, publishRelays)
            if (published) {
                // Do NOT pre-register the just-published event as processed. mlsCreateMessage()
                // does not guarantee the application plaintext is already present in MDK
                // history; if we skip our own relay echo, the sender UI can remain empty.
                // The event will be marked processed only after mlsProcessMessageResult()
                // stores/returns it during the next fetch.
                android.util.Log.d("NostrRepository", "sendMlsMessage: published group=$groupIdHex relays=${mlsPublishRelays(groupRelays + inboxRelays).size}")
            }
            published
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "sendMlsMessage failed: ${mlsRedactedError(e)}")
            false
        }
    }
}

suspend fun NostrRepository.createDmGroup(partnerPubkey: String): MlsGroup? {
    val rustClient = client.getRustClient() ?: return null
    ensureKeyPackagePublished()

    return withContext(Dispatchers.IO) {
        try {
            val relays = myMlsInboxRelays().take(4)
            val ffiGroup = rustClient.mlsCreateGroup(
                name = "", adminPubkeys = listOf(myPubkeyHex), relays = relays
            )

            val kpEvent = fetchKeyPackage(partnerPubkey, relays)
            if (kpEvent == null) {
                android.util.Log.w(
                    "NostrRepository",
                    "createDmGroup: abort local-only DM; peer KeyPackage not found partner=" + mlsLogPrefix(partnerPubkey)
                )
                cache.markGroupAsLeft(ffiGroup.groupIdHex)
                return@withContext null
            }
            // MIP-00 identity/security: never mutate a peer-signed KeyPackage event.
            val kpJson = json.encodeToString(NostrEvent.serializer(), kpEvent)
            val addResult = rustClient.mlsAddMember(ffiGroup.groupIdHex, kpJson)
            val inboxRelays = resolveMlsInboxRelaysForMembers(listOf(partnerPubkey))
            val addPublished = publishAddMemberCommitWelcomeAndMerge(
                groupIdHex = ffiGroup.groupIdHex,
                commitEventData = addResult.commitEventData,
                welcomeEventData = addResult.welcomeEventData,
                keyPackageEventId = kpEvent.id,
                publishRelays = mlsPublishRelays(ffiGroup.relays + relays + inboxRelays),
                context = "createDmGroup"
            )
            if (!addPublished) {
                cache.markGroupAsLeft(ffiGroup.groupIdHex)
                return@withContext null
            }

            val freshGroup = try { rustClient.mlsGetGroupInfo(ffiGroup.groupIdHex) } catch (_: Exception) { null }
            val memberPubkeys = freshGroup?.memberPubkeys ?: listOf(myPubkeyHex, partnerPubkey).distinct()
            val profiles = fetchProfiles(memberPubkeys)
            MlsGroup(
                groupIdHex = ffiGroup.groupIdHex,
                name = ffiGroup.name, description = ffiGroup.description,
                adminPubkeys = ffiGroup.adminPubkeys, memberPubkeys = memberPubkeys,
                relays = ffiGroup.relays, createdAt = ffiGroup.createdAt.toLong(),
                epoch = ffiGroup.epoch.toLong(), isDm = true,
                memberProfiles = memberPubkeys.mapNotNull { pk -> profiles[pk]?.let { pk to it } }.toMap()
            )
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "createDmGroup failed: ${mlsRedactedError(e)}")
            null
        }
    }
}

suspend fun NostrRepository.createGroupChat(name: String, memberPubkeys: List<String>): MlsGroup? {
    val rustClient = client.getRustClient() ?: return null
    ensureKeyPackagePublished()

    return withContext(Dispatchers.IO) {
        try {
            val relays = myMlsInboxRelays().take(4)
            val ffiGroup = rustClient.mlsCreateGroup(
                name = name, adminPubkeys = listOf(myPubkeyHex), relays = relays
            )

            val inboxRelays = resolveMlsInboxRelaysForMembers(memberPubkeys)

            // KeyPackage 取得: Kind 30443 + legacy 443。ローカル consumed set にある event id は再利用しない。
            for (memberPubkey in memberPubkeys.distinct()) {
                val kpEvent = fetchKeyPackage(memberPubkey, relays) ?: continue
                try {
                    // MIP-00 identity/security: never mutate a peer-signed KeyPackage event.
                    val kpJson = json.encodeToString(NostrEvent.serializer(), kpEvent)
                    val addResult = rustClient.mlsAddMember(ffiGroup.groupIdHex, kpJson)
                    publishAddMemberCommitWelcomeAndMerge(
                        groupIdHex = ffiGroup.groupIdHex,
                        commitEventData = addResult.commitEventData,
                        welcomeEventData = addResult.welcomeEventData,
                        keyPackageEventId = kpEvent.id,
                        publishRelays = mlsPublishRelays(ffiGroup.relays + relays + inboxRelays),
                        context = "createGroupChat"
                    )
                } catch (e: Exception) {
                    android.util.Log.w("NostrRepository", "createGroupChat: add member failed member=${mlsLogPrefix(memberPubkey)}: ${mlsRedactedError(e)}")
                }
            }

            val freshGroup = try { rustClient.mlsGetGroupInfo(ffiGroup.groupIdHex) } catch (_: Exception) { null }
            val allMembers = freshGroup?.memberPubkeys ?: (listOf(myPubkeyHex) + memberPubkeys).distinct()
            val profiles = fetchProfiles(allMembers)
            MlsGroup(
                groupIdHex = ffiGroup.groupIdHex,
                name = ffiGroup.name, description = ffiGroup.description,
                adminPubkeys = ffiGroup.adminPubkeys, memberPubkeys = allMembers,
                relays = ffiGroup.relays, createdAt = ffiGroup.createdAt.toLong(),
                epoch = ffiGroup.epoch.toLong(), isDm = false,
                memberProfiles = allMembers.mapNotNull { pk -> profiles[pk]?.let { pk to it } }.toMap()
            )
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "createGroupChat failed: ${mlsRedactedError(e)}")
            null
        }
    }
}

suspend fun NostrRepository.leaveGroup(groupIdHex: String): Boolean {
    val rustClient = client.getRustClient() ?: return false
    return withContext(Dispatchers.IO) {
        try {
            try { rustClient.mlsMergePendingCommit(groupIdHex) } catch (_: Exception) { }
            val groupRelays = try {
                rustClient.mlsGetGroupInfo(groupIdHex)?.relays ?: emptyList()
            } catch (_: Exception) { emptyList() }
            val ffiMsg = rustClient.mlsLeaveGroup(groupIdHex)
            publishMlsKind445(ffiMsg, mlsPublishRelays(groupRelays))
            cache.markGroupAsLeft(groupIdHex)
            mlsProcessedIds.remove(groupIdHex)
            true
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "leaveGroup failed: ${mlsRedactedError(e)}")
            false
        }
    }
}

suspend fun NostrRepository.addMemberToGroup(groupIdHex: String, memberPubkey: String): Boolean {
    val rustClient = client.getRustClient() ?: return false
    return withContext(Dispatchers.IO) {
        try {
            val relays = myMlsKeyPackageRelays().take(8)
            val kpEvent = fetchKeyPackage(memberPubkey, relays) ?: return@withContext false
            // MIP-00 identity/security: never mutate a peer-signed KeyPackage event.
            val kpJson = json.encodeToString(NostrEvent.serializer(), kpEvent)
            val groupRelays = try {
                rustClient.mlsGetGroupInfo(groupIdHex)?.relays ?: emptyList()
            } catch (_: Exception) { emptyList() }
            val inboxRelays = resolveMlsInboxRelaysForMembers(listOf(memberPubkey))
            val addResult = rustClient.mlsAddMember(groupIdHex, kpJson)
            publishAddMemberCommitWelcomeAndMerge(
                groupIdHex = groupIdHex,
                commitEventData = addResult.commitEventData,
                welcomeEventData = addResult.welcomeEventData,
                keyPackageEventId = kpEvent.id,
                publishRelays = mlsPublishRelays(groupRelays + relays + inboxRelays),
                context = "addMemberToGroup"
            )
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "addMemberToGroup failed: ${mlsRedactedError(e)}")
            false
        }
    }
}

suspend fun NostrRepository.removeMemberFromGroup(groupIdHex: String, memberPubkey: String): Boolean {
    val rustClient = client.getRustClient() ?: return false
    return withContext(Dispatchers.IO) {
        try {
            val groupRelays = try {
                rustClient.mlsGetGroupInfo(groupIdHex)?.relays ?: emptyList()
            } catch (_: Exception) { emptyList() }
            val ffiMsg = rustClient.mlsRemoveMember(groupIdHex, memberPubkey)
            publishMlsKind445(ffiMsg, mlsPublishRelays(groupRelays))
            try { rustClient.mlsMergePendingCommit(groupIdHex) } catch (_: Exception) { }
            true
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "removeMemberFromGroup failed: ${mlsRedactedError(e)}")
            false
        }
    }
}


suspend fun NostrRepository.repairMlsGroupHistory(groupIdHex: String): List<MlsMessage> {
    val rustClient = client.getRustClient() ?: return emptyList()
    return withContext(Dispatchers.IO) {
        try {
            try { rustClient.mlsClearPendingCommit(groupIdHex) } catch (_: Exception) { }
            mlsProcessedIds.remove(groupIdHex)
            mlsMessageRetryQueues.remove(groupIdHex)
            fetchMlsMessages(groupIdHex, repairFull = true)
        } catch (e: Exception) {
            android.util.Log.w("NostrRepository", "repairMlsGroupHistory($groupIdHex): ${mlsRedactedError(e)}")
            getLocalMlsMessages(groupIdHex)
        }
    }
}

/**
 * Issue #183: deep peer-epoch catch-up.
 *
 * Called when standard repair (`repairMlsGroupHistory` / `fetchMlsMessages(repairFull = true)`)
 * still leaves `mlsStateGapCount > 0` for a DM. Pulls a wider Kind-445 window
 * (no history-watermark filter, higher limit, longer timeout) and hands the
 * raw events + every cached wrapper Rust has stored over to
 * `catch_up_to_peer`. The Rust side replays them in `created_at` order
 * across up to 8 retry passes, applies any missing Commits, and reports
 * whether the local epoch is now usable.
 *
 * Receive-path semantics: this function never calls
 * `mlsClearPendingCommit` / `mlsMergePendingCommit`. PR #180's invariant
 * (the receive path must not tear down our own in-flight commits) is
 * preserved (AC3).
 *
 * Returns null when the Rust client is not ready or the group is unknown
 * to MDK; callers should treat that as `MlsRecoveryStatus.Unknown` and
 * fall back to standard polling.
 */
suspend fun NostrRepository.deepCatchUpMlsGroup(groupIdHex: String): MlsDeepCatchUpResult? {
    val rustClient = client.getRustClient() ?: return null

    return withContext(Dispatchers.IO) {
        try {
            // Resolve relay set the same way fetchMlsMessages does so we hit
            // the same group + inbox relays the peer publishes to.
            val groupInfo = try { rustClient.mlsGetGroupInfo(groupIdHex) } catch (_: Exception) { null }
            if (groupInfo == null || !isCurrentAccountMlsGroup(groupInfo.memberPubkeys)) {
                android.util.Log.w(
                    "NostrRepository",
                    "deepCatchUpMlsGroup($groupIdHex): unknown / cross-account group"
                )
                return@withContext null
            }
            val groupRelays = groupInfo.relays
            val inboxRelays = resolveMlsInboxRelaysForMembers(groupInfo.memberPubkeys)
            val allRelays = mlsPublishRelays(groupRelays + inboxRelays)

            // Wider pull than repairFull: no history-watermark filter, no
            // per-event cooldown, larger limit, longer timeout. This is the
            // "look harder for the missing Commit" pass.
            val filter = NostrClient.Filter(
                kinds = listOf(NostrKind.MLS_GROUP_MESSAGE),
                tags = mapOf("h" to listOf(groupIdHex)),
                limit = 2_000
            )
            val rawEvents = if (allRelays.isNotEmpty()) {
                client.fetchEventsFrom(allRelays, filter, timeoutMs = 25_000)
            } else {
                client.fetchEvents(filter, timeoutMs = 20_000)
            }.distinctBy { it.id }

            android.util.Log.d(
                "NostrRepository",
                "deepCatchUpMlsGroup($groupIdHex): relays=${allRelays.size} fetched=${rawEvents.size}"
            )

            // Serialize the relay batch and hand it to Rust along with any
            // cached wrappers Rust itself stored from earlier polls.
            val candidatesJson = rawEvents.mapNotNull { ev ->
                try { json.encodeToString(NostrEvent.serializer(), ev) } catch (_: Exception) { null }
            }

            val report = try {
                rustClient.mlsCatchUpToPeer(groupIdHex, candidatesJson)
            } catch (e: Exception) {
                android.util.Log.w(
                    "NostrRepository",
                    "deepCatchUpMlsGroup($groupIdHex) FFI failed: ${mlsRedactedError(e)}"
                )
                return@withContext null
            }

            val mapped = when (report.status) {
                uniffi.nurunuru.FfiMlsCatchUpStatus.RECOVERED -> MlsRecoveryStatus.Healthy
                uniffi.nurunuru.FfiMlsCatchUpStatus.PARTIALLY_RECOVERED -> MlsRecoveryStatus.Recovering
                uniffi.nurunuru.FfiMlsCatchUpStatus.NOT_RECOVERABLE -> MlsRecoveryStatus.NotRecoverable
                uniffi.nurunuru.FfiMlsCatchUpStatus.NO_SUCH_GROUP -> MlsRecoveryStatus.Unknown
            }
            mlsRecoveryStatuses[groupIdHex] = mapped

            // If Rust advanced the epoch, re-run the normal pull so the
            // session-only processed-ids cache picks up the newly decryptable
            // application messages and the UI sees them on the next stream tick.
            if (report.epochAfter > report.epochBefore) {
                mlsProcessedIds.remove(groupIdHex)
                mlsMessageRetryQueues.remove(groupIdHex)
                mlsRetryableStateCounts.remove(groupIdHex)
                fetchMlsMessages(groupIdHex, repairFull = true)
            }

            android.util.Log.i(
                "NostrRepository",
                "deepCatchUpMlsGroup($groupIdHex): status=${report.status} " +
                    "epoch=${report.epochBefore}->${report.epochAfter} " +
                    "apps=${report.applicationMessagesApplied} commits=${report.commitsApplied} " +
                    "unresolved=${report.stillUnprocessable} cacheHits=${report.cacheHits}"
            )

            MlsDeepCatchUpResult(
                groupIdHex = groupIdHex,
                status = mapped,
                epochBefore = report.epochBefore.toLong(),
                epochAfter = report.epochAfter.toLong(),
                candidatesConsidered = report.candidatesConsidered.toInt(),
                applicationMessagesApplied = report.applicationMessagesApplied.toInt(),
                commitsApplied = report.commitsApplied.toInt(),
                stillUnprocessable = report.stillUnprocessable.toInt(),
                cacheHits = report.cacheHits.toInt()
            )
        } catch (e: Exception) {
            android.util.Log.e(
                "NostrRepository",
                "deepCatchUpMlsGroup($groupIdHex) failed: ${mlsRedactedError(e)}"
            )
            null
        }
    }
}

/**
 * Issue #183: best-effort periodic prune of the Rust replay-cache sidecar.
 * Safe to call at most once per app session (no-op when the cache file does
 * not exist yet).
 */
suspend fun NostrRepository.pruneMlsReplayCache(): Long = withContext(Dispatchers.IO) {
    val rustClient = client.getRustClient() ?: return@withContext 0L
    try {
        rustClient.mlsPruneReplayCache().toLong()
    } catch (e: Exception) {
        android.util.Log.w(
            "NostrRepository",
            "pruneMlsReplayCache failed: ${mlsRedactedError(e)}"
        )
        0L
    }
}

/**
 * Issue #183 fallback (AC2): recreate the DM with `partnerPubkey` from
 * scratch when [deepCatchUpMlsGroup] reports [MlsRecoveryStatus.NotRecoverable].
 *
 * This is the user-facing equivalent of the issue report's "workaround A"
 * (leave the DM on both ends and recreate). We:
 *
 * 1. Leave the old group locally (and publish a leave Commit so the peer
 *    can prune their side).
 * 2. Create a fresh DM group anchored at epoch 0 — both ends realign.
 * 3. Clear the cached recovery status for the old group so stale banners
 *    do not linger in the UI.
 *
 * Returns the new [MlsGroup] on success, or null when the partner's
 * KeyPackage could not be fetched (caller should surface a user-facing
 * "相手の公開鍵が見つかりません" error).
 */
suspend fun NostrRepository.recreateDmConversation(
    oldGroupIdHex: String,
    partnerPubkey: String
): MlsGroup? = withContext(Dispatchers.IO) {
    try {
        // Best-effort leave on the old group. We don't fail the recreate if
        // the leave commit cannot publish — the local SQLite is already
        // marked-as-left, which is what the local UI cares about.
        runCatching { leaveGroup(oldGroupIdHex) }
        clearMlsRecoveryStatus(oldGroupIdHex)
        mlsProcessedIds.remove(oldGroupIdHex)
        mlsMessageRetryQueues.remove(oldGroupIdHex)
        mlsRetryableStateCounts.remove(oldGroupIdHex)

        val fresh = createDmGroup(partnerPubkey)
        if (fresh != null) {
            android.util.Log.i(
                "NostrRepository",
                "recreateDmConversation: old=$oldGroupIdHex new=${fresh.groupIdHex}"
            )
        } else {
            android.util.Log.w(
                "NostrRepository",
                "recreateDmConversation: failed to create fresh DM for partner=${mlsLogPrefix(partnerPubkey)}"
            )
        }
        fresh
    } catch (e: Exception) {
        android.util.Log.e(
            "NostrRepository",
            "recreateDmConversation($oldGroupIdHex) failed: ${mlsRedactedError(e)}"
        )
        null
    }
}

fun NostrRepository.hideMlsGroupLocally(groupIdHex: String) {
    cache.markGroupAsLeft(groupIdHex)
    mlsProcessedIds.remove(groupIdHex)
    mlsMessageRetryQueues.remove(groupIdHex)
    prefs.publicKeyHex?.let { pubkey -> cache.removeGroupFromCache(pubkey, groupIdHex, json) }
}

suspend fun NostrRepository.forceRepublishMyKeyPackageIfNeeded() {
    withContext(Dispatchers.IO) {
        try {
            publishKeyPackage(forceNewMaterial = true)
            publishMlsRelayLists()
            android.util.Log.d("NostrRepository", "forceRepublishMyKeyPackageIfNeeded: republished key package + relay lists")
        } catch (e: Exception) {
            android.util.Log.w("NostrRepository", "forceRepublishMyKeyPackageIfNeeded: ${mlsRedactedError(e)}")
        }
    }
}

private suspend fun NostrRepository.publishMlsRelayLists() {
    val keyPackageRelays = myMlsKeyPackageRelays().take(12)
    val inboxRelays = myMlsInboxRelays().take(12)
    val discoveryRelays = mlsDiscoveryRelays(keyPackageRelays + inboxRelays).take(20)
    if (keyPackageRelays.isEmpty() && inboxRelays.isEmpty()) return
    val keyPackageTags = keyPackageRelays.map { listOf("relay", it) } + listOf(listOf("alt", "MLS KeyPackage relay list"))
    val inboxTags = inboxRelays.map { listOf("relay", it) }
    for (relay in discoveryRelays) { try { client.addRelay(relay) } catch (_: Exception) { } }
    try {
        if (isExternalSigner()) {
            signAndPublishGetId(client.getRustClient()?.createUnsignedEvent(NostrKind.MLS_KEY_PACKAGE_RELAYS.toUInt(), "", keyPackageTags, myPubkeyHex) ?: return)
            signAndPublishGetId(client.getRustClient()?.createUnsignedEvent(NostrKind.DM_RELAY_LIST.toUInt(), "", inboxTags, myPubkeyHex) ?: return)
        } else {
            client.getRustClient()?.publishEvent(NostrKind.MLS_KEY_PACKAGE_RELAYS.toUInt(), "", keyPackageTags)
            client.getRustClient()?.publishEvent(NostrKind.DM_RELAY_LIST.toUInt(), "", inboxTags)
        }
    } catch (e: Exception) {
        android.util.Log.w("NostrRepository", "publishMlsRelayLists: ${mlsRedactedError(e)}")
    }
}

suspend fun NostrRepository.ensureKeyPackagePublished() {
    val pubkey = prefs.publicKeyHex ?: return
    val rustClient = client.getRustClient() ?: return
    withContext(Dispatchers.IO) {
        try {
            val consumedIds = prefs.mlsConsumedKeyPackageEventIds
            val existing = client.fetchEventsFrom(
                mlsDiscoveryRelays(),
                NostrClient.Filter(
                    // Fetch both Marmot canonical 30443 and legacy 443 fallback.
                    kinds = listOf(NostrKind.MLS_KEY_PACKAGE, NostrKind.MLS_KEY_PACKAGE_LEGACY),
                    authors = listOf(pubkey), limit = 10
                ), timeoutMs = 5_000
            ).filter { it.id.lowercase() !in consumedIds }
            val latest = existing
                .filter { it.kind == NostrKind.MLS_KEY_PACKAGE }
                .maxByOrNull { it.createdAt }
                ?: existing.filter { it.kind == NostrKind.MLS_KEY_PACKAGE_LEGACY }.maxByOrNull { it.createdAt }
            val hasRelays = latest?.tags?.any { tag ->
                tag.firstOrNull() == "relays" && tag.size > 1
            } ?: false
            val localPublishedId = prefs.mlsPublishedKeyPackageEventId
            val localBackedRelayEvent = latest != null && hasRelays &&
                localPublishedId?.equals(latest.id, ignoreCase = true) == true

            // Do not trust arbitrary relay-visible KeyPackages as usable after reinstall,
            // database reset, or old-build publication. A Welcome made from a stale public
            // KeyPackage fails as missing_or_stale_key_package because the local MDK init_key
            // material is not present. Only a KeyPackage we published from this installation
            // (tracked by prefs.mlsPublishedKeyPackageEventId) is considered locally backed.
            if (localBackedRelayEvent) {
                publishMlsRelayLists()
                return@withContext
            }

            android.util.Log.d(
                "NostrRepository",
                "ensureKeyPackagePublished: publishing fresh local-backed KeyPackage latest=" +
                    latest?.id.orEmpty() + " hasRelays=" + hasRelays + " local=" + localPublishedId.orEmpty()
            )
            publishKeyPackage()
        } catch (e: Exception) {
            android.util.Log.w("NostrRepository", "ensureKeyPackagePublished: ${mlsRedactedError(e)}")
        }
    }
}

/**
 * Welcome process means our currently published KeyPackage was consumed by the inviter.
 * Track it locally, best-effort delete it from relays, remove matching local init-key material,
 * then publish a fresh KeyPackage so future invites do not reuse the consumed one.
 */
private suspend fun NostrRepository.rotateOwnKeyPackageAfterWelcome() {
    val oldEventId = prefs.mlsPublishedKeyPackageEventId?.trim()?.takeIf { it.isNotEmpty() } ?: return
    if (oldEventId.lowercase() in prefs.mlsConsumedKeyPackageEventIds) return

    prefs.addMlsConsumedKeyPackageEventId(oldEventId)

    val rustClient = client.getRustClient()
    val oldEvent = try {
        client.fetchEvents(NostrClient.Filter(ids = listOf(oldEventId), limit = 1), timeoutMs = 3_000)
            .firstOrNull { it.id.equals(oldEventId, ignoreCase = true) }
    } catch (_: Exception) { null }

    if (rustClient != null && oldEvent != null) {
        try {
            val oldEventJson = json.encodeToString(NostrEvent.serializer(), oldEvent)
            rustClient.mlsDeleteConsumedKeyPackageFromEventJson(oldEventJson)
        } catch (e: Exception) {
            android.util.Log.w("NostrRepository", "rotateOwnKeyPackageAfterWelcome: local consumed KeyPackage cleanup failed: ${mlsRedactedError(e)}")
        }
    }

    try {
        // NIP-09 delete is relay best-effort. The local consumed set above remains authoritative.
        deleteEvent(oldEventId, "consumed MLS KeyPackage")
    } catch (e: Exception) {
        android.util.Log.w("NostrRepository", "rotateOwnKeyPackageAfterWelcome: best-effort delete failed: ${mlsRedactedError(e)}")
    }

    val existingDTag = oldEvent?.takeIf { it.kind == NostrKind.MLS_KEY_PACKAGE }?.getTagValue("d")?.takeIf { it.isNotBlank() }
    prefs.mlsPublishedKeyPackageEventId = null
    prefs.mlsPublishedKeyPackageAt = 0L
    publishKeyPackage(existingDTag = existingDTag)
}

// ─── Private Helpers ─────────────────────────────────────────────────────────

private suspend fun NostrRepository.fetchKeyPackage(
    pubkey: String, fallbackRelays: List<String>
): NostrEvent? {
    val kpFilter = NostrClient.Filter(
        kinds = listOf(NostrKind.MLS_KEY_PACKAGE, NostrKind.MLS_KEY_PACKAGE_LEGACY),
        authors = listOf(pubkey), limit = 10
    )
    val consumedIds = prefs.mlsConsumedKeyPackageEventIds

    fun best(events: List<NostrEvent>): NostrEvent? {
        val usable = events.filter { it.id.lowercase() !in consumedIds }
        // Marmot canonical KeyPackage is kind 30443; legacy kind 443 is fallback.
        return usable.filter { it.kind == NostrKind.MLS_KEY_PACKAGE }.maxByOrNull { it.createdAt }
            ?: usable.filter { it.kind == NostrKind.MLS_KEY_PACKAGE_LEGACY }.maxByOrNull { it.createdAt }
            ?: usable.maxByOrNull { it.createdAt }
    }

    // iOS parity: first resolve peer-advertised KeyPackage relays via kind:10051,
    // then fall back to the Marmot discovery pool.
    val discoveryRelays = mlsDiscoveryRelays(fallbackRelays)
    val kpRelayFilter = NostrClient.Filter(
        kinds = listOf(NostrKind.MLS_KEY_PACKAGE_RELAYS),
        authors = listOf(pubkey),
        limit = 3
    )
    val advertisedRelays = try {
        client.fetchEventsFrom(discoveryRelays, kpRelayFilter, timeoutMs = 5_000)
            .sortedByDescending { it.createdAt }
            .flatMap { ev -> ev.tags.filter { it.firstOrNull() == "relay" }.mapNotNull { it.getOrNull(1) } }
            .let { canonicalMlsRelays(it) }
    } catch (_: Exception) { emptyList() }

    if (advertisedRelays.isNotEmpty()) {
        best(client.fetchEventsFrom(advertisedRelays, kpFilter, timeoutMs = 6_000))?.let { return it }
    }

    return best(client.fetchEventsFrom(discoveryRelays, kpFilter, timeoutMs = 6_000))
}


/**
 * Resolve recipient inbox relays (kind:10050) for Welcome/Kind-445 fanout.
 * Mirrors Amethyst's computeRelayListToBroadcast fallback: prefer advertised inbox,
 * then discovery/default relays. Empty/localhost/non-ws entries are ignored by canonicalMlsRelays().
 */
private suspend fun NostrRepository.resolveMlsInboxRelaysForMembers(pubkeys: List<String>): List<String> {
    val targets = pubkeys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (targets.isEmpty()) return emptyList()
    val filter = NostrClient.Filter(
        kinds = listOf(NostrKind.DM_RELAY_LIST),
        authors = targets,
        limit = (targets.size * 3).coerceAtLeast(3)
    )
    val discoveryRelays = mlsDiscoveryRelays()
    return try {
        client.fetchEventsFrom(discoveryRelays, filter, timeoutMs = 5_000)
            .sortedByDescending { it.createdAt }
            .flatMap { ev -> ev.tags.filter { it.firstOrNull() == "relay" }.mapNotNull { it.getOrNull(1) } }
            .let { canonicalMlsRelays(it) }
    } catch (_: Exception) { emptyList() }
}


private fun NostrRepository.mlsStableKeyPackageDTag(fallback: String? = null): String {
    prefs.mlsKeyPackageStableDTag?.takeIf { it.isNotBlank() }?.let { return it }
    val generated = fallback?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().lowercase()
    prefs.mlsKeyPackageStableDTag = generated
    return generated
}

private fun applyStableKeyPackageDTag(tags: List<List<String>>, dTag: String): List<List<String>> =
    listOf(listOf("d", dTag)) + tags.filter { it.firstOrNull() != "d" }

private suspend fun NostrRepository.publishKeyPackage(existingDTag: String? = null, forceNewMaterial: Boolean = false) {
    val rustClient = client.getRustClient() ?: return
    try {
        val kpData = rustClient.mlsCreateKeyPackage()
        val keyPackageRelays = myMlsKeyPackageRelays().take(12)
        val discoveryRelays = mlsDiscoveryRelays(keyPackageRelays + myMlsInboxRelays()).take(20)
        val relayTag = listOf("relays") + keyPackageRelays
        val stableD = mlsStableKeyPackageDTag(existingDTag ?: kpData.dTag)
        val tags30443 = applyStableKeyPackageDTag(kpData.tags.filter { it.firstOrNull() != "relays" }, stableD)
            .let { base -> base + listOf(relayTag) }

        // Targeted discovery publish: add both KeyPackage relays and broad discovery relays
        // before raw publishing so Amber/internal-signing paths reach Amethyst/WhiteNoise.
        (keyPackageRelays + discoveryRelays).distinct().forEach { relay ->
            try { client.addRelay(relay) } catch (_: Exception) { }
        }

        val eventId30443 = if (isExternalSigner()) {
            val unsigned = rustClient.createUnsignedEvent(kpData.kind, kpData.content, tags30443, myPubkeyHex)
            signAndPublishGetId(unsigned)
        } else {
            rustClient.publishEvent(kpData.kind, kpData.content, tags30443).takeIf { it.isNotEmpty() }
        }

        // Issue #178 #3: kind:443 publish removed (read path still accepts it for migration).

        publishMlsRelayLists()

        if (eventId30443 != null) {
            prefs.mlsPublishedKeyPackageEventId = eventId30443
            prefs.mlsPublishedKeyPackageAt = System.currentTimeMillis() / 1000
        }
        cleanupSupersededOwnKeyPackages(setOfNotNull(eventId30443), stableD)
        android.util.Log.d("NostrRepository", "publishKeyPackage: 30443=" + (eventId30443 != null) + " stableD=" + stableD + " forceNew=" + forceNewMaterial + " kpRelays=" + keyPackageRelays.size + " discovery=" + discoveryRelays.size)
    } catch (e: Exception) {
        android.util.Log.e("NostrRepository", "publishKeyPackage failed: ${mlsRedactedError(e)}")
    }
}

private suspend fun NostrRepository.cleanupSupersededOwnKeyPackages(keepIds: Set<String>, stableD: String) {
    val pubkey = prefs.publicKeyHex ?: return
    val relays = mlsDiscoveryRelays(myMlsKeyPackageRelays() + myMlsInboxRelays()).take(12)
    val filter = NostrClient.Filter(kinds = listOf(NostrKind.MLS_KEY_PACKAGE, NostrKind.MLS_KEY_PACKAGE_LEGACY), authors = listOf(pubkey), limit = 80)
    try {
        val events = client.fetchEventsFrom(relays, filter, timeoutMs = 5_000).distinctBy { it.id }
        var deleted = 0
        for (ev in events) {
            if (ev.id in keepIds || ev.id.lowercase() in prefs.mlsConsumedKeyPackageEventIds) continue
            val superseded = ev.kind == NostrKind.MLS_KEY_PACKAGE && ev.getTagValue("d") != stableD
            val legacy = ev.kind == NostrKind.MLS_KEY_PACKAGE_LEGACY
            if (superseded || legacy) {
                try { deleteEvent(ev.id, "superseded MLS KeyPackage") } catch (_: Exception) { }
                prefs.addMlsConsumedKeyPackageEventId(ev.id)
                deleted++
            }
        }
        if (deleted > 0) android.util.Log.d("NostrRepository", "cleanupSupersededOwnKeyPackages: deleted=" + deleted)
    } catch (e: Exception) {
        android.util.Log.w("NostrRepository", "cleanupSupersededOwnKeyPackages: " + mlsRedactedError(e))
    }
}

/**
 * Marmot MLS publish target relays.
 *
 * Android/iOS interop: publish/fetch MLS traffic on group relays, selected relays,
 * and common global fallback relays so Bob self-update and follow-up messages are
 * discoverable even when each platform has a different selected relay set.
 */
private fun canonicalMlsRelays(relays: List<String>): List<String> =
    relays
        .map { it.trim().removeSuffix("/") }
        .filter { it.startsWith("wss://") || it.startsWith("ws://") }
        .distinct()

private fun NostrRepository.myMlsKeyPackageRelays(): List<String> {
    val configured = prefs.mlsKeyPackageRelays
    val fallback = prefs.relays.take(3).toList() + DEFAULT_MLS_KEY_PACKAGE_RELAYS
    return canonicalMlsRelays(if (configured.isEmpty()) fallback else configured)
}

private fun NostrRepository.myMlsInboxRelays(): List<String> {
    val configured = prefs.mlsInboxRelays
    val fallback = prefs.relays.take(3).toList() + DEFAULT_MLS_INBOX_RELAYS
    return canonicalMlsRelays(if (configured.isEmpty()) fallback else configured)
}

private fun NostrRepository.mlsDiscoveryRelays(extra: List<String> = emptyList()): List<String> =
    canonicalMlsRelays(extra + prefs.relays.toList() + myMlsKeyPackageRelays() + myMlsInboxRelays() + MLS_INTEROP_RELAYS)

private fun NostrRepository.mlsPublishRelays(groupRelays: List<String> = emptyList()): List<String> =
    scoreAndSortMlsRelays(canonicalMlsRelays(groupRelays + prefs.relays.toList() + myMlsInboxRelays() + MLS_INTEROP_RELAYS))

private fun scoreAndSortMlsRelays(relays: List<String>): List<String> {
    val priority = mapOf(
        "wss://yabu.me" to 100,
        "wss://r.kojira.io" to 95,
        "wss://relay.damus.io" to 90,
        "wss://nos.lol" to 85,
        "wss://relay.primal.net" to 80,
        "wss://relay-jp.nostr.wirednet.jp" to 60,
        "wss://relay.nostr.wirednet.jp" to -50
    )
    return relays.sortedWith(compareByDescending<String> { (mlsRelayHitScores[it] ?: 0) + (priority[it] ?: 0) }.thenBy { it })
}

private fun recordMlsRelayHits(relays: List<String>, eventCount: Int) {
    if (eventCount <= 0) return
    relays.forEach { relay -> mlsRelayHitScores.merge(relay, eventCount) { a, b -> (a + b).coerceAtMost(10_000) } }
}

private data class MlsRawEventSummary(
    val id: String,
    val kind: Int?,
    val pubkey: String,
    val hTags: List<String>,
    val pTags: List<String>
)

private fun mlsRawEventSummary(rawEventJson: String): MlsRawEventSummary? = try {
    val obj = Json.parseToJsonElement(rawEventJson).jsonObject
    val tags = obj["tags"]?.jsonArray?.mapNotNull { tagEl ->
        tagEl.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
    } ?: emptyList()
    MlsRawEventSummary(
        id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        kind = obj["kind"]?.jsonPrimitive?.intOrNull,
        pubkey = obj["pubkey"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        hTags = tags.filter { it.firstOrNull() == "h" }.mapNotNull { it.getOrNull(1) },
        pTags = tags.filter { it.firstOrNull() == "p" }.mapNotNull { it.getOrNull(1) }
    )
} catch (_: Exception) { null }

private suspend fun NostrRepository.connectMlsPublishRelays(relays: List<String>) {
    val rustClient = client.getRustClient() ?: return
    relays.forEach { relay ->
        try { rustClient.addRelay(relay) } catch (e: Exception) {
            android.util.Log.w("NostrRepository", "connectMlsPublishRelays: addRelay failed relay=$relay: ${mlsRedactedError(e)}")
        }
    }
}

/**
 * Kind-445 (MLS message/commit/proposal) を publish。
 * content は MDK がエフェメラル鍵で署名済みの完全なイベントJSON → publishRawEvent 必須。
 */
private suspend fun NostrRepository.publishMlsKind445(
    ffiMsg: FfiEncryptedMessageData,
    publishRelays: List<String> = mlsPublishRelays()
): Boolean {
    val rustClient = client.getRustClient() ?: return false
    val summary = mlsRawEventSummary(ffiMsg.content)
    return try {
        connectMlsPublishRelays(publishRelays)
        val publishedId = rustClient.publishRawEvent(ffiMsg.content)
        android.util.Log.d(
            "NostrRepository",
            "publishMlsKind445: ok event=${summary?.id ?: publishedId} kind=${summary?.kind} h=${summary?.hTags.orEmpty()} relays=${publishRelays.size}"
        )
        true
    } catch (e: Exception) {
        android.util.Log.e(
            "NostrRepository",
            "publishMlsKind445 failed event=${summary?.id.orEmpty()} kind=${summary?.kind} h=${summary?.hTags.orEmpty()} relays=${publishRelays.size}: ${mlsRedactedError(e)}"
        )
        false
    }
}

/**
 * Marmot MIP-02: gift-wrapped Welcome (Kind 1059) を publish。
 * Welcome 1059 の recipient は KeyPackage event owner pubkey (= welcomeData.recipientPubkey)。
 */
private suspend fun NostrRepository.publishMlsWelcome(
    welcomeData: uniffi.nurunuru.FfiWelcomeEventData,
    publishRelays: List<String> = mlsPublishRelays()
): Boolean {
    val rustClient = client.getRustClient() ?: return false
    return try {
        if (welcomeData.giftWrappedEventJson.isNotEmpty()) {
            val summary = mlsRawEventSummary(welcomeData.giftWrappedEventJson)
            connectMlsPublishRelays(publishRelays)
            val publishedId = rustClient.publishRawEvent(welcomeData.giftWrappedEventJson)
            android.util.Log.d(
                "NostrRepository",
                "publishMlsWelcome: ok event=${summary?.id ?: publishedId} kind=${summary?.kind} p=${mlsLogPrefixes(summary?.pTags.orEmpty())} recipient=${mlsLogPrefix(welcomeData.recipientPubkey)} relays=${publishRelays.size}"
            )
            true
        } else {
            android.util.Log.w("NostrRepository", "publishMlsWelcome: no gift-wrap — legacy 444 fallback")
            val tags = welcomeData.tags + listOf(listOf("p", welcomeData.recipientPubkey))
            val ok = if (isExternalSigner()) {
                val unsigned = rustClient.createUnsignedEvent(
                    NostrKind.MLS_WELCOME_INNER.toUInt(), welcomeData.innerRumorJson, tags, myPubkeyHex
                )
                signAndPublish(unsigned)
            } else {
                rustClient.publishEvent(NostrKind.MLS_WELCOME_INNER.toUInt(), welcomeData.innerRumorJson, tags).isNotEmpty()
            }
            android.util.Log.d(
                "NostrRepository",
                "publishMlsWelcome: legacy kind=${NostrKind.MLS_WELCOME_INNER} p=${mlsLogPrefixes(tags.filter { it.firstOrNull() == "p" }.mapNotNull { it.getOrNull(1) })} recipient=${mlsLogPrefix(welcomeData.recipientPubkey)} ok=$ok"
            )
            ok
        }
    } catch (e: Exception) {
        android.util.Log.e(
            "NostrRepository",
            "publishMlsWelcome failed recipient=${mlsLogPrefix(welcomeData.recipientPubkey)}: ${mlsRedactedError(e)}"
        )
        false
    }
}

/**
 * Add-member publish/merge policy shared by DM/group/add-member flows.
 *
 * P4-S2-compatible policy:
 *  - publish Welcome only after commit Kind-445 publish succeeds;
 *  - merge pending commit only after the required publish sequence succeeds;
 *  - on publish/merge failure, clear pending commit so retry can create a fresh commit.
 */
private suspend fun NostrRepository.publishAddMemberCommitWelcomeAndMerge(
    groupIdHex: String,
    commitEventData: FfiEncryptedMessageData,
    welcomeEventData: uniffi.nurunuru.FfiWelcomeEventData,
    keyPackageEventId: String,
    publishRelays: List<String>,
    context: String
): Boolean {
    val rustClient = client.getRustClient() ?: return false
    fun clearPending(reason: String) {
        try { rustClient.mlsClearPendingCommit(groupIdHex) } catch (clearError: Exception) {
            android.util.Log.w(
                "NostrRepository",
                "$context: clear pending failed groupIdHex=$groupIdHex reason=$reason: ${mlsRedactedError(clearError)}"
            )
        }
    }

    val commitPublished = publishMlsKind445(commitEventData, publishRelays)
    if (!commitPublished) {
        clearPending("commit-publish-failed")
        android.util.Log.w("NostrRepository", "$context: commit publish failed; Welcome skipped groupIdHex=$groupIdHex")
        return false
    }

    val welcomePublished = publishMlsWelcome(welcomeEventData, publishRelays)
    if (!welcomePublished) {
        clearPending("welcome-publish-failed")
        android.util.Log.w("NostrRepository", "$context: Welcome publish failed; merge skipped groupIdHex=$groupIdHex")
        return false
    }

    return try {
        rustClient.mlsMergePendingCommit(groupIdHex)
        prefs.addMlsConsumedKeyPackageEventId(keyPackageEventId)
        android.util.Log.d("NostrRepository", "$context: commit+Welcome published and merged groupIdHex=$groupIdHex keyPackageEvent=$keyPackageEventId")
        true
    } catch (e: Exception) {
        clearPending("merge-failed")
        android.util.Log.w("NostrRepository", "$context: merge failed; pending cleared groupIdHex=$groupIdHex: ${mlsRedactedError(e)}")
        false
    }
}

// ─── Legacy DM (read-only, migration only) ───────────────────────────────────

@Deprecated("Use MLS groups for new conversations")
suspend fun NostrRepository.fetchDmConversations(pubkeyHex: String): List<DmConversation> {
    val oneHourAgo = getOneHourAgo()
    val receivedFilter = NostrClient.Filter(
        kinds = listOf(NostrKind.ENCRYPTED_DM),
        tags = mapOf("p" to listOf(pubkeyHex)),
        limit = 200, since = oneHourAgo
    )
    val sentFilter = NostrClient.Filter(
        kinds = listOf(NostrKind.ENCRYPTED_DM),
        authors = listOf(pubkeyHex),
        limit = 200, since = oneHourAgo
    )
    val allEvents = coroutineScope {
        val received = async { client.fetchEvents(receivedFilter, 5_000) }
        val sent = async { client.fetchEvents(sentFilter, 5_000) }
        received.await() + sent.await()
    }

    val conversations = mutableMapOf<String, MutableList<NostrEvent>>()
    for (event in allEvents) {
        val partner = if (event.pubkey == pubkeyHex) {
            event.getTagValue("p") ?: continue
        } else { event.pubkey }
        conversations.getOrPut(partner) { mutableListOf() }.add(event)
    }

    val profiles = fetchProfiles(conversations.keys.toList())
    return conversations.map { (partnerKey, events) ->
        val lastEvent = events.maxByOrNull { it.createdAt }!!
        DmConversation(
            partnerPubkey = partnerKey, partnerProfile = profiles[partnerKey],
            lastMessage = "...", lastMessageTime = lastEvent.createdAt, unreadCount = 0
        )
    }.sortedByDescending { it.lastMessageTime }
}
