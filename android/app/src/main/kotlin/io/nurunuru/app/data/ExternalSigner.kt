package io.nurunuru.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import io.nurunuru.app.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ExternalSigner : AppSigner {
    private const val TAG = "ExternalSigner"
    private var currentUserPubkey: String = ""

    fun setCurrentUser(pubkey: String) {
        currentUserPubkey = pubkey
    }

    private const val PACKAGE_NAME = "com.greenart7c3.nostrsigner"

    // NIP-55 Actions (Official)
    private const val ACTION_GET_PUBLIC_KEY = "com.nostr.signer.GET_PUBLIC_KEY"
    private const val ACTION_SIGN_EVENT = "com.nostr.signer.SIGN_EVENT"
    private const val ACTION_NIP04_ENCRYPT = "com.nostr.signer.NIP_04_ENCRYPT"
    private const val ACTION_NIP04_DECRYPT = "com.nostr.signer.NIP_04_DECRYPT"
    private const val ACTION_NIP44_ENCRYPT = "com.nostr.signer.NIP_44_ENCRYPT"
    private const val ACTION_NIP44_DECRYPT = "com.nostr.signer.NIP_44_DECRYPT"

    // Legacy Amber Actions (Backwards compatibility)
    private const val ACTION_GET_PUBLIC_KEY_LEGACY = "com.greenart7c3.nostr.signer.GET_PUBLIC_KEY"
    private const val ACTION_SIGN_EVENT_LEGACY = "com.greenart7c3.nostr.signer.SIGN_EVENT"

    // NIP-55 Content Provider URIs (try multiple authorities for compatibility)
    private val CONTENT_URIS = listOf(
        "content://com.greenart7c3.nostrsigner",
        "content://com.nostr.signer"
    )
    @Volatile private var workingContentUri: String? = null

    private var pendingRequest: CompletableDeferred<Intent>? = null
    private val mutex = kotlinx.coroutines.sync.Mutex()

    fun createGetPublicKeyIntent(context: Context?): Intent {
        // Request auto-sign permissions for all operations this app needs.
        // Amber will ask the user once to grant these; subsequent ContentProvider
        // calls will then succeed silently without showing the approval UI.
        val permissions = """[
            {"type":"sign_event","kind":0},
            {"type":"sign_event","kind":1},
            {"type":"sign_event","kind":3},
            {"type":"sign_event","kind":5},
            {"type":"sign_event","kind":6},
            {"type":"sign_event","kind":7},
            {"type":"sign_event","kind":10000},
            {"type":"sign_event","kind":10002},
            {"type":"sign_event","kind":30030},
            {"type":"sign_event","kind":30008},
            {"type":"sign_event","kind":1984},
            {"type":"sign_event","kind":1985},
            {"type":"nip04_encrypt"},
            {"type":"nip04_decrypt"},
            {"type":"nip44_encrypt"},
            {"type":"nip44_decrypt"}
        ]""".trimIndent().replace("\n", "").replace("  ", "")
        return Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")).apply {
            `package` = PACKAGE_NAME
            putExtra("type", "get_public_key")
            putExtra("permissions", permissions)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    fun createSignEventIntent(context: Context?, eventJson: String, pubkey: String): Intent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:$eventJson")).apply {
            `package` = PACKAGE_NAME
            putExtra("type", "sign_event")
            putExtra("current_user", pubkey)
            try {
                val id = Json.parseToJsonElement(eventJson).jsonObject["id"]?.jsonPrimitive?.content
                if (id != null) putExtra("id", id)
            } catch (e: Exception) {}
            putExtra("returnType", "event")
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return intent
    }

    fun createDecryptIntent(content: String, pubkey: String, currentUser: String, nip44: Boolean): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:$content")).apply {
            `package` = PACKAGE_NAME
            putExtra("type", if (nip44) "nip44_decrypt" else "nip04_decrypt")
            putExtra("current_user", currentUser)
            putExtra("pubkey", pubkey)
            putExtra("returnType", "signature")
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    fun createEncryptIntent(content: String, pubkey: String, currentUser: String, nip44: Boolean): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:$content")).apply {
            `package` = PACKAGE_NAME
            putExtra("type", if (nip44) "nip44_encrypt" else "nip04_encrypt")
            putExtra("current_user", currentUser)
            putExtra("pubkey", pubkey)
            putExtra("returnType", "signature")
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    suspend fun signEvent(context: Context?, eventJson: String, pubkey: String): String? {
        Log.d(TAG, "Requesting signature (pubkey: ${pubkey.take(12)}...)")
        val intent = createSignEventIntent(context, eventJson, pubkey)
        val result = request(context, intent) ?: run {
            Log.w(TAG, "Signing request failed or cancelled")
            return null
        }

        val signedEvent = result.getStringExtra("event")
        if (signedEvent != null && signedEvent.isNotBlank()) {
            return signedEvent
        }

        val signature = result.getStringExtra("signature") ?: result.getStringExtra("sig") ?: result.getStringExtra("result") ?: result.data?.toString()?.removePrefix("nostrsigner:")
        if (signature != null && signature.isNotBlank()) {

            // Check if it's already a full signed event JSON
            if (signature.trim().startsWith("{")) {
                try {
                    val jsonObj = Json.parseToJsonElement(signature).jsonObject
                    if (jsonObj.containsKey("sig") || jsonObj.containsKey("signature")) {
                        return signature
                    }
                } catch (_: Exception) {}
            }

            // Reconstruct event if only signature is returned
            return try {
                val map = Json.parseToJsonElement(eventJson).jsonObject.toMutableMap()
                map["sig"] = kotlinx.serialization.json.JsonPrimitive(signature)

                // Ensure pubkey is present (required for Event.fromJson)
                if (!map.containsKey("pubkey")) {
                    map["pubkey"] = kotlinx.serialization.json.JsonPrimitive(pubkey)
                }

                // Ensure id is present (some signers might strip it or expect it)
                if (!map.containsKey("id")) {
                    try {
                        val id = Json.parseToJsonElement(eventJson).jsonObject["id"]?.jsonPrimitive?.content
                        if (id != null) map["id"] = kotlinx.serialization.json.JsonPrimitive(id)
                    } catch (e: Exception) {}
                }

                Json.encodeToString(JsonObject(map))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reconstruct event", e)
                null
            }
        }
        Log.w(TAG, "No event or signature found in signer result")
        return null
    }

    suspend fun decrypt(context: Context?, content: String, pubkey: String, currentUser: String, nip44: Boolean): String? {
        val intent = createDecryptIntent(content, pubkey, currentUser, nip44)
        val result = request(context, intent) ?: return null
        return result.getStringExtra("signature") ?: result.getStringExtra("sig") ?: result.getStringExtra("content") ?: result.getStringExtra("result") ?: result.data?.toString()?.removePrefix("nostrsigner:")
    }

    suspend fun encrypt(context: Context?, content: String, pubkey: String, currentUser: String, nip44: Boolean): String? {
        val intent = createEncryptIntent(content, pubkey, currentUser, nip44)
        val result = request(context, intent) ?: return null
        return result.getStringExtra("signature") ?: result.getStringExtra("sig") ?: result.getStringExtra("content") ?: result.getStringExtra("result") ?: result.data?.toString()?.removePrefix("nostrsigner:")
    }

    private suspend fun request(context: Context?, intent: Intent): Intent? = mutex.withLock {
        val ctx = context ?: MainActivity.instance ?: return null

        // Try Content Resolver first (Silent signing)
        val resultIntent = tryContentResolver(ctx, intent)
        if (resultIntent != null) return resultIntent

        // Fallback to Intent (App switching)
        Log.d(TAG, "ContentProvider returned null, falling back to Intent (type=${intent.getStringExtra("type")})")
        val deferred = CompletableDeferred<Intent>()
        pendingRequest = deferred

        if (ctx is MainActivity) {
            ctx.launchExternalSigner(intent)
        } else {
            MainActivity.instance?.launchExternalSigner(intent) ?: return null
        }

        Log.d(TAG, "Intent launched, awaiting result...")
        return try {
            val result = deferred.await()
            Log.d(TAG, "Deferred completed: extras=${result.extras?.keySet()?.joinToString()}")
            result
        } catch (e: Exception) {
            Log.w(TAG, "Deferred cancelled/failed: ${e.message}")
            null
        } finally {
            pendingRequest = null
        }
    }

    private fun tryContentResolver(context: Context, intent: Intent): Intent? {
        val type = intent.getStringExtra("type") ?: return null
        val data = intent.data?.toString()?.removePrefix("nostrsigner:") ?: ""
        val currentUser = intent.getStringExtra("current_user") ?: ""
        val pubKey = intent.getStringExtra("pubkey") ?: ""

        // Try previously working URI first
        workingContentUri?.let { uri ->
            val result = queryProvider(context, uri, type, data, currentUser, pubKey)
            if (result != null) return result
        }

        // Try all known Content Provider URIs
        for (uri in CONTENT_URIS) {
            if (uri == workingContentUri) continue
            val result = queryProvider(context, uri, type, data, currentUser, pubKey)
            if (result != null) {
                workingContentUri = uri
                Log.d(TAG, "Found working Content Provider URI: $uri")
                return result
            }
        }
        return null
    }

    private fun queryProvider(
        context: Context,
        baseUri: String,
        type: String,
        data: String,
        currentUser: String,
        pubKey: String
    ): Intent? {
        // NIP-55: Authority is often <package>.<METHOD>
        // We try both .METHOD and /method for compatibility
        val methods = listOf(type.uppercase(), type.lowercase())
        val delimiters = listOf(".", "/")

        for (delimiter in delimiters) {
            for (method in methods) {
                try {
                    val uri = Uri.parse("${baseUri.removeSuffix("/")}$delimiter$method")
                    Log.d(TAG, "Querying provider: $uri")

                    // NIP-55 Content Provider query:
                    // projection: [data] (event json or content)
                    // selection: type (e.g. "sign_event")
                    // selectionArgs: [account, counterparty (optional)]
                    val projection = arrayOf(data)
                    val selection = type
                    val selectionArgs = if (pubKey.isNotBlank()) {
                        // Amber expects account first. NIP-55 specifies [counterparty, account]
                        // but Amber's SignerProvider expects account at index 0.
                        if (baseUri.contains("greenart7c3")) {
                            arrayOf(currentUser, pubKey)
                        } else {
                            arrayOf(pubKey, currentUser)
                        }
                    } else {
                        arrayOf(currentUser)
                    }

                    context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val signatureIndex = cursor.getColumnIndex("signature")
                            val eventIndex = cursor.getColumnIndex("event")
                            val resultIndex = cursor.getColumnIndex("result")

                            val signature = if (signatureIndex != -1) cursor.getString(signatureIndex) else null
                            val event = if (eventIndex != -1) cursor.getString(eventIndex) else null
                            val result = if (resultIndex != -1) cursor.getString(resultIndex) else null

                            if (signature != null || event != null || result != null) {
                                return Intent().apply {
                                    putExtra("signature", signature)
                                    putExtra("event", event)
                                    putExtra("result", result)
                                }
                            }

                            // If we didn't find the expected columns, try any non-null column as fallback
                            for (i in 0 until cursor.columnCount) {
                                val value = cursor.getString(i)
                                if (!value.isNullOrBlank()) {
                                    return Intent().apply { putExtra("signature", value) }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Query failed for combination $baseUri $delimiter $method: ${e.message}")
                }
            }
        }
        return null
    }

    override fun getPublicKeyHex(): String {
        return currentUserPubkey
    }

    override suspend fun signEvent(eventJson: String): String? {
        return signEvent(null, eventJson, getPublicKeyHex())
    }

    override suspend fun nip04Encrypt(receiverPubkeyHex: String, content: String): String? {
        return encrypt(null, content, receiverPubkeyHex, getPublicKeyHex(), false)
    }

    override suspend fun nip04Decrypt(senderPubkeyHex: String, encryptedContent: String): String? {
        return decrypt(null, encryptedContent, senderPubkeyHex, getPublicKeyHex(), false)
    }

    override suspend fun nip44Encrypt(receiverPubkeyHex: String, content: String): String? {
        return encrypt(null, content, receiverPubkeyHex, getPublicKeyHex(), true)
    }

    override suspend fun nip44Decrypt(senderPubkeyHex: String, encryptedContent: String): String? {
        return decrypt(null, encryptedContent, senderPubkeyHex, getPublicKeyHex(), true)
    }

    fun onResult(intent: Intent?) {
        Log.d(TAG, "onResult called: intent=${if (intent != null) "non-null (extras: ${intent.extras?.keySet()?.joinToString()})" else "null"}")
        if (intent != null) {
            pendingRequest?.complete(intent)
        } else {
            pendingRequest?.cancel()
        }
    }
}
