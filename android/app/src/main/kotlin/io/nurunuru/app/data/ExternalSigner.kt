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

    private const val CONTENT_URI = "content://com.greenart7c3.nostrsigner"

    private var pendingRequest: CompletableDeferred<Intent>? = null
    private val mutex = kotlinx.coroutines.sync.Mutex()

    fun createGetPublicKeyIntent(context: Context?): Intent {
        val intent = Intent(ACTION_GET_PUBLIC_KEY).apply {
            `package` = PACKAGE_NAME
            putExtra("type", "get_public_key")
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        // Fallback to legacy action if official not resolved
        if (context != null && intent.resolveActivity(context.packageManager) == null) {
            intent.action = ACTION_GET_PUBLIC_KEY_LEGACY
        }

        // Last fallback: nostrsigner: scheme
        if (context != null && intent.resolveActivity(context.packageManager) == null) {
            intent.action = Intent.ACTION_VIEW
            intent.data = Uri.parse("nostrsigner:")
        }

        return intent
    }

    fun createSignEventIntent(context: Context?, eventJson: String, pubkey: String): Intent {
        val intent = Intent(ACTION_SIGN_EVENT).apply {
            `package` = PACKAGE_NAME
            putExtra("type", "sign_event")
            putExtra("event", eventJson)
            putExtra("current_user", pubkey)
            putExtra("returnType", "event")
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        if (context != null && intent.resolveActivity(context.packageManager) == null) {
            intent.action = ACTION_SIGN_EVENT_LEGACY
        }

        return intent
    }

    fun createDecryptIntent(content: String, pubkey: String, currentUser: String, nip44: Boolean): Intent {
        return Intent(if (nip44) ACTION_NIP44_DECRYPT else ACTION_NIP04_DECRYPT).apply {
            `package` = PACKAGE_NAME
            putExtra("type", if (nip44) "nip44_decrypt" else "nip04_decrypt")
            putExtra("content", content)
            putExtra("pubKey", pubkey)
            putExtra("current_user", currentUser)
            putExtra("returnType", "signature")
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    fun createEncryptIntent(content: String, pubkey: String, currentUser: String, nip44: Boolean): Intent {
        return Intent(if (nip44) ACTION_NIP44_ENCRYPT else ACTION_NIP04_ENCRYPT).apply {
            `package` = PACKAGE_NAME
            putExtra("type", if (nip44) "nip44_encrypt" else "nip04_encrypt")
            putExtra("content", content)
            putExtra("pubKey", pubkey)
            putExtra("current_user", currentUser)
            putExtra("returnType", "signature")
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    suspend fun signEvent(context: Context?, eventJson: String, pubkey: String): String? {
        val intent = createSignEventIntent(context, eventJson, pubkey)
        val result = request(context, intent) ?: return null
        return result.getStringExtra("event") ?: result.getStringExtra("signature")
    }

    suspend fun decrypt(context: Context?, content: String, pubkey: String, currentUser: String, nip44: Boolean): String? {
        val intent = createDecryptIntent(content, pubkey, currentUser, nip44)
        val result = request(context, intent) ?: return null
        return result.getStringExtra("signature") ?: result.getStringExtra("content")
    }

    suspend fun encrypt(context: Context?, content: String, pubkey: String, currentUser: String, nip44: Boolean): String? {
        val intent = createEncryptIntent(content, pubkey, currentUser, nip44)
        val result = request(context, intent) ?: return null
        return result.getStringExtra("signature") ?: result.getStringExtra("content")
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
        try {
            val contentResolver = context.contentResolver
            val uri = Uri.parse("$CONTENT_URI/$type")

            val projection = arrayOf("signature", "event")

            // Re-map NIP-44 and NIP-04 type for content resolver if necessary
            // Amber's content provider expects specific types
            val selectionArgs = arrayOf(
                intent.getStringExtra("event") ?: intent.getStringExtra("content") ?: "",
                intent.getStringExtra("current_user") ?: "",
                intent.getStringExtra("pubKey") ?: ""
            )

            contentResolver.query(uri, projection, type, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val signature = cursor.getString(cursor.getColumnIndexOrThrow("signature"))
                    val event = cursor.getString(cursor.getColumnIndexOrThrow("event"))
                    return Intent().apply {
                        putExtra("signature", signature)
                        putExtra("event", event)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("ExternalSigner", "ContentResolver failed, fallback to Intent: ${e.message}")
        }
        return null
    }

    override fun getPublicKeyHex(): String {
        return currentUserPubkey
    }

    override suspend fun signEvent(eventJson: String): String? {
        val intent = createSignEventIntent(null, eventJson, getPublicKeyHex())
        val result = request(null, intent) ?: return null
        val signedEventJson = result.getStringExtra("event")
        if (signedEventJson != null) return signedEventJson

        val signature = result.getStringExtra("signature")
        if (signature != null) {
            // Reconstruct event if only signature is returned
            return try {
                val map = Json.parseToJsonElement(eventJson).jsonObject.toMutableMap()
                map["sig"] = jsonPrimitive(signature)
                Json.encodeToString(JsonObject(map))
            } catch (e: Exception) {
                null
            }
        }
        return null
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
