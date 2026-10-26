// La carte des « Cartes » de la webapp : MapLibre GL JS (la version navigateur de la carte de l'appli Android), fond
// OpenFreeMap, une couche météo en tuiles posée sous les noms de villes, et le lieu entouré. Appelé depuis Kotlin
// (WebMap.kt) par window.meteoMap.

const STYLE_URL = "https://tiles.openfreemap.org/styles/liberty";
const PLACE_COLOR = "#E2701F";

let maplibre = null;
async function lib() {
  if (!maplibre) {
    maplibre = await import("./maplibre/maplibre-gl.mjs");
  }
  return maplibre;
}

/** Premier calque de texte du style : la couche météo passe dessous, les noms de villes restent lisibles. */
function firstSymbolLayer(map) {
  return map.getStyle().layers.find((l) => l.type === "symbol")?.id;
}

class WeatherMap {
  constructor(element, lat, lon, zoom) {
    this.element = element;
    this.ready = false;
    this.pending = null;
    this.tileUrl = null;
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
        if (this.pending) this.setTiles(...this.pending);
      });
    }).catch((e) => console.error("Carte indisponible", e));
  }

  setPlace(lat, lon) {
    if (!this.ready) return;
    const data = { type: "Feature", geometry: { type: "Point", coordinates: [lon, lat] } };
    const source = this.map.getSource("place");
    if (source) {
      source.setData(data);
      return;
    }
    this.map.addSource("place", { type: "geojson", data });
    this.map.addLayer({
      id: "place",
      type: "circle",
      source: "place",
      paint: { "circle-radius": 6, "circle-color": PLACE_COLOR, "circle-stroke-width": 2.5, "circle-stroke-color": "#FFFFFF" },
    });
  }

  center(lat, lon, zoom) {
    if (!this.map) return;
    this.map.jumpTo({ center: [lon, lat], zoom });
    this.setPlace(lat, lon);
  }

  /**
   * La couche météo : tuiles de [url] ({z}/{x}/{y} ou {bbox-epsg-3857}), jusqu'au zoom [maxZoom] (agrandies au-delà).
   * La nouvelle image est posée au-dessus de l'ancienne, qu'on retire une fois chargée : l'animation ne clignote pas.
   */
  setTiles(url, opacity, maxZoom, tileSize) {
    if (!this.ready) {
      this.pending = [url, opacity, maxZoom, tileSize];
      return;
    }
    this.pending = null;
    if (url === this.tileUrl) return;
    this.tileUrl = url;
    const old = (this.map.getStyle().layers || []).filter((l) => l.id.startsWith("weather-")).map((l) => l.id);
    const remove = () => old.forEach((id) => {
      if (this.map.getLayer(id)) this.map.removeLayer(id);
      if (this.map.getSource(id)) this.map.removeSource(id);
    });
    if (!url) {
      remove();
      return;
    }
    const id = "weather-" + Date.now() + "-" + Math.floor(Math.random() * 1e6);
    this.map.addSource(id, { type: "raster", tiles: [url], tileSize: tileSize, maxzoom: maxZoom });
    this.map.addLayer({ id, type: "raster", source: id, paint: { "raster-opacity": opacity, "raster-fade-duration": 0 } }, firstSymbolLayer(this.map));
    const done = () => {
      if (this.map.isSourceLoaded(id)) {
        this.map.off("sourcedata", check);
        remove();
      }
    };
    const check = (e) => { if (e.sourceId === id) done(); };
    this.map.on("sourcedata", check);
    // Au pire, l'ancienne image s'en va après une seconde et demie.
    setTimeout(() => { this.map.off("sourcedata", check); remove(); }, 1500);
  }

  resize() {
    this.map?.resize();
  }

  remove() {
    this.map?.remove();
  }
}

window.meteoMap = {
  create: (element, lat, lon, zoom) => new WeatherMap(element, lat, lon, zoom),
};

// La feuille de style de MapLibre (boutons, attribution).
const css = document.createElement("link");
css.rel = "stylesheet";
css.href = new URL("./maplibre/maplibre-gl.css", import.meta.url).href;
document.head.appendChild(css);
