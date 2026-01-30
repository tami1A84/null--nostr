/**
 * Network Adapter Interface
 *
 * Provides a unified interface for network status monitoring and connectivity
 * checks across different platforms.
 *
 * @module adapters/network
 */

/**
 * Network connection types
 */
export type NetworkConnectionType =
  | 'wifi'
  | 'cellular'
  | 'ethernet'
  | 'none'
  | 'unknown'

/**
 * Network status information
 */
export interface NetworkStatus {
  /** Whether the device is connected to a network */
  connected: boolean
  /** The type of network connection */
  connectionType: NetworkConnectionType
  /** Whether the connection is metered (e.g., cellular data) */
  isMetered?: boolean
}

/**
 * Network status change listener
 */
export type NetworkStatusListener = (status: NetworkStatus) => void

/**
 * Network adapter interface for cross-platform network monitoring
 */
export interface NetworkAdapter {
  /**
   * Get the current network status
   * @returns The current network status
   */
  getStatus(): Promise<NetworkStatus>

  /**
   * Check if currently online
   * @returns True if connected to a network
   */
  isOnline(): Promise<boolean>

  /**
   * Add a listener for network status changes
   * @param listener - Callback function called when status changes
   * @returns Function to remove the listener
   */
  addStatusListener(listener: NetworkStatusListener): () => void

  /**
   * Remove a network status listener
   * @param listener - The listener to remove
   */
  removeStatusListener(listener: NetworkStatusListener): void

  /**
   * Perform a connectivity check (ping a server)
   * @param url - URL to check (optional, uses default if not provided)
   * @returns True if the server is reachable
   */
  checkConnectivity?(url?: string): Promise<boolean>

  /**
   * Clean up resources
   */
  destroy?(): void
}

/**
 * Network error class
 */
export class NetworkError extends Error {
  constructor(
    message: string,
    public readonly code: NetworkErrorCode,
    public readonly cause?: Error
  ) {
    super(message)
    this.name = 'NetworkError'
  }
}

/**
 * Network error codes
 */
export type NetworkErrorCode =
  | 'OFFLINE'           // Device is offline
  | 'TIMEOUT'           // Request timed out
  | 'DNS_FAILED'        // DNS resolution failed
  | 'CONNECTION_REFUSED' // Connection refused
  | 'UNKNOWN'           // Unknown network error
