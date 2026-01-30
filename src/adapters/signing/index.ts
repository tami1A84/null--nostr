/**
 * Signing Adapters
 *
 * Cross-platform signing abstraction layer for Nostr events.
 *
 * @module adapters/signing
 */

// Types and interfaces
export type {
  SigningAdapter,
  SignerType,
  SignerFeature,
  UnsignedEvent,
  SigningErrorCode
} from './SigningAdapter'
export { SigningError } from './SigningAdapter'

// Implementations
export { Nip07Signer, getNip07Signer, hasNip07 } from './Nip07Signer'
export { NosskeySigner, getNosskeySigner, hasNosskey } from './NosskeySigner'
export {
  AmberSigner,
  createAmberSigner,
  handleAmberSignCallback,
  isAmberEnvironment
} from './AmberSigner'
export {
  MemorySigner,
  createRandomSigner,
  createSignerFromHex,
  createSignerFromNsec
} from './MemorySigner'
