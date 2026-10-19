package io.github.pbailly.meteo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.data.Place
import io.github.pbailly.meteo.ui.theme.HeaderBlue
import io.github.pbailly.meteo.ui.theme.HeaderBlueDark
import io.github.pbailly.meteo.ui.theme.LocalChartColors

private val TABS = listOf("Heures", "14 jours", "Météogramme", "MultiModèle")
private const val MULTI_MODEL_TAB = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForecastScreen(
    state: MeteoUiState,
    place: Place,
    onOpenPlaces: () -> Unit,
    onOpenRadar: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRefresh: () -> Unit,
    onMultiModelNeeded: () -> Unit,
    onRetryMultiModel: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val header = if (isSystemInDarkTheme()) HeaderBlueDark else HeaderBlue

    LaunchedEffect(tab, place.id) {
        if (tab == MULTI_MODEL_TAB) onMultiModelNeeded()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(place.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(place.subtitle.ifBlank { null }, Fmt.meters(state.forecast.data?.elevation ?: place.elevation))
                        if (sub.isNotEmpty()) {
                            Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenPlaces) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Lieux") }
                },
                actions = {
                    IconButton(onClick = onOpenRadar) { Icon(Icons.Filled.Radar, contentDescription = "Radar de pluie") }
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            if (state.isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            contentDescription = if (state.isFavorite) "Retirer des favoris" else "Ajouter aux favoris",
                        )
                    }
                    IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Actualiser") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = header,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
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
                    item(key = "current") { CurrentHeader(data) }
                    item(key = "strip") { HourlyStrip(data, Modifier.padding(bottom = 12.dp)) }
                    stickyHeader(key = "tabs") {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                                TABS.forEachIndexed { index, title ->
                                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title, maxLines = 1) })
                                }
                            }
                        }
                    }
                    item(key = "tab-space") { Spacer(Modifier.height(12.dp)) }
                    when (tab) {
                        0 -> hourlyItems(data)
                        1 -> item(key = "days") { DaysTab(data) }
                        2 -> item(key = "meteogram") { MeteogramTab(data) }
                        else -> item(key = "multimodel") { MultiModelTab(state.multiModel, onRetry = onRetryMultiModel) }
                    }
                    item(key = "attribution") { Attribution() }
                }
            }
        }
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

/** Conditions actuelles en tête d'écran. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrentHeader(forecast: Forecast) {
    val current = forecast.current ?: return
    val today = forecast.daily
    val colors = LocalChartColors.current
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherIcon(current.weatherCode, current.isDay, size = 72.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(Fmt.temp(current.temperature), fontSize = 48.sp, fontWeight = FontWeight.Light)
                Text(weatherDescription(current.weatherCode), style = MaterialTheme.typography.titleMedium)
                Text(
                    "Ressenti ${Fmt.temp(current.apparentTemperature)}" +
                        if (today.size > 0) " · ${Fmt.temp(today.temperatureMax[0])} / ${Fmt.temp(today.temperatureMin[0])}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column {
                Text("Vent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(14.dp)) {
                        drawWindArrow(center, current.windDirection, size.minDimension, colors.secondaryInk, 1.5f * density)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("${Fmt.kmh(current.windSpeed)} ${Fmt.compass(current.windDirection)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Metric("Rafales", Fmt.kmh(current.windGusts))
            Metric("Humidité", Fmt.percent(current.humidity))
            Metric("Pression", Fmt.hPa(current.pressure))
            if (today.size > 0) {
                Metric("Soleil", "${Fmt.clock(today.sunrise[0])} – ${Fmt.clock(today.sunset[0])}")
            }
        }
    }
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
private fun Attribution() {
    Text(
        "Données météo : Open-Meteo.com (licence CC BY 4.0), d'après les modèles de Météo-France, DWD, ECMWF, NOAA, " +
            "UK Met Office, MeteoSwiss et ECCC.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}
