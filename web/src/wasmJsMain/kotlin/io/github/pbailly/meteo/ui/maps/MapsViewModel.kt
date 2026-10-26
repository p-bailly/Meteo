package io.github.pbailly.meteo.ui.maps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.pbailly.meteo.data.defaultHttpGet
import io.github.pbailly.meteo.data.maps.RainViewer
import io.github.pbailly.meteo.data.maps.TileLayer
import io.github.pbailly.meteo.data.maps.WmsTimes
import io.github.pbailly.meteo.data.networkMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/** Les couches des Cartes de la webapp. */
enum class WebLayer(val label: String) {
    RAIN("Pluie"),
    SATELLITE("Satellite"),
    LIGHTNING("Foudre"),
}

/** Une image de la frise : son heure et ses tuiles. */
data class MapFrame(val time: Instant, val tiles: MapTiles)

data class MapsUiState(
    val layer: WebLayer = WebLayer.RAIN,
    val frames: List<MapFrame> = emptyList(),
    val selected: Int = -1,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val current: MapFrame? get() = frames.getOrNull(selected)
}

/**
 * Cartes de la webapp : radar RainViewer (sans clé), satellite et éclairs de Meteosat (EUMETSAT, sans clé), sur
 * les deux dernières heures (la dernière pour la foudre). La frise s'ouvre sur l'image la plus récente.
 */
class MapsViewModel : ViewModel() {
    private val _state = MutableStateFlow(MapsUiState())
    val state: StateFlow<MapsUiState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var playJob: Job? = null
    private var lastLoad = 0L

    /** À l'ouverture de l'onglet : recharge si les images ont plus de 5 minutes. */
    fun onShown() {
        if (_state.value.frames.isEmpty() || nowMillis() - lastLoad > REFRESH_MS) show(_state.value.layer)
    }

    fun show(layer: WebLayer) {
        stopPlaying()
        loadJob?.cancel()
        _state.update { MapsUiState(layer = layer, loading = true, frames = if (it.layer == layer) it.frames else emptyList(), selected = if (it.layer == layer) it.selected else -1) }
        loadJob = viewModelScope.launch {
            try {
                val frames = when (layer) {
                    WebLayer.RAIN -> RainViewer.parse(defaultHttpGet(RainViewer.URL)).map {
                        MapFrame(it.time, MapTiles(it.tileUrl, opacity = 0.8, maxZoom = RainViewer.MAX_ZOOM, tileSize = RainViewer.TILE_SIZE))
                    }
                    WebLayer.SATELLITE -> wms(TileLayer.SATELLITE, opacity = 1.0)
                    WebLayer.LIGHTNING -> wms(TileLayer.LIGHTNING, opacity = 1.0)
                }
                lastLoad = nowMillis()
                _state.update {
                    if (it.layer != layer) it
                    else it.copy(frames = frames, selected = frames.lastIndex, loading = false, error = if (frames.isEmpty()) "Aucune image disponible" else null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = (e as? IOException)?.networkMessage() ?: "Carte indisponible"
                _state.update { if (it.layer != layer) it else it.copy(loading = false, error = message) }
            }
        }
    }

    private suspend fun wms(layer: TileLayer, opacity: Double): List<MapFrame> {
        val now = Instant.now()
        val times = WmsTimes.fromCapabilities(defaultHttpGet(layer.capabilitiesUrl), now.minus(layer.history), now)
        // Une image toutes les 10 minutes au plus : la frise reste lisible et l'animation légère.
        return times.filterIndexed { i, t -> i == times.lastIndex || t.epochSecond % 600 == 0L }
            .map { MapFrame(it, MapTiles(layer.tileUrl(it), opacity = opacity, tileSize = 512)) }
    }

    fun select(index: Int) {
        stopPlaying()
        _state.update { if (index in it.frames.indices) it.copy(selected = index) else it }
    }

    fun togglePlay() {
        if (_state.value.playing) {
            stopPlaying()
            return
        }
        if (_state.value.frames.size < 2) return
        _state.update { it.copy(playing = true, selected = if (it.selected == it.frames.lastIndex) 0 else it.selected) }
        playJob = viewModelScope.launch {
            while (true) {
                val s = _state.value
                // Une pause sur la dernière image, puis on reprend au début.
                delay(if (s.selected == s.frames.lastIndex) 1_500 else 700)
                _state.update { it.copy(selected = if (it.selected >= it.frames.lastIndex) 0 else it.selected + 1) }
            }
        }
    }

    fun stopPlaying() {
        playJob?.cancel()
        playJob = null
        _state.update { it.copy(playing = false) }
    }

    private companion object {
        const val REFRESH_MS = 5 * 60 * 1000L
        fun nowMillis() = Instant.now().toEpochMilli()
    }
}

