package io.github.pbailly.meteo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.pbailly.meteo.ui.radar.RadarRoute
import io.github.pbailly.meteo.ui.radar.RadarViewModel

/** Deux écrans : la liste des lieux et les prévisions du lieu choisi. */
@Composable
fun MeteoApp(viewModel: MeteoViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPlaces by rememberSaveable { mutableStateOf(false) }
    var showRadar by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val place = state.place

    if (showSettings) {
        val radar: RadarViewModel = viewModel()
        val radarState by radar.state.collectAsStateWithLifecycle()
        SettingsScreen(
            hiddenModels = state.hiddenModels,
            onModelVisible = viewModel::setModelVisible,
            meteoFranceKey = radarState.apiKey,
            onSaveKey = radar::saveKey,
            onBack = { showSettings = false },
        )
    } else if (place != null && showRadar && !showPlaces) {
        RadarRoute(place, onBack = { showRadar = false })
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
            onOpenSettings = { showSettings = true },
            onToggleFavorite = { viewModel.toggleFavorite(place) },
            onRefresh = viewModel::refresh,
            onMultiModelNeeded = viewModel::ensureMultiModel,
            onRetryMultiModel = viewModel::retryMultiModel,
        )
    }
}
