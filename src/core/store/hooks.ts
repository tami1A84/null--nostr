/**
 * Store Hooks
 *
 * React hooks for accessing the Zustand store with memoization
 *
 * @module core/store/hooks
 */

import { useStore } from './index'
import type { Profile, LoginMethod } from './types'

// ====================================
// Auth Hooks
// ====================================

/**
 * Get auth state
 */
export function useAuth() {
  return useStore((state) => ({
    pubkey: state.pubkey,
    loginMethod: state.loginMethod,
    isLoggedIn: state.isLoggedIn,
  }))
}

/**
 * Get current user's pubkey
 */
export function usePubkey(): string | null {
  return useStore((state) => state.pubkey)
}

/**
 * Get login method
 */
export function useLoginMethod(): LoginMethod | null {
  return useStore((state) => state.loginMethod)
}

/**
 * Check if user is logged in
 */
export function useIsLoggedIn(): boolean {
  return useStore((state) => state.isLoggedIn)
}

/**
 * Get auth actions
 */
export function useAuthActions() {
  return useStore((state) => ({
    login: state.login,
    logout: state.logout,
    setPubkey: state.setPubkey,
  }))
}

// ====================================
// Settings Hooks
// ====================================

/**
 * Get all settings
 */
export function useSettings() {
  return useStore((state) => ({
    defaultZapAmount: state.defaultZapAmount,
    lowBandwidthMode: state.lowBandwidthMode,
    autoSign: state.autoSign,
    userGeohash: state.userGeohash,
    customRelays: state.customRelays,
    mutedPubkeys: state.mutedPubkeys,
    mutedWords: state.mutedWords,
  }))
}

/**
 * Get default zap amount
 */
export function useDefaultZapAmount(): number {
  return useStore((state) => state.defaultZapAmount)
}

/**
 * Get low bandwidth mode setting
 */
export function useLowBandwidthMode(): boolean {
  return useStore((state) => state.lowBandwidthMode)
}

/**
 * Get auto sign setting
 */
export function useAutoSign(): boolean {
  return useStore((state) => state.autoSign)
}

/**
 * Get user geohash
 */
export function useUserGeohash(): string | null {
  return useStore((state) => state.userGeohash)
}

/**
 * Get custom relays
 */
export function useCustomRelays(): string[] {
  return useStore((state) => state.customRelays)
}

/**
 * Get muted pubkeys
 */
export function useMutedPubkeys(): string[] {
  return useStore((state) => state.mutedPubkeys)
}

/**
 * Get muted words
 */
export function useMutedWords(): string[] {
  return useStore((state) => state.mutedWords)
}

/**
 * Get settings actions
 */
export function useSettingsActions() {
  return useStore((state) => ({
    setDefaultZapAmount: state.setDefaultZapAmount,
    setLowBandwidthMode: state.setLowBandwidthMode,
    setAutoSign: state.setAutoSign,
    setUserGeohash: state.setUserGeohash,
    addCustomRelay: state.addCustomRelay,
    removeCustomRelay: state.removeCustomRelay,
    setCustomRelays: state.setCustomRelays,
    addMutedPubkey: state.addMutedPubkey,
    removeMutedPubkey: state.removeMutedPubkey,
    addMutedWord: state.addMutedWord,
    removeMutedWord: state.removeMutedWord,
  }))
}

// ====================================
// Cache Hooks
// ====================================

/**
 * Get a cached profile
 */
export function useCachedProfile(pubkey: string): Profile | undefined {
  return useStore((state) => state.getProfile(pubkey))
}

/**
 * Get follow list for a pubkey
 */
export function useFollowList(pubkey: string | null): string[] | undefined {
  return useStore((state) =>
    pubkey ? state.getFollowList(pubkey) : undefined
  )
}

/**
 * Get current user's follow list
 */
export function useMyFollowList(): string[] | undefined {
  const pubkey = usePubkey()
  return useFollowList(pubkey)
}

/**
 * Get cache actions
 */
export function useCacheActions() {
  return useStore((state) => ({
    setProfile: state.setProfile,
    getProfile: state.getProfile,
    setFollowList: state.setFollowList,
    getFollowList: state.getFollowList,
    clearProfiles: state.clearProfiles,
    clearAllCache: state.clearAllCache,
  }))
}

// ====================================
// Mute Helpers
// ====================================

/**
 * Check if a pubkey is muted
 */
export function useIsMuted(pubkey: string): boolean {
  return useStore((state) => state.mutedPubkeys.includes(pubkey))
}

/**
 * Check if content contains muted words
 */
export function useContainsMutedWords(content: string): boolean {
  return useStore((state) => {
    const lowerContent = content.toLowerCase()
    return state.mutedWords.some((word) =>
      lowerContent.includes(word.toLowerCase())
    )
  })
}
