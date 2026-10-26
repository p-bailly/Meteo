package io.github.pbailly.meteo.ui.radar

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.WebElementView
import io.github.pbailly.meteo.data.maps.Webcam
import io.github.pbailly.meteo.data.radar.GeoBounds
import java.io.toLatin1
import kotlinx.browser.document
import org.maplibre.android.geometry.LatLng
import org.w3c.dom.HTMLDivElement

// La carte des Cartes dans la webapp : comme app/…/ui/radar/RadarMap.kt (MapLibre Android), mais avec MapLibre GL JS,
// pilotée par map.mjs (window.meteoMap). La carte est un élément de la page posé par-dessus l'interface.

/** Relevé d'une station sur la carte (la webapp n'a pas la carte de température : jamais affiché). */
data class StationMarker(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val color: Int,
    val minZoom: Int,
)

/** Ce que la carte affiche par-dessus le fond. */
sealed interface MapOverlay {
    data object None : MapOverlay

    /** Image Web Mercator couvrant exactement [bounds] (radar). */
    data class Image(val bitmap: Bitmap?, val bounds: GeoBounds, val opacity: Float = 0.85f) : MapOverlay

    /** Tuiles WMS en EPSG:3857 (satellite, foudre), l'URL contenant {bbox-epsg-3857}. */
    data class Tiles(val urlTemplate: String, val opacity: Float = 1f) : MapOverlay

    /** Repères cliquables (webcams). */
    data class Markers(val webcams: List<Webcam>) : MapOverlay
}

private fun createMap(element: HTMLDivElement, lat: Double, lon: Double, zoom: Double, onCam: (String) -> Unit): JsAny =
    js("window.meteoMap.create(element, lat, lon, zoom, onCam)")

private fun centerMap(map: JsAny, lat: Double, lon: Double, zoom: Double): Unit = js("map.center(lat, lon, zoom)")
private fun clearMap(map: JsAny): Unit = js("map.clear()")
private fun setImage(map: JsAny, url: String?, west: Double, north: Double, east: Double, south: Double, opacity: Double): Unit =
    js("map.setImage(url, west, north, east, south, opacity)")
private fun setTiles(map: JsAny, url: String, opacity: Double, tileSize: Int): Unit = js("map.setTiles(url, opacity, tileSize)")
private fun setMarkers(map: JsAny, json: String): Unit = js("map.setMarkers(json)")
private fun resizeMap(map: JsAny): Unit = js("map.resize()")
private fun destroyMap(map: JsAny): Unit = js("map.destroy()")
private fun imageUrl(latin1: String): String = js("window.meteoMap.imageUrl(latin1)")
private fun revokeUrl(url: String): Unit = js("window.meteoMap.revoke(url)")

/** Adresses locales (blob) des images radar déjà montrées : une douzaine, pour l'animation. */
private object ImageUrls {
    private val urls = LinkedHashMap<Bitmap, String>()

    fun of(bitmap: Bitmap): String {
        urls.remove(bitmap)?.let { url ->
            urls[bitmap] = url
            return url
        }
        val url = imageUrl(bitmap.png.toLatin1())
        urls[bitmap] = url
        while (urls.size > 16) {
            val oldest = urls.keys.first()
            urls.remove(oldest)?.let(::revokeUrl)
        }
        return url
    }
}

private class MapHolder {
    var map: JsAny? = null
    var center: LatLng? = null
}

/**
 * Carte MapLibre : fond OpenFreeMap, couche météo posée sous les noms de villes, lieu marqué. [onWebcamClick] reçoit
 * l'identifiant de la webcam touchée. Les paramètres des stations et du point touché, propres à l'appli, sont ignorés.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WeatherMap(
    overlay: MapOverlay,
    center: LatLng,
    modifier: Modifier = Modifier,
    zoom: Double = 6.6,
    showPlace: Boolean = true,
    onWebcamClick: (Long) -> Unit = {},
    stations: List<StationMarker> = emptyList(),
    onStationClick: (String) -> Unit = {},
    pin: LatLng? = null,
    onMapClick: ((LatLng) -> Unit)? = null,
) {
    val holder = remember { MapHolder() }
    val clicks = rememberUpdatedState(onWebcamClick)
    WebElementView(
        factory = {
            val div = document.createElement("div") as HTMLDivElement
            div.style.width = "100%"
            div.style.height = "100%"
            holder.map = createMap(div, center.latitude, center.longitude, zoom) { id -> id.toLongOrNull()?.let { clicks.value(it) } }
            holder.center = center
            div
        },
        modifier = modifier,
        update = {
            val map = holder.map ?: return@WebElementView
            if (holder.center != center) {
                holder.center = center
                centerMap(map, center.latitude, center.longitude, zoom)
            }
            when (overlay) {
                MapOverlay.None -> clearMap(map)
                is MapOverlay.Image -> {
                    val b = overlay.bounds
                    setImage(map, overlay.bitmap?.let(ImageUrls::of), b.west, b.north, b.east, b.south, overlay.opacity.toDouble())
                }
                is MapOverlay.Tiles -> setTiles(map, overlay.urlTemplate, overlay.opacity.toDouble(), 512)
                is MapOverlay.Markers -> setMarkers(map, markersJson(overlay.webcams))
            }
            resizeMap(map)
        },
        onRelease = {
            holder.map?.let(::destroyMap)
            holder.map = null
        },
    )
}

private fun markersJson(cams: List<Webcam>): String = cams.joinToString(",", "{\"type\":\"FeatureCollection\",\"features\":[", "]}") { cam ->
    "{\"type\":\"Feature\",\"properties\":{\"id\":\"${cam.id}\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[${cam.longitude},${cam.latitude}]}}"
}
