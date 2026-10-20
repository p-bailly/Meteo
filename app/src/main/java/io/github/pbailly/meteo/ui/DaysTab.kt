package io.github.pbailly.meteo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.data.MultiModelForecast
import io.github.pbailly.meteo.data.Outlook
import io.github.pbailly.meteo.data.Predictability
import io.github.pbailly.meteo.ui.theme.LocalChartColors
import io.github.pbailly.meteo.ui.theme.predictabilityColor
import kotlin.math.max

/** Largeur d'une journée : six à sept jours à l'écran, les suivants en faisant glisser le graphique. */
private val DAY_WIDTH = 56.dp
private val AXIS_WIDTH = 34.dp
private val CHART_HEIGHT = 356.dp

/** Rangées du graphique, en dp depuis le haut. */
private object Rows {
    const val ICONS = 34f
    const val PREDICTABILITY = 72f
    const val TEMPERATURE_TOP = 116f
    const val TEMPERATURE_BOTTOM = 232f
    const val RAIN_TOP = 252f
    const val RAIN_BOTTOM = 306f
    const val WIND = 326f
}

/** Onglet « 14 jours » des météogrammes : une colonne par jour, qu'on fait défiler du doigt. */
@Composable
fun DaysTab(forecast: Forecast, multiModel: MultiModelForecast?, modifier: Modifier = Modifier) {
    val daily = forecast.daily
    if (daily.size == 0) {
        Text("Pas de prévisions journalières.", modifier.padding(16.dp))
        return
    }
    val predictability = remember(forecast, multiModel) {
        daily.date.map { date -> multiModel?.let { Predictability.daily(it, date) } }
    }
    BorderedCard(modifier) {
        FourteenDayChart(forecast, predictability, Modifier.padding(vertical = 12.dp))
    }
}

/**
 * Pictogramme, prévisibilité, barre mini → maxi, cumul de pluie et vent (flèche de la direction dominante et
 * vitesse maximale). L'axe reste fixe à gauche pendant le défilement.
 */
@Composable
private fun FourteenDayChart(forecast: Forecast, predictability: List<Int?>, modifier: Modifier = Modifier) {
    val d = forecast.daily
    val n = d.size
    val colors = LocalChartColors.current
    val cold = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()
    val tempTicks = remember(forecast) {
        val lows = d.temperatureMin.filter { !it.isNaN() }
        val highs = d.temperatureMax.filter { !it.isNaN() }
        niceTicks((lows.minOrNull() ?: 0.0) - 1, (highs.maxOrNull() ?: 10.0) + 1, 4)
    }
    val rainMax = remember(forecast) { max(5.0, d.precipitationSum.filter { !it.isNaN() }.maxOrNull() ?: 0.0) }
    val codes = remember(forecast) { d.date.map { Outlook.dayCode(forecast, it) } }
    val scroll = rememberScrollState()

    Row(modifier) {
        Canvas(Modifier.size(AXIS_WIDTH, CHART_HEIGHT)) {
            val dp = density
            val right = size.width - 4f * dp
            val tScale = LinearScale(tempTicks.first(), tempTicks.last(), Rows.TEMPERATURE_TOP * dp, Rows.TEMPERATURE_BOTTOM * dp)
            tempTicks.forEach { t -> drawLabel(measurer, "${t.toInt()}°", Offset(right, tScale.y(t)), colors.muted, 10f, 1f, 0.5f) }
            if (predictability.any { it != null }) drawLabel(measurer, "conf.", Offset(right, (Rows.PREDICTABILITY + 2f) * dp), colors.muted, 9f, 1f, 0.5f)
            drawLabel(measurer, "mm", Offset(right, Rows.RAIN_BOTTOM * dp), colors.muted, 10f, 1f, 1f)
            drawLabel(measurer, "km/h", Offset(right, (Rows.WIND + 16f) * dp), colors.muted, 9f, 1f, 0.5f)
        }
        Box(Modifier.weight(1f).horizontalScroll(scroll)) {
            Canvas(Modifier.width(DAY_WIDTH * n).height(CHART_HEIGHT)) {
                val dp = density
                val colWidth = size.width / n
                val cx = { i: Int -> (i + 0.5f) * colWidth }
                val tScale = LinearScale(tempTicks.first(), tempTicks.last(), Rows.TEMPERATURE_TOP * dp, Rows.TEMPERATURE_BOTTOM * dp)
                val rainBottom = Rows.RAIN_BOTTOM * dp
                val rScale = LinearScale(0.0, rainMax, (Rows.RAIN_TOP + 12f) * dp, rainBottom)

                tempTicks.forEach { t ->
                    val y = tScale.y(t)
                    drawLine(colors.grid, Offset(0f, y), Offset(size.width, y), 1f * dp)
                }
                drawLine(colors.axis, Offset(0f, rainBottom), Offset(size.width, rainBottom), 1f * dp)

                for (i in 0 until n) {
                    val date = d.date[i]
                    // Séparateur entre les jours, plus marqué avant le lundi.
                    if (i > 0) {
                        val monday = date.dayOfWeek.value == 1
                        drawLine(if (monday) colors.axis else colors.grid, Offset(i * colWidth, 0f), Offset(i * colWidth, size.height), 1f * dp)
                    }
                    val weekend = date.dayOfWeek.value >= 6
                    val name = if (i == 0) "auj." else Fmt.dayName(date)
                    drawLabel(measurer, name, Offset(cx(i), 0f), if (weekend || i == 0) colors.ink else colors.secondaryInk, 11f, 0.5f, 0f, bold = true)
                    drawLabel(measurer, date.dayOfMonth.toString(), Offset(cx(i), 15f * dp), colors.secondaryInk, 10f, 0.5f, 0f)

                    val iconSize = 30f * dp
                    drawWeatherIcon(codes[i], true, colors.darkTheme, Offset(cx(i) - iconSize / 2, Rows.ICONS * dp), iconSize)

                    // Indice de confiance (accord des modèles) : petite barre colorée et note sur 5.
                    predictability.getOrNull(i)?.let { p ->
                        val barWidth = colWidth - 14f * dp
                        drawRoundRect(
                            predictabilityColor(p),
                            Offset(cx(i) - barWidth / 2, Rows.PREDICTABILITY * dp),
                            Size(barWidth, 5f * dp),
                            CornerRadius(2.5f * dp),
                        )
                        drawLabel(measurer, "${Predictability.confidence(p)}/5", Offset(cx(i), (Rows.PREDICTABILITY + 8f) * dp), colors.secondaryInk, 10f, 0.5f, 0f)
                    }

                    // Températures : barre du mini (bleu) au maxi (orange).
                    val hi = d.temperatureMax[i]
                    val lo = d.temperatureMin[i]
                    if (!hi.isNaN() && !lo.isNaN()) {
                        val barWidth = 10f * dp
                        val top = tScale.y(hi)
                        val bottom = max(tScale.y(lo), top + 2f * dp)
                        drawRoundRect(
                            Brush.verticalGradient(listOf(colors.accent, cold), startY = top, endY = bottom),
                            Offset(cx(i) - barWidth / 2, top),
                            Size(barWidth, bottom - top),
                            CornerRadius(barWidth / 2),
                        )
                        drawLabel(measurer, Fmt.temp(hi), Offset(cx(i), top - 3f * dp), colors.ink, 11f, 0.5f, 1f, bold = true)
                        drawLabel(measurer, Fmt.temp(lo), Offset(cx(i), bottom + 3f * dp), colors.secondaryInk, 11f, 0.5f, 0f)
                    }

                    val rain = d.precipitationSum[i]
                    if (!rain.isNaN() && rain >= 0.1) {
                        val top = rScale.y(rain)
                        val barWidth = colWidth - 18f * dp
                        drawRoundRect(
                            colors.precipitation,
                            Offset(cx(i) - barWidth / 2, top),
                            Size(barWidth, rainBottom - top),
                            CornerRadius(2f * dp),
                        )
                        val text = if (rain >= 10) rain.toInt().toString() else String.format(java.util.Locale.FRANCE, "%.1f", rain)
                        drawLabel(measurer, text, Offset(cx(i), top - 1f * dp), colors.precipitation, 10f, 0.5f, 1f, bold = true)
                    }

                    // Vent : flèche de la direction dominante (vers où il souffle) et vitesse maximale.
                    val wind = d.windSpeedMax[i]
                    drawWindArrow(Offset(cx(i), (Rows.WIND + 2f) * dp), d.windDirectionDominant[i], 16f * dp, colors.ink, 1.8f * dp)
                    if (!wind.isNaN()) {
                        val strong = wind >= 40
                        drawLabel(measurer, wind.toInt().toString(), Offset(cx(i), (Rows.WIND + 13f) * dp), if (strong) colors.accent else colors.secondaryInk, 10f, 0.5f, 0f, bold = strong)
                    }
                }
            }
        }
    }
}
