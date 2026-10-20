package io.github.pbailly.meteo.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * Décor et surprises du dessin aux crayons d'Aujourd'hui. Repères en fraction de la largeur et de la hauteur : sous la
 * ligne de la date, le ciel libre va de x 0,38 à 0,80 et de y 0,40 à 0,66 (soleil ou lune au milieu) ; le relief
 * monte du pied (y 0,93) jusqu'à y 0,62 au plus ; le premier plan est tout en bas.
 */

/** Pied du relief, sol du premier plan, et rivage quand le lieu est au bord de la mer. */
internal const val FOOT = 0.93f
private const val GROUND = 0.975f
private const val SEA = 0.935f
/** Milieu de la rivière, entre le pied du relief et le premier plan. */
private const val RIVER = 0.952f

private fun polygon(points: List<Offset>) = Path().apply {
    moveTo(points.first().x, points.first().y)
    points.drop(1).forEach { lineTo(it.x, it.y) }
    close()
}

private fun oval(center: Offset, rx: Float, ry: Float) = Path().apply { addOval(Rect(center.x - rx, center.y - ry, center.x + rx, center.y + ry)) }

private fun rect(left: Float, top: Float, right: Float, bottom: Float) = Path().apply { addRect(Rect(left, top, right, bottom)) }

private fun Path.union(other: Path) = apply { op(this, other, PathOperation.Union) }

/** La surface sous une ligne de crête, jusqu'en bas du dessin. */
private fun DrawScope.below(line: List<Offset>) = Path().apply {
    moveTo(line.first().x, line.first().y)
    line.drop(1).forEach { lineTo(it.x, it.y) }
    lineTo(size.width, size.height)
    lineTo(0f, size.height)
    close()
}

/** La ligne de crête, dans l'espace du dessin. */
internal fun DrawScope.ridgeLine(t: SketchTerrain): List<Offset> = t.ridge.map { (x, lift) -> Offset(size.width * x, size.height * (FOOT - lift)) }

/** Le ciel au-dessus de la crête, entre les textes des côtés : pour y colorer la lumière, y coucher le soleil. */
internal fun DrawScope.skyAbove(t: SketchTerrain, w: Float, h: Float): Path {
    val line = ridgeLine(t).filter { it.x in w * 0.28f..w * 0.82f }
    return polygon(listOf(Offset(w * 0.28f, h * 0.30f)) + line + listOf(Offset(w * 0.82f, h * 0.30f)))
}

/** Demi-largeur du lieu habité dessiné, en fraction de la largeur. */
private fun settlementHalfWidth(decor: Set<Decor>): Float? = when {
    Decor.CITY in decor -> 0.13f
    Decor.TOWN in decor -> 0.10f
    Decor.VILLAGE in decor -> 0.075f
    Decor.HAMLET in decor -> 0.05f
    Decor.FARM in decor -> 0.04f
    Decor.CHALET in decor || Decor.REFUGE in decor -> 0.025f
    else -> null
}

/** Où poser les choses : le lieu habité près du lieu, l'objet du premier plan ailleurs, jamais sur l'eau. */
private class Layout(val settlement: Float?, val half: Float, val foreground: Float)

private fun layoutOf(scene: SketchScene): Layout {
    val t = scene.terrain
    fun dry(x: Float) = !t.isWater(x, 0.02f)
    val full = settlementHalfWidth(scene.decor) ?: 0f
    var half = full
    fun span(x: Float) = (0..8).map { x - half + 2 * half * it / 8 }
    fun bumpiness(x: Float) = span(x).map { t.heightAt(it) }.let { it.max() - it.min() }
    var settlement: Float? = null
    if (Decor.REFUGE in scene.decor && t.relief == Relief.SUMMIT) {
        // Le refuge, juste sous le sommet.
        settlement = t.placeX + 0.06f
    } else if (full > 0f) {
        // Au fond de la vallée, juste à côté du lieu ; le refuge, lui, est sur la pente. Entre un lac et la montagne,
        // la ville se resserre.
        for (shrink in listOf(1f, 0.75f, 0.55f)) {
            half = full * shrink
            // Les immeubles d'une ville montent haut : ils restent à gauche du texte de droite (le ciel, les extrêmes).
            val right = if (Decor.CITY in scene.decor || Decor.TOWN in scene.decor) 0.62f else 0.74f
            val candidates = (listOf(0.06f, 0.09f, 0.12f, 0.15f, 0.03f, 0f, 0.18f).map { t.placeX + it } + listOf(0.47f, 0.55f))
                .map { it.coerceIn(0.33f + half, maxOf(0.33f + half, right - half)) }
                .filter { x -> span(x).all(::dry) }
            settlement = candidates.firstOrNull { x -> Decor.REFUGE in scene.decor || t.heightAt(x) < 0.09f && bumpiness(x) < 0.06f }
                ?: candidates.minByOrNull { t.heightAt(it) + bumpiness(it) }
            if (settlement != null) break
        }
    }
    val placed = settlement
    val foreground = listOf(0.57f, 0.50f, 0.63f, 0.44f, 0.68f)
        .firstOrNull { x -> dry(x) && (placed == null || abs(x - placed) > half + 0.04f) } ?: 0.57f
    return Layout(placed, half, foreground)
}

/** Feuillus : verts l'été, roux à l'automne, bruns et nus l'hiver. */
private fun leaves(month: Int) = when (month) {
    10, 11 -> Crayon.AUTUMN
    12, 1, 2, 3 -> Crayon.WOOD
    else -> Crayon.GREEN
}

internal fun DrawScope.landscape(pencil: Pencil, scene: SketchScene, w: Float, h: Float, month: Int, isDay: Boolean = true) {
    val t = scene.terrain
    val relief = t.relief
    val rnd = Random(scene.seed)
    val u = density
    val ridge = ridgeLine(t)
    val land = below(ridge)
    val lean = scene.lean
    // Neige au sol : les toits aussi sont blancs.
    pencil.snowyRoofs = t.snowline <= 0f
    // Étages en couleur, du bas vers le haut : prés (ou champs), forêt, alpages, roche, glaciers et neige, selon le
    // relief et l'altitude ; seulement au milieu, pas sous le texte des côtés, avec des bords irréguliers comme un
    // coloriage.
    val area = Rect(w * 0.26f, 0f, w * 0.75f, h)
    val step = 5f * u
    val rows = (0..(h / step).toInt() + 1).map { it * step }
    val colored = polygon(
        rows.map { y -> Offset(area.right + (rnd.nextFloat() - 0.5f) * 8f * u, y) } +
            rows.reversed().map { y -> Offset(area.left + (rnd.nextFloat() - 0.5f) * 8f * u, y) },
    )
    fun y(lift: Float) = h * (FOOT - lift)
    val snowY = y(t.snowline)
    // Quand il neige au lieu même, tout est blanc.
    val allSnow = t.snowline <= 0f
    val farX = w * t.farX
    val layers = buildList {
        when (relief) {
            Relief.PLAIN -> add(Float.MAX_VALUE to Crayon.MEADOW)
            // Basse montagne : prés au fond, croupes couvertes de feuillus.
            Relief.LOW -> {
                add(0.03f to Crayon.MEADOW)
                add(t.treeline to leaves(month))
            }
            else -> {
                if (relief != Relief.VERY_HIGH) add(0.02f to Crayon.MEADOW)
                add(t.treeline to Crayon.FOREST)
            }
        }
        add(t.rockline to Crayon.MEADOW)
        add(Float.MAX_VALUE to Crayon.ROCK)
    }
    clipPath(colored) {
        if (!allSnow) {
            var bottom = h
            for ((top, color) in layers) {
                val yTop = maxOf(y(top), snowY, 0f)
                if (yTop < bottom) {
                    val spacing = when (color) {
                        Crayon.MEADOW -> 6.5f
                        Crayon.ROCK -> if (relief == Relief.VERY_HIGH) 4.5f else 5.5f
                        else -> 5f
                    }
                    pencil.ink(color).hatch(land, Rect(area.left, yTop, minOf(area.right, farX), bottom), spacing = spacing)
                    bottom = yTop
                }
                if (bottom <= maxOf(snowY, 0f)) break
            }
            // Au loin, le relief bleuit (perspective aérienne).
            if (farX < area.right) pencil.ink(Crayon.FAR).hatch(land, Rect(farX, snowY.coerceAtLeast(0f), area.right, h), spacing = 5f)
        }
        val snowBottom = if (allSnow) h else snowY.coerceAtMost(h)
        if (snowBottom > 0f) pencil.ink(Crayon.SNOW).hatch(land, Rect(area.left, 0f, area.right, snowBottom), spacing = 6f, width = 0.7f)
        // Les glaciers, en toute saison : hachures croisées bleu glacier.
        val glacierY = y(t.glacierline)
        if (glacierY > 0f) pencil.ink(Crayon.SNOW).hatch(land, Rect(area.left, 0f, area.right, glacierY), spacing = 4f, width = 0.6f, rising = false)
    }
    pencil.stroke(ridge, width = if (relief >= Relief.HIGH) 1.4f else 1.3f, shake = 0.35f)

    // Lacs : on efface le relief sous la surface, puis des rides.
    // Un froid durable : les lacs gèlent (pas la mer).
    t.water.forEach { run -> water(pencil, w * run.start, w * run.endInclusive, y(t.heightAt((run.start + run.endInclusive) / 2)), rnd, ice = scene.conditions.frozen) }
    // L'île : la mer des deux côtés, là où la terre s'enfonce sous l'eau.
    if (t.island) {
        var x = 0.26f
        while (x < 0.76f) {
            if (t.heightAt(x) > 0f) { x += 0.005f; continue }
            val start = x
            while (x < 0.76f && t.heightAt(x) <= 0f) x += 0.005f
            water(pencil, w * start, w * x, h * FOOT, rnd)
        }
    }
    // Au bord de la mer : la mer au premier plan, devant le relief.
    if (t.sea) water(pencil, w * 0.27f, w * 0.93f, h * SEA, rnd)

    val layout = layoutOf(scene)
    val settled = layout.settlement?.let { (it - layout.half - 0.03f)..(it + layout.half + 0.03f) }
    // En plaine, le lointain est plat : les arbres peuvent y pousser aussi.
    fun free(x: Float) = !t.isWater(x, 0.02f) && (x < t.farX - 0.02f || relief == Relief.PLAIN) && (settled == null || x !in settled)
    if (Decor.FIELDS in scene.decor && !t.sea) fields(pencil, t, w, h, month, rnd)
    if (t.river) river(pencil, w, h, rnd)
    if (Decor.FOREST in scene.decor) forest(pencil, scene, w, h, month, rnd, ::free)
    if (Decor.FIRS in scene.decor) {
        // Des sapins sur les pentes, sous la limite des arbres et hors de la neige.
        val spots = (0..12).map { 0.42f + it * 0.028f }.filter { x ->
            val lift = t.heightAt(x)
            free(x) && lift > 0.01f && lift < minOf(t.treeline, t.snowline) - 0.01f
        }.shuffled(rnd).take(6).sorted()
        spots.forEach { x ->
            val px = x + (rnd.nextFloat() - 0.5f) * 0.01f
            fir(pencil, Offset(w * px, y(t.heightAt(px)) + 2f * u), (12f + rnd.nextFloat() * 9f) * u, lean)
        }
    }
    if (Decor.TREES in scene.decor) {
        listOf(0.47f, 0.555f, 0.65f).filter(::free).forEach { x ->
            tree(pencil, Offset(w * x, y(t.heightAt(x)) + 2f * u), (14f + rnd.nextFloat() * 6f) * u, month, lean)
        }
    }
    layout.settlement?.let { x ->
        val at = { p: Float -> Offset(w * p, y(t.heightAt(p)) + 1f * u) }
        val lit = !isDay
        val half = layout.half
        val chimney = when {
            Decor.CITY in scene.decor -> city(pencil, at, x, half, w, rnd, lit, scene.landmark)
            Decor.TOWN in scene.decor -> town(pencil, at, x, half, rnd, lit)
            Decor.VILLAGE in scene.decor -> {
                // Clocher comtois en montagne (et parfois ailleurs), sinon une flèche d'ardoise.
                if (relief.mountains || rnd.nextFloat() < 0.35f) church(pencil, at(x)) else spireChurch(pencil, at(x), 1f)
                house(pencil, at(x - 0.07f), 13f * u, chimney = false, lit = lit)
                house(pencil, at(x + 0.075f), 15f * u, chimney = true, lit = lit)
            }
            Decor.HAMLET in scene.decor -> if (relief.mountains) {
                chalet(pencil, at(x - 0.025f), 15f * u)
                chalet(pencil, at(x + 0.03f), 17f * u)
            } else {
                house(pencil, at(x - 0.025f), 13f * u, chimney = false, lit = lit)
                house(pencil, at(x + 0.025f), 14f * u, chimney = true, lit = lit)
            }
            Decor.FARM in scene.decor -> farm(pencil, at(x), 20f * u, month)
            Decor.CHALET in scene.decor -> chalet(pencil, at(x), 18f * u)
            Decor.REFUGE in scene.decor -> refuge(pencil, at(x), 14f * u)
            else -> null
        }
        if (Surprise.SMOKE in scene.surprises && chimney != null) smoke(pencil, chimney, lean)
        if (t.river && Decor.CITY !in scene.decor) bridge(pencil, Offset(w * x, h * RIVER), 10f * u)
    }
    if (Decor.SKI in scene.decor) skiArea(pencil, t, w, h, lean)
    // Les bêtes, là où le relief et la saison les mettent.
    if (Decor.COWS in scene.decor) {
        (0..14).map { 0.42f + it * 0.022f }.filter { x ->
            val lift = t.heightAt(x)
            free(x) && lift < t.snowline - 0.01f && (lift < 0.025f || lift in t.treeline..t.rockline)
        }.shuffled(rnd).take(3).forEach { x -> cow(pencil, Offset(w * x, y(t.heightAt(x)) + 1f * u), rnd.nextBoolean()) }
    }
    if (Decor.CHAMOIS in scene.decor) {
        (0..14).map { 0.44f + it * 0.02f }.filter { x ->
            val lift = t.heightAt(x)
            x < t.farX && lift > t.treeline + 0.01f && lift > 0.06f
        }.shuffled(rnd).take(2).forEach { x -> chamois(pencil, Offset(w * x, y(t.heightAt(x)) + 0.5f * u), x > 0.6f) }
    }
    if (Decor.GULLS in scene.decor) {
        listOf(Triple(0.63f, 0.72f, 7f), Triple(0.71f, 0.67f, 6f), Triple(0.55f, 0.78f, 5f)).forEach { (x, yy, size) ->
            gull(pencil, Offset(w * x, h * yy), size * u)
        }
    }
    // La manche à air, tendue par le vent.
    if (Decor.WINDSOCK in scene.decor) {
        listOf(0.68f, 0.46f, 0.72f, 0.52f).firstOrNull { x -> free(x) && abs(x - layout.foreground) > 0.06f }?.let { x ->
            windsock(pencil, Offset(w * x, y(t.heightAt(x)) + 1f * u), scene.conditions.windKmh, lean)
        }
    }
    if (Decor.LIGHTHOUSE in scene.decor) {
        // Le phare au bout de l'île, ou sur la jetée.
        val x = if (t.island) (0..32).map { 0.76f - it * 0.005f }.firstOrNull { t.heightAt(it) > 0.002f }?.minus(0.01f) ?: 0.70f else 0.72f
        lighthouse(pencil, Offset(w * x, if (t.sea && !t.island) h * SEA else y(t.heightAt(x))), 26f * u, beam = !isDay)
    }
    if (Surprise.SUMMIT_CROSS in scene.surprises) {
        // Au sommet, la croix est sur le lieu même ; ailleurs, sur le plus haut de la crête.
        val top = if (relief == Relief.SUMMIT) {
            Offset(w * t.placeX, y(t.heightAt(t.placeX)))
        } else {
            ridge.filter { it.x in w * 0.36f..w * 0.84f }.minByOrNull { it.y }
        }
        if (top != null) {
            val s = if (relief == Relief.SUMMIT) 1.6f else 1f
            pencil.line(top, top + Offset(0f, -11f * u * s), width = 1f * s)
            pencil.line(top + Offset(-3.5f * u * s, -8f * u * s), top + Offset(3.5f * u * s, -8f * u * s), width = 1f * s)
        }
    }
    // Sur la mer ou le plus grand lac : un voilier, ou le monstre du Loch Ness.
    if (t.sea) {
        val x = if (Decor.LIGHTHOUSE in scene.decor) 0.60f else 0.70f
        if (Surprise.SAILBOAT in scene.surprises) sailboat(pencil, Offset(w * x, h * SEA + 4f * u))
    } else {
        t.water.maxByOrNull { it.endInclusive - it.start }?.let { run ->
            val mid = (run.start + run.endInclusive) / 2
            val x = mid.coerceIn(0.40f, 0.80f)
            val surface = y(t.heightAt(mid))
            if (Surprise.SAILBOAT in scene.surprises) sailboat(pencil, Offset(w * x, surface + 4f * u))
            if (Surprise.NESSIE in scene.surprises) nessie(pencil, Offset(w * x - 8f * u, surface + 6f * u))
        }
    }
}

/**
 * De l'eau de [x0] à [x1] sous la surface [surface] : on efface le paysage, on trace la rive, puis des rides bleues ;
 * gelée ([ice]) : de la glace hachurée, ses fissures, et parfois quelqu'un qui patine.
 */
private fun DrawScope.water(pencil: Pencil, x0: Float, x1: Float, surface: Float, rnd: Random, ice: Boolean = false) {
    val u = density
    val h = size.height
    if (x1 - x0 < 2f * u) return
    val body = rect(x0, surface, x1, h)
    pencil.erase(body)
    pencil.line(Offset(x0, surface), Offset(x1, surface), width = 1f)
    if (ice) {
        pencil.ink(Crayon.SNOW).hatch(body, body.getBounds(), spacing = 4f, width = 0.6f, rising = false)
        repeat(3) { k ->
            var p = Offset(x0 + (x1 - x0) * (0.2f + 0.3f * k), surface + 2f * u)
            val points = mutableListOf(p)
            repeat(4) {
                p += Offset((rnd.nextFloat() - 0.5f) * 8f * u, (2f + rnd.nextFloat() * 3f) * u)
                points += p
            }
            pencil.stroke(points.filter { it.y < h }, width = 0.6f, passes = 1)
        }
        if (x1 - x0 > 40f * u && rnd.nextFloat() < 0.5f) skater(pencil, Offset((x0 + x1) / 2, minOf(surface + 9f * u, h - 1f * u)))
        return
    }
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

/** Quelqu'un qui patine sur le lac gelé, bonnet rouge, bras écartés. */
private fun DrawScope.skater(pencil: Pencil, ice: Offset) {
    val u = density
    val hip = ice + Offset(0f, -6f * u)
    val head = hip + Offset(1.5f * u, -7f * u)
    pencil.line(hip, head + Offset(-0.5f * u, 2f * u), width = 1f)
    pencil.line(hip, ice + Offset(-3f * u, 0f), width = 0.9f, passes = 1)
    pencil.line(hip, ice + Offset(4f * u, -1.5f * u), width = 0.9f, passes = 1)
    pencil.line(hip + Offset(1f * u, -4f * u), hip + Offset(-4f * u, -5f * u), width = 0.8f, passes = 1)
    pencil.line(hip + Offset(1f * u, -4f * u), hip + Offset(6f * u, -4.5f * u), width = 0.8f, passes = 1)
    pencil.outline(oval(head, 1.6f * u, 1.6f * u), width = 0.8f, passes = 1)
    pencil.ink(Crayon.RED).hatch(oval(head + Offset(0f, -1.4f * u), 1.8f * u, 1f * u), Rect(head + Offset(0f, -1.4f * u), 2f * u), spacing = 0.8f, width = 0.7f)
    pencil.line(ice + Offset(-5f * u, 0.5f * u), ice + Offset(-1f * u, 0.5f * u), width = 0.6f, passes = 1)
}

/**
 * Plaine : des parcelles au premier plan, chacune sa culture selon la saison (pousses, colza en fleur, blé mûr, terre
 * retournée), en sillons qui fuient un peu vers le fond.
 */
private fun DrawScope.fields(pencil: Pencil, t: SketchTerrain, w: Float, h: Float, month: Int, rnd: Random) {
    val crops = when (month) {
        4, 5 -> listOf(Crayon.MEADOW, Crayon.YELLOW, Crayon.MEADOW, Crayon.EARTH)
        6, 7, 8 -> listOf(Crayon.WHEAT, Crayon.MEADOW, Crayon.WHEAT, Crayon.EARTH)
        9, 10 -> listOf(Crayon.EARTH, Crayon.MEADOW, Crayon.WHEAT, Crayon.EARTH)
        else -> listOf(Crayon.EARTH, Crayon.MEADOW, Crayon.EARTH, Crayon.MEADOW)
    }.shuffled(rnd)
    val top = h * 0.943f
    val bottom = h * 0.99f
    var x = 0.31f
    var k = 0
    while (x < 0.80f) {
        val width = 0.07f + rnd.nextFloat() * 0.06f
        val x1 = minOf(x + width, 0.80f)
        if (!t.isWater(x) && !t.isWater(x1)) {
            // Les limites des parcelles penchent vers le centre, comme vues un peu de haut.
            val lean = (0.55f - x) * 0.04f
            val lean1 = (0.55f - x1) * 0.04f
            val parcel = polygon(
                listOf(Offset(w * (x + lean), top), Offset(w * (x1 + lean1), top), Offset(w * (x1 - lean1), bottom), Offset(w * (x - lean), bottom)),
            )
            val ink = pencil.ink(crops[k % crops.size])
            within(parcel) {
                var y = top + 1.5f * density
                while (y < bottom) {
                    ink.line(Offset(w * (x - 0.03f), y), Offset(w * (x1 + 0.03f), y + 0.6f * density), width = 0.9f, passes = 1, wobble = 0.3f)
                    y += (2.4f + (y - top) / (bottom - top) * 1.6f) * density
                }
            }
            // Une haie entre deux parcelles.
            if (k % 2 == 1) {
                pencil.ink(Crayon.FOREST).stroke(
                    (0..4).map { i -> Offset(w * (x + lean) - i * 0.4f * density, top + (bottom - top) * i / 4 + if (i % 2 == 0) -1f * density else 0f) },
                    width = 1.3f,
                    passes = 1,
                )
            }
        }
        x = x1
        k++
    }
}

/** Dessine [block] limité à [shape]. */
private inline fun DrawScope.within(shape: Path, crossinline block: DrawScope.() -> Unit) = clipPath(shape) { block() }

/** La rivière : un ruban qui serpente au pied du relief, plus large au milieu, ses rives et ses rides. */
private fun DrawScope.river(pencil: Pencil, w: Float, h: Float, rnd: Random) {
    val u = density
    val x0 = 0.28f
    val x1 = 0.77f
    val phase = rnd.nextFloat() * 6f
    val xs = (0..48).map { x0 + (x1 - x0) * it / 48 }
    fun center(x: Float) = h * RIVER + (sin(x * 24.0 + phase) * 2.4 * u).toFloat()
    fun half(x: Float) = sqrt(sin(PI * (x - x0) / (x1 - x0)).toFloat().coerceAtLeast(0f)) * 3.4f * u
    val upper = xs.map { Offset(w * it, center(it) - half(it)) }
    val lower = xs.map { Offset(w * it, center(it) + half(it)) }
    pencil.erase(polygon(upper + lower.reversed()))
    pencil.stroke(upper, width = 0.9f, passes = 1)
    pencil.stroke(lower, width = 0.9f, passes = 1)
    val ink = pencil.ink(Crayon.WATER)
    var x = x0 + 0.03f
    while (x < x1 - 0.04f) {
        val len = 0.012f + rnd.nextFloat() * 0.02f
        ink.line(Offset(w * x, center(x)), Offset(w * (x + len), center(x + len)), width = 1f, passes = 1, wobble = 0.2f)
        x += len + 0.01f + rnd.nextFloat() * 0.015f
    }
}

/** Un pont de pierre en arc, par-dessus la rivière. */
private fun DrawScope.bridge(pencil: Pencil, at: Offset, half: Float) {
    val u = density
    val deck = at.y - 4.5f * u
    val shape = Path().apply {
        moveTo(at.x - half, deck)
        lineTo(at.x + half, deck)
        lineTo(at.x + half, at.y + 3.5f * u)
        lineTo(at.x + half * 0.7f, at.y + 3.5f * u)
        quadraticTo(at.x, at.y - 6f * u, at.x - half * 0.7f, at.y + 3.5f * u)
        lineTo(at.x - half, at.y + 3.5f * u)
        close()
    }
    pencil.erase(shape)
    pencil.outline(shape, width = 0.9f)
    pencil.ink(Crayon.ROCK).hatch(shape, shape.getBounds(), spacing = 1.8f, width = 0.6f)
    pencil.line(Offset(at.x - half - 1f * u, deck - 2f * u), Offset(at.x + half + 1f * u, deck - 2f * u), width = 0.7f, passes = 1)
}

/**
 * Une forêt tout près : des arbres serrés sur la pente, les plus petits derrière. Des sapins en montagne, des feuillus
 * en plaine, un peu des deux en basse montagne.
 */
private fun DrawScope.forest(pencil: Pencil, scene: SketchScene, w: Float, h: Float, month: Int, rnd: Random, free: (Float) -> Boolean) {
    val t = scene.terrain
    val u = density
    val width = 0.17f
    val start = (0..30).map { 0.36f + it * 0.01f }.filter { it + width < 0.76f }.maxByOrNull { x0 ->
        (0..10).count { free(x0 + width * it / 10) } * 10 - abs(x0 - t.placeX)
    } ?: return
    val trees = (0..13).mapNotNull { k ->
        val x = start + width * k / 13 + (rnd.nextFloat() - 0.5f) * 0.01f
        val lift = t.heightAt(x)
        if (!free(x) || lift > minOf(t.treeline, t.snowline) - 0.005f) return@mapNotNull null
        // Deux rangs : l'un un peu plus haut sur la pente (et plus petit), l'autre devant.
        val back = k % 2 == 0
        Triple(x, h * (FOOT - lift) + (if (back) -1f else 3f) * u, (if (back) 11f else 15f) + rnd.nextFloat() * 5f)
    }.sortedBy { it.second }
    trees.forEach { (x, base, size) ->
        val conifer = when (t.relief) {
            Relief.PLAIN -> rnd.nextFloat() < 0.15f
            Relief.LOW -> rnd.nextFloat() < 0.4f
            else -> rnd.nextFloat() < 0.9f
        }
        if (conifer) fir(pencil, Offset(w * x, base), size * u, scene.lean) else tree(pencil, Offset(w * x, base), size * u, month, scene.lean)
    }
}

/** Ferme : un long logis sous un grand toit, la grange au bois sombre et sa porte, une botte de foin l'été. */
private fun DrawScope.farm(pencil: Pencil, ground: Offset, width: Float, month: Int): Offset {
    val u = density
    val x = ground.x
    val g = ground.y
    val wall = width * 0.42f
    val home = rect(x - width * 0.55f, g - wall, x + width * 0.05f, g)
    val barn = rect(x + width * 0.05f, g - wall * 1.1f, x + width * 0.6f, g)
    val roof = polygon(
        listOf(Offset(x - width * 0.62f, g - wall), Offset(x - width * 0.25f, g - wall - width * 0.3f), Offset(x + width * 0.3f, g - wall * 1.1f - width * 0.32f), Offset(x + width * 0.67f, g - wall * 1.1f)),
    )
    val stack = rect(x - width * 0.42f, g - wall - width * 0.34f, x - width * 0.33f, g - wall - width * 0.1f)
    pencil.erase(Path().apply { addPath(home) }.union(barn).union(roof).union(stack))
    pencil.outline(home, width = 1f)
    pencil.outline(barn, width = 1f)
    pencil.ink(Crayon.WOOD).hatch(barn, barn.getBounds(), spacing = 2.2f, width = 0.6f)
    pencil.outline(stack, width = 0.9f, passes = 1)
    pencil.outline(roof, width = 1f)
    pencil.roofInk(Crayon.ROOF).hatch(roof, roof.getBounds(), spacing = 2f, width = 0.7f)
    // La porte de la grange, en arc, et une fenêtre au logis.
    pencil.stroke(listOf(Offset(x + width * 0.22f, g), Offset(x + width * 0.22f, g - wall * 0.6f), Offset(x + width * 0.33f, g - wall * 0.8f), Offset(x + width * 0.44f, g - wall * 0.6f), Offset(x + width * 0.44f, g)), width = 0.8f, passes = 1)
    pencil.outline(rect(x - width * 0.32f, g - wall * 0.7f, x - width * 0.2f, g - wall * 0.35f), width = 0.7f, passes = 1)
    if (month in 6..9) {
        val bale = oval(Offset(x + width * 0.85f, g - 3.5f * u), 4.5f * u, 3.5f * u)
        pencil.erase(bale)
        pencil.outline(bale, width = 0.9f)
        pencil.ink(Crayon.WHEAT).hatch(bale, bale.getBounds(), spacing = 1.6f, width = 0.6f)
    }
    return Offset(x - width * 0.375f, g - wall - width * 0.37f)
}

/** Refuge de montagne : murs de pierre, toit bas, une petite fenêtre et le drapeau rouge. */
private fun DrawScope.refuge(pencil: Pencil, ground: Offset, width: Float): Offset {
    val u = density
    val x = ground.x
    val g = ground.y
    val wall = width * 0.5f
    val walls = rect(x - width / 2, g - wall, x + width / 2, g)
    val roof = polygon(listOf(Offset(x - width / 2 - 2f * u, g - wall), Offset(x - width * 0.1f, g - wall - width * 0.32f), Offset(x + width / 2 + 2f * u, g - wall - width * 0.12f)))
    pencil.erase(Path().apply { addPath(walls) }.union(roof))
    pencil.outline(walls, width = 1f)
    pencil.ink(Crayon.ROCK).hatch(walls, walls.getBounds(), spacing = 1.8f, width = 0.6f)
    pencil.outline(roof, width = 1f)
    pencil.hatch(roof, roof.getBounds(), spacing = 2.2f, width = 0.6f)
    pencil.erase(rect(x - 1.8f * u, g - wall * 0.75f, x + 1.8f * u, g - wall * 0.35f))
    pencil.outline(rect(x - 1.8f * u, g - wall * 0.75f, x + 1.8f * u, g - wall * 0.35f), width = 0.7f, passes = 1)
    val pole = Offset(x + width / 2 + 4f * u, g)
    pencil.line(pole, pole + Offset(0f, -width * 1.3f), width = 0.8f, passes = 1)
    val flag = polygon(listOf(pole + Offset(0f, -width * 1.3f), pole + Offset(6f * u, -width * 1.3f + 2f * u), pole + Offset(0f, -width * 1.3f + 4f * u)))
    pencil.ink(Crayon.RED).outline(flag, width = 0.8f, passes = 1)
    pencil.ink(Crayon.RED).hatch(flag, flag.getBounds(), spacing = 1.2f, width = 0.6f)
    return Offset(x - width * 0.3f, g - wall - width * 0.25f)
}

/** Église à flèche d'ardoise, la plus courante en plaine. [scale] l'agrandit pour une petite ville. */
private fun DrawScope.spireChurch(pencil: Pencil, ground: Offset, scale: Float) {
    val u = density * scale
    val x = ground.x
    val g = ground.y
    val t = g - 20f * u
    val tower = rect(x - 4f * u, t, x + 4f * u, g)
    val spire = polygon(listOf(Offset(x - 5f * u, t), Offset(x, t - 17f * u), Offset(x + 5f * u, t)))
    val nave = polygon(
        listOf(Offset(x + 4f * u, g), Offset(x + 22f * u, g), Offset(x + 22f * u, g - 10f * u), Offset(x + 13f * u, g - 17f * u), Offset(x + 4f * u, g - 10f * u)),
    )
    val roof = polygon(listOf(Offset(x + 22f * u, g - 10f * u), Offset(x + 13f * u, g - 17f * u), Offset(x + 4f * u, g - 10f * u)))
    pencil.erase(Path().apply { addPath(tower) }.union(spire).union(nave))
    pencil.outline(nave, width = 1f)
    pencil.roofInk(Crayon.ROOF).hatch(roof, roof.getBounds(), spacing = 2.2f, width = 0.7f)
    pencil.outline(tower, width = 1.1f)
    pencil.outline(spire, width = 1f)
    pencil.roofInk(Crayon.FAR).hatch(spire, spire.getBounds(), spacing = 1.8f, width = 0.7f)
    pencil.line(Offset(x, t - 17f * u), Offset(x, t - 22f * u), width = 0.9f)
    pencil.line(Offset(x - 2f * u, t - 20f * u), Offset(x + 2f * u, t - 20f * u), width = 0.8f, passes = 1)
    pencil.outline(oval(Offset(x, t + 5f * u), 2f * u, 2f * u), width = 0.7f, passes = 1)
    // Le vitrail de la nef.
    pencil.stroke(listOf(Offset(x + 11.5f * u, g - 3f * u), Offset(x + 11.5f * u, g - 7f * u), Offset(x + 13f * u, g - 9f * u), Offset(x + 14.5f * u, g - 7f * u), Offset(x + 14.5f * u, g - 3f * u)), width = 0.7f, passes = 1)
}

/** Une maison de ville à étages, toit de tuiles, fenêtres en rangs (allumées la nuit). */
private fun DrawScope.townHouse(pencil: Pencil, ground: Offset, width: Float, floors: Int, lit: Boolean, rnd: Random): Offset {
    val u = density
    val x = ground.x
    val g = ground.y
    val wall = floors * 6.5f * u + 2f * u
    val walls = rect(x - width / 2, g - wall, x + width / 2, g)
    val roof = polygon(listOf(Offset(x - width / 2 - 1.5f * u, g - wall), Offset(x, g - wall - width * 0.42f), Offset(x + width / 2 + 1.5f * u, g - wall)))
    pencil.erase(Path().apply { addPath(walls) }.union(roof))
    pencil.outline(walls, width = 1f)
    pencil.outline(roof, width = 1f)
    pencil.roofInk(if (rnd.nextFloat() < 0.75f) Crayon.ROOF else Crayon.FAR).hatch(roof, roof.getBounds(), spacing = 2f, width = 0.7f)
    windows(pencil, Rect(x - width / 2, g - wall, x + width / 2, g - 4f * u), floors, if (width > 12f * u) 3 else 2, lit, rnd)
    return Offset(x + width * 0.25f, g - wall - width * 0.3f)
}

/** Des rangées de fenêtres dans [facade] : petits carrés au crayon le jour, points jaunes la nuit (pas toutes). */
private fun DrawScope.windows(pencil: Pencil, facade: Rect, rows: Int, columns: Int, lit: Boolean, rnd: Random) {
    val u = density
    val yellow = pencil.ink(Crayon.YELLOW)
    for (r in 0 until rows) for (c in 0 until columns) {
        val cx = facade.left + facade.width * (c + 0.5f) / columns
        val cy = facade.top + facade.height * (r + 0.5f) / rows
        if (lit) {
            if (rnd.nextFloat() < 0.6f) drawRect(yellow.color.copy(alpha = (yellow.color.alpha * 1.4f).coerceAtMost(1f)), Offset(cx - 1f * u, cy - 1.2f * u), Size(2f * u, 2.4f * u))
        } else {
            pencil.outline(rect(cx - 0.9f * u, cy - 1.2f * u, cx + 0.9f * u, cy + 1.2f * u), width = 0.6f, passes = 1)
        }
    }
}

/** Petite ville : l'église et son clocher au milieu, des maisons à étages autour, plus basses vers les bords. */
private fun DrawScope.town(pencil: Pencil, at: (Float) -> Offset, x: Float, half: Float, rnd: Random, lit: Boolean): Offset? {
    val u = density
    var chimney: Offset? = null
    // Du bord vers le milieu : les maisons du milieu, plus hautes, passent devant celles des bords.
    val slots = listOf(-0.95f to 1, 0.95f to 1, -0.65f to 2, 0.7f to 2, -0.35f to 3, 0.4f to 3)
    slots.forEach { (k, floors) ->
        val p = townHouse(pencil, at(x + half * k), (11f + rnd.nextFloat() * 4f) * u, floors, lit, rnd)
        if (k > 0 && floors == 2) chimney = p
    }
    spireChurch(pencil, at(x - 0.03f), 1.1f)
    return chimney
}

/**
 * Grande ville : des immeubles serrés, plus hauts vers le centre, toits plats et rangées de fenêtres, une tour, et
 * parfois une grue ou une cathédrale.
 */
private fun DrawScope.city(
    pencil: Pencil,
    at: (Float) -> Offset,
    x: Float,
    half: Float,
    w: Float,
    rnd: Random,
    lit: Boolean,
    landmark: Landmark? = null,
): Offset? {
    val u = density
    val n = (2 * half / 0.0325f).toInt().coerceAtLeast(4)
    val slot = 2 * half / n
    val crane = landmark == null && rnd.nextFloat() < 0.4f
    // Le monument prend la place du milieu ; il n'y a alors ni cathédrale ni grande tour.
    val middle = n / 2
    val cathedral = if (landmark != null) -1 else rnd.nextInt(n)
    val tallest = if (landmark != null) -1 else (2 until n - 1).random(rnd)
    // Les monuments sur leur colline, ou plus hauts que tout, se dessinent derrière les immeubles.
    val monumentAt = at(x - half + (middle + 0.5f) * slot)
    when (landmark) {
        Landmark.EIFFEL_TOWER -> eiffelTower(pencil, monumentAt, 62f * u)
        Landmark.FOURVIERE -> fourviere(pencil, monumentAt + Offset(4f * u, 0f))
        Landmark.BONNE_MERE -> bonneMere(pencil, monumentAt)
        Landmark.JET_D_EAU -> jetDeau(pencil, monumentAt + Offset(6f * u, 0f), 58f * u)
        else -> Unit
    }
    // Les plus hauts derrière : on dessine d'abord les tours du centre, puis les plus basses devant.
    val buildings = (0 until n).map { i ->
        val edge = 1 - abs(i - (n - 1) / 2f) / (n / 2f)
        val height = if (i == tallest) 44f else 12f + 22f * edge + rnd.nextFloat() * 8f
        Triple(i, height, (slot * w * (0.8f + rnd.nextFloat() * 0.25f)))
    }.sortedByDescending { it.second }
    buildings.forEach { (i, height, width) ->
        val base = at(x - half + (i + 0.5f) * slot)
        // Autour du monument, une place dégagée (le Champ-de-Mars, la colline, la rade) pour bien le voir.
        if (landmark != null && (i == middle || landmark != Landmark.STRASBOURG_CATHEDRAL && abs(i - middle) == 1)) {
            if (landmark == Landmark.STRASBOURG_CATHEDRAL) strasbourgCathedral(pencil, base)
            return@forEach
        }
        if (i == cathedral && i != tallest) {
            spireChurch(pencil, base + Offset(-6f * u, 0f), 1.2f)
            return@forEach
        }
        val top = base.y - height * u
        val shape = rect(base.x - width / 2, top, base.x + width / 2, base.y)
        pencil.erase(shape)
        pencil.outline(shape, width = 1f)
        // Le côté à l'ombre, et une couleur de façade.
        pencil.ink(listOf(Crayon.FAR, Crayon.ROCK, Crayon.EARTH)[i % 3]).hatch(shape, Rect(base.x + width * 0.15f, top, base.x + width / 2, base.y), spacing = 2.2f, width = 0.6f)
        windows(pencil, Rect(base.x - width / 2, top + 2f * u, base.x + width / 2, base.y - 3f * u), ((height - 5f) / 5.5f).toInt().coerceAtLeast(1), 2, lit, rnd)
        if (i == tallest) {
            // L'antenne de la tour, avec son feu rouge.
            pencil.line(Offset(base.x, top), Offset(base.x, top - 9f * u), width = 0.8f, passes = 1)
            pencil.ink(Crayon.RED).dot(Offset(base.x, top - 9.5f * u), 1.1f)
        }
    }
    if (crane) {
        val foot = at(x + half * 0.55f)
        val top = foot + Offset(0f, -50f * u)
        val yellow = pencil.ink(Crayon.YELLOW)
        yellow.line(foot, top, width = 1.4f)
        yellow.line(top + Offset(-8f * u, 2f * u), top + Offset(22f * u, 2f * u), width = 1.2f)
        yellow.line(top, top + Offset(14f * u, 2f * u), width = 0.7f)
        pencil.line(top + Offset(16f * u, 2f * u), top + Offset(16f * u, 14f * u), width = 0.5f, passes = 1)
        pencil.outline(rect(top.x - 8f * u, top.y + 2f * u, top.x - 5f * u, top.y + 5f * u), width = 0.7f, passes = 1)
    }
    return null
}

/** Phare : une tour blanche à bandes rouges, sa lanterne, et ses faisceaux la nuit. */
private fun DrawScope.lighthouse(pencil: Pencil, ground: Offset, height: Float, beam: Boolean) {
    val u = density
    val x = ground.x
    val g = ground.y
    val top = g - height
    val tower = polygon(listOf(Offset(x - 4.5f * u, g), Offset(x - 2.8f * u, top), Offset(x + 2.8f * u, top), Offset(x + 4.5f * u, g)))
    val lantern = rect(x - 3.2f * u, top - 5f * u, x + 3.2f * u, top)
    val cap = polygon(listOf(Offset(x - 3.8f * u, top - 5f * u), Offset(x, top - 9f * u), Offset(x + 3.8f * u, top - 5f * u)))
    pencil.erase(Path().apply { addPath(tower) }.union(lantern).union(cap))
    pencil.outline(tower, width = 1f)
    val red = pencil.ink(Crayon.RED)
    listOf(0.15f, 0.55f).forEach { k ->
        val band = rect(x - 5f * u, top + height * k, x + 5f * u, top + height * (k + 0.2f))
        band.op(band, tower, PathOperation.Intersect)
        red.hatch(band, band.getBounds(), spacing = 1.4f, width = 0.7f)
    }
    pencil.outline(lantern, width = 0.9f, passes = 1)
    pencil.outline(cap, width = 0.9f)
    red.hatch(cap, cap.getBounds(), spacing = 1.4f, width = 0.6f)
    val yellow = pencil.ink(Crayon.YELLOW)
    yellow.dot(Offset(x, top - 2.5f * u), 1.6f)
    if (beam) listOf(-1f, 1f).forEach { side ->
        yellow.line(Offset(x + side * 4f * u, top - 3.5f * u), Offset(x + side * 30f * u, top - 7f * u), width = 0.8f, passes = 1)
        yellow.line(Offset(x + side * 4f * u, top - 1.5f * u), Offset(x + side * 30f * u, top + 1f * u), width = 0.8f, passes = 1)
    }
}

/** Sapin : trois étages en dents de scie et un bout de tronc ; vert, ombré à droite ; penché par le vent ([lean]). */
private fun DrawScope.fir(pencil: Pencil, base: Offset, height: Float, lean: Float = 0f) {
    val x = base.x
    val top = base.y - height
    // Le vent plie la cime plus que le pied.
    fun bent(p: Offset) = Offset(p.x + lean * 0.22f * (base.y - p.y) * (base.y - p.y) / height, p.y)
    val half = listOf(0.35f to 0.20f, 0.33f to 0.10f, 0.66f to 0.30f, 0.64f to 0.14f, 0.90f to 0.40f)
    val right = half.map { (dy, dx) -> Offset(x + dx * height, top + dy * height) }
    val left = half.reversed().map { (dy, dx) -> Offset(x - dx * height, top + dy * height) }
    val shape = polygon((listOf(Offset(x, top)) + right + left).map(::bent))
    pencil.erase(shape)
    pencil.outline(shape, width = 1f)
    pencil.ink(Crayon.FOREST).hatch(shape, shape.getBounds(), spacing = 2.4f, width = 0.7f)
    pencil.ink(Crayon.WOOD).line(bent(Offset(x, top + height * 0.9f)), base, width = 1.2f)
}

/** Arbre à feuilles : vert, orangé à l'automne, nu en hiver ; sa couronne part avec le vent ([lean]). */
private fun DrawScope.tree(pencil: Pencil, base: Offset, height: Float, month: Int, lean: Float = 0f) {
    val wood = pencil.ink(Crayon.WOOD)
    val crownC = base + Offset(lean * height * 0.22f, -height * 0.68f)
    if (month == 12 || month in 1..2) {
        wood.line(base, crownC + Offset(lean * height * 0.06f, -height * 0.25f), width = 1.3f)
        listOf(-1f, 1f).forEach { side ->
            val from = base + Offset(lean * height * 0.1f, -height * 0.45f)
            wood.line(from, from + Offset(side * height * 0.32f + lean * height * 0.15f, -height * 0.38f), width = 0.9f, passes = 1)
            wood.line(from + Offset(0f, -height * 0.2f), from + Offset(side * height * 0.2f + lean * height * 0.15f, -height * 0.52f), width = 0.8f, passes = 1)
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
    pencil.roofInk(Crayon.ROOF).hatch(roof, roof.getBounds(), spacing = 2.2f, width = 0.7f)
    pencil.outline(tower, width = 1.1f)
    pencil.outline(dome, width = 1f)
    pencil.roofInk(Crayon.DOME).hatch(dome, Rect(x - 6f * u, t - 14f * u, x + 6f * u, t), spacing = 1.8f, width = 0.7f)
    pencil.line(Offset(x, t - 14f * u), Offset(x, t - 20f * u), width = 0.9f)
    pencil.line(Offset(x - 2f * u, t - 18f * u), Offset(x + 2f * u, t - 18f * u), width = 0.8f, passes = 1)
    // L'horloge et l'abat-son du clocher, la porte de l'église.
    pencil.outline(oval(Offset(x, t + 5f * u), 2f * u, 2f * u), width = 0.7f, passes = 1)
    pencil.line(Offset(x - 1.6f * u, t + 10f * u), Offset(x + 1.6f * u, t + 10f * u), width = 0.7f, passes = 1)
    pencil.line(Offset(x - 1.6f * u, t + 12f * u), Offset(x + 1.6f * u, t + 12f * u), width = 0.7f, passes = 1)
    pencil.stroke(listOf(Offset(x - 15f * u, g), Offset(x - 15f * u, g - 5f * u), Offset(x - 13.2f * u, g - 7f * u), Offset(x - 11.4f * u, g - 5f * u), Offset(x - 11.4f * u, g)), width = 0.8f, passes = 1)
}

/** Maison : murs, toit de tuiles, une fenêtre (allumée la nuit) ; renvoie le haut de la cheminée s'il y en a une. */
private fun DrawScope.house(pencil: Pencil, ground: Offset, width: Float, chimney: Boolean, lit: Boolean = false): Offset? {
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
    pencil.roofInk(Crayon.ROOF).hatch(roof, roof.getBounds(), spacing = 2f, width = 0.7f)
    val window = Path().apply { addRect(Rect(x - 1.5f * u, g - wall * 0.7f, x + 1.5f * u, g - wall * 0.7f + 3f * u)) }
    pencil.outline(window, width = 0.7f, passes = 1)
    if (lit) pencil.ink(Crayon.YELLOW).hatch(window, window.getBounds(), spacing = 0.8f, width = 0.8f)
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
    pencil.roofInk(Crayon.ROCK).hatch(roof, roof.getBounds(), spacing = 2f, width = 0.6f)
    // Le balcon.
    pencil.line(Offset(x - width / 2 - 1f * u, g - wall * 0.45f), Offset(x + width / 2 + 1f * u, g - wall * 0.45f), width = 0.9f, passes = 1)
    return Offset(x + width * 0.26f, g - wall - width * 0.38f)
}

/** La fumée de la cheminée : elle monte en volutes, ou part à l'horizontale quand le vent souffle ([lean]). */
private fun DrawScope.smoke(pencil: Pencil, from: Offset, lean: Float = 0f) {
    val u = density
    val side = if (lean < 0) -1f else 1f
    val wind = kotlin.math.abs(lean)
    val points = (0..18).map { i ->
        Offset(from.x + side * i * (0.9f + 2.2f * wind) * u + (sin(i * 0.8) * 2.6 * (1 - wind) * u).toFloat(), from.y - i * (1.5f - 1.1f * wind) * u)
    }
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

/** Les surprises : dans le ciel, placées par rapport au texte ([sky], la hauteur du cadre du ciel) ; au sol, en bas. */
internal fun DrawScope.surprises(pencil: Pencil, scene: SketchScene, w: Float, h: Float, sky: Float = h) {
    // Au bord de la mer, les objets du premier plan sont sur le rivage.
    val ground = Offset(w * layoutOf(scene).foreground, h * if (scene.terrain.sea) SEA else GROUND)
    for (surprise in scene.surprises) when (surprise) {
        Surprise.BIRDS -> birds(pencil, w, sky)
        Surprise.PLANE -> plane(pencil, Offset(w * 0.87f, sky * 0.42f), trail = w * 0.14f)
        Surprise.PARAGLIDER -> paraglider(pencil, Offset(w * 0.81f, sky * 0.43f))
        Surprise.BALLOON -> balloon(pencil, Offset(w * 0.43f, sky * 0.48f))
        Surprise.SHOOTING_STAR -> shootingStar(pencil, Offset(w * 0.75f, sky * 0.53f), Offset(w * 0.88f, sky * 0.41f))
        Surprise.UFO -> ufo(pencil, Offset(w * 0.81f, sky * 0.46f), 24f * density, beamTo = null)
        Surprise.ABDUCTION -> {
            ufo(pencil, Offset(w * 0.81f, sky * 0.42f), 30f * density, beamTo = h * 0.95f)
            sheep(pencil, Offset(w * 0.81f, sky * 0.51f))
        }
        Surprise.SLEIGH -> sleigh(pencil, Offset(w * 0.80f, sky * 0.46f))
        Surprise.FIREWORKS -> fireworks(pencil, w, sky)
        Surprise.BATS -> listOf(0.78f to 0.43f, 0.44f to 0.48f, 0.87f to 0.50f).forEach { (x, y) -> bat(pencil, Offset(w * x, sky * y)) }
        Surprise.HEART -> heart(pencil, Offset(w * 0.79f, sky * 0.48f), 12f * density)
        Surprise.FISH -> fish(pencil, Offset(w * 0.80f, sky * 0.46f))
        Surprise.NOTES -> notes(pencil, w, sky)
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

// --- Bêtes, vent, ski et monuments ---

/** Une vache à l'alpage : blanche tachée de brun, cornes et sonnaille ; tournée à gauche ou à droite. */
private fun DrawScope.cow(pencil: Pencil, ground: Offset, facingRight: Boolean) {
    val u = density
    val s = if (facingRight) 1f else -1f
    val body = Path().apply { addRoundRect(RoundRect(ground.x - 5.5f * u, ground.y - 8f * u, ground.x + 5.5f * u, ground.y - 3.5f * u, 2f * u, 2f * u)) }
    val head = oval(ground + Offset(s * 7.2f * u, -7.3f * u), 2f * u, 1.7f * u)
    listOf(-4f, -2.5f, 2.5f, 4f).forEach { dx -> pencil.line(ground + Offset(dx * u, -3.8f * u), ground + Offset(dx * u, 0f), width = 0.8f, passes = 1) }
    pencil.erase(Path().apply { addPath(body) }.union(head))
    pencil.outline(body, width = 0.9f)
    pencil.outline(head, width = 0.8f, passes = 1)
    val brown = pencil.ink(Crayon.WOOD)
    listOf(-2.5f to -6.5f, 2f to -5f).forEach { (dx, dy) ->
        val patch = oval(ground + Offset(dx * u, dy * u), 1.8f * u, 1.3f * u)
        brown.hatch(patch, patch.getBounds(), spacing = 0.8f, width = 0.7f)
    }
    pencil.line(ground + Offset(s * 6.6f * u, -8.6f * u), ground + Offset(s * 6f * u, -10.2f * u), width = 0.6f, passes = 1)
    pencil.line(ground + Offset(s * 7.8f * u, -8.6f * u), ground + Offset(s * 8.4f * u, -10.2f * u), width = 0.6f, passes = 1)
    pencil.line(ground + Offset(-s * 5.5f * u, -7.5f * u), ground + Offset(-s * 6.5f * u, -4.5f * u), width = 0.6f, passes = 1)
    pencil.ink(Crayon.YELLOW).dot(ground + Offset(s * 6.4f * u, -5.4f * u), 0.8f)
}

/** Un chamois dans les rochers : brun, pattes fines, petites cornes en crochet. */
private fun DrawScope.chamois(pencil: Pencil, ground: Offset, facingRight: Boolean) {
    val u = density
    val s = if (facingRight) 1f else -1f
    val body = oval(ground + Offset(0f, -6f * u), 4.5f * u, 2.2f * u)
    val neck = ground + Offset(s * 3.5f * u, -7f * u)
    val head = ground + Offset(s * 5f * u, -10.5f * u)
    listOf(-3f, -1.8f, 2f, 3.2f).forEach { dx -> pencil.ink(Crayon.WOOD).line(ground + Offset(dx * u, -4.5f * u), ground + Offset(dx * u * 1.1f, 0f), width = 0.7f, passes = 1) }
    pencil.erase(body)
    pencil.ink(Crayon.WOOD).outline(body, width = 0.9f)
    pencil.ink(Crayon.WOOD).hatch(body, body.getBounds(), spacing = 1.1f, width = 0.6f)
    pencil.ink(Crayon.WOOD).line(neck, head, width = 1.4f, passes = 1)
    pencil.dot(head, 1.1f)
    pencil.stroke(listOf(head + Offset(0f, -1f * u), head + Offset(s * 0.3f * u, -3.2f * u), head + Offset(-s * 0.8f * u, -3.6f * u)), width = 0.6f, passes = 1)
}

/** Une mouette : de grandes ailes en M, un petit corps. */
private fun DrawScope.gull(pencil: Pencil, c: Offset, s: Float) {
    pencil.stroke(
        listOf(c + Offset(-1.6f * s, 0.2f * s), c + Offset(-0.9f * s, -0.55f * s), c + Offset(-0.25f * s, -0.2f * s), c, c + Offset(0.25f * s, -0.2f * s), c + Offset(0.9f * s, -0.55f * s), c + Offset(1.6f * s, 0.2f * s)),
        width = 1.1f,
    )
    pencil.dot(c + Offset(0f, 0.15f * s), 0.9f)
}

/** La manche à air : rouge et blanche, molle par petit vent, tendue à l'horizontale à 50 km/h ; elle part avec le vent. */
private fun DrawScope.windsock(pencil: Pencil, ground: Offset, windKmh: Double, lean: Float) {
    val u = density
    val top = ground + Offset(0f, -20f * u)
    pencil.line(ground, top, width = 1f)
    val side = if (lean < 0) -1f else 1f
    // De pendante (75° sous l'horizontale) à tendue.
    val droop = (1 - ((windKmh - 10) / 40).coerceIn(0.0, 1.0)) * 75.0
    val a = Math.toRadians(droop)
    val dir = Offset((side * cos(a)).toFloat(), sin(a).toFloat())
    val normal = Offset(-dir.y, dir.x)
    val length = 13f * u
    val red = pencil.ink(Crayon.RED)
    for (k in 0 until 4) {
        val t0 = k / 4f
        val t1 = (k + 1) / 4f
        val r0 = (2.6f - 1.4f * t0) * u
        val r1 = (2.6f - 1.4f * t1) * u
        val ring = polygon(listOf(top + dir * (length * t0) + normal * r0, top + dir * (length * t1) + normal * r1, top + dir * (length * t1) - normal * r1, top + dir * (length * t0) - normal * r0))
        pencil.erase(ring)
        pencil.outline(ring, width = 0.7f, passes = 1)
        if (k % 2 == 0) red.hatch(ring, ring.getBounds(), spacing = 1f, width = 0.7f)
    }
}

/**
 * La station de ski : un télésiège du fond de la vallée vers le haut de la pente, ses pylônes et ses sièges ; l'hiver,
 * deux pistes qui serpentent dans la neige, et quelques skieurs.
 */
private fun DrawScope.skiArea(pencil: Pencil, t: SketchTerrain, w: Float, h: Float, lean: Float) {
    val u = density
    val x1 = (0..24).map { 0.48f + it * 0.01f }.filter { it < t.farX }.maxByOrNull { t.heightAt(it) } ?: return
    val x0 = (t.placeX + 0.06f).coerceIn(0.40f, x1 - 0.08f)
    if (t.heightAt(x1) - t.heightAt(x0) < 0.05f) return
    fun ground(x: Float) = Offset(w * x, h * (FOOT - t.heightAt(x)))
    val a = ground(x0) + Offset(0f, -8f * u)
    val b = ground(x1 - 0.015f) + Offset(0f, -8f * u)
    // Les pistes, quand le haut est enneigé.
    if (t.snowline < t.heightAt(x1) - 0.02f) {
        listOf(0.012f, 0.05f).forEach { offset ->
            val points = (0..12).map { i ->
                val f = i / 12f
                val x = x1 - 0.01f - offset - f * 0.10f
                ground(x) + Offset((sin(f * 9.0 + offset * 40).toFloat()) * 5f * u, 2f * u)
            }
            // La piste damée : ses deux bords, et des skieurs.
            pencil.erase(polygon(points.map { it + Offset(-2.2f * u, 0f) } + points.reversed().map { it + Offset(2.2f * u, 0f) }))
            pencil.stroke(points.map { it + Offset(-2.2f * u, 0f) }, width = 0.6f, passes = 1)
            pencil.stroke(points.map { it + Offset(2.2f * u, 0f) }, width = 0.6f, passes = 1)
            listOf(3, 8).forEach { k -> pencil.ink(Crayon.RED).dot(points[k] + Offset(0f, -1.2f * u), 1.2f) }
        }
    }
    // Les pylônes, le câble et les sièges qui pendent (balancés par le vent).
    listOf(0f, 0.33f, 0.66f, 1f).forEach { f ->
        val x = x0 + (x1 - 0.015f - x0) * f
        val g = ground(x)
        val cable = a + (b - a) * f
        pencil.line(g, cable + Offset(0f, -1f * u), width = 0.9f, passes = 1)
        pencil.line(cable + Offset(-2.5f * u, -1f * u), cable + Offset(2.5f * u, -1f * u), width = 0.8f, passes = 1)
    }
    pencil.line(a + Offset(0f, -1f * u), b + Offset(0f, -1f * u), width = 0.6f, passes = 1, wobble = 0.1f)
    var f = 0.08f
    while (f < 0.95f) {
        val c = a + (b - a) * f + Offset(0f, -1f * u)
        val seat = c + Offset(lean * 1.5f * u, 4f * u)
        pencil.line(c, seat, width = 0.5f, passes = 1)
        pencil.line(seat + Offset(-1.6f * u, 0f), seat + Offset(1.6f * u, 0f), width = 0.8f, passes = 1)
        f += 0.14f
    }
}

/** La tour Eiffel : quatre pieds arqués, deux plateformes, le treillis, la pointe. */
private fun DrawScope.eiffelTower(pencil: Pencil, ground: Offset, height: Float) {
    val x = ground.x
    val g = ground.y
    val w = height * 0.42f
    fun at(f: Float, side: Float) = Offset(x + side * w / 2 * (1 - f) * (1 - f) * (1 + 0.6f * f), g - height * f)
    val legs = listOf(-1f, 1f).map { side -> (0..12).map { at(it / 12f * 0.92f, side) } }
    val outline = polygon(legs[0] + legs[1].reversed())
    pencil.erase(outline)
    legs.forEach { pencil.ink(Crayon.EARTH).stroke(it, width = 1.1f) }
    // L'arche entre les pieds, les deux plateformes.
    pencil.stroke((0..8).map { i -> val a = PI + PI * i / 8; Offset(x + (w * 0.28f * cos(a)).toFloat(), g - height * 0.1f + (height * 0.08f * sin(a)).toFloat()) }, width = 0.8f, passes = 1)
    listOf(0.2f, 0.45f).forEach { f -> pencil.line(at(f, -1f) + Offset(-1.5f * density, 0f), at(f, 1f) + Offset(1.5f * density, 0f), width = 1f) }
    // Le treillis, en croisillons.
    var f = 0.04f
    while (f < 0.88f) {
        val f2 = f + 0.07f
        pencil.line(at(f, -1f), at(f2, 1f), width = 0.4f, passes = 1, wobble = 0.1f)
        pencil.line(at(f, 1f), at(f2, -1f), width = 0.4f, passes = 1, wobble = 0.1f)
        f = f2
    }
    pencil.line(at(0.92f, 0f), Offset(x, g - height * 1.02f), width = 0.9f)
}

/** Fourvière : la colline au-dessus de la ville, la basilique et ses quatre tours, la tour métallique à côté. */
private fun DrawScope.fourviere(pencil: Pencil, ground: Offset) {
    val u = density
    val top = ground + Offset(0f, -36f * u)
    val hill = Path().apply {
        moveTo(ground.x - 34f * u, ground.y)
        quadraticTo(ground.x - 20f * u, top.y + 4f * u, ground.x, top.y + 3f * u)
        quadraticTo(ground.x + 22f * u, top.y + 4f * u, ground.x + 36f * u, ground.y)
        close()
    }
    pencil.erase(hill)
    pencil.stroke((0..16).map { i -> val f = i / 16f; Offset(ground.x - 34f * u + 70f * u * f, ground.y - (sin(PI * f) * 33.0 * u).toFloat()) }, width = 1f)
    pencil.ink(Crayon.FOREST).hatch(hill, hill.getBounds(), spacing = 3f, width = 0.6f)
    val base = top + Offset(0f, 3f * u)
    val nave = rect(base.x - 9f * u, base.y - 8f * u, base.x + 9f * u, base.y)
    pencil.erase(nave)
    pencil.outline(nave, width = 0.9f)
    listOf(-9f, -4f, 4f, 9f).forEach { dx ->
        val tower = rect(base.x + dx * u - 1.6f * u, base.y - 13f * u, base.x + dx * u + 1.6f * u, base.y - 6f * u)
        pencil.erase(tower)
        pencil.outline(tower, width = 0.8f, passes = 1)
    }
    pencil.line(base + Offset(14f * u, 0f), base + Offset(16f * u, -24f * u), width = 0.7f, passes = 1)
    pencil.line(base + Offset(18f * u, 0f), base + Offset(16f * u, -24f * u), width = 0.7f, passes = 1)
}

/** Notre-Dame de la Garde : sur son rocher, la basilique, son haut clocher et la Vierge dorée tout en haut. */
private fun DrawScope.bonneMere(pencil: Pencil, ground: Offset) {
    val u = density
    val rock = polygon(listOf(ground + Offset(-30f * u, 0f), ground + Offset(-12f * u, -22f * u), ground + Offset(10f * u, -24f * u), ground + Offset(30f * u, 0f)))
    pencil.erase(rock)
    pencil.outline(rock, width = 1f)
    pencil.ink(Crayon.ROCK).hatch(rock, rock.getBounds(), spacing = 2.6f, width = 0.6f)
    val base = ground + Offset(0f, -23f * u)
    val nave = rect(base.x - 9f * u, base.y - 7f * u, base.x + 6f * u, base.y)
    val tower = rect(base.x + 3f * u, base.y - 24f * u, base.x + 8f * u, base.y)
    pencil.erase(Path().apply { addPath(nave) }.union(tower))
    pencil.outline(nave, width = 0.9f)
    pencil.outline(tower, width = 0.9f)
    listOf(0f, 0.33f, 0.66f).forEach { k -> pencil.ink(Crayon.ROCK).hatch(tower, Rect(base.x + 3f * u, base.y - 24f * u + 24f * u * k, base.x + 8f * u, base.y - 20f * u + 24f * u * k), spacing = 1f, width = 0.6f) }
    val statue = base + Offset(5.5f * u, -24f * u)
    pencil.ink(Crayon.YELLOW).line(statue, statue + Offset(0f, -6f * u), width = 2f)
    pencil.ink(Crayon.YELLOW).dot(statue + Offset(0f, -6.8f * u), 1.3f)
}

/** La cathédrale de Strasbourg : la façade et sa flèche unique, à gauche. */
private fun DrawScope.strasbourgCathedral(pencil: Pencil, ground: Offset) {
    val u = density
    val front = rect(ground.x - 8f * u, ground.y - 26f * u, ground.x + 8f * u, ground.y)
    val tower = rect(ground.x - 8f * u, ground.y - 36f * u, ground.x - 1f * u, ground.y - 26f * u)
    val spire = polygon(listOf(Offset(ground.x - 8f * u, ground.y - 36f * u), Offset(ground.x - 4.5f * u, ground.y - 54f * u), Offset(ground.x - 1f * u, ground.y - 36f * u)))
    pencil.erase(Path().apply { addPath(front) }.union(tower).union(spire))
    pencil.outline(front, width = 1f)
    pencil.outline(tower, width = 0.9f)
    pencil.outline(spire, width = 0.9f)
    // Le grès rose des Vosges.
    pencil.ink(Crayon.PINK).hatch(front, front.getBounds(), spacing = 2f, width = 0.6f)
    pencil.ink(Crayon.PINK).hatch(spire, spire.getBounds(), spacing = 1.6f, width = 0.6f)
    pencil.outline(oval(ground + Offset(0f, -17f * u), 3.5f * u, 3.5f * u), width = 0.7f, passes = 1)
    pencil.line(Offset(ground.x - 4.5f * u, ground.y - 54f * u), Offset(ground.x - 4.5f * u, ground.y - 58f * u), width = 0.8f, passes = 1)
}

/** Le jet d'eau de Genève : une colonne blanche qui monte du lac et retombe en panache. */
private fun DrawScope.jetDeau(pencil: Pencil, ground: Offset, height: Float) {
    val u = density
    val blue = pencil.ink(Crayon.WATER)
    val top = ground + Offset(2f * u, -height)
    blue.line(ground, top, width = 1.6f, wobble = 0.3f)
    blue.line(ground + Offset(1f * u, 0f), top + Offset(1.5f * u, 2f * u), width = 0.8f, passes = 1)
    // Le panache, poussé par le vent vers la droite.
    for (k in 0..4) {
        val points = (0..8).map { i ->
            val f = i / 8f
            top + Offset((3f + k * 2.2f) * u * f * 2f, (f * f * (18f + k * 5f) - f * 3f) * u)
        }
        blue.stroke(points, width = 0.7f, passes = 1)
    }
    repeat(8) { k -> blue.dot(top + Offset((6f + k * 2.5f) * u, (10f + (k % 3) * 6f) * u), 0.6f) }
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
