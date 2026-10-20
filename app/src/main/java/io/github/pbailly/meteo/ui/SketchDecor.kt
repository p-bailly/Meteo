package io.github.pbailly.meteo.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/*
 * Décors et surprises du dessin au crayon d'Aujourd'hui. Repères en fraction de la largeur et de la hauteur : sous la
 * ligne de la date, le ciel libre va de x 0,38 à 0,80 et de y 0,40 à 0,66 (soleil ou lune au milieu) ; le premier
 * plan, devant le paysage, de x 0,36 à 0,47 en bas.
 */

/** Sol du premier plan. */
private const val GROUND = 0.97f

private fun polygon(points: List<Offset>) = Path().apply {
    moveTo(points.first().x, points.first().y)
    points.drop(1).forEach { lineTo(it.x, it.y) }
    close()
}

private fun oval(center: Offset, rx: Float, ry: Float) = Path().apply { addOval(Rect(center.x - rx, center.y - ry, center.x + rx, center.y + ry)) }

private fun Path.union(other: Path) = apply { op(this, other, PathOperation.Union) }

/** Hauteur d'une ligne de crête en [x], par interpolation entre ses points. */
private fun heightAt(ridge: List<Offset>, x: Float): Float {
    val k = ridge.indexOfFirst { it.x >= x }
    if (k <= 0) return ridge.first().y
    val a = ridge[k - 1]
    val b = ridge[k]
    return a.y + (b.y - a.y) * (x - a.x) / (b.x - a.x)
}

/** Crête un peu différente chaque jour : seuls les points du milieu bougent, les côtés restent bas sous le texte. */
private fun ridge(points: List<Pair<Float, Float>>, w: Float, h: Float, rnd: Random, amount: Float): List<Offset> =
    points.mapIndexed { i, (x, y) ->
        val inner = i in 2..points.size - 3
        val dy = if (inner) (rnd.nextFloat() * 2 - 1) * amount else 0f
        val dx = if (inner) (rnd.nextFloat() * 2 - 1) * amount * 0.5f else 0f
        Offset(w * (x + dx), h * (y + dy))
    }

private fun DrawScope.fill(ridge: List<Offset>, w: Float) = Path().apply {
    moveTo(ridge.first().x, ridge.first().y)
    ridge.drop(1).forEach { lineTo(it.x, it.y) }
    lineTo(w, size.height)
    lineTo(0f, size.height)
    close()
}

internal fun DrawScope.landscape(pencil: Pencil, scene: SketchScene, w: Float, h: Float) {
    val rnd = Random(scene.seed)
    when (scene.landscape) {
        Landscape.MOUNTAINS -> mountains(pencil, scene, w, h, rnd)
        Landscape.LAKE -> lake(pencil, scene, w, h, rnd)
        Landscape.FOREST -> forest(pencil, w, h, rnd)
        Landscape.VILLAGE -> village(pencil, scene, w, h, rnd)
    }
}

private fun DrawScope.mountains(pencil: Pencil, scene: SketchScene, w: Float, h: Float, rnd: Random) {
    val u = density
    // Sommets au milieu ; sur les côtés, la crête reste basse sous le texte.
    val ridge = ridge(
        listOf(
            0f to 0.97f, 0.15f to 0.94f, 0.28f to 0.90f, 0.38f to 0.80f, 0.46f to 0.86f,
            0.56f to 0.70f, 0.66f to 0.82f, 0.74f to 0.76f, 0.84f to 0.92f, 1f to 0.95f,
        ),
        w, h, rnd, amount = 0.03f,
    )
    pencil.stroke(ridge, width = 1.3f)
    // Ombre sur les versants : hachures sous la crête, seulement au milieu (pas sous le texte des côtés).
    pencil.hatch(fill(ridge, w), Rect(w * 0.32f, h * 0.66f, w * 0.80f, h * 0.98f), spacing = 7.5f)
    val peaks = ridge.filterIndexed { i, p -> i in 1 until ridge.lastIndex && p.y < ridge[i - 1].y && p.y < ridge[i + 1].y }
    if (scene.sky == SketchSky.SNOW) {
        // Neige sur les sommets : petits traits sous chaque pointe.
        peaks.forEach { peak -> pencil.line(peak + Offset(-8f * u, 6f * u), peak + Offset(8f * u, 6f * u), width = 1f) }
    }
    val top = peaks.minByOrNull { it.y }
    if (Surprise.SUMMIT_CROSS in scene.surprises && top != null) {
        pencil.line(top, top + Offset(0f, -11f * u), width = 1f)
        pencil.line(top + Offset(-3.5f * u, -8f * u), top + Offset(3.5f * u, -8f * u), width = 1f)
    }
}

private fun DrawScope.lake(pencil: Pencil, scene: SketchScene, w: Float, h: Float, rnd: Random) {
    val u = density
    val water = h * 0.89f
    val ridge = ridge(
        listOf(
            0f to 0.97f, 0.16f to 0.95f, 0.30f to 0.91f, 0.40f to 0.83f, 0.48f to 0.86f,
            0.58f to 0.75f, 0.67f to 0.83f, 0.75f to 0.79f, 0.84f to 0.88f, 1f to 0.93f,
        ),
        w, h, rnd, amount = 0.025f,
    )
    // Les montagnes s'arrêtent au bord du lac ; la rive proche est en courbe.
    val shore = listOf(Offset(w * 0.47f, water), Offset(w * 0.455f, h * 0.935f), Offset(w * 0.44f, h))
    val lakeArea = polygon(shore + listOf(Offset(w * 0.88f, h), Offset(w * 0.88f, water)))
    val land = Path().apply {
        addRect(Rect(0f, 0f, w, h))
        op(this, lakeArea, PathOperation.Difference)
    }
    clipPath(land) {
        pencil.stroke(ridge, width = 1.3f)
        pencil.hatch(fill(ridge, w), Rect(w * 0.32f, h * 0.66f, w * 0.80f, h * 0.98f), spacing = 7.5f)
    }
    // Rives : la plus lointaine droite, la plus proche en courbe.
    pencil.line(Offset(w * 0.47f, water), Offset(w * 0.86f, water), width = 1f)
    pencil.stroke(shore, width = 1f)
    // Rides sur l'eau, et le reflet du plus haut sommet.
    for (row in 1..3) {
        val y = water + row * 3f * u
        if (y > h * 0.99f) break
        var x = w * 0.50f + rnd.nextFloat() * 8f * u
        while (x < w * 0.82f) {
            val len = (5f + rnd.nextFloat() * 10f) * u
            pencil.line(Offset(x, y), Offset(x + len, y), width = 0.7f, passes = 1, wobble = 0.3f)
            x += len + (6f + rnd.nextFloat() * 10f) * u
        }
    }
    if (Surprise.SAILBOAT in scene.surprises) sailboat(pencil, Offset(w * 0.67f, water + 4f * u))
    if (Surprise.NESSIE in scene.surprises) nessie(pencil, Offset(w * 0.62f, water + 6f * u))
}

private fun DrawScope.forest(pencil: Pencil, w: Float, h: Float, rnd: Random) {
    val u = density
    val hills = ridge(
        listOf(
            0f to 0.97f, 0.18f to 0.95f, 0.32f to 0.90f, 0.44f to 0.85f, 0.56f to 0.81f,
            0.66f to 0.82f, 0.76f to 0.86f, 0.88f to 0.93f, 1f to 0.95f,
        ),
        w, h, rnd, amount = 0.02f,
    )
    pencil.stroke(hills, width = 1.2f)
    pencil.hatch(fill(hills, w), Rect(w * 0.32f, h * 0.78f, w * 0.80f, h * 0.98f), spacing = 9f)
    // Des sapins de tailles diverses, plantés sur la colline.
    listOf(0.48f, 0.52f, 0.56f, 0.60f, 0.64f).forEach { x ->
        if (rnd.nextFloat() < 0.15f) return@forEach
        val px = w * (x + (rnd.nextFloat() - 0.5f) * 0.015f)
        fir(pencil, Offset(px, heightAt(hills, px) + 2f * u), (16f + rnd.nextFloat() * 11f) * u)
    }
}

/** Sapin : trois étages en dents de scie et un bout de tronc ; ombré à droite. */
private fun DrawScope.fir(pencil: Pencil, base: Offset, height: Float) {
    val x = base.x
    val top = base.y - height
    val half = listOf(0.35f to 0.20f, 0.33f to 0.10f, 0.66f to 0.30f, 0.64f to 0.14f, 0.90f to 0.40f)
    val right = half.map { (dy, dx) -> Offset(x + dx * height, top + dy * height) }
    val left = half.reversed().map { (dy, dx) -> Offset(x - dx * height, top + dy * height) }
    val shape = polygon(listOf(Offset(x, top)) + right + left)
    pencil.erase(shape)
    pencil.outline(shape, width = 1f)
    pencil.hatch(shape, Rect(x, top, x + height * 0.4f, top + height), spacing = 3f, width = 0.6f)
    pencil.line(Offset(x, top + height * 0.9f), base, width = 1f)
}

private fun DrawScope.village(pencil: Pencil, scene: SketchScene, w: Float, h: Float, rnd: Random) {
    val u = density
    // Au loin, des montagnes plus pâles.
    val far = ridge(
        listOf(0.30f to 0.90f, 0.36f to 0.86f, 0.44f to 0.78f, 0.52f to 0.82f, 0.60f to 0.72f, 0.70f to 0.80f, 0.78f to 0.84f, 0.84f to 0.90f),
        w, h, rnd, amount = 0.03f,
    )
    pencil.stroke(far, width = 0.9f, passes = 1)
    val hill = ridge(
        listOf(0f to 0.97f, 0.20f to 0.95f, 0.34f to 0.91f, 0.46f to 0.885f, 0.60f to 0.87f, 0.72f to 0.875f, 0.84f to 0.92f, 1f to 0.95f),
        w, h, rnd, amount = 0.01f,
    )
    pencil.erase(fill(hill, w))
    pencil.stroke(hill, width = 1.2f)
    // Une église à clocher comtois (le dôme « à l'impériale » de Franche-Comté), et deux maisons.
    val cx = w * 0.57f
    church(pencil, Offset(cx, heightAt(hill, cx) + 1f * u))
    house(pencil, Offset(w * 0.485f, heightAt(hill, w * 0.485f) + 1f * u), 13f * u, chimney = false)
    val hx = w * 0.66f
    val chimneyTop = house(pencil, Offset(hx, heightAt(hill, hx) + 1f * u), 15f * u, chimney = true)
    if (Surprise.SMOKE in scene.surprises && chimneyTop != null) smoke(pencil, chimneyTop)
}

private fun DrawScope.church(pencil: Pencil, ground: Offset) {
    val u = density
    val x = ground.x
    val g = ground.y
    val towerTop = g - 23f * u
    val tower = Path().apply { addRect(Rect(x - 4.5f * u, towerTop, x + 4.5f * u, g)) }
    val t = towerTop
    val dome = Path().apply {
        moveTo(x - 5.5f * u, t)
        cubicTo(x - 10f * u, t - 5f * u, x - 3f * u, t - 8f * u, x - 1.6f * u, t - 11f * u)
        lineTo(x - 1f * u, t - 14f * u)
        lineTo(x + 1f * u, t - 14f * u)
        lineTo(x + 1.6f * u, t - 11f * u)
        cubicTo(x + 3f * u, t - 8f * u, x + 10f * u, t - 5f * u, x + 5.5f * u, t)
        close()
    }
    val nave = polygon(
        listOf(
            Offset(x - 4.5f * u, g), Offset(x - 22f * u, g), Offset(x - 22f * u, g - 10f * u),
            Offset(x - 13.2f * u, g - 17f * u), Offset(x - 4.5f * u, g - 10f * u),
        ),
    )
    val all = Path().apply { addPath(tower) }.union(dome).union(nave)
    pencil.erase(all)
    pencil.outline(nave, width = 1f)
    pencil.outline(tower, width = 1.1f)
    pencil.outline(dome, width = 1f)
    pencil.hatch(dome, Rect(x - 6f * u, t - 14f * u, x + 6f * u, t), spacing = 2.4f, width = 0.6f)
    pencil.line(Offset(x, t - 14f * u), Offset(x, t - 20f * u), width = 0.9f)
    pencil.line(Offset(x - 2f * u, t - 18f * u), Offset(x + 2f * u, t - 18f * u), width = 0.8f, passes = 1)
    // L'horloge et l'abat-son du clocher, la porte de l'église.
    pencil.outline(oval(Offset(x, t + 5f * u), 2f * u, 2f * u), width = 0.7f, passes = 1)
    pencil.line(Offset(x - 1.6f * u, t + 10f * u), Offset(x + 1.6f * u, t + 10f * u), width = 0.7f, passes = 1)
    pencil.line(Offset(x - 1.6f * u, t + 12f * u), Offset(x + 1.6f * u, t + 12f * u), width = 0.7f, passes = 1)
    pencil.stroke(listOf(Offset(x - 15f * u, g), Offset(x - 15f * u, g - 5f * u), Offset(x - 13.2f * u, g - 7f * u), Offset(x - 11.4f * u, g - 5f * u), Offset(x - 11.4f * u, g)), width = 0.8f, passes = 1)
}

/** Maison : murs, toit hachuré, une fenêtre ; renvoie le haut de la cheminée s'il y en a une. */
private fun DrawScope.house(pencil: Pencil, ground: Offset, width: Float, chimney: Boolean): Offset? {
    val u = density
    val x = ground.x
    val g = ground.y
    val wall = width * 0.62f
    val walls = Path().apply { addRect(Rect(x - width / 2, g - wall, x + width / 2, g)) }
    val roof = polygon(listOf(Offset(x - width / 2 - 2f * u, g - wall), Offset(x, g - wall - width * 0.5f), Offset(x + width / 2 + 2f * u, g - wall)))
    val stack = if (chimney) Path().apply { addRect(Rect(x + width * 0.18f, g - wall - width * 0.52f, x + width * 0.32f, g - wall - width * 0.2f)) } else null
    val all = Path().apply { addPath(walls) }.union(roof).also { p -> stack?.let { p.union(it) } }
    pencil.erase(all)
    pencil.outline(walls, width = 1f)
    stack?.let { pencil.outline(it, width = 0.9f, passes = 1) }
    pencil.outline(roof, width = 1f)
    pencil.hatch(roof, roof.getBounds(), spacing = 2.4f, width = 0.6f)
    pencil.outline(Path().apply { addRect(Rect(x - 1.5f * u, g - wall * 0.7f, x + 1.5f * u, g - wall * 0.7f + 3f * u)) }, width = 0.7f, passes = 1)
    return if (chimney) Offset(x + width * 0.25f, g - wall - width * 0.55f) else null
}

private fun DrawScope.smoke(pencil: Pencil, from: Offset) {
    val u = density
    val points = (0..18).map { i -> Offset(from.x + i * 0.9f * u + (sin(i * 0.8) * 2.6 * u).toFloat(), from.y - i * 1.5f * u) }
    pencil.stroke(points, width = 0.8f, passes = 1)
}

private fun DrawScope.sailboat(pencil: Pencil, at: Offset) {
    val u = density
    val x = at.x
    val y = at.y
    val hull = polygon(listOf(Offset(x - 10f * u, y), Offset(x + 10f * u, y), Offset(x + 6f * u, y + 4f * u), Offset(x - 6f * u, y + 4f * u)))
    val sail = polygon(listOf(Offset(x + 1f * u, y - 21f * u), Offset(x + 1f * u, y - 2f * u), Offset(x + 11f * u, y - 2f * u)))
    val jib = polygon(listOf(Offset(x - 1f * u, y - 17f * u), Offset(x - 1f * u, y - 2f * u), Offset(x - 8f * u, y - 2f * u)))
    pencil.erase(Path().apply { addPath(hull) }.union(sail).union(jib))
    pencil.outline(hull, width = 1f)
    pencil.hatch(hull, hull.getBounds(), spacing = 2f, width = 0.6f)
    pencil.outline(sail, width = 1f)
    pencil.outline(jib, width = 0.9f)
    pencil.line(Offset(x, y), Offset(x, y - 22f * u), width = 0.9f)
}

/** Le monstre du Loch Ness : deux bosses et un long cou qui sortent de l'eau. */
private fun DrawScope.nessie(pencil: Pencil, water: Offset) {
    val u = density * 1.3f
    listOf(-12f to 5f, -1f to 6.5f).forEach { (dx, r) ->
        val hump = Path().apply { addArc(Rect(water + Offset(dx * u, 0f), r * u), 180f, 180f); close() }
        pencil.erase(hump)
        pencil.stroke((0..10).map { i -> val a = PI + PI * i / 10; water + Offset((dx * u + r * u * cos(a)).toFloat(), (r * u * sin(a)).toFloat()) }, width = 1f)
        pencil.hatch(hump, hump.getBounds(), spacing = 2.5f, width = 0.6f)
    }
    val neck = listOf(Offset(water.x + 9f * u, water.y), Offset(water.x + 10f * u, water.y - 8f * u), Offset(water.x + 13f * u, water.y - 15f * u), Offset(water.x + 17f * u, water.y - 16f * u))
    val back = listOf(Offset(water.x + 18f * u, water.y - 13.5f * u), Offset(water.x + 15f * u, water.y - 12f * u), Offset(water.x + 14f * u, water.y - 6f * u), Offset(water.x + 14f * u, water.y))
    pencil.erase(polygon(neck + back))
    pencil.stroke(neck + back, width = 1f)
    pencil.dot(Offset(water.x + 16f * u, water.y - 14.6f * u), 0.6f)
    pencil.line(Offset(water.x - 20f * u, water.y + 1f * u), Offset(water.x + 22f * u, water.y + 1f * u), width = 0.7f, passes = 1)
}

internal fun DrawScope.surprises(pencil: Pencil, scene: SketchScene, w: Float, h: Float) {
    val s = scene.surprises
    val ground = Offset(w * 0.41f, h * GROUND)
    for (surprise in s) when (surprise) {
        Surprise.BIRDS -> birds(pencil, w, h)
        Surprise.PLANE -> plane(pencil, Offset(w * 0.78f, h * 0.42f), trail = w * 0.11f)
        Surprise.PARAGLIDER -> paraglider(pencil, Offset(w * 0.73f, h * 0.43f))
        Surprise.BALLOON -> balloon(pencil, Offset(w * 0.43f, h * 0.48f))
        Surprise.SHOOTING_STAR -> shootingStar(pencil, Offset(w * 0.68f, h * 0.56f), Offset(w * 0.78f, h * 0.42f))
        Surprise.UFO -> ufo(pencil, Offset(w * 0.73f, h * 0.46f), 24f * density, beamTo = null)
        Surprise.ABDUCTION -> {
            ufo(pencil, Offset(w * 0.73f, h * 0.43f), 30f * density, beamTo = h * 0.95f)
            sheep(pencil, Offset(w * 0.73f, h * 0.60f))
        }
        Surprise.SLEIGH -> sleigh(pencil, Offset(w * 0.72f, h * 0.47f))
        Surprise.FIREWORKS -> fireworks(pencil, w, h)
        Surprise.BATS -> listOf(0.71f to 0.45f, 0.44f to 0.48f, 0.76f to 0.56f).forEach { (x, y) -> bat(pencil, Offset(w * x, h * y)) }
        Surprise.HEART -> heart(pencil, Offset(w * 0.72f, h * 0.50f), 12f * density)
        Surprise.FISH -> fish(pencil, Offset(w * 0.72f, h * 0.47f))
        Surprise.NOTES -> notes(pencil, w, h)
        Surprise.SNOWMAN -> snowman(pencil, ground)
        Surprise.UMBRELLA -> umbrella(pencil, ground)
        Surprise.PUMPKIN -> pumpkin(pencil, ground)
        Surprise.EGGS -> eggs(pencil, w, h)
        Surprise.BLACK_CAT -> cat(pencil, ground)
        Surprise.LILY -> lily(pencil, ground)
        // Dessinés avec le paysage ou le ciel.
        Surprise.SUMMIT_CROSS, Surprise.SAILBOAT, Surprise.NESSIE, Surprise.SMOKE, Surprise.RAINBOW -> Unit
    }
}

// --- Dans le ciel ---

private fun DrawScope.bird(pencil: Pencil, c: Offset, s: Float) {
    pencil.stroke(
        listOf(c + Offset(-s, -0.3f * s), c + Offset(-0.5f * s, -0.6f * s), c, c + Offset(0.5f * s, -0.6f * s), c + Offset(s, -0.3f * s)),
        width = 1f,
    )
}

private fun DrawScope.birds(pencil: Pencil, w: Float, h: Float) {
    val u = density
    listOf(Triple(0.70f, 0.45f, 5.5f), Triple(0.745f, 0.42f, 4.5f), Triple(0.765f, 0.49f, 4f), Triple(0.72f, 0.53f, 3.5f)).forEach { (x, y, s) ->
        bird(pencil, Offset(w * x, h * y), s * u)
    }
}

private fun DrawScope.plane(pencil: Pencil, at: Offset, trail: Float) {
    val u = density
    pencil.line(at + Offset(-6f * u, 1.5f * u), at + Offset(6f * u, -1.5f * u), width = 1.2f)
    pencil.line(at + Offset(-1f * u, -5f * u), at + Offset(1.5f * u, 4.5f * u), width = 1f)
    pencil.line(at + Offset(-6f * u, 1.5f * u), at + Offset(-7.5f * u, -2f * u), width = 0.9f, passes = 1)
    // Traînée de condensation : deux lignes en pointillé derrière l'avion.
    for (side in listOf(-1.2f, 1.2f)) {
        var t = 10f * u
        while (t < trail) {
            val a = at + Offset(-t, t * 0.25f + side * u)
            pencil.line(a, a + Offset(-3f * u, 0.75f * u), width = 0.8f, passes = 1, wobble = 0.2f)
            t += 5f * u
        }
    }
}

private fun DrawScope.paraglider(pencil: Pencil, at: Offset) {
    val u = density * 1.15f
    val wing = Path().apply {
        moveTo(at.x - 11f * u, at.y + 2f * u)
        quadraticTo(at.x, at.y - 7f * u, at.x + 11f * u, at.y + 2f * u)
        quadraticTo(at.x, at.y - 3f * u, at.x - 11f * u, at.y + 2f * u)
        close()
    }
    pencil.erase(wing)
    pencil.outline(wing, width = 1f)
    pencil.hatch(wing, wing.getBounds(), spacing = 2f, width = 0.6f)
    val pilot = at + Offset(0f, 13f * u)
    listOf(-10f, -4f, 4f, 10f).forEach { dx -> pencil.line(at + Offset(dx * u, if (dx * dx > 50f) 1.5f * u else -1.5f * u), pilot, width = 0.5f, passes = 1, wobble = 0.1f) }
    pencil.dot(pilot + Offset(0f, 1.2f * u), 1.6f)
}

private fun DrawScope.balloon(pencil: Pencil, at: Offset) {
    val u = density
    val x = at.x
    val y = at.y
    val envelope = Path().apply {
        moveTo(x - 3f * u, y + 11f * u)
        cubicTo(x - 14f * u, y + 2f * u, x - 10f * u, y - 10f * u, x, y - 10f * u)
        cubicTo(x + 10f * u, y - 10f * u, x + 14f * u, y + 2f * u, x + 3f * u, y + 11f * u)
        close()
    }
    pencil.erase(envelope)
    pencil.outline(envelope, width = 1.1f)
    // Fuseaux, et l'ombre à droite.
    listOf(-0.45f, 0.45f).forEach { k ->
        pencil.stroke((0..8).map { i -> val t = i / 8f; Offset(x + k * 9f * u * sin(PI * (0.15 + 0.85 * t)).toFloat(), y - 10f * u + 21f * u * t) }, width = 0.7f, passes = 1)
    }
    pencil.hatch(envelope, Rect(x + 3f * u, y - 10f * u, x + 12f * u, y + 11f * u), spacing = 2.8f, width = 0.6f)
    pencil.line(Offset(x - 3f * u, y + 11f * u), Offset(x - 2.5f * u, y + 14f * u), width = 0.6f, passes = 1, wobble = 0.1f)
    pencil.line(Offset(x + 3f * u, y + 11f * u), Offset(x + 2.5f * u, y + 14f * u), width = 0.6f, passes = 1, wobble = 0.1f)
    pencil.outline(Path().apply { addRect(Rect(x - 2.5f * u, y + 14f * u, x + 2.5f * u, y + 17.5f * u)) }, width = 0.8f, passes = 1)
}

private fun DrawScope.shootingStar(pencil: Pencil, head: Offset, tail: Offset) {
    val u = density
    for (k in -1..1) pencil.line(head + Offset(0f, k * 0.8f * u), tail + Offset(0f, k * 2.2f * u), width = 0.7f, passes = 1, wobble = 0.2f)
    val r = 4.5f * u
    pencil.line(head + Offset(-r, 0f), head + Offset(r, 0f), width = 1f)
    pencil.line(head + Offset(0f, -r), head + Offset(0f, r), width = 1f)
    pencil.line(head + Offset(-r * 0.5f, -r * 0.5f), head + Offset(r * 0.5f, r * 0.5f), width = 0.8f, passes = 1)
    pencil.line(head + Offset(-r * 0.5f, r * 0.5f), head + Offset(r * 0.5f, -r * 0.5f), width = 0.8f, passes = 1)
}

/** Soucoupe volante ; avec [beamTo], son rayon descend jusqu'au sol. */
private fun DrawScope.ufo(pencil: Pencil, at: Offset, width: Float, beamTo: Float?) {
    val u = density
    if (beamTo != null) {
        listOf(-1f, 1f).forEach { side ->
            val a = at + Offset(side * width * 0.18f, width * 0.12f)
            val b = Offset(at.x + side * width * 0.55f, beamTo)
            var t = 0f
            while (t < 1f) {
                pencil.line(a + (b - a) * t, a + (b - a) * (t + 0.05f), width = 0.7f, passes = 1, wobble = 0.2f)
                t += 0.09f
            }
        }
    }
    val dome = Path().apply { addArc(Rect(at.x - width * 0.22f, at.y - width * 0.3f, at.x + width * 0.22f, at.y + width * 0.1f), 180f, 180f); close() }
    val saucer = oval(at, width / 2, width * 0.13f)
    pencil.erase(Path().apply { addPath(saucer) }.union(dome))
    pencil.outline(dome, width = 1f)
    pencil.outline(saucer, width = 1.2f)
    pencil.hatch(saucer, Rect(at.x - width / 2, at.y, at.x + width / 2, at.y + width * 0.13f), spacing = 2f, width = 0.6f)
    listOf(-0.3f, 0f, 0.3f).forEach { k -> pencil.dot(at + Offset(k * width, -0.5f * u), 0.9f) }
}

/** Le mouton emporté par la soucoupe, pattes dans le vide. */
private fun DrawScope.sheep(pencil: Pencil, at: Offset) {
    val u = density * 1.3f
    val wool = listOf(-4f to 0f, 0f to -2f, 4f to 0f, 0f to 2f, -2f to 1.5f, 2.5f to 1.5f)
        .fold(Path()) { p, (dx, dy) -> p.union(oval(at + Offset(dx * u, dy * u), 4f * u, 3.6f * u)) }
    val head = oval(at + Offset(9f * u, -2.5f * u), 2.6f * u, 2f * u)
    listOf(-4f, -1.5f, 2f, 4.5f).forEach { dx -> pencil.line(at + Offset(dx * u, 4f * u), at + Offset((dx + 0.6f) * u, 8.5f * u), width = 0.9f, passes = 1) }
    pencil.erase(Path().apply { addPath(wool) }.union(head))
    pencil.outline(wool, width = 1f)
    pencil.outline(head, width = 0.9f)
    pencil.hatch(head, head.getBounds(), spacing = 1.4f, width = 0.6f)
}

/** Le traîneau du père Noël et deux rennes, qui filent vers la droite. */
private fun DrawScope.sleigh(pencil: Pencil, at: Offset) {
    val u = density * 1.2f
    val x = at.x - 18f * u
    val y = at.y + 4f * u
    val box = polygon(listOf(Offset(x - 8f * u, y - 9f * u), Offset(x - 6f * u, y), Offset(x + 7f * u, y), Offset(x + 8f * u, y - 4f * u), Offset(x - 3f * u, y - 5f * u)))
    pencil.erase(box)
    pencil.outline(box, width = 1f)
    pencil.hatch(box, box.getBounds(), spacing = 2.4f, width = 0.6f)
    // Patin recourbé à l'avant.
    pencil.stroke(listOf(Offset(x - 9f * u, y + 3f * u), Offset(x + 8f * u, y + 3f * u), Offset(x + 11f * u, y + 1f * u), Offset(x + 10f * u, y - 1f * u)), width = 1f)
    pencil.line(Offset(x - 4f * u, y), Offset(x - 4f * u, y + 3f * u), width = 0.7f, passes = 1)
    pencil.line(Offset(x + 4f * u, y), Offset(x + 4f * u, y + 3f * u), width = 0.7f, passes = 1)
    // Le père Noël : tête, bonnet, et sa hotte.
    pencil.outline(oval(Offset(x - 2f * u, y - 8f * u), 2f * u, 2f * u), width = 0.8f, passes = 1)
    pencil.stroke(listOf(Offset(x - 4f * u, y - 9.5f * u), Offset(x - 5.5f * u, y - 13f * u), Offset(x - 0.5f * u, y - 10f * u)), width = 0.8f, passes = 1)
    pencil.outline(oval(Offset(x - 7f * u, y - 9.5f * u), 2.8f * u, 3f * u), width = 0.8f, passes = 1)
    val reins = mutableListOf(Offset(x + 8f * u, y - 3f * u))
    listOf(Offset(at.x + 4f * u, at.y - 2f * u), Offset(at.x + 17f * u, at.y - 5f * u)).forEach { body ->
        reindeer(pencil, body)
        reins += body + Offset(4f * u, -2f * u)
    }
    pencil.stroke(reins, width = 0.5f, passes = 1)
}

private fun DrawScope.reindeer(pencil: Pencil, body: Offset) {
    val u = density * 1.2f
    val torso = oval(body, 4.5f * u, 2f * u)
    pencil.erase(torso)
    pencil.outline(torso, width = 0.9f)
    // Au galop : pattes avant tendues vers l'avant, arrière vers l'arrière.
    pencil.line(body + Offset(3f * u, 1.5f * u), body + Offset(7f * u, 4f * u), width = 0.7f, passes = 1)
    pencil.line(body + Offset(2f * u, 1.5f * u), body + Offset(5f * u, 5f * u), width = 0.7f, passes = 1)
    pencil.line(body + Offset(-3f * u, 1.5f * u), body + Offset(-7f * u, 3.5f * u), width = 0.7f, passes = 1)
    pencil.line(body + Offset(-2f * u, 1.5f * u), body + Offset(-5f * u, 5f * u), width = 0.7f, passes = 1)
    val head = body + Offset(6.5f * u, -4.5f * u)
    pencil.line(body + Offset(3.5f * u, -1f * u), head, width = 0.9f, passes = 1)
    pencil.outline(oval(head + Offset(1f * u, 0f), 1.8f * u, 1.1f * u), width = 0.7f, passes = 1)
    // Les bois.
    pencil.stroke(listOf(head + Offset(-0.5f * u, -1f * u), head + Offset(-1.5f * u, -4.5f * u), head + Offset(-3f * u, -5.5f * u)), width = 0.6f, passes = 1)
    pencil.line(head + Offset(-1f * u, -3f * u), head + Offset(0.5f * u, -5f * u), width = 0.6f, passes = 1)
}

private fun DrawScope.fireworks(pencil: Pencil, w: Float, h: Float) {
    val u = density
    listOf(Triple(0.44f, 0.49f, 12f), Triple(0.73f, 0.45f, 11f), Triple(0.70f, 0.61f, 8f)).forEachIndexed { n, (x, y, r) ->
        val c = Offset(w * x, h * y)
        for (k in 0 until 12) {
            val a = (k * 30.0 + n * 11) * PI / 180
            val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
            pencil.line(c + dir * (r * 0.35f * u), c + dir * (r * u), width = 0.8f, passes = 1, wobble = 0.2f)
            pencil.dot(c + dir * (r * 1.2f * u), 0.7f)
        }
    }
    // La fusée qui monte vers la première gerbe.
    var y = h * 0.92f
    while (y > h * 0.49f + 15f * u) {
        pencil.line(Offset(w * 0.44f, y), Offset(w * 0.44f, y - 2.5f * u), width = 0.7f, passes = 1, wobble = 0.1f)
        y -= 6f * u
    }
}

private fun DrawScope.bat(pencil: Pencil, at: Offset) {
    val u = density
    val s = 1.7f * u
    val wing = listOf(-6f to -1f, -4.5f to -3.5f, -3.5f to -1.5f, -2f to -2.5f, 0f to 0f, 2f to -2.5f, 3.5f to -1.5f, 4.5f to -3.5f, 6f to -1f)
    pencil.stroke(wing.map { (x, y) -> at + Offset(x * s, y * s) }, width = 0.9f)
    pencil.dot(at + Offset(0f, -0.5f * u), 1.3f)
}

/** Saint-Valentin : un cœur tracé dans le ciel par un petit avion. */
private fun DrawScope.heart(pencil: Pencil, c: Offset, s: Float) {
    val shape = Path().apply {
        moveTo(c.x, c.y + s * 0.9f)
        cubicTo(c.x - s * 1.7f, c.y - s * 0.1f, c.x - s * 0.8f, c.y - s * 1.3f, c.x, c.y - s * 0.35f)
        cubicTo(c.x + s * 0.8f, c.y - s * 1.3f, c.x + s * 1.7f, c.y - s * 0.1f, c.x, c.y + s * 0.9f)
        close()
    }
    pencil.outline(shape, width = 1.1f)
    plane(pencil, c + Offset(s * 1.9f, s * 0.6f), trail = 0f)
}

/** Poisson d'avril. */
private fun DrawScope.fish(pencil: Pencil, at: Offset) {
    val u = density * 1.4f
    val body = oval(at, 9f * u, 4.5f * u)
    val tail = polygon(listOf(at + Offset(7.5f * u, 0f), at + Offset(14f * u, -5f * u), at + Offset(13f * u, 0f), at + Offset(14f * u, 5f * u)))
    val shape = Path().apply { addPath(body) }.union(tail)
    pencil.erase(shape)
    pencil.outline(shape, width = 1.1f)
    pencil.dot(at + Offset(-5.5f * u, -1f * u), 0.8f)
    pencil.stroke((0..6).map { i -> val a = -PI / 3 + 2 * PI / 3 * i / 6; at + Offset((-3f * u + 3.5f * u * cos(a)).toFloat() - 3.5f * u, (4f * u * sin(a)).toFloat()) }, width = 0.7f, passes = 1)
    // Écailles.
    for (k in 0..2) pencil.stroke((0..4).map { i -> val a = -PI / 2 + PI * i / 4; at + Offset((k * 3f * u + 1.6f * u * cos(a)).toFloat(), (1.6f * u * sin(a)).toFloat()) }, width = 0.6f, passes = 1)
    pencil.hatch(tail, tail.getBounds(), spacing = 2f, width = 0.6f)
}

/** Fête de la musique : des notes qui s'envolent. */
private fun DrawScope.notes(pencil: Pencil, w: Float, h: Float) {
    val u = density * 1.3f
    fun head(c: Offset): Offset {
        val shape = oval(c, 2.6f * u, 1.9f * u)
        pencil.outline(shape, width = 0.8f, passes = 1)
        pencil.hatch(shape, shape.getBounds(), spacing = 1.2f, width = 0.7f)
        return c + Offset(2.3f * u, -0.5f * u)
    }
    // Une croche…
    head(Offset(w * 0.43f, h * 0.53f)).let { top ->
        pencil.line(top, top + Offset(0f, -12f * u), width = 1f)
        pencil.stroke(listOf(top + Offset(0f, -12f * u), top + Offset(3f * u, -9f * u), top + Offset(4f * u, -5f * u)), width = 1f, passes = 1)
    }
    // …deux croches liées…
    val a = head(Offset(w * 0.70f, h * 0.52f))
    val b = head(Offset(w * 0.70f + 9f * u, h * 0.52f - 2f * u))
    pencil.line(a, a + Offset(0f, -12f * u), width = 1f)
    pencil.line(b, b + Offset(0f, -12f * u), width = 1f)
    pencil.line(a + Offset(0f, -12f * u), b + Offset(0f, -12f * u), width = 2.2f)
    // …et une noire.
    head(Offset(w * 0.46f, h * 0.68f)).let { top -> pencil.line(top, top + Offset(0f, -11f * u), width = 1f) }
}

// --- Au premier plan ---

private fun DrawScope.snowman(pencil: Pencil, ground: Offset) {
    val u = density * 1.1f
    val body = oval(ground + Offset(0f, -8f * u), 8.5f * u, 8f * u)
    val headC = ground + Offset(0f, -20.5f * u)
    val head = oval(headC, 5.5f * u, 5.5f * u)
    val hat = Path().apply {
        addRect(Rect(headC.x - 6f * u, headC.y - 5.5f * u, headC.x + 6f * u, headC.y - 4.3f * u))
        op(this, Path().apply { addRect(Rect(headC.x - 3.6f * u, headC.y - 11.5f * u, headC.x + 3.6f * u, headC.y - 4.6f * u)) }, PathOperation.Union)
    }
    pencil.erase(Path().apply { addPath(body) }.union(head).union(hat))
    pencil.outline(body, width = 1.1f)
    pencil.outline(head, width = 1f)
    pencil.outline(hat, width = 0.9f)
    pencil.hatch(hat, hat.getBounds(), spacing = 1.4f, width = 0.7f)
    pencil.dot(headC + Offset(-2f * u, -1f * u), 0.7f)
    pencil.dot(headC + Offset(2f * u, -1f * u), 0.7f)
    // La carotte, les boutons, les bras en branches.
    pencil.stroke(listOf(headC + Offset(0.5f * u, 0.5f * u), headC + Offset(7f * u, 1.8f * u), headC + Offset(0.5f * u, 2f * u)), width = 0.8f, passes = 1)
    listOf(-12f, -8.5f, -5f).forEach { dy -> pencil.dot(ground + Offset(0f, dy * u + 4f * u), 0.7f) }
    listOf(-1f, 1f).forEach { side ->
        val from = ground + Offset(side * 7f * u, -11f * u)
        val to = from + Offset(side * 8f * u, -6f * u)
        pencil.line(from, to, width = 0.8f, passes = 1)
        pencil.line(from + (to - from) * 0.7f, from + (to - from) * 0.7f + Offset(side * 0.5f * u, -3f * u), width = 0.6f, passes = 1)
    }
}

/** Quelqu'un sous son parapluie : on ne voit que le manteau et les bottes. */
private fun DrawScope.umbrella(pencil: Pencil, ground: Offset) {
    val u = density * 1.35f
    val top = ground + Offset(0f, -24f * u)
    val r = 11f * u
    val canopy = Path().apply {
        moveTo(top.x - r, top.y + 6f * u)
        quadraticTo(top.x - r, top.y - 3f * u, top.x, top.y - 4f * u)
        quadraticTo(top.x + r, top.y - 3f * u, top.x + r, top.y + 6f * u)
        // Bord festonné.
        for (k in 3 downTo 0) {
            val x0 = top.x - r + k * r / 2
            quadraticTo(x0 + r / 4, top.y + 3f * u, x0, top.y + 6f * u)
        }
        close()
    }
    val coat = polygon(listOf(ground + Offset(-3.5f * u, -16f * u), ground + Offset(3.5f * u, -16f * u), ground + Offset(5f * u, -5f * u), ground + Offset(-5f * u, -5f * u)))
    pencil.erase(Path().apply { addPath(canopy) }.union(coat))
    pencil.outline(coat, width = 0.9f)
    pencil.line(ground + Offset(-2f * u, -5f * u), ground + Offset(-2f * u, 0f), width = 1.2f, passes = 1)
    pencil.line(ground + Offset(2f * u, -5f * u), ground + Offset(2.3f * u, 0f), width = 1.2f, passes = 1)
    pencil.outline(canopy, width = 1.1f)
    pencil.hatch(canopy, Rect(top.x, top.y - 4f * u, top.x + r, top.y + 6f * u), spacing = 2.2f, width = 0.6f)
    pencil.line(top + Offset(0f, -4f * u), top + Offset(0f, -7f * u), width = 0.9f, passes = 1)
}

/** Halloween : une citrouille sculptée. */
private fun DrawScope.pumpkin(pencil: Pencil, ground: Offset) {
    val u = density * 1.6f
    val c = ground + Offset(0f, -7f * u)
    val shape = oval(c, 10f * u, 7f * u).union(oval(c + Offset(-4f * u, 0f), 6f * u, 7f * u)).union(oval(c + Offset(4f * u, 0f), 6f * u, 7f * u))
    pencil.erase(shape)
    pencil.outline(shape, width = 1.1f)
    // Côtes.
    listOf(-1f, 1f).forEach { side -> pencil.stroke((0..6).map { i -> val a = -PI / 2 + PI * i / 6; c + Offset((side * 3.5f * u * cos(a)).toFloat(), (6.8f * u * sin(a)).toFloat()) }, width = 0.6f, passes = 1) }
    pencil.stroke(listOf(c + Offset(0f, -7f * u), c + Offset(0.5f * u, -10f * u), c + Offset(2.5f * u, -11f * u)), width = 1.2f)
    // Les yeux et la bouche, hachurés.
    val face = polygon(listOf(c + Offset(-5f * u, -1f * u), c + Offset(-3.5f * u, -3.5f * u), c + Offset(-2f * u, -1f * u)))
        .union(polygon(listOf(c + Offset(2f * u, -1f * u), c + Offset(3.5f * u, -3.5f * u), c + Offset(5f * u, -1f * u))))
        .union(polygon(listOf(c + Offset(-5f * u, 1.5f * u), c + Offset(-3f * u, 3f * u), c + Offset(-1.5f * u, 2f * u), c + Offset(0f, 3.5f * u), c + Offset(1.5f * u, 2f * u), c + Offset(3f * u, 3f * u), c + Offset(5f * u, 1.5f * u), c + Offset(0f, 5f * u))))
    pencil.hatch(face, face.getBounds(), spacing = 1.2f, width = 0.6f)
}

/** Pâques : des œufs décorés cachés dans l'herbe. */
private fun DrawScope.eggs(pencil: Pencil, w: Float, h: Float) {
    val u = density * 1.5f
    listOf(0.37f, 0.415f, 0.46f).forEachIndexed { k, x ->
        val base = Offset(w * x, h * GROUND)
        val c = base + Offset(0f, -5.5f * u)
        val egg = Path().apply {
            moveTo(c.x, c.y - 6f * u)
            cubicTo(c.x + 5f * u, c.y - 6f * u, c.x + 5f * u, c.y + 5.5f * u, c.x, c.y + 5.5f * u)
            cubicTo(c.x - 5f * u, c.y + 5.5f * u, c.x - 5f * u, c.y - 6f * u, c.x, c.y - 6f * u)
            close()
        }
        pencil.erase(egg)
        pencil.outline(egg, width = 1f)
        when (k) {
            0 -> pencil.stroke((0..6).map { i -> c + Offset((-3.6f + 1.2f * i) * u, if (i % 2 == 0) 0f else -1.5f * u) }, width = 0.7f, passes = 1)
            1 -> listOf(-2f to -2.5f, 1.5f to -1f, -1f to 1.5f, 2f to 2.5f, 0f to -4f).forEach { (dx, dy) -> pencil.dot(c + Offset(dx * u, dy * u), 0.6f) }
            else -> {
                val band = Path().apply { addRect(Rect(c.x - 5f * u, c.y - 1.2f * u, c.x + 5f * u, c.y + 1.2f * u)) }
                band.op(band, egg, PathOperation.Intersect)
                pencil.hatch(band, band.getBounds(), spacing = 1.3f, width = 0.6f)
            }
        }
        // Herbe devant.
        listOf(-5f, -2.5f, 1f, 4f).forEach { dx -> pencil.line(base + Offset(dx * u, 0f), base + Offset((dx + 0.8f) * u, -3.5f * u), width = 0.6f, passes = 1, wobble = 0.2f) }
    }
}

/** Vendredi 13 : un chat noir assis. */
private fun DrawScope.cat(pencil: Pencil, ground: Offset) {
    val u = density * 1.3f
    val body = oval(ground + Offset(0f, -7f * u), 5.5f * u, 7f * u)
    val headC = ground + Offset(0.8f * u, -16.5f * u)
    val head = oval(headC, 4.2f * u, 3.8f * u)
    val ears = polygon(listOf(headC + Offset(-3.8f * u, -1.5f * u), headC + Offset(-3f * u, -6.5f * u), headC + Offset(-0.8f * u, -3.5f * u)))
        .union(polygon(listOf(headC + Offset(3.8f * u, -1.5f * u), headC + Offset(3f * u, -6.5f * u), headC + Offset(0.8f * u, -3.5f * u))))
    val shape = Path().apply { addPath(body) }.union(head).union(ears)
    pencil.erase(shape)
    pencil.outline(shape, width = 1f)
    pencil.hatch(shape, shape.getBounds(), spacing = 1.5f, width = 0.7f)
    // La queue enroulée, et deux yeux qui restent clairs.
    pencil.stroke((0..10).map { i -> val t = i / 10f; ground + Offset((4f + 6f * t) * u, (-1f - 7f * t * t) * u + (sin(t * PI) * 2.0 * u).toFloat()) }, width = 1.1f)
    listOf(-1.5f, 2.6f).forEach { dx -> pencil.erase(oval(headC + Offset(dx * u, -0.4f * u), 0.9f * u, 0.6f * u)) }
}

/** 1er mai : un brin de muguet, sa tige en crosse et ses clochettes, deux feuilles en lame. */
private fun DrawScope.lily(pencil: Pencil, ground: Offset) {
    val u = density * 1.6f
    listOf(Triple(-1f, 20f, 7f), Triple(1f, 12f, 4f)).forEach { (side, len, bulge) ->
        val tip = ground + Offset(side * 3f * u, -len * u)
        val leaf = Path().apply {
            moveTo(ground.x, ground.y)
            quadraticTo(ground.x + side * bulge * u, ground.y - len * 0.45f * u, tip.x, tip.y)
            quadraticTo(ground.x + side * 0.5f * u, ground.y - len * 0.5f * u, ground.x, ground.y)
            close()
        }
        pencil.erase(leaf)
        pencil.outline(leaf, width = 0.9f)
        pencil.hatch(leaf, leaf.getBounds(), spacing = 3f, width = 0.5f)
    }
    // La tige monte, puis s'arrondit vers la droite.
    val bend = ground + Offset(7f * u, -15f * u)
    val radius = 7f * u
    fun onArc(deg: Double) = bend + Offset((radius * cos(deg * PI / 180)).toFloat(), (-radius * sin(deg * PI / 180)).toFloat())
    val stem = listOf(ground, ground + Offset(0.3f * u, -8f * u)) + (180 downTo -25 step 15).map { onArc(it.toDouble()) }
    pencil.stroke(stem, width = 1f)
    // Clochettes pendues sous la crosse.
    listOf(115.0, 70.0, 28.0, -12.0).forEachIndexed { k, deg ->
        val p = onArc(deg)
        val bell = p + Offset(0f, (3.2f - k * 0.2f) * u)
        pencil.line(p, bell + Offset(0f, -1.6f * u), width = 0.5f, passes = 1, wobble = 0.1f)
        val r = (1.9f - k * 0.15f) * u
        val shape = Path().apply {
            moveTo(bell.x - r, bell.y + r * 0.9f)
            cubicTo(bell.x - r, bell.y - r * 1.1f, bell.x + r, bell.y - r * 1.1f, bell.x + r, bell.y + r * 0.9f)
            // Bord retroussé en petites dents.
            lineTo(bell.x + r * 0.5f, bell.y + r * 0.5f)
            lineTo(bell.x, bell.y + r * 0.95f)
            lineTo(bell.x - r * 0.5f, bell.y + r * 0.5f)
            close()
        }
        pencil.erase(shape)
        pencil.outline(shape, width = 0.8f, passes = 1)
    }
}
