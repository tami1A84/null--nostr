/**
 * Storage Compatibility Layer
 *
 * Provides a synchronous-looking API that wraps the async StorageAdapter.
 * This allows gradual migration from direct localStorage usage.
 *
 * Usage:
 * Before: localStorage.getItem('key')
 * After:  await storage.getItem('key')
 *
 * @module lib/compat/storage
 */

import { getStorage, ensureInitialized, isInitialized } from '@/src/platform'
import { WebStorage } from '@/src/adapters/storage'

// Fallback storage for SSR or before initialization
const fallbackStorage = new WebStorage()

/**
 * Get the current storage adapter, with fallback
 */
function getCurrentStorage() {
  if (isInitialized()) {
    return getStorage()
  }
  return fallbackStorage
}

/**
 * Storage compatibility layer
 * Provides async methods that match the StorageAdapter interface
 */
export const storage = {
  /**
   * Get a value from storage
   */
  async getItem(key: string): Promise<string | null> {
    return getCurrentStorage().getItem(key)
  },

  /**
   * Set a value in storage
   */
  async setItem(key: string, value: string): Promise<void> {
    return getCurrentStorage().setItem(key, value)
  },

  /**
   * Remove a value from storage
   */
  async removeItem(key: string): Promise<void> {
    return getCurrentStorage().removeItem(key)
  },

  /**
   * Clear all storage
   */
  async clear(): Promise<void> {
    return getCurrentStorage().clear()
  },

  /**
   * Get all keys
   */
  async keys(): Promise<string[]> {
    return getCurrentStorage().keys()
  },

  /**
   * Check if a key exists
   */
  async has(key: string): Promise<boolean> {
    return getCurrentStorage().has(key)
  },

  /**
   * Get and parse a JSON value
   */
  async getJSON<T>(key: string): Promise<T | null> {
    const value = await getCurrentStorage().getItem(key)
    if (value === null) return null
    try {
      return JSON.parse(value) as T
    } catch {
      return null
    }
  },

  /**
   * Stringify and set a JSON value
   */
  async setJSON<T>(key: string, value: T): Promise<void> {
    const stringValue = JSON.stringify(value)
    return getCurrentStorage().setItem(key, stringValue)
  }
}

/**
 * Synchronous storage helpers for cases where async isn't possible
 * WARNING: Only works in browser after platform initialization
 * Falls back to localStorage directly
 */
export const storageSync = {
  getItem(key: string): string | null {
    if (typeof window === 'undefined') return null
    try {
      return localStorage.getItem(key)
    } catch {
      return null
    }
  },

  setItem(key: string, value: string): void {
    if (typeof window === 'undefined') return
    try {
      localStorage.setItem(key, value)
    } catch {
      // Ignore quota errors
    }
  },

  removeItem(key: string): void {
    if (typeof window === 'undefined') return
    try {
      localStorage.removeItem(key)
    } catch {
      // Ignore errors
    }
  },

  getJSON<T>(key: string): T | null {
    const value = this.getItem(key)
    if (value === null) return null
    try {
      return JSON.parse(value) as T
    } catch {
      return null
    }
  },

  setJSON<T>(key: string, value: T): void {
    try {
      const stringValue = JSON.stringify(value)
      this.setItem(key, stringValue)
    } catch {
      // Ignore stringify errors
    }
  }
}

/**
 * Initialize storage and return when ready
 */
export async function initStorage(): Promise<void> {
  await ensureInitialized()
}

/**
 * Common storage keys used in the app
 */
export const STORAGE_KEYS = {
  USER_PUBKEY: 'user_pubkey',
  LOGIN_METHOD: 'nurunuru_login_method',
  DEFAULT_ZAP_AMOUNT: 'defaultZapAmount',
  AUTO_SIGN: 'nurunuru_auto_sign',
  USER_GEOHASH: 'user_geohash',
  PRIVATE_KEY: 'nostr_private_key_hex',
  BUNKER_SECRET: 'nip46_local_secret_key',
  LOW_BANDWIDTH: 'nurunuru_low_bandwidth'
} as const
