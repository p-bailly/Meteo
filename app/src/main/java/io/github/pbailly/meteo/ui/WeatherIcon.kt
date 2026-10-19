package io.github.pbailly.meteo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.pbailly.meteo.ui.theme.LocalChartColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Pictogramme météo au trait, dessiné à partir d'un code WMO (soleil, lune, nuages, pluie, neige, orage). */
@Composable
fun WeatherIcon(
    code: Int,
    isDay: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    /** Vrai quand l'icône est posée sur un fond foncé (thème sombre ou carte bleu marine). */
    dark: Boolean = LocalChartColors.current.darkTheme,
) {
    Canvas(
        modifier
            .size(size)
            .semantics { contentDescription = weatherDescription(code) },
    ) {
        drawWeatherIcon(code, isDay, dark, Offset.Zero, this.size.minDimension)
    }
}

private enum class Sky { CLEAR, MOSTLY_CLEAR, PARTLY, OVERCAST, FOG }

private enum class Precip { NONE, DRIZZLE, LIGHT, MODERATE, HEAVY, FREEZING, SNOW_LIGHT, SNOW, SNOW_HEAVY, THUNDER }

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
    // 96 et 99 (« orage avec grêle ») : Open-Meteo ne les juge fiables qu'en Europe centrale, on dessine un orage.
    95, 96, 99 -> IconSpec(Sky.OVERCAST, Precip.THUNDER)
    else -> IconSpec(Sky.OVERCAST)
}

/** Couleurs des pictogrammes au trait de l'identité Glacier (« dark » : thème sombre ou carte glacier). */
private class IconPalette(dark: Boolean) {
    val sun = if (dark) Color(0xFFFFC93C) else Color(0xFFD99000)
    val moon = if (dark) Color(0xFFD5DBE5) else Color(0xFF6E86A0)
    val cloud = if (dark) Color(0xFFFFFFFF) else Color(0xFF1C4A70)
    /** Voile des nuages chargés (couvert, fortes pluies, orage). */
    val heavyFill = if (dark) Color(0x40FFFFFF) else Color(0x261C6497)
    val rain = if (dark) Color(0xFFCDEBFF) else Color(0xFF1C6497)
    val snow = if (dark) Color(0xFFE3F2FF) else Color(0xFF4FA3D9)
    val fog = if (dark) Color(0xFFD6E8F5) else Color(0xFF66798C)
}

/** Épaisseur du trait sur la grille de 24 × 24. */
private const val LINE = 1.6f

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
                    val front = cloudShape(11f, 13f, 12f, 7.5f)
                    behind(front) { sunOrMoon(isDay, 10f, 10f, 5f, p) }
                    drawCloud(front, p, heavy = false)
                }
                Sky.PARTLY -> {
                    val front = cloudShape(5f, 9.5f, 17.5f, 10.5f)
                    behind(front) { sunOrMoon(isDay, 9f, 8.5f, 4.5f, p) }
                    drawCloud(front, p, heavy = false)
                }
                Sky.OVERCAST -> {
                    val front = cloudShape(2f, 7.5f, 18f, 11f)
                    behind(front) { drawCloud(cloudShape(8f, 3f, 14f, 8.5f), p, heavy = true) }
                    drawCloud(front, p, heavy = false)
                }
                Sky.FOG -> {
                    drawCloud(cloudShape(5f, 2f, 14f, 8.5f), p, heavy = false)
                    for (y in listOf(13.5f, 17f, 20.5f)) {
                        drawLine(p.fog, Offset(3.5f, y), Offset(20.5f, y), strokeWidth = LINE, cap = StrokeCap.Round)
                    }
                }
            }
            return@withTransform
        }

        val heavy = spec.precip in setOf(Precip.HEAVY, Precip.THUNDER, Precip.SNOW_HEAVY)
        if (spec.showers) {
            val shape = cloudShape(5f, 5f, 17f, 9.5f)
            behind(shape) { sunOrMoon(isDay, 8f, 6.5f, 4f, p) }
            drawCloud(shape, p, heavy)
        } else {
            drawCloud(cloudShape(2.5f, 2.5f, 19f, 11f), p, heavy)
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
            Precip.NONE -> Unit
        }
    }
}

/** Dessine [block] sauf là où le nuage [cover] passe devant : les nuages au trait n'ont pas de fond opaque. */
private inline fun DrawScope.behind(cover: Path, block: DrawScope.() -> Unit) = clipPath(cover, ClipOp.Difference, block)

private fun DrawScope.sunOrMoon(isDay: Boolean, cx: Float, cy: Float, r: Float, p: IconPalette) {
    if (isDay) {
        for (i in 0 until 8) {
            val a = i * PI.toFloat() / 4f
            val dx = cos(a)
            val dy = sin(a)
            drawLine(
                p.sun,
                Offset(cx + dx * (r + 1.6f), cy + dy * (r + 1.6f)),
                Offset(cx + dx * (r + 3.2f), cy + dy * (r + 3.2f)),
                strokeWidth = LINE,
                cap = StrokeCap.Round,
            )
        }
        drawCircle(p.sun, radius = r, center = Offset(cx, cy), style = Stroke(width = LINE))
    } else {
        val full = Path().apply { addOval(Rect(Offset(cx, cy), r + 0.5f)) }
        val bite = Path().apply { addOval(Rect(Offset(cx + r * 0.6f, cy - r * 0.45f), r * 0.85f)) }
        drawPath(Path.combine(PathOperation.Difference, full, bite), p.moon, style = Stroke(width = LINE, join = StrokeJoin.Round))
    }
}

/** Silhouette de nuage : une base arrondie et trois bosses, dans le rectangle donné. */
private fun cloudShape(l: Float, t: Float, w: Float, h: Float): Path {
    val base = Path().apply {
        addRoundRect(RoundRect(l, t + h * 0.45f, l + w, t + h, CornerRadius(h * 0.275f)))
    }
    val bumps = listOf(
        Offset(l + w * 0.32f, t + h * 0.55f) to h * 0.34f,
        Offset(l + w * 0.58f, t + h * 0.42f) to h * 0.42f,
        Offset(l + w * 0.80f, t + h * 0.62f) to h * 0.30f,
    )
    return bumps.fold(base) { acc, (center, radius) ->
        Path.combine(PathOperation.Union, acc, Path().apply { addOval(Rect(center, radius)) })
    }
}

private fun DrawScope.drawCloud(shape: Path, p: IconPalette, heavy: Boolean) {
    if (heavy) drawPath(shape, p.heavyFill)
    drawPath(shape, p.cloud, style = Stroke(width = LINE, join = StrokeJoin.Round))
}

private fun DrawScope.drop(x: Float, y: Float, length: Float, p: IconPalette) {
    drawLine(p.rain, Offset(x + length * 0.3f, y), Offset(x - length * 0.3f, y + length), strokeWidth = LINE, cap = StrokeCap.Round)
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
}
