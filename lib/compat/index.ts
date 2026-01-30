/**
 * Compatibility Layer
 *
 * Provides backward-compatible APIs for gradual migration to the new
 * platform abstraction layer.
 *
 * @module lib/compat
 */

// Storage compatibility
export {
  storage,
  storageSync,
  initStorage,
  STORAGE_KEYS
} from './storage'

// Signing compatibility
export {
  hasAnySigner,
  getCurrentSignerType,
  getPublicKey,
  signEvent,
  encrypt,
  decrypt,
  supportsNip44,
  supportsNip04,
  type SigningAdapter,
  type UnsignedEvent,
  type SignerType
} from './signing'
