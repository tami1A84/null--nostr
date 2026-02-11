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
import type { ClipboardAdapter } from '@/src/adapters/clipboard'
import type { NetworkAdapter } from '@/src/adapters/network'
import { detectPlatform, type Platform } from './detect'

/**
 * Container holding all platform adapters
 */
export interface AdapterContainer {
  storage: StorageAdapter
  signer: SigningAdapter | null
  signerType: SignerType | null
  clipboard: ClipboardAdapter
  network: NetworkAdapter
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
    case 'web': {
      const { initializeWeb } = await import('./web')
      newContainer = await initializeWeb(options)
      break
    }
    case 'electron': {
      const { initializeElectron } = await import('./electron')
      newContainer = await initializeElectron(options)
      break
    }
    default: {
      // Fallback to web
      const { initializeWeb } = await import('./web')
      newContainer = await initializeWeb(options)
    }
  }

  container = newContainer
  initialized = true

  console.log('[Platform] Container initialized:', {
    storage: container.storage.constructor.name,
    signerType: container.signerType,
    clipboard: container.clipboard.constructor.name,
    network: container.network.constructor.name
  })

  return container
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
 * Get the clipboard adapter
 */
export function getClipboard(): ClipboardAdapter {
  return getContainer().clipboard
}

/**
 * Get the network adapter
 */
export function getNetwork(): NetworkAdapter {
  return getContainer().network
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
  // Clean up network adapter if it has a destroy method
  if (container?.network && 'destroy' in container.network) {
    try {
      (container.network as NetworkAdapter & { destroy: () => void }).destroy()
    } catch {
      // Ignore cleanup errors
    }
  }

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

/**
 * Get available signers for the current platform
 */
export async function getAvailableSigners(): Promise<SignerType[]> {
  const platform = detectPlatform()

  switch (platform) {
    case 'web': {
      const { detectWebSigners } = await import('./web')
      return detectWebSigners()
    }
    case 'electron': {
      const { detectElectronSigners } = await import('./electron')
      return detectElectronSigners()
    }
    default: {
      const { detectWebSigners } = await import('./web')
      return detectWebSigners()
    }
  }
}
