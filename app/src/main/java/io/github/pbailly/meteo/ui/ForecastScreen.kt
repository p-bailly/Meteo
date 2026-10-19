package io.github.pbailly.meteo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.data.MultiModelForecast
import io.github.pbailly.meteo.data.Place
import io.github.pbailly.meteo.data.Predictability
import io.github.pbailly.meteo.data.RainRange
import io.github.pbailly.meteo.ui.theme.PlexMono
import io.github.pbailly.meteo.ui.theme.RainChanceOnNavy
import io.github.pbailly.meteo.ui.theme.SecondaryOnNavy
import io.github.pbailly.meteo.ui.theme.uvColor
import kotlin.math.roundToInt

/**
 * Page d'une ville, sur le modèle de Meteoblue : conditions actuelles, bandeau des heures
 * restantes de la journée, puis les 14 jours. Toucher un jour ouvre son détail heure par heure.
 */
@Composable
fun ForecastScreen(
    state: MeteoUiState,
    place: Place,
    onOpenPlaces: () -> Unit,
    onOpenRadar: () -> Unit,
    onOpenMeteograms: () -> Unit,
    onOpenDay: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRefresh: () -> Unit,
) {
    Scaffold(
        topBar = {
            NavyTopBar(
                title = { SearchPill(place.name, onOpenPlaces) },
                actions = {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            if (state.isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            contentDescription = if (state.isFavorite) "Retirer des favoris" else "Ajouter aux favoris",
                        )
                    }
                    Box {
                        var menu by remember { mutableStateOf(false) }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Plus") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Actualiser") },
                                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                                onClick = { menu = false; onRefresh() },
                            )
                            DropdownMenuItem(
                                text = { Text("Réglages") },
                                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                onClick = { menu = false; onOpenSettings() },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val forecast = state.forecast
        PullToRefreshBox(
            isRefreshing = forecast.loading && forecast.data != null,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val data = forecast.data
            when {
                data == null && forecast.error != null -> ErrorState(forecast.error, onRefresh)
                data == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "status") { StatusBanner(forecast) }
                    item(key = "current") { CurrentBlock(data, place, forecast.fetchedAt, onOpenRadar, onOpenMeteograms) }
                    item(key = "strip") { HourlyStrip(data, Modifier.padding(bottom = 12.dp)) }
                    item(key = "days") { DayList(data, state.multiModel.data, onOpenDay) }
                    item(key = "attribution") { Attribution() }
                }
            }
        }
    }
}

/** Champ de recherche de la barre du haut : le nom du lieu, qui ouvre la liste des lieux. */
@Composable
private fun SearchPill(name: String, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(end = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = "Changer de lieu", tint = Color.White)
        Spacer(Modifier.width(8.dp))
        Text(name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun StatusBanner(forecast: Remote<Forecast>) {
    val message = when {
        forecast.stale -> "Hors ligne : prévisions du ${Fmt.clockOfMillis(forecast.fetchedAt)}"
        forecast.error != null -> forecast.error
        else -> null
    } ?: return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/** Température en grand, ciel et UV, heure de mise à jour ; boutons Radar et Météogrammes à droite. */
@Composable
private fun CurrentBlock(
    forecast: Forecast,
    place: Place,
    fetchedAt: Long,
    onOpenRadar: () -> Unit,
    onOpenMeteograms: () -> Unit,
) {
    val current = forecast.current ?: return
    val now = nowIndex(forecast.hourly.time, forecast.utcOffsetSeconds)
    val uv = forecast.hourly.uvIndex.getOrElse(now) { Double.NaN }
    val minutes = ((System.currentTimeMillis() - fetchedAt) / 60_000).coerceAtLeast(0)
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WeatherIcon(current.weatherCode, current.isDay, size = 64.dp)
                Spacer(Modifier.width(8.dp))
                Text(Fmt.temp(current.temperature), fontSize = 76.sp, fontWeight = FontWeight.Light)
            }
            Text(
                buildAnnotatedString {
                    append(weatherDescription(current.weatherCode))
                    if (!uv.isNaN()) {
                        append(" • UV ")
                        withStyle(SpanStyle(color = uvColor(uv), fontWeight = FontWeight.SemiBold)) {
                            append(uv.roundToInt().toString())
                        }
                    }
                },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                "Ressenti ${Fmt.temp(current.apparentTemperature)} · mise à jour " +
                    if (minutes < 1) "à l'instant" else "il y a $minutes min",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            val sub = listOfNotNull(
                place.subtitle.ifBlank { null },
                Fmt.meters(forecast.elevation.takeIf { !it.isNaN() } ?: place.elevation),
            )
            if (sub.isNotEmpty()) {
                Text(
                    sub.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SideButton(Icons.Filled.Radar, "Cartes : pluie, température, satellite, foudre, webcams", onOpenRadar)
            SideButton(Icons.Filled.BarChart, "Météogrammes", onOpenMeteograms)
        }
    }
}

@Composable
private fun SideButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(NavyCard)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(30.dp))
    }
}

/** Les 14 jours, une ligne par jour, dans une carte marine. */
@Composable
private fun DayList(forecast: Forecast, multiModel: MultiModelForecast?, onOpenDay: (Int) -> Unit) {
    val d = forecast.daily
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(NavyCard),
    ) {
        for (i in 0 until d.size) {
            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = NavyDivider)
            DayRow(forecast, multiModel, i, onClick = { onOpenDay(i) })
        }
    }
}

@Composable
private fun DayRow(forecast: Forecast, multiModel: MultiModelForecast?, i: Int, onClick: () -> Unit) {
    val d = forecast.daily
    val date = d.date[i]
    val predictability = multiModel?.let { Predictability.daily(it, date) }
    val range = multiModel?.let { Predictability.rainRange(it, date) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(54.dp)) {
            Text(Fmt.dayName(date).removeSuffix("."), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "%02d/%02d".format(date.dayOfMonth, date.monthValue),
                color = SecondaryOnNavy,
                fontFamily = PlexMono,
                fontSize = 13.sp,
            )
        }
        Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            WeatherIcon(d.weatherCode[i], true, size = 44.dp, dark = true)
            Text(
                Fmt.percent(d.precipitationProbabilityMax[i]).replace(" ", ""),
                color = RainChanceOnNavy,
                fontFamily = PlexMono,
                fontSize = 13.sp,
            )
        }
        WeatherIcon(nightWeatherCode(forecast, i), false, size = 38.dp, dark = true)
        Spacer(Modifier.width(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TemperatureBox(d.temperatureMax[i], Modifier.size(width = 50.dp, height = 30.dp), fontSize = 18.sp)
            TemperatureBox(d.temperatureMin[i], Modifier.size(width = 50.dp, height = 30.dp), fontSize = 18.sp)
        }
        Spacer(Modifier.width(6.dp))
        PredictabilityStack(predictability)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(13.dp)) {
                    drawWindArrow(center, d.windDirectionDominant[i], size.minDimension, Color.White, 1.6f * density)
                }
                Spacer(Modifier.width(4.dp))
                Text(Fmt.kmh(d.windSpeedMax[i]), color = Color.White, fontSize = 14.sp)
            }
            val (rain, heavy) = rainText(d.precipitationSum[i], range)
            IconText(Icons.Outlined.WaterDrop, rain, if (heavy) Color(0xFFFF6B5F) else Color.White)
            IconText(Icons.Outlined.WbSunny, Fmt.sunshine(d.sunshineDuration[i]), Color.White)
        }
    }
}

@Composable
private fun IconText(icon: ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = color, fontSize = 14.sp)
    }
}

/** Pluie du jour façon Meteoblue : « – », « 0-2 mm », « 5-10 mm », « >20 mm » (en rouge au-delà de 20 mm). */
private fun rainText(sum: Double, range: RainRange?): Pair<String, Boolean> {
    val low = range?.low ?: sum
    val high = range?.high ?: sum
    if (high.isNaN() || high < 0.2) return "–" to false
    if (low >= 20) return ">20 mm" to true
    if (range == null) return Fmt.mm(sum) to (sum > 20)
    val lo = low.roundToInt()
    val hi = maxOf(high.roundToInt(), lo + 1)
    return "$lo-$hi mm" to (high > 20)
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Réessayer") }
    }
}

@Composable
fun Attribution() {
    Text(
        "Données météo : Open-Meteo.com (licence CC BY 4.0), d'après les modèles de Météo-France, DWD, ECMWF, NOAA, " +
            "UK Met Office, MeteoSwiss et ECCC. Prévisibilité : accord entre ces modèles.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}
