/**
 * Web Clipboard Adapter
 *
 * Uses the Web Clipboard API for browser environments.
 * Requires secure context (HTTPS) for full functionality.
 *
 * @module adapters/clipboard
 */

import {
  ClipboardAdapter,
  ClipboardContent,
  ClipboardError
} from './ClipboardAdapter'

/**
 * Web implementation of ClipboardAdapter using navigator.clipboard
 */
export class WebClipboard implements ClipboardAdapter {
  private clipboard: Clipboard | null = null

  constructor() {
    if (typeof navigator !== 'undefined' && navigator.clipboard) {
      this.clipboard = navigator.clipboard
    }
  }

  /**
   * Check if clipboard is available
   */
  isAvailable(): boolean {
    return this.clipboard !== null
  }

  /**
   * Check if image clipboard is supported
   */
  supportsImage(): boolean {
    // ClipboardItem is required for image support
    return (
      this.isAvailable() &&
      typeof ClipboardItem !== 'undefined' &&
      typeof this.clipboard?.write === 'function'
    )
  }

  /**
   * Write text to clipboard
   */
  async writeText(text: string): Promise<void> {
    if (!this.clipboard) {
      throw new ClipboardError(
        'Clipboard API not available',
        'NOT_AVAILABLE'
      )
    }

    try {
      await this.clipboard.writeText(text)
    } catch (error) {
      if (error instanceof DOMException && error.name === 'NotAllowedError') {
        throw new ClipboardError(
          'Clipboard write permission denied',
          'PERMISSION_DENIED',
          error
        )
      }
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
      return await this.clipboard.readText()
    } catch (error) {
      if (error instanceof DOMException && error.name === 'NotAllowedError') {
        throw new ClipboardError(
          'Clipboard read permission denied',
          'PERMISSION_DENIED',
          error
        )
      }
      // Return null for other errors (e.g., empty clipboard)
      return null
    }
  }

  /**
   * Write image to clipboard
   */
  async writeImage(imageData: string, mimeType: string = 'image/png'): Promise<void> {
    if (!this.supportsImage()) {
      throw new ClipboardError(
        'Image clipboard not supported',
        'NOT_SUPPORTED'
      )
    }

    try {
      // Convert base64 to blob
      const binary = atob(imageData)
      const array = new Uint8Array(binary.length)
      for (let i = 0; i < binary.length; i++) {
        array[i] = binary.charCodeAt(i)
      }
      const blob = new Blob([array], { type: mimeType })

      // Write to clipboard
      const item = new ClipboardItem({ [mimeType]: blob })
      await this.clipboard!.write([item])
    } catch (error) {
      if (error instanceof DOMException && error.name === 'NotAllowedError') {
        throw new ClipboardError(
          'Clipboard write permission denied',
          'PERMISSION_DENIED',
          error
        )
      }
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
    if (!this.supportsImage()) {
      return null
    }

    try {
      const items = await this.clipboard!.read()
      for (const item of items) {
        // Look for image types
        const imageType = item.types.find(type => type.startsWith('image/'))
        if (imageType) {
          const blob = await item.getType(imageType)
          const buffer = await blob.arrayBuffer()
          const base64 = btoa(
            String.fromCharCode(...new Uint8Array(buffer))
          )
          return {
            image: base64,
            imageMimeType: imageType
          }
        }
      }
      return null
    } catch {
      return null
    }
  }
}
