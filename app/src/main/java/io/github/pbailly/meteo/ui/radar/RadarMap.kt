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
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
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
import kotlin.math.floor

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
private const val PLACE_HALO_LAYER = "place-halo-layer"
private const val STATION_SOURCE = "stations"
private const val STATION_LAYER = "stations-layer"
private const val STATION_TEXT = "stations-text"
private const val PIN_SOURCE = "pin"
private const val PIN_LAYER = "pin-layer"
private const val PIN_HALO_LAYER = "pin-halo-layer"
/** Orange d'accent de l'app : le point choisi ne se confond pas avec le lieu, noir. */
private const val PIN_COLOR = "#E2701F"

/** Relevé d'une station sur la carte : pastille colorée selon la température, affichée à partir de [minZoom]. */
data class StationMarker(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val color: Int,
    val minZoom: Int,
)

/** Stations visibles au zoom [zoom] (entier inférieur), en GeoJSON. */
private fun stationFeatures(stations: List<StationMarker>, zoom: Double): FeatureCollection {
    val z = floor(zoom).toInt()
    return FeatureCollection.fromFeatures(
        stations.filter { it.minZoom <= z }.map { s ->
            Feature.fromGeometry(Point.fromLngLat(s.longitude, s.latitude)).apply {
                addStringProperty("id", s.id)
                addStringProperty("label", s.label)
                addStringProperty("color", String.format("#%06X", s.color and 0xFFFFFF))
            }
        },
    )
}

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
 * Carte MapLibre : fond OpenFreeMap, couche météo posée sous les noms de villes, lieu entouré (si [showPlace]).
 * [onWebcamClick] reçoit l'identifiant de la webcam touchée ; [onMapClick], le point touché ailleurs, que la carte
 * marque d'une épingle quand on le lui rend dans [pin].
 */
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
    val mapView = rememberMapViewWithLifecycle()
    var style by remember { mutableStateOf<Style?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val clicks by rememberUpdatedState(onWebcamClick)
    val stationClicks by rememberUpdatedState(onStationClick)
    val mapClicks by rememberUpdatedState(onMapClick)
    val currentStations by rememberUpdatedState(stations)
    var imageBounds by remember { mutableStateOf<GeoBounds?>(null) }
    var tileUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { loadedMap ->
            map = loadedMap
            loadedMap.uiSettings.apply {
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                logoGravity = Gravity.TOP or Gravity.START
                attributionGravity = Gravity.TOP or Gravity.START
                setAttributionMargins(110, 12, 0, 0)
            }
            loadedMap.cameraPosition = CameraPosition.Builder().target(center).zoom(zoom).build()
            // Au changement de zoom entier, les stations qui ont de la place apparaissent (ou disparaissent).
            var shownZoom = -1
            loadedMap.addOnCameraIdleListener {
                val z = floor(loadedMap.cameraPosition.zoom).toInt()
                if (z != shownZoom) {
                    shownZoom = z
                    loadedMap.style?.getSourceAs<GeoJsonSource>(STATION_SOURCE)?.setGeoJson(stationFeatures(currentStations, loadedMap.cameraPosition.zoom))
                }
            }
            loadedMap.addOnMapClickListener { point ->
                val screen = loadedMap.projection.toScreenLocation(point)
                val station = loadedMap.queryRenderedFeatures(screen, STATION_LAYER).firstOrNull()?.getStringProperty("id")
                if (station != null) {
                    stationClicks(station)
                    return@addOnMapClickListener true
                }
                val feature = loadedMap.queryRenderedFeatures(screen, CAM_LAYER).firstOrNull()
                if (feature != null) {
                    feature.getNumberProperty("id")?.let { clicks(it.toLong()) }
                    return@addOnMapClickListener true
                }
                val tap = mapClicks ?: return@addOnMapClickListener false
                tap(point)
                true
            }
            loadedMap.setStyle(Style.Builder().fromUri(STYLE_URL)) { loaded ->
                if (!showPlace) {
                    style = loaded
                    return@setStyle
                }
                loaded.addSource(GeoJsonSource(PLACE_SOURCE, Point.fromLngLat(center.longitude, center.latitude)))
                // Le lieu : un point noir cerclé de blanc dans un halo blanc, lisible sur toutes les couleurs de la
                // pluie (bleus, violet, rouge, jaune), des températures et du satellite.
                loaded.addLayer(
                    CircleLayer(PLACE_HALO_LAYER, PLACE_SOURCE).withProperties(
                        PropertyFactory.circleRadius(11f),
                        PropertyFactory.circleOpacity(0f),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(2.5f),
                        PropertyFactory.circleStrokeOpacity(0.9f),
                    ),
                )
                loaded.addLayer(
                    CircleLayer(PLACE_LAYER, PLACE_SOURCE).withProperties(
                        PropertyFactory.circleRadius(5.5f),
                        PropertyFactory.circleColor("#111111"),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(2f),
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

    // Point choisi sur la carte : un rond orange dans un halo, au-dessus du lieu.
    LaunchedEffect(style, pin) {
        val loaded = style ?: return@LaunchedEffect
        if (pin == null) {
            loaded.removeLayer(PIN_LAYER)
            loaded.removeLayer(PIN_HALO_LAYER)
            loaded.removeSource(PIN_SOURCE)
            return@LaunchedEffect
        }
        val point = Point.fromLngLat(pin.longitude, pin.latitude)
        val source = loaded.getSourceAs<GeoJsonSource>(PIN_SOURCE)
        if (source != null) {
            source.setGeoJson(point)
        } else {
            loaded.addSource(GeoJsonSource(PIN_SOURCE, point))
            loaded.addLayer(
                CircleLayer(PIN_HALO_LAYER, PIN_SOURCE).withProperties(
                    PropertyFactory.circleRadius(16f),
                    PropertyFactory.circleColor(PIN_COLOR),
                    PropertyFactory.circleOpacity(0.25f),
                ),
            )
            loaded.addLayer(
                CircleLayer(PIN_LAYER, PIN_SOURCE).withProperties(
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleColor(PIN_COLOR),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeWidth(2.5f),
                ),
            )
        }
    }

    // Relevés des stations, au-dessus de tout le reste.
    LaunchedEffect(style, stations) {
        val loaded = style ?: return@LaunchedEffect
        if (stations.isEmpty()) {
            loaded.removeLayer(STATION_TEXT)
            loaded.removeLayer(STATION_LAYER)
            loaded.removeSource(STATION_SOURCE)
            return@LaunchedEffect
        }
        val features = stationFeatures(stations, map?.cameraPosition?.zoom ?: 6.0)
        val source = loaded.getSourceAs<GeoJsonSource>(STATION_SOURCE)
        if (source != null) {
            source.setGeoJson(features)
        } else {
            loaded.addSource(GeoJsonSource(STATION_SOURCE, features))
            loaded.addLayer(
                CircleLayer(STATION_LAYER, STATION_SOURCE).withProperties(
                    PropertyFactory.circleRadius(11f),
                    PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeWidth(1.2f),
                ),
            )
            loaded.addLayer(
                SymbolLayer(STATION_TEXT, STATION_SOURCE).withProperties(
                    PropertyFactory.textField(Expression.get("label")),
                    PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                    PropertyFactory.textSize(11f),
                    PropertyFactory.textColor("#0F2133"),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                ),
            )
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
