'use client'

import { useState, useEffect } from 'react'

// In-memory cache for OG data
const ogDataCache = new Map()

/**
 * URLPreview Component
 * Displays a rich preview card for external URLs with OpenGraph metadata
 * Uses microlink.io API for fetching OG data (CORS-enabled)
 *
 * @param {Object} props
 * @param {string} props.url - The URL to preview
 * @param {boolean} [props.compact] - Show compact version (smaller)
 */
export default function URLPreview({ url, compact = false }) {
  const [ogData, setOgData] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)

  useEffect(() => {
    if (!url) {
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

    let mounted = true

    const fetchOGData = async () => {
      try {
        // Use microlink.io API for fetching OG data
        const response = await fetch(
          `https://api.microlink.io?url=${encodeURIComponent(url)}`,
          { headers: { 'Accept': 'application/json' } }
        )

        if (!mounted) return

        if (!response.ok) {
          throw new Error('Failed to fetch')
        }

        const result = await response.json()

        if (!mounted) return

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

        // Cache the data
        ogDataCache.set(url, data)
        try {
          localStorage.setItem(`og:${url}`, JSON.stringify({
            data,
            timestamp: Date.now()
          }))
        } catch {}

        setOgData(data)
        setLoading(false)
      } catch (e) {
        if (mounted) {
          // Cache the error to avoid repeated requests
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
    }

    fetchOGData()

    return () => { mounted = false }
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
