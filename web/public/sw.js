// The app shell, available offline, so a reload while offline still opens the board from
// IndexedDB (SPEC §7.1). Network first for the app's own files, falling back to the cache.
// The API and the WebSocket are never cached or intercepted.
const CACHE = "concord-shell-v1";

self.addEventListener("install", (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE);
    const html = await (await fetch("/", { cache: "no-store" })).text();
    const assets = [...html.matchAll(/(?:src|href)="(\/[^"]+)"/g)].map((m) => m[1]);
    await cache.put("/", new Response(html, { headers: { "Content-Type": "text/html" } }));
    await cache.addAll(assets);
    await self.skipWaiting();
  })());
});

self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== "GET" || url.origin !== location.origin || url.pathname.startsWith("/api") || url.pathname.startsWith("/ws")) return;
  const key = event.request.mode === "navigate" ? "/" : event.request;
  event.respondWith((async () => {
    const cache = await caches.open(CACHE);
    try {
      const fresh = await fetch(event.request);
      if (fresh.ok) await cache.put(key, fresh.clone());
      return fresh;
    } catch {
      const hit = await cache.match(key);
      if (hit) return hit;
      throw new Error("offline and not cached: " + url.pathname);
    }
  })());
});
