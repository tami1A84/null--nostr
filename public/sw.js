// Service Worker for ぬるぬる PWA
// 低帯域最適化版 (1.5Mbps対応)
const CACHE_NAME = 'nurunuru-v3'
const IMAGE_CACHE_NAME = 'nurunuru-images-v1'
const API_CACHE_NAME = 'nurunuru-api-v1'

// キャッシュの最大サイズ (アイテム数)
const MAX_IMAGE_CACHE_SIZE = 100
const MAX_API_CACHE_SIZE = 50

// Assets to cache on install (app shell)
const PRECACHE_ASSETS = [
  '/',
  '/favicon.ico',
  '/favicon-192.png',
  '/favicon-512.png',
  '/nurunuru-star.png',
  '/manifest.json'
]

// 画像ホストのパターン
const IMAGE_HOSTS = [
  'nostr.build',
  'image.nostr.build',
  'void.cat',
  'i.imgur.com',
  'wsrv.nl',
  'imgproxy.iris.to',
  'media.snort.social',
  'blossom.'
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
            .filter((name) => {
              // 古いバージョンのキャッシュを削除
              return (name.startsWith('nurunuru-') && name !== CACHE_NAME && name !== IMAGE_CACHE_NAME && name !== API_CACHE_NAME)
            })
            .map((name) => caches.delete(name))
        )
      })
      .then(() => {
        return self.clients.claim()
      })
  )
})

/**
 * 画像URLかどうかを判定
 */
function isImageRequest(url) {
  const pathname = url.pathname.toLowerCase()
  const hostname = url.hostname.toLowerCase()

  // 画像拡張子チェック
  if (/\.(jpg|jpeg|png|gif|webp|svg|ico)$/i.test(pathname)) {
    return true
  }

  // 画像ホストチェック
  if (IMAGE_HOSTS.some(host => hostname.includes(host))) {
    return true
  }

  return false
}

/**
 * キャッシュサイズを制限
 */
async function limitCacheSize(cacheName, maxSize) {
  const cache = await caches.open(cacheName)
  const keys = await cache.keys()

  if (keys.length > maxSize) {
    // 古いエントリを削除（FIFO）
    const toDelete = keys.slice(0, keys.length - maxSize)
    await Promise.all(toDelete.map(key => cache.delete(key)))
  }
}

/**
 * 画像のキャッシュ戦略: Cache First (高速表示)
 */
async function handleImageRequest(request) {
  const cache = await caches.open(IMAGE_CACHE_NAME)
  const cachedResponse = await cache.match(request)

  if (cachedResponse) {
    // バックグラウンドで更新
    fetch(request)
      .then(response => {
        if (response && response.status === 200) {
          cache.put(request, response.clone())
          limitCacheSize(IMAGE_CACHE_NAME, MAX_IMAGE_CACHE_SIZE)
        }
      })
      .catch(() => {})

    return cachedResponse
  }

  // キャッシュにない場合はネットワークから取得
  try {
    const response = await fetch(request)
    if (response && response.status === 200) {
      cache.put(request, response.clone())
      limitCacheSize(IMAGE_CACHE_NAME, MAX_IMAGE_CACHE_SIZE)
    }
    return response
  } catch (error) {
    // オフライン時のフォールバック（プレースホルダー画像）
    return new Response(
      `<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" viewBox="0 0 100 100">
        <rect fill="#EBEDF0" width="100" height="100"/>
        <text x="50" y="55" font-family="sans-serif" font-size="12" fill="#767676" text-anchor="middle">Offline</text>
      </svg>`,
      {
        status: 200,
        headers: { 'Content-Type': 'image/svg+xml' }
      }
    )
  }
}

/**
 * アプリシェルのキャッシュ戦略: Stale While Revalidate
 */
async function handleAppShellRequest(request) {
  const cache = await caches.open(CACHE_NAME)
  const cachedResponse = await cache.match(request)

  const fetchPromise = fetch(request)
    .then((networkResponse) => {
      if (networkResponse && networkResponse.status === 200 && networkResponse.type === 'basic') {
        const responseToCache = networkResponse.clone()
        cache.put(request, responseToCache)
      }
      return networkResponse
    })
    .catch(() => cachedResponse)

  return cachedResponse || fetchPromise
}

/**
 * その他のリクエスト: Network First, Cache Fallback
 */
async function handleDefaultRequest(request) {
  try {
    const response = await fetch(request)

    if (!response || response.status !== 200 || response.type !== 'basic') {
      return response
    }

    const cache = await caches.open(CACHE_NAME)
    cache.put(request, response.clone())

    return response
  } catch (error) {
    const cachedResponse = await caches.match(request)
    if (cachedResponse) {
      return cachedResponse
    }

    // ナビゲーションリクエストの場合はルートを返す
    if (request.mode === 'navigate') {
      return caches.match('/')
    }

    // オフラインレスポンス
    return new Response(
      JSON.stringify({
        error: 'offline',
        message: 'オフラインです。接続を確認してください。'
      }),
      {
        status: 503,
        headers: { 'Content-Type': 'application/json' }
      }
    )
  }
}

// Fetch event
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

  // 画像リクエストの処理
  if (isImageRequest(url)) {
    event.respondWith(handleImageRequest(event.request))
    return
  }

  // Skip external requests (API, relays, etc.)
  if (url.hostname !== self.location.hostname) {
    return
  }

  // アプリシェル (ナビゲーション、JS、CSS)
  if (event.request.mode === 'navigate' ||
      PRECACHE_ASSETS.some(asset => url.pathname === asset) ||
      url.pathname.endsWith('.js') ||
      url.pathname.endsWith('.css')) {
    event.respondWith(handleAppShellRequest(event.request))
    return
  }

  // その他のリクエスト
  event.respondWith(handleDefaultRequest(event.request))
})

// バックグラウンド同期（将来の実装用）
self.addEventListener('sync', (event) => {
  if (event.tag === 'sync-posts') {
    // オフラインで作成された投稿を同期
    console.log('[SW] Background sync triggered')
  }
})

// プッシュ通知（将来の実装用）
self.addEventListener('push', (event) => {
  if (event.data) {
    const data = event.data.json()
    const options = {
      body: data.body || 'New notification',
      icon: '/favicon-192.png',
      badge: '/favicon-192.png',
      vibrate: [100, 50, 100],
      data: data.data || {}
    }

    event.waitUntil(
      self.registration.showNotification(data.title || 'ぬるぬる', options)
    )
  }
})

// 通知クリック時の処理
self.addEventListener('notificationclick', (event) => {
  event.notification.close()

  event.waitUntil(
    clients.matchAll({ type: 'window' }).then((clientList) => {
      // 既存のウィンドウがあればフォーカス
      for (const client of clientList) {
        if (client.url === '/' && 'focus' in client) {
          return client.focus()
        }
      }
      // なければ新しいウィンドウを開く
      if (clients.openWindow) {
        return clients.openWindow('/')
      }
    })
  )
})

// キャッシュクリアメッセージの処理
self.addEventListener('message', (event) => {
  if (event.data === 'clear-cache') {
    event.waitUntil(
      caches.keys().then((cacheNames) => {
        return Promise.all(
          cacheNames.map((cacheName) => caches.delete(cacheName))
        )
      }).then(() => {
        console.log('[SW] All caches cleared')
      })
    )
  }

  if (event.data === 'clear-images') {
    event.waitUntil(
      caches.delete(IMAGE_CACHE_NAME).then(() => {
        console.log('[SW] Image cache cleared')
      })
    )
  }
})
