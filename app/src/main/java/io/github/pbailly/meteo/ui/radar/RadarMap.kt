package io.github.pbailly.meteo.ui.radar

import android.graphics.Bitmap
import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.pbailly.meteo.data.maps.Webcam
import io.github.pbailly.meteo.data.radar.GeoBounds
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** Fond de carte gratuit et sans clé (OpenFreeMap, données OpenStreetMap), avec relief ombré. */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val IMAGE_SOURCE = "overlay-image"
private const val IMAGE_LAYER = "overlay-image-layer"
private const val TILE_SOURCE = "overlay-tiles"
private const val TILE_LAYER = "overlay-tiles-layer"
private const val CAM_SOURCE = "webcams"
private const val CAM_LAYER = "webcams-layer"
private const val PLACE_SOURCE = "place"
private const val PLACE_LAYER = "place-layer"

/** Ce que la carte affiche par-dessus le fond. */
sealed interface MapOverlay {
    data object None : MapOverlay

    /** Image Web Mercator couvrant exactement [bounds] (radar, température). */
    data class Image(val bitmap: Bitmap?, val bounds: GeoBounds, val opacity: Float = 0.85f) : MapOverlay

    /** Tuiles WMS en EPSG:3857 (satellite, foudre), l'URL contenant {bbox-epsg-3857}. */
    data class Tiles(val urlTemplate: String, val opacity: Float = 1f) : MapOverlay

    /** Repères cliquables (webcams). */
    data class Markers(val webcams: List<Webcam>) : MapOverlay
}

/**
 * Carte MapLibre : fond OpenFreeMap, couche météo posée sous les noms de villes, lieu entouré.
 * [onWebcamClick] reçoit l'identifiant de la webcam touchée.
 */
@Composable
fun WeatherMap(
    overlay: MapOverlay,
    center: LatLng,
    modifier: Modifier = Modifier,
    onWebcamClick: (Long) -> Unit = {},
) {
    val mapView = rememberMapViewWithLifecycle()
    var style by remember { mutableStateOf<Style?>(null) }
    val clicks by rememberUpdatedState(onWebcamClick)
    var imageBounds by remember { mutableStateOf<GeoBounds?>(null) }
    var tileUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.uiSettings.apply {
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                logoGravity = Gravity.TOP or Gravity.START
                attributionGravity = Gravity.TOP or Gravity.START
                setAttributionMargins(110, 12, 0, 0)
            }
            map.cameraPosition = CameraPosition.Builder().target(center).zoom(6.6).build()
            map.addOnMapClickListener { point ->
                val screen = map.projection.toScreenLocation(point)
                val feature = map.queryRenderedFeatures(screen, CAM_LAYER).firstOrNull()
                feature?.getNumberProperty("id")?.let { clicks(it.toLong()) }
                feature != null
            }
            map.setStyle(Style.Builder().fromUri(STYLE_URL)) { loaded ->
                loaded.addSource(GeoJsonSource(PLACE_SOURCE, Point.fromLngLat(center.longitude, center.latitude)))
                loaded.addLayer(
                    CircleLayer(PLACE_LAYER, PLACE_SOURCE).withProperties(
                        PropertyFactory.circleRadius(9f),
                        PropertyFactory.circleOpacity(0f),
                        PropertyFactory.circleStrokeColor("#2D7BEA"),
                        PropertyFactory.circleStrokeWidth(3f),
                    ),
                )
                style = loaded
            }
        }
    }

    LaunchedEffect(style, overlay) {
        val loaded = style ?: return@LaunchedEffect
        // Sous le premier calque de texte, pour garder les noms de villes lisibles.
        val firstLabel = loaded.layers.firstOrNull { it is SymbolLayer }?.id
        fun addBelowLabels(layer: org.maplibre.android.style.layers.Layer) {
            if (firstLabel != null) loaded.addLayerBelow(layer, firstLabel) else loaded.addLayer(layer)
        }
        fun removeImage() {
            loaded.removeLayer(IMAGE_LAYER)
            loaded.removeSource(IMAGE_SOURCE)
            imageBounds = null
        }
        fun removeTiles() {
            loaded.removeLayer(TILE_LAYER)
            loaded.removeSource(TILE_SOURCE)
            tileUrl = null
        }
        fun removeMarkers() {
            loaded.removeLayer(CAM_LAYER)
            loaded.removeSource(CAM_SOURCE)
        }

        when (overlay) {
            MapOverlay.None -> { removeImage(); removeTiles(); removeMarkers() }
            is MapOverlay.Image -> {
                removeTiles(); removeMarkers()
                val image = overlay.bitmap
                val source = loaded.getSourceAs<ImageSource>(IMAGE_SOURCE)
                when {
                    image == null -> loaded.getLayer(IMAGE_LAYER)?.setProperties(PropertyFactory.visibility("none"))
                    source == null || imageBounds != overlay.bounds -> {
                        removeImage()
                        loaded.addSource(ImageSource(IMAGE_SOURCE, overlay.bounds.toQuad(), image))
                        addBelowLabels(
                            RasterLayer(IMAGE_LAYER, IMAGE_SOURCE).withProperties(
                                PropertyFactory.rasterOpacity(overlay.opacity),
                                PropertyFactory.rasterFadeDuration(0f),
                            ),
                        )
                        imageBounds = overlay.bounds
                    }
                    else -> {
                        source.setImage(image)
                        loaded.getLayer(IMAGE_LAYER)?.setProperties(
                            PropertyFactory.visibility("visible"),
                            PropertyFactory.rasterOpacity(overlay.opacity),
                        )
                    }
                }
            }
            is MapOverlay.Tiles -> {
                removeImage(); removeMarkers()
                if (tileUrl != overlay.urlTemplate) {
                    removeTiles()
                    loaded.addSource(RasterSource(TILE_SOURCE, TileSet("2.2.0", overlay.urlTemplate), 512))
                    addBelowLabels(
                        RasterLayer(TILE_LAYER, TILE_SOURCE).withProperties(
                            PropertyFactory.rasterOpacity(overlay.opacity),
                            PropertyFactory.rasterFadeDuration(0f),
                        ),
                    )
                    tileUrl = overlay.urlTemplate
                }
            }
            is MapOverlay.Markers -> {
                removeImage(); removeTiles()
                val features = FeatureCollection.fromFeatures(
                    overlay.webcams.map { cam ->
                        Feature.fromGeometry(Point.fromLngLat(cam.longitude, cam.latitude)).apply { addNumberProperty("id", cam.id) }
                    },
                )
                val source = loaded.getSourceAs<GeoJsonSource>(CAM_SOURCE)
                if (source != null) {
                    source.setGeoJson(features)
                } else {
                    loaded.addSource(GeoJsonSource(CAM_SOURCE, features))
                    loaded.addLayer(
                        CircleLayer(CAM_LAYER, CAM_SOURCE).withProperties(
                            PropertyFactory.circleRadius(8f),
                            PropertyFactory.circleColor("#FFC21A"),
                            PropertyFactory.circleStrokeColor("#0C304E"),
                            PropertyFactory.circleStrokeWidth(2.5f),
                        ),
                    )
                }
            }
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun GeoBounds.toQuad() = LatLngQuad(
    LatLng(north, west),
    LatLng(north, east),
    LatLng(south, east),
    LatLng(south, west),
)

/** MapView suit le cycle de vie de l'écran (démarrage, pause, destruction). */
@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> { mapView.onStop(); started = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}
