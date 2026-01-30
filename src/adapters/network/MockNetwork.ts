/**
 * Mock Network Adapter for Testing
 *
 * Provides an in-memory implementation of NetworkAdapter for unit tests.
 * Allows simulating various network states and connectivity changes.
 *
 * @module adapters/network
 */

import {
  NetworkAdapter,
  NetworkStatus,
  NetworkStatusListener,
  NetworkConnectionType,
} from './NetworkAdapter'

/**
 * Mock network adapter for testing purposes
 */
export class MockNetwork implements NetworkAdapter {
  private status: NetworkStatus = {
    connected: true,
    connectionType: 'wifi',
    isMetered: false,
  }
  private listeners: Set<NetworkStatusListener> = new Set()
  private connectivityCheckResult: boolean = true

  async getStatus(): Promise<NetworkStatus> {
    return { ...this.status }
  }

  async isOnline(): Promise<boolean> {
    return this.status.connected
  }

  addStatusListener(listener: NetworkStatusListener): () => void {
    this.listeners.add(listener)
    return () => this.removeStatusListener(listener)
  }

  removeStatusListener(listener: NetworkStatusListener): void {
    this.listeners.delete(listener)
  }

  async checkConnectivity(_url?: string): Promise<boolean> {
    return this.connectivityCheckResult
  }

  destroy(): void {
    this.listeners.clear()
  }

  // Test helper methods

  /**
   * Set the network status (for testing)
   * @param status - The new network status
   * @param notify - Whether to notify listeners (default: true)
   */
  setStatus(status: Partial<NetworkStatus>, notify: boolean = true): void {
    this.status = { ...this.status, ...status }
    if (notify) {
      this.notifyListeners()
    }
  }

  /**
   * Simulate going online
   * @param connectionType - The connection type (default: 'wifi')
   */
  goOnline(connectionType: NetworkConnectionType = 'wifi'): void {
    this.setStatus({ connected: true, connectionType })
  }

  /**
   * Simulate going offline
   */
  goOffline(): void {
    this.setStatus({ connected: false, connectionType: 'none' })
  }

  /**
   * Set whether connectivity check should succeed
   */
  setConnectivityCheckResult(result: boolean): void {
    this.connectivityCheckResult = result
  }

  /**
   * Manually notify all listeners of current status
   */
  notifyListeners(): void {
    const currentStatus = { ...this.status }
    this.listeners.forEach((listener) => listener(currentStatus))
  }

  /**
   * Get the number of registered listeners (for test assertions)
   */
  getListenerCount(): number {
    return this.listeners.size
  }

  /**
   * Check if a specific listener is registered
   */
  hasListener(listener: NetworkStatusListener): boolean {
    return this.listeners.has(listener)
  }

  /**
   * Reset to default online state
   */
  reset(): void {
    this.status = {
      connected: true,
      connectionType: 'wifi',
      isMetered: false,
    }
    this.connectivityCheckResult = true
  }
}

/**
 * Factory function to create a new mock network adapter
 */
export function createMockNetwork(): MockNetwork {
  return new MockNetwork()
}
