/**
 * Integration Tests: Store + Adapters
 *
 * Tests that verify the integration between the Zustand store
 * and platform adapters (storage, signing).
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { useStore, setStorageAdapterGetter, clearStore, getStoreState } from '@/src/core/store'
import { createMemoryStorage, MemoryStorage } from '@/src/adapters/storage/MemoryStorage'
import { createRandomSigner, MemorySigner } from '@/src/adapters/signing/MemorySigner'
import { createMockClipboard, MockClipboard } from '@/src/adapters/clipboard/MockClipboard'
import { createMockNetwork, MockNetwork } from '@/src/adapters/network/MockNetwork'
import { verifyEvent } from 'nostr-tools'

describe('Store + Storage Integration', () => {
  let storage: MemoryStorage

  beforeEach(() => {
    storage = createMemoryStorage()
    setStorageAdapterGetter(() => storage)

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

  it('should work with MemoryStorage adapter', async () => {
    // Set state
    useStore.getState().login('testpubkey', 'nip07')
    useStore.getState().setDefaultZapAmount(5000)

    // Verify state
    const state = getStoreState()
    expect(state.pubkey).toBe('testpubkey')
    expect(state.defaultZapAmount).toBe(5000)
  })

  it('should persist and retrieve data through storage', async () => {
    // Set data
    await storage.setItem('test-key', 'test-value')

    // Retrieve data
    const value = await storage.getItem('test-key')
    expect(value).toBe('test-value')
  })

  it('should handle JSON data through storage', async () => {
    const testData = {
      pubkey: 'abc123',
      settings: {
        zapAmount: 1000,
        mode: 'dark',
      },
    }

    await storage.setJSON('test-json', testData)
    const retrieved = await storage.getJSON<typeof testData>('test-json')

    expect(retrieved).toEqual(testData)
  })

  it('should clear store and storage together', async () => {
    // Set state
    useStore.getState().login('testpubkey', 'nip07')
    await storage.setItem('extra-key', 'extra-value')

    // Clear store
    await clearStore()

    // Verify store is cleared
    const state = getStoreState()
    expect(state.pubkey).toBeNull()
    expect(state.isLoggedIn).toBe(false)
  })
})

describe('Store + Signer Integration', () => {
  it('should login with signer pubkey', async () => {
    const signer = createRandomSigner()
    const pubkey = await signer.getPublicKey()

    useStore.getState().login(pubkey, 'nsec')

    const state = getStoreState()
    expect(state.pubkey).toBe(pubkey)
    expect(state.isLoggedIn).toBe(true)

    await signer.destroy()
  })

  // Note: signEvent and NIP-04/44 encryption tests are covered
  // in src/__tests__/adapters/signing.test.ts
  // This integration suite focuses on store + signer interaction
})

describe('Mock Adapters Integration', () => {
  describe('MockClipboard', () => {
    let clipboard: MockClipboard

    beforeEach(() => {
      clipboard = createMockClipboard()
    })

    it('should copy and paste text', async () => {
      await clipboard.writeText('Hello, clipboard!')
      const text = await clipboard.readText()
      expect(text).toBe('Hello, clipboard!')
    })

    it('should copy and paste images', async () => {
      const imageData = 'base64-encoded-image-data'
      await clipboard.writeImage(imageData, 'image/png')

      const result = await clipboard.readImage()
      expect(result?.image).toBe(imageData)
      expect(result?.imageMimeType).toBe('image/png')
    })

    it('should report availability correctly', () => {
      expect(clipboard.isAvailable()).toBe(true)
      clipboard.setAvailable(false)
      expect(clipboard.isAvailable()).toBe(false)
    })
  })

  describe('MockNetwork', () => {
    let network: MockNetwork

    beforeEach(() => {
      network = createMockNetwork()
    })

    afterEach(() => {
      network.destroy()
    })

    it('should report online by default', async () => {
      const online = await network.isOnline()
      expect(online).toBe(true)
    })

    it('should simulate going offline', async () => {
      network.goOffline()

      const status = await network.getStatus()
      expect(status.connected).toBe(false)
      expect(status.connectionType).toBe('none')
    })

    it('should simulate going online', async () => {
      network.goOffline()
      network.goOnline('cellular')

      const status = await network.getStatus()
      expect(status.connected).toBe(true)
      expect(status.connectionType).toBe('cellular')
    })

    it('should notify listeners of status changes', async () => {
      const listener = vi.fn()
      network.addStatusListener(listener)

      network.goOffline()

      expect(listener).toHaveBeenCalledWith(
        expect.objectContaining({
          connected: false,
          connectionType: 'none',
        })
      )
    })

    it('should stop notifying after listener removal', () => {
      const listener = vi.fn()
      const unsubscribe = network.addStatusListener(listener)

      unsubscribe()
      network.goOffline()

      expect(listener).not.toHaveBeenCalled()
    })

    it('should check connectivity', async () => {
      expect(await network.checkConnectivity()).toBe(true)

      network.setConnectivityCheckResult(false)
      expect(await network.checkConnectivity()).toBe(false)
    })
  })
})

describe('Complete Workflow Integration', () => {
  let storage: MemoryStorage
  let signer: MemorySigner

  beforeEach(() => {
    storage = createMemoryStorage()
    setStorageAdapterGetter(() => storage)
    signer = createRandomSigner()

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
    await signer.destroy()
    await clearStore()
  })

  it('should complete login flow', async () => {
    // Get pubkey from signer
    const pubkey = await signer.getPublicKey()

    // Login
    useStore.getState().login(pubkey, 'nsec')

    // Verify logged in
    expect(getStoreState().isLoggedIn).toBe(true)
    expect(getStoreState().pubkey).toBe(pubkey)

    // Set profile
    useStore.getState().setProfile({
      pubkey,
      name: 'Test User',
      displayName: 'Test',
      fetchedAt: Date.now(),
    })

    // Verify profile cached
    const profile = useStore.getState().getProfile(pubkey)
    expect(profile?.name).toBe('Test User')
  })

  // Note: Post creation with signing is tested in signing.test.ts
  // This workflow focuses on store state changes during post creation

  it('should complete settings update flow', async () => {
    // Login
    const pubkey = await signer.getPublicKey()
    useStore.getState().login(pubkey, 'nsec')

    // Update settings
    useStore.getState().setDefaultZapAmount(5000)
    useStore.getState().addCustomRelay('wss://relay.example.com')
    useStore.getState().addMutedWord('spam')
    useStore.getState().setLowBandwidthMode(true)

    // Verify settings
    const state = getStoreState()
    expect(state.defaultZapAmount).toBe(5000)
    expect(state.customRelays).toContain('wss://relay.example.com')
    expect(state.mutedWords).toContain('spam')
    expect(state.lowBandwidthMode).toBe(true)
  })

  it('should complete logout flow', async () => {
    // Login
    const pubkey = await signer.getPublicKey()
    useStore.getState().login(pubkey, 'nsec')
    useStore.getState().setProfile({
      pubkey,
      name: 'Test User',
      fetchedAt: Date.now(),
    })

    // Logout
    useStore.getState().logout()

    // Verify logged out
    const state = getStoreState()
    expect(state.isLoggedIn).toBe(false)
    expect(state.pubkey).toBeNull()

    // Profile should still be cached
    const profile = useStore.getState().getProfile(pubkey)
    expect(profile?.name).toBe('Test User')
  })

  it('should handle muting workflow', async () => {
    // Login
    const pubkey = await signer.getPublicKey()
    useStore.getState().login(pubkey, 'nsec')

    // Mute a user
    const mutedPubkey = 'spammer123'
    useStore.getState().addMutedPubkey(mutedPubkey)

    // Mute words
    useStore.getState().addMutedWord('spam')
    useStore.getState().addMutedWord('scam')

    // Verify muting
    expect(getStoreState().mutedPubkeys).toContain(mutedPubkey)
    expect(getStoreState().mutedWords).toContain('spam')
    expect(getStoreState().mutedWords).toContain('scam')

    // Unmute
    useStore.getState().removeMutedPubkey(mutedPubkey)
    useStore.getState().removeMutedWord('spam')

    // Verify unmuting
    expect(getStoreState().mutedPubkeys).not.toContain(mutedPubkey)
    expect(getStoreState().mutedWords).not.toContain('spam')
    expect(getStoreState().mutedWords).toContain('scam')
  })
})
