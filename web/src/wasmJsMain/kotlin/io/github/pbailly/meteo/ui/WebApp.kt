package io.github.pbailly.meteo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import io.github.pbailly.meteo.ui.radar.LayersViewModel
import io.github.pbailly.meteo.ui.radar.MapLayer
import io.github.pbailly.meteo.ui.radar.RadarRoute
import io.github.pbailly.meteo.ui.radar.RadarViewModel
import kotlinx.coroutines.launch

/** Les couches des Cartes : celles de l'appli sans la température (le serveur du DWD refuse les navigateurs). */
private val WebLayers = MapLayer.entries - MapLayer.TEMPERATURE

/** Les onglets de la webapp, ceux de l'appli. */
private val WebTabs = listOf(Tab.TODAY, Tab.FORECAST, Tab.METEOGRAM, Tab.RADAR, Tab.PLACES)

/** À partir de cette largeur (ordinateur, tablette en paysage), les onglets passent dans une colonne à gauche. */
private val WIDE = 840.dp

/**
 * Largeur au plus de chaque écran sur un grand écran, centré : les listes restent lisibles, les courbes et la carte
 * prennent toute la place.
 */
private fun maxWidthOf(tab: Tab): Dp = when (tab) {
    Tab.TODAY -> 1000.dp
    Tab.METEOGRAM, Tab.RADAR -> Dp.Unspecified
    else -> 760.dp
}

/** Les écrans ouverts par-dessus (réglages, détail d'un jour, altitude, comparaison). */
private val DETAIL_MAX = 900.dp

/** [content] centré, au plus [maxWidth] de large (sans limite si Dp.Unspecified). */
@Composable
private fun Centered(maxWidth: Dp, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(if (maxWidth == Dp.Unspecified) Modifier.fillMaxSize() else Modifier.fillMaxHeight().widthIn(max = maxWidth).fillMaxWidth()) {
            content()
        }
    }
}

/**
 * Les onglets en colonne, à gauche d'un grand écran : pictogramme et libellé, l'onglet actif en bleu glacier avec un
 * trait à gauche (comme le trait au-dessus de la barre du bas).
 */
@Composable
private fun SideRail(selected: Tab, onSelect: (Tab) -> Unit, tabs: List<Tab>) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxHeight().background(colors.surfaceContainerLowest)) {
        Column(Modifier.width(92.dp).fillMaxHeight().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tabs.forEach { tab ->
                val active = tab == selected
                val tint = if (active) colors.primary else colors.onSurfaceVariant
                Row(Modifier.fillMaxWidth().height(68.dp).clickable { onSelect(tab) }, verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(30.dp)
                            .background(if (active) colors.primary else Color.Transparent, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)),
                    )
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                        Text(
                            tab.label,
                            color = tint,
                            fontSize = 11.5.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
        }
        VerticalDivider(color = colors.outlineVariant)
    }
}

/**
 * Navigation de la webapp, comme MeteoApp sur le téléphone : cinq onglets (Aujourd'hui, Prévisions, Courbes, Cartes,
 * Lieux), dans la barre du bas ou en glissant (sauf sur la carte, où le glissement la déplace) ; le détail d'un jour,
 * « Neige & altitude », la comparaison des lieux et les réglages s'ouvrent par-dessus. Sur un grand écran, les onglets
 * sont dans une colonne à gauche et chaque écran garde une largeur lisible.
 */
@Composable
fun WebApp(viewModel: MeteoViewModel, radar: RadarViewModel, layers: LayersViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pager = rememberPagerState(initialPage = 0) { WebTabs.size }
    val scope = rememberCoroutineScope()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAltitude by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showCompare by rememberSaveable { mutableStateOf(false) }
    var openDay by rememberSaveable { mutableIntStateOf(-1) }
    val place = state.place
    val forecast = state.forecast.data

    // Premier lancement : pas encore de lieu, on commence par la liste des lieux.
    val current = if (place == null) Tab.PLACES else WebTabs[pager.currentPage]
    fun goTo(tab: Tab) {
        showSettings = false
        showAltitude = false
        showHistory = false
        showCompare = false
        openDay = -1
        scope.launch { pager.scrollToPage(WebTabs.indexOf(tab)) }
    }
    BackHandler(enabled = place != null && current != Tab.TODAY) { goTo(Tab.TODAY) }
    LaunchedEffect(current) { if (current == Tab.PLACES) viewModel.refreshSummaries() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = maxWidth >= WIDE
    Scaffold(
        bottomBar = { if (place != null && !wide) BottomBar(current, ::goTo, WebTabs) },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
        if (place != null && wide) SideRail(current, ::goTo, WebTabs)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val placesScreen = @Composable {
                Centered(maxWidthOf(Tab.PLACES)) { PlacesScreen(
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
                ) }
            }
            when {
                showSettings -> Centered(DETAIL_MAX) { WebSettingsScreen(
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
                ) }
                place == null -> placesScreen()
                showCompare -> Centered(DETAIL_MAX) { CompareScreen(
                    state = state,
                    onSelect = {
                        viewModel.selectPlace(it)
                        goTo(Tab.TODAY)
                    },
                    onRefresh = { viewModel.refreshSummaries() },
                    onBack = { showCompare = false },
                ) }
                forecast != null && openDay >= 0 -> Centered(DETAIL_MAX) {
                    DayDetailScreen(forecast, state.multiModel.data, state.ensemble.data, place, initialDay = openDay, onBack = { openDay = -1 })
                }
                showAltitude -> Centered(DETAIL_MAX) {
                    AltitudeScreen(state = state, place = place, onRefresh = viewModel::refresh, onBack = { showAltitude = false })
                }
                showHistory -> Centered(DETAIL_MAX) {
                    HistoryScreen(
                        state = state,
                        place = place,
                        onHistoryNeeded = { viewModel.ensureHistory(always = true) },
                        onRetry = viewModel::retryHistory,
                        onBack = { showHistory = false },
                    )
                }
                else -> HorizontalPager(
                    state = pager,
                    // Sur la carte, le glissement sert à la déplacer : on change d'onglet par la barre du bas.
                    userScrollEnabled = pager.settledPage != WebTabs.indexOf(Tab.RADAR),
                    key = { it },
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    Centered(maxWidthOf(WebTabs[page])) {
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
                            onHistoryNeeded = { viewModel.ensureHistory() },
                            onOpenHistory = { showHistory = true },
                        )
                        Tab.METEOGRAM -> MeteogramsScreen(
                            state = state,
                            onBack = null,
                            onMultiModelNeeded = viewModel::ensureMultiModel,
                            onRetryMultiModel = viewModel::retryMultiModel,
                        )
                        Tab.RADAR -> RadarRoute(
                            place,
                            onBack = { goTo(Tab.TODAY) },
                            showBack = false,
                            viewModel = radar,
                            layers = layers,
                            layerChoices = WebLayers,
                            controlsOverMap = false,
                        )
                        else -> placesScreen()
                    }
                    }
                }
            }
        }
        }
    }
    }
}
