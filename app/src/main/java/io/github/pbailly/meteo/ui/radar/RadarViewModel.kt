package io.github.pbailly.meteo.ui.radar

import android.app.Application
import android.graphics.Bitmap
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.pbailly.meteo.data.radar.GeoBounds
import io.github.pbailly.meteo.data.radar.MeteoFranceRadar
import io.github.pbailly.meteo.data.radar.MotionField
import io.github.pbailly.meteo.data.radar.Nowcast
import io.github.pbailly.meteo.data.radar.RadarAuthException
import io.github.pbailly.meteo.data.radar.RadarFrame
import io.github.pbailly.meteo.data.radar.RadarPixels
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
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/** Une case de la frise : une observation radar, ou une prévision [leadMinutes] après la dernière observation. */
data class RadarSlot(val time: Instant, val leadMinutes: Int? = null) {
    val isForecast: Boolean get() = leadMinutes != null
}

data class RadarUiState(
    val apiKey: String? = null,
    val frames: List<RadarSlot> = emptyList(),
    val selected: Int = 0,
    val currentImage: Bitmap? = null,
    val bounds: GeoBounds = MeteoFranceRadar.BOUNDS,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    /** Pourquoi la prévision n'est pas (encore) disponible, le cas échéant. */
    val forecastNote: String? = null,
) {
    val hasKey: Boolean get() = !apiKey.isNullOrBlank()
}

/** Point de départ de la prévision : la dernière image et le mouvement mesuré des pluies. */
private class NowcastBase(val time: Instant, val latest: RadarPixels, val motion: MotionField)

class RadarViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("radar", Application.MODE_PRIVATE)
    private val radar = MeteoFranceRadar(File(app.cacheDir, "radar"))

    private val _state = MutableStateFlow(RadarUiState(apiKey = prefs.getString(KEY_API, null)))
    val state: StateFlow<RadarUiState> = _state.asStateFlow()

    private var observations: List<RadarFrame> = emptyList()
    private var nowcast: NowcastBase? = null

    /** Quelques images décodées en mémoire (8 Mo chacune) ; les autres sont relues ou recalculées. */
    private val bitmaps = LruCache<String, Bitmap>(6)
    private var loadJob: Job? = null
    private var nowcastJob: Job? = null
    private var showJob: Job? = null
    private var playJob: Job? = null

    init {
        // Les images des 2 dernières heures déjà reçues s'affichent tout de suite, sans réseau.
        setObservations(radar.stored(), selectLatest = true)
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
                val list = radar.frames(key)
                val onLatest = currentSlot()?.let { !it.isForecast && it.time == observations.lastOrNull()?.time } ?: true
                if (list.map { it.time } != observations.map { it.time }) {
                    setObservations(list, selectLatest = onLatest && !_state.value.playing)
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
        val slots = _state.value.frames
        if (slots.isEmpty()) return
        _state.update { it.copy(selected = index.coerceIn(0, slots.lastIndex)) }
        showSelected()
    }

    fun togglePlay() {
        if (_state.value.playing) {
            stopPlaying()
            return
        }
        _state.update { it.copy(playing = true) }
        playJob = viewModelScope.launch {
            while (isActive && _state.value.frames.size > 1) {
                val slots = _state.value.frames
                val next = if (_state.value.selected >= slots.lastIndex) 0 else _state.value.selected + 1
                select(next)
                showJob?.join()
                delay(if (next == slots.lastIndex) 1500 else 500)
            }
            _state.update { it.copy(playing = false) }
        }
    }

    fun stopPlaying() {
        playJob?.cancel()
        _state.update { it.copy(playing = false) }
    }

    private fun currentSlot(): RadarSlot? = _state.value.frames.getOrNull(_state.value.selected)

    private fun setObservations(list: List<RadarFrame>, selectLatest: Boolean) {
        observations = list
        nowcast = null
        publishSlots(selectLatest)
        computeNowcast()
    }

    /** Met à jour la frise (observations puis prévisions) en gardant la case affichée si elle existe encore. */
    private fun publishSlots(selectLatest: Boolean) {
        val previous = currentSlot()
        val base = nowcast
        val slots = observations.map { RadarSlot(it.time) } +
            (if (base != null) LEADS.map { RadarSlot(base.time.plusSeconds(it * 60L), it) } else emptyList())
        val latestObservation = observations.lastIndex.coerceAtLeast(0)
        val selected = when {
            slots.isEmpty() -> 0
            selectLatest || previous == null -> latestObservation
            else -> slots.indexOf(previous).takeIf { it >= 0 } ?: latestObservation
        }
        _state.update { it.copy(frames = slots, selected = selected) }
        showSelected()
    }

    /** Mesure le déplacement des pluies entre deux images récentes, puis ajoute les prévisions à la frise. */
    private fun computeNowcast() {
        nowcastJob?.cancel()
        val latest = observations.lastOrNull()
        val reference = latest?.let { last ->
            observations.dropLast(1)
                .filter { Duration.between(it.time, last.time).toMinutes() in MIN_GAP..MAX_GAP }
                .minByOrNull { abs(Duration.between(it.time, last.time).toMinutes() - IDEAL_GAP) }
        }
        if (latest == null || reference == null) {
            _state.update {
                it.copy(
                    forecastNote = if (latest == null) null else
                        "Prévision : il faut 2 images radar à quelques minutes d'écart, la suivante arrive dans moins de 5 min.",
                )
            }
            return
        }
        nowcastJob = viewModelScope.launch {
            try {
                val newer = radar.pixels(latest)
                val older = radar.pixels(reference)
                val minutes = Duration.between(reference.time, latest.time).toSeconds() / 60.0
                val motion = withContext(Dispatchers.Default) {
                    Nowcast.estimateMotion(older.argb, newer.argb, newer.width, newer.height, minutes)
                }
                if (motion == null) {
                    _state.update { it.copy(forecastNote = "Pas assez de pluie sur la carte pour calculer son déplacement.") }
                    return@launch
                }
                nowcast = NowcastBase(latest.time, newer, motion)
                _state.update { it.copy(forecastNote = null) }
                publishSlots(selectLatest = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(forecastNote = "Prévision indisponible : ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    private fun showSelected() {
        val slot = currentSlot() ?: return
        val base = nowcast
        val key = if (slot.isForecast) "prevision-${base?.time?.epochSecond}-${slot.leadMinutes}" else "obs-${slot.time.epochSecond}"
        bitmaps.get(key)?.let { cached ->
            _state.update { it.copy(currentImage = cached) }
            return
        }
        showJob?.cancel()
        showJob = viewModelScope.launch {
            try {
                val bitmap = if (slot.isForecast) {
                    base ?: return@launch
                    withContext(Dispatchers.Default) {
                        val pixels = Nowcast.extrapolate(base.latest.argb, base.motion, slot.leadMinutes!!.toDouble())
                        Bitmap.createBitmap(pixels, base.latest.width, base.latest.height, Bitmap.Config.ARGB_8888)
                    }
                } else {
                    radar.image(observations.firstOrNull { it.time == slot.time } ?: return@launch)
                }
                bitmaps.put(key, bitmap)
                if (currentSlot() == slot) _state.update { it.copy(currentImage = bitmap) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage()) }
            }
        }
    }

    private companion object {
        const val KEY_API = "meteofrance_api_key"

        /** Échéances de prévision, en minutes après la dernière image. */
        val LEADS = (5..60 step 5).toList()

        /** Écart accepté entre les deux images servant à mesurer le mouvement (minutes). */
        const val MIN_GAP = 5L
        const val MAX_GAP = 30L
        const val IDEAL_GAP = 15L
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
