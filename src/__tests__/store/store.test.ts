/**
 * Store Unit Tests
 *
 * Tests for Zustand store slices and actions.
 * Uses direct store manipulation for testing.
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { useStore, clearStore, getStoreState, setStorageAdapterGetter } from '@/src/core/store'
import { createMemoryStorage } from '@/src/adapters/storage/MemoryStorage'
import type { Profile } from '@/src/core/store/types'

describe('Store', () => {
  let memoryStorage: ReturnType<typeof createMemoryStorage>

  beforeEach(() => {
    // Create fresh storage for each test
    memoryStorage = createMemoryStorage()
    setStorageAdapterGetter(() => memoryStorage)

    // Reset store to initial state
    useStore.setState({
      // Auth
      pubkey: null,
      loginMethod: null,
      isLoggedIn: false,
      // Settings
      defaultZapAmount: 1000,
      lowBandwidthMode: false,
      autoSign: false,
      userGeohash: null,
      customRelays: [],
      mutedPubkeys: [],
      mutedWords: [],
      // Cache
      profiles: {},
      followLists: {},
    })
  })

  afterEach(async () => {
    await clearStore()
  })

  describe('Auth Slice', () => {
    describe('login', () => {
      it('should set pubkey, loginMethod, and isLoggedIn', () => {
        const pubkey = 'abc123def456'
        const method = 'nip07'

        useStore.getState().login(pubkey, method)

        const state = getStoreState()
        expect(state.pubkey).toBe(pubkey)
        expect(state.loginMethod).toBe(method)
        expect(state.isLoggedIn).toBe(true)
      })

      it('should support different login methods', () => {
        const methods: Array<'nip07' | 'nosskey' | 'amber' | 'bunker' | 'nsec' | 'nsec-app'> = [
          'nip07',
          'nosskey',
          'amber',
          'bunker',
          'nsec',
          'nsec-app',
        ]

        for (const method of methods) {
          useStore.getState().login('testpubkey', method)
          expect(getStoreState().loginMethod).toBe(method)
        }
      })
    })

    describe('logout', () => {
      it('should clear auth state', () => {
        // Login first
        useStore.getState().login('testpubkey', 'nip07')
        expect(getStoreState().isLoggedIn).toBe(true)

        // Logout
        useStore.getState().logout()

        const state = getStoreState()
        expect(state.pubkey).toBeNull()
        expect(state.loginMethod).toBeNull()
        expect(state.isLoggedIn).toBe(false)
      })
    })

    describe('setPubkey', () => {
      it('should set only pubkey without affecting other auth state', () => {
        useStore.getState().setPubkey('newpubkey')

        const state = getStoreState()
        expect(state.pubkey).toBe('newpubkey')
        expect(state.loginMethod).toBeNull()
        expect(state.isLoggedIn).toBe(false)
      })

      it('should update existing pubkey', () => {
        useStore.getState().login('oldpubkey', 'nip07')
        useStore.getState().setPubkey('newpubkey')

        const state = getStoreState()
        expect(state.pubkey).toBe('newpubkey')
        expect(state.loginMethod).toBe('nip07')
        expect(state.isLoggedIn).toBe(true)
      })
    })
  })

  describe('Settings Slice', () => {
    describe('setDefaultZapAmount', () => {
      it('should set default zap amount', () => {
        useStore.getState().setDefaultZapAmount(5000)
        expect(getStoreState().defaultZapAmount).toBe(5000)
      })

      it('should handle zero amount', () => {
        useStore.getState().setDefaultZapAmount(0)
        expect(getStoreState().defaultZapAmount).toBe(0)
      })

      it('should handle large amounts', () => {
        useStore.getState().setDefaultZapAmount(1000000)
        expect(getStoreState().defaultZapAmount).toBe(1000000)
      })
    })

    describe('setLowBandwidthMode', () => {
      it('should enable low bandwidth mode', () => {
        useStore.getState().setLowBandwidthMode(true)
        expect(getStoreState().lowBandwidthMode).toBe(true)
      })

      it('should disable low bandwidth mode', () => {
        useStore.getState().setLowBandwidthMode(true)
        useStore.getState().setLowBandwidthMode(false)
        expect(getStoreState().lowBandwidthMode).toBe(false)
      })
    })

    describe('setAutoSign', () => {
      it('should enable auto sign', () => {
        useStore.getState().setAutoSign(true)
        expect(getStoreState().autoSign).toBe(true)
      })

      it('should disable auto sign', () => {
        useStore.getState().setAutoSign(true)
        useStore.getState().setAutoSign(false)
        expect(getStoreState().autoSign).toBe(false)
      })
    })

    describe('setUserGeohash', () => {
      it('should set user geohash', () => {
        useStore.getState().setUserGeohash('xn76g')
        expect(getStoreState().userGeohash).toBe('xn76g')
      })

      it('should clear user geohash with null', () => {
        useStore.getState().setUserGeohash('xn76g')
        useStore.getState().setUserGeohash(null)
        expect(getStoreState().userGeohash).toBeNull()
      })
    })

    describe('customRelays', () => {
      it('should add a custom relay', () => {
        useStore.getState().addCustomRelay('wss://relay.example.com')
        expect(getStoreState().customRelays).toContain('wss://relay.example.com')
      })

      it('should not add duplicate relays', () => {
        const url = 'wss://relay.example.com'
        useStore.getState().addCustomRelay(url)
        useStore.getState().addCustomRelay(url)
        expect(getStoreState().customRelays).toHaveLength(1)
      })

      it('should remove a custom relay', () => {
        const url = 'wss://relay.example.com'
        useStore.getState().addCustomRelay(url)
        useStore.getState().removeCustomRelay(url)
        expect(getStoreState().customRelays).not.toContain(url)
      })

      it('should set custom relays array', () => {
        const relays = ['wss://relay1.com', 'wss://relay2.com']
        useStore.getState().setCustomRelays(relays)
        expect(getStoreState().customRelays).toEqual(relays)
      })

      it('should not throw when removing non-existent relay', () => {
        expect(() => {
          useStore.getState().removeCustomRelay('wss://nonexistent.com')
        }).not.toThrow()
      })
    })

    describe('mutedPubkeys', () => {
      it('should add a muted pubkey', () => {
        const pubkey = 'abc123'
        useStore.getState().addMutedPubkey(pubkey)
        expect(getStoreState().mutedPubkeys).toContain(pubkey)
      })

      it('should not add duplicate pubkeys', () => {
        const pubkey = 'abc123'
        useStore.getState().addMutedPubkey(pubkey)
        useStore.getState().addMutedPubkey(pubkey)
        expect(getStoreState().mutedPubkeys).toHaveLength(1)
      })

      it('should remove a muted pubkey', () => {
        const pubkey = 'abc123'
        useStore.getState().addMutedPubkey(pubkey)
        useStore.getState().removeMutedPubkey(pubkey)
        expect(getStoreState().mutedPubkeys).not.toContain(pubkey)
      })
    })

    describe('mutedWords', () => {
      it('should add a muted word', () => {
        const word = 'spam'
        useStore.getState().addMutedWord(word)
        expect(getStoreState().mutedWords).toContain(word)
      })

      it('should not add duplicate words', () => {
        const word = 'spam'
        useStore.getState().addMutedWord(word)
        useStore.getState().addMutedWord(word)
        expect(getStoreState().mutedWords).toHaveLength(1)
      })

      it('should remove a muted word', () => {
        const word = 'spam'
        useStore.getState().addMutedWord(word)
        useStore.getState().removeMutedWord(word)
        expect(getStoreState().mutedWords).not.toContain(word)
      })
    })
  })

  describe('Cache Slice', () => {
    const createTestProfile = (pubkey: string): Profile => ({
      pubkey,
      name: `User ${pubkey.slice(0, 8)}`,
      fetchedAt: Date.now(),
    })

    describe('setProfile / getProfile', () => {
      it('should set and get a profile', () => {
        const profile = createTestProfile('pubkey123')
        useStore.getState().setProfile(profile)

        const retrieved = useStore.getState().getProfile('pubkey123')
        expect(retrieved).toBeDefined()
        expect(retrieved?.pubkey).toBe('pubkey123')
      })

      it('should return undefined for non-existent profile', () => {
        const profile = useStore.getState().getProfile('nonexistent')
        expect(profile).toBeUndefined()
      })

      it('should update existing profile', () => {
        const profile1 = createTestProfile('pubkey123')
        profile1.name = 'Original Name'
        useStore.getState().setProfile(profile1)

        const profile2 = createTestProfile('pubkey123')
        profile2.name = 'Updated Name'
        useStore.getState().setProfile(profile2)

        const retrieved = useStore.getState().getProfile('pubkey123')
        expect(retrieved?.name).toBe('Updated Name')
      })

      it('should set fetchedAt if not provided', () => {
        const profile: Profile = {
          pubkey: 'test123',
          name: 'Test User',
          fetchedAt: 0, // Will be overwritten
        }
        useStore.getState().setProfile({ ...profile, fetchedAt: undefined as any })

        const retrieved = useStore.getState().getProfile('test123')
        expect(retrieved?.fetchedAt).toBeGreaterThan(0)
      })

      it('should return undefined for stale profile', () => {
        const staleProfile: Profile = {
          pubkey: 'stale123',
          name: 'Stale User',
          fetchedAt: Date.now() - 25 * 60 * 60 * 1000, // 25 hours ago
        }
        useStore.getState().setProfile(staleProfile)

        const retrieved = useStore.getState().getProfile('stale123')
        expect(retrieved).toBeUndefined()
      })
    })

    describe('setFollowList / getFollowList', () => {
      it('should set and get a follow list', () => {
        const follows = ['pubkey1', 'pubkey2', 'pubkey3']
        useStore.getState().setFollowList('user123', follows)

        const retrieved = useStore.getState().getFollowList('user123')
        expect(retrieved).toEqual(follows)
      })

      it('should return undefined for non-existent follow list', () => {
        const followList = useStore.getState().getFollowList('nonexistent')
        expect(followList).toBeUndefined()
      })

      it('should update existing follow list', () => {
        useStore.getState().setFollowList('user123', ['pubkey1'])
        useStore.getState().setFollowList('user123', ['pubkey2', 'pubkey3'])

        const retrieved = useStore.getState().getFollowList('user123')
        expect(retrieved).toEqual(['pubkey2', 'pubkey3'])
      })

      it('should handle empty follow list', () => {
        useStore.getState().setFollowList('user123', [])
        const retrieved = useStore.getState().getFollowList('user123')
        expect(retrieved).toEqual([])
      })
    })

    describe('clearProfiles', () => {
      it('should clear all profiles', () => {
        useStore.getState().setProfile(createTestProfile('pubkey1'))
        useStore.getState().setProfile(createTestProfile('pubkey2'))

        useStore.getState().clearProfiles()

        expect(useStore.getState().getProfile('pubkey1')).toBeUndefined()
        expect(useStore.getState().getProfile('pubkey2')).toBeUndefined()
        expect(getStoreState().profiles).toEqual({})
      })

      it('should not affect follow lists', () => {
        useStore.getState().setProfile(createTestProfile('pubkey1'))
        useStore.getState().setFollowList('pubkey1', ['follow1'])

        useStore.getState().clearProfiles()

        expect(useStore.getState().getFollowList('pubkey1')).toEqual(['follow1'])
      })
    })

    describe('clearAllCache', () => {
      it('should clear both profiles and follow lists', () => {
        useStore.getState().setProfile(createTestProfile('pubkey1'))
        useStore.getState().setFollowList('pubkey1', ['follow1'])

        useStore.getState().clearAllCache()

        expect(getStoreState().profiles).toEqual({})
        expect(getStoreState().followLists).toEqual({})
      })
    })

    describe('cache eviction', () => {
      it('should evict oldest profiles when exceeding limit', () => {
        // Add profiles with different timestamps
        for (let i = 0; i < 510; i++) {
          const profile: Profile = {
            pubkey: `pubkey${i.toString().padStart(4, '0')}`,
            name: `User ${i}`,
            fetchedAt: Date.now() + i, // Newer profiles have higher fetchedAt
          }
          useStore.getState().setProfile(profile)
        }

        // Should have evicted old profiles
        const state = getStoreState()
        expect(Object.keys(state.profiles).length).toBeLessThanOrEqual(500)

        // Oldest profiles should be gone
        expect(state.profiles['pubkey0000']).toBeUndefined()

        // Newest profiles should remain
        expect(state.profiles['pubkey0509']).toBeDefined()
      })
    })
  })

  describe('Store subscription', () => {
    it('should notify listeners on state change', () => {
      const listener = vi.fn()
      const unsubscribe = useStore.subscribe(listener)

      useStore.getState().login('testpubkey', 'nip07')

      expect(listener).toHaveBeenCalled()
      unsubscribe()
    })

    it('should stop notifying after unsubscribe', () => {
      const listener = vi.fn()
      const unsubscribe = useStore.subscribe(listener)

      unsubscribe()
      useStore.getState().login('testpubkey', 'nip07')

      expect(listener).not.toHaveBeenCalled()
    })
  })

  describe('getStoreState', () => {
    it('should return current state', () => {
      useStore.getState().login('testpubkey', 'nip07')
      useStore.getState().setDefaultZapAmount(5000)

      const state = getStoreState()

      expect(state.pubkey).toBe('testpubkey')
      expect(state.defaultZapAmount).toBe(5000)
    })
  })

  describe('clearStore', () => {
    it('should reset store to initial state', async () => {
      useStore.getState().login('testpubkey', 'nip07')
      useStore.getState().setDefaultZapAmount(5000)
      useStore.getState().setProfile({
        pubkey: 'profile1',
        name: 'Test',
        fetchedAt: Date.now(),
      })

      await clearStore()

      const state = getStoreState()
      expect(state.pubkey).toBeNull()
      expect(state.isLoggedIn).toBe(false)
      expect(state.defaultZapAmount).toBe(1000)
      expect(state.profiles).toEqual({})
    })
  })
})
