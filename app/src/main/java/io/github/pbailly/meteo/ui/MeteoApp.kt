package io.github.pbailly.meteo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.pbailly.meteo.ui.radar.LayersViewModel
import io.github.pbailly.meteo.ui.radar.RadarRoute
import io.github.pbailly.meteo.ui.radar.RadarViewModel
import kotlinx.coroutines.launch

/**
 * Navigation : cinq onglets (Aujourd'hui, Prévisions, Météogramme, Radar, Lieux), dans la barre du bas ou en
 * glissant d'une page à l'autre (sauf sur la carte, où le glissement déplace la carte). Le détail d'un jour, la page
 * « Neige & altitude », la comparaison des lieux et les réglages s'ouvrent par-dessus, avec un bouton retour ; le
 * retour arrière ramène ensuite à l'onglet Aujourd'hui.
 */
@Composable
fun MeteoApp(viewModel: MeteoViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pager = rememberPagerState(initialPage = Tab.TODAY.ordinal) { Tab.entries.size }
    val scope = rememberCoroutineScope()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAltitude by rememberSaveable { mutableStateOf(false) }
    var showCompare by rememberSaveable { mutableStateOf(false) }
    var openDay by rememberSaveable { mutableIntStateOf(-1) }
    val place = state.place
    val forecast = state.forecast.data

    // Premier lancement : pas encore de lieu, on commence par la liste des lieux.
    val current = if (place == null) Tab.PLACES else Tab.entries[pager.currentPage]
    fun goTo(tab: Tab) {
        showSettings = false
        showAltitude = false
        showCompare = false
        openDay = -1
        // Sans animation : on ne traverse pas les pages intermédiaires (la carte est lourde à afficher).
        scope.launch { pager.scrollToPage(tab.ordinal) }
    }
    BackHandler(enabled = place != null && current != Tab.TODAY) { goTo(Tab.TODAY) }
    LaunchedEffect(current) { if (current == Tab.PLACES) viewModel.refreshSummaries() }

    Scaffold(
        bottomBar = { if (place != null) BottomBar(current, ::goTo) },
    ) { padding ->
        // Tout le contenu sous la barre d'état (heure, caméra, batterie) et au-dessus de la barre du bas.
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            val placesScreen = @Composable {
                PlacesScreen(
                    state = state,
                    onSelect = {
                        viewModel.selectPlace(it)
                        goTo(Tab.TODAY)
                    },
                    onToggleFavorite = viewModel::toggleFavorite,
                    onQueryChange = viewModel::onSearchQueryChange,
                    onLocate = { viewModel.locate { goTo(Tab.TODAY) } },
                    onLocationDenied = viewModel::onLocationDenied,
                    onOpenSettings = { showSettings = true },
                    onMoveFavorite = viewModel::moveFavorite,
                    onOpenCompare = { showCompare = true },
                )
            }
            when {
                showSettings -> {
                    val radar: RadarViewModel = viewModel()
                    val radarState by radar.state.collectAsStateWithLifecycle()
                    val layers: LayersViewModel = viewModel()
                    val layerState by layers.state.collectAsStateWithLifecycle()
                    SettingsScreen(
                        theme = state.theme,
                        onTheme = viewModel::setTheme,
                        textSize = state.textSize,
                        onTextSize = viewModel::setTextSize,
                        hiddenModels = state.hiddenModels,
                        onModelVisible = viewModel::setModelVisible,
                        meteoFranceKey = radarState.apiKey,
                        onSaveKey = radar::saveKey,
                        windyKey = layerState.windyKey,
                        onSaveWindyKey = layers::saveWindyKey,
                        onBack = { showSettings = false },
                    )
                }
                place == null -> placesScreen()
                showCompare -> CompareScreen(
                    state = state,
                    onSelect = {
                        viewModel.selectPlace(it)
                        goTo(Tab.TODAY)
                    },
                    onRefresh = { viewModel.refreshSummaries() },
                    onBack = { showCompare = false },
                )
                forecast != null && openDay >= 0 ->
                    DayDetailScreen(forecast, state.multiModel.data, state.ensemble.data, place, initialDay = openDay, onBack = { openDay = -1 })
                showAltitude -> AltitudeScreen(state = state, place = place, onRefresh = viewModel::refresh, onBack = { showAltitude = false })
                else -> HorizontalPager(
                    state = pager,
                    // Sur la carte, le glissement sert à la déplacer : on change d'onglet par la barre du bas.
                    userScrollEnabled = current != Tab.RADAR,
                    key = { it },
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (Tab.entries[page]) {
                        Tab.TODAY -> TodayScreen(
                            state = state,
                            place = place,
                            onToggleFavorite = { viewModel.toggleFavorite(place) },
                            onOpenPlaces = { goTo(Tab.PLACES) },
                            onOpenAltitude = { showAltitude = true },
                            onRefresh = viewModel::refresh,
                        )
                        Tab.FORECAST -> DaysScreen(
                            state = state,
                            place = place,
                            onOpenDay = { openDay = it },
                            onRefresh = viewModel::refresh,
                            onMultiModelNeeded = viewModel::ensureMultiModel,
                        )
                        Tab.METEOGRAM -> MeteogramsScreen(
                            state = state,
                            onBack = null,
                            onMultiModelNeeded = viewModel::ensureMultiModel,
                            onRetryMultiModel = viewModel::retryMultiModel,
                        )
                        Tab.RADAR -> RadarRoute(place, onBack = { goTo(Tab.TODAY) }, showBack = false)
                        Tab.PLACES -> placesScreen()
                    }
                }
            }
        }
    }
}
