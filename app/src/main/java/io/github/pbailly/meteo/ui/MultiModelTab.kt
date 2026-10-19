package io.github.pbailly.meteo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pbailly.meteo.data.ModelSeries
import io.github.pbailly.meteo.data.MultiModelForecast
import io.github.pbailly.meteo.data.WeatherModel
import io.github.pbailly.meteo.ui.theme.LocalChartColors
import androidx.compose.foundation.background
import kotlin.math.max

@Composable
fun MultiModelTab(
    remote: Remote<MultiModelForecast>,
    hidden: Set<WeatherModel>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val data = remote.data
    when {
        data != null -> MultiModelContent(remember(data) { data.firstDays(DISPLAY_DAYS) }, hidden, modifier)
        remote.error != null -> Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(remote.error, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("Réessayer") }
        }
        else -> Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

private class ModelChartSpec(
    val title: String,
    val values: (ModelSeries) -> DoubleArray,
    val minRange: Double,
    val fromZero: Boolean,
    /** Affiche le cumul au fil des heures (calculé après la moyenne, pour qu'un modèle qui s'arrête ne la fasse pas sauter). */
    val cumulative: Boolean = false,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiModelContent(mm: MultiModelForecast, hidden: Set<WeatherModel>, modifier: Modifier) {
    val colors = LocalChartColors.current
    val available = remember(mm) { WeatherModel.entries.filter { mm.series[it]?.hasData == true } }
    val visible = available.filter { it !in hidden }
    val now = remember(mm) { nowIndex(mm.time, mm.utcOffsetSeconds) }
    var selected by remember(mm) { mutableIntStateOf(now) }

    val specs = remember {
        listOf(
            ModelChartSpec("Température (°C)", { it.temperature }, minRange = 5.0, fromZero = false),
            ModelChartSpec("Précipitations cumulées (mm)", { it.precipitation }, minRange = 5.0, fromZero = true, cumulative = true),
            ModelChartSpec("Vent moyen (km/h)", { it.windSpeed }, minRange = 20.0, fromZero = true),
        )
    }

    Column(modifier.fillMaxWidth()) {
        if (visible.isEmpty()) {
            Text(
                "Aucun modèle affiché : choisis-les dans les Réglages.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }
        // Légende : une couleur par modèle, plus la moyenne en pointillés.
        FlowRow(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            visible.forEach { model ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorDot(colors.series[model.ordinal])
                    Spacer(Modifier.width(4.dp))
                    Text(model.label, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(width = 16.dp, height = 10.dp)) {
                    drawLine(
                        colors.ink, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2f * density,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * density, 3f * density)),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text("Moyenne", style = MaterialTheme.typography.bodySmall)
            }
        }
        val missing = available.let { all -> WeatherModel.entries.filter { it !in all && it !in hidden } }
        if (missing.isNotEmpty()) {
            Text(
                "Ne couvre pas ce lieu : " + missing.joinToString { it.label },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        specs.forEach { spec ->
            Spacer(Modifier.height(12.dp))
            ModelChart(mm, spec, visible, selected, onSelect = { selected = it })
        }

        Spacer(Modifier.height(12.dp))
        SelectedTimeTable(mm, visible, selected)
        Spacer(Modifier.height(12.dp))
        DailyModelTable(mm, visible)
    }
}

@Composable
private fun ColorDot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
private fun ModelChart(
    mm: MultiModelForecast,
    spec: ModelChartSpec,
    visible: List<WeatherModel>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = LocalChartColors.current
    val measurer = rememberTextMeasurer()
    val n = mm.time.size
    if (n < 2) return
    val raw = visible.associateWith { spec.values(mm.series.getValue(it)) }
    val rawAverage = meanOf(raw.values.toList(), n)
    val series = if (spec.cumulative) raw.mapValues { cumulative(it.value) } else raw
    val average = if (spec.cumulative) cumulative(rawAverage) else rawAverage
    val all = series.values.flatMap { arr -> arr.filter { !it.isNaN() } }
    val lo = if (spec.fromZero) 0.0 else (all.minOrNull() ?: 0.0) - 1
    val hi = max((all.maxOrNull() ?: spec.minRange) + if (spec.fromZero) 0.0 else 1.0, lo + spec.minRange)
    val ticks = niceTicks(lo, hi, 4)

    Text(
        spec.title,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        fontSize = 16.sp,
        modifier = Modifier.padding(horizontal = 18.dp),
    )
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(170.dp)
            .padding(end = 12.dp)
            .pointerInput(n) {
                val axis = 34.dp.toPx()
                fun indexAt(x: Float) = ((x - axis) / (size.width - axis) * (n - 1)).toInt().coerceIn(0, n - 1)
                detectTapGestures { onSelect(indexAt(it.x)) }
            }
            .pointerInput(n) {
                val axis = 34.dp.toPx()
                fun indexAt(x: Float) = ((x - axis) / (size.width - axis) * (n - 1)).toInt().coerceIn(0, n - 1)
                detectHorizontalDragGestures { change, _ -> onSelect(indexAt(change.position.x)) }
            },
    ) {
        val dp = density
        val axis = 34f * dp
        val top = 18f * dp
        val bottom = size.height - 2f * dp
        val x = { i: Int -> axis + i * (size.width - axis) / (n - 1) }
        val scale = LinearScale(ticks.first(), ticks.last(), top, bottom)

        ticks.forEach { t ->
            drawLine(colors.grid, Offset(axis, scale.y(t)), Offset(size.width, scale.y(t)), 1f * dp)
            val label = if (t == t.toLong().toDouble()) t.toLong().toString() else String.format(java.util.Locale.FRANCE, "%.1f", t)
            drawLabel(measurer, label, Offset(axis - 4f * dp, scale.y(t)), colors.muted, 10f, 1f, 0.5f)
        }
        for (i in 0 until n) {
            val t = mm.time[i]
            if (t.hour == 0 && i > 0) drawLine(colors.axis, Offset(x(i), top), Offset(x(i), bottom), 1f * dp)
            if (t.hour == 12) drawLabel(measurer, Fmt.dayShort(t.toLocalDate()), Offset(x(i), 0f), colors.secondaryInk, 9f, 0.5f, 0f)
        }

        series.forEach { (model, values) ->
            drawSeries(values, x, scale::y, colors.series[model.ordinal], 2f * dp)
        }
        drawSeries(average, x, scale::y, colors.ink, 2f * dp, dashed = true)

        val sx = x(selected)
        drawLine(colors.secondaryInk, Offset(sx, top), Offset(sx, bottom), 1f * dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * dp, 3f * dp)))
        series.forEach { (model, values) ->
            val v = values[selected]
            if (!v.isNaN()) {
                drawCircle(colors.series[model.ordinal], radius = 4f * dp, center = Offset(sx, scale.y(v)))
            }
        }
    }
}

/** Valeurs de chaque modèle à l'heure touchée sur les graphiques. */
@Composable
private fun SelectedTimeTable(mm: MultiModelForecast, visible: List<WeatherModel>, selected: Int) {
    val colors = LocalChartColors.current
    val time = mm.time[selected]
    BorderedCard {
        Column(Modifier.padding(12.dp)) {
            Text("${Fmt.dayLong(time.toLocalDate())} · ${Fmt.hour(time)}", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            TableRow(null, "Modèle", "Temp.", "Pluie/h", "Vent", header = true)
            visible.forEach { model ->
                val s = mm.series.getValue(model)
                TableRow(
                    colors.series[model.ordinal], model.label,
                    Fmt.temp(s.temperature[selected]), Fmt.mm(s.precipitation[selected]), Fmt.kmh(s.windSpeed[selected]),
                )
            }
            val n = mm.time.size
            val all = visible.map { mm.series.getValue(it) }
            TableRow(
                colors.ink, "Moyenne",
                Fmt.temp(meanOf(all.map { it.temperature }, n)[selected]),
                Fmt.mm(meanOf(all.map { it.precipitation }, n)[selected]),
                Fmt.kmh(meanOf(all.map { it.windSpeed }, n)[selected]),
                header = true,
            )
        }
    }
}

@Composable
private fun TableRow(color: Color?, name: String, a: String, b: String, c: String, header: Boolean = false) {
    val weight = if (header) FontWeight.SemiBold else null
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(16.dp)) { if (color != null) ColorDot(color) }
        Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = weight, modifier = Modifier.weight(1f))
        Text(a, style = MaterialTheme.typography.bodySmall, fontWeight = weight, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
        Text(b, style = MaterialTheme.typography.bodySmall, fontWeight = weight, modifier = Modifier.width(64.dp), textAlign = TextAlign.End)
        Text(c, style = MaterialTheme.typography.bodySmall, fontWeight = weight, modifier = Modifier.width(72.dp), textAlign = TextAlign.End)
    }
}

/** Résumé par jour et par modèle : maxi / mini et cumul de pluie. */
@Composable
private fun DailyModelTable(mm: MultiModelForecast, visible: List<WeatherModel>) {
    val colors = LocalChartColors.current
    val days = mm.time.map { it.toLocalDate() }.distinct()
    Text(
        "Résumé par jour",
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        fontSize = 16.sp,
        modifier = Modifier.padding(horizontal = 18.dp),
    )
    Spacer(Modifier.height(6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Column {
            Text("", style = MaterialTheme.typography.labelSmall, modifier = Modifier.height(20.dp))
            visible.forEach { model ->
                Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    ColorDot(colors.series[model.ordinal])
                    Spacer(Modifier.width(6.dp))
                    Text(model.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(96.dp))
                }
            }
        }
        days.forEach { day ->
            val idx = mm.time.indices.filter { mm.time[it].toLocalDate() == day }
            Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Fmt.dayShort(day), style = MaterialTheme.typography.labelSmall, modifier = Modifier.height(20.dp))
                visible.forEach { model ->
                    val s = mm.series.getValue(model)
                    val temps = idx.map { s.temperature[it] }.filter { !it.isNaN() }
                    val rain = idx.map { s.precipitation[it] }.filter { !it.isNaN() }
                    Column(Modifier.height(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(
                            if (temps.isEmpty()) "–" else "${Fmt.temp(temps.max())} / ${Fmt.temp(temps.min())}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            if (rain.isEmpty()) "" else Fmt.mm(rain.sum()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Cumul au fil des heures ; reste vide là où le modèle n'a plus de données. */
fun cumulative(values: DoubleArray): DoubleArray {
    var sum = 0.0
    return DoubleArray(values.size) { i ->
        val v = values[i]
        if (v.isNaN()) Double.NaN else { sum += v; sum }
    }
}

/** Moyenne des modèles disponibles à chaque heure (au moins deux, sinon NaN). */
fun meanOf(arrays: List<DoubleArray>, size: Int): DoubleArray = DoubleArray(size) { i ->
    val values = arrays.mapNotNull { arr -> arr.getOrNull(i)?.takeIf { !it.isNaN() } }
    if (values.size >= 2) values.average() else Double.NaN
}

/** Les graphiques montrent 7 jours ; les 14 jours chargés servent à la prévisibilité. */
private const val DISPLAY_DAYS = 7

private fun MultiModelForecast.firstDays(days: Int): MultiModelForecast {
    val n = minOf(time.size, days * 24)
    return MultiModelForecast(
        time.take(n),
        utcOffsetSeconds,
        series.mapValues { (_, s) -> ModelSeries(s.temperature.copyOf(n), s.precipitation.copyOf(n), s.windSpeed.copyOf(n)) },
    )
}
