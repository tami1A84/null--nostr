/**
 * Memory Storage Adapter
 *
 * In-memory implementation of StorageAdapter for:
 * - Testing/mocking
 * - SSR fallback
 * - Temporary storage needs
 *
 * @module adapters/storage
 */

import type { StorageAdapter, TypedStorageAdapter, StorableValue } from './StorageAdapter'

/**
 * In-memory storage implementation
 */
export class MemoryStorage implements TypedStorageAdapter {
  private store: Map<string, string>

  constructor(initialData?: Map<string, string> | Record<string, string>) {
    if (initialData instanceof Map) {
      this.store = new Map(initialData)
    } else if (initialData) {
      this.store = new Map(Object.entries(initialData))
    } else {
      this.store = new Map()
    }
  }

  async getItem(key: string): Promise<string | null> {
    return this.store.get(key) ?? null
  }

  async setItem(key: string, value: string): Promise<void> {
    this.store.set(key, value)
  }

  async removeItem(key: string): Promise<void> {
    this.store.delete(key)
  }

  async clear(): Promise<void> {
    this.store.clear()
  }

  async keys(): Promise<string[]> {
    return Array.from(this.store.keys())
  }

  async has(key: string): Promise<boolean> {
    return this.store.has(key)
  }

  async getMultiple(keys: string[]): Promise<Map<string, string | null>> {
    const result = new Map<string, string | null>()
    for (const key of keys) {
      result.set(key, this.store.get(key) ?? null)
    }
    return result
  }

  async setMultiple(entries: [string, string][]): Promise<void> {
    for (const [key, value] of entries) {
      this.store.set(key, value)
    }
  }

  async getJSON<T extends StorableValue>(key: string): Promise<T | null> {
    const value = await this.getItem(key)
    if (value === null) return null

    try {
      return JSON.parse(value) as T
    } catch {
      return null
    }
  }

  async setJSON<T extends StorableValue>(key: string, value: T): Promise<void> {
    const stringValue = JSON.stringify(value)
    await this.setItem(key, stringValue)
  }

  /**
   * Get the current size of the storage
   */
  get size(): number {
    return this.store.size
  }

  /**
   * Export all data as a plain object (useful for debugging/testing)
   */
  toObject(): Record<string, string> {
    const obj: Record<string, string> = {}
    this.store.forEach((value, key) => {
      obj[key] = value
    })
    return obj
  }

  /**
   * Import data from an object (useful for testing)
   */
  fromObject(data: Record<string, string>): void {
    this.store.clear()
    Object.entries(data).forEach(([key, value]) => {
      this.store.set(key, value)
    })
  }
}

/**
 * Create a new MemoryStorage instance
 * Unlike other storage adapters, MemoryStorage is typically not a singleton
 * because tests often need isolated instances
 */
export function createMemoryStorage(initialData?: Record<string, string>): MemoryStorage {
  return new MemoryStorage(initialData)
}
