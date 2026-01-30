/**
 * Clipboard Adapter Interface
 *
 * Provides a unified interface for clipboard operations across different platforms.
 * Supports both text and image clipboard operations where available.
 *
 * @module adapters/clipboard
 */

/**
 * Clipboard content types
 */
export interface ClipboardContent {
  /** Plain text content */
  text?: string
  /** Image data as base64 string */
  image?: string
  /** Image MIME type (e.g., 'image/png') */
  imageMimeType?: string
}

/**
 * Clipboard adapter interface for cross-platform clipboard abstraction
 */
export interface ClipboardAdapter {
  /**
   * Write text to the clipboard
   * @param text - The text to copy
   */
  writeText(text: string): Promise<void>

  /**
   * Read text from the clipboard
   * @returns The clipboard text content, or null if empty/unavailable
   */
  readText(): Promise<string | null>

  /**
   * Write an image to the clipboard (if supported)
   * @param imageData - Base64 encoded image data
   * @param mimeType - Image MIME type (default: 'image/png')
   */
  writeImage?(imageData: string, mimeType?: string): Promise<void>

  /**
   * Read an image from the clipboard (if supported)
   * @returns The image data as base64, or null if no image
   */
  readImage?(): Promise<ClipboardContent | null>

  /**
   * Check if clipboard operations are available
   * @returns True if clipboard is available
   */
  isAvailable(): boolean

  /**
   * Check if image clipboard is supported
   * @returns True if image operations are supported
   */
  supportsImage(): boolean
}

/**
 * Clipboard error class
 */
export class ClipboardError extends Error {
  constructor(
    message: string,
    public readonly code: ClipboardErrorCode,
    public readonly cause?: Error
  ) {
    super(message)
    this.name = 'ClipboardError'
  }
}

/**
 * Clipboard error codes
 */
export type ClipboardErrorCode =
  | 'NOT_AVAILABLE'     // Clipboard API not available
  | 'PERMISSION_DENIED' // Clipboard permission denied
  | 'READ_FAILED'       // Failed to read from clipboard
  | 'WRITE_FAILED'      // Failed to write to clipboard
  | 'NOT_SUPPORTED'     // Feature not supported on this platform
