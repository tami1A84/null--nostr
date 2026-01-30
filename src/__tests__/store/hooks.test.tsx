/**
 * Store Hooks Unit Tests
 *
 * Tests for React hooks that access the Zustand store.
 * Uses direct store state access to avoid renderHook issues with Zustand.
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { useStore, clearStore, setStorageAdapterGetter, getStoreState } from '@/src/core/store'
import { createMemoryStorage } from '@/src/adapters/storage/MemoryStorage'
import type { Profile, LoginMethod } from '@/src/core/store/types'

describe('Store Hooks', () => {
  beforeEach(() => {
    const memoryStorage = createMemoryStorage()
    setStorageAdapterGetter(() => memoryStorage)

    // Reset store
    useStore.setState({
      pubkey: null,
      loginMethod: null,
      isLoggedIn: false,
      defaultZapAmount: 1000,
      lowBandwidthMode: false,
      autoSign: false,
      userGeohash: null,
      customRelays: [],
      mutedPubkeys: [],
      mutedWords: [],
      profiles: {},
      followLists: {},
    })
  })

  afterEach(async () => {
    await clearStore()
  })

  describe('Auth Hooks (via store selectors)', () => {
    describe('auth state', () => {
      it('should return initial auth state', () => {
        const state = getStoreState()

        expect(state.pubkey).toBeNull()
        expect(state.loginMethod).toBeNull()
        expect(state.isLoggedIn).toBe(false)
      })

      it('should update when auth state changes', () => {
        useStore.getState().login('testpubkey', 'nip07')

        const state = getStoreState()
        expect(state.pubkey).toBe('testpubkey')
        expect(state.loginMethod).toBe('nip07')
        expect(state.isLoggedIn).toBe(true)
      })
    })

    describe('pubkey selector', () => {
      it('should return null when not logged in', () => {
        expect(getStoreState().pubkey).toBeNull()
      })

      it('should return pubkey when logged in', () => {
        useStore.getState().login('mypubkey', 'nip07')
        expect(getStoreState().pubkey).toBe('mypubkey')
      })
    })

    describe('loginMethod selector', () => {
      it('should return null when not logged in', () => {
        expect(getStoreState().loginMethod).toBeNull()
      })

      it('should return login method when logged in', () => {
        useStore.getState().login('testpubkey', 'nosskey')
        expect(getStoreState().loginMethod).toBe('nosskey')
      })
    })

    describe('isLoggedIn selector', () => {
      it('should return false when not logged in', () => {
        expect(getStoreState().isLoggedIn).toBe(false)
      })

      it('should return true when logged in', () => {
        useStore.getState().login('testpubkey', 'nip07')
        expect(getStoreState().isLoggedIn).toBe(true)
      })
    })

    describe('auth actions', () => {
      it('should have auth action functions', () => {
        const store = useStore.getState()

        expect(typeof store.login).toBe('function')
        expect(typeof store.logout).toBe('function')
        expect(typeof store.setPubkey).toBe('function')
      })

      it('should login successfully', () => {
        useStore.getState().login('testpubkey', 'nip07')
        expect(getStoreState().isLoggedIn).toBe(true)
      })

      it('should logout successfully', () => {
        useStore.getState().login('testpubkey', 'nip07')
        useStore.getState().logout()
        expect(getStoreState().isLoggedIn).toBe(false)
      })
    })
  })

  describe('Settings Hooks (via store selectors)', () => {
    describe('settings state', () => {
      it('should return initial settings', () => {
        const state = getStoreState()

        expect(state.defaultZapAmount).toBe(1000)
        expect(state.lowBandwidthMode).toBe(false)
        expect(state.autoSign).toBe(false)
        expect(state.userGeohash).toBeNull()
        expect(state.customRelays).toEqual([])
        expect(state.mutedPubkeys).toEqual([])
        expect(state.mutedWords).toEqual([])
      })
    })

    describe('defaultZapAmount selector', () => {
      it('should return default zap amount', () => {
        expect(getStoreState().defaultZapAmount).toBe(1000)
      })

      it('should update when zap amount changes', () => {
        useStore.getState().setDefaultZapAmount(5000)
        expect(getStoreState().defaultZapAmount).toBe(5000)
      })
    })

    describe('lowBandwidthMode selector', () => {
      it('should return low bandwidth mode setting', () => {
        expect(getStoreState().lowBandwidthMode).toBe(false)
      })
    })

    describe('autoSign selector', () => {
      it('should return auto sign setting', () => {
        expect(getStoreState().autoSign).toBe(false)
      })
    })

    describe('userGeohash selector', () => {
      it('should return null initially', () => {
        expect(getStoreState().userGeohash).toBeNull()
      })

      it('should return geohash when set', () => {
        useStore.getState().setUserGeohash('xn76g')
        expect(getStoreState().userGeohash).toBe('xn76g')
      })
    })

    describe('customRelays selector', () => {
      it('should return empty array initially', () => {
        expect(getStoreState().customRelays).toEqual([])
      })

      it('should return relays when added', () => {
        useStore.getState().addCustomRelay('wss://relay.example.com')
        expect(getStoreState().customRelays).toContain('wss://relay.example.com')
      })
    })

    describe('mutedPubkeys selector', () => {
      it('should return empty array initially', () => {
        expect(getStoreState().mutedPubkeys).toEqual([])
      })
    })

    describe('mutedWords selector', () => {
      it('should return empty array initially', () => {
        expect(getStoreState().mutedWords).toEqual([])
      })
    })

    describe('settings actions', () => {
      it('should have settings action functions', () => {
        const store = useStore.getState()

        expect(typeof store.setDefaultZapAmount).toBe('function')
        expect(typeof store.setLowBandwidthMode).toBe('function')
        expect(typeof store.setAutoSign).toBe('function')
        expect(typeof store.addCustomRelay).toBe('function')
        expect(typeof store.addMutedPubkey).toBe('function')
      })
    })
  })

  describe('Cache Hooks (via store selectors)', () => {
    describe('profile cache', () => {
      it('should return undefined for non-existent profile', () => {
        const profile = useStore.getState().getProfile('nonexistent')
        expect(profile).toBeUndefined()
      })

      it('should return profile when cached', () => {
        const profile: Profile = {
          pubkey: 'testpubkey',
          name: 'Test User',
          fetchedAt: Date.now(),
        }

        useStore.getState().setProfile(profile)

        const cached = useStore.getState().getProfile('testpubkey')
        expect(cached?.name).toBe('Test User')
      })
    })

    describe('follow list', () => {
      it('should return undefined for non-existent follow list', () => {
        const followList = useStore.getState().getFollowList('nonexistent')
        expect(followList).toBeUndefined()
      })

      it('should return follow list when set', () => {
        const follows = ['pubkey1', 'pubkey2']

        useStore.getState().setFollowList('user123', follows)

        const cached = useStore.getState().getFollowList('user123')
        expect(cached).toEqual(follows)
      })
    })

    describe('my follow list', () => {
      it('should return undefined when not logged in', () => {
        const followList = useStore.getState().getFollowList(getStoreState().pubkey || '')
        expect(followList).toBeUndefined()
      })

      it('should return own follow list when logged in', () => {
        const follows = ['follow1', 'follow2']

        useStore.getState().login('mypubkey', 'nip07')
        useStore.getState().setFollowList('mypubkey', follows)

        const cached = useStore.getState().getFollowList('mypubkey')
        expect(cached).toEqual(follows)
      })
    })

    describe('cache actions', () => {
      it('should have cache action functions', () => {
        const store = useStore.getState()

        expect(typeof store.setProfile).toBe('function')
        expect(typeof store.getProfile).toBe('function')
        expect(typeof store.setFollowList).toBe('function')
        expect(typeof store.getFollowList).toBe('function')
        expect(typeof store.clearProfiles).toBe('function')
        expect(typeof store.clearAllCache).toBe('function')
      })
    })
  })

  describe('Mute Helpers', () => {
    describe('isMuted check', () => {
      it('should return false for non-muted pubkey', () => {
        const isMuted = getStoreState().mutedPubkeys.includes('somepubkey')
        expect(isMuted).toBe(false)
      })

      it('should return true for muted pubkey', () => {
        useStore.getState().addMutedPubkey('badpubkey')
        const isMuted = getStoreState().mutedPubkeys.includes('badpubkey')
        expect(isMuted).toBe(true)
      })

      it('should update when pubkey is unmuted', () => {
        useStore.getState().addMutedPubkey('badpubkey')
        expect(getStoreState().mutedPubkeys.includes('badpubkey')).toBe(true)

        useStore.getState().removeMutedPubkey('badpubkey')
        expect(getStoreState().mutedPubkeys.includes('badpubkey')).toBe(false)
      })
    })

    describe('containsMutedWords check', () => {
      const containsMutedWords = (content: string): boolean => {
        const lowerContent = content.toLowerCase()
        return getStoreState().mutedWords.some((word) =>
          lowerContent.includes(word.toLowerCase())
        )
      }

      it('should return false when no muted words', () => {
        expect(containsMutedWords('Hello world')).toBe(false)
      })

      it('should return true when content contains muted word', () => {
        useStore.getState().addMutedWord('spam')
        expect(containsMutedWords('This is spam content')).toBe(true)
      })

      it('should be case-insensitive', () => {
        useStore.getState().addMutedWord('SPAM')
        expect(containsMutedWords('this contains spam')).toBe(true)
      })

      it('should return false when content does not contain muted words', () => {
        useStore.getState().addMutedWord('spam')
        expect(containsMutedWords('This is a nice message')).toBe(false)
      })

      it('should handle multiple muted words', () => {
        useStore.getState().addMutedWord('spam')
        useStore.getState().addMutedWord('scam')

        expect(containsMutedWords('This is spam')).toBe(true)
        expect(containsMutedWords('This is a scam')).toBe(true)
        expect(containsMutedWords('This is clean')).toBe(false)
      })
    })
  })
})
