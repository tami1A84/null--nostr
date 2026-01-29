package com.example.nostr.nostr.nip

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * NIP-05: Mapping Nostr keys to DNS-based internet identifiers
 *
 * Format: username@domain.com
 * Verifies that the domain's .well-known/nostr.json file contains the user's pubkey
 */
object Nip05 {

    private val client = OkHttpClient()

    /**
     * Verify a NIP-05 identifier
     *
     * @param nip05 The NIP-05 identifier (e.g., "alice@domain.com")
     * @param pubkey The public key to verify
     * @return True if the identifier is valid and matches the pubkey
     */
    suspend fun verify(nip05: String, pubkey: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val (name, domain) = parseNip05(nip05) ?: return@withContext false
                val url = "https://$domain/.well-known/nostr.json?name=$name"

                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Timber.w("NIP-05 verification failed: HTTP ${response.code}")
                    return@withContext false
                }

                val body = response.body?.string() ?: return@withContext false
                val json = JsonParser.parseString(body).asJsonObject
                val names = json.getAsJsonObject("names")
                val verifiedPubkey = names?.get(name)?.asString

                verifiedPubkey?.equals(pubkey, ignoreCase = true) == true
            } catch (e: Exception) {
                Timber.e(e, "NIP-05 verification error")
                false
            }
        }
    }

    /**
     * Resolve a NIP-05 identifier to a pubkey
     *
     * @param nip05 The NIP-05 identifier (e.g., "alice@domain.com")
     * @return The pubkey if found, null otherwise
     */
    suspend fun resolve(nip05: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val (name, domain) = parseNip05(nip05) ?: return@withContext null
                val url = "https://$domain/.well-known/nostr.json?name=$name"

                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val json = JsonParser.parseString(body).asJsonObject
                val names = json.getAsJsonObject("names")
                names?.get(name)?.asString
            } catch (e: Exception) {
                Timber.e(e, "NIP-05 resolution error")
                null
            }
        }
    }

    /**
     * Get relay recommendations from NIP-05
     *
     * @param nip05 The NIP-05 identifier
     * @param pubkey The pubkey to get relays for
     * @return List of relay URLs, empty if not found
     */
    suspend fun getRelays(nip05: String, pubkey: String): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                val (name, domain) = parseNip05(nip05) ?: return@withContext emptyList()
                val url = "https://$domain/.well-known/nostr.json?name=$name"

                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext emptyList()
                }

                val body = response.body?.string() ?: return@withContext emptyList()
                val json = JsonParser.parseString(body).asJsonObject
                val relays = json.getAsJsonObject("relays")
                val userRelays = relays?.getAsJsonArray(pubkey)

                userRelays?.map { it.asString } ?: emptyList()
            } catch (e: Exception) {
                Timber.e(e, "NIP-05 relay fetch error")
                emptyList()
            }
        }
    }

    /**
     * Parse NIP-05 identifier into name and domain
     */
    private fun parseNip05(nip05: String): Pair<String, String>? {
        val parts = nip05.split("@")
        if (parts.size != 2) return null

        val name = parts[0].ifEmpty { "_" }
        val domain = parts[1]

        if (domain.isEmpty()) return null

        return Pair(name, domain)
    }
}
