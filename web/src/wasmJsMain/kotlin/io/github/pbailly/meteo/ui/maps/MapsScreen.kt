package io.github.pbailly.meteo.ui.maps

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pbailly.meteo.data.Place
import io.github.pbailly.meteo.data.maps.RainViewer
import io.github.pbailly.meteo.data.maps.TileLayer
import io.github.pbailly.meteo.ui.ScreenHeader
import io.github.pbailly.meteo.ui.SegmentedToggle
import io.github.pbailly.meteo.ui.theme.AppMono
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clock = DateTimeFormatter.ofPattern("HH:mm")

/**
 * L'onglet Cartes de la webapp, comme celui de l'appli : la carte, le choix de la couche (pluie, satellite, foudre),
 * et la frise des heures avec l'image précédente, la suivante et la lecture animée.
 */
@Composable
fun MapsScreen(place: Place, viewModel: MapsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.onShown() }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Cartes") {
            IconButton(onClick = { viewModel.show(state.layer) }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Actualiser", tint = colors.onSurfaceVariant)
            }
        }
        SegmentedToggle(
            WebLayer.entries.map { it.label },
            state.layer.ordinal,
            { viewModel.show(WebLayer.entries[it]) },
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fill = true,
        )
        // La carte est un élément de la page posé par-dessus l'interface : rien de Compose ne peut s'afficher sur
        // elle. Légende, source et état du chargement sont donc juste en dessous.
        WebMap(place.latitude, place.longitude, state.current?.tiles, Modifier.weight(1f).fillMaxWidth())
        Row(
            Modifier.fillMaxWidth().background(colors.surfaceContainerLowest).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.layer == WebLayer.RAIN) RainLegend()
            Spacer(Modifier.weight(1f))
            when {
                state.loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                state.error != null -> Text(state.error!!, color = colors.error, style = MaterialTheme.typography.labelMedium, maxLines = 2)
                else -> Attribution(
                    when (state.layer) {
                        WebLayer.RAIN -> RainViewer.ATTRIBUTION
                        WebLayer.SATELLITE -> TileLayer.SATELLITE.attribution
                        WebLayer.LIGHTNING -> TileLayer.LIGHTNING.attribution
                    },
                )
            }
        }
        Timeline(state, onSelect = viewModel::select, onTogglePlay = viewModel::togglePlay)
    }
}

@Composable
private fun Timeline(state: MapsUiState, onSelect: (Int) -> Unit, onTogglePlay: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    LaunchedEffect(state.selected, state.frames.size) {
        if (state.frames.isNotEmpty()) listState.animateScrollToItem((state.selected - 2).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxWidth().background(colors.surfaceContainerLowest)) {
        HorizontalDivider(color = colors.outlineVariant)
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.frames.forEachIndexed { index, frame ->
                    item(key = frame.time.epochSecond) {
                        val active = index == state.selected
                        Text(
                            frame.time.atZone(ZoneId.systemDefault()).format(clock),
                            color = if (active) colors.background else colors.onBackground,
                            fontWeight = if (active) FontWeight.SemiBold else null,
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
            IconButton(onClick = { onSelect(state.selected - 1) }, enabled = state.selected > 0) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "Image précédente", tint = colors.primary)
            }
            IconButton(onClick = { onSelect(state.selected + 1) }, enabled = state.selected < state.frames.lastIndex) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "Image suivante", tint = colors.primary)
            }
            Box(
                Modifier
                    .padding(end = 12.dp, start = 4.dp)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (state.frames.size > 1) colors.primary else colors.outlineVariant)
                    .clickable(enabled = state.frames.size > 1, onClick = onTogglePlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.playing) "Pause" else "Lecture",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

/** Légende de la palette de RainViewer (« Universal Blue ») : de la bruine à l'orage. */
@Composable
private fun RainLegend(modifier: Modifier = Modifier) {
    Column(
        modifier,
    ) {
        Box(
            Modifier.width(120.dp).height(8.dp).clip(RoundedCornerShape(2.dp)).background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF88DDEE), Color(0xFF0099CC), Color(0xFF0077AA), Color(0xFFFFEE00), Color(0xFFFF8800), Color(0xFFFF0000), Color(0xFFC10000)),
                ),
            ),
        )
        Row(Modifier.width(120.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("faible", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = AppMono, style = MaterialTheme.typography.labelSmall)
            Text("forte", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = AppMono, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Attribution(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = AppMono,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
