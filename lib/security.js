/**
 * Security Utilities for Nostr Client
 *
 * Provides security-focused utilities including:
 * - CSRF token management
 * - Secure storage wrapper
 * - Content security helpers
 * - Rate limiting
 * - Security event logging
 *
 * @module security
 */

// ============================================
// CSRF Token Management
// ============================================

/**
 * CSRF token storage key
 * @type {string}
 */
const CSRF_TOKEN_KEY = 'nurunuru_csrf_token'

/**
 * CSRF token expiry (1 hour)
 * @type {number}
 */
const CSRF_TOKEN_EXPIRY = 60 * 60 * 1000

/**
 * Generate a cryptographically secure random token
 * @param {number} [length=32] - Token length in bytes
 * @returns {string} Hex-encoded token
 */
export function generateSecureToken(length = 32) {
  const bytes = new Uint8Array(length)
  crypto.getRandomValues(bytes)
  return Array.from(bytes)
    .map(b => b.toString(16).padStart(2, '0'))
    .join('')
}

/**
 * Generate or retrieve CSRF token
 * @returns {string} CSRF token
 */
export function getCsrfToken() {
  if (typeof window === 'undefined') return ''

  try {
    const stored = sessionStorage.getItem(CSRF_TOKEN_KEY)
    if (stored) {
      const { token, expiry } = JSON.parse(stored)
      if (Date.now() < expiry) {
        return token
      }
    }

    // Generate new token
    const token = generateSecureToken()
    sessionStorage.setItem(CSRF_TOKEN_KEY, JSON.stringify({
      token,
      expiry: Date.now() + CSRF_TOKEN_EXPIRY
    }))

    return token
  } catch {
    return generateSecureToken()
  }
}

/**
 * Validate CSRF token
 * @param {string} token - Token to validate
 * @returns {boolean} True if valid
 */
export function validateCsrfToken(token) {
  if (!token || typeof token !== 'string') return false

  const expectedToken = getCsrfToken()
  if (!expectedToken) return false

  // Constant-time comparison to prevent timing attacks
  return constantTimeCompare(token, expectedToken)
}

/**
 * Constant-time string comparison
 * @param {string} a - First string
 * @param {string} b - Second string
 * @returns {boolean} True if equal
 */
function constantTimeCompare(a, b) {
  if (a.length !== b.length) return false

  let result = 0
  for (let i = 0; i < a.length; i++) {
    result |= a.charCodeAt(i) ^ b.charCodeAt(i)
  }

  return result === 0
}

// ============================================
// Secure Storage Wrapper
// ============================================

/**
 * Encryption key for local storage (derived per session)
 * @type {CryptoKey|null}
 */
let storageEncryptionKey = null

/**
 * Initialize storage encryption key
 * @returns {Promise<CryptoKey>}
 */
async function getStorageKey() {
  if (storageEncryptionKey) return storageEncryptionKey

  // Derive key from session-specific data
  const sessionId = getCsrfToken()
  const encoder = new TextEncoder()
  const keyMaterial = await crypto.subtle.importKey(
    'raw',
    encoder.encode(sessionId),
    'PBKDF2',
    false,
    ['deriveKey']
  )

  storageEncryptionKey = await crypto.subtle.deriveKey(
    {
      name: 'PBKDF2',
      salt: encoder.encode('nurunuru_storage_salt'),
      iterations: 100000,
      hash: 'SHA-256'
    },
    keyMaterial,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt']
  )

  return storageEncryptionKey
}

/**
 * Securely store data with encryption
 * @param {string} key - Storage key
 * @param {*} value - Value to store
 * @returns {Promise<boolean>} Success status
 */
export async function secureStore(key, value) {
  if (typeof window === 'undefined') return false

  try {
    const cryptoKey = await getStorageKey()
    const iv = crypto.getRandomValues(new Uint8Array(12))
    const encoder = new TextEncoder()
    const data = encoder.encode(JSON.stringify(value))

    const encrypted = await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv },
      cryptoKey,
      data
    )

    const combined = new Uint8Array(iv.length + encrypted.byteLength)
    combined.set(iv)
    combined.set(new Uint8Array(encrypted), iv.length)

    localStorage.setItem(
      `secure_${key}`,
      btoa(String.fromCharCode(...combined))
    )

    return true
  } catch (e) {
    console.error('[Security] Secure store failed:', e)
    return false
  }
}

/**
 * Retrieve and decrypt securely stored data
 * @param {string} key - Storage key
 * @returns {Promise<*>} Decrypted value or null
 */
export async function secureRetrieve(key) {
  if (typeof window === 'undefined') return null

  try {
    const stored = localStorage.getItem(`secure_${key}`)
    if (!stored) return null

    const cryptoKey = await getStorageKey()
    const combined = Uint8Array.from(atob(stored), c => c.charCodeAt(0))

    const iv = combined.slice(0, 12)
    const encrypted = combined.slice(12)

    const decrypted = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv },
      cryptoKey,
      encrypted
    )

    const decoder = new TextDecoder()
    return JSON.parse(decoder.decode(decrypted))
  } catch (e) {
    console.error('[Security] Secure retrieve failed:', e)
    return null
  }
}

/**
 * Remove securely stored data
 * @param {string} key - Storage key
 */
export function secureRemove(key) {
  if (typeof window === 'undefined') return
  localStorage.removeItem(`secure_${key}`)
}

// ============================================
// Content Security
// ============================================

/**
 * List of dangerous HTML tags
 * @type {string[]}
 */
const DANGEROUS_TAGS = [
  'script', 'iframe', 'object', 'embed', 'form',
  'input', 'button', 'textarea', 'select', 'style',
  'link', 'meta', 'base', 'applet', 'frame', 'frameset',
  'layer', 'ilayer', 'bgsound', 'xml', 'xmp'
]

/**
 * List of dangerous attributes
 * @type {string[]}
 */
const DANGEROUS_ATTRS = [
  'onclick', 'ondblclick', 'onmousedown', 'onmouseup', 'onmouseover',
  'onmousemove', 'onmouseout', 'onmouseenter', 'onmouseleave',
  'onkeydown', 'onkeypress', 'onkeyup',
  'onload', 'onerror', 'onabort', 'onbeforeunload', 'onunload',
  'onsubmit', 'onreset', 'onchange', 'oninput', 'onfocus', 'onblur',
  'onscroll', 'onresize', 'ondrag', 'ondragstart', 'ondragend',
  'ondragenter', 'ondragleave', 'ondragover', 'ondrop',
  'oncopy', 'oncut', 'onpaste',
  'formaction', 'xlink:href', 'data-bind'
]

/**
 * Check if content contains potentially dangerous patterns
 * @param {string} content - Content to check
 * @returns {Object} Security analysis result
 */
export function analyzeContentSecurity(content) {
  if (typeof content !== 'string') {
    return { safe: true, issues: [] }
  }

  const issues = []
  const lowerContent = content.toLowerCase()

  // Check for dangerous tags
  for (const tag of DANGEROUS_TAGS) {
    const pattern = new RegExp(`<\\s*${tag}[\\s>]`, 'gi')
    if (pattern.test(content)) {
      issues.push({ type: 'dangerous_tag', tag, severity: 'high' })
    }
  }

  // Check for javascript: URLs
  if (/javascript:/i.test(content)) {
    issues.push({ type: 'javascript_url', severity: 'critical' })
  }

  // Check for data: URLs (can contain scripts)
  if (/data:\s*text\/html/i.test(content)) {
    issues.push({ type: 'data_url', severity: 'high' })
  }

  // Check for dangerous event handlers
  for (const attr of DANGEROUS_ATTRS) {
    const pattern = new RegExp(`${attr}\\s*=`, 'gi')
    if (pattern.test(content)) {
      issues.push({ type: 'dangerous_attribute', attribute: attr, severity: 'high' })
    }
  }

  // Check for base64-encoded scripts
  if (/eval\s*\(/i.test(content) || /Function\s*\(/i.test(content)) {
    issues.push({ type: 'eval_usage', severity: 'critical' })
  }

  // Check for expression() CSS
  if (/expression\s*\(/i.test(content)) {
    issues.push({ type: 'css_expression', severity: 'high' })
  }

  return {
    safe: issues.length === 0,
    issues,
    criticalCount: issues.filter(i => i.severity === 'critical').length,
    highCount: issues.filter(i => i.severity === 'high').length
  }
}

/**
 * Sanitize potentially dangerous content
 * @param {string} content - Content to sanitize
 * @returns {string} Sanitized content
 */
export function sanitizeContent(content) {
  if (typeof content !== 'string') return ''

  let sanitized = content

  // Remove dangerous tags
  for (const tag of DANGEROUS_TAGS) {
    const openPattern = new RegExp(`<\\s*${tag}[^>]*>`, 'gi')
    const closePattern = new RegExp(`<\\s*/\\s*${tag}\\s*>`, 'gi')
    sanitized = sanitized.replace(openPattern, '')
    sanitized = sanitized.replace(closePattern, '')
  }

  // Remove dangerous attributes
  for (const attr of DANGEROUS_ATTRS) {
    const pattern = new RegExp(`\\s*${attr}\\s*=\\s*["'][^"']*["']`, 'gi')
    sanitized = sanitized.replace(pattern, '')
  }

  // Remove javascript: URLs
  sanitized = sanitized.replace(/javascript:/gi, '')

  // Remove data: URLs with HTML
  sanitized = sanitized.replace(/data:\s*text\/html[^"'\s]*/gi, '')

  return sanitized
}

// ============================================
// Security Event Logging
// ============================================

/**
 * Security event types
 * @readonly
 * @enum {string}
 */
export const SecurityEventType = {
  XSS_ATTEMPT: 'xss_attempt',
  CSRF_FAILURE: 'csrf_failure',
  RATE_LIMIT_HIT: 'rate_limit_hit',
  AUTH_FAILURE: 'auth_failure',
  INVALID_INPUT: 'invalid_input',
  SUSPICIOUS_ACTIVITY: 'suspicious_activity'
}

/**
 * Security events buffer
 * @type {Array}
 */
const securityEvents = []

/**
 * Maximum security events to keep
 * @type {number}
 */
const MAX_SECURITY_EVENTS = 100

/**
 * Log a security event
 * @param {string} type - Event type from SecurityEventType
 * @param {Object} details - Event details
 */
export function logSecurityEvent(type, details = {}) {
  const event = {
    type,
    timestamp: new Date().toISOString(),
    details,
    userAgent: typeof navigator !== 'undefined' ? navigator.userAgent : 'unknown',
    url: typeof window !== 'undefined' ? window.location.href : 'unknown'
  }

  securityEvents.push(event)

  // Keep buffer size manageable
  if (securityEvents.length > MAX_SECURITY_EVENTS) {
    securityEvents.shift()
  }

  // Log to console in development
  if (typeof process !== 'undefined' && process.env.NODE_ENV === 'development') {
    console.warn('[Security Event]', event)
  }
}

/**
 * Get recent security events
 * @param {number} [count=10] - Number of events to return
 * @returns {Array} Recent security events
 */
export function getSecurityEvents(count = 10) {
  return securityEvents.slice(-count)
}

/**
 * Clear security events
 */
export function clearSecurityEvents() {
  securityEvents.length = 0
}

// ============================================
// Request Fingerprinting
// ============================================

/**
 * Generate a fingerprint for the current browser
 * Used for detecting session hijacking attempts
 * @returns {Promise<string>} Browser fingerprint
 */
export async function getBrowserFingerprint() {
  if (typeof window === 'undefined') return ''

  const components = [
    navigator.userAgent,
    navigator.language,
    screen.width,
    screen.height,
    screen.colorDepth,
    new Date().getTimezoneOffset(),
    navigator.hardwareConcurrency || 'unknown',
    navigator.deviceMemory || 'unknown'
  ]

  const fingerprint = components.join('|')

  // Hash the fingerprint
  const encoder = new TextEncoder()
  const data = encoder.encode(fingerprint)
  const hashBuffer = await crypto.subtle.digest('SHA-256', data)
  const hashArray = Array.from(new Uint8Array(hashBuffer))

  return hashArray.map(b => b.toString(16).padStart(2, '0')).join('')
}

/**
 * Validate browser fingerprint against stored value
 * @param {string} storedFingerprint - Previously stored fingerprint
 * @returns {Promise<boolean>} True if fingerprints match
 */
export async function validateFingerprint(storedFingerprint) {
  const currentFingerprint = await getBrowserFingerprint()
  return constantTimeCompare(currentFingerprint, storedFingerprint)
}

// ============================================
// Session Security
// ============================================

/**
 * Session timeout (30 minutes of inactivity)
 * @type {number}
 */
const SESSION_TIMEOUT = 30 * 60 * 1000

/**
 * Last activity timestamp key
 * @type {string}
 */
const LAST_ACTIVITY_KEY = 'nurunuru_last_activity'

/**
 * Update last activity timestamp
 */
export function updateActivity() {
  if (typeof window === 'undefined') return
  sessionStorage.setItem(LAST_ACTIVITY_KEY, Date.now().toString())
}

/**
 * Check if session has timed out
 * @returns {boolean} True if session is expired
 */
export function isSessionExpired() {
  if (typeof window === 'undefined') return false

  const lastActivity = sessionStorage.getItem(LAST_ACTIVITY_KEY)
  if (!lastActivity) return false

  const elapsed = Date.now() - parseInt(lastActivity, 10)
  return elapsed > SESSION_TIMEOUT
}

/**
 * Initialize session security
 * Sets up activity tracking and session monitoring
 */
export function initSessionSecurity() {
  if (typeof window === 'undefined') return

  // Track user activity
  const activityEvents = ['mousedown', 'keydown', 'scroll', 'touchstart']

  let debounceTimer = null
  const handleActivity = () => {
    if (debounceTimer) return
    debounceTimer = setTimeout(() => {
      updateActivity()
      debounceTimer = null
    }, 5000) // Debounce to every 5 seconds
  }

  activityEvents.forEach(event => {
    window.addEventListener(event, handleActivity, { passive: true })
  })

  // Initial activity update
  updateActivity()

  // Periodic session check
  setInterval(() => {
    if (isSessionExpired()) {
      // Emit session expired event
      window.dispatchEvent(new CustomEvent('session-expired'))
    }
  }, 60000) // Check every minute
}

// ============================================
// Origin Validation
// ============================================

/**
 * Allowed origins for cross-origin requests
 * @type {string[]}
 */
const ALLOWED_ORIGINS = [
  'https://nostr.build',
  'https://void.cat',
  'https://api.nostr.build'
]

/**
 * Check if origin is allowed
 * @param {string} origin - Origin to check
 * @returns {boolean} True if allowed
 */
export function isAllowedOrigin(origin) {
  if (!origin) return false
  return ALLOWED_ORIGINS.some(allowed => origin.startsWith(allowed))
}

/**
 * Add allowed origin
 * @param {string} origin - Origin to allow
 */
export function addAllowedOrigin(origin) {
  if (origin && !ALLOWED_ORIGINS.includes(origin)) {
    ALLOWED_ORIGINS.push(origin)
  }
}

// ============================================
// Secure Random
// ============================================

/**
 * Generate secure random integer
 * @param {number} min - Minimum value (inclusive)
 * @param {number} max - Maximum value (exclusive)
 * @returns {number} Random integer
 */
export function secureRandomInt(min, max) {
  const range = max - min
  const bytesNeeded = Math.ceil(Math.log2(range) / 8)
  const maxValid = Math.pow(256, bytesNeeded) - (Math.pow(256, bytesNeeded) % range)

  let randomValue
  do {
    const bytes = new Uint8Array(bytesNeeded)
    crypto.getRandomValues(bytes)
    randomValue = bytes.reduce((acc, byte, i) => acc + byte * Math.pow(256, i), 0)
  } while (randomValue >= maxValid)

  return min + (randomValue % range)
}

/**
 * Shuffle array securely
 * @template T
 * @param {T[]} array - Array to shuffle
 * @returns {T[]} Shuffled array (new array)
 */
export function secureShuffle(array) {
  const shuffled = [...array]
  for (let i = shuffled.length - 1; i > 0; i--) {
    const j = secureRandomInt(0, i + 1)
    ;[shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]]
  }
  return shuffled
}

// ============================================
// Export
// ============================================

export default {
  generateSecureToken,
  getCsrfToken,
  validateCsrfToken,
  secureStore,
  secureRetrieve,
  secureRemove,
  analyzeContentSecurity,
  sanitizeContent,
  SecurityEventType,
  logSecurityEvent,
  getSecurityEvents,
  clearSecurityEvents,
  getBrowserFingerprint,
  validateFingerprint,
  updateActivity,
  isSessionExpired,
  initSessionSecurity,
  isAllowedOrigin,
  addAllowedOrigin,
  secureRandomInt,
  secureShuffle
}
