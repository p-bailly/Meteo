package io.github.pbailly.meteo.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.data.Place
import io.github.pbailly.meteo.ui.theme.AppMono
import io.github.pbailly.meteo.ui.theme.LocalChartColors


/** Une ligne de la liste : un lieu, sa prévision courte, favori ou non, affiché ou non. */
private class Spot(val place: Place, val forecast: Forecast?, val altitude: Double, val favorite: Boolean, val current: Boolean)

/**
 * Onglet « Lieux » : recherche, météo à ma position, puis les favoris (étoilés) dans l'ordre choisi (appui long
 * puis glisser pour les réordonner) et les lieux consultés récemment, du plus récent au plus ancien. L'étoile de
 * chaque ligne ajoute ou retire un favori ; « Comparer » met les lieux côte à côte, jour par jour.
 */
@Composable
fun PlacesScreen(
    state: MeteoUiState,
    onSelect: (Place) -> Unit,
    onToggleFavorite: (Place) -> Unit,
    onQueryChange: (String) -> Unit,
    onLocate: () -> Unit,
    onLocationDenied: () -> Unit,
    onOpenSettings: () -> Unit,
    onMoveFavorite: (Int, Int) -> Unit,
    onOpenCompare: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) onLocate() else onLocationDenied()
    }
    val searching = state.search.query.trim().length >= 2

    // Glisser-déposer : appui long sur un favori, puis glisser ; il échange sa place avec celui qu'il survole.
    val listState = rememberLazyListState()
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val favorites by rememberUpdatedState(state.favorites)
    val move by rememberUpdatedState(onMoveFavorite)
    fun favoriteKey(place: Place) = "fav-${place.id}"
    val dragModifier = Modifier.pointerInput(Unit) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { (it.key as? String)?.startsWith("fav-") == true && offset.y.toInt() in it.offset..(it.offset + it.size) }
                    ?.let {
                        dragKey = it.key as String
                        dragOffset = 0f
                    }
            },
            onDrag = { change, amount ->
                val key = dragKey ?: return@detectDragGesturesAfterLongPress
                change.consume()
                dragOffset += amount.y
                val items = listState.layoutInfo.visibleItemsInfo
                val dragged = items.firstOrNull { it.key == key } ?: return@detectDragGesturesAfterLongPress
                val middle = dragged.offset + dragOffset + dragged.size / 2f
                val target = items.firstOrNull {
                    it.key != key && (it.key as? String)?.startsWith("fav-") == true && middle.toInt() in it.offset..(it.offset + it.size)
                } ?: return@detectDragGesturesAfterLongPress
                val from = favorites.indexOfFirst { favoriteKey(it) == key }
                val to = favorites.indexOfFirst { favoriteKey(it) == target.key }
                if (from >= 0 && to >= 0) {
                    move(from, to)
                    // Le favori prend la place de l'autre : on garde son dessin sous le doigt.
                    dragOffset -= (target.offset - dragged.offset)
                }
            },
            onDragEnd = { dragKey = null; dragOffset = 0f },
            onDragCancel = { dragKey = null; dragOffset = 0f },
        )
    }

    LazyColumn(Modifier.fillMaxSize().imePadding().then(dragModifier), state = listState) {
        item(key = "title") {
            ScreenHeader("Lieux") {
                if ((state.favorites + state.recents).distinctBy { it.id }.size >= 2) OutlineChip("Comparer", onClick = onOpenCompare)
                IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Réglages", tint = colors.onSurfaceVariant) }
            }
            OutlinedTextField(
                value = state.search.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp),
                placeholder = { Text("Rechercher un lieu") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.search.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Clear, contentDescription = "Effacer") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = colors.outlineVariant,
                    unfocusedContainerColor = colors.surfaceContainerLowest,
                    focusedContainerColor = colors.surfaceContainerLowest,
                ),
            )
            Button(
                onClick = {
                    if (hasLocationPermission(context)) {
                        onLocate()
                    } else {
                        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                    }
                },
                enabled = !state.locating,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp).height(52.dp),
            ) {
                if (state.locating) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Icon(Icons.Outlined.GpsFixed, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(if (state.locating) "Localisation…" else "Météo à ma position", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
            state.locationError?.let {
                Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = colors.outlineVariant)
        }
        if (searching) {
            searchResults(state, onSelect, onToggleFavorite)
            return@LazyColumn
        }
        val spots = spots(state)
        if (spots.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Recherche un lieu, puis touche l'étoile sur sa page pour l'ajouter aux favoris.",
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
            return@LazyColumn
        }
        val firstRecent = spots.firstOrNull { !it.favorite }
        items(spots, key = { if (it.favorite) favoriteKey(it.place) else "recent-${it.place.id}" }) { spot ->
            val dragging = spot.favorite && dragKey == favoriteKey(spot.place)
            if (spot === firstRecent && spots.first() !== spot) SectionLabel("Consultés récemment")
            SpotRow(
                spot,
                dragging,
                onSelect,
                onToggleFavorite,
                Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (dragging) dragOffset else 0f
                        shadowElevation = if (dragging) 8.dp.toPx() else 0f
                    }
                    .then(if (dragging) Modifier else Modifier.animateItem()),
            )
        }
        item(key = "end") { Spacer(Modifier.height(18.dp)) }
    }
}

/** Les favoris dans l'ordre choisi, puis les autres lieux consultés, du plus récent au plus ancien. */
private fun spots(state: MeteoUiState): List<Spot> {
    val current = state.place
    fun spot(p: Place, favorite: Boolean): Spot {
        val f = if (p.id == current?.id && state.forecast.data != null) state.forecast.data else state.summaries.data?.get(p.id)
        return Spot(p, f, p.elevation ?: f?.elevation?.takeIf { !it.isNaN() } ?: 0.0, favorite = favorite, current = p.id == current?.id)
    }
    val favoriteIds = state.favorites.map { it.id }.toSet()
    val recents = (state.recents + listOfNotNull(current)).distinctBy { it.id }.filter { it.id !in favoriteIds }
    return state.favorites.map { spot(it, favorite = true) } + recents.map { spot(it, favorite = false) }
}

@Composable
private fun SpotRow(
    e: Spot,
    dragging: Boolean,
    onSelect: (Place) -> Unit,
    onToggleFavorite: (Place) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val f = e.forecast
    val c = f?.current
    val today = f?.daily?.date?.indexOf(c?.time?.toLocalDate())?.takeIf { it >= 0 } ?: 0
    Column(modifier.background(colors.background)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (e.current || dragging) colors.surfaceContainer else Color.Transparent)
                .clickable { onSelect(e.place) }
                .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(62.dp), horizontalAlignment = Alignment.End) {
                Text(Fmt.thousands(e.altitude), fontFamily = AppMono, fontWeight = FontWeight.Medium, fontSize = 16.sp, maxLines = 1, softWrap = false)
                MonoText("m", size = 10.sp)
            }
            VerticalDivider(Modifier.padding(horizontal = 12.dp), color = colors.outlineVariant)
            Column(Modifier.weight(1f)) {
                Text(e.place.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                e.place.department?.let { MonoText(it, size = 10.5.sp) }
            }
            if (c != null) {
                WeatherIcon(c.weatherCode, c.isDay, size = 30.dp)
                Column(Modifier.width(70.dp), horizontalAlignment = Alignment.End) {
                    Text(Fmt.temp(c.temperature), fontWeight = FontWeight.SemiBold, fontSize = 22.sp, maxLines = 1)
                    MonoText(
                        "${Fmt.temp(f.daily.temperatureMax.getOrElse(today) { Double.NaN })} / ${Fmt.temp(f.daily.temperatureMin.getOrElse(today) { Double.NaN })}",
                        size = 10.sp,
                        align = TextAlign.End,
                    )
                }
            } else {
                Box(Modifier.width(108.dp), contentAlignment = Alignment.CenterEnd) { MonoText("…") }
            }
            // Étoile : pleine et orange pour un favori ; la toucher l'ajoute ou le retire.
            IconButton(onClick = { onToggleFavorite(e.place) }, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (e.favorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                    contentDescription = if (e.favorite) "Retirer des favoris" else "Ajouter aux favoris",
                    tint = if (e.favorite) LocalChartColors.current.accent else colors.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.searchResults(
    state: MeteoUiState,
    onSelect: (Place) -> Unit,
    onToggleFavorite: (Place) -> Unit,
) {
    val search = state.search
    when {
        search.loading && search.results.isEmpty() -> item {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        search.error != null -> item { Text(search.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        search.results.isEmpty() -> item { Text("Aucun lieu trouvé.", modifier = Modifier.padding(16.dp)) }
        else -> items(search.results, key = { "result-${it.id}" }) { place ->
            val favorite = state.favorites.any { it.id == place.id }
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable { onSelect(place) }.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.width(62.dp), horizontalAlignment = Alignment.End) {
                        Text(place.elevation?.let { Fmt.thousands(it) } ?: "–", fontFamily = AppMono, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        MonoText("m", size = 10.sp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(place.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (place.subtitle.isNotBlank()) MonoText(place.subtitle, size = 11.sp)
                    }
                    IconButton(onClick = { onToggleFavorite(place) }) {
                        Icon(
                            if (favorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            contentDescription = if (favorite) "Retirer des favoris" else "Ajouter aux favoris",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION).any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
