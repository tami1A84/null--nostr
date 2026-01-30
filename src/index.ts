/**
 * Nurunuru Nostr Client - Core Module
 *
 * Platform-agnostic core functionality for the Nostr client.
 *
 * @module src
 */

// Platform abstraction
export * from './platform'

// Adapters
export * from './adapters/storage'
export * from './adapters/signing'

// State management
export * from './core/store'

// Compatibility layer
export * from './lib/compat'
