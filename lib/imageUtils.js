// Image utility functions for converting and optimizing images

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
