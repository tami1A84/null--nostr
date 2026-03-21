package io.nurunuru.app.data

import io.nurunuru.app.data.models.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uniffi.nurunuru.FfiEncryptedMessageData

// ─── MLS Groups (NIP-EE) ──────────────────────────────────────────────────────

fun NostrRepository.getCachedMlsGroups(): List<MlsGroup> {
    val pubkey = prefs.publicKeyHex ?: return emptyList()
    val raw = cache.getCachedMlsGroups(pubkey) ?: return emptyList()
    return try {
        val allGroups = json.decodeFromString<List<MlsGroup>>(raw)
        val leftIds = cache.getLeftGroupIds()
        allGroups.filter { it.groupIdHex !in leftIds }
    } catch (_: Exception) { emptyList() }
}

suspend fun NostrRepository.fetchMlsGroups(): List<MlsGroup> {
    val pubkey = prefs.publicKeyHex ?: return emptyList()
    val rustClient = client.getRustClient() ?: return getCachedMlsGroups()

    return withContext(Dispatchers.IO) {
        try {
            // 1. Fetch unprocessed Kind-444 (Welcome) events from relay and process them
            val welcomeFilter = NostrClient.Filter(
                kinds = listOf(NostrKind.MLS_WELCOME),
                tags = mapOf("p" to listOf(pubkey)),
                limit = 50
            )
            val welcomeEvents = client.fetchEvents(welcomeFilter, timeoutMs = 5_000)
            for (event in welcomeEvents) {
                try {
                    rustClient.mlsProcessWelcome(json.encodeToString(NostrEvent.serializer(), event))
                } catch (_: Exception) { /* skip already-processed welcomes */ }
            }

            // 2. List all groups from Rust MLS state
            val ffiGroups = rustClient.mlsListGroups()
            val leftIds = cache.getLeftGroupIds()
            val activeGroups = ffiGroups.filter { it.groupIdHex !in leftIds }

            // 3. Enrich with member profiles
            val allMemberPubkeys = activeGroups.flatMap { it.memberPubkeys }.distinct()
            val profiles = fetchProfiles(allMemberPubkeys)

            // 4. Attach last-message from in-memory cache
            val result = activeGroups.map { ffi ->
                val cachedMsgs = mlsCachedMessages[ffi.groupIdHex]
                val lastMsg = cachedMsgs?.maxByOrNull { it.timestamp }
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
                        val p = profiles[pk]
                        if (p != null) pk to p else null
                    }.toMap(),
                    lastMessage = lastMsg?.content ?: "",
                    lastMessageTime = lastMsg?.timestamp ?: ffi.createdAt.toLong()
                )
            }.sortedByDescending { it.lastMessageTime }

            // 5. Persist to cache
            try {
                cache.setCachedMlsGroups(pubkey, json.encodeToString(result))
            } catch (_: Exception) { }

            result
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "fetchMlsGroups failed: ${e.message}", e)
            getCachedMlsGroups()
        }
    }
}

/** Rust SQLite のローカル履歴のみを即時返す（ネットワーク不要・キャッシュファースト用）。 */
suspend fun NostrRepository.getLocalMlsMessages(groupIdHex: String): List<MlsMessage> {
    val rustClient = client.getRustClient() ?: run {
        return mlsCachedMessages[groupIdHex]?.toList() ?: emptyList()
    }
    return withContext(Dispatchers.IO) {
        try {
            val history = rustClient.mlsGetMessageHistory(groupIdHex, 100u)
            val cachedMessages = mlsCachedMessages.getOrPut(groupIdHex) {
                java.util.concurrent.CopyOnWriteArrayList()
            }
            for (ffi in history) {
                val alreadyCached = cachedMessages.any {
                    it.senderPubkey == ffi.senderPubkey &&
                    it.timestamp == ffi.timestamp.toLong() &&
                    it.content == ffi.content
                }
                if (!alreadyCached) {
                    cachedMessages.add(MlsMessage(
                        id = "${ffi.senderPubkey}_${ffi.timestamp}",
                        senderPubkey = ffi.senderPubkey,
                        content = ffi.content,
                        timestamp = ffi.timestamp.toLong(),
                        groupIdHex = groupIdHex
                    ))
                }
            }
            val senderPubkeys = cachedMessages.map { it.senderPubkey }.distinct()
            val profiles = fetchProfiles(senderPubkeys)
            cachedMessages.map { msg ->
                msg.copy(senderProfile = profiles[msg.senderPubkey])
            }.distinctBy { it.id }.sortedBy { it.timestamp }
        } catch (e: Exception) {
            android.util.Log.w("NostrRepository", "getLocalMlsMessages($groupIdHex) failed: ${e.message}")
            mlsCachedMessages[groupIdHex]?.toList() ?: emptyList()
        }
    }
}

suspend fun NostrRepository.fetchMlsMessages(groupIdHex: String): List<MlsMessage> {
    val rustClient = client.getRustClient() ?: return emptyList()

    return withContext(Dispatchers.IO) {
        try {
            val processedIds = mlsProcessedIds.getOrPut(groupIdHex) {
                java.util.concurrent.ConcurrentHashMap.newKeySet()
            }
            val cachedMessages = mlsCachedMessages.getOrPut(groupIdHex) {
                java.util.concurrent.CopyOnWriteArrayList()
            }

            // Fetch Kind-445 events for this group from relay
            val filter = NostrClient.Filter(
                kinds = listOf(NostrKind.MLS_GROUP_MESSAGE),
                tags = mapOf("h" to listOf(groupIdHex)),
                limit = 100
            )
            val events = client.fetchEvents(filter, timeoutMs = 5_000)

            for (event in events) {
                // Atomic add: skip already-processed event IDs (avoids MLS epoch mismatches)
                if (!processedIds.add(event.id)) continue
                try {
                    val eventJson = json.encodeToString(NostrEvent.serializer(), event)
                    val decrypted = rustClient.mlsProcessMessage(groupIdHex, eventJson)
                    if (decrypted.content.isNotBlank()) {
                        cachedMessages.add(MlsMessage(
                            id = event.id,
                            senderPubkey = decrypted.senderPubkey,
                            content = decrypted.content,
                            timestamp = decrypted.timestamp.toLong(),
                            groupIdHex = groupIdHex
                        ))
                    }
                } catch (_: Exception) { /* skip undecryptable messages */ }
            }

            // Also get local history from Rust (includes messages sent by self)
            try {
                val history = rustClient.mlsGetMessageHistory(groupIdHex, 100u)
                for (ffi in history) {
                    val alreadyCached = cachedMessages.any {
                        it.senderPubkey == ffi.senderPubkey &&
                        it.timestamp == ffi.timestamp.toLong() &&
                        it.content == ffi.content
                    }
                    if (!alreadyCached) {
                        cachedMessages.add(MlsMessage(
                            id = "${ffi.senderPubkey}_${ffi.timestamp}",
                            senderPubkey = ffi.senderPubkey,
                            content = ffi.content,
                            timestamp = ffi.timestamp.toLong(),
                            groupIdHex = groupIdHex
                        ))
                    }
                }
            } catch (_: Exception) { }

            // Enrich with sender profiles
            val senderPubkeys = cachedMessages.map { it.senderPubkey }.distinct()
            val profiles = fetchProfiles(senderPubkeys)
            val enriched = cachedMessages.map { msg ->
                msg.copy(senderProfile = profiles[msg.senderPubkey])
            }.distinctBy { it.id }
                .sortedBy { it.timestamp }

            enriched
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "fetchMlsMessages($groupIdHex) failed: ${e.message}", e)
            mlsCachedMessages[groupIdHex]?.toList() ?: emptyList()
        }
    }
}

suspend fun NostrRepository.sendMlsMessage(groupIdHex: String, content: String): Boolean {
    val rustClient = client.getRustClient() ?: return false
    return withContext(Dispatchers.IO) {
        try {
            val ffiMsg = rustClient.mlsCreateMessage(groupIdHex, content)
            publishMlsKind445(ffiMsg, groupIdHex)
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "sendMlsMessage failed: ${e.message}", e)
            false
        }
    }
}

suspend fun NostrRepository.createDmGroup(partnerPubkey: String): MlsGroup? {
    val rustClient = client.getRustClient() ?: return null
    ensureKeyPackagePublished()

    return withContext(Dispatchers.IO) {
        try {
            val relays = prefs.relays.take(3).toList()
            val ffiGroup = rustClient.mlsCreateGroup(
                name = "",
                adminPubkeys = listOf(myPubkeyHex),
                relays = relays
            )

            // Fetch partner's key package and add them
            val kpFilter = NostrClient.Filter(
                kinds = listOf(NostrKind.MLS_KEY_PACKAGE),
                authors = listOf(partnerPubkey),
                limit = 1
            )
            val kpEvents = client.fetchEvents(kpFilter, timeoutMs = 5_000)
            val kpEvent = kpEvents.maxByOrNull { it.createdAt }

            if (kpEvent != null) {
                val kpJson = json.encodeToString(NostrEvent.serializer(),
                    patchKeyPackageRelays(kpEvent, relays.ifEmpty { prefs.relays.take(3).toList() }))
                val addResult = rustClient.mlsAddMember(ffiGroup.groupIdHex, kpJson)
                publishMlsKind445(addResult.commitEventData, ffiGroup.groupIdHex)
                publishMlsWelcome(addResult.welcomeEventData)
                try { rustClient.mlsMergePendingCommit(ffiGroup.groupIdHex) } catch (_: Exception) { }
            }

            // Re-fetch group to get updated memberPubkeys (ffiGroup is stale — only has creator)
            val freshGroup = try { rustClient.mlsGetGroupInfo(ffiGroup.groupIdHex) } catch (_: Exception) { null }
            val memberPubkeys = freshGroup?.memberPubkeys
                ?: (listOf(myPubkeyHex, partnerPubkey)).distinct()

            val profiles = fetchProfiles(memberPubkeys)
            val group = MlsGroup(
                groupIdHex = ffiGroup.groupIdHex,
                name = ffiGroup.name,
                description = ffiGroup.description,
                adminPubkeys = ffiGroup.adminPubkeys,
                memberPubkeys = memberPubkeys,
                relays = ffiGroup.relays,
                createdAt = ffiGroup.createdAt.toLong(),
                epoch = ffiGroup.epoch.toLong(),
                isDm = true,
                memberProfiles = memberPubkeys.mapNotNull { pk ->
                    val p = profiles[pk]
                    if (p != null) pk to p else null
                }.toMap()
            )

            val pubkey = prefs.publicKeyHex ?: return@withContext group
            val existing = getCachedMlsGroups().toMutableList()
            existing.add(0, group)
            cache.setCachedMlsGroups(pubkey, json.encodeToString(existing))

            group
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "createDmGroup failed: ${e.message}", e)
            null
        }
    }
}

suspend fun NostrRepository.createGroupChat(name: String, memberPubkeys: List<String>): MlsGroup? {
    val rustClient = client.getRustClient() ?: return null
    ensureKeyPackagePublished()

    return withContext(Dispatchers.IO) {
        try {
            val relays = prefs.relays.take(3).toList()
            val ffiGroup = rustClient.mlsCreateGroup(
                name = name,
                adminPubkeys = listOf(myPubkeyHex),
                relays = relays
            )

            // Add all members
            val kpFilter = NostrClient.Filter(
                kinds = listOf(NostrKind.MLS_KEY_PACKAGE),
                authors = memberPubkeys,
                limit = memberPubkeys.size
            )
            val kpEvents = client.fetchEvents(kpFilter, timeoutMs = 5_000)
            val latestKp = kpEvents.groupBy { it.pubkey }
                .mapValues { it.value.maxByOrNull { e -> e.createdAt }!! }

            for ((_, kpEvent) in latestKp) {
                try {
                    val kpJson = json.encodeToString(NostrEvent.serializer(),
                        patchKeyPackageRelays(kpEvent, relays))
                    val addResult = rustClient.mlsAddMember(ffiGroup.groupIdHex, kpJson)
                    publishMlsKind445(addResult.commitEventData, ffiGroup.groupIdHex)
                    publishMlsWelcome(addResult.welcomeEventData)
                    try { rustClient.mlsMergePendingCommit(ffiGroup.groupIdHex) } catch (_: Exception) { }
                } catch (_: Exception) { }
            }

            // Re-fetch group to get updated memberPubkeys (ffiGroup is stale — only has creator)
            val freshGroup = try { rustClient.mlsGetGroupInfo(ffiGroup.groupIdHex) } catch (_: Exception) { null }
            val allMembers = freshGroup?.memberPubkeys
                ?: (listOf(myPubkeyHex) + memberPubkeys).distinct()

            val profiles = fetchProfiles(allMembers)
            val group = MlsGroup(
                groupIdHex = ffiGroup.groupIdHex,
                name = ffiGroup.name,
                description = ffiGroup.description,
                adminPubkeys = ffiGroup.adminPubkeys,
                memberPubkeys = allMembers,
                relays = ffiGroup.relays,
                createdAt = ffiGroup.createdAt.toLong(),
                epoch = ffiGroup.epoch.toLong(),
                isDm = false,
                memberProfiles = allMembers.mapNotNull { pk ->
                    val p = profiles[pk]
                    if (p != null) pk to p else null
                }.toMap()
            )

            val pubkey = prefs.publicKeyHex ?: return@withContext group
            val existing = getCachedMlsGroups().toMutableList()
            existing.add(0, group)
            cache.setCachedMlsGroups(pubkey, json.encodeToString(existing))

            group
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "createGroupChat failed: ${e.message}", e)
            null
        }
    }
}

suspend fun NostrRepository.leaveGroup(groupIdHex: String): Boolean {
    val rustClient = client.getRustClient() ?: return false
    return withContext(Dispatchers.IO) {
        try {
            // Merge any pending commit before leaving (prior add/remove may have left one)
            try { rustClient.mlsMergePendingCommit(groupIdHex) } catch (_: Exception) { }
            val ffiMsg = rustClient.mlsLeaveGroup(groupIdHex)
            publishMlsKind445(ffiMsg, groupIdHex)
            // Rust SQLite doesn't physically delete — mark locally
            cache.markGroupAsLeft(groupIdHex)
            val pubkey = prefs.publicKeyHex ?: return@withContext true
            cache.removeGroupFromCache(pubkey, groupIdHex, json)
            mlsCachedMessages.remove(groupIdHex)
            mlsProcessedIds.remove(groupIdHex)
            true
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "leaveGroup failed: ${e.message}", e)
            false
        }
    }
}

suspend fun NostrRepository.addMemberToGroup(groupIdHex: String, memberPubkey: String): Boolean {
    val rustClient = client.getRustClient() ?: return false
    return withContext(Dispatchers.IO) {
        try {
            val relays = prefs.relays.take(3).toList()
            val kpFilter = NostrClient.Filter(
                kinds = listOf(NostrKind.MLS_KEY_PACKAGE),
                authors = listOf(memberPubkey),
                limit = 1
            )
            val kpEvents = client.fetchEvents(kpFilter, timeoutMs = 5_000)
            val kpEvent = kpEvents.maxByOrNull { it.createdAt } ?: return@withContext false
            val kpJson = json.encodeToString(NostrEvent.serializer(),
                patchKeyPackageRelays(kpEvent, relays))
            val addResult = rustClient.mlsAddMember(groupIdHex, kpJson)
            publishMlsKind445(addResult.commitEventData, groupIdHex)
            publishMlsWelcome(addResult.welcomeEventData)
            try { rustClient.mlsMergePendingCommit(groupIdHex) } catch (_: Exception) { }
            true
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "addMemberToGroup failed: ${e.message}", e)
            false
        }
    }
}

suspend fun NostrRepository.removeMemberFromGroup(groupIdHex: String, memberPubkey: String): Boolean {
    val rustClient = client.getRustClient() ?: return false
    return withContext(Dispatchers.IO) {
        try {
            val ffiMsg = rustClient.mlsRemoveMember(groupIdHex, memberPubkey)
            publishMlsKind445(ffiMsg, groupIdHex)
            try { rustClient.mlsMergePendingCommit(groupIdHex) } catch (_: Exception) { }
            true
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "removeMemberFromGroup failed: ${e.message}", e)
            false
        }
    }
}

suspend fun NostrRepository.ensureKeyPackagePublished() {
    val pubkey = prefs.publicKeyHex ?: return
    val rustClient = client.getRustClient() ?: return
    withContext(Dispatchers.IO) {
        try {
            // Check if we already have a recent key package with relay URLs on the relay.
            // Re-publish if missing or if the existing package lacks relay tags (legacy issue).
            val existing = client.fetchEvents(
                NostrClient.Filter(kinds = listOf(NostrKind.MLS_KEY_PACKAGE), authors = listOf(pubkey), limit = 1),
                timeoutMs = 3_000
            )
            val hasRelays = existing.firstOrNull()?.tags?.any { tag ->
                tag.firstOrNull() == "relays" && tag.size > 1
            } ?: false
            if (existing.isNotEmpty() && hasRelays) return@withContext

            publishKeyPackage()
        } catch (e: Exception) {
            android.util.Log.w("NostrRepository", "ensureKeyPackagePublished: ${e.message}")
        }
    }
}

/**
 * Patch a key package event's "relays" tag with actual relay URLs if missing.
 * mdk-core requires at least one relay URL in the "relays" tag.
 * Old nurunuru versions published key packages without relay URLs.
 * nostr::Event deserialization does NOT verify id/sig, so patching tags is safe.
 */
private fun patchKeyPackageRelays(kpEvent: NostrEvent, fallbackRelays: List<String>): NostrEvent {
    val hasRelays = kpEvent.tags.any { tag -> tag.firstOrNull() == "relays" && tag.size > 1 }
    if (hasRelays) return kpEvent
    val relayTag = listOf("relays") + fallbackRelays
    val patchedTags = kpEvent.tags.map { tag ->
        if (tag.firstOrNull() == "relays") relayTag else tag
    }.let { if (it.none { t -> t.firstOrNull() == "relays" }) it + listOf(relayTag) else it }
    return kpEvent.copy(tags = patchedTags)
}

private suspend fun NostrRepository.publishKeyPackage() {
    val rustClient = client.getRustClient() ?: return
    try {
        val kpData = rustClient.mlsCreateKeyPackage()
        // Replace the empty "relays" tag with actual relay URLs (mdk-core requires at least one).
        // create_key_package_for_event is called with empty relays, producing ["relays"] with no URLs.
        val relayUrls = prefs.relays.take(3).toList()
        val relayTag = listOf("relays") + relayUrls
        val tagsWithRelays = kpData.tags.map { tag ->
            if (tag.firstOrNull() == "relays") relayTag else tag
        }
        if (isExternalSigner()) {
            val unsigned = rustClient.createUnsignedEvent(
                NostrKind.MLS_KEY_PACKAGE.toUInt(), kpData.content, tagsWithRelays, myPubkeyHex
            )
            signAndPublish(unsigned)
        } else {
            rustClient.publishEvent(NostrKind.MLS_KEY_PACKAGE.toUInt(), kpData.content, tagsWithRelays)
        }
        android.util.Log.d("NostrRepository", "Key package published")
    } catch (e: Exception) {
        android.util.Log.e("NostrRepository", "publishKeyPackage failed: ${e.message}", e)
    }
}

private suspend fun NostrRepository.publishMlsKind445(
    ffiMsg: FfiEncryptedMessageData,
    groupIdHex: String
): Boolean {
    val rustClient = client.getRustClient() ?: return false
    val tags = ffiMsg.tags + listOf(listOf("h", groupIdHex))
    return try {
        if (isExternalSigner()) {
            val unsigned = rustClient.createUnsignedEvent(
                NostrKind.MLS_GROUP_MESSAGE.toUInt(), ffiMsg.content, tags, myPubkeyHex
            )
            signAndPublish(unsigned)
        } else {
            rustClient.publishEvent(NostrKind.MLS_GROUP_MESSAGE.toUInt(), ffiMsg.content, tags)
            true
        }
    } catch (e: Exception) {
        android.util.Log.e("NostrRepository", "publishMlsKind445 failed: ${e.message}", e)
        false
    }
}

private suspend fun NostrRepository.publishMlsWelcome(
    welcomeData: uniffi.nurunuru.FfiWelcomeEventData
) {
    val rustClient = client.getRustClient() ?: return
    try {
        val tags = welcomeData.tags + listOf(listOf("p", welcomeData.recipientPubkey))
        if (isExternalSigner()) {
            val unsigned = rustClient.createUnsignedEvent(
                NostrKind.MLS_WELCOME.toUInt(), welcomeData.content, tags, myPubkeyHex
            )
            signAndPublish(unsigned)
        } else {
            rustClient.publishEvent(NostrKind.MLS_WELCOME.toUInt(), welcomeData.content, tags)
        }
    } catch (e: Exception) {
        android.util.Log.e("NostrRepository", "publishMlsWelcome failed: ${e.message}", e)
    }
}

// ─── Legacy DM (read-only, migration only) ────────────────────────────────────

@Deprecated("Use MLS groups for new conversations")
suspend fun NostrRepository.fetchDmConversations(pubkeyHex: String): List<DmConversation> {
    val oneHourAgo = getOneHourAgo()
    val receivedFilter = NostrClient.Filter(
        kinds = listOf(NostrKind.ENCRYPTED_DM),
        tags = mapOf("p" to listOf(pubkeyHex)),
        limit = 200,
        since = oneHourAgo
    )
    val sentFilter = NostrClient.Filter(
        kinds = listOf(NostrKind.ENCRYPTED_DM),
        authors = listOf(pubkeyHex),
        limit = 200,
        since = oneHourAgo
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
        } else {
            event.pubkey
        }
        conversations.getOrPut(partner) { mutableListOf() }.add(event)
    }

    val profiles = fetchProfiles(conversations.keys.toList())

    return conversations.map { (partnerKey, events) ->
        val lastEvent = events.maxByOrNull { it.createdAt }!!
        DmConversation(
            partnerPubkey = partnerKey,
            partnerProfile = profiles[partnerKey],
            lastMessage = "...",
            lastMessageTime = lastEvent.createdAt,
            unreadCount = 0
        )
    }.sortedByDescending { it.lastMessageTime }
}
