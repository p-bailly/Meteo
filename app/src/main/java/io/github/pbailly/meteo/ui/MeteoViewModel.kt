package io.github.pbailly.meteo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.pbailly.meteo.data.DeviceLocation
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.data.MultiModelForecast
import io.github.pbailly.meteo.data.OpenMeteoException
import io.github.pbailly.meteo.data.Place
import io.github.pbailly.meteo.data.PlacesStore
import io.github.pbailly.meteo.data.SettingsStore
import io.github.pbailly.meteo.data.WeatherModel
import io.github.pbailly.meteo.data.WeatherRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Une donnée distante : la dernière valeur connue, plus l'état du chargement en cours. */
data class Remote<T>(
    val data: T? = null,
    val fetchedAt: Long = 0L,
    val loading: Boolean = false,
    val error: String? = null,
    /** Vrai quand on affiche le cache faute de réseau. */
    val stale: Boolean = false,
)

data class SearchState(
    val query: String = "",
    val results: List<Place> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class MeteoUiState(
    val place: Place? = null,
    val favorites: List<Place> = emptyList(),
    val forecast: Remote<Forecast> = Remote(),
    val multiModel: Remote<MultiModelForecast> = Remote(),
    val search: SearchState = SearchState(),
    val locating: Boolean = false,
    val locationError: String? = null,
    /** Modèles masqués dans l'onglet MultiModèle (choisis dans les Réglages). */
    val hiddenModels: Set<WeatherModel> = emptySet(),
) {
    val isFavorite: Boolean get() = place != null && favorites.any { it.id == place.id }
}

class MeteoViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = WeatherRepository(File(app.cacheDir, "openmeteo"))
    private val store = PlacesStore(app)
    private val settings = SettingsStore(app)
    private val deviceLocation = DeviceLocation(app)

    private val _state = MutableStateFlow(
        MeteoUiState(place = store.lastPlace(), favorites = store.favorites(), hiddenModels = settings.hiddenModels()),
    )
    val state: StateFlow<MeteoUiState> = _state.asStateFlow()

    private var forecastJob: Job? = null
    private var multiModelJob: Job? = null
    private var searchJob: Job? = null

    init {
        _state.value.place?.let {
            loadForecast(it, force = false)
            loadMultiModel(it, force = false)
        }
    }

    fun selectPlace(place: Place) {
        store.saveLastPlace(place)
        multiModelJob?.cancel()
        _state.update { it.copy(place = place, forecast = Remote(), multiModel = Remote(), search = SearchState()) }
        loadForecast(place, force = false)
        // Les modèles servent aussi à la page principale : prévisibilité et fourchettes de pluie.
        loadMultiModel(place, force = false)
    }

    fun refresh() {
        val place = _state.value.place ?: return
        loadForecast(place, force = true)
        loadMultiModel(place, force = true)
    }

    /** Appelé à l'ouverture de l'onglet MultiModèle : on ne télécharge ces données qu'à la demande. */
    fun ensureMultiModel() {
        val place = _state.value.place ?: return
        val multiModel = _state.value.multiModel
        if (multiModel.data == null && !multiModel.loading) loadMultiModel(place, force = false)
    }

    fun retryMultiModel() {
        _state.value.place?.let { loadMultiModel(it, force = true) }
    }

    fun toggleFavorite(place: Place) {
        val current = _state.value.favorites
        val updated = if (current.any { it.id == place.id }) current.filterNot { it.id == place.id } else current + place
        store.saveFavorites(updated)
        _state.update { it.copy(favorites = updated) }
    }

    fun setModelVisible(model: WeatherModel, visible: Boolean) {
        val hidden = if (visible) _state.value.hiddenModels - model else _state.value.hiddenModels + model
        settings.saveHiddenModels(hidden)
        _state.update { it.copy(hiddenModels = hidden) }
    }

    fun onSearchQueryChange(query: String) {
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _state.update { it.copy(search = SearchState(query = query)) }
            return
        }
        _state.update { it.copy(search = it.search.copy(query = query, loading = true, error = null)) }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            try {
                val results = repository.search(query)
                _state.update { it.copy(search = it.search.copy(results = results, loading = false)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(search = it.search.copy(loading = false, error = e.userMessage())) }
            }
        }
    }

    /** Localise le téléphone puis affiche la météo du lieu trouvé. [onFound] sert à fermer l'écran des lieux. */
    fun locate(onFound: () -> Unit) {
        if (_state.value.locating) return
        _state.update { it.copy(locating = true, locationError = null) }
        viewModelScope.launch {
            val location = try {
                deviceLocation.current()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (location == null) {
                _state.update {
                    it.copy(locating = false, locationError = "Position introuvable. Vérifie que la localisation est activée.")
                }
                return@launch
            }
            val place = deviceLocation.placeFor(location)
            _state.update { it.copy(locating = false) }
            selectPlace(place)
            onFound()
        }
    }

    fun onLocationDenied() {
        _state.update { it.copy(locationError = "Permission de localisation refusée.") }
    }

    private fun loadForecast(place: Place, force: Boolean) {
        forecastJob?.cancel()
        _state.update { it.copy(forecast = it.forecast.copy(loading = true, error = null)) }
        forecastJob = viewModelScope.launch {
            val result = runLoading { repository.forecast(place, force) }
            _state.update { state ->
                if (state.place?.id != place.id) return@update state
                result.fold(
                    onSuccess = { state.copy(forecast = Remote(it.value, it.fetchedAt, stale = it.stale)) },
                    onFailure = { state.copy(forecast = state.forecast.copy(loading = false, error = (it as Exception).userMessage())) },
                )
            }
        }
    }

    private fun loadMultiModel(place: Place, force: Boolean) {
        multiModelJob?.cancel()
        _state.update { it.copy(multiModel = it.multiModel.copy(loading = true, error = null)) }
        multiModelJob = viewModelScope.launch {
            val result = runLoading { repository.multiModel(place, force) }
            _state.update { state ->
                if (state.place?.id != place.id) return@update state
                result.fold(
                    onSuccess = { state.copy(multiModel = Remote(it.value, it.fetchedAt, stale = it.stale)) },
                    onFailure = { state.copy(multiModel = state.multiModel.copy(loading = false, error = (it as Exception).userMessage())) },
                )
            }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}

/** Comme runCatching, mais laisse passer l'annulation de la coroutine et les erreurs graves. */
private inline fun <T> runLoading(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

private fun Exception.userMessage(): String = when (this) {
    is OpenMeteoException -> message ?: "Erreur Open-Meteo"
    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
        "Pas de connexion internet"
    is IOException -> message ?: "Erreur réseau"
    else -> "Erreur : ${message ?: javaClass.simpleName}"
}
