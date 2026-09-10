// Bumped whenever STATIC_ASSETS changes, so the activate handler below evicts
// the previous cache instead of leaving stale entries forever.
const CACHE_NAME = 'roamly-v2';

// Same-origin only. The previous list precached MapLibre 4.1.2 from unpkg while
// the app loaded 5.24.0 (so ~1MB was downloaded on install and never served),
// plus a Google Fonts stylesheet for Space Grotesk — a font the app replaced
// with self-hosted faces, which made this the only third-party request left in
// an app whose whole pitch is that it makes none.
const STATIC_ASSETS = [
  '/static/tracker/roamlymark.svg',
  '/static/tracker/roamlymark.ico',
  '/static/tracker/icon-192.png',
  '/static/tracker/icon-512.png',
  '/offline/',
];

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE_NAME);
    // Individually, not cache.addAll(): addAll is atomic, so one 404 or one
    // network hiccup rejected the whole install and the service worker never
    // activated — offline support silently did not exist, with nothing logged.
    await Promise.allSettled(STATIC_ASSETS.map((url) => cache.add(url)));
  })());
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

  // Never touch cross-origin requests. Re-fetching them through the worker
  // returns an opaque response, which fails SRI `integrity` checks (the body
  // reads as empty) and rejects on any network hiccup, breaking third-party
  // scripts such as analytics beacons. Letting them through untouched is
  // exactly as if no service worker existed.
  if (url.origin !== self.location.origin) return;

  // Never cache API responses or anything credentialed. A stale answer here is
  // worse than no answer: it is someone's location history.
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/media/')) return;

  // Navigations: network first, falling back to the offline page. There is no
  // point caching the HTML itself — every page is per-user and the data comes
  // from /api/ anyway — but a blank browser error page is a worse answer than
  // "you're offline".
  if (event.request.mode === 'navigate') {
    event.respondWith(
      fetch(event.request).catch(() =>
        caches.match('/offline/').then((r) => r || Response.error())
      )
    );
    return;
  }

  // Same-origin static assets are content-hashed by WhiteNoise, so cache-first
  // is safe: a changed file has a different URL.
  event.respondWith(
    caches.match(event.request).then((cached) => cached || fetch(event.request))
  );
});
