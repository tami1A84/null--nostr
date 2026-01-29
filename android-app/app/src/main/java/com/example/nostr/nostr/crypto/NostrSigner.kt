package com.example.nostr.nostr.crypto

import com.example.nostr.nostr.event.NostrEvent
import com.example.nostr.nostr.event.UnsignedEvent
import com.example.nostr.nostr.event.hexToByteArray
import com.example.nostr.nostr.event.toHexString
import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Nostr cryptographic operations using secp256k1
 */
object NostrSigner {

    private val secp256k1 = Secp256k1.get()
    private val secureRandom = SecureRandom()

    /**
     * Generate a new private key
     */
    fun generatePrivateKey(): ByteArray {
        val privateKey = ByteArray(32)
        secureRandom.nextBytes(privateKey)
        // Ensure the key is valid for secp256k1
        while (!secp256k1.secKeyVerify(privateKey)) {
            secureRandom.nextBytes(privateKey)
        }
        return privateKey
    }

    /**
     * Derive public key from private key
     */
    fun getPublicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "Private key must be 32 bytes" }
        // Get compressed public key (33 bytes) and extract x-coordinate (32 bytes)
        val compressedPubKey = secp256k1.pubKeyCompress(secp256k1.pubkeyCreate(privateKey))
        // x-only public key is the last 32 bytes of compressed key (drop the 02/03 prefix)
        return compressedPubKey.copyOfRange(1, 33)
    }

    /**
     * Sign an event with a private key using Schnorr signature (BIP-340)
     */
    fun signEvent(unsignedEvent: UnsignedEvent, privateKey: ByteArray): NostrEvent {
        val serialized = unsignedEvent.serialize()
        val id = sha256(serialized.toByteArray(Charsets.UTF_8))
        val signature = signSchnorr(id, privateKey)

        return NostrEvent(
            id = id.toHexString(),
            pubkey = unsignedEvent.pubkey,
            createdAt = unsignedEvent.createdAt,
            kind = unsignedEvent.kind,
            tags = unsignedEvent.tags,
            content = unsignedEvent.content,
            sig = signature.toHexString()
        )
    }

    /**
     * Sign data with Schnorr signature (BIP-340)
     */
    fun signSchnorr(data: ByteArray, privateKey: ByteArray): ByteArray {
        require(data.size == 32) { "Data must be 32 bytes (SHA-256 hash)" }
        require(privateKey.size == 32) { "Private key must be 32 bytes" }

        // Generate aux randomness
        val auxRand = ByteArray(32)
        secureRandom.nextBytes(auxRand)

        return secp256k1.signSchnorr(data, privateKey, auxRand)
    }

    /**
     * Verify Schnorr signature (BIP-340)
     */
    fun verifySchnorr(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean {
        require(signature.size == 64) { "Signature must be 64 bytes" }
        require(data.size == 32) { "Data must be 32 bytes" }
        require(publicKey.size == 32) { "Public key must be 32 bytes (x-only)" }

        return secp256k1.verifySchnorr(signature, data, publicKey)
    }

    /**
     * Verify a Nostr event signature
     */
    fun verifyEvent(event: NostrEvent): Boolean {
        return try {
            val serialized = NostrEvent.serializeForSigning(
                event.pubkey,
                event.createdAt,
                event.kind,
                event.tags,
                event.content
            )
            val expectedId = sha256(serialized.toByteArray(Charsets.UTF_8))

            // Check ID matches
            if (expectedId.toHexString() != event.id) return false

            // Verify signature
            verifySchnorr(
                signature = event.sig.hexToByteArray(),
                data = expectedId,
                publicKey = event.pubkey.hexToByteArray()
            )
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Compute shared secret for NIP-04/NIP-44 encryption
     */
    fun computeSharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "Private key must be 32 bytes" }
        require(publicKey.size == 32) { "Public key must be 32 bytes (x-only)" }

        // Convert x-only public key to full public key (add 02 prefix for even y)
        val fullPubKey = ByteArray(33)
        fullPubKey[0] = 0x02
        System.arraycopy(publicKey, 0, fullPubKey, 1, 32)

        // Compute ECDH shared secret
        val sharedPoint = secp256k1.pubKeyTweakMul(fullPubKey, privateKey)
        // Take x-coordinate of the resulting point
        return sharedPoint.copyOfRange(1, 33)
    }

    /**
     * SHA-256 hash
     */
    fun sha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    /**
     * Convert private key to nsec (NIP-19)
     */
    fun privateKeyToNsec(privateKey: ByteArray): String {
        return Bech32.encode("nsec", privateKey)
    }

    /**
     * Convert public key to npub (NIP-19)
     */
    fun publicKeyToNpub(publicKey: ByteArray): String {
        return Bech32.encode("npub", publicKey)
    }

    /**
     * Parse nsec to private key
     */
    fun nsecToPrivateKey(nsec: String): ByteArray {
        val (hrp, data) = Bech32.decode(nsec)
        require(hrp == "nsec") { "Invalid nsec format" }
        return data
    }

    /**
     * Parse npub to public key
     */
    fun npubToPublicKey(npub: String): ByteArray {
        val (hrp, data) = Bech32.decode(npub)
        require(hrp == "npub") { "Invalid npub format" }
        return data
    }
}
