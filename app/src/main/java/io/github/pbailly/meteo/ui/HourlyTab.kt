package io.github.pbailly.meteo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.ui.theme.HeaderBlue
import io.github.pbailly.meteo.ui.theme.LocalChartColors
import io.github.pbailly.meteo.ui.theme.RainChanceOnNavy
import kotlin.math.roundToInt

private const val STRIP_HOURS = 24
private const val LIST_DAYS = 7

/** Bandeau bleu marine des prochaines heures : heure, pictogramme, probabilité de pluie, température. */
@Composable
fun HourlyStrip(forecast: Forecast, modifier: Modifier = Modifier) {
    val h = forecast.hourly
    val start = nowIndex(h.time, forecast.utcOffsetSeconds)
    val hours = (start until minOf(start + STRIP_HOURS, h.size)).toList()
    if (hours.isEmpty()) return
    LazyRow(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(HeaderBlue),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 12.dp),
    ) {
        items(hours) { i ->
            Column(Modifier.width(58.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (i == start) "Maint." else "%02d".format(h.time[i].hour),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                WeatherIcon(h.weatherCode[i], h.isDay[i], size = 40.dp, dark = true)
                Spacer(Modifier.height(4.dp))
                Text(
                    Fmt.percent(h.precipitationProbability[i]).replace(" ", ""),
                    color = RainChanceOnNavy,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    Fmt.temp(h.temperature[i]),
                    color = Color.White,
                    fontSize = 22.sp,
                )
            }
        }
    }
}

/**
 * Onglet « Heures » : une ligne par heure sur 7 jours, avec la probabilité de pluie en barre.
 * Les lignes sont ajoutées directement à la liste de l'écran pour rester paresseuses (LazyColumn).
 */
fun LazyListScope.hourlyItems(forecast: Forecast) {
    val h = forecast.hourly
    val start = nowIndex(h.time, forecast.utcOffsetSeconds)
    val end = minOf(h.size, start + LIST_DAYS * 24)
    val today = forecast.daily.date.firstOrNull()

    item(key = "hourly-legend") {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderCell("Heure", 44.dp, TextAlign.Start)
            Spacer(Modifier.width(32.dp))
            HeaderCell("Temp.", 44.dp, TextAlign.End)
            Spacer(Modifier.width(12.dp))
            HeaderCell("Risque de pluie", null, TextAlign.Start, Modifier.weight(1f))
            HeaderCell("Pluie", 56.dp, TextAlign.End)
            HeaderCell("Vent", 72.dp, TextAlign.End)
        }
    }
    for (i in start until end) {
        val time = h.time[i]
        if (i == start || time.hour == 0) {
            val date = time.toLocalDate()
            val dayIndex = forecast.daily.date.indexOf(date)
            item(key = "day-$date") {
                DayHeader(
                    title = if (today != null) Fmt.relativeDay(date, today) + " · " + Fmt.dayLong(date) else Fmt.dayLong(date),
                    sun = if (dayIndex >= 0) {
                        "${Fmt.clock(forecast.daily.sunrise[dayIndex])} – ${Fmt.clock(forecast.daily.sunset[dayIndex])}"
                    } else {
                        null
                    },
                )
            }
        }
        item(key = "hour-$i") { HourRow(forecast, i, isNow = i == start) }
    }
}

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp?, align: TextAlign, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        modifier = if (width != null) modifier.width(width) else modifier,
    )
}

@Composable
private fun DayHeader(title: String, sun: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (sun != null) {
            Text(
                "Soleil $sun",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HourRow(forecast: Forecast, i: Int, isNow: Boolean) {
    val h = forecast.hourly
    val colors = LocalChartColors.current
    val probability = h.precipitationProbability[i]
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (isNow) colors.highlight else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (isNow) "Maint." else Fmt.hour(h.time[i]),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isNow) FontWeight.SemiBold else null,
                modifier = Modifier.width(44.dp),
            )
            WeatherIcon(h.weatherCode[i], h.isDay[i], size = 32.dp)
            Text(
                Fmt.temp(h.temperature[i]),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(44.dp),
                textAlign = TextAlign.End,
            )
            Spacer(Modifier.width(12.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                ProbabilityBar(probability, Modifier.weight(1f).height(8.dp))
                Text(
                    if (probability.isNaN()) "–" else "${probability.roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(40.dp),
                    textAlign = TextAlign.End,
                )
            }
            Text(
                if (h.precipitation[i] < 0.05) "–" else Fmt.mm(h.precipitation[i]),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(56.dp),
                textAlign = TextAlign.End,
            )
            Row(Modifier.width(72.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(12.dp)) {
                    drawWindArrow(center, h.windDirection[i], size.minDimension, colors.secondaryInk, 1.5f * density)
                }
                Spacer(Modifier.width(3.dp))
                Text(Fmt.kmh(h.windSpeed[i]), style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Barre horizontale 0–100 % ; la part remplie est la probabilité de pluie. */
@Composable
private fun ProbabilityBar(probability: Double, modifier: Modifier = Modifier) {
    val colors = LocalChartColors.current
    val fraction = if (probability.isNaN()) 0f else (probability / 100.0).toFloat().coerceIn(0f, 1f)
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.grid),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.precipitation),
            )
        }
    }
}
