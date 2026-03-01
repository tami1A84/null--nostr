package io.nurunuru.app.data

import rust.nostr.sdk.*
import java.io.Closeable

/**
 * 内部署名者 — SecureKeyManager 経由で秘密鍵にアクセスする。
 *
 * 秘密鍵は SecureKeyManager がメモリ上に ByteArray で保持しており、
 * 署名操作時にのみ一時的に取得する。close() でゼロ化を行う。
 */
class InternalSigner(private val keyManager: SecureKeyManager) : AppSigner, Closeable {

    // rust-nostr Keys/Signer は初回アクセス時に生成し、キャッシュ
    private var _keys: Keys? = null
    private var _signer: NostrSigner? = null

    private fun ensureKeys(): Keys {
        _keys?.let { return it }
        val hex = keyManager.getKeyHexTemporary()
            ?: throw IllegalStateException("Key not unlocked in SecureKeyManager")
        val keys = Keys.parse(hex)
        _keys = keys
        _signer = NostrSigner.keys(keys)
        return keys
    }

    private fun ensureSigner(): NostrSigner {
        _signer?.let { return it }
        ensureKeys()
        return _signer!!
    }

    override fun getPublicKeyHex(): String {
        // 公開鍵は SecureKeyManager から取得可能 (秘密鍵アンロック不要)
        return keyManager.getStoredPublicKeyHex()
            ?: ensureKeys().publicKey().toHex()
    }

    override suspend fun signEvent(eventJson: String): String? {
        return try {
            val unsigned = UnsignedEvent.fromJson(eventJson)
            ensureSigner().signEvent(unsigned).asJson()
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun nip04Encrypt(receiverPubkeyHex: String, content: String): String? {
        return try {
            ensureSigner().nip04Encrypt(PublicKey.parse(receiverPubkeyHex), content)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun nip04Decrypt(senderPubkeyHex: String, content: String): String? {
        return try {
            ensureSigner().nip04Decrypt(PublicKey.parse(senderPubkeyHex), content)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun nip44Encrypt(receiverPubkeyHex: String, content: String): String? {
        return try {
            ensureSigner().nip44Encrypt(PublicKey.parse(receiverPubkeyHex), content)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun nip44Decrypt(senderPubkeyHex: String, content: String): String? {
        return try {
            ensureSigner().nip44Decrypt(PublicKey.parse(senderPubkeyHex), content)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * NostrClient の SDK 初期化用に秘密鍵 hex を一時取得する。
     * 内部使用限定 — 外部からの呼び出しは避けること。
     */
    internal fun getKeyHexFromManager(): String? {
        return keyManager.getKeyHexTemporary()
    }

    /**
     * キャッシュされた Keys/Signer を破棄する。
     * SecureKeyManager.zeroize() と組み合わせて使う。
     */
    override fun close() {
        _keys = null
        _signer = null
    }
}
