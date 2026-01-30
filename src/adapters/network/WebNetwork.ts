/**
 * Web Network Adapter
 *
 * Uses the Navigator.onLine API and Network Information API
 * for browser environments.
 *
 * @module adapters/network
 */

import {
  NetworkAdapter,
  NetworkStatus,
  NetworkStatusListener,
  NetworkConnectionType
} from './NetworkAdapter'

/**
 * Navigator connection type from Network Information API
 */
interface NetworkInformation {
  effectiveType?: string
  type?: string
  saveData?: boolean
  addEventListener?(type: string, listener: () => void): void
  removeEventListener?(type: string, listener: () => void): void
}

/**
 * Get connection type from Network Information API
 */
function getConnectionType(): NetworkConnectionType {
  if (typeof navigator === 'undefined') return 'unknown'

  const connection = (navigator as Navigator & { connection?: NetworkInformation }).connection

  if (!connection) {
    return navigator.onLine ? 'unknown' : 'none'
  }

  const type = connection.type || connection.effectiveType

  switch (type) {
    case 'wifi':
      return 'wifi'
    case 'cellular':
    case '4g':
    case '3g':
    case '2g':
    case 'slow-2g':
      return 'cellular'
    case 'ethernet':
      return 'ethernet'
    case 'none':
      return 'none'
    default:
      return navigator.onLine ? 'unknown' : 'none'
  }
}

/**
 * Check if connection is metered
 */
function isMetered(): boolean {
  if (typeof navigator === 'undefined') return false

  const connection = (navigator as Navigator & { connection?: NetworkInformation }).connection
  if (!connection) return false

  // saveData indicates user wants to save data
  if (connection.saveData) return true

  // Cellular connections are typically metered
  const type = connection.type || connection.effectiveType
  return ['cellular', '4g', '3g', '2g', 'slow-2g'].includes(type || '')
}

/**
 * Web implementation of NetworkAdapter
 */
export class WebNetwork implements NetworkAdapter {
  private listeners: Set<NetworkStatusListener> = new Set()
  private boundOnlineHandler: () => void
  private boundOfflineHandler: () => void
  private boundConnectionHandler: (() => void) | null = null

  constructor() {
    this.boundOnlineHandler = () => this.notifyListeners()
    this.boundOfflineHandler = () => this.notifyListeners()

    // Set up event listeners
    if (typeof window !== 'undefined') {
      window.addEventListener('online', this.boundOnlineHandler)
      window.addEventListener('offline', this.boundOfflineHandler)

      // Listen for connection changes if available
      const connection = (navigator as Navigator & { connection?: NetworkInformation }).connection
      if (connection?.addEventListener) {
        this.boundConnectionHandler = () => this.notifyListeners()
        connection.addEventListener('change', this.boundConnectionHandler)
      }
    }
  }

  /**
   * Get current network status
   */
  async getStatus(): Promise<NetworkStatus> {
    if (typeof navigator === 'undefined') {
      return {
        connected: false,
        connectionType: 'unknown'
      }
    }

    return {
      connected: navigator.onLine,
      connectionType: getConnectionType(),
      isMetered: isMetered()
    }
  }

  /**
   * Check if online
   */
  async isOnline(): Promise<boolean> {
    if (typeof navigator === 'undefined') return false
    return navigator.onLine
  }

  /**
   * Add status listener
   */
  addStatusListener(listener: NetworkStatusListener): () => void {
    this.listeners.add(listener)
    return () => this.removeStatusListener(listener)
  }

  /**
   * Remove status listener
   */
  removeStatusListener(listener: NetworkStatusListener): void {
    this.listeners.delete(listener)
  }

  /**
   * Notify all listeners of status change
   */
  private async notifyListeners(): Promise<void> {
    const status = await this.getStatus()
    this.listeners.forEach(listener => {
      try {
        listener(status)
      } catch {
        // Ignore listener errors
      }
    })
  }

  /**
   * Perform connectivity check by fetching a small resource
   */
  async checkConnectivity(url: string = 'https://www.google.com/favicon.ico'): Promise<boolean> {
    try {
      const controller = new AbortController()
      const timeoutId = setTimeout(() => controller.abort(), 5000)

      const response = await fetch(url, {
        method: 'HEAD',
        mode: 'no-cors',
        cache: 'no-store',
        signal: controller.signal
      })

      clearTimeout(timeoutId)
      return response.ok || response.type === 'opaque'
    } catch {
      return false
    }
  }

  /**
   * Clean up event listeners
   */
  destroy(): void {
    if (typeof window !== 'undefined') {
      window.removeEventListener('online', this.boundOnlineHandler)
      window.removeEventListener('offline', this.boundOfflineHandler)

      if (this.boundConnectionHandler) {
        const connection = (navigator as Navigator & { connection?: NetworkInformation }).connection
        if (connection?.removeEventListener) {
          connection.removeEventListener('change', this.boundConnectionHandler)
        }
      }
    }

    this.listeners.clear()
  }
}
