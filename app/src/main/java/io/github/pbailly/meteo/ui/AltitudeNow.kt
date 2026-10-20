package io.github.pbailly.meteo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pbailly.meteo.data.Altitude
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.data.Outlook
import io.github.pbailly.meteo.data.TerrainProfile
import io.github.pbailly.meteo.ui.theme.AppCondensed
import io.github.pbailly.meteo.ui.theme.AppMono
import io.github.pbailly.meteo.ui.theme.AppSans
import io.github.pbailly.meteo.ui.theme.LocalChartColors

/**
 * Bloc « Maintenant » de la page Neige & altitude : coupe du relief et températures par altitude, iso 0,
 * pluie-neige et ressenti en haut.
 */
@Composable
fun AltitudeNowPanel(f: Forecast, terrain: TerrainProfile?, modifier: Modifier = Modifier) {
    val i = Outlook.nowIndex(f)
    val ground = Altitude.ground(f)
    val iso = Altitude.freezingLevel(f, i)
    val snowLine = Altitude.snowLine(f, i)
    val colors = MaterialTheme.colorScheme
    Panel(modifier) {
        PanelTitle(Icons.Outlined.Thermostat, "Maintenant", "températures par altitude")
        ReliefNowChart(f, i, terrain, ground, iso, snowLine, Modifier.fillMaxWidth().padding(top = 8.dp).height(200.dp))
        HorizontalDivider(Modifier.padding(top = 10.dp, bottom = 8.dp), color = colors.outlineVariant)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // Iso 0 du jour seulement : sa fourchette jusqu'à minuit.
            val today = f.hourly.time[i].toLocalDate()
            val rest = (i until f.hourly.size).takeWhile { f.hourly.time[it].toLocalDate() == today }
                .map { Altitude.freezingLevel(f, it) }.filter { !it.isNaN() }
            val isoNote = if (rest.size >= 2 && rest.max() - rest.min() >= 100) {
                "auj. ${Fmt.thousands(rest.min(), 100)}–${Fmt.thousands(rest.max(), 100)}"
            } else {
                "stable aujourd'hui"
            }
            AltitudeCell("ISO 0 °C", Fmt.altitude(iso, 100), isoNote, colors.primary, Modifier.weight(1f))
            VerticalDivider(color = colors.outlineVariant)
            AltitudeCell("PLUIE-NEIGE", Fmt.altitude(snowLine, 100), "iso 0 − 300 m", colors.onBackground, Modifier.weight(1f))
            VerticalDivider(color = colors.outlineVariant)
            val high = if (ground < 1700) 2000.0 else 3000.0
            val wind = Altitude.wind(f, i, high)
            val feel = Altitude.windChill(Altitude.temperature(f, i, high), wind.speed)
            AltitudeCell(
                "RESS. ${Fmt.thousands(high)} M",
                Fmt.temp(feel),
                "vent ${Outlook.compass(wind.direction)} ${Fmt.kmh(wind.speed)}",
                colors.onBackground,
                Modifier.weight(1f),
            )
        }
    }
}


@Composable
private fun AltitudeCell(label: String, value: String, note: String, valueColor: Color, modifier: Modifier) {
    Column(modifier.padding(horizontal = 6.dp)) {
        MonoText(label, size = 10.sp)
        Text(value, color = valueColor, fontFamily = AppCondensed, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, maxLines = 1)
        MonoText(note, size = 10.sp)
    }
}

/**
 * Coupe du lieu vers le sommet le plus haut des environs, avec les températures à gauche (lieu, 1 000, 1 500,
 * 2 000, 3 000 m, sommet), l'iso 0 °C en tirets et la limite pluie-neige en pointillés.
 */
@Composable
private fun ReliefNowChart(
    f: Forecast,
    i: Int,
    terrain: TerrainProfile?,
    ground: Double,
    iso: Double,
    snowLine: Double,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val chart = LocalChartColors.current
    Canvas(modifier) {
        val labelWidth = 86.dp.toPx()
        val left = labelWidth + 6.dp.toPx()
        val right = size.width
        val topPx = 8.dp.toPx()
        val bottomPx = size.height - 8.dp.toPx()
        val profile = terrain?.let { it.elevations.copyOfRange(it.placeIndex, it.elevations.size) }
        val summit = terrain?.summit?.takeIf { it.index >= terrain.placeIndex }
        val peak = summit?.elevation ?: profile?.maxOrNull() ?: ground
        val maxAlt = maxOf(peak, 3000.0, if (iso.isNaN() || iso > 4500) 0.0 else iso + 250, ground + 800) + 120
        val minAlt = minOf(ground, profile?.minOrNull() ?: ground) - 60
        fun y(alt: Double) = (bottomPx - (alt - minAlt) / (maxAlt - minAlt) * (bottomPx - topPx)).toFloat()

        // Altitudes de la colonne de gauche, sans chevauchement.
        val wanted = listOfNotNull(summit?.elevation?.takeIf { it > 3250 }, 3000.0, 2000.0, 1500.0, 1000.0)
            .filter { it > ground + 150 && it < maxAlt }
        val rows = ArrayList<Double>()
        val minGap = 15.dp.toPx()
        for (alt in wanted.sortedDescending()) {
            if (rows.isEmpty() || y(alt) - y(rows.last()) >= minGap) rows += alt
        }
        while (rows.isNotEmpty() && y(ground) - y(rows.last()) < minGap) rows.removeAt(rows.lastIndex)
        rows += ground

        for (alt in rows) {
            val yy = y(alt)
            drawLine(chart.grid, Offset(left, yy), Offset(right, yy), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 5f)))
            drawLabel(measurer, Fmt.altitude(alt), Offset(0f, yy), chart.muted, 10f, anchorY = 0.5f)
            drawText(
                measurer.measure(Fmt.temp(Altitude.temperature(f, i, alt)), TextStyle(color = colors.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppSans)),
                topLeft = Offset(labelWidth - measureWidth(measurer, Fmt.temp(Altitude.temperature(f, i, alt))), yy - 9.sp.toPx()),
            )
        }

        if (profile != null && profile.size > 1) {
            val step = (right - left) / (profile.size - 1)
            val line = Path()
            profile.forEachIndexed { k, e -> if (k == 0) line.moveTo(left, y(e)) else line.lineTo(left + k * step, y(e)) }
            val fill = Path().apply {
                addPath(line)
                lineTo(right, bottomPx)
                lineTo(left, bottomPx)
                close()
            }
            drawPath(fill, colors.primary.copy(alpha = 0.13f))
            drawPath(line, colors.primary, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        }

        val textBackground = colors.surfaceContainer
        if (!iso.isNaN() && iso < maxAlt) {
            val yy = y(iso)
            drawLine(colors.primary, Offset(left, yy), Offset(right, yy), 1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 9f)))
            drawTag(measurer, "ISO 0 °C ${Fmt.altitude(iso, 100)}", Offset(left + 4.dp.toPx(), yy - 2.dp.toPx()), colors.primary, textBackground, above = true)
        }
        if (!snowLine.isNaN() && snowLine < maxAlt && snowLine > minAlt) {
            val yy = y(snowLine)
            drawLine(colors.onBackground, Offset(left, yy), Offset(right, yy), 1.4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 5f)), cap = StrokeCap.Round)
            drawTag(measurer, "PLUIE-NEIGE ${Fmt.altitude(snowLine, 100)}", Offset(left + 4.dp.toPx(), yy + 3.dp.toPx()), colors.onBackground, textBackground, above = false)
        }
        drawCircle(colors.primary, 4.dp.toPx(), Offset(left, y(ground)))
    }
}

private fun measureWidth(measurer: TextMeasurer, text: String): Float =
    measurer.measure(text, TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppSans)).size.width.toFloat()

/** Étiquette d'une ligne (iso 0, pluie-neige), sur un fond pour rester lisible au-dessus du relief. */
private fun DrawScope.drawTag(measurer: TextMeasurer, text: String, at: Offset, color: Color, background: Color, above: Boolean, boxed: Boolean = false) {
    val layout = measurer.measure(text, TextStyle(color = color, fontSize = 10.sp, fontFamily = AppMono, fontWeight = FontWeight.Medium))
    val pad = 3.dp.toPx()
    val top = if (above) at.y - layout.size.height - pad else at.y + pad
    drawRoundRect(
        background.copy(alpha = 0.9f),
        topLeft = Offset(at.x - pad, top - pad / 2),
        size = Size(layout.size.width + 2 * pad, layout.size.height + pad),
        cornerRadius = CornerRadius(4.dp.toPx()),
    )
    if (boxed) {
        drawRoundRect(
            color,
            topLeft = Offset(at.x - pad, top - pad / 2),
            size = Size(layout.size.width + 2 * pad, layout.size.height + pad),
            cornerRadius = CornerRadius(4.dp.toPx()),
            style = Stroke(1.dp.toPx()),
        )
    }
    drawText(layout, topLeft = Offset(at.x, top))
}
