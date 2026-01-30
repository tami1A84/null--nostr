/**
 * Nosskey Signer Adapter
 *
 * Implementation for Passkey-based signing using nosskey-sdk.
 * Provides a secure, device-based signing method without exposing private keys.
 *
 * @module adapters/signing
 */

import type { Event } from 'nostr-tools'
import type {
  SigningAdapter,
  SignerFeature,
  UnsignedEvent
} from './SigningAdapter'
import { SigningError } from './SigningAdapter'

/**
 * Nosskey Manager interface (from nosskey-sdk)
 */
interface NosskeyManager {
  getPublicKey(): Promise<string>
  signEvent(event: UnsignedEvent): Promise<Event>
  nip04?: {
    encrypt(pubkey: string, plaintext: string): Promise<string>
    decrypt(pubkey: string, ciphertext: string): Promise<string>
  }
  nip44?: {
    encrypt(pubkey: string, plaintext: string): Promise<string>
    decrypt(pubkey: string, ciphertext: string): Promise<string>
  }
}

declare global {
  interface Window {
    nosskeyManager?: NosskeyManager
  }
}

/**
 * Check if Nosskey is available
 */
export function hasNosskey(): boolean {
  return typeof window !== 'undefined' && window.nosskeyManager !== undefined
}

/**
 * Nosskey Signer implementation
 */
export class NosskeySigner implements SigningAdapter {
  readonly type = 'nosskey' as const

  private get manager(): NosskeyManager {
    if (typeof window === 'undefined' || !window.nosskeyManager) {
      throw new SigningError(
        'Nosskey manager not available',
        'NOT_AVAILABLE'
      )
    }
    return window.nosskeyManager
  }

  async getPublicKey(): Promise<string> {
    try {
      return await this.manager.getPublicKey()
    } catch (error) {
      if (error instanceof SigningError) throw error
      throw new SigningError(
        'Failed to get public key from Nosskey',
        'UNKNOWN',
        error instanceof Error ? error : undefined
      )
    }
  }

  async signEvent(event: UnsignedEvent): Promise<Event> {
    try {
      const signedEvent = await this.manager.signEvent(event)
      return signedEvent
    } catch (error) {
      if (error instanceof SigningError) throw error

      const errorMessage = error instanceof Error ? error.message : String(error)
      if (errorMessage.toLowerCase().includes('cancelled') ||
          errorMessage.toLowerCase().includes('aborted')) {
        throw new SigningError('User cancelled signing', 'USER_REJECTED')
      }

      throw new SigningError(
        'Failed to sign event with Nosskey',
        'SIGNING_FAILED',
        error instanceof Error ? error : undefined
      )
    }
  }

  async nip04Encrypt(pubkey: string, plaintext: string): Promise<string> {
    if (!this.manager.nip04?.encrypt) {
      throw new SigningError(
        'NIP-04 encryption not supported by Nosskey',
        'NOT_SUPPORTED'
      )
    }

    try {
      return await this.manager.nip04.encrypt(pubkey, plaintext)
    } catch (error) {
      throw new SigningError(
        'NIP-04 encryption failed',
        'ENCRYPTION_FAILED',
        error instanceof Error ? error : undefined
      )
    }
  }

  async nip04Decrypt(pubkey: string, ciphertext: string): Promise<string> {
    if (!this.manager.nip04?.decrypt) {
      throw new SigningError(
        'NIP-04 decryption not supported by Nosskey',
        'NOT_SUPPORTED'
      )
    }

    try {
      return await this.manager.nip04.decrypt(pubkey, ciphertext)
    } catch (error) {
      throw new SigningError(
        'NIP-04 decryption failed',
        'DECRYPTION_FAILED',
        error instanceof Error ? error : undefined
      )
    }
  }

  async nip44Encrypt(pubkey: string, plaintext: string): Promise<string> {
    if (!this.manager.nip44?.encrypt) {
      throw new SigningError(
        'NIP-44 encryption not supported by Nosskey',
        'NOT_SUPPORTED'
      )
    }

    try {
      return await this.manager.nip44.encrypt(pubkey, plaintext)
    } catch (error) {
      throw new SigningError(
        'NIP-44 encryption failed',
        'ENCRYPTION_FAILED',
        error instanceof Error ? error : undefined
      )
    }
  }

  async nip44Decrypt(pubkey: string, ciphertext: string): Promise<string> {
    if (!this.manager.nip44?.decrypt) {
      throw new SigningError(
        'NIP-44 decryption not supported by Nosskey',
        'NOT_SUPPORTED'
      )
    }

    try {
      return await this.manager.nip44.decrypt(pubkey, ciphertext)
    } catch (error) {
      throw new SigningError(
        'NIP-44 decryption failed',
        'DECRYPTION_FAILED',
        error instanceof Error ? error : undefined
      )
    }
  }

  supports(feature: SignerFeature): boolean {
    switch (feature) {
      case 'nip04':
        return !!this.manager.nip04
      case 'nip44':
        return !!this.manager.nip44
      case 'getRelays':
        return false
      case 'delegation':
        return false
      default:
        return false
    }
  }
}

/**
 * Singleton instance
 */
let nosskeySignerInstance: NosskeySigner | null = null

/**
 * Get the singleton NosskeySigner instance
 * @throws SigningError if Nosskey is not available
 */
export function getNosskeySigner(): NosskeySigner {
  if (!hasNosskey()) {
    throw new SigningError('Nosskey not available', 'NOT_AVAILABLE')
  }
  if (!nosskeySignerInstance) {
    nosskeySignerInstance = new NosskeySigner()
  }
  return nosskeySignerInstance
}
