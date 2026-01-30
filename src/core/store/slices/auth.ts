/**
 * Auth Slice
 *
 * Manages authentication state including login/logout
 *
 * @module core/store/slices/auth
 */

import type { StateCreator } from 'zustand'
import type { Store, AuthState, AuthActions, LoginMethod } from '../types'

/**
 * Initial auth state
 */
export const initialAuthState: AuthState = {
  pubkey: null,
  loginMethod: null,
  isLoggedIn: false,
}

/**
 * Create auth slice
 */
export const createAuthSlice: StateCreator<
  Store,
  [['zustand/immer', never]],
  [],
  AuthState & AuthActions
> = (set) => ({
  ...initialAuthState,

  login: (pubkey: string, method: LoginMethod) => {
    set((state) => {
      state.pubkey = pubkey
      state.loginMethod = method
      state.isLoggedIn = true
    })
  },

  logout: () => {
    set((state) => {
      state.pubkey = null
      state.loginMethod = null
      state.isLoggedIn = false
    })
  },

  setPubkey: (pubkey: string) => {
    set((state) => {
      state.pubkey = pubkey
    })
  },
})
