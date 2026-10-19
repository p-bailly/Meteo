package io.github.pbailly.meteo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.pbailly.meteo.ui.theme.LocalChartColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Pictogramme météo dessiné à partir d'un code WMO (soleil, lune, nuages, pluie, neige, orage). */
@Composable
fun WeatherIcon(code: Int, isDay: Boolean, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val dark = LocalChartColors.current.darkTheme
    Canvas(
        modifier
            .size(size)
            .semantics { contentDescription = weatherDescription(code) },
    ) {
        drawWeatherIcon(code, isDay, dark, Offset.Zero, this.size.minDimension)
    }
}

private enum class Sky { CLEAR, MOSTLY_CLEAR, PARTLY, OVERCAST, FOG }

private enum class Precip { NONE, DRIZZLE, LIGHT, MODERATE, HEAVY, FREEZING, SNOW_LIGHT, SNOW, SNOW_HEAVY, THUNDER, HAIL }

private data class IconSpec(val sky: Sky, val precip: Precip = Precip.NONE, val showers: Boolean = false)

private fun iconSpec(code: Int): IconSpec = when (code) {
    0 -> IconSpec(Sky.CLEAR)
    1 -> IconSpec(Sky.MOSTLY_CLEAR)
    2 -> IconSpec(Sky.PARTLY)
    3 -> IconSpec(Sky.OVERCAST)
    45, 48 -> IconSpec(Sky.FOG)
    51, 53 -> IconSpec(Sky.OVERCAST, Precip.DRIZZLE)
    55 -> IconSpec(Sky.OVERCAST, Precip.LIGHT)
    56, 57, 66, 67 -> IconSpec(Sky.OVERCAST, Precip.FREEZING)
    61 -> IconSpec(Sky.OVERCAST, Precip.LIGHT)
    63 -> IconSpec(Sky.OVERCAST, Precip.MODERATE)
    65 -> IconSpec(Sky.OVERCAST, Precip.HEAVY)
    71, 77 -> IconSpec(Sky.OVERCAST, Precip.SNOW_LIGHT)
    73 -> IconSpec(Sky.OVERCAST, Precip.SNOW)
    75 -> IconSpec(Sky.OVERCAST, Precip.SNOW_HEAVY)
    80 -> IconSpec(Sky.PARTLY, Precip.LIGHT, showers = true)
    81 -> IconSpec(Sky.PARTLY, Precip.MODERATE, showers = true)
    82 -> IconSpec(Sky.PARTLY, Precip.HEAVY, showers = true)
    85 -> IconSpec(Sky.PARTLY, Precip.SNOW_LIGHT, showers = true)
    86 -> IconSpec(Sky.PARTLY, Precip.SNOW, showers = true)
    95 -> IconSpec(Sky.OVERCAST, Precip.THUNDER)
    96, 99 -> IconSpec(Sky.OVERCAST, Precip.HAIL)
    else -> IconSpec(Sky.OVERCAST)
}

private class IconPalette(dark: Boolean) {
    val sun = Color(0xFFF5B700)
    val sunEdge = Color(0xFFC98500)
    val moon = if (dark) Color(0xFFD5DBE5) else Color(0xFF9AA6B8)
    val cloud = if (dark) Color(0xFFE6EAF0) else Color.White
    val cloudEdge = if (dark) Color(0xFFB7C0CC) else Color(0xFF8A94A3)
    val heavyCloud = if (dark) Color(0xFF9AA3AF) else Color(0xFFC9CFD8)
    val heavyCloudEdge = if (dark) Color(0xFF9AA3AF) else Color(0xFF6E7887)
    val rain = if (dark) Color(0xFF6DA7EC) else Color(0xFF2A78D6)
    val snow = if (dark) Color(0xFFB7D3F6) else Color(0xFF5598E7)
    val fog = if (dark) Color(0xFFB7C0CC) else Color(0xFF8A94A3)
}

/**
 * Dessine le pictogramme dans un carré de côté [iconSize] placé en [topLeft].
 * Les coordonnées internes sont exprimées sur une grille de 24 × 24.
 */
fun DrawScope.drawWeatherIcon(code: Int, isDay: Boolean, dark: Boolean, topLeft: Offset, iconSize: Float) {
    val spec = iconSpec(code)
    val p = IconPalette(dark)
    val k = iconSize / 24f
    withTransform({
        translate(topLeft.x, topLeft.y)
        scale(k, k, pivot = Offset.Zero)
    }) {
        if (spec.precip == Precip.NONE) {
            when (spec.sky) {
                Sky.CLEAR -> sunOrMoon(isDay, 12f, 12f, 5.5f, p)
                Sky.MOSTLY_CLEAR -> {
                    sunOrMoon(isDay, 10f, 10f, 5f, p)
                    cloud(11f, 13f, 12f, 7.5f, p, heavy = false)
                }
                Sky.PARTLY -> {
                    sunOrMoon(isDay, 9f, 8.5f, 4.5f, p)
                    cloud(5f, 9.5f, 17.5f, 10.5f, p, heavy = false)
                }
                Sky.OVERCAST -> {
                    cloud(8f, 3f, 14f, 8.5f, p, heavy = true)
                    cloud(2f, 7.5f, 18f, 11f, p, heavy = false)
                }
                Sky.FOG -> {
                    cloud(5f, 2f, 14f, 8.5f, p, heavy = false)
                    for (y in listOf(13.5f, 17f, 20.5f)) {
                        drawLine(p.fog, Offset(3.5f, y), Offset(20.5f, y), strokeWidth = 1.8f, cap = StrokeCap.Round)
                    }
                }
            }
            return@withTransform
        }

        val heavy = spec.precip in setOf(Precip.HEAVY, Precip.THUNDER, Precip.HAIL, Precip.SNOW_HEAVY)
        if (spec.showers) {
            sunOrMoon(isDay, 8f, 6.5f, 4f, p)
            cloud(5f, 5f, 17f, 9.5f, p, heavy)
        } else {
            cloud(2.5f, 2.5f, 19f, 11f, p, heavy)
        }

        when (spec.precip) {
            Precip.DRIZZLE -> listOf(8f, 12f, 16f).forEach { drop(it, 17f, 2.2f, p) }
            Precip.LIGHT -> listOf(9f, 15f).forEach { drop(it, 16f, 4f, p) }
            Precip.MODERATE -> listOf(7f, 12f, 17f).forEach { drop(it, 16f, 4.5f, p) }
            Precip.HEAVY -> listOf(5.5f, 9.5f, 13.5f, 17.5f).forEach { drop(it, 15.5f, 6f, p) }
            Precip.FREEZING -> {
                drop(8f, 16f, 4f, p)
                flake(15.5f, 19f, 2.6f, p)
            }
            Precip.SNOW_LIGHT -> listOf(9f to 18.5f, 15f to 18.5f).forEach { (x, y) -> flake(x, y, 2.8f, p) }
            Precip.SNOW -> listOf(7f to 18f, 12f to 20.5f, 17f to 18f).forEach { (x, y) -> flake(x, y, 2.6f, p) }
            Precip.SNOW_HEAVY ->
                listOf(6f to 17.5f, 10.5f to 20.5f, 14f to 17.5f, 18f to 20.5f).forEach { (x, y) -> flake(x, y, 2.3f, p) }
            Precip.THUNDER -> {
                drop(6.5f, 16f, 4f, p)
                drop(18f, 16f, 4f, p)
                lightning(p)
            }
            Precip.HAIL -> {
                lightning(p)
                listOf(6.5f to 18f, 18f to 18f, 7.5f to 21.5f).forEach { (x, y) ->
                    drawCircle(p.snow, radius = 1.2f, center = Offset(x, y))
                }
            }
            Precip.NONE -> Unit
        }
    }
}

private fun DrawScope.sunOrMoon(isDay: Boolean, cx: Float, cy: Float, r: Float, p: IconPalette) {
    if (isDay) {
        for (i in 0 until 8) {
            val a = i * PI.toFloat() / 4f
            val dx = cos(a)
            val dy = sin(a)
            drawLine(
                p.sun,
                Offset(cx + dx * (r + 1.3f), cy + dy * (r + 1.3f)),
                Offset(cx + dx * (r + 3f), cy + dy * (r + 3f)),
                strokeWidth = 1.5f,
                cap = StrokeCap.Round,
            )
        }
        drawCircle(p.sun, radius = r, center = Offset(cx, cy))
        drawCircle(p.sunEdge, radius = r, center = Offset(cx, cy), style = Stroke(width = 0.6f))
    } else {
        val full = Path().apply { addOval(Rect(Offset(cx, cy), r + 0.5f)) }
        val bite = Path().apply { addOval(Rect(Offset(cx + r * 0.6f, cy - r * 0.45f), r * 0.85f)) }
        drawPath(Path.combine(PathOperation.Difference, full, bite), p.moon)
    }
}

private fun DrawScope.cloud(l: Float, t: Float, w: Float, h: Float, p: IconPalette, heavy: Boolean) {
    val base = Path().apply {
        addRoundRect(RoundRect(l, t + h * 0.45f, l + w, t + h, CornerRadius(h * 0.275f)))
    }
    val bumps = listOf(
        Offset(l + w * 0.32f, t + h * 0.55f) to h * 0.34f,
        Offset(l + w * 0.58f, t + h * 0.42f) to h * 0.42f,
        Offset(l + w * 0.80f, t + h * 0.62f) to h * 0.30f,
    )
    val shape = bumps.fold(base) { acc, (center, radius) ->
        Path.combine(PathOperation.Union, acc, Path().apply { addOval(Rect(center, radius)) })
    }
    drawPath(shape, if (heavy) p.heavyCloud else p.cloud)
    drawPath(
        shape,
        if (heavy) p.heavyCloudEdge else p.cloudEdge,
        style = Stroke(width = 1.1f, join = StrokeJoin.Round),
    )
}

private fun DrawScope.drop(x: Float, y: Float, length: Float, p: IconPalette) {
    drawLine(p.rain, Offset(x + length * 0.3f, y), Offset(x - length * 0.3f, y + length), strokeWidth = 1.8f, cap = StrokeCap.Round)
}

private fun DrawScope.flake(x: Float, y: Float, r: Float, p: IconPalette) {
    for (i in 0 until 3) {
        val a = i * PI.toFloat() / 3f + PI.toFloat() / 2f
        val dx = cos(a) * r
        val dy = sin(a) * r
        drawLine(p.snow, Offset(x - dx, y - dy), Offset(x + dx, y + dy), strokeWidth = 1.2f, cap = StrokeCap.Round)
    }
}

private fun DrawScope.lightning(p: IconPalette) {
    val bolt = Path().apply {
        moveTo(13.5f, 12.5f)
        lineTo(9.5f, 18.5f)
        lineTo(12f, 18.5f)
        lineTo(10.5f, 23f)
        lineTo(15.5f, 16.5f)
        lineTo(13f, 16.5f)
        lineTo(14.5f, 12.5f)
        close()
    }
    drawPath(bolt, p.sun)
    drawPath(bolt, p.sunEdge, style = Stroke(width = 0.6f, join = StrokeJoin.Round))
}
