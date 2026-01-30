/**
 * Platform Container (Dependency Injection)
 *
 * Provides platform-specific adapter instances through a unified interface.
 * Automatically selects the appropriate implementations based on the detected platform.
 *
 * @module platform
 */

import type { StorageAdapter } from '@/src/adapters/storage'
import type { SigningAdapter, SignerType } from '@/src/adapters/signing'
import { detectPlatform, type Platform } from './detect'

/**
 * Container holding all platform adapters
 */
export interface AdapterContainer {
  storage: StorageAdapter
  signer: SigningAdapter | null
  signerType: SignerType | null
}

/**
 * Container configuration options
 */
export interface ContainerOptions {
  /** Override the default storage adapter */
  storage?: StorageAdapter
  /** Override the default signer */
  signer?: SigningAdapter
  /** Force a specific platform (useful for testing) */
  forcePlatform?: Platform
}

// Global container instance
let container: AdapterContainer | null = null
let initialized = false

/**
 * Initialize the platform container
 * Should be called once at app startup
 */
export async function initializePlatform(
  options: ContainerOptions = {}
): Promise<AdapterContainer> {
  const platform = options.forcePlatform ?? detectPlatform()

  console.log(`[Platform] Initializing for platform: ${platform}`)

  let newContainer: AdapterContainer

  switch (platform) {
    case 'web':
      newContainer = await initializeWeb(options)
      break
    case 'capacitor-android':
    case 'capacitor-ios':
      newContainer = await initializeCapacitor(options)
      break
    case 'electron':
      newContainer = await initializeElectron(options)
      break
    default:
      // Fallback to web
      newContainer = await initializeWeb(options)
  }

  container = newContainer
  initialized = true

  console.log('[Platform] Container initialized:', {
    storage: container.storage.constructor.name,
    signerType: container.signerType
  })

  return container
}

/**
 * Initialize for Web platform
 */
async function initializeWeb(options: ContainerOptions): Promise<AdapterContainer> {
  const { WebStorage } = await import('@/src/adapters/storage')
  const { hasNosskey, getNosskeySigner, hasNip07, getNip07Signer } =
    await import('@/src/adapters/signing')

  const storage = options.storage ?? new WebStorage()

  // Detect available signer (Nosskey takes priority over NIP-07)
  let signer: SigningAdapter | null = options.signer ?? null
  let signerType: SignerType | null = null

  if (!signer) {
    if (hasNosskey()) {
      try {
        signer = getNosskeySigner()
        signerType = 'nosskey'
      } catch (e) {
        console.warn('[Platform] Failed to initialize Nosskey signer:', e)
      }
    }

    if (!signer && hasNip07()) {
      try {
        signer = getNip07Signer()
        signerType = 'nip07'
      } catch (e) {
        console.warn('[Platform] Failed to initialize NIP-07 signer:', e)
      }
    }
  } else if (options.signer) {
    signerType = options.signer.type
  }

  return { storage, signer, signerType }
}

/**
 * Initialize for Capacitor (Android/iOS)
 */
async function initializeCapacitor(
  options: ContainerOptions
): Promise<AdapterContainer> {
  const { CapacitorStorage, WebStorage } = await import('@/src/adapters/storage')
  const { createAmberSigner, hasNip07, getNip07Signer } =
    await import('@/src/adapters/signing')
  const { isAndroid } = await import('./detect')

  // Try Capacitor Preferences, fall back to WebStorage
  let storage: StorageAdapter
  if (options.storage) {
    storage = options.storage
  } else {
    try {
      storage = new CapacitorStorage()
      // Test if it works
      await storage.keys()
    } catch {
      console.warn('[Platform] CapacitorStorage not available, using WebStorage')
      storage = new WebStorage()
    }
  }

  // Detect available signer
  let signer: SigningAdapter | null = options.signer ?? null
  let signerType: SignerType | null = null

  if (!signer) {
    // On Android, prefer Amber
    if (isAndroid()) {
      // Amber signer requires pubkey to be set later during login
      signer = createAmberSigner()
      signerType = 'amber'
    }

    // Check for injected window.nostr (Amber injects this in WebView)
    if (!signer && hasNip07()) {
      try {
        signer = getNip07Signer()
        signerType = 'nip07'
      } catch (e) {
        console.warn('[Platform] Failed to initialize NIP-07 signer:', e)
      }
    }
  } else if (options.signer) {
    signerType = options.signer.type
  }

  return { storage, signer, signerType }
}

/**
 * Initialize for Electron (Desktop)
 */
async function initializeElectron(
  options: ContainerOptions
): Promise<AdapterContainer> {
  // For now, use Web implementations
  // Electron-specific implementations can be added later
  const { WebStorage } = await import('@/src/adapters/storage')

  const storage = options.storage ?? new WebStorage()

  return {
    storage,
    signer: options.signer ?? null,
    signerType: options.signer?.type ?? null
  }
}

/**
 * Get the current container
 * @throws Error if container is not initialized
 */
export function getContainer(): AdapterContainer {
  if (!container) {
    throw new Error(
      'Platform container not initialized. Call initializePlatform() first.'
    )
  }
  return container
}

/**
 * Check if the platform has been initialized
 */
export function isInitialized(): boolean {
  return initialized
}

/**
 * Get the storage adapter
 * @throws Error if container is not initialized
 */
export function getStorage(): StorageAdapter {
  return getContainer().storage
}

/**
 * Get the signing adapter (may be null if no signer available)
 */
export function getSigner(): SigningAdapter | null {
  return getContainer().signer
}

/**
 * Get the signer type
 */
export function getSignerType(): SignerType | null {
  return getContainer().signerType
}

/**
 * Set a new signer (e.g., after login)
 */
export function setSigner(signer: SigningAdapter | null): void {
  if (!container) {
    throw new Error('Platform container not initialized')
  }
  container.signer = signer
  container.signerType = signer?.type ?? null
}

/**
 * Reset the container (useful for testing or logout)
 */
export function resetContainer(): void {
  container = null
  initialized = false
}

/**
 * Initialize with lazy loading - returns a promise that resolves when ready
 * Safe to call multiple times; only initializes once
 */
let initPromise: Promise<AdapterContainer> | null = null

export function ensureInitialized(
  options?: ContainerOptions
): Promise<AdapterContainer> {
  if (initialized && container) {
    return Promise.resolve(container)
  }

  if (initPromise) {
    return initPromise
  }

  initPromise = initializePlatform(options).finally(() => {
    initPromise = null
  })

  return initPromise
}
