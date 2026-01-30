/**
 * Store Persistence
 *
 * Custom persistence layer that integrates with the platform storage adapter
 *
 * @module core/store/persist
 */

import type { StateStorage } from 'zustand/middleware'
import type { StorageAdapter } from '@/src/adapters/storage'
import type { StoreState } from './types'
import { PERSISTED_KEYS, CACHE_KEYS } from './types'

/**
 * Store name for the main persisted state
 */
export const STORE_NAME = 'nurunuru-store'

/**
 * Store name for the cache (separate storage)
 */
export const CACHE_STORE_NAME = 'nurunuru-cache'

/**
 * Create a Zustand-compatible storage that uses our StorageAdapter
 *
 * This bridges Zustand's persist middleware with our platform-agnostic storage
 */
export function createPlatformStorage(
  getStorageAdapter: () => StorageAdapter | null
): StateStorage {
  return {
    getItem: async (name: string): Promise<string | null> => {
      const storage = getStorageAdapter()
      if (!storage) {
        console.warn('[Store] Storage adapter not available')
        return null
      }
      try {
        return await storage.getItem(name)
      } catch (error) {
        console.error('[Store] Failed to get item:', name, error)
        return null
      }
    },

    setItem: async (name: string, value: string): Promise<void> => {
      const storage = getStorageAdapter()
      if (!storage) {
        console.warn('[Store] Storage adapter not available')
        return
      }
      try {
        await storage.setItem(name, value)
      } catch (error) {
        console.error('[Store] Failed to set item:', name, error)
      }
    },

    removeItem: async (name: string): Promise<void> => {
      const storage = getStorageAdapter()
      if (!storage) {
        console.warn('[Store] Storage adapter not available')
        return
      }
      try {
        await storage.removeItem(name)
      } catch (error) {
        console.error('[Store] Failed to remove item:', name, error)
      }
    },
  }
}

/**
 * Filter state to only include persisted keys
 */
export function partializeState(state: StoreState): Partial<StoreState> {
  const persisted: Partial<StoreState> = {}

  for (const key of PERSISTED_KEYS) {
    if (key in state) {
      ;(persisted as Record<string, unknown>)[key] = state[key]
    }
  }

  return persisted
}

/**
 * Filter state to only include cache keys
 */
export function partializeCacheState(state: StoreState): Partial<StoreState> {
  const cache: Partial<StoreState> = {}

  for (const key of CACHE_KEYS) {
    if (key in state) {
      ;(cache as Record<string, unknown>)[key] = state[key]
    }
  }

  return cache
}

/**
 * Merge persisted state with current state
 * Handles migration and default values
 */
export function mergeState(
  currentState: StoreState,
  persistedState: Partial<StoreState> | undefined
): StoreState {
  if (!persistedState) {
    return currentState
  }

  // Deep merge with type safety
  return {
    ...currentState,
    // Auth state
    pubkey: persistedState.pubkey ?? currentState.pubkey,
    loginMethod: persistedState.loginMethod ?? currentState.loginMethod,
    isLoggedIn: persistedState.pubkey ? true : currentState.isLoggedIn,
    // Settings state
    defaultZapAmount:
      persistedState.defaultZapAmount ?? currentState.defaultZapAmount,
    lowBandwidthMode:
      persistedState.lowBandwidthMode ?? currentState.lowBandwidthMode,
    autoSign: persistedState.autoSign ?? currentState.autoSign,
    userGeohash: persistedState.userGeohash ?? currentState.userGeohash,
    customRelays: persistedState.customRelays ?? currentState.customRelays,
    mutedPubkeys: persistedState.mutedPubkeys ?? currentState.mutedPubkeys,
    mutedWords: persistedState.mutedWords ?? currentState.mutedWords,
    // Cache state (kept from current)
    profiles: currentState.profiles,
    followLists: currentState.followLists,
  }
}

/**
 * Version number for state migration
 */
export const STORE_VERSION = 1

/**
 * Migration function for store version updates
 */
export function migrateStore(
  persistedState: unknown,
  version: number
): Partial<StoreState> {
  // Version 0 -> 1: Initial version, no migration needed
  if (version === 0) {
    // Future migrations can be added here
    return persistedState as Partial<StoreState>
  }

  return persistedState as Partial<StoreState>
}
