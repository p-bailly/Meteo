package io.github.pbailly.meteo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.data.Predictability
import io.github.pbailly.meteo.ui.theme.OnTemperature
import io.github.pbailly.meteo.ui.theme.predictabilityColor
import io.github.pbailly.meteo.ui.theme.temperatureColor

/** Carte bleu marine des écrans façon Meteoblue. */
val NavyCard = Color(0xFF0C304E)

/** Filets entre les lignes des cartes marine. */
val NavyDivider = Color(0xFF1D4A70)

/** Case de température colorée selon la valeur (vert, jaune, orange…), texte marine. */
@Composable
fun TemperatureBox(celsius: Double, modifier: Modifier = Modifier, fontSize: TextUnit = 22.sp) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(temperatureColor(celsius)),
        contentAlignment = Alignment.Center,
    ) {
        Text(Fmt.temp(celsius), color = OnTemperature, fontSize = fontSize, fontWeight = FontWeight.Medium)
    }
}

/** Pile de quatre cibles : autant de cibles allumées que le niveau de prévisibilité (1 à 4). */
@Composable
fun PredictabilityStack(percent: Int?, modifier: Modifier = Modifier, glyph: Dp = 13.dp) {
    val level = percent?.let { Predictability.level(it) } ?: 0
    Column(
        modifier.semantics { contentDescription = if (percent != null) "Prévisibilité $percent %" else "Prévisibilité inconnue" },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (i in 4 downTo 1) {
            val active = level >= i
            Canvas(Modifier.size(glyph)) {
                drawTarget(center, size.minDimension / 2, if (active) Color(0xFFE6EEF5) else Color(0x5585A9C8), 1.2f * density)
            }
        }
    }
}

/** Carré coloré selon la prévisibilité, avec une cible au centre. */
@Composable
fun PredictabilitySquare(percent: Int?, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val background = percent?.let { predictabilityColor(it) } ?: Color(0x3385A9C8)
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .semantics { contentDescription = if (percent != null) "Prévisibilité $percent %" else "Prévisibilité inconnue" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.7f)) {
            drawTarget(center, this.size.minDimension / 2, if (percent != null) OnTemperature else Color(0xFFA9C3DA), 1.5f * density)
        }
    }
}

/** Cible : deux cercles et une croix de visée. */
fun DrawScope.drawTarget(center: Offset, radius: Float, color: Color, stroke: Float) {
    drawCircle(color, radius - stroke / 2, center, style = Stroke(stroke))
    drawCircle(color, radius * 0.5f, center, style = Stroke(stroke))
    drawCircle(color, radius * 0.12f, center)
    drawLine(color, Offset(center.x - radius, center.y), Offset(center.x - radius * 0.65f, center.y), stroke)
    drawLine(color, Offset(center.x + radius * 0.65f, center.y), Offset(center.x + radius, center.y), stroke)
    drawLine(color, Offset(center.x, center.y - radius), Offset(center.x, center.y - radius * 0.65f), stroke)
    drawLine(color, Offset(center.x, center.y + radius * 0.65f), Offset(center.x, center.y + radius), stroke)
}

/** Pictogramme de la nuit d'un jour : le temps le plus marquant entre 20 h et minuit. */
fun nightWeatherCode(forecast: Forecast, dayIndex: Int): Int {
    val h = forecast.hourly
    val date = forecast.daily.date.getOrNull(dayIndex) ?: return -1
    val codes = h.time.indices.filter { h.time[it].toLocalDate() == date && h.time[it].hour >= 20 }.map { h.weatherCode[it] }
    // Les codes WMO croissent à peu près avec la gravité (brouillard < pluie < neige < orage).
    return codes.filter { it >= 0 }.maxOrNull() ?: forecast.daily.weatherCode.getOrElse(dayIndex) { -1 }
}
