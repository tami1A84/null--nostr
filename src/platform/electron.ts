/**
 * Electron Platform Initialization
 *
 * Initializes adapters for Electron desktop environments.
 *
 * @module platform/electron
 */

import type { StorageAdapter } from '@/src/adapters/storage'
import type { SigningAdapter, SignerType } from '@/src/adapters/signing'
import type { ClipboardAdapter } from '@/src/adapters/clipboard'
import type { NetworkAdapter } from '@/src/adapters/network'

/**
 * Extended container for Electron platform
 */
export interface ElectronAdapterContainer {
  storage: StorageAdapter
  signer: SigningAdapter | null
  signerType: SignerType | null
  clipboard: ClipboardAdapter
  network: NetworkAdapter
}

/**
 * Options for Electron platform initialization
 */
export interface ElectronInitOptions {
  /** Override the default storage adapter */
  storage?: StorageAdapter
  /** Override the default signer */
  signer?: SigningAdapter
}

/**
 * Initialize adapters for Electron platform
 */
export async function initializeElectron(
  options: ElectronInitOptions = {}
): Promise<ElectronAdapterContainer> {
  // Import adapters
  const { ElectronStorage, WebStorage } = await import('@/src/adapters/storage')
  const { hasNosskey, getNosskeySigner, hasNip07, getNip07Signer } =
    await import('@/src/adapters/signing')
  const { ElectronClipboard, WebClipboard } = await import('@/src/adapters/clipboard')
  const { ElectronNetwork, WebNetwork } = await import('@/src/adapters/network')

  // Initialize storage (try Electron, fall back to Web)
  let storage: StorageAdapter
  if (options.storage) {
    storage = options.storage
  } else {
    const electronStorage = new ElectronStorage()
    if (electronStorage.isUsingElectronAPI()) {
      storage = electronStorage
      console.log('[Electron] Using ElectronStorage')
    } else {
      console.warn('[Electron] ElectronStorage API not available, using WebStorage')
      storage = new WebStorage()
    }
  }

  // Initialize clipboard (try Electron, fall back to Web)
  let clipboard: ClipboardAdapter
  const electronClipboard = new ElectronClipboard()
  if (electronClipboard.isAvailable()) {
    clipboard = electronClipboard
    console.log('[Electron] Using ElectronClipboard')
  } else {
    console.warn('[Electron] ElectronClipboard not available, using WebClipboard')
    clipboard = new WebClipboard()
  }

  // Initialize network (try Electron, fall back to Web)
  let network: NetworkAdapter
  try {
    const electronNetwork = new ElectronNetwork()
    network = electronNetwork
    console.log('[Electron] Using ElectronNetwork')
  } catch {
    console.warn('[Electron] ElectronNetwork not available, using WebNetwork')
    network = new WebNetwork()
  }

  // Detect available signer
  let signer: SigningAdapter | null = options.signer ?? null
  let signerType: SignerType | null = null

  if (!signer) {
    // In Electron, we can use web-based signers
    // Nosskey takes priority over NIP-07
    if (hasNosskey()) {
      try {
        signer = getNosskeySigner()
        signerType = 'nosskey'
        console.log('[Electron] Using Nosskey signer')
      } catch (e) {
        console.warn('[Electron] Failed to initialize Nosskey signer:', e)
      }
    }

    if (!signer && hasNip07()) {
      try {
        signer = getNip07Signer()
        signerType = 'nip07'
        console.log('[Electron] Using NIP-07 signer')
      } catch (e) {
        console.warn('[Electron] Failed to initialize NIP-07 signer:', e)
      }
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
 * Detect available Electron signers
 */
export async function detectElectronSigners(): Promise<SignerType[]> {
  const { hasNosskey, hasNip07 } = await import('@/src/adapters/signing')
  const available: SignerType[] = []

  if (hasNosskey()) {
    available.push('nosskey')
  }

  if (hasNip07()) {
    available.push('nip07')
  }

  return available
}

/**
 * Check if running in Electron environment
 */
export function isElectronEnvironment(): boolean {
  if (typeof window === 'undefined') return false

  const win = window as Window & {
    electron?: unknown
    process?: { type?: string }
  }

  // Check for Electron-specific globals
  return (
    typeof win.electron !== 'undefined' ||
    win.process?.type === 'renderer'
  )
}
