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

    private const val CONTENT_URI = "content://com.nostr.signer"

    private var pendingRequest: CompletableDeferred<Intent>? = null
    private val mutex = kotlinx.coroutines.sync.Mutex()

    fun createGetPublicKeyIntent(context: Context?): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")).apply {
            `package` = PACKAGE_NAME
            putExtra("type", "get_public_key")
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
        Log.d("ExternalSigner", "Requesting signature for event: $eventJson (pubkey: $pubkey)")
        val intent = createSignEventIntent(context, eventJson, pubkey)
        val result = request(context, intent) ?: run {
            Log.e("ExternalSigner", "Request failed or cancelled")
            return null
        }

        // Log all extras for debugging
        result.extras?.keySet()?.forEach { key ->
            Log.d("ExternalSigner", "Result extra: $key = ${result.extras?.get(key)}")
        }

        val signedEvent = result.getStringExtra("event")
        if (signedEvent != null && signedEvent.isNotBlank()) {
            Log.d("ExternalSigner", "Received full signed event: $signedEvent")
            return signedEvent
        }

        val signature = result.getStringExtra("signature") ?: result.getStringExtra("sig") ?: result.getStringExtra("result") ?: result.data?.toString()?.removePrefix("nostrsigner:")
        if (signature != null && signature.isNotBlank()) {
            Log.d("ExternalSigner", "Received signature/result: $signature. Reconstructing event...")

            // Check if it's already a full signed event JSON
            if (signature.trim().startsWith("{")) {
                try {
                    val jsonObj = Json.parseToJsonElement(signature).jsonObject
                    if (jsonObj.containsKey("sig") || jsonObj.containsKey("signature")) {
                        Log.d("ExternalSigner", "Result is already a signed event JSON")
                        return signature
                    }
                } catch (e: Exception) {}
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

                val reconstructed = Json.encodeToString(JsonObject(map))
                Log.d("ExternalSigner", "Reconstructed event: $reconstructed")
                reconstructed
            } catch (e: Exception) {
                Log.e("ExternalSigner", "Failed to reconstruct event", e)
                null
            }
        }
        Log.e("ExternalSigner", "No event or signature found in result")
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
        val deferred = CompletableDeferred<Intent>()
        pendingRequest = deferred

        if (ctx is MainActivity) {
            ctx.launchExternalSigner(intent)
        } else {
            MainActivity.instance?.launchExternalSigner(intent) ?: return null
        }

        return try {
            deferred.await()
        } catch (e: Exception) {
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

        Log.d("ExternalSigner", "tryContentResolver: type=$type, data.len=${data.length}, currentUser=$currentUser, pubKey=$pubKey")
        return queryProvider(context, CONTENT_URI, type, data, currentUser, pubKey)
    }

    private fun queryProvider(
        context: Context,
        baseUri: String,
        type: String,
        data: String,
        currentUser: String,
        pubKey: String
    ): Intent? {
        try {
            Log.d("ExternalSigner", "Querying provider: $baseUri/$type with currentUser: $currentUser")
            val uri = Uri.parse("$baseUri/$type")

            // NIP-55 Content Provider query:
            // projection: [data (event json or content), pubKey (receiver), currentUser]
            val projection = arrayOf(data, pubKey, currentUser)

            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val signatureIndex = cursor.getColumnIndex("signature")
                    val sigIndex = cursor.getColumnIndex("sig")
                    val eventIndex = cursor.getColumnIndex("event")
                    val resultIndex = cursor.getColumnIndex("result")

                    val signature = if (signatureIndex != -1) cursor.getString(signatureIndex) else if (sigIndex != -1) cursor.getString(sigIndex) else null
                    val event = if (eventIndex != -1) cursor.getString(eventIndex) else null
                    val result = if (resultIndex != -1) cursor.getString(resultIndex) else null

                    Log.d("ExternalSigner", "Provider result: sig=${signature?.take(10)}..., event=${event?.take(10)}..., res=${result?.take(10)}...")

                    if (signature == null && event == null && result == null) {
                        Log.w("ExternalSigner", "Provider returned empty columns")
                        return null
                    }

                    return Intent().apply {
                        putExtra("signature", signature)
                        putExtra("event", event)
                        putExtra("result", result)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("ExternalSigner", "ContentResolver query failed for $baseUri: ${e.message}")
        }
        return null
    }

    override fun getPublicKeyHex(): String {
        return currentUserPubkey
    }

    override suspend fun signEvent(eventJson: String): String? {
        return signEvent(null, eventJson, getPublicKeyHex())
    }

    private fun jsonPrimitive(value: String) = kotlinx.serialization.json.JsonPrimitive(value)

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
        if (intent != null) {
            pendingRequest?.complete(intent)
        } else {
            pendingRequest?.cancel()
        }
    }
}
