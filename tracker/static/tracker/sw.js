const CACHE_NAME = 'roamly-v1';
const STATIC_ASSETS = [
  '/static/tracker/roamly.svg',
  '/static/tracker/roamlymark.ico',
  '/static/tracker/icon-192.png',
  '/static/tracker/icon-512.png',
  'https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@300;400;500;600;700&display=swap',
  'https://unpkg.com/maplibre-gl@4.1.2/dist/maplibre-gl.css',
  'https://unpkg.com/maplibre-gl@4.1.2/dist/maplibre-gl.js',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(STATIC_ASSETS))
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)))
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') return;

  const url = new URL(event.request.url);

  // Never re-fetch cross-origin requests through the worker. Doing so returns an
  // opaque response, which (a) fails SRI `integrity` checks because the body
  // reads as empty (the SHA-512-of-empty mismatch) and (b) rejects on any
  // network hiccup, breaking third-party scripts like analytics beacons
  // (GoatCounter's gc.zgo.at/count.js, the Cloudflare beacon). Only serve our
  // explicitly precached CDN assets from cache; let everything else cross-origin
  // load natively (no respondWith) exactly as if no service worker existed.
  if (url.origin !== self.location.origin) {
    if (STATIC_ASSETS.includes(url.href)) {
      event.respondWith(
        caches.match(event.request).then((cached) => cached || fetch(event.request))
      );
    }
    return;
  }

  // Network-first for API calls and HTML pages
  if (url.pathname.startsWith('/api/') || event.request.mode === 'navigate') {
    event.respondWith(
      fetch(event.request).catch(() => caches.match(event.request))
    );
    return;
  }

  // Cache-first for same-origin static assets
  event.respondWith(
    caches.match(event.request).then((cached) => cached || fetch(event.request))
  );
});
