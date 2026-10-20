package io.github.pbailly.meteo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import io.github.pbailly.meteo.ui.theme.LocalChartColors
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Dessin au crayon du temps qu'il fait, en fond du haut d'Aujourd'hui : un paysage (montagnes, lac, forêt ou village,
 * selon le jour), et selon le ciel le soleil, la lune dans sa vraie phase et des étoiles, des nuages, la pluie, la
 * neige, l'orage ou le brouillard. Parfois une petite surprise, et certains jours de fête une autre. Traits doublés,
 * un peu tremblés, et hachures, en gris graphite très léger pour ne pas gêner la lecture. Toucher le dessin en montre
 * une autre version.
 */
@Composable
fun WeatherSketch(code: Int, isDay: Boolean, time: LocalDateTime, latitude: Double, placeSeed: Int, modifier: Modifier = Modifier) {
    val dark = LocalChartColors.current.darkTheme
    val graphite = if (dark) Color(0xFFE6ECF4).copy(alpha = 0.20f) else Color(0xFF2E3644).copy(alpha = 0.24f)
    val taps = remember(placeSeed, time.toLocalDate()) { mutableIntStateOf(0) }
    val scene = remember(code, isDay, time, placeSeed, taps.intValue) { SketchScenes.scene(code, isDay, time, placeSeed, taps.intValue) }
    val moonAge = remember(time) { SketchScenes.moonAge(time) }
    Canvas(
        modifier
            // Calque à part : les nuages et les objets du premier plan effacent ce qui passe derrière eux.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .pointerInput(taps) { detectTapGestures { taps.intValue++ } },
    ) {
        // Toujours le même dessin pour la même scène : pas de scintillement à chaque recomposition.
        val pencil = Pencil(this, graphite, Random(scene.seed * 31 + code))
        drawScene(pencil, scene, isDay, moonAge, southern = latitude < 0)
    }
}

private fun DrawScope.drawScene(pencil: Pencil, scene: SketchScene, isDay: Boolean, moonAge: Double, southern: Boolean) {
    val w = size.width
    val h = size.height
    val u = density
    val s = scene.surprises
    landscape(pencil, scene, w, h)
    // Le dessin occupe l'espace libre : entre la grande température (à gauche) et le ciel en toutes lettres (à
    // droite), sous la ligne du lieu et au-dessus du ressenti.
    val celestial = Offset(w * 0.57f, h * 0.47f)
    when (scene.sky) {
        SketchSky.CLEAR -> if (isDay) sun(pencil, celestial, 16f * u) else night(pencil, celestial, w, h, moonAge, southern)
        SketchSky.PARTLY -> {
            if (isDay) sun(pencil, celestial + Offset(-12f * u, -6f * u), 14f * u) else moon(pencil, celestial, 14f * u, moonAge, southern)
            cloud(pencil, Offset(w * 0.61f, h * 0.55f), 68f * u, shade = 0.5f)
        }
        SketchSky.CLOUDY -> {
            cloud(pencil, Offset(w * 0.50f, h * 0.47f), 72f * u, shade = 0.7f)
            cloud(pencil, Offset(w * 0.64f, h * 0.56f), 84f * u, shade = 0.9f)
        }
        SketchSky.FOG -> {
            if (isDay) sun(pencil, celestial, 14f * u, rays = false)
            fog(pencil, w, h)
        }
        SketchSky.RAIN, SketchSky.SNOW, SketchSky.STORM -> {
            val dense = if (scene.sky == SketchSky.STORM) 1.6f else 1f
            if (Surprise.RAINBOW in s) {
                // Averse : un seul nuage, et l'arc-en-ciel à côté.
                rainbow(pencil, Offset(w * 0.69f, h * 0.88f), w * 0.12f)
                cloud(pencil, Offset(w * 0.50f, h * 0.45f), 70f * u, shade = 0.9f)
                rain(pencil, w, h, from = 0.42f, to = 0.58f)
            } else {
                cloud(pencil, Offset(w * 0.50f, h * 0.44f), 72f * u, shade = dense)
                cloud(pencil, Offset(w * 0.64f, h * 0.50f), 84f * u, shade = dense)
                when (scene.sky) {
                    SketchSky.RAIN -> rain(pencil, w, h)
                    SketchSky.SNOW -> snow(pencil, w, h)
                    else -> {
                        rain(pencil, w, h)
                        lightning(pencil, Offset(w * 0.58f, h * 0.60f), 34f * u)
                    }
                }
            }
        }
    }
    surprises(pencil, scene, w, h)
}

/** Trait de crayon : chaque ligne repassée deux fois, avec un léger tremblé. */
internal class Pencil(val scope: DrawScope, val color: Color, val rnd: Random) {
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

    /** Contour d'une forme fermée (d'un seul tenant), échantillonné le long du tracé. */
    fun outline(shape: Path, width: Float = 1.2f, passes: Int = 2) {
        val measure = PathMeasure()
        measure.setPath(shape, true)
        val length = measure.length
        if (length <= 0f) return
        val steps = (length / (4f * d)).toInt().coerceAtLeast(12)
        // On dépasse un peu le point de départ, comme un trait de crayon qu'on referme.
        val points = (0..steps + 2).map { k -> measure.getPosition((k * length / steps) % length) }
        stroke(points, width, passes)
    }

    /** Petit point plein : un œil, un bouton. */
    fun dot(center: Offset, radius: Float = 0.8f) =
        scope.drawCircle(color.copy(alpha = (color.alpha * 1.4f).coerceAtMost(1f)), radius * d, center)

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

    /** Gomme : ce qui est derrière la forme disparaît (un nuage cache le soleil, un bonhomme la montagne). */
    fun erase(shape: Path) = scope.drawPath(shape, Color.Black, blendMode = BlendMode.Clear)
}

internal fun DrawScope.sun(pencil: Pencil, center: Offset, radius: Float, rays: Boolean = true) {
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

/**
 * La Lune dans sa phase du jour : rien à la nouvelle lune, un fin croissant, un quartier, une lune gibbeuse ou pleine
 * (avec ses mers). Côté éclairé à droite quand elle croît, dans l'hémisphère nord ; l'inverse au sud.
 */
private fun DrawScope.moon(pencil: Pencil, center: Offset, radius: Float, age: Double, southern: Boolean) {
    val lit = SketchScenes.moonIllumination(age).toFloat()
    if (lit < 0.03f) return
    val disc = Path().apply { addOval(Rect(center, radius)) }
    if (lit > 0.97f) {
        pencil.outline(disc)
        listOf(Triple(-0.3f, -0.25f, 0.22f), Triple(0.28f, 0.1f, 0.16f), Triple(-0.05f, 0.42f, 0.12f)).forEach { (x, y, r) ->
            val mare = Path().apply { addOval(Rect(center + Offset(radius * x, radius * y), radius * r)) }
            pencil.hatch(mare, Rect(center, radius), spacing = 1.8f, width = 0.6f)
        }
        return
    }
    val litOnRight = SketchScenes.waxing(age) != southern
    // Partie à l'ombre : le disque décalé vers le côté sombre, d'autant plus que la Lune est éclairée.
    val shift = 2 * radius * lit * if (litOnRight) -1 else 1
    val shadow = Path().apply { addOval(Rect(center + Offset(shift, -radius * 0.25f * (1 - lit)), radius)) }
    val shape = Path().apply { op(disc, shadow, PathOperation.Difference) }
    pencil.outline(shape)
    pencil.hatch(shape, Rect(center, radius), spacing = 4f)
}

private fun DrawScope.night(pencil: Pencil, center: Offset, w: Float, h: Float, moonAge: Double, southern: Boolean) {
    moon(pencil, center, 15f * density, moonAge, southern)
    val stars = mutableListOf(0.43f to 0.36f, 0.47f to 0.62f, 0.68f to 0.34f, 0.72f to 0.58f, 0.62f to 0.66f)
    // Sans lune, le ciel est plus étoilé.
    if (SketchScenes.moonIllumination(moonAge) < 0.03) stars += listOf(0.55f to 0.40f, 0.52f to 0.54f, 0.64f to 0.47f)
    stars.forEach { (x, y) ->
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
    pencil.erase(shape)
    pencil.outline(shape, width = 1.3f)
    val bounds = shape.getBounds()
    // Ombre du dessous du nuage, plus haute et plus serrée quand il est sombre.
    val top = bounds.bottom - bounds.height * (0.35f + 0.25f * shade.coerceAtMost(1.6f))
    pencil.hatch(shape, Rect(bounds.left, top, bounds.right, bounds.bottom), spacing = (6.5f - 2f * shade).coerceAtLeast(3f))
}

private fun DrawScope.rain(pencil: Pencil, w: Float, h: Float, from: Float = 0.42f, to: Float = 0.66f) {
    val drops = ((to - from) / 0.024f).toInt() + 1
    for (k in 0 until drops) {
        val x = w * (from + 0.024f * k) + pencil.jitter(3f)
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

/** Arc-en-ciel : trois arcs concentriques, celui du milieu repassé. */
private fun DrawScope.rainbow(pencil: Pencil, center: Offset, radius: Float) {
    val gap = 4f * density
    for (k in 0..2) {
        val r = radius - k * gap
        val points = (0..24).map { i ->
            val a = PI + PI * i / 24
            Offset(center.x + (r * cos(a)).toFloat(), center.y + (r * sin(a)).toFloat())
        }
        pencil.stroke(points, width = if (k == 1) 1.4f else 1f, passes = if (k == 1) 2 else 1)
    }
}
