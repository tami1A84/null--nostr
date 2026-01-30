/**
 * Platform Detection
 *
 * Detects the current runtime platform to enable platform-specific behavior.
 *
 * @module platform
 */

/**
 * Supported platform types
 */
export type Platform =
  | 'web'
  | 'capacitor-android'
  | 'capacitor-ios'
  | 'electron'
  | 'unknown'

/**
 * Capacitor interface for type checking
 */
interface CapacitorGlobal {
  getPlatform(): string
  isNativePlatform(): boolean
}

/**
 * Electron interface for type checking
 */
interface ElectronGlobal {
  ipcRenderer?: unknown
}

declare global {
  interface Window {
    Capacitor?: CapacitorGlobal
    electron?: ElectronGlobal
  }
}

// Cache the detected platform
let cachedPlatform: Platform | null = null

/**
 * Detect the current platform
 * @returns The detected platform type
 */
export function detectPlatform(): Platform {
  // Return cached result if available
  if (cachedPlatform !== null) {
    return cachedPlatform
  }

  // SSR check
  if (typeof window === 'undefined') {
    return 'unknown'
  }

  // Check for Capacitor
  if (window.Capacitor !== undefined) {
    try {
      const platform = window.Capacitor.getPlatform()
      if (platform === 'android') {
        cachedPlatform = 'capacitor-android'
        return cachedPlatform
      }
      if (platform === 'ios') {
        cachedPlatform = 'capacitor-ios'
        return cachedPlatform
      }
    } catch (e) {
      console.warn('[Platform] Failed to detect Capacitor platform:', e)
    }
  }

  // Check for Electron
  if (window.electron !== undefined) {
    cachedPlatform = 'electron'
    return cachedPlatform
  }

  // Default to web
  cachedPlatform = 'web'
  return cachedPlatform
}

/**
 * Check if running in a Capacitor environment (Android or iOS)
 */
export function isCapacitor(): boolean {
  const platform = detectPlatform()
  return platform === 'capacitor-android' || platform === 'capacitor-ios'
}

/**
 * Check if running on Android (Capacitor)
 */
export function isAndroid(): boolean {
  return detectPlatform() === 'capacitor-android'
}

/**
 * Check if running on iOS (Capacitor)
 */
export function isIOS(): boolean {
  return detectPlatform() === 'capacitor-ios'
}

/**
 * Check if running in Electron
 */
export function isElectron(): boolean {
  return detectPlatform() === 'electron'
}

/**
 * Check if running in a standard web browser
 */
export function isWeb(): boolean {
  return detectPlatform() === 'web'
}

/**
 * Check if running in SSR (Server-Side Rendering) environment
 */
export function isSSR(): boolean {
  return typeof window === 'undefined'
}

/**
 * Check if running in a native environment (Capacitor or Electron)
 */
export function isNative(): boolean {
  return isCapacitor() || isElectron()
}

/**
 * Check if running in a browser environment (Web or Capacitor WebView)
 */
export function isBrowser(): boolean {
  return typeof window !== 'undefined'
}

/**
 * Get platform-specific user agent info
 */
export function getPlatformInfo(): {
  platform: Platform
  userAgent: string
  isNative: boolean
  isMobile: boolean
} {
  const platform = detectPlatform()
  const userAgent = typeof navigator !== 'undefined' ? navigator.userAgent : ''
  const isMobile = /Android|iPhone|iPad|iPod/i.test(userAgent) || isCapacitor()

  return {
    platform,
    userAgent,
    isNative: isNative(),
    isMobile
  }
}

/**
 * Reset cached platform (useful for testing)
 */
export function resetPlatformCache(): void {
  cachedPlatform = null
}
