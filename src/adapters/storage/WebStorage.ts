/**
 * Web Storage Adapter
 *
 * Implementation of StorageAdapter using browser localStorage.
 * Handles SSR safety by checking for window availability.
 *
 * @module adapters/storage
 */

import type { StorageAdapter, TypedStorageAdapter, StorableValue } from './StorageAdapter'

/**
 * Check if we're in a browser environment
 */
function isBrowser(): boolean {
  return typeof window !== 'undefined' && typeof localStorage !== 'undefined'
}

/**
 * Web Storage implementation using localStorage
 */
export class WebStorage implements TypedStorageAdapter {
  async getItem(key: string): Promise<string | null> {
    if (!isBrowser()) return null
    try {
      return localStorage.getItem(key)
    } catch (error) {
      console.warn('[WebStorage] Failed to get item:', key, error)
      return null
    }
  }

  async setItem(key: string, value: string): Promise<void> {
    if (!isBrowser()) return
    try {
      localStorage.setItem(key, value)
    } catch (error) {
      console.warn('[WebStorage] Failed to set item:', key, error)
      // Handle quota exceeded error
      if (error instanceof DOMException && error.name === 'QuotaExceededError') {
        throw new Error('Storage quota exceeded')
      }
    }
  }

  async removeItem(key: string): Promise<void> {
    if (!isBrowser()) return
    try {
      localStorage.removeItem(key)
    } catch (error) {
      console.warn('[WebStorage] Failed to remove item:', key, error)
    }
  }

  async clear(): Promise<void> {
    if (!isBrowser()) return
    try {
      localStorage.clear()
    } catch (error) {
      console.warn('[WebStorage] Failed to clear storage:', error)
    }
  }

  async keys(): Promise<string[]> {
    if (!isBrowser()) return []
    try {
      return Object.keys(localStorage)
    } catch (error) {
      console.warn('[WebStorage] Failed to get keys:', error)
      return []
    }
  }

  async has(key: string): Promise<boolean> {
    if (!isBrowser()) return false
    try {
      return localStorage.getItem(key) !== null
    } catch (error) {
      console.warn('[WebStorage] Failed to check key:', key, error)
      return false
    }
  }

  async getMultiple(keys: string[]): Promise<Map<string, string | null>> {
    const result = new Map<string, string | null>()
    if (!isBrowser()) {
      keys.forEach(key => result.set(key, null))
      return result
    }

    for (const key of keys) {
      try {
        result.set(key, localStorage.getItem(key))
      } catch (error) {
        console.warn('[WebStorage] Failed to get item:', key, error)
        result.set(key, null)
      }
    }
    return result
  }

  async setMultiple(entries: [string, string][]): Promise<void> {
    if (!isBrowser()) return

    for (const [key, value] of entries) {
      try {
        localStorage.setItem(key, value)
      } catch (error) {
        console.warn('[WebStorage] Failed to set item:', key, error)
        if (error instanceof DOMException && error.name === 'QuotaExceededError') {
          throw new Error('Storage quota exceeded')
        }
      }
    }
  }

  async getJSON<T extends StorableValue>(key: string): Promise<T | null> {
    const value = await this.getItem(key)
    if (value === null) return null

    try {
      return JSON.parse(value) as T
    } catch (error) {
      console.warn('[WebStorage] Failed to parse JSON for key:', key, error)
      return null
    }
  }

  async setJSON<T extends StorableValue>(key: string, value: T): Promise<void> {
    try {
      const stringValue = JSON.stringify(value)
      await this.setItem(key, stringValue)
    } catch (error) {
      console.warn('[WebStorage] Failed to stringify JSON for key:', key, error)
    }
  }
}

/**
 * Singleton instance for convenience
 */
let webStorageInstance: WebStorage | null = null

/**
 * Get the singleton WebStorage instance
 */
export function getWebStorage(): WebStorage {
  if (!webStorageInstance) {
    webStorageInstance = new WebStorage()
  }
  return webStorageInstance
}
