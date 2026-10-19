package io.github.pbailly.meteo.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Umbrella
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.data.Moon
import io.github.pbailly.meteo.data.MultiModelForecast
import io.github.pbailly.meteo.data.Place
import io.github.pbailly.meteo.data.Predictability
import io.github.pbailly.meteo.ui.theme.PlexMono
import io.github.pbailly.meteo.ui.theme.RainChanceOnNavy
import io.github.pbailly.meteo.ui.theme.SecondaryOnNavy
import io.github.pbailly.meteo.ui.theme.uvColor
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val WEEKDAY = DateTimeFormatter.ofPattern("EEEE", Locale.FRANCE)

/** Une ligne de la prévision horaire (une heure, ou un bloc de 3 heures). */
private class HourLine(
    val index: Int,
    val label: String,
    val temperature: Double,
    val apparent: Double,
    val weatherCode: Int,
    val isDay: Boolean,
    val windDirection: Double,
    val windSpeed: Double,
    val windGusts: Double,
    val precipitation: Double,
    val probability: Double,
    val predictability: Int?,
)

/** Détail d'une journée : on glisse d'un jour à l'autre, comme sur Meteoblue. */
@Composable
fun DayDetailScreen(
    forecast: Forecast,
    multiModel: MultiModelForecast?,
    place: Place,
    initialDay: Int,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val days = forecast.daily.size
    val pager = rememberPagerState(initialPage = initialDay.coerceIn(0, (days - 1).coerceAtLeast(0))) { days }
    var step by rememberSaveable { mutableIntStateOf(1) }

    Scaffold(
        topBar = {
            NavyTopBar(
                title = { Text(place.name, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PageDots(count = days, current = pager.currentPage)
            HorizontalPager(pager, Modifier.weight(1f)) { day ->
                DayPage(forecast, multiModel, place, day, step, onStepChange = { step = it })
            }
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        for (i in 0 until count) {
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (i == current) 9.dp else 6.dp)
                    .clip(CircleShape)
                    .background(if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
            )
        }
    }
}

@Composable
private fun DayPage(
    forecast: Forecast,
    multiModel: MultiModelForecast?,
    place: Place,
    day: Int,
    step: Int,
    onStepChange: (Int) -> Unit,
) {
    val d = forecast.daily
    val date = d.date[day]
    val lines = remember(forecast, multiModel, day, step) { hourLines(forecast, multiModel, day, step) }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("%02d/%02d".format(date.dayOfMonth, date.monthValue), fontSize = 26.sp, fontFamily = PlexMono)
                Text(date.format(WEEKDAY), fontSize = 30.sp, fontWeight = FontWeight.Light)
                val uv = d.uvIndexMax[day]
                Text(
                    buildAnnotatedString {
                        append(weatherDescription(d.weatherCode[day]))
                        if (!uv.isNaN()) {
                            append(" • UV ")
                            withStyle(SpanStyle(color = uvColor(uv), fontWeight = FontWeight.SemiBold)) { append(uv.roundToInt().toString()) }
                        }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        item(key = "almanac") { AlmanacCard(forecast, place, day) }
        item(key = "hourly-header") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(NavyCard)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("PRÉVISION HORAIRE", color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                listOf(1, 3).forEach { s ->
                    Text(
                        "${s}h",
                        color = if (s == step) Color.White else SecondaryOnNavy,
                        fontFamily = PlexMono,
                        fontWeight = if (s == step) FontWeight.SemiBold else null,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (s == step) Color.White.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable { onStepChange(s) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
        items(lines, key = { it.index }) { line ->
            Column(Modifier.padding(horizontal = 16.dp).background(NavyCard)) {
                HourRow(line)
                HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = NavyDivider)
            }
        }
        item(key = "hourly-footer") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(12.dp)
                    .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                    .background(NavyCard),
            )
        }
        item(key = "note") {
            Text(
                "À droite, la prévisibilité : vert quand les modèles sont d'accord, rouge quand ils divergent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/** Soleil, Lune, pression et humidité de la journée. */
@Composable
private fun AlmanacCard(forecast: Forecast, place: Place, day: Int) {
    val d = forecast.daily
    val date = d.date[day]
    val moon = remember(date, place.id) { Moon.times(date, place.latitude, place.longitude, forecast.utcOffsetSeconds) }
    val hours = forecast.hourly.time.indices.filter { forecast.hourly.time[it].toLocalDate() == date }
    val pressure = hours.map { forecast.hourly.pressure[it] }.filter { !it.isNaN() }.average()
    val humidity = hours.map { forecast.hourly.humidity[it] }.filter { !it.isNaN() }.average()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(NavyCard)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RiseSet(Icons.Outlined.WbSunny, up = true, Fmt.clock(d.sunrise[day]))
            RiseSet(Icons.Outlined.WbSunny, up = false, Fmt.clock(d.sunset[day]))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RiseSet(Icons.Outlined.DarkMode, up = true, Fmt.clock(moon.rise))
            RiseSet(Icons.Outlined.DarkMode, up = false, Fmt.clock(moon.set))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoLine(Icons.Outlined.Compress, Fmt.hPa(pressure))
            InfoLine(Icons.Outlined.WaterDrop, Fmt.percent(humidity))
        }
    }
}

@Composable
private fun RiseSet(icon: ImageVector, up: Boolean, time: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Icon(
            if (up) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
            contentDescription = if (up) "lever" else "coucher",
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(time, color = Color.White, fontFamily = PlexMono, fontSize = 17.sp)
    }
}

@Composable
private fun InfoLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, fontFamily = PlexMono, fontSize = 17.sp)
    }
}

/** Heure et température, ciel, ressenti, vent, pluie, probabilité et prévisibilité. */
@Composable
private fun HourRow(line: HourLine) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(60.dp).clip(RoundedCornerShape(6.dp))) {
            Text(
                line.label,
                color = Color.White,
                fontFamily = PlexMono,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(Color(0xFF1D4A70)).padding(vertical = 2.dp),
            )
            TemperatureBox(line.temperature, Modifier.fillMaxWidth().height(34.dp), fontSize = 19.sp)
        }
        Spacer(Modifier.width(8.dp))
        WeatherIcon(line.weatherCode, line.isDay, size = 46.dp, dark = true)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.width(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.PanTool, contentDescription = "Ressenti", tint = Color.White, modifier = Modifier.size(18.dp))
            Text(Fmt.temp(line.apparent), color = Color.White, fontSize = 16.sp)
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(14.dp)) {
                    drawWindArrow(center, line.windDirection, size.minDimension, Color.White, 1.6f * density)
                }
                Spacer(Modifier.width(6.dp))
                Text("${line.windSpeed.roundToInt()}-${line.windGusts.roundToInt()}", color = Color.White, fontFamily = PlexMono, fontSize = 15.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WaterDrop, contentDescription = "Pluie", tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(hourRain(line.precipitation), color = Color.White, fontFamily = PlexMono, fontSize = 15.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Umbrella, contentDescription = "Probabilité de pluie", tint = RainChanceOnNavy, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(Fmt.percent(line.probability), color = RainChanceOnNavy, fontFamily = PlexMono, fontSize = 15.sp)
            }
        }
        PredictabilitySquare(line.predictability, size = 44.dp)
    }
}

/** « – » sans pluie, « < 1 mm », sinon le cumul arrondi. */
private fun hourRain(mm: Double): String = when {
    mm.isNaN() || mm < 0.05 -> "–"
    mm < 1 -> "< 1 mm"
    else -> "${mm.roundToInt()} mm"
}

/** Lignes du jour : à partir de l'heure en cours pour aujourd'hui ; par heure ou par blocs de 3 heures. */
private fun hourLines(forecast: Forecast, multiModel: MultiModelForecast?, day: Int, step: Int): List<HourLine> {
    val h = forecast.hourly
    val date = forecast.daily.date[day]
    val now = nowIndex(h.time, forecast.utcOffsetSeconds)
    val indices = h.time.indices.filter { h.time[it].toLocalDate() == date && (day > 0 || it >= now) }
    val starts = if (step == 1) indices else indices.filter { h.time[it].hour % 3 == 0 || it == indices.first() }
    return starts.map { i ->
        val block = (i until minOf(i + step, h.size)).filter { h.time[it].toLocalDate() == date }
        val rain = block.map { h.precipitation[it] }.filter { !it.isNaN() }
        HourLine(
            index = i,
            label = "%02d:00".format(h.time[i].hour),
            temperature = h.temperature[i],
            apparent = h.apparentTemperature[i],
            weatherCode = block.maxOf { h.weatherCode[it] },
            isDay = h.isDay[i],
            windDirection = h.windDirection[i],
            windSpeed = block.maxOf { h.windSpeed[it].takeIf { v -> !v.isNaN() } ?: 0.0 },
            windGusts = block.maxOf { h.windGusts[it].takeIf { v -> !v.isNaN() } ?: 0.0 },
            precipitation = if (rain.isEmpty()) Double.NaN else rain.sum(),
            probability = block.map { h.precipitationProbability[it] }.filter { !it.isNaN() }.maxOrNull() ?: Double.NaN,
            predictability = multiModel?.let { mm ->
                block.mapNotNull { Predictability.hourly(mm, h.time[it]) }.minOrNull()
            },
        )
    }
}
