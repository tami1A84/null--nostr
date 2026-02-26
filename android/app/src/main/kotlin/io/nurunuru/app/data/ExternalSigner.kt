package io.nurunuru.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import io.nurunuru.app.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ExternalSigner {
    private const val PACKAGE_NAME = "com.greenart7c3.nostr.signer"

    // NIP-55 Actions
    private const val ACTION_GET_PUBLIC_KEY = "com.greenart7c3.nostr.signer.GET_PUBLIC_KEY"
    private const val ACTION_SIGN_EVENT = "com.greenart7c3.nostr.signer.SIGN_EVENT"
    private const val ACTION_NIP04_ENCRYPT = "com.greenart7c3.nostr.signer.NIP_04_ENCRYPT"
    private const val ACTION_NIP04_DECRYPT = "com.greenart7c3.nostr.signer.NIP_04_DECRYPT"
    private const val ACTION_NIP44_ENCRYPT = "com.greenart7c3.nostr.signer.NIP_44_ENCRYPT"
    private const val ACTION_NIP44_DECRYPT = "com.greenart7c3.nostr.signer.NIP_44_DECRYPT"

    private var pendingRequest: CompletableDeferred<Intent>? = null

    fun createGetPublicKeyIntent(): Intent {
        return Intent(ACTION_GET_PUBLIC_KEY).apply {
            `package` = PACKAGE_NAME
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    fun createSignEventIntent(eventJson: String, pubkey: String): Intent {
        return Intent(ACTION_SIGN_EVENT).apply {
            `package` = PACKAGE_NAME
            putExtra("event", eventJson)
            putExtra("current_user", pubkey)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    suspend fun signEvent(context: Context, eventJson: String, pubkey: String): String? {
        val intent = createSignEventIntent(eventJson, pubkey)
        val result = request(context, intent) ?: return null
        return result.getStringExtra("event") ?: result.getStringExtra("signature")
    }

    private suspend fun request(context: Context, intent: Intent): Intent? {
        val deferred = CompletableDeferred<Intent>()
        pendingRequest = deferred

        // This is a bit hacky because we need to start activity for result from a context
        // Usually we do this in MainActivity.
        if (context is MainActivity) {
            // Need to expose a way in MainActivity to launch this
            context.launchExternalSigner(intent)
        } else {
            return null
        }

        return try {
            deferred.await()
        } catch (e: Exception) {
            null
        } finally {
            pendingRequest = null
        }
    }

    fun onResult(intent: Intent?) {
        if (intent != null) {
            pendingRequest?.complete(intent)
        } else {
            pendingRequest?.cancel()
        }
    }
}
