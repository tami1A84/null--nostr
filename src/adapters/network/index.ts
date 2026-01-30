/**
 * Network Adapter exports
 *
 * @module adapters/network
 */

export type {
  NetworkAdapter,
  NetworkStatus,
  NetworkStatusListener,
  NetworkConnectionType,
  NetworkErrorCode
} from './NetworkAdapter'

export { NetworkError } from './NetworkAdapter'

export { WebNetwork } from './WebNetwork'
export { CapacitorNetwork } from './CapacitorNetwork'
export { ElectronNetwork } from './ElectronNetwork'
