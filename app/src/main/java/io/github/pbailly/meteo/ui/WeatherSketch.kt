package io.github.pbailly.meteo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import io.github.pbailly.meteo.ui.theme.LocalChartColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Dessin au crayon du temps qu'il fait, en fond du haut d'Aujourd'hui : un horizon de montagnes, et selon le ciel le
 * soleil, la lune et des étoiles, des nuages, la pluie, la neige, l'orage ou le brouillard. Traits doublés, un peu
 * tremblés, et hachures, en gris graphite très léger pour ne pas gêner la lecture.
 */
@Composable
fun WeatherSketch(code: Int, isDay: Boolean, modifier: Modifier = Modifier) {
    val dark = LocalChartColors.current.darkTheme
    val graphite = if (dark) Color(0xFFE6ECF4).copy(alpha = 0.20f) else Color(0xFF2E3644).copy(alpha = 0.24f)
    Canvas(modifier) {
        // Toujours le même dessin pour le même temps : pas de scintillement à chaque recomposition.
        val pencil = Pencil(this, graphite, Random(code * 7 + if (isDay) 1 else 0))
        val w = size.width
        val h = size.height
        val sky = SketchSky.of(code)
        mountains(pencil, w, h, snowy = sky == SketchSky.SNOW)
        // Le dessin occupe l'espace libre : entre la grande température (à gauche) et le ciel en toutes lettres (à
        // droite), sous la ligne du lieu et au-dessus du ressenti.
        val celestial = Offset(w * 0.57f, h * 0.47f)
        when (sky) {
            SketchSky.CLEAR -> if (isDay) sun(pencil, celestial, 16f * density) else night(pencil, celestial, w, h)
            SketchSky.PARTLY -> {
                if (isDay) sun(pencil, celestial + Offset(-12f * density, -6f * density), 14f * density) else moon(pencil, celestial, 14f * density)
                cloud(pencil, Offset(w * 0.61f, h * 0.55f), 68f * density, shade = 0.5f)
            }
            SketchSky.CLOUDY -> {
                cloud(pencil, Offset(w * 0.50f, h * 0.47f), 72f * density, shade = 0.7f)
                cloud(pencil, Offset(w * 0.64f, h * 0.56f), 84f * density, shade = 0.9f)
            }
            SketchSky.FOG -> {
                if (isDay) sun(pencil, celestial, 14f * density, rays = false)
                fog(pencil, w, h)
            }
            SketchSky.RAIN, SketchSky.SNOW, SketchSky.STORM -> {
                val dense = if (sky == SketchSky.STORM) 1.6f else 1f
                cloud(pencil, Offset(w * 0.50f, h * 0.44f), 72f * density, shade = dense)
                cloud(pencil, Offset(w * 0.64f, h * 0.50f), 84f * density, shade = dense)
                when (sky) {
                    SketchSky.RAIN -> rain(pencil, w, h)
                    SketchSky.SNOW -> snow(pencil, w, h)
                    else -> {
                        rain(pencil, w, h)
                        lightning(pencil, Offset(w * 0.58f, h * 0.60f), 34f * density)
                    }
                }
            }
        }
    }
}

/** Grandes familles de ciel, d'après le code météo WMO. */
private enum class SketchSky {
    CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, STORM;

    companion object {
        fun of(code: Int): SketchSky = when (code) {
            0, 1 -> CLEAR
            2 -> PARTLY
            3 -> CLOUDY
            45, 48 -> FOG
            71, 73, 75, 77, 85, 86 -> SNOW
            95, 96, 99 -> STORM
            in 51..67, in 80..82 -> RAIN
            else -> CLOUDY
        }
    }
}

/** Trait de crayon : chaque ligne repassée deux fois, avec un léger tremblé. */
private class Pencil(val scope: DrawScope, val color: Color, val rnd: Random) {
    val d = scope.density

    fun jitter(amount: Float) = (rnd.nextFloat() * 2 - 1) * amount * d

    fun stroke(points: List<Offset>, width: Float = 1.1f, passes: Int = 2, close: Boolean = false) {
        if (points.size < 2) return
        repeat(passes) { pass ->
            val path = Path()
            points.forEachIndexed { i, p ->
                val q = Offset(p.x + jitter(0.7f), p.y + jitter(0.7f))
                if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y)
            }
            if (close) path.close()
            scope.drawPath(
                path,
                color.copy(alpha = color.alpha * if (pass == 0) 1f else 0.55f),
                style = Stroke(width * d * (if (pass == 0) 1f else 0.8f), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }

    /** Segment découpé en petits morceaux légèrement décalés : il ne paraît pas tiré à la règle. */
    fun line(a: Offset, b: Offset, width: Float = 1.1f, passes: Int = 2, wobble: Float = 0.6f) {
        val n = 6
        val points = (0..n).map { k ->
            val t = k / n.toFloat()
            Offset(a.x + (b.x - a.x) * t + if (k in 1 until n) jitter(wobble) else 0f, a.y + (b.y - a.y) * t + if (k in 1 until n) jitter(wobble) else 0f)
        }
        stroke(points, width, passes)
    }

    /** Contour d'une forme fermée, échantillonné le long du tracé. */
    fun outline(shape: Path, width: Float = 1.2f) {
        val measure = PathMeasure()
        measure.setPath(shape, true)
        val length = measure.length
        if (length <= 0f) return
        val steps = (length / (4f * d)).toInt().coerceAtLeast(12)
        // On dépasse un peu le point de départ, comme un trait de crayon qu'on referme.
        val points = (0..steps + 2).map { k -> measure.getPosition((k * length / steps) % length) }
        stroke(points, width)
    }

    /** Hachures parallèles à 45° dans [shape], espacées de [spacing] dp, limitées à [area]. */
    fun hatch(shape: Path, area: Rect, spacing: Float, width: Float = 0.8f) {
        scope.clipPath(shape) {
            var x = area.left - area.height
            while (x < area.right) {
                line(Offset(x, area.bottom), Offset(x + area.height, area.top), width, passes = 1, wobble = 0.4f)
                x += spacing * d
            }
        }
    }
}

private fun DrawScope.mountains(pencil: Pencil, w: Float, h: Float, snowy: Boolean) {
    val base = h * 0.98f
    // Sommets au milieu ; sur les côtés, la crête reste basse sous le texte.
    val ridge = listOf(
        0f to 0.97f, 0.15f to 0.94f, 0.28f to 0.90f, 0.38f to 0.80f, 0.46f to 0.86f,
        0.56f to 0.70f, 0.66f to 0.82f, 0.74f to 0.76f, 0.84f to 0.92f, 1f to 0.95f,
    ).map { (x, y) -> Offset(w * x, h * y) }
    pencil.stroke(ridge, width = 1.3f)
    val shape = Path().apply {
        moveTo(ridge.first().x, ridge.first().y)
        ridge.drop(1).forEach { lineTo(it.x, it.y) }
        lineTo(w, base)
        lineTo(0f, base)
        close()
    }
    // Ombre sur les versants : hachures sous la crête, seulement au milieu (pas sous le texte des côtés).
    pencil.hatch(shape, Rect(w * 0.32f, h * 0.66f, w * 0.80f, base), spacing = 7.5f)
    if (snowy) {
        // Neige sur les sommets : petits traits sous chaque pointe.
        ridge.filterIndexed { i, p -> i in 1 until ridge.lastIndex && p.y < ridge[i - 1].y && p.y < ridge[i + 1].y }.forEach { peak ->
            pencil.line(peak + Offset(-8f * density, 6f * density), peak + Offset(8f * density, 6f * density), width = 1f)
        }
    }
}

private fun DrawScope.sun(pencil: Pencil, center: Offset, radius: Float, rays: Boolean = true) {
    pencil.outline(Path().apply { addOval(Rect(center, radius)) })
    if (!rays) return
    for (k in 0 until 12) {
        val a = (k * 30.0 + 8) * PI / 180
        val r0 = radius + 6f * density
        val r1 = radius + (if (k % 2 == 0) 17f else 11f) * density
        pencil.line(
            Offset(center.x + (r0 * cos(a)).toFloat(), center.y + (r0 * sin(a)).toFloat()),
            Offset(center.x + (r1 * cos(a)).toFloat(), center.y + (r1 * sin(a)).toFloat()),
        )
    }
}

private fun DrawScope.moon(pencil: Pencil, center: Offset, radius: Float) {
    val disc = Path().apply { addOval(Rect(center, radius)) }
    val bite = Path().apply { addOval(Rect(center + Offset(radius * 0.55f, -radius * 0.3f), radius * 0.95f)) }
    val crescent = Path().apply { op(disc, bite, PathOperation.Difference) }
    pencil.outline(crescent)
    pencil.hatch(crescent, Rect(center, radius), spacing = 4f)
}

private fun DrawScope.night(pencil: Pencil, center: Offset, w: Float, h: Float) {
    moon(pencil, center, 15f * density)
    listOf(0.43f to 0.36f, 0.47f to 0.62f, 0.68f to 0.34f, 0.72f to 0.58f, 0.62f to 0.66f).forEach { (x, y) ->
        val c = Offset(w * x, h * y)
        val r = 4f * density
        pencil.line(c + Offset(-r, 0f), c + Offset(r, 0f), passes = 1)
        pencil.line(c + Offset(0f, -r), c + Offset(0f, r), passes = 1)
    }
}

/** Nuage : bosses rondes sur une base plate, ombré par-dessous ([shade] de 0 à 2 : de plus en plus sombre). */
private fun DrawScope.cloud(pencil: Pencil, center: Offset, width: Float, shade: Float) {
    val r = width / 4
    val shape = Path().apply {
        addOval(Rect(center + Offset(-width * 0.28f, r * 0.15f), r * 0.85f))
        op(this, Path().apply { addOval(Rect(center + Offset(-width * 0.04f, -r * 0.35f), r * 1.15f)) }, PathOperation.Union)
        op(this, Path().apply { addOval(Rect(center + Offset(width * 0.24f, r * 0.05f), r * 0.95f)) }, PathOperation.Union)
        op(this, Path().apply { addRect(Rect(center.x - width * 0.28f, center.y, center.x + width * 0.24f, center.y + r)) }, PathOperation.Union)
    }
    pencil.outline(shape, width = 1.3f)
    val bounds = shape.getBounds()
    // Ombre du dessous du nuage, plus haute et plus serrée quand il est sombre.
    val top = bounds.bottom - bounds.height * (0.35f + 0.25f * shade.coerceAtMost(1.6f))
    pencil.hatch(shape, Rect(bounds.left, top, bounds.right, bounds.bottom), spacing = (6.5f - 2f * shade).coerceAtLeast(3f))
}

private fun DrawScope.rain(pencil: Pencil, w: Float, h: Float) {
    for (k in 0 until 11) {
        val x = w * (0.42f + 0.024f * k) + pencil.jitter(3f)
        val y = h * (0.62f + if (k % 2 == 0) 0f else 0.07f)
        pencil.line(Offset(x, y), Offset(x - 5f * density, y + 14f * density), passes = 1)
    }
}

private fun DrawScope.snow(pencil: Pencil, w: Float, h: Float) {
    for (k in 0 until 9) {
        val c = Offset(w * (0.43f + 0.028f * k) + pencil.jitter(4f), h * (0.64f + if (k % 2 == 0) 0f else 0.08f))
        val r = 3.2f * density
        for (a in listOf(0.0, 60.0, 120.0)) {
            val dx = (r * cos(a * PI / 180)).toFloat()
            val dy = (r * sin(a * PI / 180)).toFloat()
            pencil.line(c + Offset(-dx, -dy), c + Offset(dx, dy), width = 0.9f, passes = 1, wobble = 0.2f)
        }
    }
}

private fun DrawScope.lightning(pencil: Pencil, top: Offset, height: Float) {
    val s = height / 4
    pencil.stroke(
        listOf(top, top + Offset(-s * 0.6f, s * 1.6f), top + Offset(s * 0.1f, s * 1.5f), top + Offset(-s * 0.7f, s * 3.6f)),
        width = 1.6f,
    )
}

private fun DrawScope.fog(pencil: Pencil, w: Float, h: Float) {
    for (k in 0 until 4) {
        val y = h * (0.44f + 0.08f * k)
        val x0 = w * (0.39f + 0.03f * (k % 2))
        val points = (0..10).map { i -> Offset(x0 + (w * 0.33f) * i / 10f, y + (sin(i * 1.3 + k) * 2.5 * density).toFloat()) }
        pencil.stroke(points, width = 1.1f)
    }
}
