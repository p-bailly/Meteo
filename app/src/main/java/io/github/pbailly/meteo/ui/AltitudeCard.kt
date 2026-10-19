package io.github.pbailly.meteo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.ui.theme.AppCondensed
import io.github.pbailly.meteo.ui.theme.AppMono
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Bloc « Altitude » de la direction Glacier : altitude de l'iso 0 °C et températures aux niveaux 850 hPa
 * (vers 1 500 m) et 700 hPa (vers 3 000 m), à l'heure courante, avec la fourchette de l'iso 0 °C sur 24 h.
 * Les altitudes des niveaux sont celles du modèle (géopotentiel), pas des valeurs fixes.
 */
@Composable
fun AltitudeCard(forecast: Forecast, modifier: Modifier = Modifier) {
    val h = forecast.hourly
    if (h.size == 0) return
    val now = nowIndex(h.time, forecast.utcOffsetSeconds)
    val iso = h.freezingLevel.getOrElse(now) { Double.NaN }
    val t850 = h.temperature850.getOrElse(now) { Double.NaN }
    val t700 = h.temperature700.getOrElse(now) { Double.NaN }
    if (iso.isNaN() && t850.isNaN() && t700.isNaN()) return

    val next = (now until min(h.size, now + 24)).map { h.freezingLevel[it] }.filter { !it.isNaN() }
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp)
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(12.dp))
            .background(colors.surfaceContainerLowest, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text("ALTITUDE", fontFamily = AppMono, fontSize = 11.sp, letterSpacing = 1.sp, color = colors.primary, modifier = Modifier.weight(1f))
            Text("MAINTENANT", fontFamily = AppMono, fontSize = 11.sp, letterSpacing = 1.sp, color = colors.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AltitudeCell("ISO 0 °C", altitude(iso) ?: "–", Modifier.weight(1.2f))
            AltitudeCell(levelLabel(h.height850.getOrElse(now) { Double.NaN }, "850 hPa"), Fmt.temp(t850), Modifier.weight(1f))
            AltitudeCell(levelLabel(h.height700.getOrElse(now) { Double.NaN }, "700 hPa"), Fmt.temp(t700), Modifier.weight(1f))
        }
        if (next.size >= 2) {
            val low = altitude(next.min())
            val high = altitude(next.max())
            Text(
                if (low == high) "Iso 0 °C stable sur 24 h, vers $low" else "Iso 0 °C sur 24 h : de $low à $high",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun AltitudeCell(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, fontFamily = AppMono, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, fontFamily = AppCondensed, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, maxLines = 1)
    }
}

/** « ≈ 1 520 m » d'après le géopotentiel du niveau, ou le nom du niveau s'il manque. */
private fun levelLabel(height: Double, fallback: String): String = altitude(height)?.let { "≈ $it" } ?: fallback

/** Altitude arrondie à la dizaine de mètres, milliers séparés par une espace : « 2 450 m ». */
private fun altitude(meters: Double): String? {
    if (meters.isNaN()) return null
    val rounded = (meters / 10).roundToInt() * 10
    val digits = kotlin.math.abs(rounded).toString().reversed().chunked(3).joinToString(" ").reversed()
    return (if (rounded < 0) "-" else "") + digits + " m"
}
