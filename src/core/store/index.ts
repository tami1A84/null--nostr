/**
 * Zustand Store
 *
 * Central state management with cross-platform persistence
 *
 * @module core/store
 */

import { create } from 'zustand'
import { persist, createJSONStorage, subscribeWithSelector } from 'zustand/middleware'
import { immer } from 'zustand/middleware/immer'
import type { Store, StoreState } from './types'
import {
  createAuthSlice,
  createSettingsSlice,
  createCacheSlice,
  initialAuthState,
  initialSettingsState,
  initialCacheState,
} from './slices'
import {
  STORE_NAME,
  STORE_VERSION,
  createPlatformStorage,
  partializeState,
  migrateStore,
} from './persist'

/**
 * Storage adapter getter
 * Lazily gets the storage adapter from platform container
 */
let storageAdapterGetter: (() => import('@/src/adapters/storage').StorageAdapter | null) | null = null

/**
 * Set the storage adapter getter
 * Must be called before using the store with persistence
 */
export function setStorageAdapterGetter(
  getter: () => import('@/src/adapters/storage').StorageAdapter | null
): void {
  storageAdapterGetter = getter
}

/**
 * Initial state combining all slices
 */
const initialState: StoreState = {
  ...initialAuthState,
  ...initialSettingsState,
  ...initialCacheState,
}

/**
 * Create the Zustand store with all middleware
 */
export const useStore = create<Store>()(
  subscribeWithSelector(
    persist(
      immer((...args) => ({
        ...createAuthSlice(...args),
        ...createSettingsSlice(...args),
        ...createCacheSlice(...args),
      })),
      {
        name: STORE_NAME,
        version: STORE_VERSION,
        storage: createJSONStorage(() =>
          createPlatformStorage(() => storageAdapterGetter?.() ?? null)
        ),
        partialize: partializeState,
        merge: (persistedState, currentState) => {
          // Keep all actions from currentState, override with persisted values
          const persisted = persistedState as Partial<StoreState> | undefined
          return {
            ...currentState,
            // Auth state
            pubkey: persisted?.pubkey ?? currentState.pubkey,
            loginMethod: persisted?.loginMethod ?? currentState.loginMethod,
            isLoggedIn: persisted?.pubkey ? true : currentState.isLoggedIn,
            // Settings state
            defaultZapAmount: persisted?.defaultZapAmount ?? currentState.defaultZapAmount,
            lowBandwidthMode: persisted?.lowBandwidthMode ?? currentState.lowBandwidthMode,
            autoSign: persisted?.autoSign ?? currentState.autoSign,
            userGeohash: persisted?.userGeohash ?? currentState.userGeohash,
            customRelays: persisted?.customRelays ?? currentState.customRelays,
            mutedPubkeys: persisted?.mutedPubkeys ?? currentState.mutedPubkeys,
            mutedWords: persisted?.mutedWords ?? currentState.mutedWords,
          }
        },
        migrate: migrateStore,
        skipHydration: true, // We'll hydrate manually after platform init
      }
    )
  )
)

/**
 * Hydrate the store from storage
 * Call this after platform initialization
 */
export async function hydrateStore(): Promise<void> {
  await useStore.persist.rehydrate()
}

/**
 * Check if store has been hydrated
 */
export function isStoreHydrated(): boolean {
  return useStore.persist.hasHydrated()
}

/**
 * Subscribe to hydration completion
 */
export function onStoreHydrated(callback: () => void): () => void {
  return useStore.persist.onFinishHydration(callback)
}

/**
 * Clear all persisted store data
 */
export async function clearStore(): Promise<void> {
  // Reset store to initial state
  useStore.setState(initialState)
  // Clear persisted data
  const storage = storageAdapterGetter?.()
  if (storage) {
    await storage.removeItem(STORE_NAME)
  }
}

/**
 * Get current state snapshot (for non-React usage)
 */
export function getStoreState(): StoreState {
  return useStore.getState()
}

/**
 * Subscribe to state changes (for non-React usage)
 */
export function subscribeToStore(
  listener: (state: Store, prevState: Store) => void
): () => void {
  return useStore.subscribe(listener)
}

/**
 * Subscribe to specific state selector
 */
export function subscribeToSelector<T>(
  selector: (state: Store) => T,
  listener: (value: T, prevValue: T) => void,
  options?: { equalityFn?: (a: T, b: T) => boolean; fireImmediately?: boolean }
): () => void {
  return useStore.subscribe(selector, listener, options)
}

// Re-export types
export type { Store, StoreState } from './types'
export type {
  AuthState,
  AuthActions,
  SettingsState,
  SettingsActions,
  CacheState,
  CacheActions,
  Profile,
  LoginMethod,
} from './types'

// Re-export constants
export { PERSISTED_KEYS, CACHE_KEYS, MAX_CACHED_PROFILES, PROFILE_CACHE_TTL } from './types'
export { STORE_NAME, STORE_VERSION } from './persist'

// Re-export hooks
export {
  useAuth,
  usePubkey,
  useLoginMethod,
  useIsLoggedIn,
  useAuthActions,
  useSettings,
  useDefaultZapAmount,
  useLowBandwidthMode,
  useAutoSign,
  useUserGeohash,
  useCustomRelays,
  useMutedPubkeys,
  useMutedWords,
  useSettingsActions,
  useCachedProfile,
  useFollowList,
  useMyFollowList,
  useCacheActions,
  useIsMuted,
  useContainsMutedWords,
} from './hooks'
