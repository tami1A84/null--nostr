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
  setSigner,
  resetContainer,
  ensureInitialized,
  type AdapterContainer,
  type ContainerOptions
} from './container'
