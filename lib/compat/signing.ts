/**
 * Signing Compatibility Layer
 *
 * Provides functions that work with both old and new signing code.
 * Allows gradual migration from direct window.nostr usage.
 *
 * @module lib/compat/signing
 */

import type { Event } from 'nostr-tools'
import { getSigner, getSignerType, isInitialized } from '@/src/platform'
import {
  hasNip07,
  hasNosskey,
  type SigningAdapter,
  type UnsignedEvent,
  type SignerType
} from '@/src/adapters/signing'

/**
 * Check if any signer is available
 */
export function hasAnySigner(): boolean {
  if (isInitialized()) {
    return getSigner() !== null
  }
  // Fallback to direct checks
  return hasNosskey() || hasNip07()
}

/**
 * Get the current signer type
 */
export function getCurrentSignerType(): SignerType | null {
  if (isInitialized()) {
    return getSignerType()
  }
  // Fallback detection
  if (hasNosskey()) return 'nosskey'
  if (hasNip07()) return 'nip07'
  return null
}

/**
 * Get public key from the current signer
 */
export async function getPublicKey(): Promise<string | null> {
  const signer = isInitialized() ? getSigner() : null

  if (signer) {
    try {
      return await signer.getPublicKey()
    } catch (e) {
      console.warn('[Signing] Failed to get public key:', e)
      return null
    }
  }

  // Fallback to direct window access
  if (typeof window !== 'undefined') {
    if (window.nosskeyManager) {
      try {
        return await window.nosskeyManager.getPublicKey()
      } catch {
        // Ignore
      }
    }
    if (window.nostr) {
      try {
        return await window.nostr.getPublicKey()
      } catch {
        // Ignore
      }
    }
  }

  return null
}

/**
 * Sign an event with the current signer
 */
export async function signEvent(event: UnsignedEvent): Promise<Event> {
  const signer = isInitialized() ? getSigner() : null

  if (signer) {
    return signer.signEvent(event)
  }

  // Fallback to direct window access
  if (typeof window !== 'undefined') {
    if (window.nosskeyManager) {
      return window.nosskeyManager.signEvent(event)
    }
    if (window.nostr) {
      return window.nostr.signEvent(event)
    }
  }

  throw new Error('No signer available')
}

/**
 * Encrypt a message (NIP-44 preferred, falls back to NIP-04)
 */
export async function encrypt(
  pubkey: string,
  plaintext: string
): Promise<string> {
  const signer = isInitialized() ? getSigner() : null

  if (signer) {
    // Try NIP-44 first
    if (signer.supports('nip44')) {
      return signer.nip44Encrypt(pubkey, plaintext)
    }
    // Fall back to NIP-04
    if (signer.supports('nip04')) {
      return signer.nip04Encrypt(pubkey, plaintext)
    }
  }

  // Fallback to direct window access
  if (typeof window !== 'undefined' && window.nostr) {
    if (window.nostr.nip44?.encrypt) {
      return window.nostr.nip44.encrypt(pubkey, plaintext)
    }
    if (window.nostr.nip04?.encrypt) {
      return window.nostr.nip04.encrypt(pubkey, plaintext)
    }
  }

  throw new Error('No encryption method available')
}

/**
 * Decrypt a message (tries NIP-44 first, then NIP-04)
 */
export async function decrypt(
  pubkey: string,
  ciphertext: string
): Promise<string> {
  const signer = isInitialized() ? getSigner() : null

  if (signer) {
    // Try NIP-44 first
    if (signer.supports('nip44')) {
      try {
        return await signer.nip44Decrypt(pubkey, ciphertext)
      } catch {
        // Try NIP-04 as fallback
      }
    }
    // Fall back to NIP-04
    if (signer.supports('nip04')) {
      return signer.nip04Decrypt(pubkey, ciphertext)
    }
  }

  // Fallback to direct window access
  if (typeof window !== 'undefined' && window.nostr) {
    if (window.nostr.nip44?.decrypt) {
      try {
        return await window.nostr.nip44.decrypt(pubkey, ciphertext)
      } catch {
        // Try NIP-04 as fallback
      }
    }
    if (window.nostr.nip04?.decrypt) {
      return window.nostr.nip04.decrypt(pubkey, ciphertext)
    }
  }

  throw new Error('No decryption method available')
}

/**
 * Check if NIP-44 encryption is supported
 */
export function supportsNip44(): boolean {
  const signer = isInitialized() ? getSigner() : null

  if (signer) {
    return signer.supports('nip44')
  }

  // Fallback check
  if (typeof window !== 'undefined' && window.nostr) {
    return !!window.nostr.nip44?.encrypt
  }

  return false
}

/**
 * Check if NIP-04 encryption is supported
 */
export function supportsNip04(): boolean {
  const signer = isInitialized() ? getSigner() : null

  if (signer) {
    return signer.supports('nip04')
  }

  // Fallback check
  if (typeof window !== 'undefined' && window.nostr) {
    return !!window.nostr.nip04?.encrypt
  }

  return false
}

// Re-export types for convenience
export type { SigningAdapter, UnsignedEvent, SignerType }
