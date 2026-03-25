package io.nurunuru.app.data

import io.nurunuru.app.data.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ─── Actions ──────────────────────────────────────────────────────────────────

/** Returns the reaction event ID on success, null on failure. */
suspend fun NostrRepository.likePost(
    eventId: String,
    authorPubkey: String,
    emoji: String = "+",
    customTags: List<List<String>> = emptyList()
): String? {
    val rustClient = client.getRustClient() ?: return null
    return try {
        if (isExternalSigner()) {
            val unsigned = rustClient.createUnsignedReaction(eventId, authorPubkey, emoji, myPubkeyHex)
            signAndPublishGetId(unsigned).also { if (it != null) android.util.Log.d("NostrRepository", "Ext react OK: $eventId id=$it") }
        } else {
            val reactionId = withContext(Dispatchers.IO) {
                rustClient.react(eventId, authorPubkey, emoji)
            }
            android.util.Log.d("NostrRepository", "Rust react OK: $eventId emoji=$emoji id=$reactionId")
            reactionId.ifEmpty { null }
        }
    } catch (e: Exception) {
        android.util.Log.e("NostrRepository", "Rust react failed: ${e.message}")
        null
    }
}

/**
 * Repost an event (Kind 6, NIP-18).
 * `eventJson` must be the full serialised Nostr event JSON (with encodeDefaults=true).
 * Returns the repost event ID on success, null on failure.
 */
suspend fun NostrRepository.repostPost(eventId: String, eventJson: String? = null): String? {
    val rustClient = client.getRustClient() ?: return null
    if (eventJson == null) {
        android.util.Log.w("NostrRepository", "repostPost: eventJson is null, skipping $eventId")
        return null
    }
    return try {
        if (isExternalSigner()) {
            val unsigned = rustClient.createUnsignedRepost(eventJson, myPubkeyHex)
            signAndPublishGetId(unsigned).also { if (it != null) android.util.Log.d("NostrRepository", "Ext repost OK: $eventId id=$it") }
        } else {
            val repostId = withContext(Dispatchers.IO) { rustClient.repost(eventJson) }
            android.util.Log.d("NostrRepository", "Rust repost OK: $eventId id=$repostId")
            repostId.ifEmpty { null }
        }
    } catch (e: Exception) {
        android.util.Log.e("NostrRepository", "Rust repost failed: ${e.message}")
        null
    }
}

suspend fun NostrRepository.publishNote(
    content: String,
    replyToId: String? = null,
    contentWarning: String? = null,
    customTags: List<List<String>> = emptyList(),
    kind: Int = NostrKind.TEXT_NOTE,
    targetRelays: List<String>? = null,
    nip70Protected: Boolean = false
): NostrEvent? {
    val tags = mutableListOf<List<String>>()
    tags.addAll(customTags)

    if (replyToId != null && tags.none { it.getOrNull(0) == "e" && it.getOrNull(1) == replyToId }) {
        tags.add(listOf("e", replyToId, "", "reply"))
    }
    if (contentWarning != null && tags.none { it.getOrNull(0) == "content-warning" }) {
        tags.add(listOf("content-warning", contentWarning))
    }
    if (nip70Protected && tags.none { it.getOrNull(0) == "-" }) {
        tags.add(listOf("-"))
    }
    if (tags.none { it.getOrNull(0) == "client" }) {
        tags.add(listOf("client", "nullnull"))
    }

    val rustClient = client.getRustClient() ?: return null

    if (isExternalSigner()) {
        return try {
            val unsigned = withContext(Dispatchers.IO) {
                rustClient.createUnsignedEvent(kind.toUInt(), content, tags, myPubkeyHex)
            }
            android.util.Log.d("NostrRepository", "Ext publishNote: unsigned created (${unsigned.length} chars), signing...")
            val signedJson = client.getSigner().signEvent(unsigned) ?: run {
                android.util.Log.w("NostrRepository", "Ext publishNote: signer returned null")
                return null
            }
            android.util.Log.d("NostrRepository", "Ext publishNote: signed (${signedJson.length} chars), publishing...")
            withContext(Dispatchers.IO) { rustClient.publishRawEvent(signedJson) }
            android.util.Log.d("NostrRepository", "Ext publishNote OK")
            try { json.decodeFromString<NostrEvent>(signedJson) } catch (_: Exception) { null }
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "Ext publishNote failed: ${e.message}")
            null
        }
    }

    if (kind == NostrKind.TEXT_NOTE) {
        return try {
            val eventId = withContext(Dispatchers.IO) {
                if (!targetRelays.isNullOrEmpty()) {
                    android.util.Log.d("NostrRepository", "publishNote to ${targetRelays.size} relays: $targetRelays")
                    rustClient.publishNoteWithTagsToRelays(content, tags, targetRelays)
                } else {
                    rustClient.publishNoteWithTags(content, tags)
                }
            }
            android.util.Log.d("NostrRepository", "Rust publishNote OK: $eventId")
            withContext(Dispatchers.IO) {
                rustClient.queryLocal(listOf(prefs.publicKeyHex ?: ""), 1u)
                    .firstOrNull()
                    ?.let { try { Json.decodeFromString<NostrEvent>(it) } catch (_: Exception) { null } }
            }
        } catch (e: Exception) {
            android.util.Log.e("NostrRepository", "Rust publishNote failed: ${e.message}")
            null
        }
    }

    // Non-Kind-1: use generic publishEvent
    return try {
        val eventId = withContext(Dispatchers.IO) { rustClient.publishEvent(kind.toUInt(), content, tags) }
        android.util.Log.d("NostrRepository", "Rust publishEvent(kind=$kind) OK id=$eventId tags=${tags.size}")
        NostrEvent(id = eventId, kind = kind, tags = tags, content = content)
    } catch (e: Exception) {
        android.util.Log.e("NostrRepository", "Rust publishEvent(kind=$kind) failed: ${e.message}")
        null
    }
}

suspend fun NostrRepository.sendDm(recipientPubkeyHex: String, content: String): Boolean =
    client.sendEncryptedDm(recipientPubkeyHex, content)

suspend fun NostrRepository.deleteEvent(eventId: String, reason: String = ""): Boolean {
    val success = try {
        if (isExternalSigner()) {
            publishNewEvent(5, reason, listOf(listOf("e", eventId))) != null
        } else {
            val rustClient = client.getRustClient() ?: return false
            rustClient.deleteEvent(eventId, reason.ifEmpty { null })
            true
        }
    } catch (e: Exception) {
        android.util.Log.e("NostrRepository", "deleteEvent failed: ${e.message}")
        false
    }
    // 削除成功後はディスクキャッシュからも即座に除外する。
    // リレーが NIP-09 を反映する前に refresh() が走っても再出現しなくなる。
    if (success) {
        // 永続セットに登録 → アプリ再起動後のリレーフェッチでも除外される
        cache.addDeletedEventId(eventId)
        val pubkey = prefs.publicKeyHex ?: return true
        cache.removeFromUserNotesCache(pubkey, eventId, json)
        cache.removeFromUserLikesCache(pubkey, eventId, json)
    }
    return success
}

suspend fun NostrRepository.reportEvent(targetPubkey: String, eventId: String?, reportType: String, content: String): Boolean {
    val tags = mutableListOf(listOf("p", targetPubkey, reportType))
    eventId?.let { tags.add(listOf("e", it, reportType)) }
    return publishNewEvent(1984, content, tags) != null
}

suspend fun NostrRepository.publishBirdwatchLabel(eventId: String, authorPubkey: String, contextType: String, content: String, sourceUrl: String = ""): Boolean {
    val fullContent = if (sourceUrl.isNotBlank()) "$content\n\nソース: $sourceUrl" else content
    val tags = listOf(
        listOf("L", "birdwatch"),
        listOf("l", contextType, "birdwatch"),
        listOf("e", eventId),
        listOf("p", authorPubkey)
    )
    return publishNewEvent(1985, fullContent, tags) != null
}

suspend fun NostrRepository.fetchMuteList(pubkeyHex: String): MuteListData {
    val filter = NostrClient.Filter(
        kinds = listOf(NostrKind.MUTE_LIST),
        authors = listOf(pubkeyHex),
        limit = 1
    )
    val events = client.fetchEvents(filter, timeoutMs = 4_000)
    val event = events.maxByOrNull { it.createdAt }

    if (event != null) {
        // NIP-44 private mute list: content is encrypted, tags may be empty
        val signer = client.getSigner()
        val muteData = if (event.content.isNotBlank() && signer != null) {
            try {
                val decrypted = signer.nip44Decrypt(pubkeyHex, event.content)
                if (decrypted != null) {
                    // Decrypted content is a JSON array of tags: [["p","..."],["e","..."],...]
                    val decryptedTags = try {
                        kotlinx.serialization.json.Json.decodeFromString<List<List<String>>>(decrypted)
                    } catch (_: Exception) { emptyList() }
                    val allTags = event.tags + decryptedTags
                    MuteListData(
                        pubkeys = allTags.filter { it.firstOrNull() == "p" }.mapNotNull { it.getOrNull(1) },
                        eventIds = allTags.filter { it.firstOrNull() == "e" }.mapNotNull { it.getOrNull(1) },
                        hashtags = allTags.filter { it.firstOrNull() == "t" }.mapNotNull { it.getOrNull(1) },
                        words = allTags.filter { it.firstOrNull() == "word" }.mapNotNull { it.getOrNull(1) }
                    )
                } else {
                    MuteListData(
                        pubkeys = event.getTagValues("p"),
                        eventIds = event.getTagValues("e"),
                        hashtags = event.getTagValues("t"),
                        words = event.getTagValues("word")
                    )
                }
            } catch (_: Exception) {
                MuteListData(
                    pubkeys = event.getTagValues("p"),
                    eventIds = event.getTagValues("e"),
                    hashtags = event.getTagValues("t"),
                    words = event.getTagValues("word")
                )
            }
        } else {
            MuteListData(
                pubkeys = event.getTagValues("p"),
                eventIds = event.getTagValues("e"),
                hashtags = event.getTagValues("t"),
                words = event.getTagValues("word")
            )
        }
        cache.setCachedMuteList(pubkeyHex, json.encodeToString(NostrEvent.serializer(), event))
        return muteData
    }

    return getCachedMuteList(pubkeyHex) ?: MuteListData()
}

suspend fun NostrRepository.fetchNip65WriteRelays(pubkeyHex: String): List<String> {
    return try {
        OutboxModel(client).fetchUserRelayList(pubkeyHex).write
    } catch (_: Exception) { emptyList() }
}

/**
 * NIP-65 kind 10002 から最寄りリレーを取得し、mainRelay に保存する。
 */
suspend fun NostrRepository.syncNip65Relays(pubkeyHex: String) {
    val writeRelays = fetchNip65WriteRelays(pubkeyHex)
    val main = writeRelays.firstOrNull() ?: return
    if (main != prefs.mainRelay) {
        prefs.mainRelay = main
        android.util.Log.d("NostrRepository", "NIP-65 main relay synced: $main")
    }
}

suspend fun NostrRepository.removeFromMuteList(pubkeyHex: String, type: String, value: String): Boolean {
    val signer = client.getSigner()
    val filter = NostrClient.Filter(kinds = listOf(NostrKind.MUTE_LIST), authors = listOf(pubkeyHex), limit = 1)
    val latest = client.fetchEvents(filter, timeoutMs = 4_000).maxByOrNull { it.createdAt } ?: return true

    val tagType = when (type) {
        "pubkey" -> "p"; "event" -> "e"; "hashtag" -> "t"; "word" -> "word"
        else -> return false
    }

    if (signer != null && latest.content.isNotBlank()) {
        val existingTags = try {
            val decrypted = signer.nip44Decrypt(pubkeyHex, latest.content)
            if (decrypted != null) kotlinx.serialization.json.Json.decodeFromString<List<List<String>>>(decrypted) else latest.tags
        } catch (_: Exception) { latest.tags }
        val newTags = existingTags.filter { !(it.firstOrNull() == tagType && it.getOrNull(1) == value) }
        if (newTags.size == existingTags.size) return true
        val encryptedContent = try { signer.nip44Encrypt(pubkeyHex, kotlinx.serialization.json.Json.encodeToString(newTags)) ?: "" } catch (_: Exception) { "" }
        return publishNewEvent(NostrKind.MUTE_LIST, encryptedContent, emptyList()) != null
    } else {
        val newTags = latest.tags.filter { !(it.firstOrNull() == tagType && it.getOrNull(1) == value) }
        if (newTags.size == latest.tags.size) return true
        return publishNewEvent(NostrKind.MUTE_LIST, latest.content, newTags) != null
    }
}

suspend fun NostrRepository.muteUser(pubkeyHex: String): Boolean {
    val myPubkey = prefs.publicKeyHex ?: return false
    val signer = client.getSigner()
    val filter = NostrClient.Filter(kinds = listOf(NostrKind.MUTE_LIST), authors = listOf(myPubkey), limit = 1)
    val latest = client.fetchEvents(filter, timeoutMs = 4_000).maxByOrNull { it.createdAt }

    if (signer != null) {
        // NIP-44 private mute list
        val existingPrivateTags = if (latest != null && latest.content.isNotBlank()) {
            try {
                val decrypted = signer.nip44Decrypt(myPubkey, latest.content)
                if (decrypted != null) kotlinx.serialization.json.Json.decodeFromString<List<List<String>>>(decrypted) else emptyList()
            } catch (_: Exception) { emptyList() }
        } else {
            latest?.tags ?: emptyList()  // Migrate from public tags
        }
        val allTags = existingPrivateTags.toMutableList()
        if (allTags.any { it.firstOrNull() == "p" && it.getOrNull(1) == pubkeyHex }) return true
        allTags.add(listOf("p", pubkeyHex))
        val encryptedContent = try {
            signer.nip44Encrypt(myPubkey, kotlinx.serialization.json.Json.encodeToString(allTags)) ?: ""
        } catch (_: Exception) { "" }
        return publishNewEvent(NostrKind.MUTE_LIST, encryptedContent, emptyList()) != null
    } else {
        // Fallback: public tags
        val tags = latest?.tags?.toMutableList() ?: mutableListOf()
        if (tags.any { it.firstOrNull() == "p" && it.getOrNull(1) == pubkeyHex }) return true
        tags.add(listOf("p", pubkeyHex))
        return publishNewEvent(NostrKind.MUTE_LIST, latest?.content ?: "", tags) != null
    }
}

suspend fun NostrRepository.followUser(myPubkeyHex: String, targetPubkeyHex: String): Boolean {
    return try {
        if (isExternalSigner()) {
            val contactsFilter = NostrClient.Filter(kinds = listOf(NostrKind.CONTACT_LIST), authors = listOf(myPubkeyHex), limit = 1)
            val latest = client.fetchEvents(contactsFilter, timeoutMs = 4_000).maxByOrNull { it.createdAt }
            val tags = latest?.tags?.toMutableList() ?: mutableListOf()
            if (tags.any { it.firstOrNull() == "p" && it.getOrNull(1) == targetPubkeyHex }) return true
            tags.add(listOf("p", targetPubkeyHex))
            publishNewEvent(NostrKind.CONTACT_LIST, latest?.content ?: "", tags) != null
        } else {
            val rustClient = client.getRustClient() ?: return false
            rustClient.followUser(targetPubkeyHex)
            android.util.Log.d("NostrRepository", "Rust followUser OK: $targetPubkeyHex")
            true
        }
    } catch (e: Exception) {
        android.util.Log.e("NostrRepository", "followUser failed: ${e.message}")
        false
    }
}

suspend fun NostrRepository.unfollowUser(myPubkeyHex: String, targetPubkeyHex: String): Boolean {
    return try {
        if (isExternalSigner()) {
            val contactsFilter = NostrClient.Filter(kinds = listOf(NostrKind.CONTACT_LIST), authors = listOf(myPubkeyHex), limit = 1)
            val latest = client.fetchEvents(contactsFilter, timeoutMs = 4_000).maxByOrNull { it.createdAt } ?: return true
            val newTags = latest.tags.filter { !(it.firstOrNull() == "p" && it.getOrNull(1) == targetPubkeyHex) }
            if (newTags.size == latest.tags.size) return true
            publishNewEvent(NostrKind.CONTACT_LIST, latest.content, newTags) != null
        } else {
            val rustClient = client.getRustClient() ?: return false
            rustClient.unfollowUser(targetPubkeyHex)
            android.util.Log.d("NostrRepository", "Rust unfollowUser OK: $targetPubkeyHex")
            true
        }
    } catch (e: Exception) {
        android.util.Log.e("NostrRepository", "unfollowUser failed: ${e.message}")
        false
    }
}
