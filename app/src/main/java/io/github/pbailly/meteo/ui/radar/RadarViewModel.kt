package io.github.pbailly.meteo.ui.radar

import android.app.Application
import android.graphics.Bitmap
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.pbailly.meteo.data.radar.GeoBounds
import io.github.pbailly.meteo.data.radar.MeteoFranceRadar
import io.github.pbailly.meteo.data.radar.RadarAuthException
import io.github.pbailly.meteo.data.radar.RadarFrame
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

data class RadarUiState(
    val apiKey: String? = null,
    val frames: List<Instant> = emptyList(),
    val selected: Int = 0,
    val currentImage: Bitmap? = null,
    val bounds: GeoBounds = MeteoFranceRadar.BOUNDS,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val hasKey: Boolean get() = !apiKey.isNullOrBlank()
}

class RadarViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("radar", Application.MODE_PRIVATE)
    private val radar = MeteoFranceRadar(File(app.cacheDir, "radar"))

    private val _state = MutableStateFlow(RadarUiState(apiKey = prefs.getString(KEY_API, null)))
    val state: StateFlow<RadarUiState> = _state.asStateFlow()

    private var frames: List<RadarFrame> = emptyList()

    /** Quelques images décodées en mémoire (8 Mo chacune) ; les autres sont relues depuis le disque. */
    private val bitmaps = LruCache<Instant, Bitmap>(6)
    private var loadJob: Job? = null
    private var showJob: Job? = null
    private var playJob: Job? = null

    init {
        // Les images des 2 dernières heures déjà reçues s'affichent tout de suite, sans réseau.
        setFrames(radar.stored(), selectLatest = true)
    }

    fun saveKey(key: String) {
        prefs.edit().putString(KEY_API, key.trim()).apply()
        _state.update { it.copy(apiKey = key.trim(), error = null) }
        refresh()
    }

    /** Vérifie s'il y a une nouvelle image Météo-France et la télécharge (environ 2 Mo). */
    fun refresh() {
        val key = _state.value.apiKey?.takeIf { it.isNotBlank() } ?: return
        if (loadJob?.isActive == true) return
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val wasOnLatest = _state.value.selected >= frames.lastIndex
                setFrames(radar.frames(key), selectLatest = wasOnLatest && !_state.value.playing)
                _state.update { it.copy(loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    fun select(index: Int) {
        if (frames.isEmpty()) return
        _state.update { it.copy(selected = index.coerceIn(0, frames.lastIndex)) }
        showSelected()
    }

    fun togglePlay() {
        if (_state.value.playing) {
            stopPlaying()
            return
        }
        _state.update { it.copy(playing = true) }
        playJob = viewModelScope.launch {
            while (isActive && frames.size > 1) {
                val current = _state.value.selected
                val next = if (current >= frames.lastIndex) 0 else current + 1
                select(next)
                showJob?.join()
                delay(if (next == frames.lastIndex) 1500 else 500)
            }
            _state.update { it.copy(playing = false) }
        }
    }

    fun stopPlaying() {
        playJob?.cancel()
        _state.update { it.copy(playing = false) }
    }

    private fun setFrames(list: List<RadarFrame>, selectLatest: Boolean) {
        val previous = frames.getOrNull(_state.value.selected)?.time
        frames = list
        val selected = when {
            list.isEmpty() -> 0
            selectLatest || previous == null -> list.lastIndex
            else -> list.indexOfFirst { it.time == previous }.takeIf { it >= 0 } ?: list.lastIndex
        }
        _state.update { it.copy(frames = list.map(RadarFrame::time), selected = selected) }
        showSelected()
    }

    private fun showSelected() {
        val frame = frames.getOrNull(_state.value.selected) ?: return
        bitmaps.get(frame.time)?.let { cached ->
            _state.update { it.copy(currentImage = cached) }
            return
        }
        showJob?.cancel()
        showJob = viewModelScope.launch {
            try {
                val bitmap = radar.image(frame)
                bitmaps.put(frame.time, bitmap)
                if (frames.getOrNull(_state.value.selected)?.time == frame.time) {
                    _state.update { it.copy(currentImage = bitmap) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage()) }
            }
        }
    }

    private companion object {
        const val KEY_API = "meteofrance_api_key"
    }
}

private fun Exception.userMessage(): String = when (this) {
    is RadarAuthException ->
        "Clé Météo-France refusée. Vérifie la clé et l'abonnement à l'API « DonneesPubliquesRadar » (bouton clé en haut)."
    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
        "Pas de connexion internet"
    is IOException -> message ?: "Erreur réseau"
    else -> "Radar indisponible : ${message ?: javaClass.simpleName}"
}
