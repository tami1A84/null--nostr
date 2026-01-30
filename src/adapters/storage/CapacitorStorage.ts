/**
 * Capacitor Storage Adapter
 *
 * Implementation of StorageAdapter using Capacitor Preferences plugin.
 * For use in Android/iOS apps built with Capacitor.
 *
 * Note: Requires @capacitor/preferences package to be installed.
 *
 * @module adapters/storage
 */

import type { StorageAdapter, TypedStorageAdapter, StorableValue } from './StorageAdapter'

// Dynamic import type for Capacitor Preferences
interface CapacitorPreferences {
  get(options: { key: string }): Promise<{ value: string | null }>
  set(options: { key: string; value: string }): Promise<void>
  remove(options: { key: string }): Promise<void>
  clear(): Promise<void>
  keys(): Promise<{ keys: string[] }>
}

/**
 * Capacitor Storage implementation using Preferences plugin
 */
export class CapacitorStorage implements TypedStorageAdapter {
  private preferences: CapacitorPreferences | null = null
  private initPromise: Promise<void> | null = null

  /**
   * Initialize Capacitor Preferences dynamically
   * This allows the module to be imported even in non-Capacitor environments
   */
  private async init(): Promise<void> {
    if (this.preferences) return
    if (this.initPromise) return this.initPromise

    this.initPromise = (async () => {
      try {
        // Dynamic import to avoid errors in non-Capacitor environments
        const { Preferences } = await import('@capacitor/preferences')
        this.preferences = Preferences
      } catch (error) {
        console.warn('[CapacitorStorage] Preferences plugin not available:', error)
        throw new Error('Capacitor Preferences plugin not available')
      }
    })()

    return this.initPromise
  }

  async getItem(key: string): Promise<string | null> {
    try {
      await this.init()
      if (!this.preferences) return null

      const { value } = await this.preferences.get({ key })
      return value
    } catch (error) {
      console.warn('[CapacitorStorage] Failed to get item:', key, error)
      return null
    }
  }

  async setItem(key: string, value: string): Promise<void> {
    try {
      await this.init()
      if (!this.preferences) return

      await this.preferences.set({ key, value })
    } catch (error) {
      console.warn('[CapacitorStorage] Failed to set item:', key, error)
    }
  }

  async removeItem(key: string): Promise<void> {
    try {
      await this.init()
      if (!this.preferences) return

      await this.preferences.remove({ key })
    } catch (error) {
      console.warn('[CapacitorStorage] Failed to remove item:', key, error)
    }
  }

  async clear(): Promise<void> {
    try {
      await this.init()
      if (!this.preferences) return

      await this.preferences.clear()
    } catch (error) {
      console.warn('[CapacitorStorage] Failed to clear storage:', error)
    }
  }

  async keys(): Promise<string[]> {
    try {
      await this.init()
      if (!this.preferences) return []

      const { keys } = await this.preferences.keys()
      return keys
    } catch (error) {
      console.warn('[CapacitorStorage] Failed to get keys:', error)
      return []
    }
  }

  async has(key: string): Promise<boolean> {
    const value = await this.getItem(key)
    return value !== null
  }

  async getMultiple(keys: string[]): Promise<Map<string, string | null>> {
    const result = new Map<string, string | null>()

    // Capacitor Preferences doesn't have a batch get method,
    // so we fetch each key individually
    const promises = keys.map(async key => {
      const value = await this.getItem(key)
      result.set(key, value)
    })

    await Promise.all(promises)
    return result
  }

  async setMultiple(entries: [string, string][]): Promise<void> {
    // Capacitor Preferences doesn't have a batch set method,
    // so we set each key individually
    const promises = entries.map(([key, value]) => this.setItem(key, value))
    await Promise.all(promises)
  }

  async getJSON<T extends StorableValue>(key: string): Promise<T | null> {
    const value = await this.getItem(key)
    if (value === null) return null

    try {
      return JSON.parse(value) as T
    } catch (error) {
      console.warn('[CapacitorStorage] Failed to parse JSON for key:', key, error)
      return null
    }
  }

  async setJSON<T extends StorableValue>(key: string, value: T): Promise<void> {
    try {
      const stringValue = JSON.stringify(value)
      await this.setItem(key, stringValue)
    } catch (error) {
      console.warn('[CapacitorStorage] Failed to stringify JSON for key:', key, error)
    }
  }
}

/**
 * Singleton instance for convenience
 */
let capacitorStorageInstance: CapacitorStorage | null = null

/**
 * Get the singleton CapacitorStorage instance
 */
export function getCapacitorStorage(): CapacitorStorage {
  if (!capacitorStorageInstance) {
    capacitorStorageInstance = new CapacitorStorage()
  }
  return capacitorStorageInstance
}
