// Image utility functions for converting and optimizing images
import { UPLOAD_CONFIG } from './constants'
import { uploadImage } from './nostr'

// ============================================
// Image Proxy for CORP/CORS Issues
// ============================================

/**
 * Known image proxy services
 * These help bypass CORP (Cross-Origin-Resource-Policy) restrictions
 */
const IMAGE_PROXIES = [
  // imgproxy.iris.to - Nostr community proxy
  (url) => `https://imgproxy.iris.to/insecure/plain/${encodeURIComponent(url)}`,
  // wsrv.nl - free image proxy
  (url) => `https://wsrv.nl/?url=${encodeURIComponent(url)}`,
]

/**
 * Domains that commonly have CORP issues
 */
const CORP_PROBLEM_DOMAINS = [
  'cdninstagram.com',
  'scontent.cdninstagram.com',
  'instagram.com',
  'fbcdn.net',
  'twimg.com',
  'pbs.twimg.com',
]

/**
 * Trusted domains that don't need proxying
 */
const TRUSTED_IMAGE_DOMAINS = [
  // Nostr image hosts
  'nostr.build',
  'image.nostr.build',
  'pfp.nostr.build',
  'blossom.nostr.build',
  'void.cat',
  'media.snort.social',
  'cdn.jb55.com',
  'nostr.download',
  'nostrcheck.me',
  'cdn.nostrcheck.me',
  // Blossom servers
  'blossom.primal.net',
  'blossom.westernbtc.com',
  // Yabu.me
  'yabu.me',
  'share.yabu.me',
  // Kojira
  'kojira.io',
  'r.kojira.io',
  // Proxy services (already proxied)
  'imgproxy.iris.to',
  'wsrv.nl',
  // Common image hosts
  'i.imgur.com',
  'imgur.com',
  'gyazo.com',
  'i.gyazo.com',
  'gravatar.com',
  // GitHub
  'githubusercontent.com',
  'raw.githubusercontent.com',
  'github.io',
  // Google
  'googleusercontent.com',
  'ggpht.com',
  // Other trusted
  'robohash.org',
  'dicebear.com',
  'api.dicebear.com',
]

/**
 * Check if URL needs proxying due to potential CORP issues
 * @param {string} url - Image URL to check
 * @returns {boolean} - True if URL likely has CORP issues
 */
export function needsProxy(url) {
  if (!url || typeof url !== 'string') return false

  try {
    const parsed = new URL(url)
    const hostname = parsed.hostname.toLowerCase()

    // Don't proxy localhost
    if (hostname.includes('localhost') || hostname.includes('127.0.0.1')) {
      return false
    }

    // Don't proxy data URLs
    if (url.startsWith('data:')) {
      return false
    }

    // Check if it's a trusted domain - don't proxy
    if (TRUSTED_IMAGE_DOMAINS.some(d => hostname.includes(d))) {
      return false
    }

    // Only proxy known problem domains (conservative approach)
    if (CORP_PROBLEM_DOMAINS.some(d => hostname.includes(d))) {
      return true
    }

    // For unknown domains, don't proxy by default
    // Most domains work fine without proxy
    return false
  } catch {
    return false
  }
}

/**
 * Get proxied URL for an image
 * @param {string} url - Original image URL
 * @param {number} [proxyIndex=0] - Which proxy to use (for fallback)
 * @returns {string} - Proxied URL or original if proxying not needed
 */
export function getProxiedUrl(url, proxyIndex = 0) {
  if (!url || typeof url !== 'string') return url

  // Don't proxy data URLs
  if (url.startsWith('data:')) return url

  // Don't proxy if already proxied
  if (url.includes('imgproxy.iris.to') || url.includes('wsrv.nl')) {
    return url
  }

  // Use the specified proxy (with fallback to first)
  const proxy = IMAGE_PROXIES[proxyIndex] || IMAGE_PROXIES[0]
  return proxy(url)
}

/**
 * Get image URL with automatic proxy decision
 * @param {string} url - Original image URL
 * @param {Object} [options] - Options
 * @param {boolean} [options.forceProxy=false] - Force proxying regardless of domain
 * @returns {string} - Processed URL
 */
export function getImageUrl(url, options = {}) {
  const { forceProxy = false } = options

  if (!url || typeof url !== 'string') return ''

  // Check if proxying is needed
  if (forceProxy || needsProxy(url)) {
    return getProxiedUrl(url)
  }

  return url
}

/**
 * Create an image element with automatic CORP error handling
 * Falls back to proxy if original fails
 * @param {string} url - Image URL
 * @param {Object} [options] - Options
 * @param {string} [options.alt=''] - Alt text
 * @param {string} [options.className=''] - CSS classes
 * @param {Function} [options.onLoad] - Load callback
 * @param {Function} [options.onFinalError] - Called when all fallbacks fail
 * @returns {HTMLImageElement}
 */
export function createRobustImage(url, options = {}) {
  const { alt = '', className = '', onLoad, onFinalError } = options

  const img = new Image()
  img.alt = alt
  img.className = className
  img.referrerPolicy = 'no-referrer'
  img.crossOrigin = 'anonymous'

  let proxyAttempt = 0

  img.onerror = () => {
    if (proxyAttempt < IMAGE_PROXIES.length) {
      // Try next proxy
      const proxiedUrl = getProxiedUrl(url, proxyAttempt)
      proxyAttempt++
      img.src = proxiedUrl
    } else if (onFinalError) {
      onFinalError()
    }
  }

  if (onLoad) {
    img.onload = onLoad
  }

  // Start with original URL if trusted, otherwise proxy immediately
  if (needsProxy(url)) {
    img.src = getProxiedUrl(url, 0)
    proxyAttempt = 1
  } else {
    img.src = url
  }

  return img
}

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
