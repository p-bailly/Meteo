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
import io.github.pbailly.meteo.data.PressureLevels
import io.github.pbailly.meteo.data.withLevels
import io.github.pbailly.meteo.data.PlaceNames
import io.github.pbailly.meteo.data.PlacesStore
import io.github.pbailly.meteo.data.SettingsStore
import io.github.pbailly.meteo.data.AirQuality
import io.github.pbailly.meteo.data.AirQualityRepository
import io.github.pbailly.meteo.data.SpaceWeatherRepository
import io.github.pbailly.meteo.data.Surroundings
import io.github.pbailly.meteo.data.slowHttpGet
import io.github.pbailly.meteo.data.SurroundingsRepository
import io.github.pbailly.meteo.data.Terrain
import io.github.pbailly.meteo.data.TerrainProfile
import io.github.pbailly.meteo.data.TerrainRepository
import io.github.pbailly.meteo.data.WeatherModel
import io.github.pbailly.meteo.data.WeatherRepository
import io.github.pbailly.meteo.share.NimbusProvider
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
    /** Prévisions d'ensemble de cinq centres (environ 194 scénarios), pour l'indice de confiance. */
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
    /** Qualité de l'air (sable du Sahara, pollution), pour le ciel du dessin. */
    val airQuality: Remote<AirQuality> = Remote(),
    /** Activité magnétique (indice Kp), pour les aurores du dessin. */
    val kp: Double? = null,
    /** Prévision courte de chaque favori, par identifiant de lieu. */
    val summaries: Remote<Map<String, Forecast>> = Remote(),
    /** Lieux consultés, du plus récent au plus ancien (favoris compris). */
    val recents: List<Place> = emptyList(),
    /** Thème (0 = comme le téléphone, 1 = clair, 2 = sombre) et taille du texte (0 à 2), choisis dans les Réglages. */
    val theme: Int = 0,
    val textSize: Int = 0,
    /** Dessin d'Aujourd'hui : discret, normal ou coloré (0 à 2), et animé ou non. */
    val sketchStyle: Int = 1,
    val sketchMotion: Boolean = true,
) {
    val isFavorite: Boolean get() = place != null && favorites.any { it.id == place.id }
}

class MeteoViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = WeatherRepository(File(app.cacheDir, "openmeteo"))
    // Overpass répond lentement : ces deux-là attendent plus longtemps (slowHttpGet).
    private val terrainRepository = TerrainRepository(File(app.filesDir, "terrain"), repository.httpGet, ::slowHttpGet)
    private val surroundingsRepository = SurroundingsRepository(File(app.filesDir, "terrain"), repository.httpGet, ::slowHttpGet)
    private val airRepository = AirQualityRepository(File(app.cacheDir, "air"), repository.httpGet)
    private val spaceWeather = SpaceWeatherRepository(File(app.cacheDir, "air"), repository.httpGet)
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
            sketchStyle = settings.sketchStyle(),
            sketchMotion = settings.sketchMotion(),
        ),
    )
    val state: StateFlow<MeteoUiState> = _state.asStateFlow()

    private var forecastJob: Job? = null
    private var levelsJob: Job? = null
    /** Les niveaux de pression du lieu affiché, ajoutés à chaque prévision qui arrive (pour « Neige & altitude »). */
    private var levels: PressureLevels? = null
    private var multiModelJob: Job? = null
    private var ensembleJob: Job? = null
    private var searchJob: Job? = null
    private var terrainJob: Job? = null
    private var airJob: Job? = null
    private var summariesJob: Job? = null
    private var pickJob: Job? = null
    private var favoritesJob: Job? = null

    init {
        _state.value.place?.let {
            loadForecast(it, force = false)
            loadMultiModel(it, force = false)
            loadTerrain(it)
            loadAir(it)
        }
        loadSummaries(force = false)
        backfillDepartments()
        prepareFavorites()
    }

    /**
     * Le relief et les environs des favoris, préparés en arrière-plan et gardés sur le téléphone : en passant d'un
     * favori à l'autre, le dessin est tout de suite complet. Un favori à la fois, après le lieu affiché ; ce qui est
     * déjà gardé n'est pas redemandé (sauf des environs incomplets, ou une coupe sans noms de sommets).
     */
    private fun prepareFavorites() {
        favoritesJob?.cancel()
        favoritesJob = viewModelScope.launch {
            // Laisser passer d'abord le lieu affiché.
            delay(5_000)
            for (place in _state.value.favorites) {
                if (place.id == _state.value.place?.id) continue
                val around = runCatching { surroundingsRepository.cached(place) }.getOrNull()
                if (around == null) runLoading { surroundingsRepository.quick(place) }
                if (around == null || around.second) runLoading { surroundingsRepository.full(place) }
                val terrain = runCatching { terrainRepository.cached(place) }.getOrNull()
                when {
                    terrain == null -> {
                        val quick = runLoading { terrainRepository.quick(place) }.getOrNull()
                        runLoading { terrainRepository.profile(place, quick) }
                    }
                    !terrain.named -> runLoading { terrainRepository.rename(place, terrain) }
                }
            }
        }
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
        levelsJob?.cancel()
        levels = null
        _state.update {
            it.copy(place = place, forecast = Remote(), multiModel = Remote(), ensemble = Remote(), terrain = Remote(), surroundings = Remote(), airQuality = Remote(), search = SearchState())
        }
        loadForecast(place, force = false)
        // Les modèles servent aussi à la page principale : prévisibilité et fourchettes de pluie.
        loadMultiModel(place, force = false)
        loadTerrain(place)
        loadAir(place)
    }

    fun refresh() {
        val place = _state.value.place ?: return
        loadForecast(place, force = true)
        loadMultiModel(place, force = true)
        if (_state.value.terrain.data == null || _state.value.surroundings.data == null) loadTerrain(place)
        loadAir(place)
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
        prepareFavorites()
    }

    fun setTheme(theme: Int) {
        settings.saveTheme(theme)
        _state.update { it.copy(theme = theme) }
        viewModelScope.launch { Widgets.updateAll(getApplication()) }
    }

    fun setSketchStyle(style: Int) {
        settings.saveSketchStyle(style)
        _state.update { it.copy(sketchStyle = style) }
        // Nimbus montre le dessin dans ce style : il le redemande.
        NimbusProvider.notifyChange(getApplication())
    }

    fun setSketchMotion(motion: Boolean) {
        settings.saveSketchMotion(motion)
        _state.update { it.copy(sketchMotion = motion) }
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
            // Open-Meteo met parfois longtemps à répondre : la dernière prévision gardée s'affiche en attendant.
            if (_state.value.forecast.data == null) {
                runLoading { repository.lastForecast(place) }.getOrNull()?.let { last ->
                    _state.update {
                        if (it.place?.id != place.id || it.forecast.data != null) it
                        else it.copy(forecast = it.forecast.copy(data = last.value.withLevels(levels), fetchedAt = last.fetchedAt, stale = true))
                    }
                }
            }
            val result = runLoading { repository.forecast(place, force) }
            _state.update { state ->
                if (state.place?.id != place.id) return@update state
                result.fold(
                    onSuccess = { state.copy(forecast = Remote(it.value.withLevels(levels), it.fetchedAt, stale = it.stale)) },
                    onFailure = { state.copy(forecast = state.forecast.copy(loading = false, error = (it as Exception).userMessage())) },
                )
            }
            if (result.isSuccess) loadLevels(place, force)
            // Les widgets relisent le cache tout juste écrit : même lieu et même prévision que l'app.
            if (result.isSuccess) Widgets.updateAll(getApplication())
        }
    }

    /**
     * Les niveaux de pression, une fois la prévision affichée (Open-Meteo les calcule lentement) ; sans eux, « Neige &
     * altitude » se contente de l'iso 0 °C.
     */
    private fun loadLevels(place: Place, force: Boolean) {
        levelsJob?.cancel()
        levelsJob = viewModelScope.launch {
            val loaded = runLoading { repository.levels(place, force) }.getOrNull() ?: return@launch
            if (_state.value.place?.id != place.id) return@launch
            levels = loaded.value
            _state.update { state ->
                val f = state.forecast.data ?: return@update state
                state.copy(forecast = state.forecast.copy(data = f.withLevels(loaded.value)))
            }
        }
    }

    /**
     * Le relief et les environs, pour le dessin. Ils ne changent pas : gardés sur le téléphone. La première fois, une
     * coupe rapide et la taille de la ville (Open-Meteo, une seconde) dessinent tout de suite ; OpenStreetMap, lent,
     * complète ensuite (sommets nommés, forêt, rivière, ski…). Les deux demandes à OpenStreetMap partent en même temps.
     */
    private fun loadTerrain(place: Place) {
        terrainJob?.cancel()
        _state.update { it.copy(terrain = it.terrain.copy(loading = true, error = null)) }
        fun setTerrain(result: Result<TerrainProfile>) = _state.update { state ->
            if (state.place?.id != place.id) return@update state
            result.fold(
                onSuccess = { state.copy(terrain = Remote(it, System.currentTimeMillis())) },
                onFailure = { if (state.terrain.data != null) state else state.copy(terrain = Remote(error = (it as Exception).userMessage())) },
            )
        }
        fun setSurroundings(result: Result<Surroundings>) = _state.update { state ->
            if (state.place?.id != place.id) return@update state
            result.fold(
                onSuccess = { state.copy(surroundings = Remote(it, System.currentTimeMillis())) },
                onFailure = { if (state.surroundings.data != null) state else state.copy(surroundings = Remote(error = (it as Exception).userMessage())) },
            )
        }
        terrainJob = viewModelScope.launch {
            launch {
                val cached = runCatching { surroundingsRepository.cached(place) }.getOrNull()
                if (cached != null) setSurroundings(Result.success(cached.first))
                if (cached != null && !cached.second) return@launch
                if (cached == null) setSurroundings(runLoading { surroundingsRepository.quick(place) })
                setSurroundings(runLoading { surroundingsRepository.full(place) })
            }
            val cached = runCatching { terrainRepository.cached(place) }.getOrNull()
            if (cached != null) {
                setTerrain(Result.success(cached))
                // Une coupe gardée sans noms de sommets (le réseau manquait) : on réessaie de les trouver.
                if (!cached.named) runLoading { terrainRepository.rename(place, cached) }.getOrNull()?.takeIf { it.named }?.let { setTerrain(Result.success(it)) }
                return@launch
            }
            val quick = runLoading { terrainRepository.quick(place) }
            setTerrain(quick)
            setTerrain(runLoading { terrainRepository.profile(place, quick.getOrNull()) })
        }
    }

    /** Qualité de l'air : gardée une heure ; sans elle, le ciel du dessin reste simplement clair. */
    private fun loadAir(place: Place) {
        airJob?.cancel()
        airJob = viewModelScope.launch {
            // L'indice des aurores vaut pour toute la Terre ; sans lui, simplement pas d'aurore.
            launch { runLoading { spaceWeather.kp() }.getOrNull()?.let { kp -> _state.update { it.copy(kp = kp) } } }
            val result = runLoading { airRepository.of(place) }
            _state.update { state ->
                if (state.place?.id != place.id) return@update state
                result.fold(
                    onSuccess = { state.copy(airQuality = Remote(it, System.currentTimeMillis())) },
                    onFailure = { state.copy(airQuality = Remote(error = (it as Exception).userMessage())) },
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
            if (_state.value.multiModel.data == null) {
                runLoading { repository.lastMultiModel(place) }.getOrNull()?.let { last ->
                    _state.update {
                        if (it.place?.id != place.id || it.multiModel.data != null) it
                        else it.copy(multiModel = it.multiModel.copy(data = last.value, fetchedAt = last.fetchedAt, stale = true))
                    }
                }
            }
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
