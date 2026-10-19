package io.github.pbailly.meteo.ui.radar

import android.graphics.Bitmap
import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import org.maplibre.geojson.Point

/** Fond de carte gratuit et sans clé (OpenFreeMap, données OpenStreetMap), avec relief ombré. */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val RADAR_SOURCE = "radar"
private const val RADAR_LAYER = "radar-layer"
private const val PLACE_SOURCE = "place"
private const val PLACE_LAYER = "place-layer"

/**
 * Carte MapLibre avec l'image radar posée par-dessus le relief, sous les noms de villes.
 * [image] doit être en projection Web Mercator et couvrir exactement [bounds].
 */
@Composable
fun RadarMap(
    image: Bitmap?,
    bounds: GeoBounds,
    center: LatLng,
    modifier: Modifier = Modifier,
) {
    val mapView = rememberMapViewWithLifecycle()
    var style by remember { mutableStateOf<Style?>(null) }

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

    // Ajoute ou remplace l'image radar quand elle change (animation, nouvelle image).
    LaunchedEffect(style, image) {
        val loaded = style ?: return@LaunchedEffect
        val source = loaded.getSourceAs<ImageSource>(RADAR_SOURCE)
        when {
            image == null -> loaded.getLayer(RADAR_LAYER)?.setProperties(PropertyFactory.visibility("none"))
            source == null -> {
                loaded.addSource(ImageSource(RADAR_SOURCE, bounds.toQuad(), image))
                val layer = RasterLayer(RADAR_LAYER, RADAR_SOURCE).withProperties(
                    PropertyFactory.rasterOpacity(0.85f),
                    PropertyFactory.rasterFadeDuration(0f),
                )
                // Sous le premier calque de texte, pour garder les noms de villes lisibles.
                val firstLabel = loaded.layers.firstOrNull { it is SymbolLayer }?.id
                if (firstLabel != null) loaded.addLayerBelow(layer, firstLabel) else loaded.addLayer(layer)
            }
            else -> {
                source.setImage(image)
                loaded.getLayer(RADAR_LAYER)?.setProperties(PropertyFactory.visibility("visible"))
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
