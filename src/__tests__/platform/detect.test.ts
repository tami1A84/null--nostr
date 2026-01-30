/**
 * Platform Detection Unit Tests
 *
 * Tests for platform detection functionality including
 * web, Capacitor (Android/iOS), and Electron detection.
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import {
  detectPlatform,
  isWeb,
  isCapacitor,
  isAndroid,
  isIOS,
  isElectron,
  isSSR,
  isNative,
  isBrowser,
  getPlatformInfo,
  resetPlatformCache,
} from '@/src/platform/detect'

describe('Platform Detection', () => {
  beforeEach(() => {
    // Reset cache before each test
    resetPlatformCache()
    // Clear any mocked globals
    delete (window as any).Capacitor
    delete (window as any).electron
  })

  afterEach(() => {
    resetPlatformCache()
  })

  describe('detectPlatform', () => {
    it('should return "web" for standard browser', () => {
      const platform = detectPlatform()
      expect(platform).toBe('web')
    })

    it('should return "capacitor-android" when Capacitor Android is present', () => {
      (window as any).Capacitor = {
        getPlatform: () => 'android',
        isNativePlatform: () => true,
      }

      const platform = detectPlatform()
      expect(platform).toBe('capacitor-android')
    })

    it('should return "capacitor-ios" when Capacitor iOS is present', () => {
      (window as any).Capacitor = {
        getPlatform: () => 'ios',
        isNativePlatform: () => true,
      }

      const platform = detectPlatform()
      expect(platform).toBe('capacitor-ios')
    })

    it('should return "electron" when Electron is present', () => {
      (window as any).electron = {
        ipcRenderer: {},
      }

      const platform = detectPlatform()
      expect(platform).toBe('electron')
    })

    it('should cache the detected platform', () => {
      // First detection should be 'web'
      const platform1 = detectPlatform()
      expect(platform1).toBe('web')

      // Add Capacitor after first detection
      ;(window as any).Capacitor = {
        getPlatform: () => 'android',
        isNativePlatform: () => true,
      }

      // Should still return cached 'web' result
      const platform2 = detectPlatform()
      expect(platform2).toBe('web')
    })

    it('should detect correctly after cache reset', () => {
      // First detection
      detectPlatform()

      // Reset cache
      resetPlatformCache()

      // Now add Capacitor
      ;(window as any).Capacitor = {
        getPlatform: () => 'android',
        isNativePlatform: () => true,
      }

      // Should detect new platform
      const platform = detectPlatform()
      expect(platform).toBe('capacitor-android')
    })

    it('should handle Capacitor getPlatform errors gracefully', () => {
      (window as any).Capacitor = {
        getPlatform: () => {
          throw new Error('getPlatform failed')
        },
        isNativePlatform: () => true,
      }

      // Should fall through to web
      const platform = detectPlatform()
      expect(platform).toBe('web')
    })
  })

  describe('isWeb', () => {
    it('should return true for web platform', () => {
      expect(isWeb()).toBe(true)
    })

    it('should return false for Capacitor platform', () => {
      (window as any).Capacitor = {
        getPlatform: () => 'android',
        isNativePlatform: () => true,
      }
      resetPlatformCache()

      expect(isWeb()).toBe(false)
    })

    it('should return false for Electron platform', () => {
      (window as any).electron = { ipcRenderer: {} }
      resetPlatformCache()

      expect(isWeb()).toBe(false)
    })
  })

  describe('isCapacitor', () => {
    it('should return false for web platform', () => {
      expect(isCapacitor()).toBe(false)
    })

    it('should return true for Capacitor Android', () => {
      (window as any).Capacitor = {
        getPlatform: () => 'android',
        isNativePlatform: () => true,
      }
      resetPlatformCache()

      expect(isCapacitor()).toBe(true)
    })

    it('should return true for Capacitor iOS', () => {
      (window as any).Capacitor = {
        getPlatform: () => 'ios',
        isNativePlatform: () => true,
      }
      resetPlatformCache()

      expect(isCapacitor()).toBe(true)
    })

    it('should return false for Electron', () => {
      (window as any).electron = { ipcRenderer: {} }
      resetPlatformCache()

      expect(isCapacitor()).toBe(false)
    })
  })

  describe('isAndroid', () => {
    it('should return false for web platform', () => {
      expect(isAndroid()).toBe(false)
    })

    it('should return true for Capacitor Android', () => {
      (window as any).Capacitor = {
        getPlatform: () => 'android',
        isNativePlatform: () => true,
      }
      resetPlatformCache()

      expect(isAndroid()).toBe(true)
    })

    it('should return false for Capacitor iOS', () => {
      (window as any).Capacitor = {
        getPlatform: () => 'ios',
        isNativePlatform: () => true,
      }
      resetPlatformCache()

      expect(isAndroid()).toBe(false)
    })
  })

  describe('isIOS', () => {
    it('should return false for web platform', () => {
      expect(isIOS()).toBe(false)
    })

    it('should return false for Capacitor Android', () => {
      (window as any).Capacitor = {
        getPlatform: () => 'android',
        isNativePlatform: () => true,
      }
      resetPlatformCache()

      expect(isIOS()).toBe(false)
    })

    it('should return true for Capacitor iOS', () => {
      (window as any).Capacitor = {
        getPlatform: () => 'ios',
        isNativePlatform: () => true,
      }
      resetPlatformCache()

      expect(isIOS()).toBe(true)
    })
  })

  describe('isElectron', () => {
    it('should return false for web platform', () => {
      expect(isElectron()).toBe(false)
    })

    it('should return false for Capacitor platform', () => {
      (window as any).Capacitor = {
        getPlatform: () => 'android',
        isNativePlatform: () => true,
      }
      resetPlatformCache()

      expect(isElectron()).toBe(false)
    })

    it('should return true for Electron platform', () => {
      (window as any).electron = { ipcRenderer: {} }
      resetPlatformCache()

      expect(isElectron()).toBe(true)
    })
  })

  describe('isSSR', () => {
    it('should return false when window is defined', () => {
      expect(isSSR()).toBe(false)
    })

    // Note: Testing true case requires modifying window which is difficult in jsdom
    // The implementation correctly checks typeof window === 'undefined'
  })

  describe('isNative', () => {
    it('should return false for web platform', () => {
      expect(isNative()).toBe(false)
    })

    it('should return true for Capacitor Android', () => {
      (window as any).Capacitor = {
        getPlatform: () => 'android',
        isNativePlatform: () => true,
      }
      resetPlatformCache()

      expect(isNative()).toBe(true)
    })

    it('should return true for Capacitor iOS', () => {
      (window as any).Capacitor = {
        getPlatform: () => 'ios',
        isNativePlatform: () => true,
      }
      resetPlatformCache()

      expect(isNative()).toBe(true)
    })

    it('should return true for Electron', () => {
      (window as any).electron = { ipcRenderer: {} }
      resetPlatformCache()

      expect(isNative()).toBe(true)
    })
  })

  describe('isBrowser', () => {
    it('should return true when window is defined', () => {
      expect(isBrowser()).toBe(true)
    })
  })

  describe('getPlatformInfo', () => {
    it('should return platform info for web', () => {
      const info = getPlatformInfo()

      expect(info.platform).toBe('web')
      expect(info.isNative).toBe(false)
      expect(typeof info.userAgent).toBe('string')
      expect(typeof info.isMobile).toBe('boolean')
    })

    it('should return platform info for Capacitor Android', () => {
      (window as any).Capacitor = {
        getPlatform: () => 'android',
        isNativePlatform: () => true,
      }
      resetPlatformCache()

      const info = getPlatformInfo()

      expect(info.platform).toBe('capacitor-android')
      expect(info.isNative).toBe(true)
      expect(info.isMobile).toBe(true)
    })

    it('should return platform info for Capacitor iOS', () => {
      (window as any).Capacitor = {
        getPlatform: () => 'ios',
        isNativePlatform: () => true,
      }
      resetPlatformCache()

      const info = getPlatformInfo()

      expect(info.platform).toBe('capacitor-ios')
      expect(info.isNative).toBe(true)
      expect(info.isMobile).toBe(true)
    })

    it('should return platform info for Electron', () => {
      (window as any).electron = { ipcRenderer: {} }
      resetPlatformCache()

      const info = getPlatformInfo()

      expect(info.platform).toBe('electron')
      expect(info.isNative).toBe(true)
      expect(info.isMobile).toBe(false)
    })

    it('should detect mobile from user agent', () => {
      // Mock mobile user agent
      Object.defineProperty(navigator, 'userAgent', {
        value: 'Mozilla/5.0 (iPhone; CPU iPhone OS 14_0 like Mac OS X)',
        writable: true,
        configurable: true,
      })
      resetPlatformCache()

      const info = getPlatformInfo()
      expect(info.isMobile).toBe(true)
    })
  })

  describe('resetPlatformCache', () => {
    it('should allow re-detection after reset', () => {
      // Ensure clean state
      resetPlatformCache()
      delete (window as any).Capacitor
      delete (window as any).electron

      // First detection: web
      const platform1 = detectPlatform()
      expect(platform1).toBe('web')

      // Reset cache before adding Capacitor
      resetPlatformCache()

      // Add Capacitor
      ;(window as any).Capacitor = {
        getPlatform: () => 'android',
        isNativePlatform: () => true,
      }

      // Now should detect Capacitor
      expect(detectPlatform()).toBe('capacitor-android')
    })
  })
})
