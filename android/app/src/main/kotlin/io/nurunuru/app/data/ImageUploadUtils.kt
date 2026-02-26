package io.nurunuru.app.data

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import rust.nostr.sdk.*

object ImageUploadUtils {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun uploadToBlossom(
        fileBytes: ByteArray,
        mimeType: String,
        signer: NostrSigner?,
        blossomUrl: String = "https://blossom.nostr.build"
    ): String? = withContext(Dispatchers.IO) {
        try {
            val requestBuilder = okhttp3.Request.Builder()
                .url("$blossomUrl/upload")
                .put(fileBytes.toRequestBody(mimeType.toMediaTypeOrNull()))

            // Blossom NIP-98 Auth (Kind 24242)
            if (signer != null) {
                val hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(fileBytes)
                    .joinToString("") { "%02x".format(it) }

                val now = System.currentTimeMillis() / 1000
                val authEventBuilder = EventBuilder(Kind(24242u), "Upload to Blossom")
                    .tags(listOf(
                        Tag.parse(listOf("t", "upload")),
                        Tag.parse(listOf("x", hash)),
                        Tag.parse(listOf("expiration", (now + 300).toString()))
                    ))

                val publicKey = signer.getPublicKey()
                val unsignedEvent = authEventBuilder.build(publicKey)
                val authEvent = signer.signEvent(unsignedEvent)
                val authHeader = Base64.encodeToString(authEvent.asJson().toByteArray(), Base64.NO_WRAP)
                requestBuilder.addHeader("Authorization", "Nostr $authHeader")
            }

            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val root = json.parseToJsonElement(body).jsonObject
                return@withContext root["url"]?.jsonPrimitive?.content
            }
        } catch (e: Exception) {
            Log.e("ImageUploadUtils", "Blossom upload failed", e)
            null
        }
    }

    suspend fun uploadToNostrBuild(
        fileBytes: ByteArray,
        mimeType: String,
        signer: NostrSigner? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://nostr.build/api/v2/upload/files"
            val requestBuilder = okhttp3.Request.Builder().url(url)

            // Optional NIP-98 Auth
            if (signer != null) {
                try {
                    val authEventBuilder = EventBuilder(Kind(27235u), "")
                        .tags(listOf(
                            Tag.parse(listOf("u", url)),
                            Tag.parse(listOf("method", "POST"))
                        ))

                    val publicKey = signer.getPublicKey()
                    val unsignedEvent = authEventBuilder.build(publicKey)
                    val authEvent = signer.signEvent(unsignedEvent)
                    val authHeader = Base64.encodeToString(authEvent.asJson().toByteArray(), Base64.NO_WRAP)
                    requestBuilder.addHeader("Authorization", "Nostr $authHeader")
                } catch (e: Exception) {
                    Log.w("ImageUploadUtils", "Failed to create NIP-98 header", e)
                }
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "image.${mimeType.split("/").last()}",
                    fileBytes.toRequestBody(mimeType.toMediaTypeOrNull())
                )
                .build()

            val request = requestBuilder
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.w("ImageUploadUtils", "Upload failed: ${response.code} ${response.message} body: $body")
                    return@withContext null
                }

                Log.d("ImageUploadUtils", "Upload response: $body")
                val root = json.parseToJsonElement(body).jsonObject

                // nostr.build V2 response handling
                if (root["status"]?.jsonPrimitive?.content == "success") {
                    val data = root["data"]?.jsonArray?.getOrNull(0)?.jsonObject
                    return@withContext data?.get("url")?.jsonPrimitive?.content
                } else {
                    // Fallback for different response formats or errors
                    Log.w("ImageUploadUtils", "Upload status not success: $body")
                    val url = root["url"]?.jsonPrimitive?.content
                    if (url != null) return@withContext url
                }
            }
        } catch (e: Exception) {
            Log.e("ImageUploadUtils", "Upload failed", e)
        }
        null
    }
}
