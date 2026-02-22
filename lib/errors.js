/**
 * Custom Error Classes for Nostr Client
 *
 * Provides structured error handling with error codes, context,
 * and user-friendly messages for better debugging and UX.
 *
 * @module errors
 */

// ============================================
// Error Codes
// ============================================

/**
 * Error code categories
 * @readonly
 * @enum {string}
 */
export const ErrorCategory = {
  NETWORK: 'NETWORK',
  AUTH: 'AUTH',
  ENCRYPTION: 'ENCRYPTION',
  VALIDATION: 'VALIDATION',
  RELAY: 'RELAY',
  EVENT: 'EVENT',
  STORAGE: 'STORAGE',
  UNKNOWN: 'UNKNOWN'
}

/**
 * Error codes with descriptions
 * @readonly
 * @enum {Object}
 */
export const ErrorCode = {
  // Network errors (1xxx)
  NETWORK_TIMEOUT: { code: 1001, message: 'リクエストがタイムアウトしました', category: ErrorCategory.NETWORK },
  NETWORK_OFFLINE: { code: 1002, message: 'ネットワークに接続できません', category: ErrorCategory.NETWORK },
  NETWORK_FAILED: { code: 1003, message: 'ネットワークリクエストが失敗しました', category: ErrorCategory.NETWORK },
  NETWORK_RETRIES_EXHAUSTED: { code: 1004, message: 'すべての再試行が失敗しました', category: ErrorCategory.NETWORK },

  // Authentication errors (2xxx)
  AUTH_NO_SIGNER: { code: 2001, message: '署名機能が利用できません', category: ErrorCategory.AUTH },
  AUTH_SIGNING_FAILED: { code: 2002, message: '署名に失敗しました', category: ErrorCategory.AUTH },
  AUTH_PASSKEY_FAILED: { code: 2003, message: 'パスキーでの署名に失敗しました', category: ErrorCategory.AUTH },
  AUTH_NIP07_NOT_FOUND: { code: 2004, message: 'NIP-07拡張機能が見つかりません', category: ErrorCategory.AUTH },
  AUTH_PUBKEY_FAILED: { code: 2005, message: '公開鍵の取得に失敗しました', category: ErrorCategory.AUTH },
  AUTH_AMBER_FAILED: { code: 2006, message: 'Amberでの署名に失敗しました', category: ErrorCategory.AUTH },
  AUTH_BUNKER_FAILED: { code: 2007, message: 'Nostr Connectでの署名に失敗しました', category: ErrorCategory.AUTH },
  AUTH_CHALLENGE_EXPIRED: { code: 2008, message: '認証チャレンジの有効期限が切れています', category: ErrorCategory.AUTH },
  AUTH_RELAY_REQUIRED: { code: 2009, message: 'このリレーには認証が必要です', category: ErrorCategory.AUTH },

  // Encryption errors (3xxx)
  ENCRYPTION_FAILED: { code: 3001, message: '暗号化に失敗しました', category: ErrorCategory.ENCRYPTION },
  DECRYPTION_FAILED: { code: 3002, message: '復号に失敗しました', category: ErrorCategory.ENCRYPTION },
  ENCRYPTION_NOT_AVAILABLE: { code: 3003, message: '暗号化機能が利用できません', category: ErrorCategory.ENCRYPTION },
  ENCRYPTION_KEY_REQUIRED: { code: 3004, message: '秘密鍵が必要です', category: ErrorCategory.ENCRYPTION },

  // Validation errors (4xxx)
  VALIDATION_INVALID_PUBKEY: { code: 4001, message: '無効な公開鍵です', category: ErrorCategory.VALIDATION },
  VALIDATION_INVALID_EVENT_ID: { code: 4002, message: '無効なイベントIDです', category: ErrorCategory.VALIDATION },
  VALIDATION_INVALID_RELAY_URL: { code: 4003, message: '無効なリレーURLです', category: ErrorCategory.VALIDATION },
  VALIDATION_INVALID_CONTENT: { code: 4004, message: '無効なコンテンツです', category: ErrorCategory.VALIDATION },
  VALIDATION_CONTENT_TOO_LONG: { code: 4005, message: 'コンテンツが長すぎます', category: ErrorCategory.VALIDATION },
  VALIDATION_INVALID_NIP05: { code: 4006, message: '無効なNIP-05識別子です', category: ErrorCategory.VALIDATION },
  VALIDATION_INVALID_LIGHTNING: { code: 4007, message: '無効なLightningアドレスです', category: ErrorCategory.VALIDATION },
  VALIDATION_INVALID_AMOUNT: { code: 4008, message: '無効な金額です', category: ErrorCategory.VALIDATION },

  // Relay errors (5xxx)
  RELAY_CONNECTION_FAILED: { code: 5001, message: 'リレーへの接続に失敗しました', category: ErrorCategory.RELAY },
  RELAY_PUBLISH_FAILED: { code: 5002, message: 'イベントの送信に失敗しました', category: ErrorCategory.RELAY },
  RELAY_SUBSCRIPTION_FAILED: { code: 5003, message: '購読の開始に失敗しました', category: ErrorCategory.RELAY },
  RELAY_CLOSED: { code: 5004, message: 'リレー接続が閉じられました', category: ErrorCategory.RELAY },
  RELAY_PAYMENT_REQUIRED: { code: 5005, message: 'このリレーには支払いが必要です', category: ErrorCategory.RELAY },
  RELAY_RATE_LIMITED: { code: 5006, message: 'レート制限に達しました', category: ErrorCategory.RELAY },
  RELAY_NOT_AVAILABLE: { code: 5007, message: 'リレーが利用できません', category: ErrorCategory.RELAY },

  // Event errors (6xxx)
  EVENT_NOT_FOUND: { code: 6001, message: 'イベントが見つかりません', category: ErrorCategory.EVENT },
  EVENT_INVALID_SIGNATURE: { code: 6002, message: '無効な署名です', category: ErrorCategory.EVENT },
  EVENT_CREATION_FAILED: { code: 6003, message: 'イベントの作成に失敗しました', category: ErrorCategory.EVENT },
  EVENT_DELETE_FAILED: { code: 6004, message: 'イベントの削除に失敗しました', category: ErrorCategory.EVENT },
  EVENT_PROTECTED: { code: 6005, message: '保護されたイベントです', category: ErrorCategory.EVENT },

  // Storage errors (7xxx)
  STORAGE_READ_FAILED: { code: 7001, message: 'データの読み込みに失敗しました', category: ErrorCategory.STORAGE },
  STORAGE_WRITE_FAILED: { code: 7002, message: 'データの保存に失敗しました', category: ErrorCategory.STORAGE },
  STORAGE_QUOTA_EXCEEDED: { code: 7003, message: 'ストレージ容量が不足しています', category: ErrorCategory.STORAGE },

  // Unknown errors (9xxx)
  UNKNOWN: { code: 9999, message: '予期しないエラーが発生しました', category: ErrorCategory.UNKNOWN }
}

// ============================================
// Base Error Class
// ============================================

/**
 * Base error class for Nostr client errors
 * @extends Error
 */
export class NostrError extends Error {
  /**
   * @param {Object} errorCode - Error code object from ErrorCode
   * @param {Object} [options] - Additional options
   * @param {string} [options.details] - Additional error details
   * @param {Error} [options.cause] - Original error
   * @param {Object} [options.context] - Additional context
   */
  constructor(errorCode, options = {}) {
    const { details, cause, context } = options

    // Build message
    const message = details
      ? `${errorCode.message}: ${details}`
      : errorCode.message

    super(message)

    this.name = 'NostrError'
    this.code = errorCode.code
    this.category = errorCode.category
    this.details = details || null
    this.context = context || null
    this.timestamp = new Date().toISOString()

    // Preserve original error
    if (cause) {
      this.cause = cause
    }

    // Capture stack trace
    if (Error.captureStackTrace) {
      Error.captureStackTrace(this, NostrError)
    }
  }

  /**
   * Check if error is of specific category
   * @param {string} category - Error category
   * @returns {boolean}
   */
  isCategory(category) {
    return this.category === category
  }

  /**
   * Check if error is retryable
   * @returns {boolean}
   */
  isRetryable() {
    // Network and relay errors are generally retryable
    return [
      ErrorCategory.NETWORK,
      ErrorCategory.RELAY
    ].includes(this.category) && ![
      ErrorCode.RELAY_PAYMENT_REQUIRED.code,
      ErrorCode.RELAY_NOT_AVAILABLE.code
    ].includes(this.code)
  }

  /**
   * Get user-friendly error message
   * @returns {string}
   */
  getUserMessage() {
    return this.message
  }

  /**
   * Serialize error for logging
   * @returns {Object}
   */
  toJSON() {
    return {
      name: this.name,
      code: this.code,
      category: this.category,
      message: this.message,
      details: this.details,
      context: this.context,
      timestamp: this.timestamp,
      stack: this.stack
    }
  }
}

// ============================================
// Specific Error Classes
// ============================================

/**
 * Network error
 */
export class NetworkError extends NostrError {
  constructor(errorCode, options) {
    super(errorCode, options)
    this.name = 'NetworkError'
  }
}

/**
 * Authentication error
 */
export class AuthError extends NostrError {
  constructor(errorCode, options) {
    super(errorCode, options)
    this.name = 'AuthError'
  }
}

/**
 * Encryption error
 */
export class EncryptionError extends NostrError {
  constructor(errorCode, options) {
    super(errorCode, options)
    this.name = 'EncryptionError'
  }
}

/**
 * Validation error
 */
export class ValidationError extends NostrError {
  constructor(errorCode, options) {
    super(errorCode, options)
    this.name = 'ValidationError'
  }
}

/**
 * Relay error
 */
export class RelayError extends NostrError {
  /**
   * @param {Object} errorCode - Error code
   * @param {Object} [options] - Options
   * @param {string} [options.relayUrl] - Relay URL that caused the error
   */
  constructor(errorCode, options = {}) {
    super(errorCode, options)
    this.name = 'RelayError'
    this.relayUrl = options.relayUrl || null
  }
}

/**
 * Event error
 */
export class EventError extends NostrError {
  /**
   * @param {Object} errorCode - Error code
   * @param {Object} [options] - Options
   * @param {string} [options.eventId] - Event ID that caused the error
   */
  constructor(errorCode, options = {}) {
    super(errorCode, options)
    this.name = 'EventError'
    this.eventId = options.eventId || null
  }
}

/**
 * Storage error
 */
export class StorageError extends NostrError {
  constructor(errorCode, options) {
    super(errorCode, options)
    this.name = 'StorageError'
  }
}

// ============================================
// Error Factory
// ============================================

/**
 * Create appropriate error based on error code category
 * @param {Object} errorCode - Error code object
 * @param {Object} [options] - Error options
 * @returns {NostrError} Appropriate error instance
 */
export function createError(errorCode, options = {}) {
  switch (errorCode.category) {
    case ErrorCategory.NETWORK:
      return new NetworkError(errorCode, options)
    case ErrorCategory.AUTH:
      return new AuthError(errorCode, options)
    case ErrorCategory.ENCRYPTION:
      return new EncryptionError(errorCode, options)
    case ErrorCategory.VALIDATION:
      return new ValidationError(errorCode, options)
    case ErrorCategory.RELAY:
      return new RelayError(errorCode, options)
    case ErrorCategory.EVENT:
      return new EventError(errorCode, options)
    case ErrorCategory.STORAGE:
      return new StorageError(errorCode, options)
    default:
      return new NostrError(errorCode, options)
  }
}

/**
 * Wrap unknown error as NostrError
 * @param {Error} error - Original error
 * @param {Object} [context] - Additional context
 * @returns {NostrError}
 */
export function wrapError(error, context = {}) {
  if (error instanceof NostrError) {
    // Add context and return
    if (context) {
      error.context = { ...error.context, ...context }
    }
    return error
  }

  return new NostrError(ErrorCode.UNKNOWN, {
    details: error.message,
    cause: error,
    context
  })
}

// ============================================
// Error Handler Utility
// ============================================

/**
 * Default error handler
 * @param {Error} error - Error to handle
 * @param {Object} [options] - Handler options
 * @param {boolean} [options.silent=false] - Don't log to console
 * @param {Function} [options.onError] - Custom error callback
 * @returns {NostrError}
 */
export function handleError(error, options = {}) {
  const { silent = false, onError } = options

  const nostrError = wrapError(error)

  if (!silent) {
    console.error(`[${nostrError.name}]`, nostrError.toJSON())
  }

  if (onError) {
    onError(nostrError)
  }

  return nostrError
}

/**
 * Create an async error handler wrapper
 * @param {Function} fn - Async function to wrap
 * @param {Object} [options] - Handler options
 * @returns {Function} Wrapped function
 */
export function withErrorHandler(fn, options = {}) {
  return async (...args) => {
    try {
      return await fn(...args)
    } catch (error) {
      throw handleError(error, options)
    }
  }
}

// ============================================
// Result Type (for error-first returns)
// ============================================

/**
 * @template T
 * @typedef {Object} Result
 * @property {boolean} success - Whether operation succeeded
 * @property {T} [data] - Result data on success
 * @property {NostrError} [error] - Error on failure
 */

/**
 * Create success result
 * @template T
 * @param {T} data - Result data
 * @returns {Result<T>}
 */
export function success(data) {
  return { success: true, data }
}

/**
 * Create failure result
 * @param {NostrError|Error} error - Error
 * @returns {Result<never>}
 */
export function failure(error) {
  return {
    success: false,
    error: error instanceof NostrError ? error : wrapError(error)
  }
}

/**
 * Execute function and return Result
 * @template T
 * @param {Function} fn - Function to execute
 * @returns {Promise<Result<T>>}
 */
export async function tryAsync(fn) {
  try {
    const data = await fn()
    return success(data)
  } catch (error) {
    return failure(error)
  }
}

// ============================================
// Export
// ============================================

export default {
  ErrorCategory,
  ErrorCode,
  NostrError,
  NetworkError,
  AuthError,
  EncryptionError,
  ValidationError,
  RelayError,
  EventError,
  StorageError,
  createError,
  wrapError,
  handleError,
  withErrorHandler,
  success,
  failure,
  tryAsync
}
