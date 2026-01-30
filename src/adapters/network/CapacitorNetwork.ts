/**
 * Capacitor Network Adapter
 *
 * Uses the Capacitor Network plugin for native mobile environments.
 * Provides more accurate network status on Android and iOS.
 *
 * @module adapters/network
 */

import {
  NetworkAdapter,
  NetworkStatus,
  NetworkStatusListener,
  NetworkConnectionType
} from './NetworkAdapter'

// Types for Capacitor Network plugin
interface CapacitorNetworkStatus {
  connected: boolean
  connectionType: 'wifi' | 'cellular' | 'none' | 'unknown'
}

interface CapacitorNetworkPlugin {
  getStatus(): Promise<CapacitorNetworkStatus>
  addListener(
    eventName: 'networkStatusChange',
    listener: (status: CapacitorNetworkStatus) => void
  ): Promise<{ remove: () => Promise<void> }>
}

/**
 * Capacitor implementation of NetworkAdapter
 */
export class CapacitorNetwork implements NetworkAdapter {
  private plugin: CapacitorNetworkPlugin | null = null
  private listeners: Set<NetworkStatusListener> = new Set()
  private pluginListener: { remove: () => Promise<void> } | null = null
  private initialized = false

  constructor() {
    this.initPlugin()
  }

  /**
   * Initialize the Capacitor Network plugin
   */
  private async initPlugin(): Promise<void> {
    if (this.initialized) return

    try {
      const { Network } = await import('@capacitor/network')
      this.plugin = Network as unknown as CapacitorNetworkPlugin

      // Set up native listener
      this.pluginListener = await this.plugin.addListener(
        'networkStatusChange',
        (status) => this.notifyListeners(this.mapStatus(status))
      )

      this.initialized = true
    } catch {
      // Plugin not available
      console.warn('[CapacitorNetwork] Plugin not available')
    }
  }

  /**
   * Map Capacitor status to our NetworkStatus
   */
  private mapStatus(status: CapacitorNetworkStatus): NetworkStatus {
    return {
      connected: status.connected,
      connectionType: status.connectionType as NetworkConnectionType,
      isMetered: status.connectionType === 'cellular'
    }
  }

  /**
   * Ensure plugin is ready
   */
  private async ensurePlugin(): Promise<CapacitorNetworkPlugin | null> {
    if (!this.initialized) {
      await this.initPlugin()
    }
    return this.plugin
  }

  /**
   * Get current network status
   */
  async getStatus(): Promise<NetworkStatus> {
    const plugin = await this.ensurePlugin()

    if (!plugin) {
      // Fall back to navigator.onLine
      if (typeof navigator !== 'undefined') {
        return {
          connected: navigator.onLine,
          connectionType: navigator.onLine ? 'unknown' : 'none'
        }
      }
      return { connected: false, connectionType: 'unknown' }
    }

    try {
      const status = await plugin.getStatus()
      return this.mapStatus(status)
    } catch {
      return { connected: false, connectionType: 'unknown' }
    }
  }

  /**
   * Check if online
   */
  async isOnline(): Promise<boolean> {
    const status = await this.getStatus()
    return status.connected
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
  private notifyListeners(status: NetworkStatus): void {
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
  async destroy(): Promise<void> {
    if (this.pluginListener) {
      await this.pluginListener.remove()
      this.pluginListener = null
    }
    this.listeners.clear()
  }
}
