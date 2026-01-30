/**
 * Capacitor Clipboard Adapter
 *
 * Uses the Capacitor Clipboard plugin for native mobile environments.
 * Provides better native integration on Android and iOS.
 *
 * @module adapters/clipboard
 */

import {
  ClipboardAdapter,
  ClipboardContent,
  ClipboardError
} from './ClipboardAdapter'

// Type for Capacitor Clipboard plugin
interface CapacitorClipboardPlugin {
  write(options: { string?: string; image?: string }): Promise<void>
  read(): Promise<{ type: string; value: string }>
}

/**
 * Capacitor implementation of ClipboardAdapter
 */
export class CapacitorClipboard implements ClipboardAdapter {
  private plugin: CapacitorClipboardPlugin | null = null
  private available = false

  constructor() {
    this.initPlugin()
  }

  /**
   * Initialize the Capacitor Clipboard plugin
   */
  private async initPlugin(): Promise<void> {
    try {
      const { Clipboard } = await import('@capacitor/clipboard')
      this.plugin = Clipboard as unknown as CapacitorClipboardPlugin
      this.available = true
    } catch {
      // Plugin not available, fall back to unavailable state
      this.available = false
    }
  }

  /**
   * Ensure plugin is initialized
   */
  private async ensurePlugin(): Promise<CapacitorClipboardPlugin> {
    if (!this.plugin) {
      await this.initPlugin()
    }
    if (!this.plugin) {
      throw new ClipboardError(
        'Capacitor Clipboard plugin not available',
        'NOT_AVAILABLE'
      )
    }
    return this.plugin
  }

  /**
   * Check if clipboard is available
   */
  isAvailable(): boolean {
    return this.available
  }

  /**
   * Check if image clipboard is supported
   * Capacitor supports image clipboard on both Android and iOS
   */
  supportsImage(): boolean {
    return this.available
  }

  /**
   * Write text to clipboard
   */
  async writeText(text: string): Promise<void> {
    const plugin = await this.ensurePlugin()

    try {
      await plugin.write({ string: text })
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
    try {
      const plugin = await this.ensurePlugin()
      const result = await plugin.read()

      if (result.type === 'text/plain') {
        return result.value
      }
      return null
    } catch {
      return null
    }
  }

  /**
   * Write image to clipboard
   */
  async writeImage(imageData: string, _mimeType: string = 'image/png'): Promise<void> {
    const plugin = await this.ensurePlugin()

    try {
      // Capacitor expects base64 data
      await plugin.write({ image: imageData })
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
    try {
      const plugin = await this.ensurePlugin()
      const result = await plugin.read()

      if (result.type.startsWith('image/')) {
        return {
          image: result.value,
          imageMimeType: result.type
        }
      }
      return null
    } catch {
      return null
    }
  }
}
