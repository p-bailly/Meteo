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
import io.github.pbailly.meteo.data.maps.Measure
import io.github.pbailly.meteo.data.maps.ObservationSource
import io.github.pbailly.meteo.data.maps.StationReading
import io.github.pbailly.meteo.data.maps.MeteoFranceObservations
import io.github.pbailly.meteo.data.maps.MeteoSwissObservations
import io.github.pbailly.meteo.data.maps.MetarObservations
import io.github.pbailly.meteo.data.maps.StationInfo
import io.github.pbailly.meteo.data.maps.StationObservation
import io.github.pbailly.meteo.data.maps.mergeObservations
import io.github.pbailly.meteo.data.maps.stationMinZooms
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.coroutines.cancellation.CancellationException

private val HOUR = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

/** Station touchée : son relevé, toutes ses mesures et ses dernières 24 heures (quand la source les donne). */
data class StationDetails(
    val station: StationObservation,
    val values: Map<Measure, Double>,
    val history: List<StationReading>,
    val loading: Boolean,
)

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
    /** Relevés des stations, affichés sur la carte de température à l'heure actuelle. */
    val stations: List<StationMarker> = emptyList(),
    val openStation: StationDetails? = null,
    /** « Relevés de 21 h · 1 830 stations », ou comment en avoir plus. */
    val observationNote: String? = null,
)

/**
 * Couches autres que la pluie : température (DWD ICON-EU), satellite et foudre (EUMETSAT, en tuiles),
 * webcams (Windy, clé gratuite).
 */
class LayersViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Application.MODE_PRIVATE)
    private val radarPrefs = app.getSharedPreferences("radar", Application.MODE_PRIVATE)
    private val stationsFile = File(app.filesDir, "observations/stations-meteofrance.csv")
    private val _state = MutableStateFlow(LayerUiState(windyKey = prefs.getString(KEY_WINDY, null)?.ifBlank { null }))
    val state: StateFlow<LayerUiState> = _state.asStateFlow()

    private var center: Pair<Double, Double> = 46.5 to 2.5
    private val temperatures = LruCache<Instant, Bitmap>(8)
    private var webcamsFetchedAt: Instant? = null
    private var observations: List<StationObservation> = emptyList()
    private var markers: List<StationMarker> = emptyList()
    private var observationsAt: Instant? = null
    private var observationNote: String? = null
    private var stationInfo: Map<String, StationInfo>? = null
    private var observationJob: Job? = null
    private var detailsJob: Job? = null
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
        _state.update {
            it.copy(layer = layer, times = emptyList(), selected = 0, overlay = MapOverlay.None, error = null, openWebcam = null, stations = emptyList(), openStation = null)
        }
        if (layer == MapLayer.RAIN) return
        _state.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            try {
                when (layer) {
                    MapLayer.TEMPERATURE -> {
                        // Seulement la carte actuelle : l'heure du modèle la plus proche de maintenant.
                        val now = Instant.now()
                        val caps = httpBytes(DwdTemperature.CAPABILITIES).decodeToString()
                        val current = WmsTimes.fromCapabilities(caps, now.minus(Duration.ofHours(3)), now.plus(Duration.ofHours(3)))
                            .minByOrNull { Duration.between(it, now).abs() } ?: now.truncatedTo(ChronoUnit.HOURS)
                        publishTimes(listOf(current), selectLatest = true)
                        loadObservations()
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
        publishStations()
    }

    /** Affiche la station, puis va chercher ses autres mesures (MétéoSuisse) et ses dernières 24 heures. */
    fun openStation(id: String) {
        val station = observations.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(openStation = StationDetails(station, station.values, emptyList(), loading = true)) }
        detailsJob?.cancel()
        detailsJob = viewModelScope.launch {
            val (values, history) = coroutineScope {
                val values = async { if (station.source == ObservationSource.METEOSWISS) swissMeasures(station.id) else station.values }
                val history = async { history(station) }
                values.await() to history.await()
            }
            _state.update { s ->
                if (s.openStation?.station?.id != id) s else s.copy(openStation = StationDetails(station, values, history, loading = false))
            }
        }
    }

    fun closeStation() {
        detailsJob?.cancel()
        _state.update { it.copy(openStation = null) }
    }

    private suspend fun swissMeasures(id: String): Map<Measure, Double> = coroutineScope {
        MeteoSwissObservations.MEASURES.map { (measure, name) ->
            async {
                runCatching {
                    MeteoSwissObservations.parseMeasure(httpBytes(MeteoSwissObservations.measureUrl(name)).decodeToString(), id)
                }.getOrNull()?.let { (value, direction) ->
                    listOfNotNull(measure to value, direction?.takeIf { measure == Measure.WIND }?.let { Measure.WIND_DIRECTION to it })
                }.orEmpty()
            }
        }.flatMap { it.await() }.toMap()
    }

    private suspend fun history(station: StationObservation): List<StationReading> = try {
        when (station.source) {
            ObservationSource.METEO_FRANCE -> {
                val key = radarPrefs.getString(KEY_METEO_FRANCE, null)?.takeIf { it.isNotBlank() }
                if (key == null) {
                    emptyList()
                } else {
                    val body = httpBytes(MeteoFranceObservations.historyUrl(station.id), mapOf("apikey" to key, "Accept" to "*/*"))
                    withContext(Dispatchers.Default) { MeteoFranceObservations.parseHistory(body.decodeToString()) }
                }
            }
            ObservationSource.METAR -> MetarObservations.parseHistory(httpBytes(MetarObservations.historyUrl(station.id)).decodeToString())
            ObservationSource.METEOSWISS -> emptyList()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    /** Relevés des stations : Météo-France avec la clé (si elle est abonnée aux observations), MétéoSuisse et aéroports. */
    private fun loadObservations() {
        val fetched = observationsAt
        if (fetched != null && Duration.between(fetched, Instant.now()) < Duration.ofMinutes(10)) {
            publishStations()
            return
        }
        observationJob?.cancel()
        observationJob = viewModelScope.launch {
            val key = radarPrefs.getString(KEY_METEO_FRANCE, null)?.takeIf { it.isNotBlank() }
            val (list, note) = fetchObservations(key)
            observations = list
            markers = withContext(Dispatchers.Default) {
                val zooms = stationMinZooms(list)
                list.mapIndexed { i, o ->
                    StationMarker(o.id, o.latitude, o.longitude, Math.round(o.temperature).toString(), temperatureColor(o.temperature).toArgb(), zooms[i])
                }
            }
            observationsAt = Instant.now()
            val latest = list.maxOfOrNull { it.time }
            observationNote = listOfNotNull(
                latest?.let { "Relevés de ${HOUR.format(it.atZone(java.time.ZoneId.systemDefault()))} · ${list.size} stations" },
                note,
            ).joinToString("\n").ifEmpty { null }
            publishStations()
        }
    }

    private suspend fun fetchObservations(key: String?): Pair<List<StationObservation>, String?> = coroutineScope {
        val swiss = async {
            runCatching { MeteoSwissObservations.parse(httpBytes(MeteoSwissObservations.URL).decodeToString()) }.getOrDefault(emptyList())
        }
        val airports = async {
            runCatching { MetarObservations.parse(httpBytes(MetarObservations.url()).decodeToString()) }.getOrDefault(emptyList())
        }
        var hint: String? = null
        val france = if (key == null) {
            hint = "Plus de stations : clé Météo-France (Réglages)"
            emptyList()
        } else {
            try {
                meteoFranceObservations(key)
            } catch (e: CancellationException) {
                throw e
            } catch (_: MapKeyException) {
                hint = "Clé Météo-France non abonnée aux observations"
                emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
        // Avec Météo-France, les aéroports français font doublon : on ne garde que ceux des pays voisins.
        val metar = airports.await().let { list -> if (france.isNotEmpty()) list.filterNot { it.id.startsWith("LF") } else list }
        mergeObservations(france, swiss.await(), metar) to hint
    }

    private suspend fun meteoFranceObservations(key: String): List<StationObservation> {
        val headers = mapOf("apikey" to key, "Accept" to "*/*")
        val names = stationNames(headers)
        val hour = MeteoFranceObservations.latestHour(Instant.now())
        val body = try {
            httpBytes(MeteoFranceObservations.hourlyUrl(hour), headers)
        } catch (e: MapKeyException) {
            throw e
        } catch (_: IOException) {
            // Paquet de l'heure pas encore publié : celui de l'heure d'avant.
            httpBytes(MeteoFranceObservations.hourlyUrl(hour.minus(Duration.ofHours(1))), headers)
        }
        return withContext(Dispatchers.Default) { MeteoFranceObservations.parse(body.decodeToString(), names) }
    }

    /** Noms et altitudes des stations Météo-France, relus chaque semaine. */
    private suspend fun stationNames(headers: Map<String, String>): Map<String, StationInfo> {
        stationInfo?.let { return it }
        val fresh = stationsFile.exists() && System.currentTimeMillis() - stationsFile.lastModified() < 7 * 24 * 3600 * 1000L
        val text = if (fresh) {
            withContext(Dispatchers.IO) { stationsFile.readText() }
        } else {
            try {
                httpBytes(MeteoFranceObservations.STATIONS_URL, headers).decodeToString().also { csv ->
                    withContext(Dispatchers.IO) {
                        stationsFile.parentFile?.mkdirs()
                        stationsFile.writeText(csv)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MapKeyException) {
                throw e
            } catch (_: Exception) {
                if (stationsFile.exists()) withContext(Dispatchers.IO) { stationsFile.readText() } else ""
            }
        }
        return MeteoFranceObservations.parseStations(text).also { if (it.isNotEmpty()) stationInfo = it }
    }

    /** Les points des stations, sur la carte de température (qui est toujours la carte actuelle). */
    private fun publishStations() {
        val current = _state.value.layer == MapLayer.TEMPERATURE
        _state.update {
            it.copy(
                stations = if (current) markers else emptyList(),
                observationNote = if (current) observationNote else null,
                openStation = if (current) it.openStation else null,
            )
        }
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
        /** La clé Météo-France du radar (même préférence que RadarViewModel). */
        const val KEY_METEO_FRANCE = "meteofrance_api_key"
        const val TEMPERATURE_WIDTH = 900
    }
}

private fun Exception.userMessage(): String = when (this) {
    is MapKeyException -> "Clé Windy refusée : vérifie-la dans les Réglages."
    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException -> "Pas de connexion internet"
    is IOException -> message ?: "Erreur réseau"
    else -> "Carte indisponible : ${message ?: javaClass.simpleName}"
}
