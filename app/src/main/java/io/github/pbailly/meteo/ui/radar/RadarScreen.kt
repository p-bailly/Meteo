package io.github.pbailly.meteo.ui.radar

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import io.github.pbailly.meteo.data.radar.RainClass
import io.github.pbailly.meteo.ui.NavyTopBar
import io.github.pbailly.meteo.ui.theme.HeaderBlue
import io.github.pbailly.meteo.ui.theme.PlexMono
import io.github.pbailly.meteo.ui.theme.RainChanceOnNavy
import org.maplibre.android.geometry.LatLng
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Fond de la frise des heures, un ton au-dessus du marine de la barre. */
private val TIMELINE_BLUE = Color(0xFF123F63)
private const val REFRESH_MS = 2 * 60 * 1000L

/** Écran radar relié à son ViewModel : vérifie une nouvelle image toutes les 2 minutes tant qu'il est visible. */
@Composable
fun RadarRoute(place: Place, onBack: () -> Unit, viewModel: RadarViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, state.hasKey) {
        if (!state.hasKey) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                viewModel.refresh()
                delay(REFRESH_MS)
            }
        }
    }
    DisposableEffect(viewModel) { onDispose { viewModel.stopPlaying() } }
    BackHandler(onBack = onBack)
    RadarScreen(
        place = place,
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onSelect = viewModel::select,
        onTogglePlay = viewModel::togglePlay,
        onSaveKey = viewModel::saveKey,
    )
}
private val SELECTED_BLUE = Color(0xFF1F6FAE)
private val clock = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScreen(
    place: Place,
    state: RadarUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (Int) -> Unit,
    onTogglePlay: () -> Unit,
    onSaveKey: (String) -> Unit,
) {
    var editingKey by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            NavyTopBar(
                title = { Text("Radar", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
                actions = {
                    IconButton(onClick = { editingKey = true }) { Icon(Icons.Filled.Key, contentDescription = "Clé Météo-France") }
                    IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Actualiser") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                RadarMap(
                    image = state.currentImage,
                    bounds = state.bounds,
                    center = LatLng(place.latitude, place.longitude),
                    modifier = Modifier.fillMaxSize(),
                )
                Legend(Modifier.align(Alignment.BottomStart))
                if (state.loading) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.TopCenter).padding(top = 16.dp).size(28.dp),
                        strokeWidth = 3.dp,
                    )
                }
                if (!state.hasKey || editingKey) {
                    KeyCard(
                        initial = state.apiKey.orEmpty(),
                        onSave = {
                            editingKey = false
                            onSaveKey(it)
                        },
                        onCancel = if (state.hasKey) ({ editingKey = false }) else null,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    )
                } else if (state.error != null) {
                    Card(Modifier.align(Alignment.TopCenter).padding(16.dp)) {
                        Text(state.error, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Timeline(state, onSelect, onTogglePlay)
        }
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

/** Frise des heures disponibles, avec image précédente/suivante et lecture animée. */
@Composable
private fun Timeline(state: RadarUiState, onSelect: (Int) -> Unit, onTogglePlay: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.selected, state.frames.size) {
        // +1 pour le séparateur « Prévision » placé avant la première prévision.
        val firstForecast = state.frames.indexOfFirst { it.isForecast }
        val item = state.selected + if (firstForecast in 0..state.selected) 1 else 0
        if (state.frames.isNotEmpty()) listState.animateScrollToItem((item - 2).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxWidth().background(HeaderBlue).navigationBarsPadding()) {
        val slot = state.frames.getOrNull(state.selected)
        val time = slot?.time?.let { clock.format(it.atZone(ZoneId.systemDefault())) }
        Text(
            when {
                slot == null -> "Radar Météo-France"
                slot.isForecast -> "Prévision pour $time (+${slot.leadMinutes} min), d'après le déplacement des pluies"
                else -> {
                    val ago = Duration.between(slot.time, Instant.now()).toMinutes()
                    "Observation Météo-France de $time" + if (ago in 0..600) " · il y a $ago min" else ""
                }
            },
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp),
        )
        if (state.forecastNote != null && slot?.isForecast != true) {
            Text(
                state.forecastNote,
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
                val firstForecast = state.frames.indexOfFirst { it.isForecast }
                state.frames.forEachIndexed { index, slot ->
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
                        val selected = index == state.selected
                        Text(
                            clock.format(slot.time.atZone(ZoneId.systemDefault())),
                            color = if (slot.isForecast && !selected) RainChanceOnNavy else Color.White,
                            fontWeight = if (selected) FontWeight.SemiBold else null,
                            fontStyle = if (slot.isForecast) FontStyle.Italic else null,
                            fontFamily = PlexMono,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (selected) SELECTED_BLUE else Color.Transparent)
                                .clickable { onSelect(index) }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            IconButton(onClick = { onSelect(state.selected - 1) }, enabled = state.selected > 0) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "Image précédente", tint = Color.White)
            }
            IconButton(onClick = { onSelect(state.selected + 1) }, enabled = state.selected < state.frames.lastIndex) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "Image suivante", tint = Color.White)
            }
            IconButton(onClick = onTogglePlay, enabled = state.frames.size > 1, modifier = Modifier.size(52.dp)) {
                Icon(
                    if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.playing) "Pause" else "Lecture",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
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
                    "2. Ouvrir l'API « Radar » (DonneesPubliquesRadar) et cliquer « Souscrire gratuitement »\n" +
                    "3. Dans « Mes API », générer une clé de type « API Key » avec la durée maximale (3 ans)\n" +
                    "4. La coller ci-dessous. Elle reste sur ce téléphone.\n\n" +
                    "Météo-France ne fournit que la dernière image (toutes les 5 min) : l'animation se remplit " +
                    "au fil des consultations et garde les 2 dernières heures. La prévision à 1 h prolonge " +
                    "le déplacement des pluies mesuré entre deux images.",
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
