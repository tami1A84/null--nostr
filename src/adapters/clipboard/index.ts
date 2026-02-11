/**
 * Clipboard Adapter exports
 *
 * @module adapters/clipboard
 */

export type {
  ClipboardAdapter,
  ClipboardContent,
  ClipboardErrorCode
} from './ClipboardAdapter'

export { ClipboardError } from './ClipboardAdapter'

export { WebClipboard } from './WebClipboard'
export { ElectronClipboard } from './ElectronClipboard'
