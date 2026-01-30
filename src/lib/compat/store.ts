/**
 * Store Compatibility Layer
 *
 * Bridges the new Zustand store with legacy localStorage-based code.
 * This allows gradual migration without breaking existing functionality.
 *
 * @module lib/compat/store
 */

import {
  useStore,
  hydrateStore,
  setStorageAdapterGetter,
  getStoreState,
} from '@/src/core/store'
import type { LoginMethod, Profile } from '@/src/core/store'
import { storage, LEGACY_KEYS } from './storage'
import { getStorage, isInitialized } from '@/src/platform'

/**
 * Initialize the store with platform storage
 * Should be called after platform initialization
 */
export async function initializeStore(): Promise<void> {
  // Set up storage adapter getter for the store
  setStorageAdapterGetter(() => {
    if (!isInitialized()) return null
    return getStorage()
  })

  // Hydrate from storage
  await hydrateStore()

  // Migrate from legacy localStorage if needed
  await migrateFromLegacyStorage()
}

/**
 * Migrate data from legacy localStorage keys to Zustand store
 */
async function migrateFromLegacyStorage(): Promise<void> {
  const state = getStoreState()

  // Only migrate if store is empty (first run after migration)
  if (state.pubkey) {
    return
  }

  // Try to get legacy values
  const legacyPubkey = await storage.getItem(LEGACY_KEYS.USER_PUBKEY)
  const legacyMethod = await storage.getItem(LEGACY_KEYS.LOGIN_METHOD) as LoginMethod | null
  const legacyZapAmount = await storage.getItem(LEGACY_KEYS.DEFAULT_ZAP_AMOUNT)
  const legacyAutoSign = await storage.getItem(LEGACY_KEYS.AUTO_SIGN)
  const legacyGeohash = await storage.getItem(LEGACY_KEYS.USER_GEOHASH)

  if (legacyPubkey && legacyMethod) {
    console.log('[Migration] Migrating auth state from localStorage')
    useStore.getState().login(legacyPubkey, legacyMethod)
  }

  if (legacyZapAmount) {
    const amount = parseInt(legacyZapAmount, 10)
    if (!isNaN(amount)) {
      useStore.getState().setDefaultZapAmount(amount)
    }
  }

  if (legacyAutoSign === 'true') {
    useStore.getState().setAutoSign(true)
  }

  if (legacyGeohash) {
    useStore.getState().setUserGeohash(legacyGeohash)
  }
}

/**
 * Sync store changes back to legacy localStorage
 * This maintains backwards compatibility during migration period
 */
export function syncToLegacyStorage(): () => void {
  return useStore.subscribe(
    (state) => ({
      pubkey: state.pubkey,
      loginMethod: state.loginMethod,
      defaultZapAmount: state.defaultZapAmount,
      autoSign: state.autoSign,
      userGeohash: state.userGeohash,
    }),
    async (current, prev) => {
      // Sync auth changes
      if (current.pubkey !== prev.pubkey) {
        if (current.pubkey) {
          await storage.setItem(LEGACY_KEYS.USER_PUBKEY, current.pubkey)
        } else {
          await storage.removeItem(LEGACY_KEYS.USER_PUBKEY)
        }
      }

      if (current.loginMethod !== prev.loginMethod) {
        if (current.loginMethod) {
          await storage.setItem(LEGACY_KEYS.LOGIN_METHOD, current.loginMethod)
        } else {
          await storage.removeItem(LEGACY_KEYS.LOGIN_METHOD)
        }
      }

      // Sync settings changes
      if (current.defaultZapAmount !== prev.defaultZapAmount) {
        await storage.setItem(
          LEGACY_KEYS.DEFAULT_ZAP_AMOUNT,
          String(current.defaultZapAmount)
        )
      }

      if (current.autoSign !== prev.autoSign) {
        await storage.setItem(
          LEGACY_KEYS.AUTO_SIGN,
          current.autoSign ? 'true' : 'false'
        )
      }

      if (current.userGeohash !== prev.userGeohash) {
        if (current.userGeohash) {
          await storage.setItem(LEGACY_KEYS.USER_GEOHASH, current.userGeohash)
        } else {
          await storage.removeItem(LEGACY_KEYS.USER_GEOHASH)
        }
      }
    }
  )
}

// ====================================
// Legacy API Wrappers
// ====================================

/**
 * Get current user's pubkey (legacy API)
 */
export function getUserPubkey(): string | null {
  return getStoreState().pubkey
}

/**
 * Get login method (legacy API)
 */
export function getLoginMethod(): LoginMethod | null {
  return getStoreState().loginMethod
}

/**
 * Check if user is logged in (legacy API)
 */
export function isLoggedIn(): boolean {
  return getStoreState().isLoggedIn
}

/**
 * Login (legacy API)
 */
export function login(pubkey: string, method: LoginMethod): void {
  useStore.getState().login(pubkey, method)
}

/**
 * Logout (legacy API)
 */
export function logout(): void {
  useStore.getState().logout()
}

/**
 * Get default zap amount (legacy API)
 */
export function getDefaultZapAmount(): number {
  return getStoreState().defaultZapAmount
}

/**
 * Set default zap amount (legacy API)
 */
export function setDefaultZapAmount(amount: number): void {
  useStore.getState().setDefaultZapAmount(amount)
}

/**
 * Get cached profile (legacy API)
 */
export function getCachedProfile(pubkey: string): Profile | undefined {
  return useStore.getState().getProfile(pubkey)
}

/**
 * Set cached profile (legacy API)
 */
export function setCachedProfile(profile: Profile): void {
  useStore.getState().setProfile(profile)
}
