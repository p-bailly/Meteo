package io.github.pbailly.meteo.ui.radar

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.pbailly.meteo.data.Place
import io.github.pbailly.meteo.data.maps.DwdTemperature
import io.github.pbailly.meteo.data.maps.StationObservation
import io.github.pbailly.meteo.data.maps.TileLayer
import io.github.pbailly.meteo.data.maps.WindyWebcams
import io.github.pbailly.meteo.data.radar.RainClass
import io.github.pbailly.meteo.ui.AppTopBar
import io.github.pbailly.meteo.ui.theme.HeaderBlue
import io.github.pbailly.meteo.ui.theme.OnTemperature
import io.github.pbailly.meteo.ui.theme.AppMono
import io.github.pbailly.meteo.ui.theme.RainChanceOnNavy
import io.github.pbailly.meteo.ui.theme.SecondaryOnNavy
import io.github.pbailly.meteo.ui.theme.SelectedOnCard
import io.github.pbailly.meteo.ui.theme.temperatureColor
import kotlinx.coroutines.delay
import org.maplibre.android.geometry.LatLng
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Fond de la frise des heures, un ton sous le bleu glacier des cartes. */
private val TIMELINE_BLUE = Color(0xFF15527F)
private const val REFRESH_MS = 2 * 60 * 1000L

/**
 * Écran des cartes relié à ses ViewModels : pluie (radar Météo-France, vérifié toutes les 2 minutes
 * tant qu'il est affiché), température, satellite, foudre et webcams.
 */
@Composable
fun RadarRoute(
    place: Place,
    onBack: () -> Unit,
    /** Faux dans la barre du bas : l'écran est un onglet, sans flèche de retour. */
    showBack: Boolean = true,
    viewModel: RadarViewModel = viewModel(),
    layers: LayersViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val layerState by layers.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(place.id) {
        viewModel.setCenter(place.latitude, place.longitude)
        layers.setCenter(place.latitude, place.longitude)
    }
    LaunchedEffect(lifecycle, state.hasKey, layerState.layer) {
        if (!state.hasKey || layerState.layer != MapLayer.RAIN) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                viewModel.refresh()
                delay(REFRESH_MS)
            }
        }
    }
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.stopPlaying()
            layers.stopPlaying()
        }
    }
    BackHandler(onBack = onBack)
    RadarScreen(
        place = place,
        state = state,
        layerState = layerState,
        onBack = onBack.takeIf { showBack },
        onRefresh = {
            if (layerState.layer == MapLayer.RAIN) viewModel.refresh(force = true) else layers.show(layerState.layer)
        },
        onLayer = { layer ->
            viewModel.stopPlaying()
            layers.show(layer)
        },
        onSelect = { if (layerState.layer == MapLayer.RAIN) viewModel.select(it) else layers.select(it) },
        onTogglePlay = { if (layerState.layer == MapLayer.RAIN) viewModel.togglePlay() else layers.togglePlay() },
        onSaveKey = {
            viewModel.saveKey(it)
            viewModel.refresh(force = true)
        },
        onSaveWindyKey = layers::saveWindyKey,
        onWebcamClick = layers::openWebcam,
        onCloseWebcam = layers::closeWebcam,
        onStationClick = layers::openStation,
        onCloseStation = layers::closeStation,
    )
}
private val SELECTED_BLUE = SelectedOnCard
private val clock = DateTimeFormatter.ofPattern("HH:mm")
private val dayHour = DateTimeFormatter.ofPattern("EEE HH'h'", Locale.FRANCE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScreen(
    place: Place,
    state: RadarUiState,
    layerState: LayerUiState,
    onBack: (() -> Unit)?,
    onRefresh: () -> Unit,
    onLayer: (MapLayer) -> Unit,
    onSelect: (Int) -> Unit,
    onTogglePlay: () -> Unit,
    onSaveKey: (String) -> Unit,
    onSaveWindyKey: (String) -> Unit,
    onWebcamClick: (Long) -> Unit,
    onCloseWebcam: () -> Unit,
    onStationClick: (String) -> Unit = {},
    onCloseStation: () -> Unit = {},
) {
    var editingKey by rememberSaveable { mutableStateOf(false) }
    val layer = layerState.layer
    val rain = layer == MapLayer.RAIN

    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text("Cartes", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                    }
                },
                actions = {
                    if (rain) {
                        IconButton(onClick = { editingKey = true }) { Icon(Icons.Filled.Key, contentDescription = "Clé Météo-France") }
                    }
                    IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Actualiser") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            LayerTabs(layer, onLayer)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                WeatherMap(
                    overlay = if (rain) MapOverlay.Image(state.currentImage, state.bounds) else layerState.overlay,
                    center = LatLng(place.latitude, place.longitude),
                    modifier = Modifier.fillMaxSize(),
                    onWebcamClick = onWebcamClick,
                    stations = if (layer == MapLayer.TEMPERATURE) layerState.stations else emptyList(),
                    onStationClick = onStationClick,
                )
                when (layer) {
                    MapLayer.RAIN -> Legend(Modifier.align(Alignment.BottomStart))
                    MapLayer.TEMPERATURE -> TemperatureLegend(Modifier.align(Alignment.BottomStart))
                    MapLayer.LIGHTNING -> LightningLegend(Modifier.align(Alignment.BottomStart))
                    else -> Unit
                }
                // Décalée à droite des légendes pour ne pas les recouvrir.
                Attribution(layer, Modifier.align(Alignment.BottomEnd).padding(start = 110.dp))
                if (state.loading && rain || layerState.loading && !rain) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.TopCenter).padding(top = 16.dp).size(28.dp),
                        strokeWidth = 3.dp,
                    )
                }
                val error = if (rain) state.error else layerState.error
                when {
                    rain && (!state.hasKey || editingKey) -> KeyCard(
                        initial = state.apiKey.orEmpty(),
                        onSave = {
                            editingKey = false
                            onSaveKey(it)
                        },
                        onCancel = if (state.hasKey) ({ editingKey = false }) else null,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    )
                    layer == MapLayer.WEBCAMS && layerState.windyKey == null -> WindyKeyCard(
                        onSave = onSaveWindyKey,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    )
                    layerState.openWebcam != null && layer == MapLayer.WEBCAMS -> WebcamCard(
                        layerState,
                        onClose = onCloseWebcam,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    )
                    layerState.openStation != null && layer == MapLayer.TEMPERATURE -> StationCard(
                        layerState.openStation,
                        onClose = onCloseStation,
                        modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                    )
                    layer == MapLayer.TEMPERATURE && layerState.observationNote != null && error == null -> Text(
                        layerState.observationNote,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(HeaderBlue.copy(alpha = 0.8f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    error != null -> Card(Modifier.align(Alignment.TopCenter).padding(16.dp)) {
                        Text(error, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            when (layer) {
                MapLayer.RAIN -> Timeline(
                    slots = state.frames,
                    selected = state.selected,
                    playing = state.playing,
                    header = rainHeader(state.frames.getOrNull(state.selected)),
                    note = state.forecastNote.takeIf { state.frames.getOrNull(state.selected)?.isForecast != true },
                    label = { clock.format(it.atZone(ZoneId.systemDefault())) },
                    onSelect = onSelect,
                    onTogglePlay = onTogglePlay,
                )
                MapLayer.WEBCAMS -> Unit
                else -> Timeline(
                    slots = layerState.times.map { RadarSlot(it) },
                    selected = layerState.selected,
                    playing = layerState.playing,
                    header = null,
                    note = null,
                    label = {
                        val zoned = it.atZone(ZoneId.systemDefault())
                        if (layer == MapLayer.TEMPERATURE) dayHour.format(zoned).replaceFirstChar { c -> c.titlecase(Locale.FRANCE) } else clock.format(zoned)
                    },
                    onSelect = onSelect,
                    onTogglePlay = onTogglePlay,
                )
            }
        }
    }
}

/** Choix de la couche, sous la barre du haut. */
@Composable
private fun LayerTabs(selected: MapLayer, onLayer: (MapLayer) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(HeaderBlue)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MapLayer.entries.forEach { layer ->
            val active = layer == selected
            Text(
                layer.label,
                color = if (active) Color.White else SecondaryOnNavy,
                fontWeight = if (active) FontWeight.SemiBold else null,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) SELECTED_BLUE else Color.Transparent)
                    .clickable { onLayer(layer) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

private fun rainHeader(slot: RadarSlot?): String = when {
    slot == null -> "Radar Météo-France"
    // Pas de texte pour les prévisions : la frise suffit (on garde la ligne pour que la carte ne bouge pas).
    slot.isForecast -> ""
    else -> {
        val ago = Duration.between(slot.time, Instant.now()).toMinutes()
        "Observation Météo-France de ${clock.format(slot.time.atZone(ZoneId.systemDefault()))}" +
            if (ago in 0..600) " · il y a $ago min" else ""
    }
}

/** Légende des intensités, en bas à gauche comme sur Meteoblue. */
@Composable
private fun Legend(modifier: Modifier = Modifier) {
    Column(modifier.padding(bottom = 8.dp).width(104.dp)) {
        Text(
            "Précipitations",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.fillMaxWidth().background(HeaderBlue.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 3.dp),
        )
        RainClass.entries.forEach { c ->
            Text(
                c.label,
                color = if (c == RainClass.MODERATE || c == RainClass.HEAVY || c == RainClass.VERY_HEAVY) Color.White else Color(0xFF0B0B0B),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.fillMaxWidth().background(Color(c.argb)).padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

/** Légende de température : les couleurs des cases de température de l'app. */
@Composable
private fun TemperatureLegend(modifier: Modifier = Modifier) {
    Column(modifier.padding(bottom = 8.dp).width(64.dp)) {
        Text(
            "°C",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.fillMaxWidth().background(HeaderBlue.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 3.dp),
        )
        listOf(35, 30, 25, 20, 15, 10, 5, 0, -5, -10).forEach { t ->
            Text(
                "$t°",
                color = OnTemperature,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = AppMono,
                modifier = Modifier.fillMaxWidth().background(temperatureColor(t.toDouble())).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

/** Légende de la foudre : nombre d'éclairs en 5 minutes (échelle d'EUMETSAT). */
@Composable
private fun LightningLegend(modifier: Modifier = Modifier) {
    Column(modifier.padding(bottom = 8.dp).width(104.dp)) {
        Text(
            "Éclairs / 5 min",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.fillMaxWidth().background(HeaderBlue.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 3.dp),
        )
        listOf(
            "1" to Color(0xFFFFF6B8), "3" to Color(0xFFFDD380), "5" to Color(0xFFFCA350),
            "10" to Color(0xFFEE5A2B), "15" to Color(0xFFC21E26), "20 et +" to Color(0xFF7A0A2A),
        ).forEach { (label, color) ->
            Text(
                label,
                color = if (color.luminance() < 0.4f) Color.White else Color(0xFF0B0B0B),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.fillMaxWidth().background(color).padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

/** Source de la couche affichée, en petit en bas à droite (obligatoire pour EUMETSAT, DWD et Windy). */
@Composable
private fun Attribution(layer: MapLayer, modifier: Modifier = Modifier) {
    val text = when (layer) {
        MapLayer.RAIN -> "Radar : Météo-France"
        MapLayer.TEMPERATURE -> DwdTemperature.ATTRIBUTION + " · relevés Météo-France, MétéoSuisse, NOAA"
        MapLayer.SATELLITE -> TileLayer.SATELLITE.attribution
        MapLayer.LIGHTNING -> TileLayer.LIGHTNING.attribution
        MapLayer.WEBCAMS -> WindyWebcams.ATTRIBUTION
    }
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(HeaderBlue.copy(alpha = 0.7f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Frise des heures disponibles, avec image précédente/suivante et lecture animée. */
@Composable
private fun Timeline(
    slots: List<RadarSlot>,
    selected: Int,
    playing: Boolean,
    header: String?,
    note: String?,
    label: (Instant) -> String,
    onSelect: (Int) -> Unit,
    onTogglePlay: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selected, slots.size) {
        // +1 pour le séparateur « Prévision » placé avant la première prévision.
        val firstForecast = slots.indexOfFirst { it.isForecast }
        val item = selected + if (firstForecast in 0..selected) 1 else 0
        if (slots.isNotEmpty()) listState.animateScrollToItem((item - 2).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxWidth().background(HeaderBlue).navigationBarsPadding()) {
        if (header != null) {
            Text(
                header,
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp),
            )
        }
        if (note != null) {
            Text(
                note,
                color = RainChanceOnNavy,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                Modifier.weight(1f).background(TIMELINE_BLUE),
                state = listState,
                contentPadding = PaddingValues(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val firstForecast = slots.indexOfFirst { it.isForecast }
                slots.forEachIndexed { index, slot ->
                    if (index == firstForecast) {
                        item(key = "separateur") {
                            Text(
                                "Prévision ›",
                                color = RainChanceOnNavy,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )
                        }
                    }
                    item(key = "${slot.time.epochSecond}-${slot.leadMinutes}") {
                        val active = index == selected
                        Text(
                            label(slot.time),
                            color = if (slot.isForecast && !active) RainChanceOnNavy else Color.White,
                            fontWeight = if (active) FontWeight.SemiBold else null,
                            fontStyle = if (slot.isForecast) FontStyle.Italic else null,
                            fontFamily = AppMono,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (active) SELECTED_BLUE else Color.Transparent)
                                .clickable { onSelect(index) }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            IconButton(onClick = { onSelect(selected - 1) }, enabled = selected > 0) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "Image précédente", tint = Color.White)
            }
            IconButton(onClick = { onSelect(selected + 1) }, enabled = selected < slots.lastIndex) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "Image suivante", tint = Color.White)
            }
            IconButton(onClick = onTogglePlay, enabled = slots.size > 1, modifier = Modifier.size(52.dp)) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Lecture",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }
}

/** Relevé de la station touchée : nom, altitude, température et heure. */
@Composable
private fun StationCard(station: StationObservation, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.clickable(onClick = onClose)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(temperatureColor(station.temperature)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    String.format(Locale.FRANCE, "%.1f°", station.temperature),
                    color = OnTemperature,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(station.name ?: station.id, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    listOfNotNull(
                        station.altitude?.let { "${it.roundToInt()} m" },
                        "relevé de ${clock.format(station.time.atZone(ZoneId.systemDefault()))}",
                        station.source.label,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = AppMono,
                )
            }
        }
    }
}

/** Saisie de la clé gratuite Windy pour les webcams. */
@Composable
private fun WindyKeyCard(onSave: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var key by rememberSaveable { mutableStateOf("") }
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text("Clé Windy", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Les webcams viennent de Windy, gratuitement avec une clé personnelle : créer un compte sur " +
                    "api.windy.com, puis une clé « Webcams API » (offre gratuite), et la coller ici.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.windy.com/keys")))
            }) { Text("Ouvrir api.windy.com") }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim() },
                label = { Text("Clé Windy") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = { onSave(key) }, enabled = key.isNotBlank()) { Text("Enregistrer") }
            }
        }
    }
}

/** Image de la webcam touchée, avec le lien vers sa page Windy (demandé par les conditions de Windy). */
@Composable
private fun WebcamCard(state: LayerUiState, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val cam = state.openWebcam ?: return
    val context = LocalContext.current
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(cam.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            val image = state.webcamImage
            if (image != null) {
                Image(
                    image.asImageBitmap(),
                    contentDescription = cam.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cam.pageUrl)))
                }) { Text("Voir sur Windy") }
                TextButton(onClick = onClose) { Text("Fermer") }
            }
        }
    }
}

/** Saisie de la clé d'API Météo-France (gratuite), gardée sur le téléphone uniquement. */
@Composable
private fun KeyCard(initial: String, onSave: (String) -> Unit, onCancel: (() -> Unit)?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var key by rememberSaveable { mutableStateOf(initial) }
    Card(modifier) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Clé Météo-France", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Le radar vient de l'API publique de Météo-France, gratuite mais avec une clé personnelle :\n" +
                    "1. Créer un compte sur portail-api.meteofrance.fr\n" +
                    "2. S'abonner gratuitement à « DonneesPubliquesRadar », à « PrevisionImmediatePrecipitations » " +
                    "(prévision à 3 h) et à « DonneesPubliquesPaquetObservation » (relevés des stations)\n" +
                    "3. Ensuite seulement, dans « Mes API », générer une clé de type « API Key » (durée maximale, 3 ans)\n" +
                    "4. La coller ci-dessous. Elle reste sur ce téléphone.\n\n" +
                    "Météo-France ne fournit que la dernière image (toutes les 5 min) : l'animation se remplit " +
                    "au fil des consultations et garde les 2 dernières heures.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://portail-api.meteofrance.fr")))
            }) { Text("Ouvrir le portail Météo-France") }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim() },
                label = { Text("Clé d'API") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (onCancel != null) TextButton(onClick = onCancel) { Text("Annuler") }
                Button(onClick = { onSave(key) }, enabled = key.isNotBlank()) { Text("Enregistrer") }
            }
        }
    }
}
