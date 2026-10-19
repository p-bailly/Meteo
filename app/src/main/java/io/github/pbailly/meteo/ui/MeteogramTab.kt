package io.github.pbailly.meteo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.ui.theme.ChartColors
import io.github.pbailly.meteo.ui.theme.LocalChartColors
import io.github.pbailly.meteo.ui.theme.AppMono
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min

private const val METEOGRAM_DAYS = 7
private val HOUR_WIDTH = 8.dp
private val AXIS_WIDTH = 38.dp

/** Bandes horizontales du météogramme, en dp : (titre, hauteur du tracé). */
private enum class Band(val title: String?, val titleHeight: Float, val plotHeight: Float) {
    DAYS(null, 0f, 22f),
    ICONS(null, 0f, 28f),
    TEMPERATURE("Température (°C)", 18f, 110f),
    PRECIPITATION("Précipitations (mm)", 18f, 70f),
    CLOUDS("Nuages", 18f, 33f),
    WIND("Vent (km/h)", 18f, 86f),
    PRESSURE("Pression (hPa)", 18f, 50f),
}

private class BandBox(val titleTop: Float, val top: Float, val bottom: Float) {
    val height get() = bottom - top
}

private fun layoutBands(density: Float): Pair<Map<Band, BandBox>, Float> {
    var y = 0f
    val boxes = Band.entries.associateWith { band ->
        val titleTop = y
        val top = y + band.titleHeight * density
        val bottom = top + band.plotHeight * density
        y = bottom + 4f * density
        BandBox(titleTop, top, bottom)
    }
    return boxes to y
}

/** Index de l'heure courante dans la série horaire (heure locale du lieu). */
fun nowIndex(times: List<LocalDateTime>, utcOffsetSeconds: Int): Int {
    val now = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.ofTotalSeconds(utcOffsetSeconds))
        .truncatedTo(ChronoUnit.HOURS)
    val index = times.indexOf(now)
    return if (index >= 0) index else if (times.isNotEmpty() && now.isAfter(times.last())) times.lastIndex else 0
}

@Composable
fun MeteogramTab(forecast: Forecast, modifier: Modifier = Modifier) {
    val hourly = forecast.hourly
    val hours = min(hourly.size, METEOGRAM_DAYS * 24)
    if (hours == 0) {
        Text("Pas de données horaires.", modifier.padding(16.dp))
        return
    }
    val colors = LocalChartColors.current
    val density = LocalDensity.current
    val hourPx = with(density) { HOUR_WIDTH.toPx() }
    val now = remember(forecast) { nowIndex(hourly.time, forecast.utcOffsetSeconds) }
    var selected by rememberSaveable(forecast) { mutableStateOf(now) }
    val (bands, totalHeightPx) = remember(density.density) { layoutBands(density.density) }
    val totalHeight = with(density) { totalHeightPx.toDp() }
    val scroll = rememberScrollState()
    val measurer = rememberTextMeasurer()

    // Échelles calculées une fois pour toute la période affichée.
    val scales = remember(forecast) { MeteogramScales.from(forecast, hours) }

    LaunchedEffect(forecast) {
        scroll.scrollTo(max(0, ((now - 3) * hourPx).toInt()))
    }

    Column(modifier.fillMaxWidth()) {
        HourReadout(forecast, selected.coerceIn(0, hours - 1))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth()) {
            Row {
                Spacer(Modifier.width(AXIS_WIDTH))
                Box(Modifier.horizontalScroll(scroll)) {
                    Canvas(
                        Modifier
                            .size(width = HOUR_WIDTH * hours, height = totalHeight)
                            .pointerInput(hours) {
                                detectTapGestures { offset ->
                                    selected = (offset.x / hourPx).toInt().coerceIn(0, hours - 1)
                                }
                            },
                    ) {
                        drawMeteogram(forecast, hours, hourPx, bands, scales, colors, now, selected, measurer, scroll.value.toFloat())
                    }
                }
            }
            // Axes et titres restent fixes pendant le défilement horizontal.
            Canvas(Modifier.size(width = AXIS_WIDTH + 260.dp, height = totalHeight)) {
                drawAxes(bands, scales, colors, measurer, AXIS_WIDTH.toPx())
            }
        }
        Text(
            "Touchez le graphique pour lire les valeurs d'une heure. Zones grisées : nuit.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

private class MeteogramScales(
    val temperatureTicks: List<Double>,
    val precipitationTicks: List<Double>,
    val windTicks: List<Double>,
    val pressureTicks: List<Double>,
) {
    companion object {
        fun from(forecast: Forecast, hours: Int): MeteogramScales {
            val h = forecast.hourly
            fun DoubleArray.finite() = take(hours).filter { !it.isNaN() }
            val temps = h.temperature.finite() + h.apparentTemperature.finite()
            val precip = h.precipitation.finite()
            val wind = h.windSpeed.finite() + h.windGusts.finite()
            val pressure = h.pressure.finite()
            return MeteogramScales(
                temperatureTicks = niceTicks((temps.minOrNull() ?: 0.0) - 1, (temps.maxOrNull() ?: 10.0) + 1, 4),
                precipitationTicks = niceTicks(0.0, max(2.0, precip.maxOrNull() ?: 0.0), 2),
                windTicks = niceTicks(0.0, max(20.0, wind.maxOrNull() ?: 0.0), 3),
                pressureTicks = niceTicks((pressure.minOrNull() ?: 1010.0) - 1, (pressure.maxOrNull() ?: 1020.0) + 1, 3),
            )
        }
    }
}

private fun ticksScale(ticks: List<Double>, box: BandBox, topPadding: Float = 0f) =
    LinearScale(ticks.first(), ticks.last(), box.top + topPadding, box.bottom)

private fun DrawScope.drawMeteogram(
    forecast: Forecast,
    hours: Int,
    hourPx: Float,
    bands: Map<Band, BandBox>,
    scales: MeteogramScales,
    c: ChartColors,
    now: Int,
    selected: Int,
    measurer: TextMeasurer,
    scrollX: Float,
) {
    val h = forecast.hourly
    val d = density
    val x = { i: Int -> (i + 0.5f) * hourPx }
    val plotTop = bands.getValue(Band.ICONS).top
    val plotBottom = size.height

    drawRect(c.plot, Offset(0f, plotTop), Size(size.width, plotBottom - plotTop))

    // Nuit : on grise les heures où is_day = 0.
    var i = 0
    while (i < hours) {
        if (!h.isDay[i]) {
            val start = i
            while (i < hours && !h.isDay[i]) i++
            drawRect(c.night, Offset(start * hourPx, plotTop), Size((i - start) * hourPx, plotBottom - plotTop))
        } else {
            i++
        }
    }

    // Graduations horizontales.
    fun grid(ticks: List<Double>, box: BandBox, topPadding: Float = 0f) {
        val scale = ticksScale(ticks, box, topPadding)
        ticks.forEach { t -> drawLine(c.grid, Offset(0f, scale.y(t)), Offset(size.width, scale.y(t)), 1f * d) }
    }
    grid(scales.temperatureTicks, bands.getValue(Band.TEMPERATURE))
    grid(scales.precipitationTicks, bands.getValue(Band.PRECIPITATION), 14f * d)
    grid(scales.windTicks, bands.getValue(Band.WIND), 20f * d)
    grid(scales.pressureTicks, bands.getValue(Band.PRESSURE))

    // Jours : séparateurs, et nom du jour qui reste visible au bord gauche pendant le défilement.
    val daysBox = bands.getValue(Band.DAYS)
    val dayStarts = (0 until hours).filter { it == 0 || h.time[it].hour == 0 }
    dayStarts.forEachIndexed { n, start ->
        val end = dayStarts.getOrElse(n + 1) { hours }
        if (start > 0) drawLine(c.axis, Offset(start * hourPx, daysBox.top), Offset(start * hourPx, size.height), 1f * d)
        val label = measurer.measure(
            Fmt.dayShort(h.time[start].toLocalDate()),
            TextStyle(color = c.ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
        )
        val minX = start * hourPx + 6f * d
        val maxX = end * hourPx - label.size.width - 6f * d
        if (maxX >= minX) {
            val labelX = (scrollX + 6f * d).coerceIn(minX, maxX)
            drawText(label, topLeft = Offset(labelX, daysBox.top + (daysBox.height - label.size.height) / 2))
        }
    }

    // Heure sélectionnée et heure actuelle.
    drawRect(c.highlight, Offset(selected * hourPx, plotTop), Size(hourPx, plotBottom - plotTop))
    if (now in 0 until hours) {
        drawLine(
            c.secondaryInk, Offset(x(now), plotTop), Offset(x(now), plotBottom), 1f * d,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * d, 3f * d)),
        )
    }

    // Pictogrammes toutes les 3 heures.
    val iconsBox = bands.getValue(Band.ICONS)
    val iconSize = 22f * d
    for (k in 1 until hours step 3) {
        drawWeatherIcon(
            h.weatherCode[k], h.isDay[k], c.darkTheme,
            Offset(x(k) - iconSize / 2, iconsBox.top + (iconsBox.height - iconSize) / 2), iconSize,
        )
    }

    // Températures : réelle (trait plein) et ressentie (pointillés).
    val tBox = bands.getValue(Band.TEMPERATURE)
    val tScale = ticksScale(scales.temperatureTicks, tBox)
    if (scales.temperatureTicks.first() < 0 && scales.temperatureTicks.last() > 0) {
        drawLine(c.axis, Offset(0f, tScale.y(0.0)), Offset(size.width, tScale.y(0.0)), 1.5f * d)
    }
    drawSeries(h.apparentTemperature, x, tScale::y, c.temperature.copy(alpha = 0.6f), 1.5f * d, dashed = true, range = 0 until hours)
    drawSeries(h.temperature, x, tScale::y, c.temperature, 2f * d, range = 0 until hours)

    // Précipitations : barres horaires (neige en violet) et probabilité toutes les 3 h.
    val pBox = bands.getValue(Band.PRECIPITATION)
    val pScale = ticksScale(scales.precipitationTicks, pBox, 14f * d)
    val gap = 1f * d
    for (k in 0 until hours) {
        val v = h.precipitation[k]
        if (v.isNaN() || v < 0.05) continue
        val top = pScale.y(v)
        val color = if (h.snowfall[k] > 0.0) c.snow else c.precipitation
        drawRoundRect(
            color,
            topLeft = Offset(k * hourPx + gap, top),
            size = Size(hourPx - 2 * gap, pBox.bottom - top),
            cornerRadius = CornerRadius(1.5f * d),
        )
    }
    for (k in 0 until hours step 3) {
        val prob = (k until min(k + 3, hours)).maxOfOrNull { h.precipitationProbability[it].takeIf { p -> !p.isNaN() } ?: 0.0 } ?: 0.0
        if (prob >= 10) {
            drawLabel(measurer, "${prob.toInt()}%", Offset((k + 1.5f) * hourPx, pBox.top + 1f * d), c.secondaryInk, 9f, 0.5f, 0f)
        }
    }
    drawLine(c.axis, Offset(0f, pBox.bottom), Offset(size.width, pBox.bottom), 1f * d)

    // Nuages : trois rangées (hauts, moyens, bas), plus foncées quand la couverture augmente.
    val cBox = bands.getValue(Band.CLOUDS)
    val rowHeight = cBox.height / 3f
    val layers = listOf(h.cloudCoverHigh, h.cloudCoverMid, h.cloudCoverLow)
    layers.forEachIndexed { row, values ->
        for (k in 0 until hours) {
            val cover = values[k]
            if (cover.isNaN() || cover < 5) continue
            drawRect(
                c.cloud.copy(alpha = (cover / 100.0 * 0.75).toFloat()),
                Offset(k * hourPx, cBox.top + row * rowHeight + 0.5f * d),
                Size(hourPx, rowHeight - 1f * d),
            )
        }
    }

    // Vent : vitesse (trait plein), rafales (pointillés) et flèches de direction toutes les 3 h.
    val wBox = bands.getValue(Band.WIND)
    val wScale = ticksScale(scales.windTicks, wBox, 20f * d)
    for (k in 1 until hours step 3) {
        drawWindArrow(Offset(x(k), wBox.top + 9f * d), h.windDirection[k], 12f * d, c.secondaryInk, 1.5f * d)
    }
    drawSeries(h.windGusts, x, wScale::y, c.wind.copy(alpha = 0.7f), 1.5f * d, dashed = true, range = 0 until hours)
    drawSeries(h.windSpeed, x, wScale::y, c.wind, 2f * d, range = 0 until hours)

    // Pression au niveau de la mer.
    val prBox = bands.getValue(Band.PRESSURE)
    drawSeries(h.pressure, x, ticksScale(scales.pressureTicks, prBox)::y, c.pressure, 2f * d, range = 0 until hours)
}

private fun DrawScope.drawAxes(
    bands: Map<Band, BandBox>,
    scales: MeteogramScales,
    c: ChartColors,
    measurer: TextMeasurer,
    axisWidth: Float,
) {
    val d = density
    fun axis(ticks: List<Double>, box: BandBox, topPadding: Float = 0f, every: Int = 1) {
        val scale = ticksScale(ticks, box, topPadding)
        ticks.forEachIndexed { index, t ->
            if (index % every != 0) return@forEachIndexed
            val label = if (t == t.toLong().toDouble()) t.toLong().toString() else Fmt.mm(t).removeSuffix(" mm")
            drawLabel(measurer, label, Offset(axisWidth - 6f * d, scale.y(t)), c.muted, 10f, 1f, 0.5f)
        }
    }
    axis(scales.temperatureTicks, bands.getValue(Band.TEMPERATURE))
    axis(scales.precipitationTicks, bands.getValue(Band.PRECIPITATION), 14f * d)
    axis(scales.windTicks, bands.getValue(Band.WIND), 20f * d)
    axis(scales.pressureTicks, bands.getValue(Band.PRESSURE))

    val clouds = bands.getValue(Band.CLOUDS)
    listOf("hauts", "moy.", "bas").forEachIndexed { row, label ->
        drawLabel(measurer, label, Offset(axisWidth - 6f * d, clouds.top + clouds.height / 3f * (row + 0.5f)), c.muted, 9f, 1f, 0.5f)
    }

    // Titres de bande, avec la légende des séries quand il y en a deux.
    Band.entries.forEach { band ->
        val title = band.title ?: return@forEach
        val box = bands.getValue(band)
        val start = Offset(axisWidth + 4f * d, box.titleTop + band.titleHeight * d / 2)
        val layout = measurer.measure(title, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
        drawLabel(measurer, title, start, c.ink, 11f, 0f, 0.5f, bold = true)
        val legend = when (band) {
            Band.TEMPERATURE -> listOf(Triple("réelle", c.temperature, false), Triple("ressentie", c.temperature.copy(alpha = 0.6f), true))
            Band.WIND -> listOf(Triple("moyen", c.wind, false), Triple("rafales", c.wind.copy(alpha = 0.7f), true))
            Band.PRECIPITATION -> listOf(Triple("pluie", c.precipitation, false), Triple("neige", c.snow, false))
            else -> emptyList()
        }
        var lx = start.x + layout.size.width + 12f * d
        legend.forEach { (label, color, dashed) ->
            legendSwatch(Offset(lx, start.y), color, dashed, band == Band.PRECIPITATION)
            lx += 18f * d
            val labelLayout = measurer.measure(label, TextStyle(fontSize = 10.sp))
            drawLabel(measurer, label, Offset(lx, start.y), c.secondaryInk, 10f, 0f, 0.5f)
            lx += labelLayout.size.width + 10f * d
        }
    }
}

private fun DrawScope.legendSwatch(at: Offset, color: Color, dashed: Boolean, block: Boolean) {
    val d = density
    if (block) {
        drawRoundRect(color, Offset(at.x + 3f * d, at.y - 5f * d), Size(8f * d, 10f * d), CornerRadius(1.5f * d))
    } else {
        drawLine(
            color, Offset(at.x, at.y), Offset(at.x + 14f * d, at.y), 2f * d,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4f * d, 3f * d)) else null,
        )
    }
}

/** Valeurs de l'heure sélectionnée, au-dessus du graphique. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HourReadout(forecast: Forecast, index: Int) {
    val h = forecast.hourly
    val time = h.time[index]
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WeatherIcon(h.weatherCode[index], h.isDay[index], size = 36.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "${Fmt.dayLong(time.toLocalDate())} · ${Fmt.hour(time)}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        weatherDescription(h.weatherCode[index]),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Metric("Temp.", Fmt.temp(h.temperature[index]))
                Metric("Ressentie", Fmt.temp(h.apparentTemperature[index]))
                Metric("Pluie", Fmt.mm(h.precipitation[index]) + " · " + Fmt.percent(h.precipitationProbability[index]))
                if (h.snowfall[index] > 0) Metric("Neige", Fmt.cm(h.snowfall[index]))
                Metric("Vent", "${Fmt.kmh(h.windSpeed[index])} ${Fmt.compass(h.windDirection[index])}")
                Metric("Rafales", Fmt.kmh(h.windGusts[index]))
                Metric("Humidité", Fmt.percent(h.humidity[index]))
                Metric("Pression", Fmt.hPa(h.pressure[index]))
                Metric(
                    "Nuages b/m/h",
                    listOf(h.cloudCoverLow, h.cloudCoverMid, h.cloudCoverHigh)
                        .joinToString("/") { Fmt.percent(it[index]).removeSuffix(" %") } + " %",
                )
            }
        }
    }
}

@Composable
fun Metric(label: String, value: String) {
    Column {
        MetricLabel(label)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Libellé de mesure : capitales en Martian Mono, comme les axes des graphiques. */
@Composable
fun MetricLabel(label: String) {
    Text(
        label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = AppMono,
        letterSpacing = 0.6.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
