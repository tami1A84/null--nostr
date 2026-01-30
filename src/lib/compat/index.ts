/**
 * Compatibility Layer
 *
 * Provides backwards-compatible APIs for gradual migration
 * from localStorage to the new platform-agnostic storage system.
 *
 * @module lib/compat
 */

// Storage compatibility
export { storage, migrateFromLocalStorage, LEGACY_KEYS } from './storage'
export type { CompatStorage } from './storage'

// Store compatibility
export {
  initializeStore,
  syncToLegacyStorage,
  getUserPubkey,
  getLoginMethod,
  isLoggedIn,
  login,
  logout,
  getDefaultZapAmount,
  setDefaultZapAmount,
  getCachedProfile,
  setCachedProfile,
} from './store'
