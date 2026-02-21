package io.nurunuru.shared.protocol

import io.nurunuru.shared.models.NostrEvent
import io.nurunuru.shared.platform.*
import kotlinx.serialization.json.*

/**
 * Pure NIP-01 protocol helpers: event ID computation and signing.
 * All functions are platform-independent; cryptographic primitives are
 * provided via `expect` functions in Platform.kt.
 */
object NostrProtocol {

    /**
     * Compute the NIP-01 event ID:
     * SHA-256 of the canonical JSON serialization [0, pubkey, created_at, kind, tags, content].
     */
    fun computeEventId(event: NostrEvent): String {
        val serialized = buildCanonicalJson(event)
        return sha256(serialized.toByteArray(Charsets.UTF_8)).toHex()
    }

    /**
     * Build and sign an event template.
     * Returns a fully populated [NostrEvent] ready to publish.
     */
    fun finalizeEvent(template: NostrEvent, privateKeyHex: String): NostrEvent? {
        val privKeyBytes = privateKeyHex.hexToBytes() ?: return null
        return try {
            val id = computeEventId(template)
            val msgBytes = id.hexToBytes()!!
            val sig = schnorrSign(msgBytes, privKeyBytes, null).toHex()
            template.copy(id = id, sig = sig)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Derive x-only public key hex from a private key hex.
     */
    fun getPublicKey(privateKeyHex: String): String? {
        val privKeyBytes = privateKeyHex.hexToBytes() ?: return null
        return try {
            pubkeyCreate(privKeyBytes).toHex()
        } catch (e: Exception) {
            null
        }
    }

    // ─── NIP-04 Encryption ────────────────────────────────────────────────────

    /**
     * NIP-04 encrypt [content] for [recipientPubkeyHex].
     * Returns "<ciphertext_base64>?iv=<iv_base64>" or null on failure.
     */
    fun encryptNip04(
        senderPrivKeyHex: String,
        recipientPubkeyHex: String,
        content: String
    ): String? {
        return try {
            val privKeyBytes = senderPrivKeyHex.hexToBytes() ?: return null
            val pubKeyBytes = recipientPubkeyHex.hexToBytes() ?: return null
            val sharedSecret = ecdhNip04(privKeyBytes, pubKeyBytes)

            val iv = randomBytes(16)
            val encrypted = aesEncryptCbc(sharedSecret, iv, content.toByteArray(Charsets.UTF_8))

            "${base64Encode(encrypted)}?iv=${base64Encode(iv)}"
        } catch (e: Exception) {
            null
        }
    }

    /**
     * NIP-04 decrypt [encryptedContent] from [senderPubkeyHex].
     * [encryptedContent] has format "<ciphertext_base64>?iv=<iv_base64>".
     */
    fun decryptNip04(
        recipientPrivKeyHex: String,
        senderPubkeyHex: String,
        encryptedContent: String
    ): String? {
        return try {
            val privKeyBytes = recipientPrivKeyHex.hexToBytes() ?: return null
            val pubKeyBytes = senderPubkeyHex.hexToBytes() ?: return null
            val sharedSecret = ecdhNip04(privKeyBytes, pubKeyBytes)

            val parts = encryptedContent.split("?iv=")
            if (parts.size != 2) return null
            val ciphertext = base64Decode(parts[0])
            val iv = base64Decode(parts[1])

            String(aesDecryptCbc(sharedSecret, iv, ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private fun buildCanonicalJson(event: NostrEvent): String =
        buildJsonArray {
            add(0)
            add(event.pubkey)
            add(event.createdAt)
            add(event.kind)
            add(buildJsonArray {
                event.tags.forEach { tag ->
                    add(buildJsonArray { tag.forEach { add(it) } })
                }
            })
            add(event.content)
        }.toString()
}

/**
 * AES-128/256-CBC encrypt. Platform-agnostic expect declarations route to
 * the correct JVM/Apple crypto implementation.
 */
expect fun aesEncryptCbc(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray

/** AES-128/256-CBC decrypt. */
expect fun aesDecryptCbc(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray
