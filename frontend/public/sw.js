// Service worker: makes the app shell load without a connection.
//
// It never caches API responses. Downloaded articles live in IndexedDB,
// managed by the app, so publisher restrictions are applied when the device
// is back online (FR-061, FR-063). Anything cached here is code and icons only.

const VERSION = 'v1'
const SHELL = `shell-${VERSION}`
const ASSETS = `assets-${VERSION}`
const MAX_ASSETS = 80
const SHELL_FILES = ['/', '/manifest.webmanifest', '/favicon.svg', '/icons/icon-192.png']

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(SHELL).then((cache) => cache.addAll(SHELL_FILES)))
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== SHELL && k !== ASSETS).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  )
})

self.addEventListener('fetch', (event) => {
  const request = event.request
  if (request.method !== 'GET') return
  const url = new URL(request.url)
  if (url.origin !== self.location.origin) return
  // API answers depend on rights and on who is signed in: always from the network.
  if (url.pathname.startsWith('/api/')) return

  if (request.mode === 'navigate') {
    // Network first, so a new release is picked up; the cached shell when offline.
    event.respondWith(
      fetch(request)
        .then((response) => {
          if (response.ok) {
            const copy = response.clone()
            caches.open(SHELL).then((cache) => cache.put('/', copy))
          }
          return response
        })
        .catch(() => caches.match('/')),
    )
    return
  }

  if (url.pathname.startsWith('/assets/')) {
    // Built files have content hashes in their names, so a cached copy is never stale.
    event.respondWith(
      caches.match(request).then(
        (cached) =>
          cached ||
          fetch(request).then((response) => {
            if (response.ok) {
              const copy = response.clone()
              caches.open(ASSETS).then(async (cache) => {
                await cache.put(request, copy)
                const keys = await cache.keys()
                // Oldest first; drop files from old releases.
                await Promise.all(keys.slice(0, Math.max(0, keys.length - MAX_ASSETS)).map((k) => cache.delete(k)))
              })
            }
            return response
          }),
      ),
    )
    return
  }

  if (SHELL_FILES.includes(url.pathname) || url.pathname.startsWith('/icons/')) {
    event.respondWith(fetch(request).catch(() => caches.match(request)))
  }
})
