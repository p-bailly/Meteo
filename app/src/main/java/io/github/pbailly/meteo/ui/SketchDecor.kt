package io.github.pbailly.meteo.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/*
 * Décor et surprises du dessin aux crayons d'Aujourd'hui. Repères en fraction de la largeur et de la hauteur : sous la
 * ligne de la date, le ciel libre va de x 0,38 à 0,80 et de y 0,40 à 0,66 (soleil ou lune au milieu) ; le relief
 * monte du pied (y 0,93) jusqu'à y 0,62 au plus ; le premier plan est tout en bas.
 */

/** Pied du relief, sol du premier plan, et rivage quand le lieu est au bord de la mer. */
private const val FOOT = 0.93f
private const val GROUND = 0.975f
private const val SEA = 0.935f

private fun polygon(points: List<Offset>) = Path().apply {
    moveTo(points.first().x, points.first().y)
    points.drop(1).forEach { lineTo(it.x, it.y) }
    close()
}

private fun oval(center: Offset, rx: Float, ry: Float) = Path().apply { addOval(Rect(center.x - rx, center.y - ry, center.x + rx, center.y + ry)) }

private fun Path.union(other: Path) = apply { op(this, other, PathOperation.Union) }

/** La surface sous une ligne de crête, jusqu'en bas du dessin. */
private fun DrawScope.below(line: List<Offset>) = Path().apply {
    moveTo(line.first().x, line.first().y)
    line.drop(1).forEach { lineTo(it.x, it.y) }
    lineTo(size.width, size.height)
    lineTo(0f, size.height)
    close()
}

/** Où poser les choses : le village près du lieu, l'objet du premier plan ailleurs, jamais sur l'eau. */
private class Layout(val village: Float?, val foreground: Float)

private fun layoutOf(scene: SketchScene): Layout {
    val t = scene.terrain
    fun dry(x: Float) = !t.isWater(x, 0.03f)
    val village = if (Decor.VILLAGE in scene.decor || Decor.CHALET in scene.decor) {
        // Au fond de la vallée, juste à côté du lieu.
        (listOf(0.06f, 0.09f, 0.12f, 0.15f).map { t.placeX + it } + listOf(t.placeX - 0.03f, 0.40f))
            .map { it.coerceIn(0.40f, 0.70f) }
            .firstOrNull { x -> dry(x) && dry(x - 0.07f) && dry(x + 0.07f) && t.heightAt(x) < 0.09f }
    } else {
        null
    }
    val foreground = listOf(0.57f, 0.50f, 0.63f, 0.44f).firstOrNull { x -> dry(x) && (village == null || abs(x - village) > 0.11f) }
        ?: 0.57f
    return Layout(village, foreground)
}

internal fun DrawScope.landscape(pencil: Pencil, scene: SketchScene, w: Float, h: Float, month: Int) {
    val t = scene.terrain
    val rnd = Random(scene.seed)
    val u = density
    val ridge = t.ridge.map { (x, lift) -> Offset(w * x, h * (FOOT - lift)) }
    val land = below(ridge)
    // Étages en couleur : prés ou forêts en bas, roche au-dessus de la limite des arbres, neige en haut ; seulement au
    // milieu, pas sous le texte des côtés, avec des bords irréguliers comme un coloriage.
    val area = Rect(w * 0.26f, 0f, w * 0.75f, h)
    val step = 5f * u
    val rows = (0..(h / step).toInt() + 1).map { it * step }
    val colored = polygon(
        rows.map { y -> Offset(area.right + (rnd.nextFloat() - 0.5f) * 8f * u, y) } +
            rows.reversed().map { y -> Offset(area.left + (rnd.nextFloat() - 0.5f) * 8f * u, y) },
    )
    val snowY = h * (FOOT - t.snowline)
    val treeY = h * (FOOT - t.treeline)
    val mountains = t.relief == Relief.MOUNTAINS || t.relief == Relief.HIGH_MOUNTAINS
    // Quand il neige au lieu même, tout est blanc.
    val allSnow = t.snowline <= 0f
    val farX = w * t.farX
    clipPath(colored) {
        val lowTop = maxOf(snowY, treeY)
        if (!allSnow && lowTop < h) {
            pencil.ink(if (mountains) Crayon.FOREST else Crayon.MEADOW)
                .hatch(land, Rect(area.left, lowTop.coerceAtLeast(0f), minOf(area.right, farX), h), spacing = if (mountains) 5f else 6.5f)
        }
        if (!allSnow && treeY > snowY && treeY > 0f) {
            pencil.ink(Crayon.ROCK).hatch(land, Rect(area.left, snowY.coerceAtLeast(0f), minOf(area.right, farX), treeY), spacing = 5.5f)
        }
        // Au loin, le relief bleuit (perspective aérienne).
        if (!allSnow && farX < area.right) pencil.ink(Crayon.FAR).hatch(land, Rect(farX, snowY.coerceAtLeast(0f), area.right, h), spacing = 5f)
        val snowBottom = if (allSnow) h else snowY.coerceAtMost(h)
        if (snowBottom > 0f) pencil.ink(Crayon.SNOW).hatch(land, Rect(area.left, 0f, area.right, snowBottom), spacing = 11f, width = 0.6f)
    }
    pencil.stroke(ridge, width = 1.3f, shake = 0.35f)

    // Lacs et mer : on efface le relief sous la surface, puis des rides.
    t.water.forEach { run ->
        val x0 = w * run.start
        val x1 = w * run.endInclusive
        val surface = h * (FOOT - t.heightAt((run.start + run.endInclusive) / 2))
        pencil.erase(Path().apply { addRect(Rect(x0, surface, x1, h)) })
        pencil.line(Offset(x0, surface), Offset(x1, surface), width = 1f)
        val ink = pencil.ink(Crayon.WATER)
        var y = surface + 2.5f * u
        while (y < h) {
            var x = x0 + rnd.nextFloat() * 6f * u
            while (x < x1 - 4f * u) {
                val len = minOf((5f + rnd.nextFloat() * 10f) * u, x1 - x)
                ink.line(Offset(x, y), Offset(x + len, y), width = 0.8f, passes = 1, wobble = 0.3f)
                x += len + (4f + rnd.nextFloat() * 9f) * u
            }
            y += 3f * u
        }
    }

    // Au bord de la mer : la mer au premier plan, devant le relief.
    if (t.sea) {
        val shore = h * SEA
        pencil.erase(Path().apply { addRect(Rect(w * 0.27f, shore, w * 0.93f, h)) })
        pencil.line(Offset(w * 0.27f, shore), Offset(w * 0.93f, shore), width = 1f)
        val ink = pencil.ink(Crayon.WATER)
        var y = shore + 2.5f * u
        while (y < h) {
            var x = w * 0.28f + rnd.nextFloat() * 6f * u
            while (x < w * 0.92f) {
                val len = (5f + rnd.nextFloat() * 10f) * u
                ink.line(Offset(x, y), Offset(x + len, y), width = 0.8f, passes = 1, wobble = 0.3f)
                x += len + (4f + rnd.nextFloat() * 9f) * u
            }
            y += 3f * u
        }
    }

    val layout = layoutOf(scene)
    if (Decor.FIELDS in scene.decor && !t.sea) fields(pencil, t, w, h, month)
    if (Decor.FIRS in scene.decor) {
        // Des sapins sur les pentes, sous la limite des arbres et hors de la neige.
        val spots = (0..12).map { 0.42f + it * 0.028f }.filter { x ->
            val lift = t.heightAt(x)
            !t.isWater(x, 0.02f) && x < t.farX - 0.02f && lift > 0.01f && lift < minOf(t.treeline, t.snowline) - 0.01f &&
                (layout.village == null || abs(x - layout.village) > 0.08f)
        }.shuffled(rnd).take(6).sorted()
        spots.forEach { x ->
            val px = x + (rnd.nextFloat() - 0.5f) * 0.01f
            fir(pencil, Offset(w * px, h * (FOOT - t.heightAt(px)) + 2f * u), (12f + rnd.nextFloat() * 9f) * u)
        }
    }
    if (Decor.TREES in scene.decor) {
        listOf(0.47f, 0.555f, 0.65f).filter { !t.isWater(it, 0.02f) }.forEach { x ->
            tree(pencil, Offset(w * x, h * (FOOT - t.heightAt(x)) + 2f * u), (14f + rnd.nextFloat() * 6f) * u, month)
        }
    }
    layout.village?.let { x ->
        val at = { p: Float -> Offset(w * p, h * (FOOT - t.heightAt(p)) + 1f * u) }
        val chimney = if (Decor.CHALET in scene.decor) {
            chalet(pencil, at(x), 18f * u)
        } else {
            church(pencil, at(x))
            house(pencil, at(x - 0.07f), 13f * u, chimney = false)
            house(pencil, at(x + 0.075f), 15f * u, chimney = true)
        }
        if (Surprise.SMOKE in scene.surprises && chimney != null) smoke(pencil, chimney)
    }
    if (Surprise.SUMMIT_CROSS in scene.surprises) {
        val top = ridge.filter { it.x in w * 0.36f..w * 0.84f }.minByOrNull { it.y }
        if (top != null) {
            pencil.line(top, top + Offset(0f, -11f * u), width = 1f)
            pencil.line(top + Offset(-3.5f * u, -8f * u), top + Offset(3.5f * u, -8f * u), width = 1f)
        }
    }
    // Sur la mer ou le plus grand lac : un voilier, ou le monstre du Loch Ness.
    if (t.sea) {
        if (Surprise.SAILBOAT in scene.surprises) sailboat(pencil, Offset(w * 0.70f, h * SEA + 4f * u))
    } else {
        t.water.maxByOrNull { it.endInclusive - it.start }?.let { run ->
            val mid = (run.start + run.endInclusive) / 2
            val x = mid.coerceIn(0.40f, 0.80f)
            val surface = h * (FOOT - t.heightAt(mid))
            if (Surprise.SAILBOAT in scene.surprises) sailboat(pencil, Offset(w * x, surface + 4f * u))
            if (Surprise.NESSIE in scene.surprises) nessie(pencil, Offset(w * x - 8f * u, surface + 6f * u))
        }
    }
}

/** Plaine : sillons au premier plan, couleur de la saison (pousses, blé mûr, terre retournée). */
private fun DrawScope.fields(pencil: Pencil, t: SketchTerrain, w: Float, h: Float, month: Int) {
    val ink = pencil.ink(
        when (month) {
            in 3..5 -> Crayon.MEADOW
            in 6..8 -> Crayon.WHEAT
            else -> Crayon.EARTH
        },
    )
    for (k in 0..3) {
        val y = h * (0.945f + 0.013f * k)
        val points = (0..10).map { i ->
            val x = 0.33f + 0.53f * i / 10
            Offset(w * x, y + (sin(i * 0.9 + k) * 0.8 * density).toFloat())
        }.filter { !t.isWater(it.x / w) }
        if (points.size > 1) ink.stroke(points, width = 0.9f, passes = 1)
    }
}

/** Sapin : trois étages en dents de scie et un bout de tronc ; vert, ombré à droite. */
private fun DrawScope.fir(pencil: Pencil, base: Offset, height: Float) {
    val x = base.x
    val top = base.y - height
    val half = listOf(0.35f to 0.20f, 0.33f to 0.10f, 0.66f to 0.30f, 0.64f to 0.14f, 0.90f to 0.40f)
    val right = half.map { (dy, dx) -> Offset(x + dx * height, top + dy * height) }
    val left = half.reversed().map { (dy, dx) -> Offset(x - dx * height, top + dy * height) }
    val shape = polygon(listOf(Offset(x, top)) + right + left)
    pencil.erase(shape)
    pencil.outline(shape, width = 1f)
    pencil.ink(Crayon.FOREST).hatch(shape, Rect(x - height * 0.4f, top, x + height * 0.4f, top + height), spacing = 2.4f, width = 0.7f)
    pencil.ink(Crayon.WOOD).line(Offset(x, top + height * 0.9f), base, width = 1.2f)
}

/** Arbre à feuilles : vert, orangé à l'automne, nu en hiver. */
private fun DrawScope.tree(pencil: Pencil, base: Offset, height: Float, month: Int) {
    val wood = pencil.ink(Crayon.WOOD)
    val crownC = base + Offset(0f, -height * 0.68f)
    if (month == 12 || month in 1..2) {
        wood.line(base, crownC + Offset(0f, -height * 0.25f), width = 1.3f)
        listOf(-1f, 1f).forEach { side ->
            val from = base + Offset(0f, -height * 0.45f)
            wood.line(from, from + Offset(side * height * 0.32f, -height * 0.38f), width = 0.9f, passes = 1)
            wood.line(from + Offset(0f, -height * 0.2f), from + Offset(side * height * 0.2f, -height * 0.52f), width = 0.8f, passes = 1)
        }
        return
    }
    val r = height * 0.3f
    val crown = oval(crownC, r, r).union(oval(crownC + Offset(-r * 0.7f, r * 0.35f), r * 0.75f, r * 0.7f))
        .union(oval(crownC + Offset(r * 0.7f, r * 0.3f), r * 0.8f, r * 0.75f))
    pencil.erase(crown)
    wood.line(base, crownC + Offset(0f, r * 0.6f), width = 1.4f)
    pencil.outline(crown, width = 1f)
    pencil.ink(if (month in 10..11) Crayon.AUTUMN else Crayon.MEADOW).hatch(crown, crown.getBounds(), spacing = 2.4f, width = 0.7f)
}

private fun DrawScope.church(pencil: Pencil, ground: Offset) {
    val u = density
    val x = ground.x
    val g = ground.y
    val t = g - 23f * u
    val tower = Path().apply { addRect(Rect(x - 4.5f * u, t, x + 4.5f * u, g)) }
    // Le dôme « à l'impériale » des clochers comtois, en tuiles vernissées.
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
    val roof = polygon(listOf(Offset(x - 22f * u, g - 10f * u), Offset(x - 13.2f * u, g - 17f * u), Offset(x - 4.5f * u, g - 10f * u)))
    pencil.erase(Path().apply { addPath(tower) }.union(dome).union(nave))
    pencil.outline(nave, width = 1f)
    pencil.ink(Crayon.ROOF).hatch(roof, roof.getBounds(), spacing = 2.2f, width = 0.7f)
    pencil.outline(tower, width = 1.1f)
    pencil.outline(dome, width = 1f)
    pencil.ink(Crayon.DOME).hatch(dome, Rect(x - 6f * u, t - 14f * u, x + 6f * u, t), spacing = 1.8f, width = 0.7f)
    pencil.line(Offset(x, t - 14f * u), Offset(x, t - 20f * u), width = 0.9f)
    pencil.line(Offset(x - 2f * u, t - 18f * u), Offset(x + 2f * u, t - 18f * u), width = 0.8f, passes = 1)
    // L'horloge et l'abat-son du clocher, la porte de l'église.
    pencil.outline(oval(Offset(x, t + 5f * u), 2f * u, 2f * u), width = 0.7f, passes = 1)
    pencil.line(Offset(x - 1.6f * u, t + 10f * u), Offset(x + 1.6f * u, t + 10f * u), width = 0.7f, passes = 1)
    pencil.line(Offset(x - 1.6f * u, t + 12f * u), Offset(x + 1.6f * u, t + 12f * u), width = 0.7f, passes = 1)
    pencil.stroke(listOf(Offset(x - 15f * u, g), Offset(x - 15f * u, g - 5f * u), Offset(x - 13.2f * u, g - 7f * u), Offset(x - 11.4f * u, g - 5f * u), Offset(x - 11.4f * u, g)), width = 0.8f, passes = 1)
}

/** Maison : murs, toit de tuiles, une fenêtre ; renvoie le haut de la cheminée s'il y en a une. */
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
    pencil.ink(Crayon.ROOF).hatch(roof, roof.getBounds(), spacing = 2f, width = 0.7f)
    pencil.outline(Path().apply { addRect(Rect(x - 1.5f * u, g - wall * 0.7f, x + 1.5f * u, g - wall * 0.7f + 3f * u)) }, width = 0.7f, passes = 1)
    return if (chimney) Offset(x + width * 0.25f, g - wall - width * 0.55f) else null
}

/** Chalet : murs de bois, grand toit qui déborde, balcon ; renvoie le haut de sa cheminée. */
private fun DrawScope.chalet(pencil: Pencil, ground: Offset, width: Float): Offset {
    val u = density
    val x = ground.x
    val g = ground.y
    val wall = width * 0.55f
    val walls = Path().apply { addRect(Rect(x - width / 2, g - wall, x + width / 2, g)) }
    val roof = polygon(listOf(Offset(x - width / 2 - 4f * u, g - wall + 1f * u), Offset(x, g - wall - width * 0.32f), Offset(x + width / 2 + 4f * u, g - wall + 1f * u)))
    val stack = Path().apply { addRect(Rect(x + width * 0.2f, g - wall - width * 0.36f, x + width * 0.32f, g - wall - width * 0.1f)) }
    pencil.erase(Path().apply { addPath(walls) }.union(roof).union(stack))
    pencil.outline(walls, width = 1f)
    pencil.ink(Crayon.WOOD).hatch(walls, walls.getBounds(), spacing = 2.2f, width = 0.6f)
    pencil.outline(stack, width = 0.9f, passes = 1)
    pencil.outline(roof, width = 1.1f)
    pencil.ink(Crayon.ROCK).hatch(roof, roof.getBounds(), spacing = 2f, width = 0.6f)
    // Le balcon.
    pencil.line(Offset(x - width / 2 - 1f * u, g - wall * 0.45f), Offset(x + width / 2 + 1f * u, g - wall * 0.45f), width = 0.9f, passes = 1)
    return Offset(x + width * 0.26f, g - wall - width * 0.38f)
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
    pencil.ink(Crayon.RED).hatch(hull, hull.getBounds(), spacing = 1.6f, width = 0.7f)
    pencil.outline(sail, width = 1f)
    pencil.outline(jib, width = 0.9f)
    pencil.ink(Crayon.WOOD).line(Offset(x, y), Offset(x, y - 22f * u), width = 1f)
}

/** Le monstre du Loch Ness : deux bosses et un long cou vert qui sortent de l'eau. */
private fun DrawScope.nessie(pencil: Pencil, water: Offset) {
    val u = density * 1.3f
    val green = pencil.ink(Crayon.FOREST)
    listOf(-12f to 5f, -1f to 6.5f).forEach { (dx, r) ->
        val hump = Path().apply { addArc(Rect(water + Offset(dx * u, 0f), r * u), 180f, 180f); close() }
        pencil.erase(hump)
        green.stroke((0..10).map { i -> val a = PI + PI * i / 10; water + Offset((dx * u + r * u * cos(a)).toFloat(), (r * u * sin(a)).toFloat()) }, width = 1.1f)
        green.hatch(hump, hump.getBounds(), spacing = 2.2f, width = 0.6f)
    }
    val neck = listOf(Offset(water.x + 9f * u, water.y), Offset(water.x + 10f * u, water.y - 8f * u), Offset(water.x + 13f * u, water.y - 15f * u), Offset(water.x + 17f * u, water.y - 16f * u))
    val back = listOf(Offset(water.x + 18f * u, water.y - 13.5f * u), Offset(water.x + 15f * u, water.y - 12f * u), Offset(water.x + 14f * u, water.y - 6f * u), Offset(water.x + 14f * u, water.y))
    pencil.erase(polygon(neck + back))
    green.stroke(neck + back, width = 1.1f)
    pencil.dot(Offset(water.x + 16f * u, water.y - 14.6f * u), 0.6f)
    pencil.ink(Crayon.WATER).line(Offset(water.x - 20f * u, water.y + 1f * u), Offset(water.x + 22f * u, water.y + 1f * u), width = 0.8f, passes = 1)
}

internal fun DrawScope.surprises(pencil: Pencil, scene: SketchScene, w: Float, h: Float) {
    // Au bord de la mer, les objets du premier plan sont sur le rivage.
    val ground = Offset(w * layoutOf(scene).foreground, h * if (scene.terrain.sea) SEA else GROUND)
    for (surprise in scene.surprises) when (surprise) {
        Surprise.BIRDS -> birds(pencil, w, h)
        Surprise.PLANE -> plane(pencil, Offset(w * 0.87f, h * 0.42f), trail = w * 0.14f)
        Surprise.PARAGLIDER -> paraglider(pencil, Offset(w * 0.81f, h * 0.43f))
        Surprise.BALLOON -> balloon(pencil, Offset(w * 0.43f, h * 0.48f))
        Surprise.SHOOTING_STAR -> shootingStar(pencil, Offset(w * 0.75f, h * 0.53f), Offset(w * 0.88f, h * 0.41f))
        Surprise.UFO -> ufo(pencil, Offset(w * 0.81f, h * 0.46f), 24f * density, beamTo = null)
        Surprise.ABDUCTION -> {
            ufo(pencil, Offset(w * 0.81f, h * 0.42f), 30f * density, beamTo = h * 0.95f)
            sheep(pencil, Offset(w * 0.81f, h * 0.51f))
        }
        Surprise.SLEIGH -> sleigh(pencil, Offset(w * 0.80f, h * 0.46f))
        Surprise.FIREWORKS -> fireworks(pencil, w, h)
        Surprise.BATS -> listOf(0.78f to 0.43f, 0.44f to 0.48f, 0.87f to 0.50f).forEach { (x, y) -> bat(pencil, Offset(w * x, h * y)) }
        Surprise.HEART -> heart(pencil, Offset(w * 0.79f, h * 0.48f), 12f * density)
        Surprise.FISH -> fish(pencil, Offset(w * 0.80f, h * 0.46f))
        Surprise.NOTES -> notes(pencil, w, h)
        Surprise.SNOWMAN -> snowman(pencil, ground)
        Surprise.UMBRELLA -> umbrella(pencil, ground)
        Surprise.PUMPKIN -> pumpkin(pencil, ground)
        Surprise.EGGS -> eggs(pencil, ground)
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
    listOf(Triple(0.77f, 0.45f, 5.5f), Triple(0.815f, 0.42f, 4.5f), Triple(0.845f, 0.49f, 4f), Triple(0.79f, 0.53f, 3.5f)).forEach { (x, y, s) ->
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
    val orange = pencil.ink(Crayon.ORANGE)
    pencil.erase(wing)
    orange.outline(wing, width = 1.1f)
    orange.hatch(wing, wing.getBounds(), spacing = 1.6f, width = 0.7f)
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
    val red = pencil.ink(Crayon.RED)
    pencil.erase(envelope)
    red.outline(envelope, width = 1.2f)
    // Fuseaux jaunes, et l'ombre rouge à droite.
    listOf(-0.45f, 0f, 0.45f).forEach { k ->
        pencil.ink(Crayon.YELLOW).stroke((0..8).map { i -> val t = i / 8f; Offset(x + k * 9f * u * sin(PI * (0.15 + 0.85 * t)).toFloat(), y - 10f * u + 21f * u * t) }, width = 1.4f, passes = 1)
    }
    red.hatch(envelope, Rect(x + 2f * u, y - 10f * u, x + 12f * u, y + 11f * u), spacing = 2.2f, width = 0.7f)
    pencil.line(Offset(x - 3f * u, y + 11f * u), Offset(x - 2.5f * u, y + 14f * u), width = 0.6f, passes = 1, wobble = 0.1f)
    pencil.line(Offset(x + 3f * u, y + 11f * u), Offset(x + 2.5f * u, y + 14f * u), width = 0.6f, passes = 1, wobble = 0.1f)
    val basket = Path().apply { addRect(Rect(x - 2.5f * u, y + 14f * u, x + 2.5f * u, y + 17.5f * u)) }
    pencil.outline(basket, width = 0.8f, passes = 1)
    pencil.ink(Crayon.WOOD).hatch(basket, basket.getBounds(), spacing = 1.2f, width = 0.6f)
}

private fun DrawScope.shootingStar(pencil: Pencil, head: Offset, tail: Offset) {
    val u = density
    val gold = pencil.ink(Crayon.MOON)
    for (k in -1..1) gold.line(head + Offset(0f, k * 0.8f * u), tail + Offset(0f, k * 2.2f * u), width = 0.8f, passes = 1, wobble = 0.2f)
    val r = 4.5f * u
    gold.line(head + Offset(-r, 0f), head + Offset(r, 0f), width = 1.2f)
    gold.line(head + Offset(0f, -r), head + Offset(0f, r), width = 1.2f)
    gold.line(head + Offset(-r * 0.5f, -r * 0.5f), head + Offset(r * 0.5f, r * 0.5f), width = 0.9f, passes = 1)
    gold.line(head + Offset(-r * 0.5f, r * 0.5f), head + Offset(r * 0.5f, -r * 0.5f), width = 0.9f, passes = 1)
}

/** Soucoupe volante ; avec [beamTo], son rayon vert descend jusqu'au sol. */
private fun DrawScope.ufo(pencil: Pencil, at: Offset, width: Float, beamTo: Float?) {
    val u = density
    if (beamTo != null) {
        val beam = pencil.ink(Crayon.GREEN)
        listOf(-1f, 1f).forEach { side ->
            val a = at + Offset(side * width * 0.18f, width * 0.12f)
            val b = Offset(at.x + side * width * 0.55f, beamTo)
            var t = 0f
            while (t < 1f) {
                beam.line(a + (b - a) * t, a + (b - a) * (t + 0.05f), width = 0.9f, passes = 1, wobble = 0.2f)
                t += 0.09f
            }
        }
    }
    val dome = Path().apply { addArc(Rect(at.x - width * 0.22f, at.y - width * 0.3f, at.x + width * 0.22f, at.y + width * 0.1f), 180f, 180f); close() }
    val saucer = oval(at, width / 2, width * 0.13f)
    pencil.erase(Path().apply { addPath(saucer) }.union(dome))
    pencil.outline(dome, width = 1f)
    pencil.ink(Crayon.SNOW).hatch(dome, dome.getBounds(), spacing = 2f, width = 0.6f)
    pencil.outline(saucer, width = 1.2f)
    pencil.hatch(saucer, Rect(at.x - width / 2, at.y, at.x + width / 2, at.y + width * 0.13f), spacing = 2f, width = 0.6f)
    listOf(-0.3f, 0f, 0.3f).forEach { k -> pencil.ink(Crayon.GREEN).dot(at + Offset(k * width, -0.5f * u), 1.1f) }
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

/** Le traîneau rouge du père Noël et deux rennes, qui filent vers la droite. */
private fun DrawScope.sleigh(pencil: Pencil, at: Offset) {
    val u = density * 1.2f
    val x = at.x - 18f * u
    val y = at.y + 4f * u
    val red = pencil.ink(Crayon.RED)
    val box = polygon(listOf(Offset(x - 8f * u, y - 9f * u), Offset(x - 6f * u, y), Offset(x + 7f * u, y), Offset(x + 8f * u, y - 4f * u), Offset(x - 3f * u, y - 5f * u)))
    pencil.erase(box)
    pencil.outline(box, width = 1f)
    red.hatch(box, box.getBounds(), spacing = 1.6f, width = 0.7f)
    // Patin recourbé à l'avant.
    pencil.stroke(listOf(Offset(x - 9f * u, y + 3f * u), Offset(x + 8f * u, y + 3f * u), Offset(x + 11f * u, y + 1f * u), Offset(x + 10f * u, y - 1f * u)), width = 1f)
    pencil.line(Offset(x - 4f * u, y), Offset(x - 4f * u, y + 3f * u), width = 0.7f, passes = 1)
    pencil.line(Offset(x + 4f * u, y), Offset(x + 4f * u, y + 3f * u), width = 0.7f, passes = 1)
    // Le père Noël : tête, bonnet rouge, et sa hotte.
    pencil.outline(oval(Offset(x - 2f * u, y - 8f * u), 2f * u, 2f * u), width = 0.8f, passes = 1)
    red.stroke(listOf(Offset(x - 4f * u, y - 9.5f * u), Offset(x - 5.5f * u, y - 13f * u), Offset(x - 0.5f * u, y - 10f * u)), width = 1.1f)
    pencil.ink(Crayon.WOOD).outline(oval(Offset(x - 7f * u, y - 9.5f * u), 2.8f * u, 3f * u), width = 0.9f)
    val reins = mutableListOf(Offset(x + 8f * u, y - 3f * u))
    listOf(Offset(at.x + 4f * u, at.y - 2f * u), Offset(at.x + 17f * u, at.y - 5f * u)).forEach { body ->
        reindeer(pencil, body)
        reins += body + Offset(4f * u, -2f * u)
    }
    pencil.stroke(reins, width = 0.5f, passes = 1)
}

private fun DrawScope.reindeer(pencil: Pencil, body: Offset) {
    val u = density * 1.2f
    val wood = pencil.ink(Crayon.WOOD)
    val torso = oval(body, 4.5f * u, 2f * u)
    pencil.erase(torso)
    wood.outline(torso, width = 1f)
    wood.hatch(torso, torso.getBounds(), spacing = 1.6f, width = 0.6f)
    // Au galop : pattes avant tendues vers l'avant, arrière vers l'arrière.
    wood.line(body + Offset(3f * u, 1.5f * u), body + Offset(7f * u, 4f * u), width = 0.8f, passes = 1)
    wood.line(body + Offset(2f * u, 1.5f * u), body + Offset(5f * u, 5f * u), width = 0.8f, passes = 1)
    wood.line(body + Offset(-3f * u, 1.5f * u), body + Offset(-7f * u, 3.5f * u), width = 0.8f, passes = 1)
    wood.line(body + Offset(-2f * u, 1.5f * u), body + Offset(-5f * u, 5f * u), width = 0.8f, passes = 1)
    val head = body + Offset(6.5f * u, -4.5f * u)
    wood.line(body + Offset(3.5f * u, -1f * u), head, width = 1f, passes = 1)
    wood.outline(oval(head + Offset(1f * u, 0f), 1.8f * u, 1.1f * u), width = 0.8f, passes = 1)
    // Les bois.
    pencil.stroke(listOf(head + Offset(-0.5f * u, -1f * u), head + Offset(-1.5f * u, -4.5f * u), head + Offset(-3f * u, -5.5f * u)), width = 0.6f, passes = 1)
    pencil.line(head + Offset(-1f * u, -3f * u), head + Offset(0.5f * u, -5f * u), width = 0.6f, passes = 1)
}

private fun DrawScope.fireworks(pencil: Pencil, w: Float, h: Float) {
    val u = density
    val colors = listOf(Crayon.RED, Crayon.YELLOW, Crayon.BLUE)
    listOf(Triple(0.44f, 0.49f, 12f), Triple(0.80f, 0.44f, 11f), Triple(0.69f, 0.52f, 7f)).forEachIndexed { n, (x, y, r) ->
        val c = Offset(w * x, h * y)
        val ink = pencil.ink(colors[n])
        for (k in 0 until 12) {
            val a = (k * 30.0 + n * 11) * PI / 180
            val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
            ink.line(c + dir * (r * 0.35f * u), c + dir * (r * u), width = 1f, passes = 1, wobble = 0.2f)
            ink.dot(c + dir * (r * 1.2f * u), 0.8f)
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

/** Saint-Valentin : un cœur rouge tracé dans le ciel par un petit avion. */
private fun DrawScope.heart(pencil: Pencil, c: Offset, s: Float) {
    val shape = Path().apply {
        moveTo(c.x, c.y + s * 0.9f)
        cubicTo(c.x - s * 1.7f, c.y - s * 0.1f, c.x - s * 0.8f, c.y - s * 1.3f, c.x, c.y - s * 0.35f)
        cubicTo(c.x + s * 0.8f, c.y - s * 1.3f, c.x + s * 1.7f, c.y - s * 0.1f, c.x, c.y + s * 0.9f)
        close()
    }
    pencil.ink(Crayon.RED).outline(shape, width = 1.4f)
    pencil.ink(Crayon.PINK).hatch(shape, shape.getBounds(), spacing = 3f, width = 0.6f)
    plane(pencil, c + Offset(s * 1.9f, s * 0.6f), trail = 0f)
}

/** Poisson d'avril, orange. */
private fun DrawScope.fish(pencil: Pencil, at: Offset) {
    val u = density * 1.4f
    val orange = pencil.ink(Crayon.ORANGE)
    val body = oval(at, 9f * u, 4.5f * u)
    val tail = polygon(listOf(at + Offset(7.5f * u, 0f), at + Offset(14f * u, -5f * u), at + Offset(13f * u, 0f), at + Offset(14f * u, 5f * u)))
    val shape = Path().apply { addPath(body) }.union(tail)
    pencil.erase(shape)
    orange.outline(shape, width = 1.2f)
    pencil.dot(at + Offset(-5.5f * u, -1f * u), 0.8f)
    orange.stroke((0..6).map { i -> val a = -PI / 3 + 2 * PI / 3 * i / 6; at + Offset((-3f * u + 3.5f * u * cos(a)).toFloat() - 3.5f * u, (4f * u * sin(a)).toFloat()) }, width = 0.8f, passes = 1)
    // Écailles.
    for (k in 0..2) orange.stroke((0..4).map { i -> val a = -PI / 2 + PI * i / 4; at + Offset((k * 3f * u + 1.6f * u * cos(a)).toFloat(), (1.6f * u * sin(a)).toFloat()) }, width = 0.7f, passes = 1)
    orange.hatch(tail, tail.getBounds(), spacing = 1.6f, width = 0.6f)
}

/** Fête de la musique : des notes violettes qui s'envolent. */
private fun DrawScope.notes(pencil: Pencil, w: Float, h: Float) {
    val u = density * 1.3f
    val ink = pencil.ink(Crayon.VIOLET)
    fun head(c: Offset): Offset {
        val shape = oval(c, 2.6f * u, 1.9f * u)
        ink.outline(shape, width = 0.9f, passes = 1)
        ink.hatch(shape, shape.getBounds(), spacing = 1.1f, width = 0.7f)
        return c + Offset(2.3f * u, -0.5f * u)
    }
    // Une croche…
    head(Offset(w * 0.43f, h * 0.53f)).let { top ->
        ink.line(top, top + Offset(0f, -12f * u), width = 1.1f)
        ink.stroke(listOf(top + Offset(0f, -12f * u), top + Offset(3f * u, -9f * u), top + Offset(4f * u, -5f * u)), width = 1.1f, passes = 1)
    }
    // …deux croches liées…
    val a = head(Offset(w * 0.78f, h * 0.52f))
    val b = head(Offset(w * 0.78f + 9f * u, h * 0.52f - 2f * u))
    ink.line(a, a + Offset(0f, -12f * u), width = 1.1f)
    ink.line(b, b + Offset(0f, -12f * u), width = 1.1f)
    ink.line(a + Offset(0f, -12f * u), b + Offset(0f, -12f * u), width = 2.2f)
    // …et une noire.
    head(Offset(w * 0.46f, h * 0.68f)).let { top -> ink.line(top, top + Offset(0f, -11f * u), width = 1.1f) }
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
    pencil.ink(Crayon.SNOW).hatch(body, Rect(ground.x + 2f * u, ground.y - 16f * u, ground.x + 9f * u, ground.y), spacing = 2.4f, width = 0.6f)
    pencil.outline(hat, width = 0.9f)
    pencil.hatch(hat, hat.getBounds(), spacing = 1.2f, width = 0.7f)
    pencil.dot(headC + Offset(-2f * u, -1f * u), 0.7f)
    pencil.dot(headC + Offset(2f * u, -1f * u), 0.7f)
    // La carotte, les boutons, les bras en branches.
    pencil.ink(Crayon.ORANGE).stroke(listOf(headC + Offset(0.5f * u, 0.5f * u), headC + Offset(7f * u, 1.8f * u), headC + Offset(0.5f * u, 2f * u)), width = 1.1f)
    listOf(-12f, -8.5f, -5f).forEach { dy -> pencil.dot(ground + Offset(0f, dy * u + 4f * u), 0.7f) }
    val wood = pencil.ink(Crayon.WOOD)
    listOf(-1f, 1f).forEach { side ->
        val from = ground + Offset(side * 7f * u, -11f * u)
        val to = from + Offset(side * 8f * u, -6f * u)
        wood.line(from, to, width = 1f, passes = 1)
        wood.line(from + (to - from) * 0.7f, from + (to - from) * 0.7f + Offset(side * 0.5f * u, -3f * u), width = 0.8f, passes = 1)
    }
}

/** Quelqu'un sous son parapluie rouge : on ne voit que le manteau et les bottes. */
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
    val red = pencil.ink(Crayon.RED)
    pencil.erase(Path().apply { addPath(canopy) }.union(coat))
    pencil.outline(coat, width = 0.9f)
    pencil.ink(Crayon.BLUE).hatch(coat, coat.getBounds(), spacing = 2f, width = 0.6f)
    pencil.line(ground + Offset(-2f * u, -5f * u), ground + Offset(-2f * u, 0f), width = 1.2f, passes = 1)
    pencil.line(ground + Offset(2f * u, -5f * u), ground + Offset(2.3f * u, 0f), width = 1.2f, passes = 1)
    red.outline(canopy, width = 1.2f)
    red.hatch(canopy, canopy.getBounds(), spacing = 2f, width = 0.6f)
    pencil.line(top + Offset(0f, -4f * u), top + Offset(0f, -7f * u), width = 0.9f, passes = 1)
}

/** Halloween : une citrouille sculptée. */
private fun DrawScope.pumpkin(pencil: Pencil, ground: Offset) {
    val u = density * 1.6f
    val c = ground + Offset(0f, -7f * u)
    val orange = pencil.ink(Crayon.ORANGE)
    val shape = oval(c, 10f * u, 7f * u).union(oval(c + Offset(-4f * u, 0f), 6f * u, 7f * u)).union(oval(c + Offset(4f * u, 0f), 6f * u, 7f * u))
    pencil.erase(shape)
    orange.outline(shape, width = 1.3f)
    orange.hatch(shape, shape.getBounds(), spacing = 2.6f, width = 0.6f)
    // Côtes.
    listOf(-1f, 1f).forEach { side -> orange.stroke((0..6).map { i -> val a = -PI / 2 + PI * i / 6; c + Offset((side * 3.5f * u * cos(a)).toFloat(), (6.8f * u * sin(a)).toFloat()) }, width = 0.8f, passes = 1) }
    pencil.ink(Crayon.FOREST).stroke(listOf(c + Offset(0f, -7f * u), c + Offset(0.5f * u, -10f * u), c + Offset(2.5f * u, -11f * u)), width = 1.4f)
    // Les yeux et la bouche, sombres.
    val face = polygon(listOf(c + Offset(-5f * u, -1f * u), c + Offset(-3.5f * u, -3.5f * u), c + Offset(-2f * u, -1f * u)))
        .union(polygon(listOf(c + Offset(2f * u, -1f * u), c + Offset(3.5f * u, -3.5f * u), c + Offset(5f * u, -1f * u))))
        .union(polygon(listOf(c + Offset(-5f * u, 1.5f * u), c + Offset(-3f * u, 3f * u), c + Offset(-1.5f * u, 2f * u), c + Offset(0f, 3.5f * u), c + Offset(1.5f * u, 2f * u), c + Offset(3f * u, 3f * u), c + Offset(5f * u, 1.5f * u), c + Offset(0f, 5f * u))))
    pencil.erase(face)
    pencil.hatch(face, face.getBounds(), spacing = 1f, width = 0.6f)
}

/** Pâques : des œufs décorés, de toutes les couleurs, cachés dans l'herbe. */
private fun DrawScope.eggs(pencil: Pencil, ground: Offset) {
    val u = density * 1.5f
    listOf(Crayon.PINK, Crayon.BLUE, Crayon.YELLOW).forEachIndexed { k, color ->
        val base = ground + Offset((k - 1) * 11f * u, 0f)
        val c = base + Offset(0f, -5.5f * u)
        val ink = pencil.ink(color)
        val egg = Path().apply {
            moveTo(c.x, c.y - 6f * u)
            cubicTo(c.x + 5f * u, c.y - 6f * u, c.x + 5f * u, c.y + 5.5f * u, c.x, c.y + 5.5f * u)
            cubicTo(c.x - 5f * u, c.y + 5.5f * u, c.x - 5f * u, c.y - 6f * u, c.x, c.y - 6f * u)
            close()
        }
        pencil.erase(egg)
        ink.outline(egg, width = 1.2f)
        when (k) {
            0 -> ink.stroke((0..6).map { i -> c + Offset((-3.6f + 1.2f * i) * u, if (i % 2 == 0) 0f else -1.5f * u) }, width = 1f, passes = 1)
            1 -> listOf(-2f to -2.5f, 1.5f to -1f, -1f to 1.5f, 2f to 2.5f, 0f to -4f).forEach { (dx, dy) -> ink.dot(c + Offset(dx * u, dy * u), 0.9f) }
            else -> {
                val band = Path().apply { addRect(Rect(c.x - 5f * u, c.y - 1.2f * u, c.x + 5f * u, c.y + 1.2f * u)) }
                band.op(band, egg, PathOperation.Intersect)
                ink.hatch(band, band.getBounds(), spacing = 1.1f, width = 0.7f)
            }
        }
        // Herbe devant.
        listOf(-5f, -2.5f, 1f, 4f).forEach { dx -> pencil.ink(Crayon.MEADOW).line(base + Offset(dx * u, 0f), base + Offset((dx + 0.8f) * u, -3.5f * u), width = 0.8f, passes = 1, wobble = 0.2f) }
    }
}

/** Vendredi 13 : un chat noir assis, aux yeux jaunes. */
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
    // La queue enroulée, et les yeux.
    pencil.stroke((0..10).map { i -> val t = i / 10f; ground + Offset((4f + 6f * t) * u, (-1f - 7f * t * t) * u + (sin(t * PI) * 2.0 * u).toFloat()) }, width = 1.1f)
    listOf(-1.5f, 2.6f).forEach { dx ->
        val eye = headC + Offset(dx * u, -0.4f * u)
        pencil.erase(oval(eye, 0.9f * u, 0.6f * u))
        pencil.ink(Crayon.YELLOW).dot(eye, 0.7f)
    }
}

/** 1er mai : un brin de muguet, sa tige en crosse et ses clochettes, deux feuilles en lame. */
private fun DrawScope.lily(pencil: Pencil, ground: Offset) {
    val u = density * 1.6f
    val green = pencil.ink(Crayon.MEADOW)
    listOf(Triple(-1f, 20f, 7f), Triple(1f, 12f, 4f)).forEach { (side, len, bulge) ->
        val tip = ground + Offset(side * 3f * u, -len * u)
        val leaf = Path().apply {
            moveTo(ground.x, ground.y)
            quadraticTo(ground.x + side * bulge * u, ground.y - len * 0.45f * u, tip.x, tip.y)
            quadraticTo(ground.x + side * 0.5f * u, ground.y - len * 0.5f * u, ground.x, ground.y)
            close()
        }
        pencil.erase(leaf)
        green.outline(leaf, width = 1f)
        green.hatch(leaf, leaf.getBounds(), spacing = 2.2f, width = 0.6f)
    }
    // La tige monte, puis s'arrondit vers la droite.
    val bend = ground + Offset(7f * u, -15f * u)
    val radius = 7f * u
    fun onArc(deg: Double) = bend + Offset((radius * cos(deg * PI / 180)).toFloat(), (-radius * sin(deg * PI / 180)).toFloat())
    val stem = listOf(ground, ground + Offset(0.3f * u, -8f * u)) + (180 downTo -25 step 15).map { onArc(it.toDouble()) }
    green.stroke(stem, width = 1.1f)
    // Clochettes blanches pendues sous la crosse.
    listOf(115.0, 70.0, 28.0, -12.0).forEachIndexed { k, deg ->
        val p = onArc(deg)
        val bell = p + Offset(0f, (3.2f - k * 0.2f) * u)
        green.line(p, bell + Offset(0f, -1.6f * u), width = 0.6f, passes = 1, wobble = 0.1f)
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
