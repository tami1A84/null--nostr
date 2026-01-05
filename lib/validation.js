/**
 * Input Validation and Sanitization Utilities
 *
 * This module provides comprehensive input validation and sanitization
 * to prevent XSS, injection attacks, and other security vulnerabilities.
 *
 * @module validation
 */

// ============================================
// HTML Sanitization
// ============================================

/**
 * HTML entities that need to be escaped
 * @type {Object<string, string>}
 */
const HTML_ENTITIES = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#x27;',
  '/': '&#x2F;',
  '`': '&#x60;',
  '=': '&#x3D;'
}

/**
 * Escape HTML entities to prevent XSS
 * @param {string} str - Input string
 * @returns {string} Escaped string
 */
export function escapeHtml(str) {
  if (typeof str !== 'string') return ''
  return str.replace(/[&<>"'`=/]/g, char => HTML_ENTITIES[char])
}

/**
 * Sanitize text content for safe display
 * Removes potentially dangerous patterns while preserving safe content
 * @param {string} text - Input text
 * @returns {string} Sanitized text
 */
export function sanitizeText(text) {
  if (typeof text !== 'string') return ''

  return text
    // Remove null bytes
    .replace(/\0/g, '')
    // Remove control characters except newlines and tabs
    .replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g, '')
    // Normalize line endings
    .replace(/\r\n/g, '\n')
    .replace(/\r/g, '\n')
}

/**
 * Strip all HTML tags from text
 * @param {string} html - HTML string
 * @returns {string} Plain text
 */
export function stripHtmlTags(html) {
  if (typeof html !== 'string') return ''
  return html.replace(/<[^>]*>/g, '')
}

// ============================================
// URL Validation
// ============================================

/**
 * Allowed URL protocols for safe links
 * @type {string[]}
 */
const SAFE_PROTOCOLS = ['http:', 'https:', 'mailto:', 'nostr:', 'lightning:']

/**
 * Validate and sanitize a URL
 * @param {string} url - URL to validate
 * @param {Object} options - Validation options
 * @param {boolean} [options.allowNostr=true] - Allow nostr: protocol
 * @param {boolean} [options.allowLightning=true] - Allow lightning: protocol
 * @param {boolean} [options.requireHttps=false] - Require HTTPS
 * @returns {string|null} Validated URL or null if invalid
 */
export function validateUrl(url, options = {}) {
  const {
    allowNostr = true,
    allowLightning = true,
    requireHttps = false
  } = options

  if (!url || typeof url !== 'string') return null

  const trimmed = url.trim()

  // Check for javascript: and data: protocols (dangerous)
  if (/^(javascript|data|vbscript):/i.test(trimmed)) {
    return null
  }

  try {
    const parsed = new URL(trimmed)

    // Build allowed protocols list
    let allowed = ['https:', 'mailto:']
    if (!requireHttps) allowed.push('http:')
    if (allowNostr) allowed.push('nostr:')
    if (allowLightning) allowed.push('lightning:')

    if (!allowed.includes(parsed.protocol)) {
      return null
    }

    return trimmed
  } catch {
    // Try adding https:// if no protocol
    if (!/^[a-z]+:/i.test(trimmed)) {
      return validateUrl('https://' + trimmed, options)
    }
    return null
  }
}

/**
 * Validate WebSocket relay URL
 * @param {string} url - WebSocket URL
 * @returns {boolean} True if valid WSS URL
 */
export function isValidRelayUrl(url) {
  if (!url || typeof url !== 'string') return false

  try {
    const parsed = new URL(url)

    // Must be wss:// (secure WebSocket)
    if (parsed.protocol !== 'wss:') return false

    // Exclude Tor onion addresses
    if (parsed.hostname.endsWith('.onion')) return false

    // Exclude localhost in production
    if (parsed.hostname === 'localhost' || parsed.hostname === '127.0.0.1') {
      return typeof window !== 'undefined' && window.location.hostname === 'localhost'
    }

    return true
  } catch {
    return false
  }
}

/**
 * Validate image URL
 * @param {string} url - Image URL
 * @returns {boolean} True if valid image URL
 */
export function isValidImageUrl(url) {
  if (!url || typeof url !== 'string') return false

  const validated = validateUrl(url, { requireHttps: true })
  if (!validated) return false

  // Check for common image extensions or image hosting domains
  const imageExtensions = /\.(jpg|jpeg|png|gif|webp|svg|bmp|ico)(\?.*)?$/i
  const imageHosts = [
    'nostr.build',
    'void.cat',
    'imgproxy.iris.to',
    'imgur.com',
    'i.imgur.com',
    'image.nostr.build',
    'media.snort.social'
  ]

  try {
    const parsed = new URL(validated)

    // Check extension
    if (imageExtensions.test(parsed.pathname)) return true

    // Check known image hosts
    if (imageHosts.some(host => parsed.hostname.includes(host))) return true

    return false
  } catch {
    return false
  }
}

// ============================================
// Nostr-specific Validation
// ============================================

/**
 * Validate hex string (for pubkey, event ID, etc.)
 * @param {string} hex - Hex string
 * @param {number} [expectedLength] - Expected length in characters
 * @returns {boolean} True if valid hex
 */
export function isValidHex(hex, expectedLength) {
  if (!hex || typeof hex !== 'string') return false
  if (expectedLength && hex.length !== expectedLength) return false
  return /^[0-9a-fA-F]+$/.test(hex)
}

/**
 * Validate public key (64-character hex)
 * @param {string} pubkey - Public key
 * @returns {boolean} True if valid pubkey
 */
export function isValidPubkey(pubkey) {
  return isValidHex(pubkey, 64)
}

/**
 * Validate event ID (64-character hex)
 * @param {string} eventId - Event ID
 * @returns {boolean} True if valid event ID
 */
export function isValidEventId(eventId) {
  return isValidHex(eventId, 64)
}

/**
 * Validate NIP-05 identifier format
 * @param {string} nip05 - NIP-05 identifier
 * @returns {boolean} True if valid format
 */
export function isValidNip05(nip05) {
  if (!nip05 || typeof nip05 !== 'string') return false

  // Allow domain-only format (treated as _@domain)
  if (!nip05.includes('@')) {
    return isValidDomain(nip05)
  }

  const [name, domain] = nip05.split('@')
  if (!name || !domain) return false

  // Validate name part (alphanumeric, underscore, dot, hyphen)
  if (!/^[a-zA-Z0-9._-]+$/.test(name)) return false

  return isValidDomain(domain)
}

/**
 * Validate domain name
 * @param {string} domain - Domain name
 * @returns {boolean} True if valid domain
 */
export function isValidDomain(domain) {
  if (!domain || typeof domain !== 'string') return false

  // Basic domain validation
  const domainRegex = /^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$/
  return domainRegex.test(domain) && domain.length <= 253
}

/**
 * Validate bech32 encoded string (npub, nsec, note, etc.)
 * @param {string} str - bech32 string
 * @param {string} [prefix] - Expected prefix (e.g., 'npub', 'nsec')
 * @returns {boolean} True if valid
 */
export function isValidBech32(str, prefix) {
  if (!str || typeof str !== 'string') return false

  // Basic bech32 pattern
  const bech32Pattern = /^[a-z]+1[023456789acdefghjklmnpqrstuvwxyz]+$/
  if (!bech32Pattern.test(str)) return false

  if (prefix && !str.startsWith(prefix + '1')) return false

  return true
}

// ============================================
// Event Content Validation
// ============================================

/**
 * Maximum allowed content length for different event kinds
 * @type {Object<number, number>}
 */
const MAX_CONTENT_LENGTH = {
  0: 10000,   // Metadata
  1: 100000,  // Text note
  4: 50000,   // Encrypted DM
  7: 100,     // Reaction
  14: 50000,  // Direct message
}

/**
 * Validate event content
 * @param {string} content - Event content
 * @param {number} kind - Event kind
 * @returns {Object} Validation result
 */
export function validateEventContent(content, kind) {
  const result = {
    valid: true,
    errors: [],
    sanitized: content
  }

  if (typeof content !== 'string') {
    result.valid = false
    result.errors.push('Content must be a string')
    return result
  }

  // Check length
  const maxLength = MAX_CONTENT_LENGTH[kind] || 100000
  if (content.length > maxLength) {
    result.valid = false
    result.errors.push(`Content exceeds maximum length of ${maxLength} characters`)
    return result
  }

  // Sanitize
  result.sanitized = sanitizeText(content)

  return result
}

/**
 * Validate event tags
 * @param {Array} tags - Event tags array
 * @returns {Object} Validation result
 */
export function validateEventTags(tags) {
  const result = {
    valid: true,
    errors: [],
    sanitized: []
  }

  if (!Array.isArray(tags)) {
    result.valid = false
    result.errors.push('Tags must be an array')
    return result
  }

  for (let i = 0; i < tags.length; i++) {
    const tag = tags[i]

    if (!Array.isArray(tag)) {
      result.errors.push(`Tag at index ${i} must be an array`)
      continue
    }

    if (tag.length === 0) {
      result.errors.push(`Tag at index ${i} is empty`)
      continue
    }

    // Sanitize each tag element
    const sanitizedTag = tag.map(element => {
      if (typeof element === 'string') {
        return sanitizeText(element)
      }
      return String(element)
    })

    // Validate specific tag types
    const tagType = sanitizedTag[0]
    const tagValue = sanitizedTag[1]

    switch (tagType) {
      case 'p':
        if (tagValue && !isValidPubkey(tagValue)) {
          result.errors.push(`Invalid pubkey in tag at index ${i}`)
        }
        break
      case 'e':
        if (tagValue && !isValidEventId(tagValue)) {
          result.errors.push(`Invalid event ID in tag at index ${i}`)
        }
        break
      case 'relay':
        if (tagValue && tagValue !== 'ALL_RELAYS' && !isValidRelayUrl(tagValue)) {
          result.errors.push(`Invalid relay URL in tag at index ${i}`)
        }
        break
    }

    result.sanitized.push(sanitizedTag)
  }

  if (result.errors.length > 0) {
    result.valid = false
  }

  return result
}

// ============================================
// Profile Validation
// ============================================

/**
 * Validate profile metadata
 * @param {Object} profile - Profile object
 * @returns {Object} Validation result with sanitized profile
 */
export function validateProfile(profile) {
  const result = {
    valid: true,
    errors: [],
    sanitized: {}
  }

  if (!profile || typeof profile !== 'object') {
    result.valid = false
    result.errors.push('Profile must be an object')
    return result
  }

  // Validate and sanitize each field
  const stringFields = ['name', 'display_name', 'about', 'website', 'nip05', 'lud16', 'birthday']
  const urlFields = ['picture', 'banner']

  for (const field of stringFields) {
    if (profile[field]) {
      if (typeof profile[field] !== 'string') {
        result.errors.push(`${field} must be a string`)
      } else {
        result.sanitized[field] = sanitizeText(profile[field]).slice(0, 10000)
      }
    }
  }

  for (const field of urlFields) {
    if (profile[field]) {
      const validated = validateUrl(profile[field], { requireHttps: true })
      if (!validated) {
        result.errors.push(`${field} must be a valid HTTPS URL`)
      } else {
        result.sanitized[field] = validated
      }
    }
  }

  // Validate NIP-05 format
  if (profile.nip05 && !isValidNip05(profile.nip05)) {
    result.errors.push('nip05 format is invalid')
  }

  // Validate website URL
  if (profile.website) {
    const validated = validateUrl(profile.website)
    if (!validated) {
      result.errors.push('website must be a valid URL')
    } else {
      result.sanitized.website = validated
    }
  }

  if (result.errors.length > 0) {
    result.valid = false
  }

  return result
}

// ============================================
// Lightning/Zap Validation
// ============================================

/**
 * Validate Lightning address (lud16)
 * @param {string} address - Lightning address
 * @returns {boolean} True if valid
 */
export function isValidLightningAddress(address) {
  if (!address || typeof address !== 'string') return false

  const [name, domain] = address.split('@')
  if (!name || !domain) return false

  // Validate name part
  if (!/^[a-zA-Z0-9._-]+$/.test(name)) return false

  return isValidDomain(domain)
}

/**
 * Validate zap amount
 * @param {number} amount - Amount in sats
 * @param {number} [min=1] - Minimum amount
 * @param {number} [max=2100000000000000] - Maximum amount (21M BTC in sats)
 * @returns {boolean} True if valid
 */
export function isValidZapAmount(amount, min = 1, max = 2100000000000000) {
  if (typeof amount !== 'number' || isNaN(amount)) return false
  return Number.isInteger(amount) && amount >= min && amount <= max
}

// ============================================
// Search Query Validation
// ============================================

/**
 * Sanitize search query
 * @param {string} query - Search query
 * @param {number} [maxLength=500] - Maximum query length
 * @returns {string} Sanitized query
 */
export function sanitizeSearchQuery(query, maxLength = 500) {
  if (typeof query !== 'string') return ''

  return sanitizeText(query)
    .trim()
    .slice(0, maxLength)
    // Remove excessive whitespace
    .replace(/\s+/g, ' ')
}

// ============================================
// JSON Validation
// ============================================

/**
 * Safely parse JSON with error handling
 * @param {string} json - JSON string
 * @param {*} [defaultValue=null] - Default value on parse failure
 * @returns {*} Parsed value or default
 */
export function safeJsonParse(json, defaultValue = null) {
  if (typeof json !== 'string') return defaultValue

  try {
    return JSON.parse(json)
  } catch {
    return defaultValue
  }
}

/**
 * Validate JSON string can be parsed
 * @param {string} json - JSON string
 * @returns {boolean} True if valid JSON
 */
export function isValidJson(json) {
  return safeJsonParse(json, undefined) !== undefined
}

// ============================================
// Rate Limiting Helper
// ============================================

/**
 * Simple in-memory rate limiter
 */
export class RateLimiter {
  /**
   * @param {number} maxRequests - Maximum requests allowed
   * @param {number} windowMs - Time window in milliseconds
   */
  constructor(maxRequests, windowMs) {
    this.maxRequests = maxRequests
    this.windowMs = windowMs
    this.requests = new Map()
  }

  /**
   * Check if request is allowed
   * @param {string} key - Unique identifier (e.g., IP, pubkey)
   * @returns {boolean} True if allowed
   */
  isAllowed(key) {
    const now = Date.now()
    const windowStart = now - this.windowMs

    // Get requests for this key
    let keyRequests = this.requests.get(key) || []

    // Filter to only requests in current window
    keyRequests = keyRequests.filter(time => time > windowStart)

    if (keyRequests.length >= this.maxRequests) {
      return false
    }

    keyRequests.push(now)
    this.requests.set(key, keyRequests)

    return true
  }

  /**
   * Get remaining requests for key
   * @param {string} key - Unique identifier
   * @returns {number} Remaining requests
   */
  remaining(key) {
    const now = Date.now()
    const windowStart = now - this.windowMs
    const keyRequests = this.requests.get(key) || []
    const validRequests = keyRequests.filter(time => time > windowStart)
    return Math.max(0, this.maxRequests - validRequests.length)
  }

  /**
   * Clear all rate limit data
   */
  clear() {
    this.requests.clear()
  }
}

// ============================================
// Export defaults
// ============================================

export default {
  escapeHtml,
  sanitizeText,
  stripHtmlTags,
  validateUrl,
  isValidRelayUrl,
  isValidImageUrl,
  isValidHex,
  isValidPubkey,
  isValidEventId,
  isValidNip05,
  isValidDomain,
  isValidBech32,
  validateEventContent,
  validateEventTags,
  validateProfile,
  isValidLightningAddress,
  isValidZapAmount,
  sanitizeSearchQuery,
  safeJsonParse,
  isValidJson,
  RateLimiter
}
