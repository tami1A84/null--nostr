/**
 * NIP-07 Signer Adapter
 *
 * Implementation for browser extensions that implement NIP-07:
 * - nos2x
 * - Alby
 * - Flamingo
 * - etc.
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
 * NIP-07 window.nostr interface
 */
interface Nip07Extension {
  getPublicKey(): Promise<string>
  signEvent(event: UnsignedEvent): Promise<Event>
  getRelays?(): Promise<Record<string, { read: boolean; write: boolean }>>
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
    nostr?: Nip07Extension
  }
}

/**
 * Check if NIP-07 extension is available
 */
export function hasNip07(): boolean {
  return typeof window !== 'undefined' && window.nostr !== undefined
}

/**
 * NIP-07 Signer implementation
 */
export class Nip07Signer implements SigningAdapter {
  readonly type = 'nip07' as const

  private get nostr(): Nip07Extension {
    if (typeof window === 'undefined' || !window.nostr) {
      throw new SigningError(
        'NIP-07 extension not available',
        'NOT_AVAILABLE'
      )
    }
    return window.nostr
  }

  async getPublicKey(): Promise<string> {
    try {
      return await this.nostr.getPublicKey()
    } catch (error) {
      if (error instanceof SigningError) throw error
      throw new SigningError(
        'Failed to get public key from NIP-07 extension',
        'UNKNOWN',
        error instanceof Error ? error : undefined
      )
    }
  }

  async signEvent(event: UnsignedEvent): Promise<Event> {
    try {
      const signedEvent = await this.nostr.signEvent(event)
      return signedEvent
    } catch (error) {
      if (error instanceof SigningError) throw error

      // Check for user rejection
      const errorMessage = error instanceof Error ? error.message : String(error)
      if (errorMessage.toLowerCase().includes('rejected') ||
          errorMessage.toLowerCase().includes('denied') ||
          errorMessage.toLowerCase().includes('cancelled')) {
        throw new SigningError('User rejected signing', 'USER_REJECTED')
      }

      throw new SigningError(
        'Failed to sign event with NIP-07 extension',
        'SIGNING_FAILED',
        error instanceof Error ? error : undefined
      )
    }
  }

  async nip04Encrypt(pubkey: string, plaintext: string): Promise<string> {
    if (!this.nostr.nip04?.encrypt) {
      throw new SigningError(
        'NIP-04 encryption not supported by this extension',
        'NOT_SUPPORTED'
      )
    }

    try {
      return await this.nostr.nip04.encrypt(pubkey, plaintext)
    } catch (error) {
      throw new SigningError(
        'NIP-04 encryption failed',
        'ENCRYPTION_FAILED',
        error instanceof Error ? error : undefined
      )
    }
  }

  async nip04Decrypt(pubkey: string, ciphertext: string): Promise<string> {
    if (!this.nostr.nip04?.decrypt) {
      throw new SigningError(
        'NIP-04 decryption not supported by this extension',
        'NOT_SUPPORTED'
      )
    }

    try {
      return await this.nostr.nip04.decrypt(pubkey, ciphertext)
    } catch (error) {
      throw new SigningError(
        'NIP-04 decryption failed',
        'DECRYPTION_FAILED',
        error instanceof Error ? error : undefined
      )
    }
  }

  async nip44Encrypt(pubkey: string, plaintext: string): Promise<string> {
    if (!this.nostr.nip44?.encrypt) {
      throw new SigningError(
        'NIP-44 encryption not supported by this extension',
        'NOT_SUPPORTED'
      )
    }

    try {
      return await this.nostr.nip44.encrypt(pubkey, plaintext)
    } catch (error) {
      throw new SigningError(
        'NIP-44 encryption failed',
        'ENCRYPTION_FAILED',
        error instanceof Error ? error : undefined
      )
    }
  }

  async nip44Decrypt(pubkey: string, ciphertext: string): Promise<string> {
    if (!this.nostr.nip44?.decrypt) {
      throw new SigningError(
        'NIP-44 decryption not supported by this extension',
        'NOT_SUPPORTED'
      )
    }

    try {
      return await this.nostr.nip44.decrypt(pubkey, ciphertext)
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
        return !!this.nostr.nip04
      case 'nip44':
        return !!this.nostr.nip44
      case 'getRelays':
        return typeof this.nostr.getRelays === 'function'
      case 'delegation':
        return false // NIP-07 doesn't specify delegation support
      default:
        return false
    }
  }

  async getRelays(): Promise<Record<string, { read: boolean; write: boolean }>> {
    if (!this.nostr.getRelays) {
      throw new SigningError(
        'getRelays not supported by this extension',
        'NOT_SUPPORTED'
      )
    }

    try {
      return await this.nostr.getRelays()
    } catch (error) {
      throw new SigningError(
        'Failed to get relays from NIP-07 extension',
        'UNKNOWN',
        error instanceof Error ? error : undefined
      )
    }
  }
}

/**
 * Singleton instance
 */
let nip07SignerInstance: Nip07Signer | null = null

/**
 * Get the singleton Nip07Signer instance
 * @throws SigningError if NIP-07 extension is not available
 */
export function getNip07Signer(): Nip07Signer {
  if (!hasNip07()) {
    throw new SigningError('NIP-07 extension not available', 'NOT_AVAILABLE')
  }
  if (!nip07SignerInstance) {
    nip07SignerInstance = new Nip07Signer()
  }
  return nip07SignerInstance
}
