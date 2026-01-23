/**
 * Geohash Utility Library
 *
 * For location-based relay selection and user proximity calculations.
 * Based on NIP-65 relay list metadata support.
 *
 * Relay data sourced from: https://github.com/permissionlesstech/bitchat
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
 * Save user's location coordinates
 * @param {number} lat
 * @param {number} lon
 */
export function saveUserLocation(lat, lon) {
  if (typeof window !== 'undefined') {
    localStorage.setItem('user_location', JSON.stringify({ lat, lon }))
  }
}

/**
 * Load user's location coordinates
 * @returns {{lat: number, lon: number}|null}
 */
export function loadUserLocation() {
  if (typeof window !== 'undefined') {
    try {
      const data = localStorage.getItem('user_location')
      return data ? JSON.parse(data) : null
    } catch {
      return null
    }
  }
  return null
}

// ============================================
// GPS-based Relay Database
// Source: https://github.com/permissionlesstech/bitchat
// ============================================

/**
 * Global relay database with GPS coordinates
 * Includes popular relays from various regions
 */
export const GPS_RELAY_DATABASE = [
  // Japan
  { url: 'wss://yabu.me', lat: 35.6092, lon: 139.73, name: 'やぶみ', region: 'JP', priority: 1 },
  { url: 'wss://relay.nostr.wirednet.jp', lat: 34.706, lon: 135.493, name: 'WiredNet JP', region: 'JP', priority: 1 },
  { url: 'wss://r.kojira.io', lat: 35.6762, lon: 139.6503, name: 'Kojira', region: 'JP', priority: 1 },
  { url: 'wss://relay.origin.land', lat: 35.6673, lon: 139.751, name: 'Origin Land', region: 'JP', priority: 2 },
  { url: 'wss://v-relay.d02.vrtmrz.net', lat: 34.6937, lon: 135.502, name: 'vrtmrz', region: 'JP', priority: 2 },

  // Asia
  { url: 'wss://relay.0xchat.com', lat: 1.35208, lon: 103.82, name: '0xchat', region: 'SG', priority: 1 },
  { url: 'wss://nostr-01.yakihonne.com', lat: 1.29524, lon: 103.79, name: 'Yakihonne', region: 'SG', priority: 2 },
  { url: 'wss://nostr.dler.com', lat: 25.0501, lon: 121.565, name: 'dler', region: 'TW', priority: 2 },
  { url: 'wss://relay.islandbitcoin.com', lat: 12.8498, lon: 77.6545, name: 'Island Bitcoin', region: 'IN', priority: 2 },
  { url: 'wss://nostr.jerrynya.fun', lat: 31.2304, lon: 121.474, name: 'jerrynya', region: 'CN', priority: 2 },

  // North America
  { url: 'wss://relay.damus.io', lat: 43.6532, lon: -79.3832, name: 'Damus', region: 'NA', priority: 1 },
  { url: 'wss://relay.primal.net', lat: 43.6532, lon: -79.3832, name: 'Primal', region: 'NA', priority: 1 },
  { url: 'wss://relay.wellorder.net', lat: 45.5201, lon: -122.99, name: 'Wellorder', region: 'NA', priority: 2 },
  { url: 'wss://relay.illuminodes.com', lat: 47.6061, lon: -122.333, name: 'Illuminodes', region: 'NA', priority: 2 },
  { url: 'wss://relay.fundstr.me', lat: 42.3601, lon: -71.0589, name: 'Fundstr', region: 'NA', priority: 2 },
  { url: 'wss://nostrelites.org', lat: 41.8781, lon: -87.6298, name: 'Nostrelites', region: 'NA', priority: 2 },
  { url: 'wss://relay.westernbtc.com', lat: 44.5401, lon: -123.368, name: 'Western BTC', region: 'NA', priority: 2 },
  { url: 'wss://cyberspace.nostr1.com', lat: 40.7057, lon: -74.0136, name: 'Cyberspace', region: 'NA', priority: 2 },
  { url: 'wss://fanfares.nostr1.com', lat: 40.7128, lon: -74.006, name: 'Fanfares', region: 'NA', priority: 2 },

  // Europe
  { url: 'wss://nos.lol', lat: 50.4754, lon: 12.3683, name: 'nos.lol', region: 'EU', priority: 1 },
  { url: 'wss://relay.snort.social', lat: 53.3498, lon: -6.26031, name: 'Snort', region: 'EU', priority: 1 },
  { url: 'wss://nostr.wine', lat: 48.8566, lon: 2.35222, name: 'nostr.wine', region: 'EU', priority: 1 },
  { url: 'wss://relay.nostr.band', lat: 52.52, lon: 13.405, name: 'nostr.band', region: 'EU', priority: 1 },
  { url: 'wss://nostr.bond', lat: 50.1109, lon: 8.68213, name: 'nostr.bond', region: 'EU', priority: 2 },
  { url: 'wss://relay.thebluepulse.com', lat: 49.4521, lon: 11.0767, name: 'Blue Pulse', region: 'EU', priority: 2 },
  { url: 'wss://relay.lumina.rocks', lat: 49.0291, lon: 8.35695, name: 'Lumina', region: 'EU', priority: 2 },
  { url: 'wss://relay.dwadziesciajeden.pl', lat: 52.2297, lon: 21.0122, name: 'dwadziesciajeden', region: 'EU', priority: 2 },
  { url: 'wss://relay.angor.io', lat: 48.1046, lon: 11.6002, name: 'Angor', region: 'EU', priority: 2 },
  { url: 'wss://relay.malxte.de', lat: 52.52, lon: 13.405, name: 'malxte', region: 'EU', priority: 2 },
  { url: 'wss://purplerelay.com', lat: 50.1109, lon: 8.68213, name: 'Purple Relay', region: 'EU', priority: 2 },
  { url: 'wss://nostr.mom', lat: 50.4754, lon: 12.3683, name: 'nostr.mom', region: 'EU', priority: 2 },
  { url: 'wss://relay.nostrhub.fr', lat: 48.1045, lon: 11.6004, name: 'NostrHub FR', region: 'EU', priority: 2 },
  { url: 'wss://wot.dergigi.com', lat: 64.1476, lon: -21.9392, name: 'Gigi WoT', region: 'EU', priority: 2 },
  { url: 'wss://lightning.red', lat: 53.3498, lon: -6.26031, name: 'Lightning Red', region: 'EU', priority: 2 },

  // Scandinavia/Nordic
  { url: 'wss://r.alphaama.com', lat: 60.1699, lon: 24.9384, name: 'Alphaama', region: 'EU', priority: 2 },
  { url: 'wss://nostr.snowbla.de', lat: 60.1699, lon: 24.9384, name: 'Snowblade', region: 'EU', priority: 2 },
  { url: 'wss://relay.zone667.com', lat: 60.1699, lon: 24.9384, name: 'Zone667', region: 'EU', priority: 2 },

  // Russia
  { url: 'wss://adre.su', lat: 59.8845, lon: 30.3184, name: 'adre.su', region: 'RU', priority: 2 },

  // Middle East
  { url: 'wss://shu01.shugur.net', lat: 21.4902, lon: 39.2246, name: 'Shugur', region: 'ME', priority: 2 },

  // South America
  { url: 'wss://relay.internationalright-wing.org', lat: -22.5022, lon: -48.7114, name: 'IRW', region: 'SA', priority: 2 },

  // Global/CDN (use as fallback)
  { url: 'wss://relay.nostr.bg', lat: 42.6977, lon: 23.3219, name: 'nostr.bg', region: 'Global', priority: 1 },
  { url: 'wss://nostr.mutinywallet.com', lat: 37.7749, lon: -122.4194, name: 'Mutiny', region: 'Global', priority: 2 },
]

// Directory relays for NIP-65 relay list discovery
export const DIRECTORY_RELAYS = [
  { url: 'wss://directory.yabu.me', name: 'Directory yabu.me', region: 'JP', purpose: 'discover' },
  { url: 'wss://purplepag.es', name: 'Purple Pages', region: 'Global', purpose: 'discover' },
]

/**
 * Regional relay recommendations based on geohash prefix
 * Maps geohash prefixes to recommended relays
 */
export const REGIONAL_RELAYS = {
  // Japan (xn, xp, wv, ws, wt, wu)
  'xn': [
    { url: 'wss://yabu.me', name: 'やぶみ', region: 'JP' },
    { url: 'wss://relay.nostr.wirednet.jp', name: 'WiredNet JP', region: 'JP' },
    { url: 'wss://r.kojira.io', name: 'Kojira', region: 'JP' },
  ],
  'xp': [
    { url: 'wss://yabu.me', name: 'やぶみ', region: 'JP' },
    { url: 'wss://relay.nostr.wirednet.jp', name: 'WiredNet JP', region: 'JP' },
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
    { url: 'wss://nos.lol', name: 'nos.lol', region: 'EU' },
    { url: 'wss://relay.snort.social', name: 'Snort', region: 'EU' },
    { url: 'wss://nostr.wine', name: 'nostr.wine', region: 'EU' },
  ],
  'gc': [
    { url: 'wss://relay.nostr.bg', name: 'nostr.bg', region: 'EU' },
  ],

  // North America (dr, dq, dn, 9)
  'dr': [
    { url: 'wss://relay.damus.io', name: 'Damus', region: 'US' },
    { url: 'wss://relay.primal.net', name: 'Primal', region: 'US' },
    { url: 'wss://nos.lol', name: 'nos.lol', region: 'Global' },
  ],
  'dq': [
    { url: 'wss://relay.damus.io', name: 'Damus', region: 'US' },
  ],
  '9': [
    { url: 'wss://relay.damus.io', name: 'Damus', region: 'US' },
    { url: 'wss://nos.lol', name: 'nos.lol', region: 'Global' },
  ],

  // Singapore/SE Asia (w2)
  'w2': [
    { url: 'wss://relay.0xchat.com', name: '0xchat', region: 'SG' },
    { url: 'wss://nostr-01.yakihonne.com', name: 'Yakihonne', region: 'SG' },
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
 * Find nearest relays by GPS coordinates
 * @param {number} userLat - User's latitude
 * @param {number} userLon - User's longitude
 * @param {number} count - Number of relays to return (default: 5)
 * @param {number} maxDistance - Maximum distance in km (default: unlimited)
 * @returns {Array<{url: string, name: string, region: string, distance: number}>}
 */
export function findNearestRelays(userLat, userLon, count = 5, maxDistance = Infinity) {
  const relaysWithDistance = GPS_RELAY_DATABASE.map(relay => ({
    ...relay,
    distance: calculateDistance(userLat, userLon, relay.lat, relay.lon)
  }))

  // Sort by priority first, then by distance
  relaysWithDistance.sort((a, b) => {
    // Priority 1 relays are preferred
    if (a.priority !== b.priority) {
      return a.priority - b.priority
    }
    return a.distance - b.distance
  })

  // Filter by max distance and limit count
  return relaysWithDistance
    .filter(r => r.distance <= maxDistance)
    .slice(0, count)
}

/**
 * Generate NIP-65 relay list configuration based on location
 * Following Gossip's recommendations:
 * - 3-4 Inbox relays (read)
 * - 3-5 Outbox relays (write)
 * - 2-3 Discovery relays
 *
 * @param {number} userLat - User's latitude
 * @param {number} userLon - User's longitude
 * @returns {{inbox: Array, outbox: Array, discover: Array, combined: Array}}
 */
export function generateRelayListByLocation(userLat, userLon) {
  // Find nearest relays
  const nearestRelays = findNearestRelays(userLat, userLon, 10)

  // Select outbox relays (3-5): prefer nearest priority 1 relays
  const outbox = nearestRelays
    .filter(r => r.priority === 1)
    .slice(0, 3)

  // Add more outbox if needed
  if (outbox.length < 3) {
    const additionalOutbox = nearestRelays
      .filter(r => !outbox.some(o => o.url === r.url))
      .slice(0, 3 - outbox.length)
    outbox.push(...additionalOutbox)
  }

  // Select inbox relays (3-4): mix of nearest and global
  const inbox = []

  // Add 2 nearest relays
  nearestRelays
    .filter(r => !outbox.some(o => o.url === r.url))
    .slice(0, 2)
    .forEach(r => inbox.push(r))

  // Add 1-2 global relays for better reachability
  const globalRelays = GPS_RELAY_DATABASE
    .filter(r => r.priority === 1 && !outbox.some(o => o.url === r.url) && !inbox.some(i => i.url === r.url))
    .slice(0, 2)
  inbox.push(...globalRelays)

  // Discovery relays
  const discover = DIRECTORY_RELAYS.map(r => ({ ...r, distance: 0 }))

  // Combined list for NIP-65 publishing
  const combined = []

  // Add outbox relays (write)
  outbox.forEach(r => {
    combined.push({ url: r.url, read: false, write: true })
  })

  // Add inbox relays (read)
  inbox.forEach(r => {
    const existing = combined.find(c => c.url === r.url)
    if (existing) {
      existing.read = true
    } else {
      combined.push({ url: r.url, read: true, write: false })
    }
  })

  // Add some relays as both read and write for better connectivity
  const bothRelays = nearestRelays.slice(0, 2)
  bothRelays.forEach(r => {
    const existing = combined.find(c => c.url === r.url)
    if (existing) {
      existing.read = true
      existing.write = true
    } else {
      combined.push({ url: r.url, read: true, write: true })
    }
  })

  return {
    inbox: inbox.slice(0, 4),
    outbox: outbox.slice(0, 5),
    discover,
    combined
  }
}

/**
 * Auto-detect location and return recommended relays with NIP-65 configuration
 * @returns {Promise<{geohash: string, relays: Array, location: {lat: number, lon: number}, nip65Config: Object}>}
 */
export async function autoDetectRelays() {
  try {
    const location = await getCurrentLocation()
    const geohash = encodeGeohash(location.lat, location.lon, 5)

    // Save location data
    saveUserGeohash(geohash)
    saveUserLocation(location.lat, location.lon)

    // Get relays by geohash (legacy method)
    const relays = getRelaysByGeohash(geohash)

    // Generate NIP-65 configuration based on GPS
    const nip65Config = generateRelayListByLocation(location.lat, location.lon)

    // Find nearest relays with distance info
    const nearestRelays = findNearestRelays(location.lat, location.lon, 10)

    return {
      geohash,
      relays,
      location,
      nip65Config,
      nearestRelays
    }
  } catch (error) {
    console.error('Auto-detect location failed:', error)
    return {
      geohash: null,
      relays: REGIONAL_RELAYS.default,
      location: null,
      nip65Config: null,
      nearestRelays: null,
      error: error.message
    }
  }
}

/**
 * Get relay configuration summary for display
 * @param {{inbox: Array, outbox: Array, discover: Array}} config
 * @returns {string}
 */
export function getRelayConfigSummary(config) {
  if (!config) return 'Not configured'

  const parts = []
  if (config.outbox?.length) {
    parts.push(`Outbox: ${config.outbox.length}`)
  }
  if (config.inbox?.length) {
    parts.push(`Inbox: ${config.inbox.length}`)
  }
  if (config.discover?.length) {
    parts.push(`Discover: ${config.discover.length}`)
  }

  return parts.join(', ') || 'No relays configured'
}

/**
 * Format distance for display
 * @param {number} km - Distance in kilometers
 * @returns {string}
 */
export function formatDistance(km) {
  if (km < 1) {
    return `${Math.round(km * 1000)}m`
  } else if (km < 100) {
    return `${km.toFixed(1)}km`
  } else {
    return `${Math.round(km)}km`
  }
}
