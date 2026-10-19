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
import io.github.pbailly.meteo.data.Terrain
import io.github.pbailly.meteo.data.TerrainProfile
import io.github.pbailly.meteo.data.TerrainRepository
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
import io.github.pbailly.meteo.data.networkMessage
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
    /** Coupe du relief autour du lieu (vallée → sommets). */
    val terrain: Remote<TerrainProfile> = Remote(),
    /** Prévision courte de chaque favori, par identifiant de lieu. */
    val summaries: Remote<Map<String, Forecast>> = Remote(),
) {
    val isFavorite: Boolean get() = place != null && favorites.any { it.id == place.id }
}

class MeteoViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = WeatherRepository(File(app.cacheDir, "openmeteo"))
    private val terrainRepository = TerrainRepository(File(app.filesDir, "terrain"), repository.httpGet)
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
    private var terrainJob: Job? = null
    private var summariesJob: Job? = null

    init {
        _state.value.place?.let {
            loadForecast(it, force = false)
            loadMultiModel(it, force = false)
            loadTerrain(it)
        }
        loadSummaries(force = false)
        backfillDepartments()
    }

    /** Les lieux enregistrés avant l'ajout du département le retrouvent par une recherche, une seule fois. */
    private fun backfillDepartments() {
        val missing = (_state.value.favorites + listOfNotNull(_state.value.place)).filter { it.admin2 == null }.distinctBy { it.id }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            val found = missing.associate { place ->
                val match = runCatching { repository.search(place.name) }.getOrNull()
                    ?.filter { Terrain.distanceKm(it.latitude, it.longitude, place.latitude, place.longitude) < 3 }
                    ?.minByOrNull { Terrain.distanceKm(it.latitude, it.longitude, place.latitude, place.longitude) }
                // Vide : cherché sans succès, on ne recommencera pas.
                place.id to (match?.admin2 ?: "")
            }
            fun Place.filled() = if (admin2 == null) copy(admin2 = found[id] ?: admin2) else this
            val favorites = _state.value.favorites.map { it.filled() }
            store.saveFavorites(favorites)
            val current = _state.value.place?.filled()
            current?.let { store.saveLastPlace(it) }
            _state.update { it.copy(favorites = favorites, place = current ?: it.place) }
        }
    }

    /** Glisser-déposer dans la liste des lieux : le favori [from] prend la place [to]. */
    fun moveFavorite(from: Int, to: Int) {
        val list = _state.value.favorites.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        list.add(to, list.removeAt(from))
        store.saveFavorites(list)
        _state.update { it.copy(favorites = list) }
    }

    fun selectPlace(place: Place) {
        store.saveLastPlace(place)
        multiModelJob?.cancel()
        _state.update { it.copy(place = place, forecast = Remote(), multiModel = Remote(), terrain = Remote(), search = SearchState()) }
        loadForecast(place, force = false)
        // Les modèles servent aussi à la page principale : prévisibilité et fourchettes de pluie.
        loadMultiModel(place, force = false)
        loadTerrain(place)
    }

    fun refresh() {
        val place = _state.value.place ?: return
        loadForecast(place, force = true)
        loadMultiModel(place, force = true)
        if (_state.value.terrain.data == null) loadTerrain(place)
    }

    /** Liste des lieux : on rafraîchit la météo des favoris (le cache évite les appels inutiles). */
    fun refreshSummaries(force: Boolean = false) = loadSummaries(force)

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
        loadSummaries(force = false)
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

    /** Le relief ne change pas : la coupe est gardée sur le téléphone après le premier téléchargement. */
    private fun loadTerrain(place: Place) {
        terrainJob?.cancel()
        _state.update { it.copy(terrain = it.terrain.copy(loading = true, error = null)) }
        terrainJob = viewModelScope.launch {
            val result = runLoading { terrainRepository.profile(place) }
            _state.update { state ->
                if (state.place?.id != place.id) return@update state
                result.fold(
                    onSuccess = { state.copy(terrain = Remote(it, System.currentTimeMillis())) },
                    onFailure = { state.copy(terrain = Remote(error = (it as Exception).userMessage())) },
                )
            }
        }
    }

    private fun loadSummaries(force: Boolean) {
        // Toujours dans le même ordre : réordonner les favoris ne relance pas d'appel.
        val places = _state.value.favorites.sortedBy { it.id }
        summariesJob?.cancel()
        if (places.isEmpty()) {
            _state.update { it.copy(summaries = Remote()) }
            return
        }
        _state.update { it.copy(summaries = it.summaries.copy(loading = true, error = null)) }
        summariesJob = viewModelScope.launch {
            val result = runLoading { repository.summaries(places, force) }
            _state.update { state ->
                result.fold(
                    onSuccess = { loaded ->
                        val byId = places.map { it.id }.zip(loaded.value).toMap()
                        state.copy(summaries = Remote(byId, loaded.fetchedAt, stale = loaded.stale))
                    },
                    onFailure = { state.copy(summaries = state.summaries.copy(loading = false, error = (it as Exception).userMessage())) },
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
    is IOException -> networkMessage()
    else -> "Erreur : ${message ?: javaClass.simpleName}"
}
