package io.github.pbailly.meteo.ui.radar

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.pbailly.meteo.data.Outlook
import io.github.pbailly.meteo.data.Place
import io.github.pbailly.meteo.data.maps.Measure
import io.github.pbailly.meteo.data.maps.ObservationSource
import io.github.pbailly.meteo.data.maps.StationReading
import io.github.pbailly.meteo.data.maps.TileLayer
import io.github.pbailly.meteo.data.maps.WindyWebcams
import io.github.pbailly.meteo.data.radar.RainClass
import io.github.pbailly.meteo.ui.DetailHeader
import io.github.pbailly.meteo.ui.Fmt
import io.github.pbailly.meteo.ui.ScreenHeader
import io.github.pbailly.meteo.ui.drawLabel
import io.github.pbailly.meteo.ui.theme.AppMono
import io.github.pbailly.meteo.ui.theme.HeaderBlue
import io.github.pbailly.meteo.ui.theme.LocalChartColors
import io.github.pbailly.meteo.ui.theme.OnTemperature
import io.github.pbailly.meteo.ui.theme.temperatureColor
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.maplibre.android.geometry.LatLng

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
private val clock = DateTimeFormatter.ofPattern("HH:mm")

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

    val actions: @Composable RowScope.() -> Unit = {
        val tint = MaterialTheme.colorScheme.onSurfaceVariant
        // Les webcams ont leur bouton ; les couches météo sont dans le menu posé sur la carte.
        WebcamsButton(active = layer == MapLayer.WEBCAMS, onClick = { onLayer(if (layer == MapLayer.WEBCAMS) MapLayer.RAIN else MapLayer.WEBCAMS) })
        if (rain) {
            IconButton(onClick = { editingKey = true }) { Icon(Icons.Filled.Key, contentDescription = "Clé Météo-France", tint = tint) }
        }
        IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Actualiser", tint = tint) }
    }
    Column(Modifier.fillMaxSize()) {
        if (onBack != null) DetailHeader("Cartes", onBack, trailing = actions) else ScreenHeader("Cartes", trailing = actions)
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
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
            LayerMenu(layer, onLayer, Modifier.align(Alignment.TopEnd).padding(8.dp))
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
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 8.dp),
                )
                layerState.openStation != null && layer == MapLayer.TEMPERATURE -> StationCard(
                    layerState.openStation,
                    onClose = onCloseStation,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
                layer == MapLayer.TEMPERATURE && layerState.observationNote != null && error == null -> Column(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(HeaderBlue.copy(alpha = 0.8f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    // Une information par ligne, jamais coupée en deux.
                    layerState.observationNote.lines().forEach { line ->
                        Text(line, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                    }
                }
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
                // Toujours affichée, même sur les images prévues : la frise et la carte ne bougent pas.
                note = state.forecastNote,
                label = { clock.format(it.atZone(ZoneId.systemDefault())) },
                onSelect = onSelect,
                onTogglePlay = onTogglePlay,
            )
            // Température : seulement la carte actuelle, pas de frise.
            MapLayer.WEBCAMS, MapLayer.TEMPERATURE -> Unit
            else -> Timeline(
                slots = layerState.times.map { RadarSlot(it) },
                selected = layerState.selected,
                playing = layerState.playing,
                header = null,
                note = null,
                label = { clock.format(it.atZone(ZoneId.systemDefault())) },
                onSelect = onSelect,
                onTogglePlay = onTogglePlay,
            )
        }
    }
}

/** Bouton des webcams, dans l'en-tête : plein quand elles sont affichées. */
@Composable
private fun WebcamsButton(active: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (active) colors.onBackground else colors.surfaceContainerLowest,
        border = BorderStroke(1.dp, if (active) colors.onBackground else colors.outlineVariant),
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Videocam, contentDescription = null, tint = if (active) colors.background else colors.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Webcams", color = if (active) colors.background else colors.primary, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** Choix de la couche météo (pluie, température, satellite, foudre) : un menu déroulant posé sur la carte. */
@Composable
private fun LayerMenu(selected: MapLayer, onLayer: (MapLayer) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box(modifier) {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(12.dp),
            color = colors.surfaceContainerLowest,
            border = BorderStroke(1.dp, colors.outlineVariant),
            shadowElevation = 3.dp,
        ) {
            Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Layers, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (selected == MapLayer.WEBCAMS) "Couches" else selected.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choisir la carte", tint = colors.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MapLayer.entries.filter { it != MapLayer.WEBCAMS }.forEach { layer ->
                DropdownMenuItem(
                    text = { Text(layer.label, fontWeight = if (layer == selected) FontWeight.SemiBold else null) },
                    onClick = {
                        open = false
                        onLayer(layer)
                    },
                    trailingIcon = { if (layer == selected) Icon(Icons.Filled.Check, contentDescription = null, tint = colors.primary) },
                )
            }
        }
    }
}

/** En-tête de la frise : toujours une ligne, pour que la carte ne bouge pas en passant aux prévisions. */
private fun rainHeader(slot: RadarSlot?): String = when {
    slot == null -> "Radar Météo-France"
    slot.isForecast -> {
        val ahead = Duration.between(Instant.now(), slot.time).toMinutes()
        "Prévision pour ${clock.format(slot.time.atZone(ZoneId.systemDefault()))}" + if (ahead > 0) " · dans $ahead min" else ""
    }
    else -> {
        val ago = Duration.between(slot.time, Instant.now()).toMinutes()
        "Radar de ${clock.format(slot.time.atZone(ZoneId.systemDefault()))}" +
            if (ago in 0..600) " · il y a $ago min" else ""
    }
}

/** Légende des intensités, en bas à gauche comme sur Meteoblue. */
@Composable
private fun Legend(modifier: Modifier = Modifier) {
    Column(modifier.padding(bottom = 8.dp).width(IntrinsicSize.Max)) {
        Text(
            "Précipitations",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.fillMaxWidth().background(HeaderBlue.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 3.dp),
        )
        RainClass.entries.forEach { c ->
            Text(
                c.label,
                color = if (c == RainClass.MODERATE || c == RainClass.HEAVY || c == RainClass.VERY_HEAVY) Color.White else Color(0xFF0B0B0B),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.fillMaxWidth().background(Color(c.argb)).padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

/** Légende de température : les couleurs des cases de température de l'app. */
@Composable
private fun TemperatureLegend(modifier: Modifier = Modifier) {
    Column(modifier.padding(bottom = 8.dp).width(IntrinsicSize.Max)) {
        Text(
            "°C",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
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
    Column(modifier.padding(bottom = 8.dp).width(IntrinsicSize.Max)) {
        Text(
            "Éclairs / 5 min",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
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
                maxLines = 1,
                softWrap = false,
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
        MapLayer.TEMPERATURE -> "© DWD · relevés Météo-France, MétéoSuisse, NOAA"
        MapLayer.SATELLITE -> TileLayer.SATELLITE.attribution
        MapLayer.LIGHTNING -> TileLayer.LIGHTNING.attribution
        MapLayer.WEBCAMS -> WindyWebcams.ATTRIBUTION
    }
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
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
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(colors.surfaceContainerLowest).navigationBarsPadding()) {
        HorizontalDivider(color = colors.outlineVariant)
        if (header != null) {
            Text(
                header,
                color = colors.onSurfaceVariant,
                fontFamily = AppMono,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
        }
        if (note != null) {
            Text(
                note,
                color = colors.primary,
                fontFamily = AppMono,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val firstForecast = slots.indexOfFirst { it.isForecast }
                slots.forEachIndexed { index, slot ->
                    if (index == firstForecast) {
                        item(key = "separateur") {
                            Text(
                                "Prévision ›",
                                color = colors.primary,
                                fontFamily = AppMono,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )
                        }
                    }
                    item(key = "${slot.time.epochSecond}-${slot.leadMinutes}") {
                        val active = index == selected
                        Text(
                            label(slot.time),
                            color = when {
                                active -> colors.background
                                slot.isForecast -> colors.primary
                                else -> colors.onBackground
                            },
                            fontWeight = if (active) FontWeight.SemiBold else null,
                            fontStyle = if (slot.isForecast) FontStyle.Italic else null,
                            fontFamily = AppMono,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) colors.onBackground else Color.Transparent)
                                .clickable { onSelect(index) }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                        )
                    }
                }
            }
            IconButton(onClick = { onSelect(selected - 1) }, enabled = selected > 0) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "Image précédente", tint = colors.primary)
            }
            IconButton(onClick = { onSelect(selected + 1) }, enabled = selected < slots.lastIndex) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "Image suivante", tint = colors.primary)
            }
            Box(
                Modifier
                    .padding(end = 12.dp, start = 4.dp)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (slots.size > 1) colors.primary else colors.outlineVariant)
                    .clickable(enabled = slots.size > 1, onClick = onTogglePlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Lecture",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

/** Station touchée : nom, altitude, heure du relevé, toutes ses mesures et sa température des dernières 24 heures. */
@Composable
private fun StationCard(details: StationDetails, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val station = details.station
    val colors = MaterialTheme.colorScheme
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLowest),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(24.dp))
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
                Column(Modifier.weight(1f)) {
                    Text(station.name ?: station.id, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        listOfNotNull(
                            station.altitude?.let { "${it.roundToInt()} m" },
                            "relevé de ${clock.format(station.time.atZone(ZoneId.systemDefault()))}",
                            station.source.label.substringBefore(" ("),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = AppMono,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Fermer") }
            }
            // Mesures, deux par ligne.
            val shown = details.values.entries.filter { it.key != Measure.WIND_DIRECTION }.toList()
            shown.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    pair.forEach { (measure, value) ->
                        Column(Modifier.weight(1f)) {
                            Text(
                                measure.label.uppercase(Locale.FRANCE),
                                fontFamily = AppMono,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                            )
                            Text(measureText(measure, value, details.values), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            when {
                details.history.size >= 2 -> HistoryChart(details.history, Modifier.fillMaxWidth().padding(top = 12.dp).height(86.dp))
                details.loading -> Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                station.source == ObservationSource.METEO_FRANCE -> Text(
                    "Les dernières 24 h demandent la clé Météo-France abonnée aux observations.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            station.text?.let {
                Text(it, fontFamily = AppMono, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** « 12 km/h NO », « 1 016,2 hPa », « 82 % ». */
private fun measureText(measure: Measure, value: Double, all: Map<Measure, Double>): String {
    val number = if (measure.decimals == 0) value.roundToInt().toString() else String.format(Locale.FRANCE, "%.${measure.decimals}f", value)
    val direction = if (measure == Measure.WIND) all[Measure.WIND_DIRECTION]?.let { " " + Outlook.compass(it) }.orEmpty() else ""
    return "$number ${measure.unit}$direction"
}

/** Température des dernières 24 heures, avec le mini et le maxi. */
@Composable
private fun HistoryChart(history: List<StationReading>, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val chart = LocalChartColors.current
    Column(modifier) {
        val min = history.minBy { it.temperature }
        val max = history.maxBy { it.temperature }
        Text(
            "DERNIÈRES 24 H · mini ${Fmt.temp(min.temperature)} à ${clock.format(min.time.atZone(ZoneId.systemDefault()))}" +
                " · maxi ${Fmt.temp(max.temperature)} à ${clock.format(max.time.atZone(ZoneId.systemDefault()))}",
            fontFamily = AppMono,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)) {
            val t0 = history.first().time.epochSecond.toDouble()
            val t1 = history.last().time.epochSecond.toDouble().coerceAtLeast(t0 + 1)
            val lo = min.temperature - 0.5
            val hi = max.temperature + 0.5
            val bottom = size.height - 14.dp.toPx()
            fun x(r: StationReading) = ((r.time.epochSecond - t0) / (t1 - t0) * size.width).toFloat()
            fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * bottom).toFloat()
            val path = Path()
            history.forEachIndexed { k, r -> if (k == 0) path.moveTo(x(r), y(r.temperature)) else path.lineTo(x(r), y(r.temperature)) }
            drawLine(chart.axis, Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())
            drawPath(path, chart.temperature, style = Stroke(2.dp.toPx()))
            drawCircle(chart.temperature, 3.dp.toPx(), Offset(x(history.last()), y(history.last().temperature)))
            listOf(history.first(), history.last()).forEachIndexed { k, r ->
                drawLabel(measurer, clock.format(r.time.atZone(ZoneId.systemDefault())), Offset(if (k == 0) 0f else size.width, size.height), chart.muted, 9.5f, anchorX = k.toFloat(), anchorY = 1f)
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

/** Image de la webcam touchée, sur toute la largeur, avec le lien vers sa page Windy (demandé par Windy). */
@Composable
private fun WebcamCard(state: LayerUiState, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val cam = state.openWebcam ?: return
    val context = LocalContext.current
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(cam.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            val image = state.webcamImage
            if (image != null) {
                Image(
                    image.asImageBitmap(),
                    contentDescription = cam.title,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(image.width.toFloat() / image.height.coerceAtLeast(1))
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
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
