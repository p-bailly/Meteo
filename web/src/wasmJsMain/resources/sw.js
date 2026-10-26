// Hors ligne : toute la webapp est gardée dans le téléphone, version par version (@VERSION@ est remplacé à la
// construction). Une nouvelle version se télécharge en entier en arrière-plan, puis prend la place de l'ancienne dès
// qu'elle est complète, et la page se recharge (index.html) : on ne mélange jamais les fichiers de deux versions. Sans
// skipWaiting, elle attendait que toutes les pages de la webapp soient fermées, ce qui n'arrive presque jamais sur un
// téléphone. Les prévisions, elles, passent par le réseau (et le cache de l'appli).
const VERSION = "@VERSION@";
const CACHE = "meteo-" + VERSION;
const FILES = [
  "./", "index.html", "manifest.webmanifest", "icon.svg", "icon-192.png", "icon-512.png", "apple-touch-icon.png",
  "meteo.mjs", "meteo.import-object.mjs", "meteo.js-builtins.mjs", "meteo.wasm",
  "skiko.mjs", "skiko.wasm", "js-joda.esm.js",
  "map.mjs", "maplibre/maplibre-gl.mjs", "maplibre/maplibre-gl-worker.mjs", "maplibre/maplibre-gl.css",
  "fonts/archivo_condensed_light.ttf", "fonts/archivo_condensed_regular.ttf", "fonts/archivo_condensed_semibold.ttf",
  "fonts/archivo_medium.ttf", "fonts/archivo_regular.ttf", "fonts/archivo_semibold.ttf",
  "fonts/martian_mono_condensed_medium.ttf", "fonts/martian_mono_condensed_regular.ttf",
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE)
      .then((cache) => cache.addAll(FILES.map((f) => new Request(f, { cache: "reload" }))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k.startsWith("meteo-") && k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET" || new URL(request.url).origin !== self.location.origin) return;
  event.respondWith(
    caches.open(CACHE).then((cache) =>
      cache.match(request, { ignoreSearch: true }).then((hit) => hit || fetch(request))
    )
  );
});
