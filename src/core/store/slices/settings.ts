/**
 * Settings Slice
 *
 * Manages user preferences and settings
 *
 * @module core/store/slices/settings
 */

import type { StateCreator } from 'zustand'
import type { Store, SettingsState, SettingsActions } from '../types'

/**
 * Initial settings state with defaults
 */
export const initialSettingsState: SettingsState = {
  defaultZapAmount: 1000,
  lowBandwidthMode: false,
  autoSign: false,
  userGeohash: null,
  customRelays: [],
  mutedPubkeys: [],
  mutedWords: [],
}

/**
 * Create settings slice
 */
export const createSettingsSlice: StateCreator<
  Store,
  [['zustand/immer', never]],
  [],
  SettingsState & SettingsActions
> = (set) => ({
  ...initialSettingsState,

  setDefaultZapAmount: (amount: number) => {
    set((state) => {
      state.defaultZapAmount = amount
    })
  },

  setLowBandwidthMode: (enabled: boolean) => {
    set((state) => {
      state.lowBandwidthMode = enabled
    })
  },

  setAutoSign: (enabled: boolean) => {
    set((state) => {
      state.autoSign = enabled
    })
  },

  setUserGeohash: (geohash: string | null) => {
    set((state) => {
      state.userGeohash = geohash
    })
  },

  addCustomRelay: (url: string) => {
    set((state) => {
      if (!state.customRelays.includes(url)) {
        state.customRelays.push(url)
      }
    })
  },

  removeCustomRelay: (url: string) => {
    set((state) => {
      const index = state.customRelays.indexOf(url)
      if (index !== -1) {
        state.customRelays.splice(index, 1)
      }
    })
  },

  setCustomRelays: (urls: string[]) => {
    set((state) => {
      state.customRelays = urls
    })
  },

  addMutedPubkey: (pubkey: string) => {
    set((state) => {
      if (!state.mutedPubkeys.includes(pubkey)) {
        state.mutedPubkeys.push(pubkey)
      }
    })
  },

  removeMutedPubkey: (pubkey: string) => {
    set((state) => {
      const index = state.mutedPubkeys.indexOf(pubkey)
      if (index !== -1) {
        state.mutedPubkeys.splice(index, 1)
      }
    })
  },

  addMutedWord: (word: string) => {
    set((state) => {
      if (!state.mutedWords.includes(word)) {
        state.mutedWords.push(word)
      }
    })
  },

  removeMutedWord: (word: string) => {
    set((state) => {
      const index = state.mutedWords.indexOf(word)
      if (index !== -1) {
        state.mutedWords.splice(index, 1)
      }
    })
  },
})
