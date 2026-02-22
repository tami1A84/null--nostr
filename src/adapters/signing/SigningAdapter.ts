/**
 * Signing Adapter Interface
 *
 * Provides a unified interface for Nostr event signing across different methods:
 * - NIP-07 browser extensions (nos2x, Alby, etc.)
 * - Nosskey (Passkey-based signing)
 * - Amber (Android signer app)
 * - NIP-46 Remote Signer (Bunker)
 * - Memory/nsec (direct private key)
 *
 * @module adapters/signing
 */

import type { Event } from 'nostr-tools'

/**
 * Unsigned event type (without id and sig)
 */
export interface UnsignedEvent {
  kind: number
  created_at: number
  tags: string[][]
  content: string
  pubkey?: string
}

/**
 * Signer types supported by the application
 */
export type SignerType =
  | 'nip07'
  | 'nosskey'
  | 'amber'
  | 'bunker'
  | 'memory'
  | 'nostr-connect'

/**
 * Features that signers may support
 */
export type SignerFeature =
  | 'nip04'       // Legacy encryption (NIP-04)
  | 'nip44'       // Modern encryption (NIP-44)
  | 'delegation'  // Event delegation (NIP-26)
  | 'getRelays'   // Get user relays

/**
 * Signing adapter interface for cross-platform signing abstraction
 */
export interface SigningAdapter {
  /**
   * The type of this signer
   */
  readonly type: SignerType

  /**
   * Get the public key of the signer
   * @returns The public key in hex format
   */
  getPublicKey(): Promise<string>

  /**
   * Sign a Nostr event
   * @param event - The unsigned event to sign
   * @returns The signed event with id and sig
   */
  signEvent(event: UnsignedEvent): Promise<Event>

  /**
   * Encrypt a message using NIP-04
   * @param pubkey - Recipient's public key
   * @param plaintext - Message to encrypt
   * @returns Encrypted message
   */
  nip04Encrypt(pubkey: string, plaintext: string): Promise<string>

  /**
   * Decrypt a message using NIP-04
   * @param pubkey - Sender's public key
   * @param ciphertext - Encrypted message
   * @returns Decrypted plaintext
   */
  nip04Decrypt(pubkey: string, ciphertext: string): Promise<string>

  /**
   * Encrypt a message using NIP-44
   * @param pubkey - Recipient's public key
   * @param plaintext - Message to encrypt
   * @returns Encrypted message
   */
  nip44Encrypt(pubkey: string, plaintext: string): Promise<string>

  /**
   * Decrypt a message using NIP-44
   * @param pubkey - Sender's public key
   * @param ciphertext - Encrypted message
   * @returns Decrypted plaintext
   */
  nip44Decrypt(pubkey: string, ciphertext: string): Promise<string>

  /**
   * Check if this signer supports a specific feature
   * @param feature - The feature to check
   * @returns True if the feature is supported
   */
  supports(feature: SignerFeature): boolean

  /**
   * Get user relays (if supported)
   * @returns Map of relay URLs to read/write permissions
   */
  getRelays?(): Promise<Record<string, { read: boolean; write: boolean }>>

  /**
   * Clean up any resources held by the signer
   */
  destroy?(): Promise<void>
}

/**
 * Error thrown when signing operations fail
 */
export class SigningError extends Error {
  constructor(
    message: string,
    public readonly code: SigningErrorCode,
    public readonly cause?: Error
  ) {
    super(message)
    this.name = 'SigningError'
  }
}

/**
 * Signing error codes
 */
export type SigningErrorCode =
  | 'NOT_AVAILABLE'    // Signer not available
  | 'USER_REJECTED'    // User rejected the operation
  | 'NOT_SUPPORTED'    // Feature not supported
  | 'ENCRYPTION_FAILED' // Encryption failed
  | 'DECRYPTION_FAILED' // Decryption failed
  | 'SIGNING_FAILED'   // Signing failed
  | 'TIMEOUT'          // Operation timed out
  | 'UNKNOWN'          // Unknown error
