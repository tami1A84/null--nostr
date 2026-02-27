package io.nurunuru.app.data

interface AppSigner {
    fun getPublicKeyHex(): String
    suspend fun signEvent(eventJson: String): String?
    suspend fun nip04Encrypt(receiverPubkeyHex: String, content: String): String?
    suspend fun nip04Decrypt(senderPubkeyHex: String, content: String): String?
    suspend fun nip44Encrypt(receiverPubkeyHex: String, content: String): String?
    suspend fun nip44Decrypt(senderPubkeyHex: String, content: String): String?
}
