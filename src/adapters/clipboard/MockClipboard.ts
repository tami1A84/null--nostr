/**
 * Mock Clipboard Adapter for Testing
 *
 * Provides an in-memory implementation of ClipboardAdapter for unit tests.
 * All operations are synchronous and stored in memory.
 *
 * @module adapters/clipboard
 */

import { ClipboardAdapter, ClipboardContent } from './ClipboardAdapter'

/**
 * Mock clipboard adapter for testing purposes
 */
export class MockClipboard implements ClipboardAdapter {
  private textContent: string | null = null
  private imageContent: ClipboardContent | null = null
  private _isAvailable: boolean = true
  private _supportsImage: boolean = true

  async writeText(text: string): Promise<void> {
    this.textContent = text
  }

  async readText(): Promise<string | null> {
    return this.textContent
  }

  async writeImage(imageData: string, mimeType: string = 'image/png'): Promise<void> {
    this.imageContent = {
      image: imageData,
      imageMimeType: mimeType,
    }
  }

  async readImage(): Promise<ClipboardContent | null> {
    return this.imageContent
  }

  isAvailable(): boolean {
    return this._isAvailable
  }

  supportsImage(): boolean {
    return this._supportsImage
  }

  // Test helper methods

  /**
   * Set whether clipboard is available (for testing error scenarios)
   */
  setAvailable(available: boolean): void {
    this._isAvailable = available
  }

  /**
   * Set whether image operations are supported
   */
  setSupportsImage(supports: boolean): void {
    this._supportsImage = supports
  }

  /**
   * Clear all clipboard content
   */
  clear(): void {
    this.textContent = null
    this.imageContent = null
  }

  /**
   * Get current text content (for test assertions)
   */
  getTextContent(): string | null {
    return this.textContent
  }

  /**
   * Get current image content (for test assertions)
   */
  getImageContent(): ClipboardContent | null {
    return this.imageContent
  }
}

/**
 * Factory function to create a new mock clipboard
 */
export function createMockClipboard(): MockClipboard {
  return new MockClipboard()
}
