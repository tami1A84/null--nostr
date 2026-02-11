/**
 * Storage Adapters
 *
 * Cross-platform storage abstraction layer.
 *
 * @module adapters/storage
 */

// Types and interfaces
export type {
  StorageAdapter,
  TypedStorageAdapter,
  StorableValue
} from './StorageAdapter'

// Implementations
export { WebStorage, getWebStorage } from './WebStorage'
export { MemoryStorage, createMemoryStorage } from './MemoryStorage'
export { ElectronStorage } from './ElectronStorage'
