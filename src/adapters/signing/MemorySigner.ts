/**
 * Memory Signer Adapter
 *
 * Implementation for in-memory private key signing.
 * Useful for:
 * - Testing and mocking
 * - Development environments
 * - Temporary sessions where user provides nsec
 *
 * WARNING: Private keys stored in memory can be exposed.
 * Use more secure methods (NIP-07, Nosskey) in production.
 *
 * @module adapters/signing
 */

import {
  generateSecretKey,
  getPublicKey,
  finalizeEvent,
  nip04,
  nip44
} from 'nostr-tools'
import { bytesToHex, hexToBytes } from '@noble/hashes/utils'
import type { Event } from 'nostr-tools'
import type {
  SigningAdapter,
  SignerFeature,
  UnsignedEvent
} from './SigningAdapter'
import { SigningError } from './SigningAdapter'

/**
 * Memory Signer implementation
 */
export class MemorySigner implements SigningAdapter {
  readonly type = 'memory' as const
  private privateKeyBytes: Uint8Array
  private pubkey: string

  /**
   * Create a new MemorySigner
   * @param privateKeyHex - Private key in hex format, or undefined to generate new
   */
  constructor(privateKeyHex?: string) {
    if (privateKeyHex) {
      this.privateKeyBytes = hexToBytes(privateKeyHex)
    } else {
      this.privateKeyBytes = generateSecretKey()
    }
    this.pubkey = getPublicKey(this.privateKeyBytes)
  }

  async getPublicKey(): Promise<string> {
    return this.pubkey
  }

  async signEvent(event: UnsignedEvent): Promise<Event> {
    try {
      // Ensure pubkey is set
      const eventWithPubkey = {
        ...event,
        pubkey: event.pubkey || this.pubkey
      }

      const signedEvent = finalizeEvent(eventWithPubkey, this.privateKeyBytes)
      return signedEvent
    } catch (error) {
      throw new SigningError(
        'Failed to sign event',
        'SIGNING_FAILED',
        error instanceof Error ? error : undefined
      )
    }
  }

  async nip04Encrypt(pubkey: string, plaintext: string): Promise<string> {
    try {
      return await nip04.encrypt(this.privateKeyBytes, pubkey, plaintext)
    } catch (error) {
      throw new SigningError(
        'NIP-04 encryption failed',
        'ENCRYPTION_FAILED',
        error instanceof Error ? error : undefined
      )
    }
  }

  async nip04Decrypt(pubkey: string, ciphertext: string): Promise<string> {
    try {
      return await nip04.decrypt(this.privateKeyBytes, pubkey, ciphertext)
    } catch (error) {
      throw new SigningError(
        'NIP-04 decryption failed',
        'DECRYPTION_FAILED',
        error instanceof Error ? error : undefined
      )
    }
  }

  async nip44Encrypt(pubkey: string, plaintext: string): Promise<string> {
    try {
      const conversationKey = nip44.v2.utils.getConversationKey(
        this.privateKeyBytes,
        pubkey
      )
      return nip44.v2.encrypt(plaintext, conversationKey)
    } catch (error) {
      throw new SigningError(
        'NIP-44 encryption failed',
        'ENCRYPTION_FAILED',
        error instanceof Error ? error : undefined
      )
    }
  }

  async nip44Decrypt(pubkey: string, ciphertext: string): Promise<string> {
    try {
      const conversationKey = nip44.v2.utils.getConversationKey(
        this.privateKeyBytes,
        pubkey
      )
      return nip44.v2.decrypt(ciphertext, conversationKey)
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
        return true
      case 'nip44':
        return true
      case 'getRelays':
        return false
      case 'delegation':
        return true // Can sign delegation events
      default:
        return false
    }
  }

  /**
   * Get the private key in hex format
   * WARNING: Exposing private keys is dangerous
   */
  getPrivateKeyHex(): string {
    return bytesToHex(this.privateKeyBytes)
  }

  /**
   * Securely clear the private key from memory
   */
  async destroy(): Promise<void> {
    // Overwrite with zeros before clearing reference
    this.privateKeyBytes.fill(0)
    this.pubkey = ''
  }
}

/**
 * Create a new MemorySigner with a random private key
 */
export function createRandomSigner(): MemorySigner {
  return new MemorySigner()
}

/**
 * Create a MemorySigner from a hex private key
 */
export function createSignerFromHex(privateKeyHex: string): MemorySigner {
  return new MemorySigner(privateKeyHex)
}

/**
 * Create a MemorySigner from nsec format
 */
export function createSignerFromNsec(nsec: string): MemorySigner {
  // Import nip19 for decoding
  const { nip19 } = require('nostr-tools')
  const { type, data } = nip19.decode(nsec)

  if (type !== 'nsec') {
    throw new SigningError('Invalid nsec format', 'UNKNOWN')
  }

  return new MemorySigner(bytesToHex(data as Uint8Array))
}
