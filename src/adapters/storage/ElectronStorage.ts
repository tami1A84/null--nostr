/**
 * Electron Storage Adapter
 *
 * Uses Electron's IPC to communicate with the main process for storage.
 * Falls back to localStorage if Electron IPC is not available.
 * Designed to work with electron-store in the main process.
 *
 * @module adapters/storage
 */

import type { StorageAdapter, TypedStorageAdapter, StorableValue } from './StorageAdapter'

// Type for Electron storage API (exposed via preload script)
interface ElectronStorageAPI {
  getItem(key: string): Promise<string | null>
  setItem(key: string, value: string): Promise<void>
  removeItem(key: string): Promise<void>
  clear(): Promise<void>
  keys(): Promise<string[]>
  has(key: string): Promise<boolean>
}

/**
 * Get Electron storage API from window context
 */
function getElectronStorage(): ElectronStorageAPI | null {
  if (typeof window === 'undefined') return null

  const win = window as Window & {
    electronStorage?: ElectronStorageAPI
    electron?: { storage?: ElectronStorageAPI }
  }

  return win.electronStorage ?? win.electron?.storage ?? null
}

/**
 * Electron implementation of StorageAdapter
 *
 * This adapter communicates with the main process via IPC to use
 * electron-store for persistent storage. Falls back to localStorage
 * if the Electron API is not available.
 */
export class ElectronStorage implements StorageAdapter, TypedStorageAdapter {
  private electronApi: ElectronStorageAPI | null
  private fallbackStorage: Storage | null

  constructor() {
    this.electronApi = getElectronStorage()

    // Set up fallback to localStorage
    if (!this.electronApi && typeof window !== 'undefined') {
      this.fallbackStorage = window.localStorage
    } else {
      this.fallbackStorage = null
    }
  }

  /**
   * Check if using Electron API or fallback
   */
  isUsingElectronAPI(): boolean {
    return this.electronApi !== null
  }

  /**
   * Get a value from storage
   */
  async getItem(key: string): Promise<string | null> {
    if (this.electronApi) {
      return this.electronApi.getItem(key)
    }

    if (this.fallbackStorage) {
      return this.fallbackStorage.getItem(key)
    }

    return null
  }

  /**
   * Set a value in storage
   */
  async setItem(key: string, value: string): Promise<void> {
    if (this.electronApi) {
      await this.electronApi.setItem(key, value)
      return
    }

    if (this.fallbackStorage) {
      this.fallbackStorage.setItem(key, value)
    }
  }

  /**
   * Remove a value from storage
   */
  async removeItem(key: string): Promise<void> {
    if (this.electronApi) {
      await this.electronApi.removeItem(key)
      return
    }

    if (this.fallbackStorage) {
      this.fallbackStorage.removeItem(key)
    }
  }

  /**
   * Clear all values from storage
   */
  async clear(): Promise<void> {
    if (this.electronApi) {
      await this.electronApi.clear()
      return
    }

    if (this.fallbackStorage) {
      this.fallbackStorage.clear()
    }
  }

  /**
   * Get all keys in storage
   */
  async keys(): Promise<string[]> {
    if (this.electronApi) {
      return this.electronApi.keys()
    }

    if (this.fallbackStorage) {
      return Object.keys(this.fallbackStorage)
    }

    return []
  }

  /**
   * Check if a key exists in storage
   */
  async has(key: string): Promise<boolean> {
    if (this.electronApi) {
      return this.electronApi.has(key)
    }

    if (this.fallbackStorage) {
      return this.fallbackStorage.getItem(key) !== null
    }

    return false
  }

  /**
   * Get multiple values at once
   */
  async getMultiple(keys: string[]): Promise<Map<string, string | null>> {
    const results = new Map<string, string | null>()

    // Execute in parallel for better performance
    const values = await Promise.all(keys.map(key => this.getItem(key)))

    keys.forEach((key, index) => {
      results.set(key, values[index])
    })

    return results
  }

  /**
   * Set multiple values at once
   */
  async setMultiple(entries: [string, string][]): Promise<void> {
    // Execute in parallel for better performance
    await Promise.all(entries.map(([key, value]) => this.setItem(key, value)))
  }

  /**
   * Get and parse a JSON value
   */
  async getJSON<T extends StorableValue>(key: string): Promise<T | null> {
    const value = await this.getItem(key)
    if (value === null) return null

    try {
      return JSON.parse(value) as T
    } catch {
      console.warn(`[ElectronStorage] Failed to parse JSON for key: ${key}`)
      return null
    }
  }

  /**
   * Stringify and set a JSON value
   */
  async setJSON<T extends StorableValue>(key: string, value: T): Promise<void> {
    try {
      const stringified = JSON.stringify(value)
      await this.setItem(key, stringified)
    } catch (error) {
      console.error(`[ElectronStorage] Failed to stringify JSON for key: ${key}`, error)
      throw error
    }
  }
}
