package io.github.pbailly.meteo.ui.maps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.WebElementView
import kotlinx.browser.document
import org.w3c.dom.HTMLDivElement

// La carte MapLibre GL JS de map.mjs (window.meteoMap), posée dans la page sous l'interface Compose.

private fun createMap(element: HTMLDivElement, lat: Double, lon: Double, zoom: Double): JsAny =
    js("window.meteoMap.create(element, lat, lon, zoom)")

private fun setTiles(map: JsAny, url: String?, opacity: Double, maxZoom: Int, tileSize: Int): Unit =
    js("map.setTiles(url, opacity, maxZoom, tileSize)")

private fun centerMap(map: JsAny, lat: Double, lon: Double, zoom: Double): Unit = js("map.center(lat, lon, zoom)")

private fun resizeMap(map: JsAny): Unit = js("map.resize()")

private fun removeMap(map: JsAny): Unit = js("map.remove()")

/** Ce que la carte affiche sur le fond : des tuiles ([url] : {z}/{x}/{y} ou {bbox-epsg-3857}). */
data class MapTiles(val url: String, val opacity: Double = 0.8, val maxZoom: Int = 22, val tileSize: Int = 256)

private class MapHolder {
    var map: JsAny? = null
    var center: Pair<Double, Double>? = null
}

/** Carte centrée sur ([latitude], [longitude]) au zoom [zoom], avec la couche [tiles] (aucune si null). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WebMap(latitude: Double, longitude: Double, tiles: MapTiles?, modifier: Modifier = Modifier, zoom: Double = 6.6) {
    val holder = remember { MapHolder() }
    WebElementView(
        factory = {
            val div = document.createElement("div") as HTMLDivElement
            div.style.width = "100%"
            div.style.height = "100%"
            holder.map = createMap(div, latitude, longitude, zoom)
            holder.center = latitude to longitude
            div
        },
        modifier = modifier,
        update = {
            val map = holder.map ?: return@WebElementView
            if (holder.center != latitude to longitude) {
                holder.center = latitude to longitude
                centerMap(map, latitude, longitude, zoom)
            }
            setTiles(map, tiles?.url, tiles?.opacity ?: 0.0, tiles?.maxZoom ?: 0, tiles?.tileSize ?: 256)
            resizeMap(map)
        },
        onRelease = {
            holder.map?.let(::removeMap)
            holder.map = null
        },
    )
}
