package io.github.pbailly.meteo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.pbailly.meteo.ui.radar.LayersViewModel
import io.github.pbailly.meteo.ui.radar.RadarRoute
import io.github.pbailly.meteo.ui.radar.RadarViewModel

/**
 * Navigation : lieux, page de la ville, détail d'un jour, météogrammes, radar et réglages.
 * Le retour arrière ramène toujours à la page de la ville.
 */
@Composable
fun MeteoApp(viewModel: MeteoViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPlaces by rememberSaveable { mutableStateOf(false) }
    var showRadar by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showMeteograms by rememberSaveable { mutableStateOf(false) }
    var openDay by rememberSaveable { mutableIntStateOf(-1) }
    val place = state.place
    val forecast = state.forecast.data

    if (showSettings) {
        val radar: RadarViewModel = viewModel()
        val radarState by radar.state.collectAsStateWithLifecycle()
        val layers: LayersViewModel = viewModel()
        val layerState by layers.state.collectAsStateWithLifecycle()
        SettingsScreen(
            hiddenModels = state.hiddenModels,
            onModelVisible = viewModel::setModelVisible,
            meteoFranceKey = radarState.apiKey,
            onSaveKey = radar::saveKey,
            windyKey = layerState.windyKey,
            onSaveWindyKey = layers::saveWindyKey,
            onBack = { showSettings = false },
        )
    } else if (place != null && showRadar && !showPlaces) {
        RadarRoute(place, onBack = { showRadar = false })
    } else if (place != null && forecast != null && openDay >= 0 && !showPlaces) {
        DayDetailScreen(forecast, state.multiModel.data, place, initialDay = openDay, onBack = { openDay = -1 })
    } else if (place != null && showMeteograms && !showPlaces) {
        MeteogramsScreen(
            state = state,
            onBack = { showMeteograms = false },
            onMultiModelNeeded = viewModel::ensureMultiModel,
            onRetryMultiModel = viewModel::retryMultiModel,
        )
    } else if (place == null || showPlaces) {
        PlacesScreen(
            state = state,
            onBack = if (place != null) ({ showPlaces = false }) else null,
            onSelect = {
                viewModel.selectPlace(it)
                showPlaces = false
            },
            onToggleFavorite = viewModel::toggleFavorite,
            onQueryChange = viewModel::onSearchQueryChange,
            onLocate = { viewModel.locate { showPlaces = false } },
            onLocationDenied = viewModel::onLocationDenied,
        )
    } else {
        ForecastScreen(
            state = state,
            place = place,
            onOpenPlaces = { showPlaces = true },
            onOpenRadar = { showRadar = true },
            onOpenMeteograms = { showMeteograms = true },
            onOpenDay = { openDay = it },
            onOpenSettings = { showSettings = true },
            onToggleFavorite = { viewModel.toggleFavorite(place) },
            onRefresh = viewModel::refresh,
        )
    }
}
