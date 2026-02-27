package io.nurunuru.app.data

import rust.nostr.sdk.*

class InternalSigner(val privateKeyHex: String) : AppSigner {
    private val keys = Keys.parse(privateKeyHex)
    private val signer = NostrSigner.keys(keys)

    override fun getPublicKeyHex(): String = keys.publicKey().toHex()

    override suspend fun signEvent(eventJson: String): String? {
        return try {
            val unsigned = UnsignedEvent.fromJson(eventJson)
            signer.signEvent(unsigned).asJson()
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun nip04Encrypt(receiverPubkeyHex: String, content: String): String? {
        return try {
            signer.nip04Encrypt(PublicKey.parse(receiverPubkeyHex), content)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun nip04Decrypt(senderPubkeyHex: String, content: String): String? {
        return try {
            signer.nip04Decrypt(PublicKey.parse(senderPubkeyHex), content)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun nip44Encrypt(receiverPubkeyHex: String, content: String): String? {
        return try {
            signer.nip44Encrypt(PublicKey.parse(receiverPubkeyHex), content)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun nip44Decrypt(senderPubkeyHex: String, content: String): String? {
        return try {
            signer.nip44Decrypt(PublicKey.parse(senderPubkeyHex), content)
        } catch (e: Exception) {
            null
        }
    }
}
