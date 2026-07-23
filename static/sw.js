// Bump VER on every release to invalidate old caches.
const VER   = "naada-v26";
const SHELL = ["/", "/static/index.html", "/static/app.css",
               "/static/app.js", "/manifest.json", "/static/icons/icon.svg"];

self.addEventListener("install", e => {
  e.waitUntil(caches.open(VER).then(c => Promise.allSettled(SHELL.map(u => c.add(u)))));
  self.skipWaiting();           // activate immediately, don't wait for old SW
});

self.addEventListener("activate", e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== VER).map(k => caches.delete(k)))
    ).then(() => self.clients.claim())   // take control of open pages now
  );
});

self.addEventListener("fetch", e => {
  const { request } = e;
  const url = new URL(request.url);

  // ── Media and every cross-origin request: DO NOT TOUCH ──────────────
  // Never call respondWith() here. Piping audio through the service
  // worker breaks HTTP Range requests (seeking, progressive buffering)
  // and — worse — ties the stream's lifetime to the worker. Android is
  // free to terminate a service worker while the app is backgrounded, so
  // a proxied stream simply stops when you minimise the app. Letting the
  // request go straight to the network keeps playback owned by the media
  // stack, which is exactly what survives backgrounding.
  if (url.hostname !== location.hostname) return;
  if (request.destination === "audio" || request.destination === "video") return;
  if (request.headers.has("range")) return;

  // Same-origin API: always network, never cache
  if (url.pathname.startsWith("/api/")) {
    e.respondWith(
      fetch(request).catch(() =>
        new Response(JSON.stringify({error:"offline"}),
          {status:503, headers:{"Content-Type":"application/json"}}))
    );
    return;
  }

  // App shell (HTML/CSS/JS/manifest/icon): NETWORK-FIRST.
  // This is the key fix — updates always land immediately. Cache is only a
  // fallback for offline use, so we never get stuck serving stale code again.
  e.respondWith(
    fetch(request)
      .then(res => {
        if (res && res.ok && request.method === "GET") {
          const clone = res.clone();
          caches.open(VER).then(c => c.put(request, clone));
        }
        return res;
      })
      .catch(() => caches.match(request).then(hit => hit || caches.match("/")))
  );
});
