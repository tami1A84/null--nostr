package io.nurunuru.app.data.errors

/**
 * Structured error handling for Nostr client.
 * Synced with web version: lib/errors.js
 */

enum class ErrorCategory {
    NETWORK, AUTH, ENCRYPTION, VALIDATION, RELAY, EVENT, STORAGE, UNKNOWN
}

data class ErrorCodeInfo(val code: Int, val message: String, val category: ErrorCategory)

object ErrorCode {
    // Network errors (1xxx)
    val NETWORK_TIMEOUT = ErrorCodeInfo(1001, "リクエストがタイムアウトしました", ErrorCategory.NETWORK)
    val NETWORK_OFFLINE = ErrorCodeInfo(1002, "ネットワークに接続できません", ErrorCategory.NETWORK)
    val NETWORK_FAILED = ErrorCodeInfo(1003, "ネットワークリクエストが失敗しました", ErrorCategory.NETWORK)
    val NETWORK_RETRIES_EXHAUSTED = ErrorCodeInfo(1004, "すべての再試行が失敗しました", ErrorCategory.NETWORK)

    // Authentication errors (2xxx)
    val AUTH_NO_SIGNER = ErrorCodeInfo(2001, "署名機能が利用できません", ErrorCategory.AUTH)
    val AUTH_SIGNING_FAILED = ErrorCodeInfo(2002, "署名に失敗しました", ErrorCategory.AUTH)
    val AUTH_PASSKEY_FAILED = ErrorCodeInfo(2003, "パスキーでの署名に失敗しました", ErrorCategory.AUTH)
    val AUTH_AMBER_FAILED = ErrorCodeInfo(2006, "Amberでの署名に失敗しました", ErrorCategory.AUTH)
    val AUTH_PUBKEY_FAILED = ErrorCodeInfo(2005, "公開鍵の取得に失敗しました", ErrorCategory.AUTH)
    val AUTH_RELAY_REQUIRED = ErrorCodeInfo(2009, "このリレーには認証が必要です", ErrorCategory.AUTH)

    // Encryption errors (3xxx)
    val ENCRYPTION_FAILED = ErrorCodeInfo(3001, "暗号化に失敗しました", ErrorCategory.ENCRYPTION)
    val DECRYPTION_FAILED = ErrorCodeInfo(3002, "復号に失敗しました", ErrorCategory.ENCRYPTION)
    val ENCRYPTION_NOT_AVAILABLE = ErrorCodeInfo(3003, "暗号化機能が利用できません", ErrorCategory.ENCRYPTION)
    val ENCRYPTION_KEY_REQUIRED = ErrorCodeInfo(3004, "秘密鍵が必要です", ErrorCategory.ENCRYPTION)

    // Validation errors (4xxx)
    val VALIDATION_INVALID_PUBKEY = ErrorCodeInfo(4001, "無効な公開鍵です", ErrorCategory.VALIDATION)
    val VALIDATION_INVALID_EVENT_ID = ErrorCodeInfo(4002, "無効なイベントIDです", ErrorCategory.VALIDATION)
    val VALIDATION_INVALID_RELAY_URL = ErrorCodeInfo(4003, "無効なリレーURLです", ErrorCategory.VALIDATION)
    val VALIDATION_INVALID_CONTENT = ErrorCodeInfo(4004, "無効なコンテンツです", ErrorCategory.VALIDATION)
    val VALIDATION_CONTENT_TOO_LONG = ErrorCodeInfo(4005, "コンテンツが長すぎます", ErrorCategory.VALIDATION)
    val VALIDATION_INVALID_NIP05 = ErrorCodeInfo(4006, "無効なNIP-05識別子です", ErrorCategory.VALIDATION)
    val VALIDATION_INVALID_LIGHTNING = ErrorCodeInfo(4007, "無効なLightningアドレスです", ErrorCategory.VALIDATION)
    val VALIDATION_INVALID_AMOUNT = ErrorCodeInfo(4008, "無効な金額です", ErrorCategory.VALIDATION)

    // Relay errors (5xxx)
    val RELAY_CONNECTION_FAILED = ErrorCodeInfo(5001, "リレーへの接続に失敗しました", ErrorCategory.RELAY)
    val RELAY_PUBLISH_FAILED = ErrorCodeInfo(5002, "イベントの送信に失敗しました", ErrorCategory.RELAY)
    val RELAY_SUBSCRIPTION_FAILED = ErrorCodeInfo(5003, "購読の開始に失敗しました", ErrorCategory.RELAY)
    val RELAY_CLOSED = ErrorCodeInfo(5004, "リレー接続が閉じられました", ErrorCategory.RELAY)
    val RELAY_PAYMENT_REQUIRED = ErrorCodeInfo(5005, "このリレーには支払いが必要です", ErrorCategory.RELAY)
    val RELAY_RATE_LIMITED = ErrorCodeInfo(5006, "レート制限に達しました", ErrorCategory.RELAY)
    val RELAY_NOT_AVAILABLE = ErrorCodeInfo(5007, "リレーが利用できません", ErrorCategory.RELAY)

    // Event errors (6xxx)
    val EVENT_NOT_FOUND = ErrorCodeInfo(6001, "イベントが見つかりません", ErrorCategory.EVENT)
    val EVENT_INVALID_SIGNATURE = ErrorCodeInfo(6002, "無効な署名です", ErrorCategory.EVENT)
    val EVENT_CREATION_FAILED = ErrorCodeInfo(6003, "イベントの作成に失敗しました", ErrorCategory.EVENT)
    val EVENT_DELETE_FAILED = ErrorCodeInfo(6004, "イベントの削除に失敗しました", ErrorCategory.EVENT)
    val EVENT_PROTECTED = ErrorCodeInfo(6005, "保護されたイベントです", ErrorCategory.EVENT)

    // Storage errors (7xxx)
    val STORAGE_READ_FAILED = ErrorCodeInfo(7001, "データの読み込みに失敗しました", ErrorCategory.STORAGE)
    val STORAGE_WRITE_FAILED = ErrorCodeInfo(7002, "データの保存に失敗しました", ErrorCategory.STORAGE)
    val STORAGE_QUOTA_EXCEEDED = ErrorCodeInfo(7003, "ストレージ容量が不足しています", ErrorCategory.STORAGE)

    // Unknown
    val UNKNOWN = ErrorCodeInfo(9999, "予期しないエラーが発生しました", ErrorCategory.UNKNOWN)
}

open class NostrException(
    val errorCode: ErrorCodeInfo,
    val details: String? = null,
    cause: Throwable? = null,
    val context: Map<String, Any?>? = null
) : Exception(
    if (details != null) "${errorCode.message}: $details" else errorCode.message,
    cause
) {
    val code: Int get() = errorCode.code
    val category: ErrorCategory get() = errorCode.category

    fun isRetryable(): Boolean =
        category in listOf(ErrorCategory.NETWORK, ErrorCategory.RELAY) &&
        code !in listOf(ErrorCode.RELAY_PAYMENT_REQUIRED.code, ErrorCode.RELAY_NOT_AVAILABLE.code)

    fun getUserMessage(): String = message ?: errorCode.message
}

class NetworkException(errorCode: ErrorCodeInfo, details: String? = null, cause: Throwable? = null)
    : NostrException(errorCode, details, cause)

class AuthException(errorCode: ErrorCodeInfo, details: String? = null, cause: Throwable? = null)
    : NostrException(errorCode, details, cause)

class EncryptionException(errorCode: ErrorCodeInfo, details: String? = null, cause: Throwable? = null)
    : NostrException(errorCode, details, cause)

class ValidationException(errorCode: ErrorCodeInfo, details: String? = null, cause: Throwable? = null)
    : NostrException(errorCode, details, cause)

class RelayException(
    errorCode: ErrorCodeInfo,
    details: String? = null,
    cause: Throwable? = null,
    val relayUrl: String? = null
) : NostrException(errorCode, details, cause, context = relayUrl?.let { mapOf("relayUrl" to it) })

class EventException(
    errorCode: ErrorCodeInfo,
    details: String? = null,
    cause: Throwable? = null,
    val eventId: String? = null
) : NostrException(errorCode, details, cause, context = eventId?.let { mapOf("eventId" to it) })

class StorageException(errorCode: ErrorCodeInfo, details: String? = null, cause: Throwable? = null)
    : NostrException(errorCode, details, cause)

/** Error factory */
fun createError(errorCode: ErrorCodeInfo, details: String? = null, cause: Throwable? = null): NostrException {
    return when (errorCode.category) {
        ErrorCategory.NETWORK -> NetworkException(errorCode, details, cause)
        ErrorCategory.AUTH -> AuthException(errorCode, details, cause)
        ErrorCategory.ENCRYPTION -> EncryptionException(errorCode, details, cause)
        ErrorCategory.VALIDATION -> ValidationException(errorCode, details, cause)
        ErrorCategory.RELAY -> RelayException(errorCode, details, cause)
        ErrorCategory.EVENT -> EventException(errorCode, details, cause)
        ErrorCategory.STORAGE -> StorageException(errorCode, details, cause)
        ErrorCategory.UNKNOWN -> NostrException(errorCode, details, cause)
    }
}

/** Wrap unknown error as NostrException */
fun wrapError(error: Throwable, details: String? = null): NostrException {
    if (error is NostrException) return error
    return NostrException(ErrorCode.UNKNOWN, details ?: error.message, error)
}

/** Result type for error-first returns */
sealed class NostrResult<out T> {
    data class Success<T>(val data: T) : NostrResult<T>()
    data class Failure(val error: NostrException) : NostrResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrNull(): T? = (this as? Success)?.data
    fun errorOrNull(): NostrException? = (this as? Failure)?.error
}

suspend fun <T> tryAsync(block: suspend () -> T): NostrResult<T> {
    return try {
        NostrResult.Success(block())
    } catch (e: NostrException) {
        NostrResult.Failure(e)
    } catch (e: Exception) {
        NostrResult.Failure(wrapError(e))
    }
}
