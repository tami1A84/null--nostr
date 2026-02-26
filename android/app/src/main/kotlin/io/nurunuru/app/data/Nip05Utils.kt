package io.nurunuru.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

object Nip05Utils {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Boolean>()

    suspend fun verifyNip05(nip05: String, pubkeyHex: String): Boolean = withContext(Dispatchers.IO) {
        // Normalize NIP-05 format (handle domain-only format)
        val normalizedNip05 = if (!nip05.contains("@")) {
            "_@$nip05"
        } else {
            nip05
        }

        val cacheKey = "$normalizedNip05:$pubkeyHex"
        cache[cacheKey]?.let { return@withContext it }

        val parts = normalizedNip05.split("@")
        if (parts.size != 2) return@withContext false

        val name = parts[0]
        val domain = parts[1]

        val url = "https://$domain/.well-known/nostr.json?name=$name"

        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "NuruNuru-Android/1.0")
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

    suspend fun resolveNip05(nip05: String): String? = withContext(Dispatchers.IO) {
        // Normalize NIP-05 format
        val normalizedNip05 = if (!nip05.contains("@")) {
            "_@$nip05"
        } else {
            nip05
        }

        val parts = normalizedNip05.split("@")
        if (parts.size != 2) return@withContext null

        val name = parts[0]
        val domain = parts[1]

        val url = "https://$domain/.well-known/nostr.json?name=$name"

        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "NuruNuru-Android/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null

                val body = response.body?.string() ?: return@withContext null
                val root = json.parseToJsonElement(body).jsonObject
                val names = root["names"]?.jsonObject ?: return@withContext null
                return@withContext names[name]?.jsonPrimitive?.content
            }
        } catch (e: Exception) {
            Log.w("Nip05Utils", "Resolution failed for $nip05: ${e.message}")
            null
        }
    }
}
