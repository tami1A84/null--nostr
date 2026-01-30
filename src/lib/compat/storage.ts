/**
 * Storage Compatibility Layer
 *
 * Provides a synchronous-like API that bridges old localStorage code
 * with the new async StorageAdapter system.
 *
 * Usage (gradual migration):
 * Before: localStorage.getItem('key')
 * After:  await storage.getItem('key')
 *
 * @module lib/compat/storage
 */

import { getStorage as getPlatformStorage, isInitialized } from '@/src/platform'
import type { StorageAdapter } from '@/src/adapters/storage'

/**
 * Legacy storage interface that matches localStorage API but is async
 */
export interface CompatStorage {
  getItem(key: string): Promise<string | null>
  setItem(key: string, value: string): Promise<void>
  removeItem(key: string): Promise<void>
  clear(): Promise<void>
  keys(): Promise<string[]>

  // JSON helpers
  getJSON<T>(key: string): Promise<T | null>
  setJSON<T>(key: string, value: T): Promise<void>
}

/**
 * In-memory fallback for SSR
 */
const memoryFallback = new Map<string, string>()

/**
 * Get the storage adapter, with fallback for non-initialized state
 */
function getStorageAdapter(): StorageAdapter | null {
  if (typeof window === 'undefined') {
    return null
  }

  if (!isInitialized()) {
    console.warn(
      '[CompatStorage] Platform not initialized, using localStorage directly'
    )
    return null
  }

  return getPlatformStorage()
}

/**
 * Fallback to localStorage when adapter not available
 */
function localStorageFallback() {
  if (typeof window !== 'undefined' && window.localStorage) {
    return window.localStorage
  }
  return null
}

/**
 * Compatible storage instance
 */
export const storage: CompatStorage = {
  async getItem(key: string): Promise<string | null> {
    const adapter = getStorageAdapter()
    if (adapter) {
      return adapter.getItem(key)
    }

    // Fallback
    const ls = localStorageFallback()
    if (ls) {
      return ls.getItem(key)
    }

    return memoryFallback.get(key) ?? null
  },

  async setItem(key: string, value: string): Promise<void> {
    const adapter = getStorageAdapter()
    if (adapter) {
      return adapter.setItem(key, value)
    }

    // Fallback
    const ls = localStorageFallback()
    if (ls) {
      ls.setItem(key, value)
      return
    }

    memoryFallback.set(key, value)
  },

  async removeItem(key: string): Promise<void> {
    const adapter = getStorageAdapter()
    if (adapter) {
      return adapter.removeItem(key)
    }

    // Fallback
    const ls = localStorageFallback()
    if (ls) {
      ls.removeItem(key)
      return
    }

    memoryFallback.delete(key)
  },

  async clear(): Promise<void> {
    const adapter = getStorageAdapter()
    if (adapter) {
      return adapter.clear()
    }

    // Fallback
    const ls = localStorageFallback()
    if (ls) {
      ls.clear()
      return
    }

    memoryFallback.clear()
  },

  async keys(): Promise<string[]> {
    const adapter = getStorageAdapter()
    if (adapter) {
      return adapter.keys()
    }

    // Fallback
    const ls = localStorageFallback()
    if (ls) {
      return Object.keys(ls)
    }

    return Array.from(memoryFallback.keys())
  },

  async getJSON<T>(key: string): Promise<T | null> {
    const value = await this.getItem(key)
    if (value === null) return null
    try {
      return JSON.parse(value) as T
    } catch {
      console.warn(`[CompatStorage] Failed to parse JSON for key: ${key}`)
      return null
    }
  },

  async setJSON<T>(key: string, value: T): Promise<void> {
    await this.setItem(key, JSON.stringify(value))
  },
}

/**
 * Migration helper: Copy old localStorage keys to new storage
 */
export async function migrateFromLocalStorage(
  keys: string[]
): Promise<{ migrated: string[]; failed: string[] }> {
  const migrated: string[] = []
  const failed: string[] = []

  if (typeof window === 'undefined' || !window.localStorage) {
    return { migrated, failed }
  }

  const adapter = getStorageAdapter()
  if (!adapter) {
    return { migrated, failed }
  }

  for (const key of keys) {
    try {
      const value = window.localStorage.getItem(key)
      if (value !== null) {
        await adapter.setItem(key, value)
        migrated.push(key)
      }
    } catch (error) {
      console.error(`[Migration] Failed to migrate key: ${key}`, error)
      failed.push(key)
    }
  }

  return { migrated, failed }
}

/**
 * Legacy key mappings for backwards compatibility
 */
export const LEGACY_KEYS = {
  // Auth
  USER_PUBKEY: 'user_pubkey',
  LOGIN_METHOD: 'nurunuru_login_method',

  // Settings
  DEFAULT_ZAP_AMOUNT: 'defaultZapAmount',
  AUTO_SIGN: 'nurunuru_auto_sign',
  USER_GEOHASH: 'user_geohash',

  // Signer-specific
  NOSSKEY: 'nurunuru_nosskey',
  BUNKER_SESSION: 'nurunuru_bunker_session',
} as const
