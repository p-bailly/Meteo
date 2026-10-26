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
import io.github.pbailly.meteo.ui.maps.MapsScreen
import io.github.pbailly.meteo.ui.maps.MapsViewModel
import kotlinx.coroutines.launch

/** Les onglets de la webapp, ceux de l'appli. */
private val WebTabs = listOf(Tab.TODAY, Tab.FORECAST, Tab.METEOGRAM, Tab.RADAR, Tab.PLACES)

/**
 * Navigation de la webapp, comme MeteoApp sur le téléphone : cinq onglets (Aujourd'hui, Prévisions, Courbes, Cartes,
 * Lieux), dans la barre du bas ou en glissant (sauf sur la carte, où le glissement la déplace) ; le détail d'un jour, « Neige & altitude », la comparaison des lieux et les
 * réglages s'ouvrent par-dessus.
 */
@Composable
fun WebApp(viewModel: MeteoViewModel, maps: MapsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pager = rememberPagerState(initialPage = 0) { WebTabs.size }
    val scope = rememberCoroutineScope()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAltitude by rememberSaveable { mutableStateOf(false) }
    var showCompare by rememberSaveable { mutableStateOf(false) }
    var openDay by rememberSaveable { mutableIntStateOf(-1) }
    val place = state.place
    val forecast = state.forecast.data

    // Premier lancement : pas encore de lieu, on commence par la liste des lieux.
    val current = if (place == null) Tab.PLACES else WebTabs[pager.currentPage]
    fun goTo(tab: Tab) {
        showSettings = false
        showAltitude = false
        showCompare = false
        openDay = -1
        scope.launch { pager.scrollToPage(WebTabs.indexOf(tab)) }
    }
    BackHandler(enabled = place != null && current != Tab.TODAY) { goTo(Tab.TODAY) }
    LaunchedEffect(current) { if (current == Tab.PLACES) viewModel.refreshSummaries() }

    Scaffold(
        bottomBar = { if (place != null) BottomBar(current, ::goTo, WebTabs) },
    ) { padding ->
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
                    onOpenMap = null,
                    onOpenSettings = { showSettings = true },
                    onMoveFavorite = viewModel::moveFavorite,
                    onOpenCompare = { showCompare = true },
                )
            }
            when {
                showSettings -> WebSettingsScreen(
                    theme = state.theme,
                    onTheme = viewModel::setTheme,
                    textSize = state.textSize,
                    onTextSize = viewModel::setTextSize,
                    sketchStyle = state.sketchStyle,
                    onSketchStyle = viewModel::setSketchStyle,
                    sketchMotion = state.sketchMotion,
                    onSketchMotion = viewModel::setSketchMotion,
                    hiddenModels = state.hiddenModels,
                    onModelVisible = viewModel::setModelVisible,
                    onBack = { showSettings = false },
                )
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
                    userScrollEnabled = pager.settledPage != WebTabs.indexOf(Tab.RADAR),
                    key = { it },
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (WebTabs[page]) {
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
                        Tab.RADAR -> MapsScreen(place, maps)
                        else -> placesScreen()
                    }
                }
            }
        }
    }
}
