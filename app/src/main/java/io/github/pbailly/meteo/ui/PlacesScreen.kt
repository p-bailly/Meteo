package io.github.pbailly.meteo.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.SwapVert
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.pbailly.meteo.data.Altitude
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.data.Outlook
import io.github.pbailly.meteo.data.Place
import io.github.pbailly.meteo.ui.theme.AppMono
import java.time.LocalDate
import java.util.Locale

private val AlertRed = Color(0xFFC8202A)

/** Une ligne de la liste : un lieu, ou un repère d'altitude (iso 0, limite pluie-neige) intercalé. */
private sealed interface Entry {
    val altitude: Double

    class Spot(val place: Place, val forecast: Forecast?, override val altitude: Double, val favorite: Boolean, val current: Boolean) : Entry

    class Mark(val text: String, override val altitude: Double, val freezing: Boolean) : Entry
}

/**
 * Onglet « Lieux » : recherche, météo à ma position, puis les favoris rangés par altitude, avec l'iso 0 °C du jour
 * et la prochaine limite pluie-neige intercalées à leur altitude. « Modifier » permet de retirer des favoris.
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
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var editing by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) onLocate() else onLocationDenied()
    }
    val searching = state.search.query.trim().length >= 2

    LazyColumn(Modifier.fillMaxSize().imePadding()) {
        item(key = "title") {
            ScreenHeader("Lieux") {
                IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Réglages", tint = colors.onSurfaceVariant) }
                if (state.favorites.isNotEmpty()) OutlineChip(if (editing) "Terminé" else "Modifier", onClick = { editing = !editing })
            }
            val count = state.favorites.size
            MonoText(
                when (count) {
                    0 -> "aucun favori"
                    1 -> "1 favori"
                    else -> "$count favoris · rangés par altitude"
                },
                modifier = Modifier.padding(start = 20.dp, top = 2.dp),
            )
            OutlinedTextField(
                value = state.search.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp),
                placeholder = { Text("Ville, col, station, sommet") },
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
        val entries = entries(state)
        if (entries.none { it is Entry.Spot }) {
            item(key = "empty") {
                Text(
                    "Recherche un lieu, puis touche l'étoile sur sa page pour l'ajouter aux favoris.",
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
            return@LazyColumn
        }
        items(entries, key = { e -> if (e is Entry.Spot) "place-${e.place.id}" else "mark-${(e as Entry.Mark).freezing}" }) { e ->
            when (e) {
                is Entry.Spot -> SpotRow(e, editing, onSelect, onToggleFavorite)
                is Entry.Mark -> MarkRow(e)
            }
        }
        item(key = "footer") {
            Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 18.dp)) {
                Icon(Icons.Outlined.SwapVert, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                MonoText("Iso 0 et limite pluie-neige s'intercalent entre vos lieux selon leur altitude.", size = 11.sp, maxLines = 3)
            }
        }
    }
}

/** Favoris (et le lieu affiché s'il n'en est pas un), du plus haut au plus bas, avec les repères d'altitude. */
private fun entries(state: MeteoUiState): List<Entry> {
    val current = state.place
    val currentForecast = state.forecast.data
    val spots = state.favorites.map { p ->
        val f = if (p.id == current?.id && currentForecast != null) currentForecast else state.summaries.data?.get(p.id)
        Entry.Spot(p, f, p.elevation ?: f?.elevation ?: 0.0, favorite = true, current = p.id == current?.id)
    }.toMutableList<Entry>()
    if (current != null && state.favorites.none { it.id == current.id }) {
        spots += Entry.Spot(current, currentForecast, current.elevation ?: currentForecast?.elevation ?: 0.0, favorite = false, current = true)
    }
    val marks = ArrayList<Entry>()
    if (currentForecast != null) {
        val now = Outlook.nowIndex(currentForecast)
        val iso = Altitude.freezingLevel(currentForecast, now)
        if (!iso.isNaN()) marks += Entry.Mark("ISO 0 °C AUJ. · ${Fmt.altitude(iso, 100).uppercase(Locale.FRANCE)}", iso, freezing = true)
        val today = currentForecast.hourly.time.getOrNull(now)?.toLocalDate() ?: LocalDate.now()
        // La plus basse limite pluie-neige de la semaine, quand il pleut ou neige.
        Altitude.days(currentForecast).filter { !it.date.isBefore(today) }.take(8).filter { it.snowLine != null }.minByOrNull { it.snowLine!! }?.let { d ->
            val label = Outlook.dayLabel(d.date, today).uppercase(Locale.FRANCE)
            marks += Entry.Mark("PLUIE-NEIGE $label · ${Fmt.altitude(d.snowLine!!, 100).uppercase(Locale.FRANCE)}", d.snowLine, freezing = false)
        }
    }
    // Les repères au-dessus du plus haut lieu ou sous le plus bas restent en tête ou en fin de liste.
    return (spots + marks).sortedByDescending { it.altitude }
}

@Composable
private fun SpotRow(e: Entry.Spot, editing: Boolean, onSelect: (Place) -> Unit, onToggleFavorite: (Place) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val f = e.forecast
    val c = f?.current
    val today = f?.daily?.date?.indexOf(c?.time?.toLocalDate())?.takeIf { it >= 0 } ?: 0
    val note = remember(f) { f?.let { Outlook.placeNote(it, it.daily.date.getOrNull(today) ?: LocalDate.now()) } }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (e.current) colors.surfaceContainer else Color.Transparent)
                .clickable { onSelect(e.place) }
                .padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(62.dp), horizontalAlignment = Alignment.End) {
                Text(Fmt.thousands(e.altitude), fontFamily = AppMono, fontWeight = FontWeight.Medium, fontSize = 16.sp, maxLines = 1)
                MonoText("m", size = 10.sp)
            }
            VerticalDivider(Modifier.padding(horizontal = 12.dp), color = colors.outlineVariant)
            Column(Modifier.weight(1f)) {
                Text(e.place.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when {
                    note != null -> note.text
                    else -> listOfNotNull(e.place.admin1?.takeIf { it.isNotBlank() }, if (e.current) "affiché" else null).joinToString(" · ")
                }
                if (sub.isNotEmpty()) MonoText(sub, color = if (note?.alert == true) AlertRed else colors.onSurfaceVariant, size = 10.sp, maxLines = 2)
            }
            if (editing) {
                IconButton(onClick = { onToggleFavorite(e.place) }) {
                    if (e.favorite) {
                        Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = "Retirer des favoris", tint = AlertRed)
                    } else {
                        Icon(Icons.Outlined.StarOutline, contentDescription = "Ajouter aux favoris", tint = colors.primary)
                    }
                }
                return@Row
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
        }
        HorizontalDivider(color = colors.outlineVariant)
    }
}

@Composable
private fun MarkRow(e: Entry.Mark) {
    val colors = MaterialTheme.colorScheme
    val color = if (e.freezing) colors.primary else colors.onBackground
    Column {
        Row(
            Modifier.fillMaxWidth().background(colors.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoText(e.text, color = color, size = 10.5.sp, bold = true)
            Spacer(Modifier.width(8.dp))
            Canvas(Modifier.weight(1f).height(6.dp)) {
                drawLine(
                    color.copy(alpha = 0.6f),
                    Offset(0f, size.height / 2),
                    Offset(size.width, size.height / 2),
                    1.2.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = if (e.freezing) PathEffect.dashPathEffect(floatArrayOf(6f, 5f)) else PathEffect.dashPathEffect(floatArrayOf(1.5f, 5f)),
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
