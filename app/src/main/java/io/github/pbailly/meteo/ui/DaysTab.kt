package io.github.pbailly.meteo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.data.MultiModelForecast
import io.github.pbailly.meteo.data.Predictability
import io.github.pbailly.meteo.ui.theme.predictabilityColor
import io.github.pbailly.meteo.ui.theme.LocalChartColors
import kotlin.math.max

/** Onglet « 14 jours » des météogrammes : températures, prévisibilité et pluies, une colonne par jour. */
@Composable
fun DaysTab(forecast: Forecast, multiModel: MultiModelForecast?, modifier: Modifier = Modifier) {
    val daily = forecast.daily
    if (daily.size == 0) {
        Text("Pas de prévisions journalières.", modifier.padding(16.dp))
        return
    }
    var selected by rememberSaveable(forecast) { mutableIntStateOf(0) }
    val predictability = remember(forecast, multiModel) {
        daily.date.map { date -> multiModel?.let { Predictability.daily(it, date) } }
    }
    Card(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(vertical = 12.dp)) {
            DaySummaryLine(forecast, selected, predictability[selected], Modifier.padding(horizontal = 12.dp))
            Spacer(Modifier.height(8.dp))
            FourteenDayChart(forecast, predictability, selected, onSelect = { selected = it })
        }
    }
}

@Composable
private fun DaySummaryLine(forecast: Forecast, index: Int, predictability: Int?, modifier: Modifier = Modifier) {
    val d = forecast.daily
    Text(
        "${Fmt.dayLong(d.date[index])} : ${Fmt.temp(d.temperatureMax[index])} / ${Fmt.temp(d.temperatureMin[index])}, " +
            "${Fmt.mm(d.precipitationSum[index])} (${Fmt.percent(d.precipitationProbabilityMax[index])}), " +
            "vent ${Fmt.kmh(d.windSpeedMax[index])}" +
            (predictability?.let { ", prévisibilité $it %" } ?: ""),
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier,
    )
}

/** Une colonne par jour : pictogramme, prévisibilité, barre min → max, cumul de pluie. */
@Composable
private fun FourteenDayChart(forecast: Forecast, predictability: List<Int?>, selected: Int, onSelect: (Int) -> Unit) {
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
            .height(278.dp)
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
        val predictTop = 56f * dp
        val tempTop = 92f * dp
        val tempBottom = 208f * dp
        val rainTop = 220f * dp
        val rainBottom = size.height - 2f * dp
        val tScale = LinearScale(tempTicks.first(), tempTicks.last(), tempTop, tempBottom)
        val rScale = LinearScale(0.0, rainMax, rainTop + 12f * dp, rainBottom)

        drawRect(colors.highlight, Offset(axis + selected * colWidth, 0f), Size(colWidth, size.height))
        drawLabel(measurer, "%", Offset(axis - 4f * dp, predictTop + 2f * dp), colors.muted, 10f, 1f, 0.5f)
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

            // Prévisibilité : petite barre colorée et pourcentage, comme sur Meteoblue.
            predictability.getOrNull(i)?.let { p ->
                val barWidth = colWidth - 6f * dp
                drawRoundRect(
                    predictabilityColor(p),
                    Offset(cx(i) - barWidth / 2, predictTop),
                    Size(barWidth, 5f * dp),
                    CornerRadius(2f * dp),
                )
                drawLabel(measurer, "$p%", Offset(cx(i), predictTop + 8f * dp), colors.secondaryInk, 8f, 0.5f, 0f)
            }

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
