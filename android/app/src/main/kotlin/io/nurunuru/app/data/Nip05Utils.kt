package io.nurunuru.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.Log

object Nip05Utils {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = mutableMapOf<String, Boolean>()

    suspend fun verifyNip05(nip05: String, pubkeyHex: String): Boolean = withContext(Dispatchers.IO) {
        val cacheKey = "$nip05:$pubkeyHex"
        cache[cacheKey]?.let { return@withContext it }

        if (!nip05.contains("@")) return@withContext false

        val parts = nip05.split("@")
        if (parts.size != 2) return@withContext false

        val name = parts[0]
        val domain = parts[1]

        val url = "https://$domain/.well-known/nostr.json?name=$name"

        try {
            val request = Request.Builder()
                .url(url)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false

                val body = response.body?.string() ?: return@withContext false
                val root = json.parseToJsonElement(body).jsonObject
                val names = root["names"]?.jsonObject ?: return@withContext false
                val foundPubkey = names[name]?.jsonPrimitive?.content ?: return@withContext false

                return@withContext foundPubkey.lowercase() == pubkeyHex.lowercase()
            }
        } catch (e: Exception) {
            Log.w("Nip05Utils", "Verification failed for $nip05: ${e.message}")
            false
        }
    }
}
