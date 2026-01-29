package com.example.nostr.nostr.nip

import com.example.nostr.nostr.event.EventKind
import com.example.nostr.nostr.event.NostrEvent
import com.example.nostr.nostr.event.UnsignedEvent
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.URLEncoder

/**
 * NIP-57: Lightning Zaps
 *
 * Implements zap requests and receipt handling
 */
object Nip57 {

    private val client = OkHttpClient()

    /**
     * Lightning address info from LNURL
     */
    data class LnurlPayInfo(
        val callback: String,
        val minSendable: Long,
        val maxSendable: Long,
        val metadata: String,
        val allowsNostr: Boolean = false,
        val nostrPubkey: String? = null
    )

    /**
     * Fetch LNURL-pay info for a lightning address
     *
     * @param lightningAddress The lightning address (e.g., "user@domain.com")
     * @return LnurlPayInfo if successful, null otherwise
     */
    suspend fun fetchLnurlPayInfo(lightningAddress: String): LnurlPayInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val parts = lightningAddress.split("@")
                if (parts.size != 2) return@withContext null

                val (username, domain) = parts
                val url = "https://$domain/.well-known/lnurlp/$username"

                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Timber.w("LNURL fetch failed: HTTP ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val json = JsonParser.parseString(body).asJsonObject

                LnurlPayInfo(
                    callback = json.get("callback").asString,
                    minSendable = json.get("minSendable").asLong,
                    maxSendable = json.get("maxSendable").asLong,
                    metadata = json.get("metadata").asString,
                    allowsNostr = json.get("allowsNostr")?.asBoolean ?: false,
                    nostrPubkey = json.get("nostrPubkey")?.asString
                )
            } catch (e: Exception) {
                Timber.e(e, "Error fetching LNURL pay info")
                null
            }
        }
    }

    /**
     * Create a zap request event (kind 9734)
     *
     * @param senderPubkey The sender's pubkey
     * @param recipientPubkey The recipient's pubkey
     * @param eventId Optional event ID being zapped
     * @param amount Amount in millisatoshis
     * @param relays List of relays to publish the zap receipt
     * @param content Optional comment
     * @return UnsignedEvent for the zap request
     */
    fun createZapRequest(
        senderPubkey: String,
        recipientPubkey: String,
        eventId: String? = null,
        amount: Long,
        relays: List<String>,
        content: String = ""
    ): UnsignedEvent {
        val tags = mutableListOf<List<String>>()

        // Required tags
        tags.add(listOf("p", recipientPubkey))
        tags.add(listOf("relays") + relays)
        tags.add(listOf("amount", amount.toString()))

        // Optional event tag
        if (eventId != null) {
            tags.add(listOf("e", eventId))
        }

        return UnsignedEvent(
            pubkey = senderPubkey,
            kind = EventKind.ZAP_REQUEST,
            tags = tags,
            content = content
        )
    }

    /**
     * Fetch a lightning invoice for a zap
     *
     * @param lnurlPayInfo The LNURL pay info
     * @param amount Amount in millisatoshis
     * @param zapRequest Optional signed zap request event
     * @return Invoice string if successful, null otherwise
     */
    suspend fun fetchInvoice(
        lnurlPayInfo: LnurlPayInfo,
        amount: Long,
        zapRequest: NostrEvent? = null
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                var url = "${lnurlPayInfo.callback}?amount=$amount"

                if (zapRequest != null && lnurlPayInfo.allowsNostr) {
                    val zapRequestJson = com.google.gson.Gson().toJson(zapRequest)
                    val encodedZapRequest = URLEncoder.encode(zapRequestJson, "UTF-8")
                    url += "&nostr=$encodedZapRequest"
                }

                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Timber.w("Invoice fetch failed: HTTP ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val json = JsonParser.parseString(body).asJsonObject

                json.get("pr")?.asString
            } catch (e: Exception) {
                Timber.e(e, "Error fetching invoice")
                null
            }
        }
    }

    /**
     * Parse a zap receipt (kind 9735) to extract zap details
     *
     * @param zapReceipt The zap receipt event
     * @return Parsed zap info or null if invalid
     */
    fun parseZapReceipt(zapReceipt: NostrEvent): ZapInfo? {
        if (zapReceipt.kind != EventKind.ZAP_RECEIPT) return null

        try {
            // Get the embedded zap request from description tag
            val descriptionTag = zapReceipt.tags.find { it.firstOrNull() == "description" }
            val zapRequestJson = descriptionTag?.getOrNull(1) ?: return null

            val zapRequest = com.google.gson.Gson().fromJson(zapRequestJson, NostrEvent::class.java)

            // Get amount from bolt11 tag
            val bolt11Tag = zapReceipt.tags.find { it.firstOrNull() == "bolt11" }
            val bolt11 = bolt11Tag?.getOrNull(1)

            // Get recipient
            val pTag = zapReceipt.tags.find { it.firstOrNull() == "p" }
            val recipientPubkey = pTag?.getOrNull(1)

            // Get event being zapped
            val eTag = zapReceipt.tags.find { it.firstOrNull() == "e" }
            val eventId = eTag?.getOrNull(1)

            // Get amount from zap request
            val amountTag = zapRequest.tags.find { it.firstOrNull() == "amount" }
            val amount = amountTag?.getOrNull(1)?.toLongOrNull() ?: 0

            return ZapInfo(
                id = zapReceipt.id,
                senderPubkey = zapRequest.pubkey,
                recipientPubkey = recipientPubkey ?: "",
                eventId = eventId,
                amount = amount,
                content = zapRequest.content,
                createdAt = zapReceipt.createdAt
            )
        } catch (e: Exception) {
            Timber.e(e, "Error parsing zap receipt")
            return null
        }
    }

    /**
     * Parsed zap information
     */
    data class ZapInfo(
        val id: String,
        val senderPubkey: String,
        val recipientPubkey: String,
        val eventId: String?,
        val amount: Long, // in millisatoshis
        val content: String,
        val createdAt: Long
    ) {
        val amountSats: Long get() = amount / 1000
    }
}
