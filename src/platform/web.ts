/**
 * Web Platform Initialization
 *
 * Initializes adapters for browser/PWA environments.
 *
 * @module platform/web
 */

import type { StorageAdapter } from '@/src/adapters/storage'
import type { SigningAdapter, SignerType } from '@/src/adapters/signing'
import type { ClipboardAdapter } from '@/src/adapters/clipboard'
import type { NetworkAdapter } from '@/src/adapters/network'

/**
 * Extended container for Web platform
 */
export interface WebAdapterContainer {
  storage: StorageAdapter
  signer: SigningAdapter | null
  signerType: SignerType | null
  clipboard: ClipboardAdapter
  network: NetworkAdapter
}

/**
 * Options for Web platform initialization
 */
export interface WebInitOptions {
  /** Override the default storage adapter */
  storage?: StorageAdapter
  /** Override the default signer */
  signer?: SigningAdapter
}

/**
 * Initialize adapters for Web platform
 */
export async function initializeWeb(
  options: WebInitOptions = {}
): Promise<WebAdapterContainer> {
  // Import adapters
  const { WebStorage } = await import('@/src/adapters/storage')
  const { hasNosskey, getNosskeySigner, hasNip07, getNip07Signer } =
    await import('@/src/adapters/signing')
  const { WebClipboard } = await import('@/src/adapters/clipboard')
  const { WebNetwork } = await import('@/src/adapters/network')

  // Initialize storage
  const storage = options.storage ?? new WebStorage()

  // Initialize clipboard
  const clipboard = new WebClipboard()

  // Initialize network
  const network = new WebNetwork()

  // Detect available signer (Nosskey takes priority over NIP-07)
  let signer: SigningAdapter | null = options.signer ?? null
  let signerType: SignerType | null = null

  if (!signer) {
    if (hasNosskey()) {
      try {
        signer = getNosskeySigner()
        signerType = 'nosskey'
        console.log('[Web] Using Nosskey signer')
      } catch (e) {
        console.warn('[Web] Failed to initialize Nosskey signer:', e)
      }
    }

    if (!signer && hasNip07()) {
      try {
        signer = getNip07Signer()
        signerType = 'nip07'
        console.log('[Web] Using NIP-07 signer')
      } catch (e) {
        console.warn('[Web] Failed to initialize NIP-07 signer:', e)
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
 * Detect available Web signers
 */
export async function detectWebSigners(): Promise<SignerType[]> {
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
