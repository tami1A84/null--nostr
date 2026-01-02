// Image utility functions for converting and optimizing images
import { UPLOAD_CONFIG } from './constants'
import { uploadImage } from './nostr'

/**
 * Convert an image file to WebP format
 * @param {File} file - The image file to convert
 * @param {Object} options - Conversion options
 * @param {number} options.maxWidth - Maximum width (default: 1024)
 * @param {number} options.maxHeight - Maximum height (default: 1024)
 * @param {number} options.quality - WebP quality 0-1 (default: 0.85)
 * @returns {Promise<Blob>} - WebP blob
 */
export async function convertToWebP(file, options = {}) {
  const {
    maxWidth = 1024,
    maxHeight = 1024,
    quality = 0.85
  } = options

  return new Promise((resolve, reject) => {
    const img = new Image()
    const canvas = document.createElement('canvas')
    const ctx = canvas.getContext('2d')

    img.onload = () => {
      // Calculate new dimensions maintaining aspect ratio
      let { width, height } = img
      
      if (width > maxWidth || height > maxHeight) {
        const ratio = Math.min(maxWidth / width, maxHeight / height)
        width = Math.round(width * ratio)
        height = Math.round(height * ratio)
      }

      canvas.width = width
      canvas.height = height

      // Draw image with white background (for transparency)
      ctx.fillStyle = '#ffffff'
      ctx.fillRect(0, 0, width, height)
      ctx.drawImage(img, 0, 0, width, height)

      // Convert to WebP
      canvas.toBlob(
        (blob) => {
          if (blob) {
            resolve(blob)
          } else {
            reject(new Error('Failed to convert to WebP'))
          }
        },
        'image/webp',
        quality
      )
    }

    img.onerror = () => reject(new Error('Failed to load image'))

    // Read file as data URL
    const reader = new FileReader()
    reader.onload = (e) => {
      img.src = e.target.result
    }
    reader.onerror = () => reject(new Error('Failed to read file'))
    reader.readAsDataURL(file)
  })
}

/**
 * Convert image to WebP and return as File object
 * @param {File} file - The image file to convert
 * @param {Object} options - Conversion options
 * @returns {Promise<File>} - WebP file
 */
export async function convertToWebPFile(file, options = {}) {
  const blob = await convertToWebP(file, options)
  const fileName = file.name.replace(/\.[^.]+$/, '.webp')
  return new File([blob], fileName, { type: 'image/webp' })
}

/**
 * Create a thumbnail version of an image
 * @param {File} file - The image file
 * @param {number} size - Thumbnail size (default: 256)
 * @returns {Promise<Blob>} - WebP thumbnail blob
 */
export async function createThumbnail(file, size = 256) {
  return convertToWebP(file, {
    maxWidth: size,
    maxHeight: size,
    quality: 0.8
  })
}

/**
 * Get image dimensions from a file
 * @param {File} file - The image file
 * @returns {Promise<{width: number, height: number}>}
 */
export async function getImageDimensions(file) {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => {
      resolve({ width: img.width, height: img.height })
    }
    img.onerror = () => reject(new Error('Failed to load image'))

    const reader = new FileReader()
    reader.onload = (e) => {
      img.src = e.target.result
    }
    reader.onerror = () => reject(new Error('Failed to read file'))
    reader.readAsDataURL(file)
  })
}

// ============================================
// Bulk Upload Utilities
// ============================================

/**
 * Calculate delay with exponential backoff and jitter
 * @param {number} attempt - Current attempt number (0-indexed)
 * @returns {number} - Delay in milliseconds
 */
function calculateBackoffDelay(attempt) {
  const { baseDelay, maxDelay, jitter } = UPLOAD_CONFIG.retry
  const exponentialDelay = Math.min(baseDelay * Math.pow(2, attempt), maxDelay)
  const jitterAmount = exponentialDelay * jitter * Math.random()
  return exponentialDelay + jitterAmount
}

/**
 * Wrap a promise with a timeout
 * @param {Promise} promise - The promise to wrap
 * @param {number} timeoutMs - Timeout in milliseconds
 * @param {string} errorMessage - Error message for timeout
 * @returns {Promise} - Promise that rejects on timeout
 */
function withTimeout(promise, timeoutMs, errorMessage = 'Operation timed out') {
  return Promise.race([
    promise,
    new Promise((_, reject) =>
      setTimeout(() => reject(new Error(errorMessage)), timeoutMs)
    )
  ])
}

/**
 * Upload a single image with retry logic and timeout
 * @param {File} file - The image file to upload
 * @param {Object} options - Upload options
 * @param {number} options.maxRetries - Maximum retry attempts
 * @param {number} options.timeout - Upload timeout in ms
 * @returns {Promise<string>} - Uploaded image URL
 */
export async function uploadImageWithRetry(file, options = {}) {
  const {
    maxRetries = UPLOAD_CONFIG.retry.maxAttempts,
    timeout = UPLOAD_CONFIG.uploadTimeout
  } = options

  let lastError = null

  for (let attempt = 0; attempt < maxRetries; attempt++) {
    try {
      const url = await withTimeout(
        uploadImage(file),
        timeout,
        `画像のアップロードがタイムアウトしました (${timeout / 1000}秒)`
      )
      if (url) return url
      throw new Error('アップロードからURLが返されませんでした')
    } catch (error) {
      lastError = error
      console.error(`Upload attempt ${attempt + 1}/${maxRetries} failed:`, error.message)

      if (attempt < maxRetries - 1) {
        const delay = calculateBackoffDelay(attempt)
        console.log(`Retrying in ${Math.round(delay)}ms...`)
        await new Promise(resolve => setTimeout(resolve, delay))
      }
    }
  }

  throw lastError || new Error('すべてのアップロード試行が失敗しました')
}

/**
 * Upload multiple images with controlled concurrency
 * @param {File[]} files - Array of image files to upload
 * @param {Object} options - Upload options
 * @param {number} options.maxConcurrent - Maximum concurrent uploads
 * @param {function} options.onProgress - Progress callback (current, total, result)
 * @param {function} options.onError - Error callback (index, error) - return true to continue, false to abort
 * @returns {Promise<{urls: string[], errors: Array<{index: number, error: Error}>}>}
 */
export async function uploadImagesInParallel(files, options = {}) {
  const {
    maxConcurrent = UPLOAD_CONFIG.maxConcurrentUploads,
    onProgress = null,
    onError = null
  } = options

  const results = new Array(files.length).fill(null)
  const errors = []
  let completedCount = 0
  let aborted = false

  // Semaphore for concurrency control
  let activeCount = 0
  const queue = []

  const processQueue = () => {
    while (activeCount < maxConcurrent && queue.length > 0 && !aborted) {
      const task = queue.shift()
      activeCount++
      task().finally(() => {
        activeCount--
        processQueue()
      })
    }
  }

  const uploadTasks = files.map((file, index) => {
    return new Promise((resolve) => {
      const task = async () => {
        if (aborted) {
          resolve()
          return
        }

        try {
          const url = await uploadImageWithRetry(file)
          results[index] = url
          completedCount++

          if (onProgress) {
            onProgress(completedCount, files.length, { index, url, success: true })
          }
        } catch (error) {
          errors.push({ index, error, fileName: file.name })
          completedCount++

          if (onProgress) {
            onProgress(completedCount, files.length, { index, error, success: false })
          }

          // Check if we should abort
          if (onError) {
            const shouldContinue = onError(index, error)
            if (shouldContinue === false) {
              aborted = true
            }
          }
        }
        resolve()
      }

      queue.push(task)
      processQueue()
    })
  })

  await Promise.all(uploadTasks)

  // Filter out null values (failed uploads)
  const urls = results.filter(url => url !== null)

  return { urls, errors, aborted }
}

/**
 * Upload images sequentially (one at a time)
 * More stable but slower than parallel upload
 * @param {File[]} files - Array of image files to upload
 * @param {Object} options - Upload options
 * @param {function} options.onProgress - Progress callback (current, total)
 * @param {boolean} options.stopOnError - Whether to stop on first error
 * @returns {Promise<{urls: string[], errors: Array<{index: number, error: Error}>}>}
 */
export async function uploadImagesSequentially(files, options = {}) {
  const { onProgress = null, stopOnError = false } = options
  const urls = []
  const errors = []

  for (let i = 0; i < files.length; i++) {
    if (onProgress) {
      onProgress(i + 1, files.length)
    }

    try {
      const url = await uploadImageWithRetry(files[i])
      urls.push(url)
    } catch (error) {
      errors.push({ index: i, error, fileName: files[i].name })
      if (stopOnError) {
        break
      }
    }
  }

  return { urls, errors }
}
