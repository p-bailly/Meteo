package io.github.pbailly.meteo.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.ui.theme.LocalChartColors
import kotlin.math.max

/** Onglet « 14 jours » : graphique des températures et pluies, puis une ligne détaillée par jour. */
@Composable
fun DaysTab(forecast: Forecast, modifier: Modifier = Modifier) {
    val daily = forecast.daily
    if (daily.size == 0) {
        Text("Pas de prévisions journalières.", modifier.padding(16.dp))
        return
    }
    var selected by rememberSaveable(forecast) { mutableIntStateOf(0) }
    var expanded by rememberSaveable(forecast) { mutableIntStateOf(-1) }
    val today = daily.date.first()

    Column(modifier.fillMaxWidth()) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(vertical = 12.dp)) {
                DaySummaryLine(forecast, selected, Modifier.padding(horizontal = 12.dp))
                Spacer(Modifier.height(8.dp))
                FourteenDayChart(forecast, selected, onSelect = { selected = it })
            }
        }
        Spacer(Modifier.height(12.dp))
        for (i in 0 until daily.size) {
            DayRow(
                forecast = forecast,
                index = i,
                label = Fmt.relativeDay(daily.date[i], today),
                expanded = expanded == i,
                onClick = { expanded = if (expanded == i) -1 else i },
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun DaySummaryLine(forecast: Forecast, index: Int, modifier: Modifier = Modifier) {
    val d = forecast.daily
    Text(
        "${Fmt.dayLong(d.date[index])} : ${Fmt.temp(d.temperatureMax[index])} / ${Fmt.temp(d.temperatureMin[index])}, " +
            "${Fmt.mm(d.precipitationSum[index])} (${Fmt.percent(d.precipitationProbabilityMax[index])}), " +
            "vent ${Fmt.kmh(d.windSpeedMax[index])}",
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier,
    )
}

/** Une colonne par jour : pictogramme, barre min → max, cumul de pluie. */
@Composable
private fun FourteenDayChart(forecast: Forecast, selected: Int, onSelect: (Int) -> Unit) {
    val d = forecast.daily
    val n = d.size
    val colors = LocalChartColors.current
    val measurer = rememberTextMeasurer()
    val tempTicks = remember(forecast) {
        val lows = d.temperatureMin.filter { !it.isNaN() }
        val highs = d.temperatureMax.filter { !it.isNaN() }
        niceTicks((lows.minOrNull() ?: 0.0) - 1, (highs.maxOrNull() ?: 10.0) + 1, 4)
    }
    val rainMax = remember(forecast) { max(5.0, d.precipitationSum.filter { !it.isNaN() }.maxOrNull() ?: 0.0) }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(250.dp)
            .pointerInput(n) {
                detectTapGestures { offset ->
                    val axis = 30.dp.toPx()
                    val column = ((offset.x - axis) / ((size.width - axis - 8.dp.toPx()) / n)).toInt()
                    onSelect(column.coerceIn(0, n - 1))
                }
            },
    ) {
        val dp = density
        val axis = 30f * dp
        val colWidth = (size.width - axis - 8f * dp) / n
        val cx = { i: Int -> axis + (i + 0.5f) * colWidth }
        val labelsTop = 0f
        val iconsTop = 30f * dp
        val tempTop = 64f * dp
        val tempBottom = 180f * dp
        val rainTop = 192f * dp
        val rainBottom = size.height - 2f * dp
        val tScale = LinearScale(tempTicks.first(), tempTicks.last(), tempTop, tempBottom)
        val rScale = LinearScale(0.0, rainMax, rainTop + 12f * dp, rainBottom)

        drawRect(colors.highlight, Offset(axis + selected * colWidth, 0f), Size(colWidth, size.height))
        tempTicks.forEach { t ->
            val y = tScale.y(t)
            drawLine(colors.grid, Offset(axis, y), Offset(size.width, y), 1f * dp)
            drawLabel(measurer, t.toInt().toString(), Offset(axis - 4f * dp, y), colors.muted, 10f, 1f, 0.5f)
        }
        drawLabel(measurer, "mm", Offset(axis - 4f * dp, rainBottom), colors.muted, 10f, 1f, 1f)
        drawLine(colors.axis, Offset(axis, rainBottom), Offset(size.width, rainBottom), 1f * dp)

        for (i in 0 until n) {
            val date = d.date[i]
            val weekend = date.dayOfWeek.value >= 6
            drawLabel(measurer, Fmt.dayName(date), Offset(cx(i), labelsTop), if (weekend) colors.ink else colors.secondaryInk, 9f, 0.5f, 0f, bold = weekend)
            drawLabel(measurer, date.dayOfMonth.toString(), Offset(cx(i), labelsTop + 13f * dp), colors.secondaryInk, 9f, 0.5f, 0f)

            val iconSize = minOf(colWidth - 2f * dp, 22f * dp)
            drawWeatherIcon(d.weatherCode[i], true, colors.darkTheme, Offset(cx(i) - iconSize / 2, iconsTop), iconSize)

            val hi = d.temperatureMax[i]
            val lo = d.temperatureMin[i]
            if (!hi.isNaN() && !lo.isNaN()) {
                val barWidth = minOf(8f * dp, colWidth * 0.4f)
                val top = tScale.y(hi)
                val bottom = tScale.y(lo)
                drawRoundRect(
                    colors.temperature,
                    Offset(cx(i) - barWidth / 2, top),
                    Size(barWidth, max(bottom - top, 2f * dp)),
                    CornerRadius(barWidth / 2),
                )
                drawLabel(measurer, Fmt.temp(hi), Offset(cx(i), top - 2f * dp), colors.ink, 9f, 0.5f, 1f)
                drawLabel(measurer, Fmt.temp(lo), Offset(cx(i), bottom + 2f * dp), colors.secondaryInk, 9f, 0.5f, 0f)
            }

            val rain = d.precipitationSum[i]
            if (!rain.isNaN() && rain >= 0.1) {
                val top = rScale.y(rain)
                val barWidth = colWidth - 4f * dp
                drawRoundRect(
                    colors.precipitation,
                    Offset(cx(i) - barWidth / 2, top),
                    Size(barWidth, rainBottom - top),
                    CornerRadius(2f * dp),
                )
                val text = if (rain >= 10) rain.toInt().toString() else String.format(java.util.Locale.FRANCE, "%.1f", rain)
                drawLabel(measurer, text, Offset(cx(i), top - 1f * dp), colors.secondaryInk, 8f, 0.5f, 1f)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayRow(forecast: Forecast, index: Int, label: String, expanded: Boolean, onClick: () -> Unit) {
    val d = forecast.daily
    val colors = LocalChartColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .animateContentSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(96.dp))
            WeatherIcon(d.weatherCode[index], true, size = 34.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                Fmt.temp(d.temperatureMax[index]),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(36.dp),
                textAlign = TextAlign.End,
            )
            Text(
                Fmt.temp(d.temperatureMin[index]),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(36.dp),
                textAlign = TextAlign.End,
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.mm(d.precipitationSum[index]), style = MaterialTheme.typography.bodyMedium)
                Text(
                    Fmt.percent(d.precipitationProbabilityMax[index]),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(64.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(14.dp)) {
                        drawWindArrow(center, d.windDirectionDominant[index], size.minDimension, colors.secondaryInk, 1.5f * density)
                    }
                    Spacer(Modifier.width(2.dp))
                    Text(Fmt.kmh(d.windSpeedMax[index]).removeSuffix(" km/h"), style = MaterialTheme.typography.bodyMedium)
                }
                Text("km/h", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Metric("Ciel", weatherDescription(d.weatherCode[index]))
                Metric("Soleil", Fmt.sunshine(d.sunshineDuration[index]))
                Metric("Rafales", Fmt.kmh(d.windGustsMax[index]))
                Metric("Vent de", Fmt.compass(d.windDirectionDominant[index]))
                Metric("UV max", Fmt.uv(d.uvIndexMax[index]))
                Metric("Lever", Fmt.clock(d.sunrise[index]))
                Metric("Coucher", Fmt.clock(d.sunset[index]))
            }
            Spacer(Modifier.height(8.dp))
            HourlyForDay(forecast, index)
        }
    }
}

/** Détail toutes les 3 heures pour un jour. */
@Composable
private fun HourlyForDay(forecast: Forecast, dayIndex: Int) {
    val h = forecast.hourly
    val day = forecast.daily.date[dayIndex]
    val indices = h.time.indices.filter { h.time[it].toLocalDate() == day && h.time[it].hour % 3 == 0 }
    if (indices.isEmpty()) return
    Column {
        for (i in indices) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.hour(h.time[i]), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(36.dp))
                WeatherIcon(h.weatherCode[i], h.isDay[i], size = 24.dp)
                Text(Fmt.temp(h.temperature[i]), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
                Text(Fmt.mm(h.precipitation[i]), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp), textAlign = TextAlign.End)
                Text(Fmt.percent(h.precipitationProbability[i]), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
                Spacer(Modifier.weight(1f))
                Text(
                    "${Fmt.kmh(h.windSpeed[i])} ${Fmt.compass(h.windDirection[i])}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
