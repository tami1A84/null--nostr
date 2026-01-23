/**
 * Geohash Utility Library
 *
 * For location-based relay selection and user proximity calculations.
 * Based on NIP-65 relay list metadata support.
 */

// Base32 encoding characters for geohash
const BASE32_CODES = '0123456789bcdefghjkmnpqrstuvwxyz'
const BASE32_CODES_DICT = {}
for (let i = 0; i < BASE32_CODES.length; i++) {
  BASE32_CODES_DICT[BASE32_CODES.charAt(i)] = i
}

/**
 * Encode latitude/longitude to geohash
 * @param {number} lat - Latitude (-90 to 90)
 * @param {number} lon - Longitude (-180 to 180)
 * @param {number} precision - Geohash length (default: 5 for ~5km accuracy)
 * @returns {string} Geohash string
 */
export function encodeGeohash(lat, lon, precision = 5) {
  let latRange = [-90, 90]
  let lonRange = [-180, 180]
  let hash = ''
  let bit = 0
  let ch = 0
  let isLon = true

  while (hash.length < precision) {
    if (isLon) {
      const mid = (lonRange[0] + lonRange[1]) / 2
      if (lon > mid) {
        ch |= (1 << (4 - bit))
        lonRange[0] = mid
      } else {
        lonRange[1] = mid
      }
    } else {
      const mid = (latRange[0] + latRange[1]) / 2
      if (lat > mid) {
        ch |= (1 << (4 - bit))
        latRange[0] = mid
      } else {
        latRange[1] = mid
      }
    }

    isLon = !isLon
    if (bit < 4) {
      bit++
    } else {
      hash += BASE32_CODES[ch]
      bit = 0
      ch = 0
    }
  }

  return hash
}

/**
 * Decode geohash to latitude/longitude bounds
 * @param {string} hash - Geohash string
 * @returns {{lat: number, lon: number, latErr: number, lonErr: number}}
 */
export function decodeGeohash(hash) {
  let latRange = [-90, 90]
  let lonRange = [-180, 180]
  let isLon = true

  for (let i = 0; i < hash.length; i++) {
    const code = BASE32_CODES_DICT[hash.charAt(i).toLowerCase()]
    if (code === undefined) continue

    for (let bits = 4; bits >= 0; bits--) {
      const bit = (code >> bits) & 1
      if (isLon) {
        const mid = (lonRange[0] + lonRange[1]) / 2
        if (bit === 1) {
          lonRange[0] = mid
        } else {
          lonRange[1] = mid
        }
      } else {
        const mid = (latRange[0] + latRange[1]) / 2
        if (bit === 1) {
          latRange[0] = mid
        } else {
          latRange[1] = mid
        }
      }
      isLon = !isLon
    }
  }

  const lat = (latRange[0] + latRange[1]) / 2
  const lon = (lonRange[0] + lonRange[1]) / 2
  const latErr = (latRange[1] - latRange[0]) / 2
  const lonErr = (lonRange[1] - lonRange[0]) / 2

  return { lat, lon, latErr, lonErr }
}

/**
 * Check if two geohashes share a common prefix
 * @param {string} hash1 - First geohash
 * @param {string} hash2 - Second geohash
 * @param {number} minMatch - Minimum characters to match
 * @returns {boolean}
 */
export function geohashesMatch(hash1, hash2, minMatch = 3) {
  if (!hash1 || !hash2) return false
  const len = Math.min(hash1.length, hash2.length, minMatch)
  return hash1.substring(0, len).toLowerCase() === hash2.substring(0, len).toLowerCase()
}

/**
 * Calculate approximate distance between two points in km
 * @param {number} lat1 - First latitude
 * @param {number} lon1 - First longitude
 * @param {number} lat2 - Second latitude
 * @param {number} lon2 - Second longitude
 * @returns {number} Distance in kilometers
 */
export function calculateDistance(lat1, lon1, lat2, lon2) {
  const R = 6371 // Earth's radius in km
  const dLat = (lat2 - lat1) * Math.PI / 180
  const dLon = (lon2 - lon1) * Math.PI / 180
  const a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
    Math.sin(dLon / 2) * Math.sin(dLon / 2)
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
  return R * c
}

/**
 * Get user's current geolocation
 * @returns {Promise<{lat: number, lon: number}>}
 */
export function getCurrentLocation() {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) {
      reject(new Error('Geolocation is not supported'))
      return
    }

    navigator.geolocation.getCurrentPosition(
      (position) => {
        resolve({
          lat: position.coords.latitude,
          lon: position.coords.longitude
        })
      },
      (error) => {
        let message = 'Location access denied'
        if (error.code === 2) message = 'Location unavailable'
        if (error.code === 3) message = 'Location timeout'
        reject(new Error(message))
      },
      {
        enableHighAccuracy: false,
        timeout: 10000,
        maximumAge: 300000 // Cache for 5 minutes
      }
    )
  })
}

/**
 * Store user's geohash in localStorage
 * @param {string} geohash
 */
export function saveUserGeohash(geohash) {
  if (typeof window !== 'undefined') {
    localStorage.setItem('user_geohash', geohash)
  }
}

/**
 * Load user's geohash from localStorage
 * @returns {string|null}
 */
export function loadUserGeohash() {
  if (typeof window !== 'undefined') {
    return localStorage.getItem('user_geohash')
  }
  return null
}

/**
 * Regional relay recommendations based on geohash prefix
 * Maps geohash prefixes to recommended relays
 */
export const REGIONAL_RELAYS = {
  // Japan (xn, xp, wv, ws, wt, wu)
  'xn': [
    { url: 'wss://yabu.me', name: 'やぶみ', region: 'JP' },
    { url: 'wss://relay-jp.nostr.wirednet.jp', name: 'WiredNet JP', region: 'JP' },
    { url: 'wss://r.kojira.io', name: 'Kojira', region: 'JP' },
  ],
  'xp': [
    { url: 'wss://yabu.me', name: 'やぶみ', region: 'JP' },
    { url: 'wss://relay-jp.nostr.wirednet.jp', name: 'WiredNet JP', region: 'JP' },
  ],
  'wv': [
    { url: 'wss://yabu.me', name: 'やぶみ', region: 'JP' },
    { url: 'wss://r.kojira.io', name: 'Kojira', region: 'JP' },
  ],

  // Korea (wy)
  'wy': [
    { url: 'wss://relay.nostr.band', name: 'nostr.band', region: 'Global' },
    { url: 'wss://nos.lol', name: 'nos.lol', region: 'Global' },
  ],

  // Europe (u, gc, gb, gf)
  'u': [
    { url: 'wss://relay.nostr.bg', name: 'nostr.bg', region: 'EU' },
    { url: 'wss://nostr.wine', name: 'nostr.wine', region: 'EU' },
  ],
  'gc': [
    { url: 'wss://relay.nostr.bg', name: 'nostr.bg', region: 'EU' },
  ],

  // North America (dr, dq, dn, 9)
  'dr': [
    { url: 'wss://relay.damus.io', name: 'Damus', region: 'US' },
    { url: 'wss://nos.lol', name: 'nos.lol', region: 'Global' },
  ],
  'dq': [
    { url: 'wss://relay.damus.io', name: 'Damus', region: 'US' },
  ],
  '9': [
    { url: 'wss://relay.damus.io', name: 'Damus', region: 'US' },
    { url: 'wss://nos.lol', name: 'nos.lol', region: 'Global' },
  ],

  // Default/Global
  'default': [
    { url: 'wss://nos.lol', name: 'nos.lol', region: 'Global' },
    { url: 'wss://relay.damus.io', name: 'Damus', region: 'Global' },
    { url: 'wss://relay.snort.social', name: 'Snort', region: 'Global' },
  ]
}

/**
 * Get recommended relays based on geohash
 * @param {string} geohash - User's geohash
 * @returns {Array<{url: string, name: string, region: string}>}
 */
export function getRelaysByGeohash(geohash) {
  if (!geohash) return REGIONAL_RELAYS.default

  // Check progressively shorter prefixes
  for (let len = Math.min(2, geohash.length); len >= 1; len--) {
    const prefix = geohash.substring(0, len).toLowerCase()
    if (REGIONAL_RELAYS[prefix]) {
      return REGIONAL_RELAYS[prefix]
    }
  }

  return REGIONAL_RELAYS.default
}

/**
 * Auto-detect location and return recommended relays
 * @returns {Promise<{geohash: string, relays: Array, location: {lat: number, lon: number}}>}
 */
export async function autoDetectRelays() {
  try {
    const location = await getCurrentLocation()
    const geohash = encodeGeohash(location.lat, location.lon, 5)
    const relays = getRelaysByGeohash(geohash)

    // Save geohash for future use
    saveUserGeohash(geohash)

    return { geohash, relays, location }
  } catch (error) {
    console.error('Auto-detect location failed:', error)
    return {
      geohash: null,
      relays: REGIONAL_RELAYS.default,
      location: null,
      error: error.message
    }
  }
}
