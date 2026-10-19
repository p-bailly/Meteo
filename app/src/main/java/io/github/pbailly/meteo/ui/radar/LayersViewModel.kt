package io.github.pbailly.meteo.ui.radar

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.pbailly.meteo.data.maps.DwdTemperature
import io.github.pbailly.meteo.data.maps.MapKeyException
import io.github.pbailly.meteo.data.maps.TileLayer
import io.github.pbailly.meteo.data.maps.Webcam
import io.github.pbailly.meteo.data.maps.WindyWebcams
import io.github.pbailly.meteo.data.maps.WmsTimes
import io.github.pbailly.meteo.data.maps.httpBytes
import io.github.pbailly.meteo.data.maps.renderLatLonGrid
import io.github.pbailly.meteo.data.radar.GeoTiff
import io.github.pbailly.meteo.ui.theme.temperatureColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.coroutines.cancellation.CancellationException

/** Couches de la carte, dans l'ordre du sélecteur. */
enum class MapLayer(val label: String) {
    RAIN("Pluie"),
    TEMPERATURE("Température"),
    SATELLITE("Satellite"),
    LIGHTNING("Foudre"),
    WEBCAMS("Webcams"),
}

data class LayerUiState(
    val layer: MapLayer = MapLayer.RAIN,
    val times: List<Instant> = emptyList(),
    val selected: Int = 0,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val overlay: MapOverlay = MapOverlay.None,
    val windyKey: String? = null,
    val webcams: List<Webcam> = emptyList(),
    val openWebcam: Webcam? = null,
    val webcamImage: Bitmap? = null,
)

/**
 * Couches autres que la pluie : température (DWD ICON-EU), satellite et foudre (EUMETSAT, en tuiles),
 * webcams (Windy, clé gratuite).
 */
class LayersViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Application.MODE_PRIVATE)
    private val _state = MutableStateFlow(LayerUiState(windyKey = prefs.getString(KEY_WINDY, null)?.ifBlank { null }))
    val state: StateFlow<LayerUiState> = _state.asStateFlow()

    private var center: Pair<Double, Double> = 46.5 to 2.5
    private val temperatures = LruCache<Instant, Bitmap>(8)
    private var webcamsFetchedAt: Instant? = null
    private var loadJob: Job? = null
    private var showJob: Job? = null
    private var playJob: Job? = null

    fun setCenter(latitude: Double, longitude: Double) {
        if (center != latitude to longitude) webcamsFetchedAt = null
        center = latitude to longitude
    }

    fun saveWindyKey(key: String) {
        prefs.edit().putString(KEY_WINDY, key.trim()).apply()
        webcamsFetchedAt = null
        _state.update { it.copy(windyKey = key.trim().ifEmpty { null }, error = null) }
        if (_state.value.layer == MapLayer.WEBCAMS) show(MapLayer.WEBCAMS)
    }

    /** Change de couche (la pluie reste gérée par le radar) et charge ses heures disponibles. */
    fun show(layer: MapLayer) {
        stopPlaying()
        loadJob?.cancel()
        showJob?.cancel()
        _state.update { it.copy(layer = layer, times = emptyList(), selected = 0, overlay = MapOverlay.None, error = null, openWebcam = null) }
        if (layer == MapLayer.RAIN) return
        _state.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            try {
                when (layer) {
                    MapLayer.TEMPERATURE -> {
                        val now = Instant.now().truncatedTo(ChronoUnit.HOURS)
                        val caps = httpBytes(DwdTemperature.CAPABILITIES).decodeToString()
                        val times = WmsTimes.fromCapabilities(caps, now, now.plus(Duration.ofHours(72)))
                            // Toutes les heures sur 24 h, puis toutes les 3 heures.
                            .filter { Duration.between(now, it).toHours() < 24 || it.atZone(java.time.ZoneOffset.UTC).hour % 3 == 0 }
                        publishTimes(times, selectLatest = false)
                    }
                    MapLayer.SATELLITE, MapLayer.LIGHTNING -> {
                        val tiles = if (layer == MapLayer.SATELLITE) TileLayer.SATELLITE else TileLayer.LIGHTNING
                        val now = Instant.now()
                        val caps = httpBytes(tiles.capabilitiesUrl).decodeToString()
                        publishTimes(WmsTimes.fromCapabilities(caps, now.minus(tiles.history), now), selectLatest = true)
                    }
                    MapLayer.WEBCAMS -> loadWebcams()
                    MapLayer.RAIN -> Unit
                }
                _state.update { it.copy(loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    fun select(index: Int) {
        val times = _state.value.times
        if (times.isEmpty()) return
        _state.update { it.copy(selected = index.coerceIn(0, times.lastIndex)) }
        showSelected()
    }

    fun togglePlay() {
        if (_state.value.playing) {
            stopPlaying()
            return
        }
        _state.update { it.copy(playing = true) }
        playJob = viewModelScope.launch {
            while (isActive && _state.value.times.size > 1) {
                val s = _state.value
                val next = if (s.selected >= s.times.lastIndex) 0 else s.selected + 1
                select(next)
                showJob?.join()
                // Les tuiles satellite mettent 1 à 3 s à arriver : on laisse le temps de les voir.
                delay(if (s.layer == MapLayer.TEMPERATURE) 700 else 1500)
            }
            _state.update { it.copy(playing = false) }
        }
    }

    fun stopPlaying() {
        playJob?.cancel()
        _state.update { it.copy(playing = false) }
    }

    fun openWebcam(id: Long) {
        val cam = _state.value.webcams.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(openWebcam = cam, webcamImage = null) }
        val url = cam.imageUrl ?: return
        viewModelScope.launch {
            val bitmap = runCatching {
                val bytes = httpBytes(url)
                withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            }.getOrNull()
            if (_state.value.openWebcam?.id == id) _state.update { it.copy(webcamImage = bitmap) }
        }
    }

    fun closeWebcam() {
        _state.update { it.copy(openWebcam = null, webcamImage = null) }
    }

    private fun publishTimes(times: List<Instant>, selectLatest: Boolean) {
        val now = Instant.now()
        val selected = when {
            times.isEmpty() -> 0
            selectLatest -> times.lastIndex
            else -> times.indexOfFirst { !it.isBefore(now.truncatedTo(ChronoUnit.HOURS)) }.coerceAtLeast(0)
        }
        _state.update { it.copy(times = times, selected = selected) }
        showSelected()
    }

    private fun showSelected() {
        val s = _state.value
        val time = s.times.getOrNull(s.selected) ?: return
        when (s.layer) {
            MapLayer.SATELLITE -> _state.update { it.copy(overlay = MapOverlay.Tiles(TileLayer.SATELLITE.tileUrl(time))) }
            MapLayer.LIGHTNING -> _state.update { it.copy(overlay = MapOverlay.Tiles(TileLayer.LIGHTNING.tileUrl(time))) }
            MapLayer.TEMPERATURE -> showTemperature(time)
            else -> Unit
        }
    }

    /** Température : GeoTIFF du DWD (environ 350 Ko), colorée comme les cases de l'app. */
    private fun showTemperature(time: Instant) {
        temperatures.get(time)?.let { cached ->
            _state.update { it.copy(overlay = MapOverlay.Image(cached, DwdTemperature.BOUNDS, opacity = 0.75f)) }
            return
        }
        showJob?.cancel()
        showJob = viewModelScope.launch {
            try {
                _state.update { it.copy(loading = true) }
                val bytes = httpBytes(DwdTemperature.coverageUrl(time))
                val bitmap = withContext(Dispatchers.Default) {
                    val image = GeoTiff.read(bytes)
                    val (pixels, height) = renderLatLonGrid(image, DwdTemperature.BOUNDS, TEMPERATURE_WIDTH) { celsius ->
                        temperatureColor(celsius.toDouble()).toArgb()
                    }
                    Bitmap.createBitmap(pixels, TEMPERATURE_WIDTH, height, Bitmap.Config.ARGB_8888)
                }
                temperatures.put(time, bitmap)
                if (_state.value.times.getOrNull(_state.value.selected) == time) {
                    _state.update { it.copy(overlay = MapOverlay.Image(bitmap, DwdTemperature.BOUNDS, opacity = 0.75f), loading = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    /** Webcams à 60 km autour du lieu ; les liens d'images expirent, on relit la liste après 8 minutes. */
    private suspend fun loadWebcams() {
        val key = _state.value.windyKey ?: return
        val fetched = webcamsFetchedAt
        if (fetched != null && Duration.between(fetched, Instant.now()) < Duration.ofMinutes(8)) {
            _state.update { it.copy(overlay = MapOverlay.Markers(it.webcams)) }
            return
        }
        val (lat, lon) = center
        val body = httpBytes(WindyWebcams.nearbyUrl(lat, lon), mapOf("x-windy-api-key" to key)).decodeToString()
        val cams = WindyWebcams.parse(body)
        webcamsFetchedAt = Instant.now()
        _state.update {
            it.copy(
                webcams = cams,
                overlay = MapOverlay.Markers(cams),
                error = if (cams.isEmpty()) "Aucune webcam à moins de 60 km." else null,
            )
        }
    }

    private companion object {
        const val KEY_WINDY = "windy_api_key"
        const val TEMPERATURE_WIDTH = 900
    }
}

private fun Exception.userMessage(): String = when (this) {
    is MapKeyException -> "Clé Windy refusée : vérifie-la dans les Réglages."
    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException -> "Pas de connexion internet"
    is IOException -> message ?: "Erreur réseau"
    else -> "Carte indisponible : ${message ?: javaClass.simpleName}"
}
