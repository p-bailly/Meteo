// La carte des « Cartes » de la webapp : MapLibre GL JS (la version navigateur de la carte de l'appli Android), fond
// OpenFreeMap, la couche météo (image radar, tuiles satellite ou foudre, repères des webcams) posée sous les noms de
// villes, et le lieu marqué d'un point noir cerclé de blanc. Appelé depuis Kotlin (RadarMap.kt) par window.meteoMap.

const STYLE_URL = "https://tiles.openfreemap.org/styles/liberty";

let maplibre = null;
async function lib() {
  if (!maplibre) maplibre = await import("./maplibre/maplibre-gl.mjs");
  return maplibre;
}

/** Premier calque de texte du style : la couche météo passe dessous, les noms de villes restent lisibles. */
function firstSymbolLayer(map) {
  return map.getStyle().layers.find((l) => l.type === "symbol")?.id;
}

class WeatherMap {
  constructor(element, lat, lon, zoom, onCamClick) {
    this.ready = false;
    this.pending = null;
    this.kind = null;
    this.tileUrl = null;
    this.imageBounds = null;
    lib().then((m) => {
      this.map = new m.Map({
        container: element,
        style: STYLE_URL,
        center: [lon, lat],
        zoom: zoom,
        attributionControl: { compact: true },
        dragRotate: false,
        pitchWithRotate: false,
        touchPitch: false,
      });
      this.map.touchZoomRotate.disableRotation();
      this.map.on("load", () => {
        this.ready = true;
        this.setPlace(lat, lon);
        if (this.pending) this.pending();
      });
      this.map.on("click", "cams", (e) => {
        const id = e.features?.[0]?.properties?.id;
        if (id != null) onCamClick(String(id));
      });
      this.map.on("mouseenter", "cams", () => { this.map.getCanvas().style.cursor = "pointer"; });
      this.map.on("mouseleave", "cams", () => { this.map.getCanvas().style.cursor = ""; });
    }).catch((e) => console.error("Carte indisponible", e));
  }

  /** Exécute [action] maintenant, ou au chargement du style (seule la dernière demandée compte). */
  whenReady(action) {
    if (this.ready) action();
    else this.pending = action;
  }

  setPlace(lat, lon) {
    if (!this.ready) return;
    const data = { type: "Feature", geometry: { type: "Point", coordinates: [lon, lat] } };
    const source = this.map.getSource("place");
    if (source) {
      source.setData(data);
      return;
    }
    // Le lieu : un point noir cerclé de blanc dans un halo blanc, lisible sur toutes les couleurs de la pluie.
    this.map.addSource("place", { type: "geojson", data });
    this.map.addLayer({
      id: "place-halo", type: "circle", source: "place",
      paint: { "circle-radius": 11, "circle-opacity": 0, "circle-stroke-color": "#FFFFFF", "circle-stroke-width": 2.5, "circle-stroke-opacity": 0.9 },
    });
    this.map.addLayer({
      id: "place", type: "circle", source: "place",
      paint: { "circle-radius": 5.5, "circle-color": "#111111", "circle-stroke-color": "#FFFFFF", "circle-stroke-width": 2 },
    });
  }

  center(lat, lon, zoom) {
    if (!this.map) return;
    this.map.jumpTo({ center: [lon, lat], zoom });
    this.setPlace(lat, lon);
  }

  remove(...ids) {
    for (const id of ids) {
      if (this.map.getLayer(id)) this.map.removeLayer(id);
      if (this.map.getSource(id)) this.map.removeSource(id);
    }
  }

  clear() {
    this.whenReady(() => {
      this.remove("image", "cams");
      this.removeTiles();
      this.kind = null;
    });
  }

  /** Image Web Mercator couvrant exactement le rectangle donné (radar), de l'adresse [url] (blob). */
  setImage(url, west, north, east, south, opacity) {
    this.whenReady(() => {
      this.removeTiles();
      this.remove("cams");
      const coordinates = [[west, north], [east, north], [east, south], [west, south]];
      const bounds = coordinates.join(",");
      const source = this.map.getSource("image");
      if (!url) {
        if (this.map.getLayer("image")) this.map.setLayoutProperty("image", "visibility", "none");
        return;
      }
      if (source && this.imageBounds === bounds) {
        source.updateImage({ url, coordinates });
        this.map.setLayoutProperty("image", "visibility", "visible");
        this.map.setPaintProperty("image", "raster-opacity", opacity);
        return;
      }
      this.remove("image");
      this.map.addSource("image", { type: "image", url, coordinates });
      this.map.addLayer({ id: "image", type: "raster", source: "image", paint: { "raster-opacity": opacity, "raster-fade-duration": 0 } }, firstSymbolLayer(this.map));
      this.imageBounds = bounds;
    });
  }

  removeTiles() {
    const old = (this.map.getStyle().layers || []).filter((l) => l.id.startsWith("weather-")).map((l) => l.id);
    old.forEach((id) => this.remove(id));
    this.tileUrl = null;
  }

  /**
   * Tuiles de [url] ({bbox-epsg-3857} ou {z}/{x}/{y}). La nouvelle couche est posée au-dessus de l'ancienne, qu'on
   * retire une fois chargée : l'animation ne clignote pas.
   */
  setTiles(url, opacity, tileSize) {
    this.whenReady(() => {
      this.remove("image", "cams");
      if (url === this.tileUrl) return;
      this.tileUrl = url;
      const old = (this.map.getStyle().layers || []).filter((l) => l.id.startsWith("weather-")).map((l) => l.id);
      const id = "weather-" + Date.now() + "-" + Math.floor(Math.random() * 1e6);
      this.map.addSource(id, { type: "raster", tiles: [url], tileSize });
      this.map.addLayer({ id, type: "raster", source: id, paint: { "raster-opacity": opacity, "raster-fade-duration": 0 } }, firstSymbolLayer(this.map));
      const removeOld = () => old.forEach((o) => this.remove(o));
      const check = (e) => {
        if (e.sourceId === id && this.map.isSourceLoaded(id)) {
          this.map.off("sourcedata", check);
          removeOld();
        }
      };
      this.map.on("sourcedata", check);
      // Au pire, l'ancienne image s'en va après trois secondes.
      setTimeout(() => { this.map.off("sourcedata", check); removeOld(); }, 3000);
    });
  }

  /** Repères des webcams (GeoJSON en texte), ronds jaunes cerclés de bleu nuit. */
  setMarkers(json) {
    this.whenReady(() => {
      this.remove("image");
      this.removeTiles();
      const data = JSON.parse(json);
      const source = this.map.getSource("cams");
      if (source) {
        source.setData(data);
        return;
      }
      this.map.addSource("cams", { type: "geojson", data });
      this.map.addLayer({
        id: "cams", type: "circle", source: "cams",
        paint: { "circle-radius": 8, "circle-color": "#FFC21A", "circle-stroke-color": "#0C304E", "circle-stroke-width": 2.5 },
      });
    });
  }

  resize() {
    this.map?.resize();
  }

  destroy() {
    this.map?.remove();
  }
}

window.meteoMap = {
  create: (element, lat, lon, zoom, onCamClick) => new WeatherMap(element, lat, lon, zoom, onCamClick),
  /** Une adresse locale (blob) pour une image PNG passée en « latin1 » (un caractère par octet). */
  imageUrl: (latin1) => {
    const bytes = new Uint8Array(latin1.length);
    for (let i = 0; i < latin1.length; i++) bytes[i] = latin1.charCodeAt(i);
    return URL.createObjectURL(new Blob([bytes], { type: "image/png" }));
  },
  revoke: (url) => URL.revokeObjectURL(url),
};

// La feuille de style de MapLibre (boutons, attribution).
const css = document.createElement("link");
css.rel = "stylesheet";
css.href = new URL("./maplibre/maplibre-gl.css", import.meta.url).href;
document.head.appendChild(css);
