/**
 * Platform Module
 *
 * Cross-platform abstraction layer providing:
 * - Platform detection
 * - Adapter container (DI)
 * - Platform-specific initialization
 *
 * @module platform
 */

// Platform detection
export {
  detectPlatform,
  isCapacitor,
  isAndroid,
  isIOS,
  isElectron,
  isWeb,
  isSSR,
  isNative,
  isBrowser,
  getPlatformInfo,
  resetPlatformCache,
  type Platform
} from './detect'

// Container (DI)
export {
  initializePlatform,
  getContainer,
  isInitialized,
  getStorage,
  getSigner,
  getSignerType,
  getClipboard,
  getNetwork,
  setSigner,
  resetContainer,
  ensureInitialized,
  getAvailableSigners,
  type AdapterContainer,
  type ContainerOptions
} from './container'

// Platform-specific initializers
export { initializeWeb, detectWebSigners, type WebAdapterContainer } from './web'
export { initializeCapacitor, detectCapacitorSigners, isCapacitorEnvironment, type CapacitorAdapterContainer } from './capacitor'
export { initializeElectron, detectElectronSigners, isElectronEnvironment, type ElectronAdapterContainer } from './electron'
