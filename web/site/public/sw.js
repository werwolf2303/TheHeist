const CACHE = "theheist-v1";

self.addEventListener("install", e => {
    self.skipWaiting();
    e.waitUntil(
        caches.open(CACHE).then(c => c.addAll(["./", "./index.html"]))
    );
});

self.addEventListener("activate", e => {
    e.waitUntil(
        caches.keys().then(keys =>
            Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))
        )
    );
    self.clients.claim();
});

self.addEventListener("fetch", e => {
    const url = new URL(e.request.url);
    // wasm/ffmpeg are too large to cache and versioned by content hash
    if (url.pathname.endsWith(".wasm") || url.pathname.includes("ffmpeg")) {
        e.respondWith(fetch(e.request));
        return;
    }
    e.respondWith(
        caches.match(e.request).then(cached => cached || fetch(e.request).then(resp => {
            if (resp.ok) {
                const clone = resp.clone();
                caches.open(CACHE).then(c => c.put(e.request, clone));
            }
            return resp;
        }))
    );
});
