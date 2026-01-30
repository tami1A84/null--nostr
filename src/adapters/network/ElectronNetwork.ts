/**
 * Electron Network Adapter
 *
 * Uses Electron's net module and IPC for desktop network monitoring.
 * Falls back to Web APIs when Electron APIs are not available.
 *
 * @module adapters/network
 */

import {
  NetworkAdapter,
  NetworkStatus,
  NetworkStatusListener,
  NetworkConnectionType
} from './NetworkAdapter'

// Type for Electron network API (exposed via preload)
interface ElectronNetworkAPI {
  isOnline(): boolean
  getConnectionType?(): NetworkConnectionType
  onStatusChange?(callback: (online: boolean) => void): () => void
}

/**
 * Get Electron network API from window context
 */
function getElectronNetwork(): ElectronNetworkAPI | null {
  if (typeof window === 'undefined') return null

  const win = window as Window & {
    electronNetwork?: ElectronNetworkAPI
    electron?: { network?: ElectronNetworkAPI }
  }

  return win.electronNetwork ?? win.electron?.network ?? null
}

/**
 * Electron implementation of NetworkAdapter
 */
export class ElectronNetwork implements NetworkAdapter {
  private electronApi: ElectronNetworkAPI | null
  private listeners: Set<NetworkStatusListener> = new Set()
  private boundOnlineHandler: () => void
  private boundOfflineHandler: () => void
  private electronUnsubscribe: (() => void) | null = null

  constructor() {
    this.electronApi = getElectronNetwork()

    this.boundOnlineHandler = () => this.notifyListeners()
    this.boundOfflineHandler = () => this.notifyListeners()

    // Set up window event listeners as fallback
    if (typeof window !== 'undefined') {
      window.addEventListener('online', this.boundOnlineHandler)
      window.addEventListener('offline', this.boundOfflineHandler)
    }

    // Set up Electron-specific listener if available
    if (this.electronApi?.onStatusChange) {
      this.electronUnsubscribe = this.electronApi.onStatusChange(() => {
        this.notifyListeners()
      })
    }
  }

  /**
   * Get current network status
   */
  async getStatus(): Promise<NetworkStatus> {
    let connected: boolean
    let connectionType: NetworkConnectionType

    if (this.electronApi) {
      connected = this.electronApi.isOnline()
      connectionType = this.electronApi.getConnectionType?.() ?? (connected ? 'unknown' : 'none')
    } else if (typeof navigator !== 'undefined') {
      connected = navigator.onLine
      connectionType = connected ? 'unknown' : 'none'
    } else {
      connected = false
      connectionType = 'unknown'
    }

    return {
      connected,
      connectionType,
      // Desktop connections are typically not metered
      isMetered: false
    }
  }

  /**
   * Check if online
   */
  async isOnline(): Promise<boolean> {
    if (this.electronApi) {
      return this.electronApi.isOnline()
    }
    if (typeof navigator !== 'undefined') {
      return navigator.onLine
    }
    return false
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
   * Notify all listeners
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
   * Perform connectivity check
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
   * Clean up
   */
  destroy(): void {
    if (typeof window !== 'undefined') {
      window.removeEventListener('online', this.boundOnlineHandler)
      window.removeEventListener('offline', this.boundOfflineHandler)
    }

    if (this.electronUnsubscribe) {
      this.electronUnsubscribe()
      this.electronUnsubscribe = null
    }

    this.listeners.clear()
  }
}
