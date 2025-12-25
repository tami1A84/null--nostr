// Service Worker for ぬるぬる PWA
const CACHE_NAME = 'nurunuru-v2'

// Assets to cache on install (app shell)
const PRECACHE_ASSETS = [
  '/',
  '/favicon.ico',
  '/favicon-192.png',
  '/favicon-512.png',
  '/nurunuru-star.png',
  '/manifest.json'
]

// Install event - cache essential assets
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then((cache) => {
        return cache.addAll(PRECACHE_ASSETS)
      })
      .then(() => {
        return self.skipWaiting()
      })
  )
})

// Activate event - clean up old caches
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((cacheNames) => {
        return Promise.all(
          cacheNames
            .filter((name) => name.startsWith('nurunuru-') && name !== CACHE_NAME)
            .map((name) => caches.delete(name))
        )
      })
      .then(() => {
        return self.clients.claim()
      })
  )
})

// Fetch event - stale-while-revalidate for app shell, network-first for API
self.addEventListener('fetch', (event) => {
  // Skip non-GET requests
  if (event.request.method !== 'GET') {
    return
  }

  const url = new URL(event.request.url)

  // Skip WebSocket requests
  if (url.protocol === 'wss:' || url.protocol === 'ws:') {
    return
  }

  // Skip external requests (API, relays, etc.)
  if (url.hostname !== self.location.hostname) {
    return
  }

  // For navigation and app shell - use stale-while-revalidate for fast loading
  if (event.request.mode === 'navigate' || 
      PRECACHE_ASSETS.some(asset => url.pathname === asset || url.pathname.endsWith('.js') || url.pathname.endsWith('.css'))) {
    event.respondWith(
      caches.match(event.request)
        .then((cachedResponse) => {
          // Return cache immediately and update in background
          const fetchPromise = fetch(event.request)
            .then((networkResponse) => {
              if (networkResponse && networkResponse.status === 200 && networkResponse.type === 'basic') {
                const responseToCache = networkResponse.clone()
                caches.open(CACHE_NAME)
                  .then((cache) => {
                    cache.put(event.request, responseToCache)
                  })
              }
              return networkResponse
            })
            .catch(() => cachedResponse)

          return cachedResponse || fetchPromise
        })
    )
    return
  }

  // For other same-origin requests - network first, cache fallback
  event.respondWith(
    fetch(event.request)
      .then((response) => {
        if (!response || response.status !== 200 || response.type !== 'basic') {
          return response
        }

        const responseToCache = response.clone()
        caches.open(CACHE_NAME)
          .then((cache) => {
            cache.put(event.request, responseToCache)
          })

        return response
      })
      .catch(() => {
        return caches.match(event.request)
          .then((cachedResponse) => {
            if (cachedResponse) {
              return cachedResponse
            }
            if (event.request.mode === 'navigate') {
              return caches.match('/')
            }
            return new Response('Offline', { status: 503 })
          })
      })
  )
})
