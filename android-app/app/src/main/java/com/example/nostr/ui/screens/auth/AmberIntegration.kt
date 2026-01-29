package com.example.nostr.ui.screens.auth

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.example.nostr.nostr.event.NostrEvent
import com.example.nostr.nostr.event.UnsignedEvent
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Integration with Amber signer app for NIP-55 signing
 */
object AmberIntegration {

    private const val AMBER_PACKAGE = "com.greenart7c3.nostrsigner"
    private const val CALLBACK_SCHEME = "nostrclient"
    private const val CALLBACK_HOST = "amber-callback"

    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<SigningResult>>()
    private val gson = Gson()

    data class SigningResult(
        val signature: String?,
        val event: String?,
        val error: String? = null
    )

    /**
     * Check if Amber is installed
     */
    fun isAmberInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(AMBER_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Request public key from Amber
     */
    fun requestPublicKey(context: Context): String {
        val requestId = generateRequestId()
        val callbackUri = "$CALLBACK_SCHEME://$CALLBACK_HOST?id=$requestId"

        val intent = Intent(Intent.ACTION_VIEW).apply {
            `package` = AMBER_PACKAGE
            data = Uri.parse("nostrsigner:?compressionType=none&returnType=signature&type=get_public_key&callbackUrl=$callbackUri")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
        return requestId
    }

    /**
     * Request event signing from Amber
     */
    suspend fun signEvent(context: Context, unsignedEvent: UnsignedEvent): NostrEvent? {
        val requestId = generateRequestId()
        val deferred = CompletableDeferred<SigningResult>()
        pendingRequests[requestId] = deferred

        try {
            val eventJson = gson.toJson(mapOf(
                "pubkey" to unsignedEvent.pubkey,
                "created_at" to unsignedEvent.createdAt,
                "kind" to unsignedEvent.kind,
                "tags" to unsignedEvent.tags,
                "content" to unsignedEvent.content
            ))

            val callbackUri = "$CALLBACK_SCHEME://$CALLBACK_HOST?id=$requestId"
            val encodedEvent = Uri.encode(eventJson)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                `package` = AMBER_PACKAGE
                data = Uri.parse("nostrsigner:$encodedEvent?compressionType=none&returnType=event&type=sign_event&callbackUrl=$callbackUri")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)

            // Wait for the callback
            val result = deferred.await()

            return if (result.event != null) {
                gson.fromJson(result.event, NostrEvent::class.java)
            } else if (result.signature != null) {
                unsignedEvent.toSignedEvent(result.signature)
            } else {
                Timber.e("Amber signing failed: ${result.error}")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Error signing with Amber")
            pendingRequests.remove(requestId)
            return null
        }
    }

    /**
     * Request NIP-44 encryption from Amber
     */
    suspend fun encrypt(context: Context, plaintext: String, recipientPubkey: String): String? {
        val requestId = generateRequestId()
        val deferred = CompletableDeferred<SigningResult>()
        pendingRequests[requestId] = deferred

        try {
            val callbackUri = "$CALLBACK_SCHEME://$CALLBACK_HOST?id=$requestId"
            val encodedPlaintext = Uri.encode(plaintext)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                `package` = AMBER_PACKAGE
                data = Uri.parse("nostrsigner:$encodedPlaintext?pubkey=$recipientPubkey&compressionType=none&returnType=signature&type=nip44_encrypt&callbackUrl=$callbackUri")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)

            val result = deferred.await()
            return result.signature
        } catch (e: Exception) {
            Timber.e(e, "Error encrypting with Amber")
            pendingRequests.remove(requestId)
            return null
        }
    }

    /**
     * Request NIP-44 decryption from Amber
     */
    suspend fun decrypt(context: Context, ciphertext: String, senderPubkey: String): String? {
        val requestId = generateRequestId()
        val deferred = CompletableDeferred<SigningResult>()
        pendingRequests[requestId] = deferred

        try {
            val callbackUri = "$CALLBACK_SCHEME://$CALLBACK_HOST?id=$requestId"
            val encodedCiphertext = Uri.encode(ciphertext)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                `package` = AMBER_PACKAGE
                data = Uri.parse("nostrsigner:$encodedCiphertext?pubkey=$senderPubkey&compressionType=none&returnType=signature&type=nip44_decrypt&callbackUrl=$callbackUri")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)

            val result = deferred.await()
            return result.signature
        } catch (e: Exception) {
            Timber.e(e, "Error decrypting with Amber")
            pendingRequests.remove(requestId)
            return null
        }
    }

    /**
     * Complete a pending signing request (called from AmberResultActivity)
     */
    fun completePendingRequest(requestId: String?, signature: String?, event: String?) {
        if (requestId == null) {
            Timber.w("Amber callback with null request ID")
            return
        }

        val deferred = pendingRequests.remove(requestId)
        if (deferred != null) {
            deferred.complete(SigningResult(signature, event))
        } else {
            Timber.w("No pending request found for ID: $requestId")
        }
    }

    /**
     * Cancel a pending request
     */
    fun cancelPendingRequest(requestId: String) {
        pendingRequests.remove(requestId)?.complete(SigningResult(null, null, "Cancelled"))
    }

    private fun generateRequestId(): String {
        return java.util.UUID.randomUUID().toString()
    }
}
