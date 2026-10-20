package io.github.pbailly.meteo.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Fond des cases de température, comme sur Meteoblue : vert menthe vers 6°, vert franc vers 11°,
 * vert-jaune vers 16°, puis jaune, orange et rouge ; bleu puis violet sous zéro.
 * Le texte posé dessus reste en nuit.
 */
private val TEMPERATURE_STOPS = listOf(
    -15.0 to Color(0xFFB9A6F2),
    -5.0 to Color(0xFF9CC2F7),
    0.0 to Color(0xFFADE3F5),
    6.0 to Color(0xFFC0F2BB),
    11.0 to Color(0xFF8CE782),
    16.0 to Color(0xFFC0F082),
    20.0 to Color(0xFFEDEF7C),
    25.0 to Color(0xFFF8CF6A),
    30.0 to Color(0xFFF5A05E),
    35.0 to Color(0xFFEF6E5A),
)

fun temperatureColor(celsius: Double): Color {
    if (celsius.isNaN()) return Color(0xFFD3E1EC)
    val stops = TEMPERATURE_STOPS
    if (celsius <= stops.first().first) return stops.first().second
    if (celsius >= stops.last().first) return stops.last().second
    val upper = stops.indexOfFirst { it.first >= celsius }
    val (t0, c0) = stops[upper - 1]
    val (t1, c1) = stops[upper]
    return lerp(c0, c1, ((celsius - t0) / (t1 - t0)).toFloat())
}

/** Texte des cases de température. */
val OnTemperature = Color(0xFF0F2133)

/** Couleurs de l'indice UV (échelle OMS). */
fun uvColor(index: Double): Color = when {
    index.isNaN() -> Color.White
    index < 3 -> Color(0xFF9BD45B)
    index < 6 -> Color(0xFFF2D43D)
    index < 8 -> Color(0xFFF59A3B)
    index < 11 -> Color(0xFFE5483B)
    else -> Color(0xFFB77CE6)
}
