/**
 * Electron Clipboard Adapter
 *
 * Uses Electron's clipboard API through IPC for desktop environments.
 * Provides full native clipboard access including images.
 *
 * @module adapters/clipboard
 */

import {
  ClipboardAdapter,
  ClipboardContent,
  ClipboardError
} from './ClipboardAdapter'

// Type for Electron clipboard (accessed via preload script)
interface ElectronClipboardAPI {
  readText(): string
  writeText(text: string): void
  readImage(): { toDataURL(): string; isEmpty(): boolean } | null
  writeImage(dataURL: string): void
  availableFormats(): string[]
}

/**
 * Get Electron clipboard from window context
 */
function getElectronClipboard(): ElectronClipboardAPI | null {
  if (typeof window === 'undefined') return null

  // Check for exposed clipboard in preload
  const win = window as Window & {
    electronClipboard?: ElectronClipboardAPI
    electron?: { clipboard?: ElectronClipboardAPI }
  }

  return win.electronClipboard ?? win.electron?.clipboard ?? null
}

/**
 * Electron implementation of ClipboardAdapter
 */
export class ElectronClipboard implements ClipboardAdapter {
  private clipboard: ElectronClipboardAPI | null

  constructor() {
    this.clipboard = getElectronClipboard()
  }

  /**
   * Check if clipboard is available
   */
  isAvailable(): boolean {
    return this.clipboard !== null
  }

  /**
   * Check if image clipboard is supported
   * Electron supports image clipboard natively
   */
  supportsImage(): boolean {
    return this.isAvailable()
  }

  /**
   * Write text to clipboard
   */
  async writeText(text: string): Promise<void> {
    if (!this.clipboard) {
      throw new ClipboardError(
        'Electron clipboard not available',
        'NOT_AVAILABLE'
      )
    }

    try {
      this.clipboard.writeText(text)
    } catch (error) {
      throw new ClipboardError(
        'Failed to write to clipboard',
        'WRITE_FAILED',
        error instanceof Error ? error : undefined
      )
    }
  }

  /**
   * Read text from clipboard
   */
  async readText(): Promise<string | null> {
    if (!this.clipboard) {
      return null
    }

    try {
      return this.clipboard.readText() || null
    } catch {
      return null
    }
  }

  /**
   * Write image to clipboard
   */
  async writeImage(imageData: string, mimeType: string = 'image/png'): Promise<void> {
    if (!this.clipboard) {
      throw new ClipboardError(
        'Electron clipboard not available',
        'NOT_AVAILABLE'
      )
    }

    try {
      // Convert to data URL if not already
      const dataURL = imageData.startsWith('data:')
        ? imageData
        : `data:${mimeType};base64,${imageData}`

      this.clipboard.writeImage(dataURL)
    } catch (error) {
      throw new ClipboardError(
        'Failed to write image to clipboard',
        'WRITE_FAILED',
        error instanceof Error ? error : undefined
      )
    }
  }

  /**
   * Read image from clipboard
   */
  async readImage(): Promise<ClipboardContent | null> {
    if (!this.clipboard) {
      return null
    }

    try {
      const image = this.clipboard.readImage()
      if (!image || image.isEmpty()) {
        return null
      }

      const dataURL = image.toDataURL()
      // Parse data URL to extract base64 and mime type
      const match = dataURL.match(/^data:([^;]+);base64,(.+)$/)
      if (match) {
        return {
          image: match[2],
          imageMimeType: match[1]
        }
      }
      return null
    } catch {
      return null
    }
  }
}
