'use client'

import { useState, useEffect, useRef } from 'react'

// In-memory cache for OG data
const ogDataCache = new Map()

// Rate limiting - track last request time per domain
const domainLastRequest = new Map()
const MIN_REQUEST_INTERVAL = 1000 // ms between requests to same domain

// Global rate limiting for microlink.io API
let lastMicrolinkRequest = 0
const MICROLINK_MIN_INTERVAL = 1000 // ms between any requests to microlink
const requestQueue = []
let isProcessingQueue = false

// Track 429 errors to back off temporarily
let rateLimitBackoffUntil = 0
const RATE_LIMIT_BACKOFF_MS = 60000 // Back off for 60 seconds on 429

/**
 * Validate URL to prevent invalid requests
 */
function isValidUrl(urlString) {
  try {
    const url = new URL(urlString)
    // Check for valid protocol
    if (!['http:', 'https:'].includes(url.protocol)) {
      return false
    }
    // Check for valid hostname
    if (!url.hostname || url.hostname.length === 0) {
      return false
    }
    // Check for malformed URLs (e.g., trailing quotes)
    if (urlString.includes("'") || urlString.match(/['"]\s*$/)) {
      return false
    }
    return true
  } catch {
    return false
  }
}

/**
 * URLPreview Component
 * Displays a rich preview card for external URLs with OpenGraph metadata
 * Uses microlink.io API for fetching OG data (CORS-enabled)
 */
export default function URLPreview({ url, compact = false }) {
  const [ogData, setOgData] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const mountedRef = useRef(true)
  const fetchAttemptedRef = useRef(false)

  useEffect(() => {
    mountedRef.current = true
    fetchAttemptedRef.current = false

    return () => {
      mountedRef.current = false
    }
  }, [url])

  useEffect(() => {
    if (!url || fetchAttemptedRef.current) {
      return
    }

    // Validate URL first
    if (!isValidUrl(url)) {
      setLoading(false)
      setError(true)
      return
    }

    // Skip image/video URLs - they are already displayed inline
    if (url.match(/\.(jpg|jpeg|png|gif|webp|svg|mp4|webm|mov)(\?.*)?$/i)) {
      setLoading(false)
      setError(true)
      return
    }

    fetchAttemptedRef.current = true

    // Check in-memory cache first
    const cached = ogDataCache.get(url)
    if (cached) {
      if (cached.error) {
        setError(true)
        setLoading(false)
      } else {
        setOgData(cached)
        setLoading(false)
      }
      return
    }

    // Check localStorage cache
    try {
      const storedCache = localStorage.getItem(`og:${url}`)
      if (storedCache) {
        const parsed = JSON.parse(storedCache)
        if (Date.now() - parsed.timestamp < 1000 * 60 * 60 * 24) { // 24 hours
          if (parsed.error) {
            ogDataCache.set(url, { error: true })
            setError(true)
            setLoading(false)
          } else {
            ogDataCache.set(url, parsed.data)
            setOgData(parsed.data)
            setLoading(false)
          }
          return
        }
      }
    } catch {}

    const fetchOGData = async () => {
      // Check if we're in rate limit backoff period
      if (Date.now() < rateLimitBackoffUntil) {
        if (mountedRef.current) {
          ogDataCache.set(url, { error: true })
          setError(true)
          setLoading(false)
        }
        return
      }

      // Global rate limiting for microlink API
      const timeSinceMicrolinkRequest = Date.now() - lastMicrolinkRequest
      if (timeSinceMicrolinkRequest < MICROLINK_MIN_INTERVAL) {
        await new Promise(resolve =>
          setTimeout(resolve, MICROLINK_MIN_INTERVAL - timeSinceMicrolinkRequest)
        )
      }

      // Rate limiting per domain
      try {
        const domain = new URL(url).hostname
        const lastRequest = domainLastRequest.get(domain) || 0
        const timeSinceLastRequest = Date.now() - lastRequest

        if (timeSinceLastRequest < MIN_REQUEST_INTERVAL) {
          await new Promise(resolve => setTimeout(resolve, MIN_REQUEST_INTERVAL - timeSinceLastRequest))
        }

        domainLastRequest.set(domain, Date.now())
      } catch {}

      // Retry logic with exponential backoff
      const maxRetries = 2
      let lastError = null

      for (let attempt = 0; attempt <= maxRetries; attempt++) {
        try {
          if (!mountedRef.current) return

          // Update last request timestamp
          lastMicrolinkRequest = Date.now()

          const controller = new AbortController()
          const timeoutId = setTimeout(() => controller.abort(), 10000)

          const response = await fetch(
            `https://api.microlink.io?url=${encodeURIComponent(url)}`,
            {
              headers: { 'Accept': 'application/json' },
              signal: controller.signal
            }
          )

          clearTimeout(timeoutId)

          if (!mountedRef.current) return

          // Handle rate limiting
          if (response.status === 429) {
            // Set global backoff
            rateLimitBackoffUntil = Date.now() + RATE_LIMIT_BACKOFF_MS

            // Try to get Retry-After header
            const retryAfter = response.headers.get('Retry-After')
            if (retryAfter) {
              const retryMs = parseInt(retryAfter) * 1000
              if (!isNaN(retryMs)) {
                rateLimitBackoffUntil = Date.now() + retryMs
              }
            }

            throw new Error('Rate limited')
          }

          if (!response.ok) {
            throw new Error(`HTTP ${response.status}`)
          }

          const result = await response.json()

          if (!mountedRef.current) return

          if (result.status !== 'success' || !result.data) {
            throw new Error('No data')
          }

          const data = {
            url,
            title: result.data.title || null,
            description: result.data.description || null,
            image: result.data.image?.url || null,
            siteName: result.data.publisher || null,
            favicon: result.data.logo?.url || null,
          }

          // Only cache if we got useful data
          if (data.title) {
            ogDataCache.set(url, data)
            try {
              localStorage.setItem(`og:${url}`, JSON.stringify({
                data,
                timestamp: Date.now()
              }))
            } catch {}
          }

          if (mountedRef.current) {
            setOgData(data)
            setLoading(false)
          }
          return // Success, exit retry loop

        } catch (e) {
          lastError = e

          // Don't retry on rate limit errors
          if (e.message === 'Rate limited') {
            break
          }

          if (attempt < maxRetries) {
            // Wait before retry with exponential backoff (2s, 4s, 8s)
            await new Promise(resolve => setTimeout(resolve, 2000 * Math.pow(2, attempt)))
          }
        }
      }

      // All retries failed
      if (mountedRef.current) {
        ogDataCache.set(url, { error: true })
        try {
          localStorage.setItem(`og:${url}`, JSON.stringify({
            error: true,
            timestamp: Date.now()
          }))
        } catch {}
        setError(true)
        setLoading(false)
      }
    }

    // Delay fetch slightly to avoid overwhelming the API
    // Use random delay to distribute requests over time
    const delay = 200 + Math.random() * 500 // 200-700ms
    const timeoutId = setTimeout(fetchOGData, delay)
    return () => clearTimeout(timeoutId)
  }, [url])

  // Don't show anything if error or no data
  if (error || (!loading && !ogData)) {
    return null
  }

  // Loading skeleton
  if (loading) {
    return (
      <div className={`border border-[var(--border-color)] rounded-lg overflow-hidden bg-[var(--bg-secondary)] my-2 ${compact ? 'max-w-xs' : ''}`}>
        <div className="animate-pulse">
          {!compact && <div className="h-32 bg-[var(--bg-tertiary)]" />}
          <div className="p-3">
            <div className="h-4 bg-[var(--bg-tertiary)] rounded w-3/4 mb-2" />
            <div className="h-3 bg-[var(--bg-tertiary)] rounded w-full mb-1" />
            <div className="h-3 bg-[var(--bg-tertiary)] rounded w-2/3" />
          </div>
        </div>
      </div>
    )
  }

  // Only show if we have at least a title
  if (!ogData.title) {
    return null
  }

  const handleClick = (e) => {
    e.stopPropagation()
  }

  // Extract domain for display
  let displayDomain = ''
  try {
    const urlObj = new URL(url)
    displayDomain = urlObj.hostname.replace(/^www\./, '')
  } catch {}

  if (compact) {
    // Compact version - horizontal layout
    return (
      <a
        href={url}
        target="_blank"
        rel="noopener noreferrer"
        onClick={handleClick}
        className="block border border-[var(--border-color)] rounded-lg overflow-hidden bg-[var(--bg-secondary)] hover:bg-[var(--bg-tertiary)] transition-colors my-2 max-w-sm"
      >
        <div className="flex items-center gap-3 p-2">
          {ogData.image ? (
            <img
              src={ogData.image}
              alt=""
              className="w-16 h-16 object-cover rounded flex-shrink-0"
              onError={(e) => { e.target.style.display = 'none' }}
            />
          ) : ogData.favicon ? (
            <div className="w-16 h-16 bg-[var(--bg-tertiary)] rounded flex-shrink-0 flex items-center justify-center">
              <img
                src={ogData.favicon}
                alt=""
                className="w-6 h-6"
                onError={(e) => { e.target.style.display = 'none' }}
              />
            </div>
          ) : (
            <div className="w-16 h-16 bg-[var(--bg-tertiary)] rounded flex-shrink-0 flex items-center justify-center">
              <svg className="w-6 h-6 text-[var(--text-tertiary)]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M10 13a5 5 0 007.54.54l3-3a5 5 0 00-7.07-7.07l-1.72 1.71"/>
                <path d="M14 11a5 5 0 00-7.54-.54l-3 3a5 5 0 007.07 7.07l1.71-1.71"/>
              </svg>
            </div>
          )}
          <div className="min-w-0 flex-1">
            <p className="text-sm font-medium text-[var(--text-primary)] line-clamp-2">
              {ogData.title}
            </p>
            <p className="text-xs text-[var(--text-tertiary)] mt-0.5 truncate">
              {displayDomain}
            </p>
          </div>
        </div>
      </a>
    )
  }

  // Full version - vertical layout with image
  return (
    <a
      href={url}
      target="_blank"
      rel="noopener noreferrer"
      onClick={handleClick}
      className="block border border-[var(--border-color)] rounded-lg overflow-hidden bg-[var(--bg-secondary)] hover:bg-[var(--bg-tertiary)] transition-colors my-2"
    >
      {ogData.image && (
        <div className="relative aspect-[1.91/1] max-h-48 overflow-hidden bg-[var(--bg-tertiary)]">
          <img
            src={ogData.image}
            alt=""
            className="w-full h-full object-cover"
            onError={(e) => { e.target.parentElement.style.display = 'none' }}
          />
        </div>
      )}
      <div className="p-3">
        <div className="flex items-center gap-2 mb-1">
          {ogData.favicon && (
            <img
              src={ogData.favicon}
              alt=""
              className="w-4 h-4 flex-shrink-0"
              onError={(e) => { e.target.style.display = 'none' }}
            />
          )}
          <span className="text-xs text-[var(--text-tertiary)] truncate">
            {ogData.siteName || displayDomain}
          </span>
        </div>
        <h3 className="text-sm font-medium text-[var(--text-primary)] line-clamp-2 mb-1">
          {ogData.title}
        </h3>
        {ogData.description && (
          <p className="text-xs text-[var(--text-secondary)] line-clamp-2">
            {ogData.description}
          </p>
        )}
      </div>
    </a>
  )
}
