package io.github.pbailly.meteo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.pbailly.meteo.ui.radar.LayersViewModel
import io.github.pbailly.meteo.ui.radar.RadarRoute
import io.github.pbailly.meteo.ui.radar.RadarViewModel

/**
 * Navigation : cinq onglets dans la barre du bas (Aujourd'hui, Prévisions, Altitude, Radar, Lieux).
 * Le détail d'un jour et les météogrammes s'ouvrent dans l'onglet Prévisions, les réglages dans Lieux, avec un
 * bouton retour. Le retour arrière ramène ensuite à l'onglet Aujourd'hui.
 */
@Composable
fun MeteoApp(viewModel: MeteoViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showMeteograms by rememberSaveable { mutableStateOf(false) }
    var openDay by rememberSaveable { mutableIntStateOf(-1) }
    val place = state.place
    val forecast = state.forecast.data

    // Premier lancement : pas encore de lieu, on commence par la liste des lieux.
    val current = if (place == null) Tab.PLACES else tab
    BackHandler(enabled = place != null && tab != Tab.TODAY) { tab = Tab.TODAY }
    LaunchedEffect(current) { if (current == Tab.PLACES) viewModel.refreshSummaries() }

    Scaffold(
        bottomBar = {
            if (place != null) {
                BottomBar(current) {
                    // Toucher un onglet referme le détail, les météogrammes ou les réglages.
                    showSettings = false
                    showMeteograms = false
                    openDay = -1
                    tab = it
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()).consumeWindowInsets(padding)) {
            // Écrans ouverts depuis un onglet (la barre du bas reste visible).
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
                return@Box
            }
            if (place != null && forecast != null && openDay >= 0) {
                DayDetailScreen(forecast, state.multiModel.data, place, initialDay = openDay, onBack = { openDay = -1 })
                return@Box
            }
            if (place != null && showMeteograms) {
                MeteogramsScreen(
                    state = state,
                    onBack = { showMeteograms = false },
                    onMultiModelNeeded = viewModel::ensureMultiModel,
                    onRetryMultiModel = viewModel::retryMultiModel,
                )
                return@Box
            }
            when (current) {
                Tab.PLACES -> PlacesScreen(
                    state = state,
                    onSelect = {
                        viewModel.selectPlace(it)
                        tab = Tab.TODAY
                    },
                    onToggleFavorite = viewModel::toggleFavorite,
                    onQueryChange = viewModel::onSearchQueryChange,
                    onLocate = { viewModel.locate { tab = Tab.TODAY } },
                    onLocationDenied = viewModel::onLocationDenied,
                    onOpenSettings = { showSettings = true },
                )
                Tab.TODAY -> TodayScreen(
                    state = state,
                    place = place!!,
                    onToggleFavorite = { viewModel.toggleFavorite(place) },
                    onOpenPlaces = { tab = Tab.PLACES },
                    onRefresh = viewModel::refresh,
                )
                Tab.FORECAST -> DaysScreen(
                    state = state,
                    place = place!!,
                    onOpenDay = { openDay = it },
                    onOpenMeteograms = { showMeteograms = true },
                    onRefresh = viewModel::refresh,
                )
                Tab.ALTITUDE -> AltitudeScreen(state = state, place = place!!, onRefresh = viewModel::refresh)
                Tab.RADAR -> RadarRoute(place!!, onBack = { tab = Tab.TODAY }, showBack = false)
            }
        }
    }
}
