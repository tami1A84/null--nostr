/**
 * Capacitor Platform Initialization
 *
 * Initializes adapters for Capacitor (Android/iOS) environments.
 *
 * @module platform/capacitor
 */

import type { StorageAdapter } from '@/src/adapters/storage'
import type { SigningAdapter, SignerType } from '@/src/adapters/signing'
import type { ClipboardAdapter } from '@/src/adapters/clipboard'
import type { NetworkAdapter } from '@/src/adapters/network'
import { isAndroid, isIOS } from './detect'

/**
 * Extended container for Capacitor platform
 */
export interface CapacitorAdapterContainer {
  storage: StorageAdapter
  signer: SigningAdapter | null
  signerType: SignerType | null
  clipboard: ClipboardAdapter
  network: NetworkAdapter
}

/**
 * Options for Capacitor platform initialization
 */
export interface CapacitorInitOptions {
  /** Override the default storage adapter */
  storage?: StorageAdapter
  /** Override the default signer */
  signer?: SigningAdapter
}

/**
 * Initialize adapters for Capacitor platform
 */
export async function initializeCapacitor(
  options: CapacitorInitOptions = {}
): Promise<CapacitorAdapterContainer> {
  // Import adapters
  const { CapacitorStorage, WebStorage } = await import('@/src/adapters/storage')
  const { createAmberSigner, hasNip07, getNip07Signer } =
    await import('@/src/adapters/signing')
  const { CapacitorClipboard, WebClipboard } = await import('@/src/adapters/clipboard')
  const { CapacitorNetwork, WebNetwork } = await import('@/src/adapters/network')

  // Initialize storage (try Capacitor, fall back to Web)
  let storage: StorageAdapter
  if (options.storage) {
    storage = options.storage
  } else {
    try {
      storage = new CapacitorStorage()
      // Test if it works
      await storage.keys()
      console.log('[Capacitor] Using CapacitorStorage')
    } catch {
      console.warn('[Capacitor] CapacitorStorage not available, using WebStorage')
      storage = new WebStorage()
    }
  }

  // Initialize clipboard (try Capacitor, fall back to Web)
  let clipboard: ClipboardAdapter
  try {
    clipboard = new CapacitorClipboard()
    if (!clipboard.isAvailable()) {
      throw new Error('Not available')
    }
    console.log('[Capacitor] Using CapacitorClipboard')
  } catch {
    console.warn('[Capacitor] CapacitorClipboard not available, using WebClipboard')
    clipboard = new WebClipboard()
  }

  // Initialize network (try Capacitor, fall back to Web)
  let network: NetworkAdapter
  try {
    network = new CapacitorNetwork()
    console.log('[Capacitor] Using CapacitorNetwork')
  } catch {
    console.warn('[Capacitor] CapacitorNetwork not available, using WebNetwork')
    network = new WebNetwork()
  }

  // Detect available signer
  let signer: SigningAdapter | null = options.signer ?? null
  let signerType: SignerType | null = null

  if (!signer) {
    // On Android, prefer Amber
    if (isAndroid()) {
      // Amber signer - pubkey will be set later during login
      signer = createAmberSigner()
      signerType = 'amber'
      console.log('[Capacitor] Using Amber signer (Android)')
    }

    // Check for injected window.nostr (Amber injects this in WebView)
    if (!signer && hasNip07()) {
      try {
        signer = getNip07Signer()
        signerType = 'nip07'
        console.log('[Capacitor] Using NIP-07 signer')
      } catch (e) {
        console.warn('[Capacitor] Failed to initialize NIP-07 signer:', e)
      }
    }

    // iOS doesn't have a standard signer yet
    if (isIOS() && !signer) {
      console.log('[Capacitor] No signer available on iOS')
    }
  } else if (options.signer) {
    signerType = options.signer.type
  }

  return {
    storage,
    signer,
    signerType,
    clipboard,
    network
  }
}

/**
 * Detect available Capacitor signers
 */
export async function detectCapacitorSigners(): Promise<SignerType[]> {
  const { hasNip07 } = await import('@/src/adapters/signing')
  const available: SignerType[] = []

  // Amber is always available on Android (app may not be installed though)
  if (isAndroid()) {
    available.push('amber')
  }

  // Check for injected NIP-07
  if (hasNip07()) {
    available.push('nip07')
  }

  return available
}

/**
 * Check if running in Capacitor environment
 */
export function isCapacitorEnvironment(): boolean {
  if (typeof window === 'undefined') return false
  return typeof (window as Window & { Capacitor?: unknown }).Capacitor !== 'undefined'
}
