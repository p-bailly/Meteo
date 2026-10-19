package io.github.pbailly.meteo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.data.Moon
import io.github.pbailly.meteo.data.Outlook
import io.github.pbailly.meteo.data.MultiModelForecast
import io.github.pbailly.meteo.data.Place
import io.github.pbailly.meteo.data.Predictability
import io.github.pbailly.meteo.ui.theme.AppMono
import io.github.pbailly.meteo.ui.theme.LocalChartColors
import io.github.pbailly.meteo.ui.theme.OnTemperature
import io.github.pbailly.meteo.ui.theme.predictabilityColor
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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

/**
 * Détail d'une journée : choix du jour (ou glisser d'un jour à l'autre, comme sur Meteoblue), résumé et
 * prévisibilité du jour, soleil et Lune, puis la prévision heure par heure (ou par 3 heures).
 */
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
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        val date = forecast.daily.date.getOrNull(pager.currentPage)
        DetailHeader(date?.let { Fmt.dayLong(it) } ?: "", onBack) {
            SegmentedToggle(listOf("1 h", "3 h"), if (step == 1) 0 else 1, { step = if (it == 0) 1 else 3 })
        }
        PlaceLine(place.name, place.elevation ?: forecast.elevation)
        DayChips(forecast, multiModel, pager.currentPage) { scope.launch { pager.animateScrollToPage(it) } }
        HorizontalPager(pager, Modifier.weight(1f)) { day ->
            DayPage(forecast, multiModel, place, day, step)
        }
    }
}

/** Jours de la prévision ; le point sous le jour a la couleur de sa prévisibilité. */
@Composable
private fun DayChips(forecast: Forecast, multiModel: MultiModelForecast?, selected: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val d = forecast.daily
    val state = rememberLazyListState()
    LaunchedEffect(selected) { state.animateScrollToItem((selected - 3).coerceAtLeast(0)) }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
    ) {
        itemsIndexed(d.date) { k, date ->
            val active = k == selected
            val percent = multiModel?.let { Predictability.daily(it, date) }
            Column(
                Modifier
                    .width(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) colors.onBackground else colors.surfaceContainerLowest)
                    .border(1.dp, if (active) colors.onBackground else colors.outlineVariant, RoundedCornerShape(12.dp))
                    .clickable { onSelect(k) }
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MonoText(Fmt.dayName(date).removeSuffix("."), color = if (active) colors.background else colors.onSurfaceVariant, size = 10.sp)
                Text(
                    date.dayOfMonth.toString(),
                    color = if (active) colors.background else colors.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                )
                Box(
                    Modifier
                        .padding(top = 3.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(percent?.let { predictabilityColor(it) } ?: Color.Transparent),
                )
            }
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
) {
    val d = forecast.daily
    val date = d.date[day]
    val lines = remember(forecast, multiModel, day, step) { hourLines(forecast, multiModel, day, step) }
    val code = remember(forecast, day) { Outlook.dayCode(forecast, date) }
    val colors = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "summary") {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                WeatherIcon(code, true, size = 52.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(weatherDescription(code), fontWeight = FontWeight.SemiBold, fontSize = 18.sp, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        MonoText("mini ${Fmt.temp(d.temperatureMin[day])} · maxi ", size = 11.5.sp)
                        MonoText(Fmt.temp(d.temperatureMax[day]), color = colors.onBackground, size = 11.5.sp, bold = true)
                        val uv = d.uvIndexMax[day]
                        if (!uv.isNaN()) MonoText(" · UV ${uv.roundToInt()}", size = 11.5.sp)
                    }
                    MonoText(
                        "pluie ${Fmt.decimal(d.precipitationSum[day])} mm · ${Fmt.percent(d.precipitationProbabilityMax[day])}",
                        color = if (d.precipitationSum[day] >= 0.1) colors.primary else colors.onSurfaceVariant,
                        size = 11.5.sp,
                    )
                }
                PredictabilityBadge(multiModel?.let { Predictability.daily(it, date) }, 46.dp)
            }
        }
        item(key = "almanac") { AlmanacCard(forecast, place, day) }
        item(key = "hourly-title") {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (step == 1) "Heure par heure" else "Par 3 heures", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                MonoText("prévisibilité à droite")
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                ColumnLabel("HEURE", HOUR_WIDTH)
                ColumnLabel("", ICON_WIDTH)
                ColumnLabel("TEMP.", TEMP_WIDTH)
                ColumnLabel("RESS.", FEEL_WIDTH)
                ColumnLabel("VENT", null, modifier = Modifier.weight(1f))
                ColumnLabel("PLUIE", RAIN_WIDTH, TextAlign.End)
                ColumnLabel("PROB.", PROB_WIDTH, TextAlign.End)
                ColumnLabel("PRÉV.", PRED_WIDTH, TextAlign.End)
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = colors.outlineVariant)
        }
        items(lines, key = { it.index }) { line ->
            HourRow(line)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = colors.outlineVariant)
        }
        item(key = "note") {
            MonoText(
                "vent : moyen-rafales en km/h · pluie en mm · prévisibilité : accord entre 8 modèles (vert : sûr, rouge : incertain)",
                size = 10.sp,
                maxLines = 3,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }
}

private val HOUR_WIDTH = 44.dp
private val ICON_WIDTH = 34.dp
private val TEMP_WIDTH = 40.dp
private val FEEL_WIDTH = 38.dp
private val RAIN_WIDTH = 40.dp
private val PROB_WIDTH = 40.dp
private val PRED_WIDTH = 38.dp

@Composable
private fun ColumnLabel(text: String, width: Dp?, align: TextAlign = TextAlign.Start, modifier: Modifier = Modifier) {
    MonoText(text, size = 9.5.sp, align = align, modifier = if (width != null) modifier.width(width) else modifier)
}

/** Prévisibilité : carré à la couleur du niveau (vert sûr → rouge incertain), avec le pourcentage. */
@Composable
private fun PredictabilityBadge(percent: Int?, size: Dp) {
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
                .background(percent?.let { predictabilityColor(it) } ?: colors.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(percent?.let { "$it %" } ?: "–", color = OnTemperature, fontFamily = AppMono, fontWeight = FontWeight.Medium, fontSize = 12.sp)
        }
        MonoText("PRÉVIS.", size = 9.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

/** Soleil, Lune, pression et humidité de la journée, en carte blanche comme les mesures d'Aujourd'hui. */
@Composable
private fun AlmanacCard(forecast: Forecast, place: Place, day: Int) {
    val d = forecast.daily
    val date = d.date[day]
    val moon = remember(date, place.id) { Moon.times(date, place.latitude, place.longitude, forecast.utcOffsetSeconds) }
    val hours = forecast.hourly.time.indices.filter { forecast.hourly.time[it].toLocalDate() == date }
    val pressure = hours.map { forecast.hourly.pressure[it] }.filter { !it.isNaN() }.average()
    val humidity = hours.map { forecast.hourly.humidity[it] }.filter { !it.isNaN() }.average()
    val colors = MaterialTheme.colorScheme
    BorderedCard(Modifier.padding(top = 14.dp)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // Lever en grand, coucher en petit (les flèches restent dans le libellé, en petit).
            AlmanacTile(Icons.Outlined.WbTwilight, "SOLEIL ↑ ↓", Fmt.clock(d.sunrise[day]), Fmt.clock(d.sunset[day]), Modifier.weight(1f))
            VerticalDivider(color = colors.outlineVariant)
            AlmanacTile(Icons.Outlined.DarkMode, "LUNE ↑ ↓", Fmt.clock(moon.rise), Fmt.clock(moon.set), Modifier.weight(1f))
        }
        HorizontalDivider(color = colors.outlineVariant)
        Row(Modifier.height(IntrinsicSize.Min)) {
            AlmanacTile(Icons.Outlined.Compress, "PRESSION", if (pressure.isNaN()) "–" else pressure.roundToInt().toString(), "hPa", Modifier.weight(1f))
            VerticalDivider(color = colors.outlineVariant)
            AlmanacTile(Icons.Outlined.WaterDrop, "HUMIDITÉ", if (humidity.isNaN()) "–" else humidity.roundToInt().toString(), "%", Modifier.weight(1f))
        }
    }
}

@Composable
private fun AlmanacTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, note: String, modifier: Modifier) {
    Column(modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            MonoText(label, size = 10.sp)
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 3.dp)) {
            Text(value, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            MonoText(note, size = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

/** Heure, ciel, température, ressenti, vent moyen et rafales, pluie, probabilité et prévisibilité. */
@Composable
private fun HourRow(line: HourLine) {
    val colors = MaterialTheme.colorScheme
    val chart = LocalChartColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText(line.label, color = colors.onBackground, size = 12.5.sp, bold = true, modifier = Modifier.width(HOUR_WIDTH))
        Box(Modifier.width(ICON_WIDTH)) { WeatherIcon(line.weatherCode, line.isDay, size = 28.dp) }
        Text(Fmt.temp(line.temperature), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.width(TEMP_WIDTH))
        MonoText(Fmt.temp(line.apparent), size = 11.5.sp, modifier = Modifier.width(FEEL_WIDTH))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(12.dp)) {
                drawWindArrow(center, line.windDirection, size.minDimension, chart.secondaryInk, 1.4f * density)
            }
            Spacer(Modifier.width(5.dp))
            MonoText("${line.windSpeed.roundToInt()}-${line.windGusts.roundToInt()}", color = colors.onBackground, size = 11.5.sp)
        }
        val wet = !line.precipitation.isNaN() && line.precipitation >= 0.05
        MonoText(
            hourRain(line.precipitation).removeSuffix(" mm"),
            color = if (wet) colors.primary else colors.onSurfaceVariant,
            bold = wet,
            size = 11.5.sp,
            align = TextAlign.End,
            modifier = Modifier.width(RAIN_WIDTH),
        )
        val likely = !line.probability.isNaN() && line.probability >= 50
        MonoText(
            Fmt.percent(line.probability).replace(" ", ""),
            color = if (likely) colors.primary else colors.onSurfaceVariant,
            bold = likely,
            size = 11.5.sp,
            align = TextAlign.End,
            modifier = Modifier.width(PROB_WIDTH),
        )
        Box(Modifier.width(PRED_WIDTH), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier
                    .size(width = 30.dp, height = 22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(line.predictability?.let { predictabilityColor(it) } ?: colors.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(line.predictability?.toString() ?: "–", color = OnTemperature, fontFamily = AppMono, fontSize = 10.5.sp)
            }
        }
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
            label = "%02d h".format(h.time[i].hour),
            temperature = h.temperature[i],
            apparent = h.apparentTemperature[i],
            weatherCode = if (block.size == 1) h.weatherCode[i] else Outlook.representativeCode(forecast, block),
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
