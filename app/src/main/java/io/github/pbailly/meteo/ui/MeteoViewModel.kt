package io.github.pbailly.meteo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.pbailly.meteo.data.DeviceLocation
import io.github.pbailly.meteo.data.EnsembleForecast
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.data.MultiModelForecast
import io.github.pbailly.meteo.data.OpenMeteoException
import io.github.pbailly.meteo.data.Place
import io.github.pbailly.meteo.data.PlaceNames
import io.github.pbailly.meteo.data.PlacesStore
import io.github.pbailly.meteo.data.SettingsStore
import io.github.pbailly.meteo.data.Normals
import io.github.pbailly.meteo.data.ClimateRepository
import io.github.pbailly.meteo.data.Surroundings
import io.github.pbailly.meteo.data.SurroundingsRepository
import io.github.pbailly.meteo.data.Terrain
import io.github.pbailly.meteo.data.TerrainProfile
import io.github.pbailly.meteo.data.TerrainRepository
import io.github.pbailly.meteo.data.WeatherModel
import io.github.pbailly.meteo.data.WeatherRepository
import io.github.pbailly.meteo.widget.Widgets
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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

/** Point touché sur la carte de l'onglet Lieux ; [naming] tant qu'on cherche son nom et son altitude. */
data class MapPick(val place: Place, val naming: Boolean)

data class MeteoUiState(
    val place: Place? = null,
    val favorites: List<Place> = emptyList(),
    val forecast: Remote<Forecast> = Remote(),
    val multiModel: Remote<MultiModelForecast> = Remote(),
    /** Ensemble ECMWF (51 scénarios), pour l'indice de confiance. */
    val ensemble: Remote<EnsembleForecast> = Remote(),
    val search: SearchState = SearchState(),
    val locating: Boolean = false,
    val locationError: String? = null,
    val mapPick: MapPick? = null,
    /** Modèles masqués dans l'onglet MultiModèle (choisis dans les Réglages). */
    val hiddenModels: Set<WeatherModel> = emptySet(),
    /** Coupe du relief autour du lieu (vallée → sommets). */
    val terrain: Remote<TerrainProfile> = Remote(),
    /** Ce qui entoure le lieu (ville ou village, forêt, rivière, lac, mer, île), pour le dessin d'Aujourd'hui. */
    val surroundings: Remote<Surroundings> = Remote(),
    /** Normales de saison du lieu (1991-2020). */
    val normals: Remote<Normals> = Remote(),
    /** Prévision courte de chaque favori, par identifiant de lieu. */
    val summaries: Remote<Map<String, Forecast>> = Remote(),
    /** Lieux consultés, du plus récent au plus ancien (favoris compris). */
    val recents: List<Place> = emptyList(),
    /** Thème (0 = comme le téléphone, 1 = clair, 2 = sombre) et taille du texte (0 à 2), choisis dans les Réglages. */
    val theme: Int = 0,
    val textSize: Int = 0,
) {
    val isFavorite: Boolean get() = place != null && favorites.any { it.id == place.id }
}

class MeteoViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = WeatherRepository(File(app.cacheDir, "openmeteo"))
    private val terrainRepository = TerrainRepository(File(app.filesDir, "terrain"), repository.httpGet)
    private val surroundingsRepository = SurroundingsRepository(File(app.filesDir, "terrain"), repository.httpGet)
    private val climateRepository = ClimateRepository(File(app.filesDir, "normals"), repository.httpGet)
    private val store = PlacesStore(app)
    private val settings = SettingsStore(app)
    private val placeNames = PlaceNames(app, repository.httpGet)
    private val deviceLocation = DeviceLocation(app, placeNames)

    private val _state = MutableStateFlow(
        MeteoUiState(
            place = store.lastPlace(),
            favorites = store.favorites(),
            hiddenModels = settings.hiddenModels(),
            recents = store.recents().ifEmpty { listOfNotNull(store.lastPlace()) },
            theme = settings.theme(),
            textSize = settings.textSize(),
        ),
    )
    val state: StateFlow<MeteoUiState> = _state.asStateFlow()

    private var forecastJob: Job? = null
    private var multiModelJob: Job? = null
    private var ensembleJob: Job? = null
    private var searchJob: Job? = null
    private var terrainJob: Job? = null
    private var normalsJob: Job? = null
    private var summariesJob: Job? = null
    private var pickJob: Job? = null

    init {
        _state.value.place?.let {
            loadForecast(it, force = false)
            loadMultiModel(it, force = false)
            loadTerrain(it)
            loadNormals(it)
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
        val recents = (listOf(place) + _state.value.recents.filterNot { it.id == place.id }).take(MAX_RECENTS)
        store.saveRecents(recents)
        _state.update { it.copy(recents = recents) }
        multiModelJob?.cancel()
        _state.update {
            it.copy(place = place, forecast = Remote(), multiModel = Remote(), ensemble = Remote(), terrain = Remote(), surroundings = Remote(), normals = Remote(), search = SearchState())
        }
        loadForecast(place, force = false)
        // Les modèles servent aussi à la page principale : prévisibilité et fourchettes de pluie.
        loadMultiModel(place, force = false)
        loadTerrain(place)
        loadNormals(place)
    }

    fun refresh() {
        val place = _state.value.place ?: return
        loadForecast(place, force = true)
        loadMultiModel(place, force = true)
        if (_state.value.terrain.data == null || _state.value.surroundings.data == null) loadTerrain(place)
    }

    /** Liste des lieux : on rafraîchit la météo des favoris (le cache évite les appels inutiles). */
    fun refreshSummaries(force: Boolean = false) = loadSummaries(force)

    /** Appelé à l'ouverture de l'onglet MultiModèle : on ne télécharge ces données qu'à la demande. */
    fun ensureMultiModel() {
        val place = _state.value.place ?: return
        val multiModel = _state.value.multiModel
        if (multiModel.data == null && !multiModel.loading) {
            loadMultiModel(place, force = false)
        } else if (_state.value.ensemble.data == null && ensembleJob?.isActive != true) {
            loadEnsemble(place, force = false)
        }
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

    fun setTheme(theme: Int) {
        settings.saveTheme(theme)
        _state.update { it.copy(theme = theme) }
        viewModelScope.launch { Widgets.updateAll(getApplication()) }
    }

    fun setTextSize(size: Int) {
        settings.saveTextSize(size)
        _state.update { it.copy(textSize = size) }
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

    /**
     * Carte de l'onglet Lieux : le point touché devient un lieu, au nom de la commune (ses coordonnées s'il n'en a
     * pas) et à l'altitude du terrain. On attend un instant : toucher plusieurs fois de suite ne fait qu'une recherche.
     */
    fun pickOnMap(latitude: Double, longitude: Double) {
        pickJob?.cancel()
        val point = Place(Fmt.coordinates(latitude, longitude), latitude, longitude, admin2 = "")
        _state.update { it.copy(mapPick = MapPick(point, naming = true)) }
        pickJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val elevation = async { runLoading { repository.elevation(latitude, longitude) }.getOrNull() }
            val named = placeNames.at(latitude, longitude) ?: point
            val place = named.copy(elevation = elevation.await())
            _state.update { if (it.mapPick?.place?.id == point.id) it.copy(mapPick = MapPick(place, naming = false)) else it }
        }
    }

    fun clearMapPick() {
        pickJob?.cancel()
        _state.update { it.copy(mapPick = null) }
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
            // Les widgets relisent le cache tout juste écrit : même lieu et même prévision que l'app.
            if (result.isSuccess) Widgets.updateAll(getApplication())
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
            // Puis ce qui entoure le lieu, aussi d'OpenStreetMap : après la coupe, pour ne pas charger Overpass deux fois
            // en même temps. Sans lui, le dessin choisit son décor d'après le seul relief.
            val around = runLoading { surroundingsRepository.of(place) }
            _state.update { state ->
                if (state.place?.id != place.id) return@update state
                around.fold(
                    onSuccess = { state.copy(surroundings = Remote(it, System.currentTimeMillis())) },
                    onFailure = { state.copy(surroundings = Remote(error = (it as Exception).userMessage())) },
                )
            }
        }
    }

    /** Normales de saison : téléchargées une fois par lieu ; sans elles, Aujourd'hui n'affiche simplement pas d'écart. */
    private fun loadNormals(place: Place) {
        normalsJob?.cancel()
        normalsJob = viewModelScope.launch {
            val result = runLoading { climateRepository.normals(place, java.time.LocalDate.now()) }
            _state.update { state ->
                if (state.place?.id != place.id) return@update state
                result.fold(
                    onSuccess = { state.copy(normals = Remote(it, System.currentTimeMillis())) },
                    onFailure = { state.copy(normals = Remote(error = (it as Exception).userMessage())) },
                )
            }
        }
    }

    /** Retire un lieu de la liste des lieux consultés. */
    fun forgetRecent(place: Place) {
        val recents = _state.value.recents.filterNot { it.id == place.id }
        store.saveRecents(recents)
        _state.update { it.copy(recents = recents) }
    }

    private fun loadSummaries(force: Boolean) {
        // Favoris et lieux récents, toujours dans le même ordre : réordonner ne relance pas d'appel.
        val places = (_state.value.favorites + _state.value.recents).distinctBy { it.id }.sortedBy { it.id }
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
        loadEnsemble(place, force)
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

    /** L'ensemble se charge avec la comparaison des modèles ; sans lui, l'indice de confiance se rabat sur les modèles. */
    private fun loadEnsemble(place: Place, force: Boolean) {
        ensembleJob?.cancel()
        ensembleJob = viewModelScope.launch {
            val result = runLoading { repository.ensemble(place, force) }
            _state.update { state ->
                if (state.place?.id != place.id) return@update state
                result.fold(
                    onSuccess = { state.copy(ensemble = Remote(it.value, it.fetchedAt, stale = it.stale)) },
                    onFailure = { state.copy(ensemble = Remote(error = (it as Exception).userMessage())) },
                )
            }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
        /** Lieux consultés gardés en mémoire (favoris compris). */
        const val MAX_RECENTS = 10
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
