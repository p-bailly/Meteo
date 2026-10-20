package io.github.pbailly.meteo.ui.radar

import android.app.Application
import android.graphics.Bitmap
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.pbailly.meteo.data.radar.GeoBounds
import io.github.pbailly.meteo.data.radar.MeteoFrancePiaf
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
import io.github.pbailly.meteo.data.networkMessage
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

/** Point de départ de l'extrapolation : la dernière image et le mouvement mesuré des pluies. */
private class NowcastBase(val time: Instant, val latest: RadarPixels, val motion: MotionField)

/** D'où viennent les images prévues de la frise. */
private sealed interface ForecastSource {
    /** Prévision immédiate officielle de Météo-France (PIAF), jusqu'à +3 h. */
    class Piaf(val frames: List<RadarFrame>) : ForecastSource

    /** Repli : extrapolation calculée par l'app, jusqu'à +1 h. */
    class Extrapolated(val base: NowcastBase) : ForecastSource
}

class RadarViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("radar", Application.MODE_PRIVATE)
    private val radar = MeteoFranceRadar(File(app.cacheDir, "radar"))
    private val piaf = MeteoFrancePiaf(File(app.cacheDir, "piaf"))
    private var center: Pair<Double, Double>? = null
    private var piafFrames: List<RadarFrame> = emptyList()
    private var piafCheckedAt: Instant? = null
    private var piafNote: String? = null

    private val _state = MutableStateFlow(RadarUiState(apiKey = prefs.getString(KEY_API, null)))
    val state: StateFlow<RadarUiState> = _state.asStateFlow()

    private var observations: List<RadarFrame> = emptyList()
    private var nowcast: NowcastBase? = null
    private var forecast: ForecastSource? = null

    /** Quelques images décodées en mémoire (8 Mo chacune) ; les autres sont relues ou recalculées. */
    private val bitmaps = LruCache<String, Bitmap>(6)
    private var loadJob: Job? = null
    private var nowcastJob: Job? = null
    private var piafJob: Job? = null
    private var showJob: Job? = null
    private var playJob: Job? = null

    init {
        // Les images des 2 dernières heures déjà reçues s'affichent tout de suite, sans réseau.
        setObservations(radar.stored(), selectLatest = true)
    }

    /** Enregistre la clé ; l'écran radar relance le téléchargement s'il est ouvert. */
    fun saveKey(key: String) {
        prefs.edit().putString(KEY_API, key.trim()).apply()
        piafCheckedAt = null
        _state.update { it.copy(apiKey = key.trim().ifEmpty { null }, error = null) }
    }

    /** Lieu autour duquel on demande la prévision Météo-France. */
    fun setCenter(latitude: Double, longitude: Double) {
        if (center == latitude to longitude) return
        center = latitude to longitude
        // Nouvelle zone : l'ancienne prévision Météo-France ne vaut plus, on la redemandera.
        piafCheckedAt = null
        piafFrames = emptyList()
        chooseForecast()
        publishSlots(selectLatest = false)
    }

    /**
     * Vérifie s'il y a une nouvelle image Météo-France et la télécharge (environ 2 Mo).
     * La prévision PIAF est revue au plus toutes les [PIAF_INTERVAL], sauf si [force].
     */
    fun refresh(force: Boolean = false) {
        val key = _state.value.apiKey?.takeIf { it.isNotBlank() } ?: return
        if (force) piafCheckedAt = null
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
                refreshPiaf(key)
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
        chooseForecast()
        publishSlots(selectLatest)
        computeNowcast()
    }

    /** La prévision Météo-France si elle couvre la suite de la dernière observation, sinon l'extrapolation. */
    private fun chooseForecast() {
        val latest = observations.lastOrNull()?.time
        val official = piafFrames.filter { latest == null || it.time.isAfter(latest) }
        val base = nowcast
        forecast = when {
            official.isNotEmpty() -> ForecastSource.Piaf(official)
            base != null -> ForecastSource.Extrapolated(base)
            else -> null
        }
        val note = when (forecast) {
            is ForecastSource.Piaf -> null
            is ForecastSource.Extrapolated -> piafNote
            null -> _state.value.forecastNote
        }
        _state.update { it.copy(forecastNote = note) }
    }

    /** Télécharge la prévision immédiate PIAF de Météo-France autour du lieu (au plus toutes les 10 min). */
    private fun refreshPiaf(key: String) {
        val (lat, lon) = center ?: return
        val latest = observations.lastOrNull() ?: return
        val checked = piafCheckedAt
        if (piafJob?.isActive == true || (checked != null && Duration.between(checked, Instant.now()) < PIAF_INTERVAL)) return
        piafCheckedAt = Instant.now()
        piafJob = viewModelScope.launch {
            try {
                piafFrames = piaf.forecast(key, lat, lon, after = latest.time)
                piafNote = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                piafFrames = emptyList()
                // Une panne passagère : on réessaie dans 2 minutes, pas dans 10.
                piafCheckedAt = Instant.now().minus(PIAF_INTERVAL).plus(PIAF_RETRY)
                piafNote = if (e is RadarAuthException) {
                    "Prévision calculée par l'app (1 h). Pour celle de Météo-France (3 h) : s'abonner à " +
                        "« PrevisionImmediatePrecipitations » sur le portail, puis générer une nouvelle clé."
                } else {
                    "Prévision Météo-France indisponible (${e.message ?: e.javaClass.simpleName}) : prévision calculée par l'app (1 h)."
                }
            }
            chooseForecast()
            publishSlots(selectLatest = false)
        }
    }

    /** Met à jour la frise (observations puis prévisions) en gardant la case affichée si elle existe encore. */
    private fun publishSlots(selectLatest: Boolean) {
        val previous = currentSlot()
        val latest = observations.lastOrNull()?.time
        val forecastSlots = when (val f = forecast) {
            is ForecastSource.Piaf -> f.frames.map { frame ->
                RadarSlot(frame.time, latest?.let { Duration.between(it, frame.time).toMinutes().toInt() } ?: 0)
            }
            is ForecastSource.Extrapolated -> LEADS.map { RadarSlot(f.base.time.plusSeconds(it * 60L), it) }
            null -> emptyList()
        }
        val slots = observations.map { RadarSlot(it.time) } + forecastSlots
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
        if (latest == null) return
        // Toujours une prévision : sans deuxième image, ou sans assez de pluie pour mesurer son déplacement, la pluie
        // reste où elle est (une carte sèche quand il ne pleut nulle part), au lieu de ne rien montrer.
        nowcastJob = viewModelScope.launch {
            try {
                val newer = radar.pixels(latest)
                val motion = reference?.let {
                    val older = radar.pixels(it)
                    val minutes = Duration.between(it.time, latest.time).toSeconds() / 60.0
                    withContext(Dispatchers.Default) { Nowcast.estimateMotion(older.argb, newer.argb, newer.width, newer.height, minutes) }
                }
                if (motion == null && forecast == null) {
                    piafNote = piafNote ?: if (reference == null) {
                        "Une seule image radar pour l'instant : la pluie est montrée sans son déplacement (la suivante arrive dans moins de 5 min)."
                    } else {
                        "Peu de pluie sur la carte : son déplacement ne se mesure pas, elle est montrée là où elle est."
                    }
                }
                nowcast = NowcastBase(latest.time, newer, motion ?: MotionField.still(newer.width, newer.height))
                chooseForecast()
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
        val source = forecast
        val official = (source as? ForecastSource.Piaf)?.frames?.firstOrNull { it.time == slot.time }
        val base = (source as? ForecastSource.Extrapolated)?.base
        val key = when {
            !slot.isForecast -> "obs-${slot.time.epochSecond}"
            official != null -> "piaf-${official.file.path}"
            else -> "prevision-${base?.time?.epochSecond}-${slot.leadMinutes}"
        }
        bitmaps.get(key)?.let { cached ->
            _state.update { it.copy(currentImage = cached) }
            return
        }
        showJob?.cancel()
        showJob = viewModelScope.launch {
            try {
                val bitmap = if (official != null) {
                    radar.image(official)
                } else if (slot.isForecast) {
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

        val PIAF_INTERVAL: Duration = Duration.ofMinutes(10)
        val PIAF_RETRY: Duration = Duration.ofMinutes(2)
    }
}

private fun Exception.userMessage(): String = when (this) {
    is RadarAuthException ->
        "Clé Météo-France refusée. Vérifie la clé et l'abonnement à l'API « DonneesPubliquesRadar » (bouton clé en haut)."
    is IOException -> networkMessage()
    else -> "Radar indisponible : ${message ?: javaClass.simpleName}"
}
