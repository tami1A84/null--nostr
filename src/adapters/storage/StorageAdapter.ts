/**
 * Storage Adapter Interface
 *
 * Provides a unified interface for storage operations across different platforms.
 * All methods are async to support both synchronous (localStorage) and
 * asynchronous (Capacitor Preferences, Electron store) backends.
 *
 * @module adapters/storage
 */

/**
 * Storage adapter interface for cross-platform storage abstraction
 */
export interface StorageAdapter {
  /**
   * Get a value from storage
   * @param key - The key to retrieve
   * @returns The stored value, or null if not found
   */
  getItem(key: string): Promise<string | null>

  /**
   * Set a value in storage
   * @param key - The key to store under
   * @param value - The value to store
   */
  setItem(key: string, value: string): Promise<void>

  /**
   * Remove a value from storage
   * @param key - The key to remove
   */
  removeItem(key: string): Promise<void>

  /**
   * Clear all values from storage
   */
  clear(): Promise<void>

  /**
   * Get all keys in storage
   * @returns Array of all storage keys
   */
  keys(): Promise<string[]>

  /**
   * Check if a key exists in storage
   * @param key - The key to check
   * @returns True if the key exists
   */
  has(key: string): Promise<boolean>

  /**
   * Get multiple values at once
   * @param keys - Array of keys to retrieve
   * @returns Map of key-value pairs
   */
  getMultiple(keys: string[]): Promise<Map<string, string | null>>

  /**
   * Set multiple values at once
   * @param entries - Array of [key, value] pairs
   */
  setMultiple(entries: [string, string][]): Promise<void>
}

/**
 * Helper type for JSON-serializable values
 */
export type StorableValue = string | number | boolean | null | StorableValue[] | { [key: string]: StorableValue }

/**
 * Extended storage adapter with JSON support
 */
export interface TypedStorageAdapter extends StorageAdapter {
  /**
   * Get and parse a JSON value
   * @param key - The key to retrieve
   * @returns The parsed value, or null if not found
   */
  getJSON<T extends StorableValue>(key: string): Promise<T | null>

  /**
   * Stringify and set a JSON value
   * @param key - The key to store under
   * @param value - The value to store (will be JSON stringified)
   */
  setJSON<T extends StorableValue>(key: string, value: T): Promise<void>
}
