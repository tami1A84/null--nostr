/**
 * Adapters Module
 *
 * Cross-platform abstraction layer for:
 * - Storage (localStorage, Capacitor Preferences, electron-store)
 * - Signing (NIP-07, Nosskey, Amber, Bunker)
 * - Clipboard (Web, Capacitor, Electron)
 * - Network (status monitoring)
 *
 * @module adapters
 */

// Storage adapters
export * from './storage'

// Signing adapters
export * from './signing'

// Clipboard adapters
export * from './clipboard'

// Network adapters
export * from './network'
