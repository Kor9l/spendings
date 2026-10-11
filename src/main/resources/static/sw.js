// Service worker: статика из кэша (с фоновым обновлением), страницы — только из сети.
// Данные в кэш не попадают: без сети показывается офлайн-страница.
const CACHE = 'spendings-static-v1';
const OFFLINE = '/offline.html';
const PRECACHE = [
  OFFLINE,
  '/favicon.svg',
  '/icons/icon-192.png',
  '/css/app.css',
  '/js/theme.js',
  '/js/app.js',
  '/js/charts.js',
  '/js/operation-form.js',
  '/js/passkeys.js',
  '/webjars/bootstrap/dist/css/bootstrap.min.css',
  '/webjars/bootstrap/dist/js/bootstrap.bundle.min.js',
  '/webjars/htmx.org/dist/htmx.min.js',
  '/webjars/sortablejs/Sortable.min.js',
  '/webjars/alpinejs/dist/cdn.min.js',
];
const STATIC = /^\/(css|js|webjars|icons)\/|^\/favicon\.svg$/;

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(PRECACHE)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  if (request.mode === 'navigate') {
    event.respondWith(fetch(request).catch(() => caches.match(OFFLINE)));
    return;
  }
  if (STATIC.test(url.pathname)) {
    event.respondWith(
      caches.open(CACHE).then(async (cache) => {
        const cached = await cache.match(request);
        const fresh = fetch(request)
          .then((response) => {
            if (response.ok) cache.put(request, response.clone());
            return response;
          })
          .catch(() => cached);
        return cached || fresh;
      }),
    );
  }
});
