package io.github.pbailly.meteo.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
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

/** Tout le ciel, d'un bord à l'autre du dessin, au-dessus de la crête et de la chaîne lointaine : pour le colorier. */
internal fun DrawScope.skyOver(t: SketchTerrain, w: Float, h: Float): Path {
    val line = ridgeLine(t)
    val sky = polygon(listOf(Offset(0f, 0f), Offset(0f, line.first().y)) + line + listOf(Offset(w, line.last().y), Offset(w, 0f)))
    if (t.backdrop.isEmpty()) return sky
    val far = listOf(Offset(0f, h * FOOT)) + t.backdrop.map { (x, lift) -> Offset(w * x, h * (FOOT - lift)) } + listOf(Offset(w, h * FOOT))
    return sky.apply { op(this, below(far), PathOperation.Difference) }
}

/**
 * La taille du lieu habité dans sa catégorie, de 0 (le plus petit) à 1 (le plus grand), d'après le nombre d'habitants
 * (en échelle logarithmique : un village de 150 à 2 000, une petite ville de 2 000 à 50 000, une grande ville de
 * 50 000 à 10 millions). Sans population connue, la taille moyenne.
 */
internal fun settlementSize(scene: SketchScene): Float {
    val people = scene.population?.takeIf { it > 0 } ?: return 0.5f
    val (from, to) = when {
        Decor.CITY in scene.decor -> 50_000.0 to 10_000_000.0
        Decor.TOWN in scene.decor -> 2_000.0 to 50_000.0
        else -> 150.0 to 2_000.0
    }
    return (ln(people / from) / ln(to / from)).coerceIn(0.0, 1.0).toFloat()
}

/** Demi-largeur du lieu habité dessiné, en fraction de la largeur : plus grande avec plus d'habitants. */
private fun settlementHalfWidth(scene: SketchScene): Float? {
    val size = settlementSize(scene)
    val decor = scene.decor
    return when {
        Decor.CITY in decor -> 0.11f + 0.06f * size
        Decor.TOWN in decor -> 0.075f + 0.05f * size
        Decor.VILLAGE in decor -> 0.06f + 0.04f * size
        Decor.HAMLET in decor -> 0.05f
        Decor.FARM in decor -> 0.04f
        Decor.CHALET in decor || Decor.REFUGE in decor -> 0.025f
        else -> null
    }
}

/**
 * Ce que le paysage fixe laisse à la couche animée, en pixels : le haut des cheminées qui fument, le moyeu et la
 * longueur des pales des éoliennes, la hauteur de la voie ferrée, le voilier, les bateaux du port. Rempli en dessinant
 * le paysage ([landscape]), lu à chaque image ([animated]).
 */
internal class SketchAnchors {
    val chimneys = mutableListOf<Offset>()
    val rotors = mutableListOf<Pair<Offset, Float>>()
    var track: Float? = null
    var sail: Offset? = null
    val moorings = mutableListOf<Offset>()

    fun clear() {
        chimneys.clear()
        rotors.clear()
        track = null
        sail = null
        moorings.clear()
    }
}

/**
 * Où poser les choses : le lieu habité près du lieu, l'objet du premier plan ailleurs, jamais sur l'eau ; [wet] quand il
 * n'y avait de place qu'en empiétant sur l'eau (elle reculera jusqu'au-delà du lieu habité).
 */
internal class Layout(val settlement: Float?, val half: Float, val foreground: Float, val wet: Boolean = false)

internal fun layoutOf(scene: SketchScene): Layout {
    scene.layout?.let { return it }
    val t = scene.terrain
    fun dry(x: Float) = !t.isWater(x, 0.02f)
    val full = settlementHalfWidth(scene) ?: 0f
    var half = full
    fun span(x: Float) = (0..8).map { x - half + 2 * half * it / 8 }
    fun bumpiness(x: Float) = span(x).map { t.heightAt(it) }.let { it.max() - it.min() }
    var settlement: Float? = null
    var wet = false
    if (Decor.REFUGE in scene.decor && t.relief == Relief.SUMMIT) {
        // Le refuge, juste sous le sommet.
        settlement = t.placeX + 0.06f
    } else if (full > 0f) {
        // Au fond de la vallée, juste à côté du lieu ; le refuge, lui, est sur la pente. Entre un lac et la montagne,
        // la ville se resserre.
        // Les immeubles d'une ville montent haut : ils restent à gauche du texte de droite (le ciel, les extrêmes).
        val right = if (Decor.CITY in scene.decor || Decor.TOWN in scene.decor) 0.62f else 0.74f
        fun spots() = (listOf(0.06f, 0.09f, 0.12f, 0.15f, 0.03f, 0f, 0.18f).map { t.placeX + it } + listOf(0.47f, 0.55f))
            .map { it.coerceIn(0.33f + half, maxOf(0.33f + half, right - half)) }
        for (shrink in listOf(1f, 0.75f, 0.55f)) {
            half = full * shrink
            val candidates = spots().filter { x -> span(x).all(::dry) }
            settlement = candidates.firstOrNull { x -> Decor.REFUGE in scene.decor || t.heightAt(x) < 0.09f && bumpiness(x) < 0.06f }
                ?: candidates.minByOrNull { t.heightAt(it) + bumpiness(it) }
            if (settlement != null) break
        }
        // Pas de place au sec dans les terres (la coupe met un lac, ou le fleuve rendu plat, contre le lieu) : le lieu
        // habité est là quand même, le moins possible sur l'eau, qui recule devant lui (SketchTerrain.dried).
        if (settlement == null && !t.sea && !t.island) {
            settlement = spots().minByOrNull { x -> span(x).count { !dry(it) } + abs(x - t.placeX) }
            wet = true
        }
    }
    val placed = settlement
    val foreground = listOf(0.57f, 0.50f, 0.63f, 0.44f, 0.68f)
        .firstOrNull { x -> dry(x) && (placed == null || abs(x - placed) > half + 0.04f) } ?: 0.57f
    return Layout(placed, half, foreground, wet)
}

/** Feuillus : verts l'été, roux à l'automne, bruns et nus l'hiver. */
private fun leaves(month: Int) = when (month) {
    10, 11 -> Crayon.AUTUMN
    12, 1, 2, 3 -> Crayon.WOOD
    else -> Crayon.GREEN
}

/** Les étages verts du relief, du bas vers le haut : prés (ou champs), feuillus ou sapins, alpages, roche. */
private fun greenLayers(relief: Relief, t: SketchTerrain, month: Int): List<Pair<Float, androidx.compose.ui.graphics.Color>> = buildList {
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
    pencil.culture = scene.culture
    pencil.local = scene.local
    val anchors = scene.anchors
    anchors.clear()
    // Étages en couleur, du bas vers le haut : prés (ou champs), forêt, alpages, roche, glaciers et neige, selon le
    // relief et l'altitude ; sur toute la largeur (le relief reste bas sous le texte des côtés), avec des bords
    // irréguliers comme un coloriage.
    val area = Rect(w * 0.012f, 0f, w * 0.988f, h)
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
        when {
            // Le désert : du sable, puis la roche nue des montagnes.
            scene.desert -> {
                add((if (relief == Relief.PLAIN) Float.MAX_VALUE else 0.03f) to Crayon.WHEAT)
                add(Float.MAX_VALUE to Crayon.ROCK)
            }
            else -> addAll(greenLayers(relief, t, month))
        }
    }
    clipPath(colored) {
        // Au loin, derrière le relief, l'autre chaîne : pâle et bleutée, enneigée en haut.
        if (t.backdrop.isNotEmpty()) backdrop(pencil, t, w, h, land, snowY)
        if (!allSnow) {
            var bottom = h
            for ((top, color) in layers) {
                val yTop = maxOf(y(top), snowY, 0f)
                if (yTop < bottom) {
                    val spacing = when (color) {
                        Crayon.MEADOW -> 5.5f
                        Crayon.ROCK -> if (relief == Relief.VERY_HIGH) 4.2f else 5f
                        else -> 4.6f
                    }
                    val band = Rect(area.left, yTop, minOf(area.right, farX), bottom)
                    pencil.ink(color).hatch(land, band, spacing = spacing)
                    // La deuxième couleur croisée par-dessus : l'étage chatoie.
                    shadeOf(color)?.let { pencil.ink(it).hatch(land, band, spacing = spacing * 1.8f, width = 0.7f, rising = false) }
                    bottom = yTop
                }
                if (bottom <= maxOf(snowY, 0f)) break
            }
            // Au loin, le relief bleuit (perspective aérienne).
            if (farX < area.right) {
                val far = Rect(farX, snowY.coerceAtLeast(0f), area.right, h)
                pencil.ink(Crayon.FAR).hatch(land, far, spacing = 4.6f)
                pencil.ink(Crayon.SKY).hatch(land, far, spacing = 8.5f, width = 0.6f, rising = false)
            }
        }
        val snowBottom = if (allSnow) h else snowY.coerceAtMost(h)
        if (snowBottom > 0f) pencil.ink(Crayon.SNOW).hatch(land, Rect(area.left, 0f, area.right, snowBottom), spacing = 5.5f, width = 0.7f)
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
        var x = 0f
        while (x < 1f) {
            if (t.heightAt(x) > 0f) { x += 0.005f; continue }
            val start = x
            while (x < 1f && t.heightAt(x) <= 0f) x += 0.005f
            water(pencil, w * start, w * x, h * FOOT, rnd)
        }
    }
    // Au bord de la mer : la mer au premier plan, devant le relief ; au large, parfois des îles.
    if (t.sea) water(pencil, 0f, w, h * SEA, rnd)
    if (t.sea && Decor.ISLANDS in scene.decor) islands(pencil, w, h)

    val layout = layoutOf(scene)
    val settled = layout.settlement?.let { (it - layout.half - 0.03f)..(it + layout.half + 0.03f) }
    // En plaine, le lointain est plat : les arbres peuvent y pousser aussi.
    fun free(x: Float) = !t.isWater(x, 0.02f) && (x < t.farX - 0.02f || relief == Relief.PLAIN) && (settled == null || x !in settled)
    val crops = scene.decor.any { it == Decor.VINES || it == Decor.LAVENDER || it == Decor.SUNFLOWERS }
    if ((Decor.FIELDS in scene.decor || crops) && !t.sea) fields(pencil, scene, w, h, month, rnd)
    if (t.river) river(pencil, w, h, rnd)
    // La voie ferrée au pied du relief (au-dessus de la rivière quand il y en a une) ; le train passe dans la couche animée.
    if (Decor.TRAIN in scene.decor) {
        val trackY = h * (if (t.river) FOOT - 0.004f else FOOT + 0.004f)
        railway(pencil, t, w, trackY)
        anchors.track = trackY
    }
    if (Decor.HARBOUR in scene.decor) harbour(pencil, scene, w, h)
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
        TREE_SPOTS.filter(::free).forEachIndexed { k, x ->
            val base = Offset(w * x, y(t.heightAt(x)) + 2f * u)
            val size = (14f + rnd.nextFloat() * 6f) * u
            // En Provence, des pins parasols et un cyprès.
            when {
                scene.local != LocalStyle.PROVENCE -> tree(pencil, base, size, month, lean)
                k == 1 -> cypress(pencil, base, size * 1.25f, lean)
                else -> umbrellaPine(pencil, base, size * 1.1f, lean)
            }
        }
    }
    // Sur les plateaux du Haut-Doubs et du Jura suisse, une grande ferme à tuyé ; en plaine comtoise, une ferme bressane.
    if ((scene.local == LocalStyle.COMTOIS || scene.local == LocalStyle.SWISS_JURA) && scene.decor.any { it == Decor.VILLAGE || it == Decor.TOWN || it == Decor.HAMLET }) {
        listOf(0.70f, 0.66f, 0.44f, 0.74f).firstOrNull { x -> free(x) && t.heightAt(x) < t.treeline && t.heightAt(x) < 0.12f }?.let { x ->
            val ground = Offset(w * x, y(t.heightAt(x)) + 1f * u)
            if (relief == Relief.PLAIN && scene.local == LocalStyle.COMTOIS) bresseFarm(pencil, ground, 22f * u) else jurassienFarm(pencil, ground, 22f * u)
        }
    }
    if (Decor.ORCHARD in scene.decor) orchard(pencil, t, w, h, month, rnd, ::free)
    // Le désert : des dunes au premier plan, des palmiers, des dromadaires.
    if (scene.desert && !t.sea) dunes(pencil, t, w, h)
    if (Decor.PALMS in scene.decor) {
        listOf(0.45f, 0.52f, 0.66f, 0.71f).filter(::free).take(3).forEach { x ->
            palm(pencil, Offset(w * x, y(t.heightAt(x)) + 2f * u), (17f + rnd.nextFloat() * 6f) * u, lean)
        }
    }
    if (Decor.CAMELS in scene.decor) {
        (0..12).map { 0.44f + it * 0.025f }.filter { x -> free(x) && t.heightAt(x) < 0.05f }.take(2).forEachIndexed { k, x ->
            camel(pencil, Offset(w * x, y(t.heightAt(x)) + 1f * u), k % 2 == 0)
        }
    }
    // Les éoliennes sur les hauteurs, le château ou les ruines sur une butte, l'abbaye au fond du vallon.
    val taken = mutableListOf<ClosedFloatingPointRange<Float>>()
    settled?.let { taken += it }
    if (Decor.TURBINES in scene.decor) turbines(pencil, t, w, h, anchors, taken)
    heritage(pencil, scene, w, h, taken)
    layout.settlement?.let { x ->
        val at = { p: Float -> Offset(w * p, y(t.heightAt(p)) + 1f * u) }
        val lit = scene.lit
        val half = layout.half
        // Le monument du lieu, à la place de l'église d'une petite ville ou d'un village (la grande ville le place
        // elle-même parmi ses immeubles).
        // En Europe, une ville moyenne (moins de 250 000 habitants) est une vieille ville aux toits de tuiles, pas des tours.
        val oldTown = Decor.CITY in scene.decor && scene.culture == Culture.EUROPE && (scene.population ?: 0L) < 250_000 &&
            scene.landmark?.look != Silhouette.OWN
        val monument = scene.landmark?.takeIf { it.look != Silhouette.OWN && (Decor.CITY !in scene.decor || oldTown) }
        // Les monuments sur leur colline se dessinent derrière les maisons, les autres devant.
        val behind = monument?.look in setOf(Silhouette.CITADEL, Silhouette.MOUNT, Silhouette.STATUE_ON_HILL)
        if (behind) silhouette(pencil, monument!!.look, at(x - 0.03f), lit)
        val chimney = when {
            oldTown || Decor.TOWN in scene.decor -> town(pencil, at, x, half, rnd, lit, if (oldTown) 1.4f else settlementSize(scene), church = monument == null || behind)
                .also { if (!behind) monument?.let { m -> silhouette(pencil, m.look, at(x - 0.03f), lit) } }
            Decor.CITY in scene.decor -> city(pencil, at, x, half, w, rnd, lit, scene.landmark, settlementSize(scene))
            Decor.HAMLET in scene.decor && monument != null -> {
                silhouette(pencil, monument.look, at(x), lit)
                null
            }
            Decor.VILLAGE in scene.decor -> {
                // Clocher comtois en montagne (et parfois ailleurs), sinon une flèche d'ardoise ; de deux à cinq
                // maisons autour, selon le nombre d'habitants.
                when {
                    monument != null -> silhouette(pencil, monument.look, at(x), lit)
                    else -> worship(pencil, at(x), 1f, comtois = relief.mountains || rnd.nextFloat() < 0.35f)
                }
                val houses = 2 + (3 * settlementSize(scene)).roundToInt()
                var smoking: Offset? = null
                for (k in 0 until houses) {
                    val side = if (k % 2 == 0) 1f else -1f
                    val p = at(x + side * (0.065f + 0.035f * (k / 2)) * (half / 0.08f).coerceAtMost(1.3f))
                    val chimney = house(pencil, p, (13f + (k % 3)) * u, chimney = k == 0, lit = rnd.nextFloat() < lit * 1.3f)
                    if (k == 0) smoking = chimney
                }
                smoking
            }
            Decor.HAMLET in scene.decor -> if (relief.mountains) {
                chalet(pencil, at(x - 0.025f), 15f * u)
                chalet(pencil, at(x + 0.03f), 17f * u)
            } else {
                house(pencil, at(x - 0.025f), 13f * u, chimney = false, lit = rnd.nextFloat() < lit * 1.3f)
                house(pencil, at(x + 0.025f), 14f * u, chimney = true, lit = rnd.nextFloat() < lit * 1.3f)
            }
            Decor.FARM in scene.decor -> when {
                // La ferme de chez nous : à tuyé sur les plateaux du Jura, bressane en plaine comtoise.
                (scene.local == LocalStyle.COMTOIS || scene.local == LocalStyle.SWISS_JURA) && relief.mountains -> jurassienFarm(pencil, at(x), 24f * u)
                scene.local == LocalStyle.COMTOIS && relief == Relief.PLAIN -> bresseFarm(pencil, at(x), 24f * u)
                else -> farm(pencil, at(x), 20f * u, month)
            }
            Decor.CHALET in scene.decor -> chalet(pencil, at(x), 18f * u)
            Decor.REFUGE in scene.decor -> refuge(pencil, at(x), 14f * u)
            else -> null
        }
        if (Surprise.SMOKE in scene.surprises && chimney != null) anchors.chimneys += chimney
        if (t.river && Decor.CITY !in scene.decor) bridge(pencil, Offset(w * x, h * RIVER), 10f * u)
        // Décembre : le sapin illuminé sur la place et une guirlande à travers le village.
        if (Decor.GARLANDS in scene.decor) {
            val side = if (Decor.VILLAGE in scene.decor || Decor.TOWN in scene.decor) 0.035f else half * 0.45f
            christmasTree(pencil, at(x + side) + Offset(0f, 1f * u), 17f * u, lit > 0f)
            garland(pencil, at(x - half * 0.85f) + Offset(0f, -9f * u), at(x + half * 0.85f) + Offset(0f, -9f * u), lit > 0f)
        }
        // Les réverbères aux deux bouts, allumés dès le coucher du soleil.
        if (Decor.LAMPS in scene.decor) {
            listOf(x - half - 0.012f, x + half + 0.012f).filter { !t.isWater(it, 0.01f) }.forEach { lamp(pencil, at(it), lit > 0f) }
        }
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
    // Sur la mer ou le plus grand lac : un voilier (qui navigue dans la couche animée), ou le monstre du Loch Ness.
    if (t.sea) {
        val x = if (Decor.LIGHTHOUSE in scene.decor) 0.60f else 0.70f
        if (Surprise.SAILBOAT in scene.surprises) anchors.sail = Offset(w * x, h * SEA + 4f * u)
    } else {
        t.water.maxByOrNull { it.endInclusive - it.start }?.let { run ->
            val mid = (run.start + run.endInclusive) / 2
            val x = mid.coerceIn(0.40f, 0.80f)
            val surface = y(t.heightAt(mid))
            if (Surprise.SAILBOAT in scene.surprises) anchors.sail = Offset(w * x, surface + 4f * u)
            if (Surprise.NESSIE in scene.surprises) nessie(pencil, Offset(w * x - 8f * u, surface + 6f * u))
        }
    }
    peakNames(pencil, scene, w, h)
}

/**
 * Les sommets nommés (OpenStreetMap), écrits au crayon au-dessus de la crête avec un petit trait : deux au plus, les plus
 * hauts d'abord ; pas dans le brouillard ni quand les nuages coiffent les sommets, pas sur le texte de l'écran, ni
 * au-dessus des immeubles de la ville.
 */
private fun DrawScope.peakNames(pencil: Pencil, scene: SketchScene, w: Float, h: Float) {
    val t = scene.terrain
    if (t.labels.isEmpty() || scene.sky == SketchSky.FOG || scene.lowClouds && t.relief.mountains) return
    val u = density
    val sh = skyHeight()
    val size = 9f
    val layout = layoutOf(scene)
    val built = layout.settlement?.takeIf { Decor.CITY in scene.decor || Decor.TOWN in scene.decor }
    // La croix est sur le plus haut point de la crête : le nom passe au-dessus.
    val crossY = if (Surprise.SUMMIT_CROSS in scene.surprises) ridgeLine(t).filter { it.x in w * 0.36f..w * 0.84f }.minOfOrNull { it.y } else null
    val placed = mutableListOf<Rect>()
    for (label in t.labels.sortedByDescending { it.elevation }) {
        if (placed.size == 2) break
        val x = label.x
        if (x !in 0.36f..0.84f || built != null && abs(x - built) < layout.half + 0.03f) continue
        val peak = Offset(w * x, h * (FOOT - t.heightAt(x)))
        val lift = if (crossY != null && abs(peak.y - crossY) < 3f * u) 15f else 7f
        val half = label.name.length * size * 0.26f * u
        // Centré sur le sommet ; poussé de côté s'il passerait sur le texte de l'écran (le ciel à droite, la
        // température à gauche).
        val y = peak.y - lift * u
        val beside = y - size * u < sh * 0.95f
        val center = when {
            beside && peak.x + half > w * 0.60f -> w * 0.60f - half
            beside && peak.x - half < w * 0.37f -> w * 0.37f + half
            else -> peak.x
        }
        val base = Offset(center, y)
        val box = Rect(base.x - half, base.y - size * u, base.x + half, base.y + 1f * u)
        if (box.left < w * 0.30f || box.right > w || box.top < sh * 0.30f || abs(center - peak.x) > half * 1.6f || placed.any { it.overlaps(box) } || pencil.nearText(box)) continue
        if (lift < 10f) pencil.line(peak + Offset(0f, -1.5f * u), Offset(peak.x + (center - peak.x) * 0.25f, peak.y - 4.5f * u), width = 0.6f, passes = 1, wobble = 0.1f)
        pencil.write(label.name, base, size)
        placed += box
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
 * retournée), en sillons qui fuient un peu vers le fond. Parmi elles, les cultures du lieu : des rangs de vigne, de
 * lavande en fleur, de tournesols. Hors de la plaine, seulement ces cultures-là, au milieu du premier plan.
 */
private fun DrawScope.fields(pencil: Pencil, scene: SketchScene, w: Float, h: Float, month: Int, rnd: Random) {
    val t = scene.terrain
    val plain = Decor.FIELDS in scene.decor
    val crops = when (month) {
        4, 5 -> listOf(Crayon.MEADOW, Crayon.YELLOW, Crayon.MEADOW, Crayon.EARTH)
        6, 7, 8 -> listOf(Crayon.WHEAT, Crayon.MEADOW, Crayon.WHEAT, Crayon.EARTH)
        9, 10 -> listOf(Crayon.EARTH, Crayon.MEADOW, Crayon.WHEAT, Crayon.EARTH)
        else -> listOf(Crayon.EARTH, Crayon.MEADOW, Crayon.EARTH, Crayon.MEADOW)
    }.shuffled(rnd)
    val special = listOf(Decor.VINES, Decor.LAVENDER, Decor.SUNFLOWERS).filter { it in scene.decor }
    val top = h * 0.943f
    val bottom = h * 0.99f
    val end = if (plain) 0.98f else 0.80f
    var x = if (plain) 0.02f else 0.36f
    var k = 0
    while (x < end) {
        val width = 0.07f + rnd.nextFloat() * 0.06f
        val x1 = minOf(x + width, end)
        // Une parcelle sur deux porte la culture du lieu ; hors de la plaine, les autres restent en herbe.
        val kind = if (special.isNotEmpty() && k % 2 == 0) special[(k / 2) % special.size] else null
        if (!t.isWater(x) && !t.isWater(x1) && (plain || kind != null)) {
            // Les limites des parcelles penchent vers le centre, comme vues un peu de haut.
            val lean = (0.55f - x) * 0.04f
            val lean1 = (0.55f - x1) * 0.04f
            val parcel = polygon(
                listOf(Offset(w * (x + lean), top), Offset(w * (x1 + lean1), top), Offset(w * (x1 - lean1), bottom), Offset(w * (x - lean), bottom)),
            )
            within(parcel) {
                when (kind) {
                    Decor.VINES -> vineRows(pencil, w * (x - 0.03f), w * (x1 + 0.03f), top, bottom, month)
                    Decor.LAVENDER -> plantRows(pencil, w * (x - 0.03f), w * (x1 + 0.03f), top, bottom, Crayon.VIOLET, null)
                    Decor.SUNFLOWERS -> plantRows(pencil, w * (x - 0.03f), w * (x1 + 0.03f), top, bottom, Crayon.YELLOW, Crayon.WOOD)
                    else -> {
                        val ink = pencil.ink(crops[k % crops.size])
                        var y = top + 1.5f * density
                        while (y < bottom) {
                            ink.line(Offset(w * (x - 0.03f), y), Offset(w * (x1 + 0.03f), y + 0.6f * density), width = 0.9f, passes = 1, wobble = 0.3f)
                            y += (2.4f + (y - top) / (bottom - top) * 1.6f) * density
                        }
                    }
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

/**
 * Rangs de vigne : les piquets et le fil, les ceps en petites touffes, verts l'été, rouges et dorés aux vendanges,
 * nus l'hiver ; plus serrés vers le fond.
 */
private fun DrawScope.vineRows(pencil: Pencil, x0: Float, x1: Float, top: Float, bottom: Float, month: Int) {
    val u = density
    val leaves = when (month) {
        9, 10 -> listOf(Crayon.AUTUMN, Crayon.RED)
        11, 12, 1, 2, 3 -> emptyList()
        else -> listOf(Crayon.GREEN, Crayon.MEADOW)
    }
    val wood = pencil.ink(Crayon.WOOD)
    var y = top + 2.5f * u
    var row = 0
    while (y < bottom) {
        val gap = (2.6f + (y - top) / (bottom - top) * 1.8f) * u
        wood.line(Offset(x0, y), Offset(x1, y + 0.4f * u), width = 0.6f, passes = 1, wobble = 0.2f)
        var x = x0 + (row % 2) * gap / 2
        while (x < x1) {
            if (leaves.isEmpty()) {
                wood.line(Offset(x, y), Offset(x, y - 2f * u), width = 0.7f, passes = 1, wobble = 0.1f)
            } else {
                pencil.ink(leaves[((x / gap).toInt() + row) % leaves.size]).dot(Offset(x, y - 1f * u), 1.2f)
            }
            x += gap
        }
        y += gap * 1.1f
        row++
    }
}

/** Rangs de fleurs : la lavande en touffes violettes, ou les tournesols jaunes à cœur brun ([heart]). */
private fun DrawScope.plantRows(pencil: Pencil, x0: Float, x1: Float, top: Float, bottom: Float, color: androidx.compose.ui.graphics.Color, heart: androidx.compose.ui.graphics.Color?) {
    val u = density
    val ink = pencil.ink(color)
    val green = pencil.ink(Crayon.MEADOW)
    var y = top + 2f * u
    var row = 0
    while (y < bottom) {
        val gap = (2.8f + (y - top) / (bottom - top) * 1.6f) * u
        var x = x0 + (row % 2) * gap / 2
        while (x < x1) {
            if (heart == null) {
                green.line(Offset(x, y + 0.8f * u), Offset(x, y - 0.4f * u), width = 0.6f, passes = 1, wobble = 0.1f)
                ink.dot(Offset(x - 0.5f * u, y - 0.8f * u), 0.9f)
                ink.dot(Offset(x + 0.5f * u, y - 1.1f * u), 0.9f)
            } else {
                ink.dot(Offset(x, y - 0.6f * u), 1.3f)
                pencil.ink(heart).dot(Offset(x, y - 0.6f * u), 0.5f)
            }
            x += gap
        }
        y += gap * 0.9f
        row++
    }
}

/** Dessine [block] limité à [shape]. */
private inline fun DrawScope.within(shape: Path, crossinline block: DrawScope.() -> Unit) = clipPath(shape) { block() }

/** La rivière : un ruban qui serpente au pied du relief, plus large au milieu, ses rives et ses rides. */
private fun DrawScope.river(pencil: Pencil, w: Float, h: Float, rnd: Random) {
    val u = density
    val x0 = 0.02f
    val x1 = 0.98f
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
        when {
            // La forêt de Provence : des pins parasols, quelques cyprès.
            scene.local == LocalStyle.PROVENCE -> if (rnd.nextFloat() < 0.25f) cypress(pencil, Offset(w * x, base), size * 1.2f * u, scene.lean) else umbrellaPine(pencil, Offset(w * x, base), size * u, scene.lean)
            conifer -> fir(pencil, Offset(w * x, base), size * u, scene.lean)
            else -> tree(pencil, Offset(w * x, base), size * u, month, scene.lean)
        }
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
private fun DrawScope.townHouse(pencil: Pencil, ground: Offset, width: Float, floors: Int, lit: Float, rnd: Random): Offset {
    val u = density
    val x = ground.x
    val g = ground.y
    val wall = floors * 6.5f * u + 2f * u
    val walls = rect(x - width / 2, g - wall, x + width / 2, g)
    val roof = roof(pencil, x - width / 2, x + width / 2, g - wall, width * 0.42f, 1.5f * u)
    pencil.erase(Path().apply { addPath(walls) }.union(roof))
    pencil.outline(walls, width = 1f)
    pencil.outline(roof, width = 1f)
    val tiles = if (rnd.nextFloat() < 0.75f) Crayon.ROOF else Crayon.FAR
    // Des façades de couleur, une teinte claire par maison.
    if (!pencil.culture.flatRoofs) facade(pencil, walls, (x / u).toInt() + floors)
    when {
        pencil.culture.flatRoofs -> pencil.ink(Crayon.WHEAT).hatch(walls, walls.getBounds(), spacing = 3.2f, width = 0.5f)
        pencil.culture.curvedRoofs -> pencil.roofInk(Crayon.FAR).hatch(roof, roof.getBounds(), spacing = 2f, width = 0.7f)
        else -> pencil.roofInk(tiles).hatch(roof, roof.getBounds(), spacing = 2f, width = 0.7f)
    }
    windows(pencil, Rect(x - width / 2, g - wall, x + width / 2, g - 4f * u), floors, if (width > 12f * u) 3 else 2, lit, rnd)
    return Offset(x + width * 0.25f, g - wall - width * 0.3f)
}

/**
 * Des rangées de fenêtres dans [facade] : petits carrés au crayon le jour ; à la tombée du jour, une part [lit] d'entre
 * elles s'allume en jaune, de plus en plus le soir (la nuit, les autres ne se voient plus).
 */
private fun DrawScope.windows(pencil: Pencil, facade: Rect, rows: Int, columns: Int, lit: Float, rnd: Random) {
    val u = density
    val yellow = pencil.ink(Crayon.YELLOW)
    for (r in 0 until rows) for (c in 0 until columns) {
        val cx = facade.left + facade.width * (c + 0.5f) / columns
        val cy = facade.top + facade.height * (r + 0.5f) / rows
        if (lit > 0f) {
            if (rnd.nextFloat() < lit) {
                drawRect(yellow.color.copy(alpha = (yellow.color.alpha * 1.4f).coerceAtMost(1f)), Offset(cx - 1f * u, cy - 1.2f * u), Size(2f * u, 2.4f * u))
            } else if (lit < 0.5f) {
                pencil.outline(rect(cx - 0.9f * u, cy - 1.2f * u, cx + 0.9f * u, cy + 1.2f * u), width = 0.6f, passes = 1)
            }
        } else {
            pencil.outline(rect(cx - 0.9f * u, cy - 1.2f * u, cx + 0.9f * u, cy + 1.2f * u), width = 0.6f, passes = 1)
        }
    }
}

/** Petite ville : l'église et son clocher au milieu, des maisons à étages autour, plus basses vers les bords. */
private fun DrawScope.town(pencil: Pencil, at: (Float) -> Offset, x: Float, half: Float, rnd: Random, lit: Float, size: Float = 0.5f, church: Boolean = true): Offset? {
    val u = density
    var chimney: Offset? = null
    // Du bord vers le milieu : les maisons du milieu, plus hautes, passent devant celles des bords. Plus la ville est
    // peuplée, plus il y a de maisons et d'étages.
    val pairs = 2 + (2 * size).roundToInt()
    val tallest = 2 + (2 * size).roundToInt()
    val slots = (0 until pairs).flatMap { k ->
        val f = 0.95f - 0.6f * k / maxOf(pairs - 1, 1)
        val floors = (1 + (tallest - 1) * k / maxOf(pairs - 1, 1)).coerceAtLeast(1)
        listOf(-f to floors, f + 0.03f to floors)
    }
    slots.forEach { (k, floors) ->
        val p = townHouse(pencil, at(x + half * k), (11f + rnd.nextFloat() * 4f) * u, floors, lit, rnd)
        if (k > 0 && chimney == null) chimney = p
    }
    if (church) worship(pencil, at(x - 0.03f), 1.1f)
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
    lit: Float,
    landmark: Landmark? = null,
    size: Float = 0.5f,
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
        Landmark.EMPIRE_STATE -> empireState(pencil, monumentAt, 70f * u, lit)
        Landmark.TOKYO -> eiffelTower(pencil, monumentAt, 60f * u, Crayon.RED)
        null, Landmark.STRASBOURG_CATHEDRAL -> Unit
        else -> silhouette(pencil, landmark.look, monumentAt, lit)
    }
    // Les plus hauts derrière : on dessine d'abord les tours du centre, puis les plus basses devant.
    val buildings = (0 until n).map { i ->
        val edge = 1 - abs(i - (n - 1) / 2f) / (n / 2f)
        // Plus la ville est peuplée, plus ses immeubles et sa tour montent haut.
        val height = if (i == tallest) 38f + 34f * size else 12f + (16f + 22f * size) * edge + rnd.nextFloat() * 8f
        Triple(i, height, (slot * w * (0.8f + rnd.nextFloat() * 0.25f)))
    }.sortedByDescending { it.second }
    buildings.forEach { (i, height, width) ->
        val base = at(x - half + (i + 0.5f) * slot)
        // Autour du monument, une place dégagée (le Champ-de-Mars, la colline, la rade) pour bien le voir.
        val open = landmark != Landmark.STRASBOURG_CATHEDRAL && landmark != Landmark.EMPIRE_STATE
        if (landmark != null && (i == middle || open && abs(i - middle) == 1)) {
            if (landmark == Landmark.STRASBOURG_CATHEDRAL) strasbourgCathedral(pencil, base)
            return@forEach
        }
        if (i == cathedral && i != tallest) {
            worship(pencil, base + Offset(-6f * u, 0f), 1.2f)
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
    // Au printemps, les arbres en fleurs : des pétales roses et blancs plein la couronne.
    if (month == 4) blossom(pencil, crownC, r)
}

/** Les fleurs du printemps dans une couronne de rayon [r] : des points roses, et quelques blancs (des cerisiers). */
private fun DrawScope.blossom(pencil: Pencil, c: Offset, r: Float) {
    val pink = pencil.ink(Crayon.PINK)
    for (k in 0 until 9) {
        val a = k * 2.4
        val d = r * (0.25f + 0.6f * ((k * 37) % 10) / 10f)
        val p = c + Offset((d * cos(a)).toFloat(), (d * sin(a) * 0.8).toFloat())
        if (k % 3 == 2) pencil.scope.drawCircle(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f), 1.1f * density, p) else pink.dot(p, 1.1f)
    }
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
    // Sous un toit plat, la maison monte plus haut ; pas de cheminée dans les pays chauds.
    val flat = pencil.culture.flatRoofs
    val wall = width * if (flat) 0.82f else 0.62f
    val walls = Path().apply { addRect(Rect(x - width / 2, g - wall, x + width / 2, g)) }
    val roof = roof(pencil, x - width / 2, x + width / 2, g - wall, width * 0.5f, 2f * u)
    val stack = if (chimney && !flat) Path().apply { addRect(Rect(x + width * 0.18f, g - wall - width * 0.52f, x + width * 0.32f, g - wall - width * 0.2f)) } else null
    val all = Path().apply { addPath(walls) }.union(roof).also { p -> stack?.let { p.union(it) } }
    pencil.erase(all)
    pencil.outline(walls, width = 1f)
    // Les murs clairs des pays chauds, l'ocre des maisons de Provence.
    if (flat || pencil.local == LocalStyle.PROVENCE) {
        pencil.ink(if (flat) Crayon.WHEAT else Crayon.ORANGE).hatch(walls, walls.getBounds(), spacing = 3.2f, width = 0.5f)
    } else {
        facade(pencil, walls, (x / u).toInt())
    }
    stack?.let { pencil.outline(it, width = 0.9f, passes = 1) }
    pencil.outline(roof, width = 1f)
    if (!flat) pencil.roofInk(if (pencil.culture.curvedRoofs) Crayon.FAR else Crayon.ROOF).hatch(roof, roof.getBounds(), spacing = 2f, width = 0.7f)
    val window = Path().apply { addRect(Rect(x - 1.5f * u, g - wall * 0.7f, x + 1.5f * u, g - wall * 0.7f + 3f * u)) }
    pencil.outline(window, width = 0.7f, passes = 1)
    if (lit) pencil.ink(Crayon.YELLOW).hatch(window, window.getBounds(), spacing = 0.8f, width = 0.8f)
    return if (stack != null) Offset(x + width * 0.25f, g - wall - width * 0.55f) else null
}

/** Les teintes claires des façades : crème, ocre, rose, jaune paille, terre. */
private val FACADES = listOf(Crayon.WHEAT, Crayon.ORANGE, Crayon.PINK, Crayon.YELLOW, Crayon.EARTH)

/** Une façade [walls] colorée légèrement, dans la teinte numéro [tint] (au hasard des maisons). */
private fun DrawScope.facade(pencil: Pencil, walls: Path, tint: Int) {
    val color = FACADES[((tint % FACADES.size) + FACADES.size) % FACADES.size]
    pencil.ink(color).pressed(0.55f).hatch(walls, walls.getBounds(), spacing = 2.6f, width = 0.6f, rising = false)
}

/**
 * Le toit d'une maison de [left] à [right], posé sur [eave], de [height] de haut et débordant de [overhang] : en pente
 * de tuiles, plat avec son petit muret (pays chauds), ou aux coins relevés (Asie de l'Est).
 */
private fun DrawScope.roof(pencil: Pencil, left: Float, right: Float, eave: Float, height: Float, overhang: Float): Path {
    val u = density
    val x = (left + right) / 2
    return when {
        pencil.culture.flatRoofs -> rect(left - 0.6f * u, eave - 1.6f * u, right + 0.6f * u, eave)
        pencil.culture.curvedRoofs -> Path().apply {
            val w = right - left
            moveTo(left - overhang - 1.5f * u, eave - 2.2f * u)
            quadraticTo(left + w * 0.05f, eave + 0.4f * u, x - w * 0.3f, eave - height * 0.75f)
            lineTo(x + w * 0.3f, eave - height * 0.75f)
            quadraticTo(right - w * 0.05f, eave + 0.4f * u, right + overhang + 1.5f * u, eave - 2.2f * u)
            lineTo(right, eave)
            lineTo(left, eave)
            close()
        }
        else -> polygon(listOf(Offset(left - overhang, eave), Offset(x, eave - height), Offset(right + overhang, eave)))
    }
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

/**
 * La fumée de la cheminée : elle monte en volutes (qui s'élèvent au fil de l'animation, [phase]), ou part à
 * l'horizontale quand le vent souffle ([lean]).
 */
private fun DrawScope.smoke(pencil: Pencil, from: Offset, lean: Float = 0f, phase: Float = 0f) {
    val u = density
    val side = if (lean < 0) -1f else 1f
    val wind = kotlin.math.abs(lean)
    val points = (0..18).map { i ->
        Offset(from.x + side * i * (0.9f + 2.2f * wind) * u + (sin(i * 0.8 - 2 * PI * phase * 6) * 2.6 * (1 - wind * 0.7) * u).toFloat(), from.y - i * (1.5f - 1.1f * wind) * u)
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
    val u = density
    // Ce qui passe dans le ciel se met à côté du texte de l'écran.
    fun aside(x: Float, y: Float, rx: Float, ry: Float) = pencil.clear(Offset(w * x, sky * y), rx * u, ry * u)
    for (surprise in scene.surprises) when (surprise) {
        Surprise.PARAGLIDER -> paraglider(pencil, aside(0.81f, 0.43f, 14f, 14f))
        Surprise.SHOOTING_STAR -> {
            val shift = aside(0.815f, 0.47f, 30f, 14f) - Offset(w * 0.815f, sky * 0.47f)
            shootingStar(pencil, Offset(w * 0.75f, sky * 0.53f) + shift, Offset(w * 0.88f, sky * 0.41f) + shift)
        }
        Surprise.UFO -> ufo(pencil, aside(0.81f, 0.46f, 16f, 10f), 24f * u, beamTo = null)
        Surprise.ABDUCTION -> {
            val shift = aside(0.81f, 0.46f, 20f, 18f) - Offset(w * 0.81f, sky * 0.46f)
            ufo(pencil, Offset(w * 0.81f, sky * 0.42f) + shift, 30f * u, beamTo = h * 0.95f)
            sheep(pencil, Offset(w * 0.81f, sky * 0.51f) + shift)
        }
        Surprise.SLEIGH -> sleigh(pencil, aside(0.80f, 0.46f, 30f, 12f))
        Surprise.FIREWORKS -> fireworks(pencil, w, sky)
        Surprise.BATS -> listOf(0.78f to 0.43f, 0.44f to 0.48f, 0.87f to 0.50f).forEach { (x, y) -> bat(pencil, aside(x, y, 7f, 5f)) }
        Surprise.HEART -> heart(pencil, aside(0.79f, 0.48f, 14f, 14f), 12f * u)
        Surprise.FISH -> fish(pencil, aside(0.80f, 0.46f, 14f, 9f))
        Surprise.NOTES -> notes(pencil, w, sky)
        Surprise.SNOWMAN -> snowman(pencil, ground)
        Surprise.UMBRELLA -> umbrella(pencil, ground)
        Surprise.PUMPKIN -> pumpkin(pencil, ground)
        Surprise.EGGS -> eggs(pencil, ground)
        Surprise.BLACK_CAT -> cat(pencil, ground)
        Surprise.LILY -> lily(pencil, ground)
        // Dessinés avec le paysage ou le ciel, ou dans la couche animée.
        Surprise.SUMMIT_CROSS, Surprise.SAILBOAT, Surprise.NESSIE, Surprise.SMOKE, Surprise.RAINBOW, Surprise.BIRDS, Surprise.PLANE,
        Surprise.BALLOON, Surprise.AURORA, Surprise.METEORS, Surprise.HEAT, Surprise.FROST, Surprise.LEAVES -> Unit
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
private fun DrawScope.eiffelTower(pencil: Pencil, ground: Offset, height: Float, color: androidx.compose.ui.graphics.Color = Crayon.EARTH) {
    val x = ground.x
    val g = ground.y
    val w = height * 0.42f
    fun at(f: Float, side: Float) = Offset(x + side * w / 2 * (1 - f) * (1 - f) * (1 + 0.6f * f), g - height * f)
    val legs = listOf(-1f, 1f).map { side -> (0..12).map { at(it / 12f * 0.92f, side) } }
    val outline = polygon(legs[0] + legs[1].reversed())
    pencil.erase(outline)
    legs.forEach { pencil.ink(color).stroke(it, width = 1.1f) }
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

/** L'Empire State Building : une tour à gradins, sa flèche, et ses fenêtres (allumées la nuit). */
private fun DrawScope.empireState(pencil: Pencil, ground: Offset, height: Float, lit: Float) {
    val u = density
    val x = ground.x
    val g = ground.y
    val tiers = listOf(0f to 9f, 0.55f to 7f, 0.72f to 5f, 0.82f to 3.2f)
    val shape = tiers.foldIndexed(Path()) { k, path, (from, half) ->
        val to = tiers.getOrNull(k + 1)?.first ?: 0.9f
        path.union(rect(x - half * u, g - height * to, x + half * u, g - height * from))
    }
    pencil.erase(shape)
    pencil.outline(shape, width = 1f)
    pencil.ink(Crayon.ROCK).hatch(shape, Rect(x + 2f * u, g - height, x + 9f * u, g), spacing = 2f, width = 0.6f)
    windows(pencil, Rect(x - 9f * u, g - height * 0.55f, x + 9f * u, g - 3f * u), 7, 3, lit, Random(7))
    pencil.line(Offset(x, g - height * 0.9f), Offset(x, g - height * 1.08f), width = 1f)
    pencil.ink(Crayon.RED).dot(Offset(x, g - height * 1.09f), 1f)
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

/** Un oiseau en V ; [flap] de -1 à 1 : les ailes vers le haut ou vers le bas. */
private fun DrawScope.bird(pencil: Pencil, c: Offset, s: Float, flap: Float = 1f) {
    val tip = -0.3f * s * flap
    pencil.stroke(
        listOf(c + Offset(-s, tip), c + Offset(-0.5f * s, -0.6f * s * (0.4f + 0.6f * flap)), c, c + Offset(0.5f * s, -0.6f * s * (0.4f + 0.6f * flap)), c + Offset(s, tip)),
        width = 1f,
    )
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

// --- L'autre chaîne, les cultures, le patrimoine, les éoliennes, le train, le port, Noël, les réverbères ---

/**
 * La deuxième chaîne de montagnes, dans une autre direction : derrière le relief, pâle et bleutée comme tout ce qui
 * est loin ; son trait seulement là où elle dépasse.
 */
private fun DrawScope.backdrop(pencil: Pencil, t: SketchTerrain, w: Float, h: Float, land: Path, snowY: Float) {
    val line = listOf(Offset(0f, h * FOOT)) + t.backdrop.map { (x, lift) -> Offset(w * x, h * (FOOT - lift)) } + listOf(Offset(w, h * FOOT))
    val behind = Path().apply { op(below(line), land, PathOperation.Difference) }
    val far = pencil.ink(Crayon.FAR)
    far.hatch(behind, Rect(0f, 0f, w, h), spacing = 7f, width = 0.6f)
    // Les sommets lointains blanchis l'hiver, quand la neige descend bas.
    if (snowY > h * (FOOT - 0.06f)) pencil.ink(Crayon.SNOW).hatch(behind, Rect(0f, 0f, w, h * (FOOT - 0.06f)), spacing = 7f, width = 0.6f, rising = false)
    clipPath(Path().apply { op(rect(0f, 0f, w, h), land, PathOperation.Difference) }) {
        far.stroke(line.drop(1).dropLast(1), width = 0.9f, passes = 1, shake = 0.3f)
    }
}

/** La voie ferrée : deux rails et leurs traverses sur le ballast, interrompue par l'eau. */
private fun DrawScope.railway(pencil: Pencil, t: SketchTerrain, w: Float, y: Float) {
    val u = density
    val wood = pencil.ink(Crayon.WOOD)
    var x = 0.02f
    while (x < 0.98f) {
        if (t.isWater(x, 0.005f)) {
            x += 0.005f
            continue
        }
        val start = x
        while (x < 0.98f && !t.isWater(x, 0.005f)) x += 0.005f
        pencil.erase(rect(w * start, y - 1.4f * u, w * x, y + 1.6f * u))
        var s = w * start + 1.5f * u
        while (s < w * x - 1f * u) {
            wood.line(Offset(s - 0.5f * u, y + 1.5f * u), Offset(s + 0.5f * u, y - 1.3f * u), width = 0.6f, passes = 1, wobble = 0.05f)
            s += 3.4f * u
        }
        pencil.line(Offset(w * start, y - 0.6f * u), Offset(w * x, y - 0.6f * u), width = 0.7f, passes = 1, wobble = 0.15f)
        pencil.line(Offset(w * start, y + 0.9f * u), Offset(w * x, y + 0.9f * u), width = 0.7f, passes = 1, wobble = 0.15f)
    }
}

/** Le port : un quai de pierre et ses bornes au bord de la mer (ou du lac) ; les bateaux amarrés bougent avec l'eau. */
private fun DrawScope.harbour(pencil: Pencil, scene: SketchScene, w: Float, h: Float) {
    val t = scene.terrain
    val u = density
    val x0: Float
    val x1: Float
    val surface: Float
    if (t.sea) {
        x0 = 0.36f
        x1 = 0.46f
        surface = h * SEA
    } else {
        val run = t.water.maxByOrNull { it.endInclusive - it.start } ?: return
        x0 = maxOf(run.start, 0.30f)
        x1 = minOf(x0 + 0.08f, run.endInclusive - 0.05f)
        surface = h * (FOOT - t.heightAt((run.start + run.endInclusive) / 2))
    }
    if (x1 - x0 < 0.03f) return
    val quay = rect(w * x0, surface - 2.5f * u, w * x1, surface + 4f * u)
    pencil.erase(quay)
    pencil.outline(quay, width = 0.9f)
    pencil.ink(Crayon.ROCK).hatch(quay, quay.getBounds(), spacing = 1.6f, width = 0.6f)
    listOf(0.2f, 0.55f, 0.85f).forEach { f -> pencil.dot(Offset(w * (x0 + (x1 - x0) * f), surface - 3.4f * u), 1f) }
    listOf(0.028f, 0.062f).map { x1 + it }.filter { t.sea || t.isWater(it, -0.01f) }.forEach { x -> scene.anchors.moorings += Offset(w * x, surface + 3.5f * u) }
}

/** Un petit bateau amarré : la coque et son mât, la voile roulée. */
private fun DrawScope.boat(pencil: Pencil, at: Offset, color: androidx.compose.ui.graphics.Color) {
    val u = density
    val hull = polygon(listOf(Offset(at.x - 6f * u, at.y - 2.5f * u), Offset(at.x + 6f * u, at.y - 2.5f * u), Offset(at.x + 4f * u, at.y + 0.5f * u), Offset(at.x - 4f * u, at.y + 0.5f * u)))
    pencil.erase(hull)
    pencil.outline(hull, width = 0.9f)
    pencil.ink(color).hatch(hull, hull.getBounds(), spacing = 1.4f, width = 0.6f)
    pencil.ink(Crayon.WOOD).line(Offset(at.x, at.y - 2.5f * u), Offset(at.x, at.y - 16f * u), width = 0.8f, passes = 1)
    pencil.line(Offset(at.x, at.y - 5f * u), Offset(at.x + 5f * u, at.y - 4.5f * u), width = 1.1f, passes = 1)
}

/** Un verger : une rangée de petits arbres fruitiers au pied du relief, en fleurs au printemps, chargés de fruits l'été. */
private fun DrawScope.orchard(pencil: Pencil, t: SketchTerrain, w: Float, h: Float, month: Int, rnd: Random, free: (Float) -> Boolean) {
    val u = density
    val step = 0.024f
    val start = (0..28).map { 0.40f + it * 0.01f }.firstOrNull { x0 -> (0..4).all { k -> val x = x0 + k * step; free(x) && t.heightAt(x) < 0.08f } } ?: return
    val wood = pencil.ink(Crayon.WOOD)
    for (k in 0..4) {
        val x = start + k * step
        val base = Offset(w * x, h * (FOOT - t.heightAt(x)) + 3f * u)
        val c = base + Offset(0f, -7f * u)
        wood.line(base, c + Offset(0f, 2f * u), width = 1f, passes = 1)
        if (month == 12 || month in 1..2) {
            listOf(-1f, 1f).forEach { side -> wood.line(c + Offset(0f, 2f * u), c + Offset(side * 3.5f * u, -2f * u), width = 0.7f, passes = 1) }
            continue
        }
        val crown = oval(c, 4.2f * u, 3.6f * u)
        pencil.erase(crown)
        pencil.outline(crown, width = 0.8f, passes = 1)
        pencil.ink(if (month in 10..11) Crayon.AUTUMN else Crayon.MEADOW).hatch(crown, crown.getBounds(), spacing = 1.8f, width = 0.6f)
        when (month) {
            4 -> blossom(pencil, c, 4f * u)
            in 7..9 -> repeat(3) { pencil.ink(Crayon.RED).dot(c + Offset((rnd.nextFloat() - 0.5f) * 5f * u, (rnd.nextFloat() - 0.5f) * 4f * u), 0.9f) }
        }
    }
}

/**
 * Les éoliennes des environs, sur les hauteurs : deux ou trois mâts blancs ; leurs pales tournent avec le vent dans la
 * couche animée ([SketchAnchors.rotors]). Plus petites au loin.
 */
private fun DrawScope.turbines(pencil: Pencil, t: SketchTerrain, w: Float, h: Float, anchors: SketchAnchors, taken: MutableList<ClosedFloatingPointRange<Float>>) {
    val u = density
    val spots = (0..34).map { 0.42f + it * 0.01f }.filter { x -> !t.isWater(x, 0.01f) && taken.none { x in it } }.sortedByDescending { t.heightAt(it) }
    val chosen = mutableListOf<Float>()
    for (x in spots) {
        if (chosen.size == 3) break
        if (chosen.all { abs(it - x) >= 0.06f }) chosen += x
    }
    chosen.sorted().forEach { x ->
        val far = x > t.farX
        val size = (if (far) 20f else 28f) * u
        val base = Offset(w * x, h * (FOOT - t.heightAt(x)) + 1f * u)
        val hub = base + Offset(0f, -size)
        val mast = polygon(listOf(base + Offset(-1.1f * u, 0f), hub + Offset(-0.5f * u, 0f), hub + Offset(0.5f * u, 0f), base + Offset(1.1f * u, 0f)))
        pencil.erase(mast)
        (if (far) pencil.ink(Crayon.FAR) else pencil).outline(mast, width = 0.7f, passes = 1)
        pencil.outline(rect(hub.x - 1.2f * u, hub.y - 1f * u, hub.x + 2.4f * u, hub.y + 1f * u), width = 0.7f, passes = 1)
        anchors.rotors += hub to size * 0.42f
        taken += (x - 0.025f)..(x + 0.025f)
    }
}

/** Le rotor d'une éolienne : trois pales fines autour du moyeu, tournées de [angle] degrés. */
private fun DrawScope.rotor(pencil: Pencil, hub: Offset, length: Float, angle: Float) {
    val u = density
    for (k in 0 until 3) {
        val a = Math.toRadians((angle + k * 120).toDouble())
        val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
        val side = Offset(-dir.y, dir.x)
        val blade = polygon(listOf(hub + side * (0.9f * u), hub + dir * (length * 0.25f) + side * (1.3f * u), hub + dir * length, hub - side * (0.4f * u)))
        pencil.erase(blade)
        pencil.outline(blade, width = 0.6f, passes = 1)
    }
    pencil.dot(hub, 1.1f)
}

/** Où poussent les feuillus du décor. */
private val TREE_SPOTS = listOf(0.47f, 0.555f, 0.65f)

/** Direction de Crussol depuis Valence, en degrés depuis le nord. */
private const val CRUSSOL_BEARING = 290.0

/** Le château (ou des ruines) sur une hauteur, l'abbaye au fond du vallon, là où il reste de la place. */
private fun DrawScope.heritage(pencil: Pencil, scene: SketchScene, w: Float, h: Float, taken: MutableList<ClosedFloatingPointRange<Float>>) {
    val t = scene.terrain
    val u = density
    // En plaine, le lointain est plat : le château peut y être aussi, mais pas sur les montagnes de l'horizon.
    fun open(x: Float) = !t.isWater(x, 0.03f) && taken.none { x in it } && (x < t.farX || t.relief == Relief.PLAIN && t.heightAt(x) < 0.02f) &&
        t.heightAt(x) < t.snowline - 0.005f
    fun ground(x: Float) = Offset(w * x, h * (FOOT - t.heightAt(x)) + 1f * u)
    val xs = (0..30).map { 0.40f + it * 0.01f }
    if (scene.landmark?.look == Silhouette.APOLLINAIRE) {
        // Valence : Crussol est à l'ouest-nord-ouest, de l'autre côté du Rhône. Quand la coupe va vers lui, ses ruines
        // sont sur la colline de la coupe ; sinon (vers le Vercors), la montagne est à gauche de la ville.
        val toward = abs(((t.bearing - CRUSSOL_BEARING) % 360 + 540) % 360 - 180) < 90
        // Le calcaire est nu au sommet : pas sous un des arbres.
        val bare = { x: Float -> Decor.TREES !in scene.decor || TREE_SPOTS.none { abs(it - x) < 0.03f } }
        val hilltop = xs.filter { x -> toward && open(x - 0.025f) && open(x) && open(x + 0.025f) && t.heightAt(x) > 0.03f && bare(x) }.maxByOrNull { t.heightAt(it) }
        if (hilltop != null) {
            crussol(pencil, ground(hilltop), hill = false)
            taken += (hilltop - 0.03f)..(hilltop + 0.03f)
        } else {
            crussol(pencil, ground(0.27f), hill = true)
        }
    } else if (Decor.CASTLE in scene.decor || Decor.RUINS in scene.decor) {
        xs.filter { x -> open(x - 0.025f) && open(x) && open(x + 0.025f) }.maxByOrNull { t.heightAt(it) }?.let { x ->
            // En plaine, sur une butte rocheuse ; sur une colline, la colline suffit.
            val g = if (t.relief == Relief.PLAIN && t.heightAt(x) < 0.02f) butte(pencil, ground(x)) else ground(x)
            if (Decor.CASTLE in scene.decor) castle(pencil, g, 1f) else ruins(pencil, g)
            taken += (x - 0.04f)..(x + 0.04f)
        }
    }
    if (Decor.ABBEY in scene.decor) {
        xs.filter { x -> open(x - 0.03f) && open(x) && open(x + 0.03f) }.minByOrNull { t.heightAt(it) + abs(it - 0.55f) * 0.05f }?.let { x ->
            abbey(pencil, ground(x))
            taken += (x - 0.045f)..(x + 0.045f)
        }
    }
}

/** Un château fort : le donjon crénelé entre deux tours rondes coiffées d'ardoise, la porte en arc. */
private fun DrawScope.castle(pencil: Pencil, ground: Offset, scale: Float) {
    val u = density * scale
    val x = ground.x
    val g = ground.y
    val keep = rect(x - 6f * u, g - 17f * u, x + 6f * u, g)
    val merlons = (0..3).fold(Path()) { p, k -> p.union(rect(x - 6f * u + k * 3.4f * u, g - 19.5f * u, x - 4f * u + k * 3.4f * u, g - 17f * u)) }
    val walls = rect(x - 14f * u, g - 9f * u, x + 14f * u, g)
    val towers = listOf(-13f, 13f).map { dx -> rect(x + (dx - 3f) * u, g - 15f * u, x + (dx + 3f) * u, g) }
    val roofs = listOf(-13f, 13f).map { dx -> polygon(listOf(Offset(x + (dx - 3.8f) * u, g - 15f * u), Offset(x + dx * u, g - 23f * u), Offset(x + (dx + 3.8f) * u, g - 15f * u))) }
    val all = (towers + roofs).fold(Path().apply { addPath(walls) }.union(keep).union(merlons)) { p, s -> p.union(s) }
    pencil.erase(all)
    val stone = pencil.ink(Crayon.ROCK)
    pencil.outline(walls, width = 0.9f)
    stone.hatch(walls, walls.getBounds(), spacing = 2.2f, width = 0.6f)
    pencil.outline(keep, width = 1f)
    pencil.outline(merlons, width = 0.7f, passes = 1)
    stone.hatch(keep, keep.getBounds(), spacing = 2.6f, width = 0.6f)
    towers.forEach { pencil.outline(it, width = 0.9f) }
    roofs.forEach {
        pencil.outline(it, width = 0.9f)
        pencil.roofInk(Crayon.FAR).hatch(it, it.getBounds(), spacing = 1.5f, width = 0.6f)
    }
    // La porte, une meurtrière, et le drapeau sur le donjon.
    pencil.stroke(listOf(Offset(x - 2f * u, g), Offset(x - 2f * u, g - 4f * u), Offset(x, g - 6f * u), Offset(x + 2f * u, g - 4f * u), Offset(x + 2f * u, g)), width = 0.8f, passes = 1)
    pencil.line(Offset(x, g - 13f * u), Offset(x, g - 10f * u), width = 0.7f, passes = 1)
    pencil.line(Offset(x + 4f * u, g - 19.5f * u), Offset(x + 4f * u, g - 26f * u), width = 0.7f, passes = 1)
    val flag = polygon(listOf(Offset(x + 4f * u, g - 26f * u), Offset(x + 9f * u, g - 24.8f * u), Offset(x + 4f * u, g - 23.5f * u)))
    pencil.ink(Crayon.RED).hatch(flag, flag.getBounds(), spacing = 1f, width = 0.6f)
}

/** Des ruines : un pan de mur au sommet déchiqueté, une fenêtre en arc, du lierre. */
private fun DrawScope.ruins(pencil: Pencil, ground: Offset) {
    val u = density
    val x = ground.x
    val g = ground.y
    val wall = polygon(
        listOf(
            Offset(x - 13f * u, g), Offset(x - 13f * u, g - 9f * u), Offset(x - 9f * u, g - 13f * u), Offset(x - 6f * u, g - 11f * u),
            Offset(x - 4f * u, g - 20f * u), Offset(x + 1f * u, g - 21f * u), Offset(x + 2f * u, g - 15f * u), Offset(x + 6f * u, g - 12f * u),
            Offset(x + 8f * u, g - 6f * u), Offset(x + 12f * u, g - 4f * u), Offset(x + 13f * u, g),
        ),
    )
    pencil.erase(wall)
    pencil.outline(wall, width = 1f)
    pencil.ink(Crayon.ROCK).hatch(wall, wall.getBounds(), spacing = 2.4f, width = 0.6f)
    val window = Path().apply {
        moveTo(x - 3f * u, g - 6f * u)
        lineTo(x - 3f * u, g - 11f * u)
        quadraticTo(x - 1f * u, g - 15f * u, x + 1f * u, g - 11f * u)
        lineTo(x + 1f * u, g - 6f * u)
        close()
    }
    pencil.erase(window)
    pencil.outline(window, width = 0.7f, passes = 1)
    listOf(-10f to -3f, -8f to -6f, 5f to -3f, 9f to -2f, -11f to -7f).forEach { (dx, dy) -> pencil.ink(Crayon.FOREST).dot(Offset(x + dx * u, g + dy * u), 1.2f) }
}

/** Une abbaye : la longue église et son clocher carré, le cloître en arcades devant. */
private fun DrawScope.abbey(pencil: Pencil, ground: Offset) {
    val u = density
    val x = ground.x
    val g = ground.y
    val nave = rect(x - 14f * u, g - 10f * u, x + 8f * u, g)
    val roof = polygon(listOf(Offset(x - 15f * u, g - 10f * u), Offset(x - 10f * u, g - 15f * u), Offset(x + 8f * u, g - 15f * u), Offset(x + 9f * u, g - 10f * u)))
    val tower = rect(x + 8f * u, g - 21f * u, x + 14f * u, g)
    val cap = polygon(listOf(Offset(x + 7.5f * u, g - 21f * u), Offset(x + 11f * u, g - 26f * u), Offset(x + 14.5f * u, g - 21f * u)))
    pencil.erase(Path().apply { addPath(nave) }.union(roof).union(tower).union(cap))
    pencil.outline(nave, width = 0.9f)
    pencil.ink(Crayon.WHEAT).hatch(nave, nave.getBounds(), spacing = 2.6f, width = 0.5f)
    pencil.outline(roof, width = 0.9f)
    pencil.roofInk(Crayon.ROOF).hatch(roof, roof.getBounds(), spacing = 1.8f, width = 0.6f)
    pencil.outline(tower, width = 1f)
    pencil.outline(cap, width = 0.9f)
    pencil.roofInk(Crayon.ROOF).hatch(cap, cap.getBounds(), spacing = 1.5f, width = 0.6f)
    pencil.outline(oval(Offset(x + 11f * u, g - 16f * u), 1.4f * u, 1.4f * u), width = 0.6f, passes = 1)
    pencil.line(Offset(x + 11f * u, g - 26f * u), Offset(x + 11f * u, g - 29f * u), width = 0.7f, passes = 1)
    // Les arcades du cloître.
    for (k in 0 until 5) {
        val ax = x - 13f * u + k * 4f * u
        pencil.stroke(listOf(Offset(ax, g), Offset(ax, g - 3.5f * u), Offset(ax + 1.5f * u, g - 5.2f * u), Offset(ax + 3f * u, g - 3.5f * u), Offset(ax + 3f * u, g)), width = 0.6f, passes = 1)
    }
}

/** Le sapin de Noël : boules de couleur, étoile au sommet ; allumé le soir ([lit]), ses lumières brillent. */
private fun DrawScope.christmasTree(pencil: Pencil, base: Offset, height: Float, lit: Boolean) {
    fir(pencil, base, height)
    val u = density
    val colors = listOf(Crayon.RED, Crayon.YELLOW, Crayon.BLUE, Crayon.PINK)
    listOf(0.42f to -0.05f, 0.55f to 0.12f, 0.68f to -0.18f, 0.74f to 0.2f, 0.84f to -0.05f, 0.6f to -0.1f).forEachIndexed { k, (dy, dx) ->
        val p = base + Offset(dx * height, -height + dy * height)
        val ink = pencil.ink(colors[k % colors.size])
        if (lit) ink.hatch(oval(p, 2.2f * u, 2.2f * u), Rect(p, 2.2f * u), spacing = 0.9f, width = 0.5f)
        ink.dot(p, if (lit) 1.2f else 0.9f)
    }
    val top = base + Offset(0f, -height - 1.5f * u)
    val star = pencil.ink(Crayon.YELLOW)
    for (k in 0 until 5) {
        val a = -PI / 2 + k * 2 * PI / 5
        star.line(top, top + Offset((3f * u * cos(a)).toFloat(), (3f * u * sin(a)).toFloat()), width = 1f, passes = 1, wobble = 0.05f)
    }
}

/** Une guirlande qui pend entre deux points : des ampoules de couleur, qui brillent le soir. */
private fun DrawScope.garland(pencil: Pencil, from: Offset, to: Offset, lit: Boolean) {
    val u = density
    val n = ((to.x - from.x) / (4f * u)).toInt().coerceAtLeast(4)
    val points = (0..n).map { k ->
        val f = k / n.toFloat()
        // Elle pend en festons, d'une maison à l'autre.
        val sag = abs(sin(f * PI * 3)).toFloat() * 3f * u
        from + (to - from) * f + Offset(0f, sag)
    }
    pencil.stroke(points, width = 0.5f, passes = 1)
    val colors = listOf(Crayon.RED, Crayon.YELLOW, Crayon.GREEN, Crayon.BLUE)
    points.forEachIndexed { k, p -> pencil.ink(colors[k % colors.size]).dot(p + Offset(0f, 0.8f * u), if (lit) 1.1f else 0.7f) }
}

/** Un réverbère : le mât, la crosse et la lanterne, et sa lumière le soir. */
private fun DrawScope.lamp(pencil: Pencil, ground: Offset, lit: Boolean) {
    val u = density
    val top = ground + Offset(0f, -13f * u)
    pencil.line(ground, top, width = 0.8f, passes = 1)
    pencil.stroke(listOf(top, top + Offset(1.5f * u, -1.5f * u), top + Offset(3.5f * u, -1f * u)), width = 0.7f, passes = 1)
    val lantern = top + Offset(3.5f * u, 0.5f * u)
    if (lit) {
        val glow = pencil.ink(Crayon.YELLOW)
        glow.hatch(oval(lantern, 4.5f * u, 4.5f * u), Rect(lantern, 4.5f * u), spacing = 1.4f, width = 0.5f)
        glow.dot(lantern, 1.4f)
    } else {
        pencil.dot(lantern, 1f)
    }
}

// --- La couche animée ---

/**
 * Ce qui bouge dans le paysage et le ciel, à chaque image ([phase] de 0 à 1) : la fumée qui monte, le voilier qui
 * navigue, les bateaux qui dansent au port, les éoliennes, le train, les mouettes, les oiseaux, l'avion, le ballon, les
 * feuilles mortes. Toujours des tours entiers : l'animation boucle sans à-coup.
 */
internal fun DrawScope.animated(pencil: Pencil, scene: SketchScene, w: Float, h: Float, sky: Float, phase: Float) {
    val a = scene.anchors
    val u = density
    val lean = scene.lean
    val turn = 2 * PI * phase
    // Plus il y a de vent, plus les pales tournent vite (des tours entiers par cycle).
    val turns = (3 + scene.conditions.windKmh / 6).toInt().coerceAtMost(12)
    a.rotors.forEachIndexed { k, (hub, length) -> rotor(pencil, hub, length, phase * 120f * turns + k * 40f) }
    a.chimneys.forEach { smoke(pencil, it, lean, phase) }
    a.moorings.forEachIndexed { k, m -> boat(pencil, m + Offset(0f, (sin(turn * 3 + k * 1.7) * 0.8 * u).toFloat()), if (k % 2 == 0) Crayon.BLUE else Crayon.RED) }
    a.sail?.let { sailboat(pencil, it + Offset((w * 0.025f * sin(turn)).toFloat(), (sin(turn * 4) * 0.6 * u).toFloat())) }
    a.track?.let { train(pencil, scene.terrain, w, it, phase, scene.lit) }
    if (Decor.GULLS in scene.decor) {
        listOf(Triple(0.63f, 0.72f, 7f), Triple(0.71f, 0.67f, 6f), Triple(0.55f, 0.78f, 5f)).forEachIndexed { k, (x, y, size) ->
            val glide = turn + k * 2.1
            gull(pencil, Offset(w * x + (w * 0.02f * sin(glide)).toFloat(), h * y + (2.5f * u * cos(glide * 2)).toFloat()), size * u)
        }
    }
    for (surprise in scene.surprises) when (surprise) {
        Surprise.BIRDS -> flyingBirds(pencil, w, sky, phase)
        // L'avion traverse le ciel libre vers la droite, sa traînée derrière lui, une fois par cycle.
        Surprise.PLANE -> plane(pencil, Offset(w * (0.56f + 0.32f * phase), sky * (0.45f - 0.04f * phase)), trail = w * (0.06f + 0.10f * phase))
        Surprise.BALLOON -> balloon(pencil, pencil.clear(Offset(w * 0.43f, sky * 0.48f), 9f * u + w * 0.012f, 17f * u) + Offset((w * 0.012f * sin(turn)).toFloat(), (3f * u * sin(turn * 2)).toFloat()))
        Surprise.LEAVES -> leaves(pencil, w, h, phase, lean)
        else -> Unit
    }
}

/** Les oiseaux tournent en rond dans le ciel, en battant des ailes. */
private fun DrawScope.flyingBirds(pencil: Pencil, w: Float, h: Float, phase: Float) {
    val u = density
    // Leur ronde se fait à côté du texte de l'écran.
    val shift = pencil.clear(Offset(w * 0.755f, h * 0.47f), w * 0.09f, h * 0.06f) - Offset(w * 0.755f, h * 0.47f)
    listOf(Triple(0.77f, 0.45f, 5.5f), Triple(0.815f, 0.42f, 4.5f), Triple(0.845f, 0.49f, 4f), Triple(0.79f, 0.53f, 3.5f)).forEachIndexed { k, (x, y, s) ->
        val a = 2 * PI * (phase * 2 + k * 0.08)
        val c = shift + Offset(w * (x - 0.05f) + (w * 0.05f * cos(a)).toFloat(), h * y + (h * 0.025f * sin(a)).toFloat())
        val flap = sin(2 * PI * (phase * 30 + k * 0.25)).toFloat()
        bird(pencil, c, s * u, flap)
    }
}

/** Le train régional : la motrice et ses voitures, fenêtres allumées le soir ; il traverse la vallée une fois par cycle. */
private fun DrawScope.train(pencil: Pencil, t: SketchTerrain, w: Float, track: Float, phase: Float, lit: Float) {
    val u = density
    val car = 15f * u
    val gap = 1.5f * u
    val cars = 4
    val length = cars * (car + gap)
    // Sur une image fixe (phase 0), le train est au milieu de la vallée.
    val head = -length + (w + 2 * length) * ((phase + 0.5f) % 1f)
    for (c in 0 until cars) {
        val x1 = head - c * (car + gap)
        val x0 = x1 - car
        if (x1 < 0f || x0 > w || t.isWater(x0 / w) || t.isWater(x1 / w)) continue
        val top = track - 7.5f * u
        val body = if (c == 0) {
            polygon(listOf(Offset(x0, track - 1.2f * u), Offset(x0, top), Offset(x1 - 4f * u, top), Offset(x1, track - 3.5f * u), Offset(x1, track - 1.2f * u)))
        } else {
            rect(x0, top, x1, track - 1.2f * u)
        }
        pencil.erase(body)
        pencil.outline(body, width = 0.8f, passes = 1)
        // La livrée : le bas rouge, une bande grise.
        pencil.ink(Crayon.RED).hatch(body, Rect(x0, track - 3.6f * u, x1, track - 1.2f * u), spacing = 1.1f, width = 0.6f)
        val yellow = pencil.ink(Crayon.YELLOW)
        var wx = x0 + 2f * u
        var k = 0
        while (wx < x1 - (if (c == 0) 5f else 2.5f) * u) {
            val window = rect(wx, top + 1.5f * u, wx + 2f * u, top + 3.5f * u)
            if (lit > 0f && (k + c) % 3 != 2) drawRect(yellow.color.copy(alpha = (yellow.color.alpha * 1.4f).coerceAtMost(1f)), Offset(wx, top + 1.5f * u), Size(2f * u, 2f * u)) else pencil.outline(window, width = 0.5f, passes = 1)
            wx += 3.4f * u
            k++
        }
        listOf(x0 + 3f * u, x1 - 3f * u).forEach { pencil.dot(Offset(it, track - 0.6f * u), 1f) }
    }
}

/** Les feuilles mortes, orange et rousses, emportées par le vent de l'automne. */
private fun DrawScope.leaves(pencil: Pencil, w: Float, h: Float, phase: Float, lean: Float) {
    val u = density
    val side = if (lean < 0) -1f else 1f
    val colors = listOf(Crayon.AUTUMN, Crayon.ORANGE, Crayon.WOOD)
    for (k in 0 until 7) {
        val f = (phase * (1 + k % 2) + k / 7f) % 1f
        val x = 0.42f + 0.36f * (if (side > 0) f else 1 - f)
        val y = 0.70f + 0.20f * ((k * 0.37f) % 1f) + 0.03f * sin(2 * PI * (f * 2 + k * 0.3)).toFloat()
        val c = Offset(w * x, h * y)
        val a = 2 * PI * (phase * (3 + k % 3) + k * 0.2)
        val dir = Offset(cos(a).toFloat(), sin(a).toFloat()) * (2.6f * u)
        val normal = Offset(-dir.y, dir.x) * 0.45f
        val leaf = polygon(listOf(c - dir, c + normal, c + dir, c - normal))
        pencil.erase(leaf)
        pencil.ink(colors[k % colors.size]).outline(leaf, width = 0.8f, passes = 1)
        pencil.ink(colors[k % colors.size]).hatch(leaf, leaf.getBounds(), spacing = 0.9f, width = 0.5f)
    }
}

// --- Les silhouettes des villes ---

/** Le monument d'une famille de silhouettes ([Silhouette]), posé sur [ground]. */
internal fun DrawScope.silhouette(pencil: Pencil, look: Silhouette, ground: Offset, lit: Float) {
    when (look) {
        Silhouette.TWO_TOWERS -> cathedral(pencil, ground, spires = false)
        Silhouette.TWO_SPIRES -> cathedral(pencil, ground, spires = true)
        Silhouette.CENTRAL_SPIRE -> spiredCathedral(pencil, ground)
        Silhouette.BELFRY -> belfry(pencil, ground, Crayon.WHEAT, lit)
        Silhouette.BRICK_BELFRY -> belfry(pencil, ground, Crayon.ROOF, lit)
        Silhouette.DOME -> domed(pencil, ground)
        Silhouette.NEEDLE -> needle(pencil, ground, lit)
        Silhouette.RAMPARTS -> ramparts(pencil, ground)
        Silhouette.MOUNT -> mount(pencil, ground)
        Silhouette.CLOCK_TOWER -> clockTower(pencil, ground, lit)
        Silhouette.LEANING_TOWER -> leaningTower(pencil, ground)
        Silhouette.SPIRES -> sagrada(pencil, ground)
        Silhouette.ATOMIUM -> atomium(pencil, ground)
        Silhouette.OCTAGON -> octagon(pencil, ground)
        Silhouette.BURJ -> burj(pencil, ground, lit)
        Silhouette.GREAT_MOSQUE -> greatMosque(pencil, ground)
        Silhouette.ONIONS -> stBasil(pencil, ground)
        Silhouette.RED_TOWER -> eiffelTower(pencil, ground, 60f * density, Crayon.RED)
        Silhouette.TAJ -> taj(pencil, ground)
        Silhouette.PYRAMIDS -> pyramids(pencil, ground)
        Silhouette.OPERA -> opera(pencil, ground)
        Silhouette.STATUE_ON_HILL -> statueOnHill(pencil, ground)
        Silhouette.PERTUSIER -> pertusier(pencil, ground)
        Silhouette.APOLLINAIRE -> apollinaire(pencil, ground)
        Silhouette.CITADEL -> citadel(pencil, ground)
        Silhouette.OWN, Silhouette.NONE -> Unit
    }
}

/** Efface les formes, trace leurs contours et les hachure dans la couleur [stone]. */
private fun DrawScope.build(pencil: Pencil, shapes: List<Path>, stone: androidx.compose.ui.graphics.Color, spacing: Float = 2.4f) {
    pencil.erase(shapes.fold(Path()) { p, s -> p.union(s) })
    val ink = pencil.ink(stone)
    shapes.forEach {
        pencil.outline(it, width = 0.9f, passes = 1)
        ink.hatch(it, it.getBounds(), spacing = spacing, width = 0.5f)
    }
}

/** Un toit pointu (flèche) de [left] à [right] sur [base], jusqu'à [top]. */
private fun spire(left: Float, right: Float, base: Float, top: Float) = polygon(listOf(Offset(left, base), Offset((left + right) / 2, top), Offset(right, base)))

/** Une cathédrale gothique de face : deux tours carrées (ou coiffées de flèches), la rose, les trois portails, la nef. */
private fun DrawScope.cathedral(pencil: Pencil, ground: Offset, spires: Boolean) {
    val u = density
    val x = ground.x
    val g = ground.y
    val nave = rect(x + 10f * u, g - 20f * u, x + 32f * u, g)
    val roof = polygon(listOf(Offset(x + 10f * u, g - 20f * u), Offset(x + 12f * u, g - 27f * u), Offset(x + 32f * u, g - 27f * u), Offset(x + 33f * u, g - 20f * u)))
    val front = rect(x - 11f * u, g - 27f * u, x + 11f * u, g)
    val towers = listOf(rect(x - 11f * u, g - 42f * u, x - 4f * u, g - 27f * u), rect(x + 4f * u, g - 42f * u, x + 11f * u, g - 27f * u))
    val tops = if (spires) listOf(spire(x - 11.5f * u, x - 3.5f * u, g - 42f * u, g - 60f * u), spire(x + 3.5f * u, x + 11.5f * u, g - 42f * u, g - 57f * u)) else emptyList()
    build(pencil, listOf(nave, front) + towers, Crayon.WHEAT)
    pencil.erase(roof)
    pencil.outline(roof, width = 0.8f, passes = 1)
    pencil.roofInk(Crayon.FAR).hatch(roof, roof.getBounds(), spacing = 1.8f, width = 0.5f)
    tops.forEach {
        pencil.erase(it)
        pencil.outline(it, width = 0.9f, passes = 1)
        pencil.roofInk(Crayon.FAR).hatch(it, it.getBounds(), spacing = 1.6f, width = 0.5f)
    }
    // La rose, les portails, les abat-son des tours, les arcs-boutants de la nef.
    pencil.outline(oval(Offset(x, g - 18f * u), 3.6f * u, 3.6f * u), width = 0.7f, passes = 1)
    listOf(-7f, 0f, 7f).forEach { dx ->
        val r = if (dx == 0f) 2.6f else 2f
        pencil.stroke(listOf(Offset(x + (dx - r) * u, g), Offset(x + (dx - r) * u, g - 5f * u), Offset(x + dx * u, g - (6f + r) * u), Offset(x + (dx + r) * u, g - 5f * u), Offset(x + (dx + r) * u, g)), width = 0.6f, passes = 1)
    }
    listOf(-7.5f, 7.5f).forEach { dx -> pencil.line(Offset(x + dx * u, g - 39f * u), Offset(x + dx * u, g - 31f * u), width = 0.6f, passes = 1) }
    listOf(17f, 23f, 29f).forEach { dx -> pencil.line(Offset(x + dx * u, g - 20f * u), Offset(x + (dx + 3f) * u, g - 12f * u), width = 0.5f, passes = 1) }
}

/** Une cathédrale à flèche centrale : la longue nef, deux tours basses en façade, la haute flèche à la croisée. */
private fun DrawScope.spiredCathedral(pencil: Pencil, ground: Offset) {
    val u = density
    val x = ground.x
    val g = ground.y
    val nave = rect(x - 20f * u, g - 16f * u, x + 22f * u, g)
    val roof = polygon(listOf(Offset(x - 20f * u, g - 16f * u), Offset(x - 18f * u, g - 22f * u), Offset(x + 20f * u, g - 22f * u), Offset(x + 22f * u, g - 16f * u)))
    val front = listOf(rect(x - 24f * u, g - 30f * u, x - 18f * u, g), rect(x - 17f * u, g - 30f * u, x - 11f * u, g))
    val base = rect(x - 4f * u, g - 32f * u, x + 4f * u, g - 20f * u)
    val tip = spire(x - 4.5f * u, x + 4.5f * u, g - 32f * u, g - 66f * u)
    build(pencil, listOf(nave) + front + base, Crayon.WHEAT)
    listOf(roof, tip).forEach {
        pencil.erase(it)
        pencil.outline(it, width = 0.9f, passes = 1)
        pencil.roofInk(Crayon.FAR).hatch(it, it.getBounds(), spacing = 1.6f, width = 0.5f)
    }
    pencil.line(Offset(x, g - 66f * u), Offset(x, g - 71f * u), width = 0.7f, passes = 1)
    pencil.outline(oval(Offset(x - 14f * u, g - 20f * u), 2.2f * u, 2.2f * u), width = 0.6f, passes = 1)
    var dx = -8f
    while (dx < 20f) {
        pencil.stroke(listOf(Offset(x + dx * u, g - 4f * u), Offset(x + dx * u, g - 10f * u), Offset(x + (dx + 1.2f) * u, g - 12f * u), Offset(x + (dx + 2.4f) * u, g - 10f * u), Offset(x + (dx + 2.4f) * u, g - 4f * u)), width = 0.5f, passes = 1)
        dx += 6f
    }
}

/** Un beffroi : la haute tour carrée de l'hôtel de ville, son horloge, son bulbe et sa flèche. */
private fun DrawScope.belfry(pencil: Pencil, ground: Offset, stone: androidx.compose.ui.graphics.Color, lit: Float) {
    val u = density
    val x = ground.x
    val g = ground.y
    val hall = rect(x - 17f * u, g - 12f * u, x + 17f * u, g)
    val hallRoof = polygon(listOf(Offset(x - 18f * u, g - 12f * u), Offset(x - 13f * u, g - 18f * u), Offset(x + 13f * u, g - 18f * u), Offset(x + 18f * u, g - 12f * u)))
    val shaft = rect(x - 5f * u, g - 40f * u, x + 5f * u, g - 12f * u)
    val stage = rect(x - 4f * u, g - 48f * u, x + 4f * u, g - 40f * u)
    val turrets = listOf(-5f, 5f).map { dx -> spire(x + (dx - 1.2f) * u, x + (dx + 1.2f) * u, g - 40f * u, g - 45f * u) }
    val bulb = oval(Offset(x, g - 51f * u), 3.2f * u, 3.4f * u)
    val tip = spire(x - 1.6f * u, x + 1.6f * u, g - 53.5f * u, g - 64f * u)
    build(pencil, listOf(hall, shaft, stage), stone)
    (listOf(hallRoof, bulb, tip) + turrets).forEach {
        pencil.erase(it)
        pencil.outline(it, width = 0.8f, passes = 1)
        pencil.roofInk(Crayon.FAR).hatch(it, it.getBounds(), spacing = 1.5f, width = 0.5f)
    }
    pencil.ink(Crayon.YELLOW).dot(Offset(x, g - 64.5f * u), 1f)
    val clock = oval(Offset(x, g - 34f * u), 2.6f * u, 2.6f * u)
    pencil.erase(clock)
    pencil.outline(clock, width = 0.7f, passes = 1)
    if (lit > 0f) pencil.ink(Crayon.YELLOW).hatch(clock, clock.getBounds(), spacing = 0.8f, width = 0.5f)
    pencil.line(Offset(x, g - 34f * u), Offset(x + 1.4f * u, g - 35f * u), width = 0.5f, passes = 1)
    listOf(-11f, -7f, 7f, 11f).forEach { dx -> pencil.outline(rect(x + (dx - 1f) * u, g - 9f * u, x + (dx + 1f) * u, g - 5f * u), width = 0.5f, passes = 1) }
}

/** Une basilique à dôme : la façade à colonnes et fronton, le tambour, la coupole et sa lanterne. */
private fun DrawScope.domed(pencil: Pencil, ground: Offset) {
    val u = density
    val x = ground.x
    val g = ground.y
    val front = rect(x - 17f * u, g - 14f * u, x + 17f * u, g)
    val pediment = polygon(listOf(Offset(x - 8f * u, g - 14f * u), Offset(x, g - 19f * u), Offset(x + 8f * u, g - 14f * u)))
    val drum = rect(x - 9f * u, g - 26f * u, x + 9f * u, g - 16f * u)
    val dome = Path().apply { addArc(Rect(x - 10f * u, g - 37f * u, x + 10f * u, g - 15f * u), 180f, 180f); close() }
    val lantern = rect(x - 1.8f * u, g - 42f * u, x + 1.8f * u, g - 36f * u)
    build(pencil, listOf(front, pediment, drum, lantern), Crayon.WHEAT)
    pencil.erase(dome)
    pencil.outline(dome, width = 1f)
    pencil.roofInk(Crayon.FAR).hatch(dome, dome.getBounds(), spacing = 1.8f, width = 0.5f)
    listOf(-6f, -2f, 2f, 6f).forEach { dx -> pencil.stroke((0..6).map { i -> val a = PI + PI * i / 6; Offset(x + (dx * u * sin(-a + PI)).toFloat(), g - 26f * u + (11f * u * sin(a)).toFloat() * (1 - abs(dx) / 12f)) }, width = 0.5f, passes = 1) }
    var dx = -15f
    while (dx <= 15f) {
        pencil.line(Offset(x + dx * u, g - 13f * u), Offset(x + dx * u, g - 1f * u), width = 0.5f, passes = 1)
        dx += 3.75f
    }
    pencil.line(Offset(x, g - 42f * u), Offset(x, g - 47f * u), width = 0.7f, passes = 1)
    pencil.line(Offset(x - 1.5f * u, g - 45.5f * u), Offset(x + 1.5f * u, g - 45.5f * u), width = 0.6f, passes = 1)
}

/** Une tour de télévision : le fût fin, la boule et l'antenne (allumée en rouge la nuit). */
private fun DrawScope.needle(pencil: Pencil, ground: Offset, lit: Float) {
    val u = density
    val x = ground.x
    val g = ground.y
    val shaft = polygon(listOf(Offset(x - 3f * u, g), Offset(x - 1.4f * u, g - 50f * u), Offset(x + 1.4f * u, g - 50f * u), Offset(x + 3f * u, g)))
    val ball = oval(Offset(x, g - 54f * u), 5.5f * u, 5.5f * u)
    val mast = rect(x - 0.8f * u, g - 76f * u, x + 0.8f * u, g - 59f * u)
    build(pencil, listOf(shaft, mast), Crayon.ROCK, spacing = 1.6f)
    pencil.erase(ball)
    pencil.outline(ball, width = 0.9f)
    pencil.ink(Crayon.FAR).hatch(ball, ball.getBounds(), spacing = 1.4f, width = 0.5f)
    pencil.line(Offset(x - 5.5f * u, g - 54f * u), Offset(x + 5.5f * u, g - 54f * u), width = 0.5f, passes = 1)
    if (lit > 0f) repeat(5) { k -> pencil.ink(Crayon.YELLOW).dot(Offset(x - 4f * u + k * 2f * u, g - 52.5f * u), 0.6f) }
    pencil.ink(Crayon.RED).dot(Offset(x, g - 77f * u), 1f)
}

/** Une cité fortifiée : la muraille crénelée, ses tours rondes coiffées en poivrière, le donjon au milieu. */
private fun DrawScope.ramparts(pencil: Pencil, ground: Offset) {
    val u = density
    val x = ground.x
    val g = ground.y
    val wall = rect(x - 30f * u, g - 10f * u, x + 30f * u, g)
    val merlons = (0..14).fold(Path()) { p, k -> p.union(rect(x - 30f * u + k * 4f * u, g - 12f * u, x - 28f * u + k * 4f * u, g - 10f * u)) }
    val keep = rect(x - 3f * u, g - 28f * u, x + 6f * u, g - 10f * u)
    val towers = listOf(-30f, -11f, 11f, 30f).map { dx -> rect(x + (dx - 3.5f) * u, g - 18f * u, x + (dx + 3.5f) * u, g) }
    build(pencil, listOf(keep) + listOf(wall) + towers, Crayon.ROCK, spacing = 2.2f)
    pencil.outline(merlons, width = 0.6f, passes = 1)
    (listOf(-30f, -11f, 11f, 30f).map { dx -> spire(x + (dx - 4.3f) * u, x + (dx + 4.3f) * u, g - 18f * u, g - 26f * u) } + spire(x - 3.8f * u, x + 6.8f * u, g - 28f * u, g - 36f * u)).forEach {
        pencil.erase(it)
        pencil.outline(it, width = 0.8f, passes = 1)
        pencil.roofInk(Crayon.FAR).hatch(it, it.getBounds(), spacing = 1.5f, width = 0.5f)
    }
    pencil.stroke(listOf(Offset(x - 2.5f * u, g), Offset(x - 2.5f * u, g - 4f * u), Offset(x, g - 6.5f * u), Offset(x + 2.5f * u, g - 4f * u), Offset(x + 2.5f * u, g)), width = 0.7f, passes = 1)
}

/** Le Mont-Saint-Michel : le rocher, les maisons accrochées, l'abbaye et sa flèche, l'archange doré tout en haut. */
private fun DrawScope.mount(pencil: Pencil, ground: Offset) {
    val u = density
    val x = ground.x
    val g = ground.y
    val rock = polygon(listOf(Offset(x - 32f * u, g), Offset(x - 18f * u, g - 12f * u), Offset(x - 7f * u, g - 26f * u), Offset(x + 8f * u, g - 27f * u), Offset(x + 20f * u, g - 14f * u), Offset(x + 32f * u, g)))
    pencil.erase(rock)
    pencil.outline(rock, width = 1f)
    pencil.ink(Crayon.ROCK).hatch(rock, rock.getBounds(), spacing = 2.8f, width = 0.5f)
    val nave = rect(x - 9f * u, g - 36f * u, x + 10f * u, g - 26f * u)
    val tower = rect(x - 2f * u, g - 46f * u, x + 3f * u, g - 36f * u)
    val tip = spire(x - 2.5f * u, x + 3.5f * u, g - 46f * u, g - 66f * u)
    build(pencil, listOf(nave, tower), Crayon.WHEAT, spacing = 2f)
    pencil.erase(tip)
    pencil.outline(tip, width = 0.9f, passes = 1)
    pencil.roofInk(Crayon.FAR).hatch(tip, tip.getBounds(), spacing = 1.4f, width = 0.5f)
    pencil.ink(Crayon.YELLOW).line(Offset(x + 0.5f * u, g - 66f * u), Offset(x + 0.5f * u, g - 71f * u), width = 1.4f, passes = 1)
    // Les maisons sur la pente, et les remparts au pied.
    listOf(-20f to -6f, -14f to -12f, 14f to -8f, 20f to -4f).forEach { (dx, dy) -> house(pencil, Offset(x + dx * u, g + dy * u), 7f * u, chimney = false) }
    pencil.stroke(listOf(Offset(x - 30f * u, g - 3f * u), Offset(x + 30f * u, g - 3f * u)), width = 0.8f, passes = 1)
}

/** Big Ben : la tour de l'horloge et sa flèche, le Parlement à côté. */
private fun DrawScope.clockTower(pencil: Pencil, ground: Offset, lit: Float) {
    val u = density
    val x = ground.x
    val g = ground.y
    val tower = rect(x - 4f * u, g - 46f * u, x + 4f * u, g)
    val belfry = rect(x - 3f * u, g - 52f * u, x + 3f * u, g - 46f * u)
    val parliament = rect(x + 4f * u, g - 16f * u, x + 30f * u, g)
    val victoria = rect(x + 25f * u, g - 36f * u, x + 32f * u, g)
    build(pencil, listOf(tower, belfry, parliament, victoria), Crayon.WHEAT, spacing = 2f)
    val tip = spire(x - 3.5f * u, x + 3.5f * u, g - 52f * u, g - 64f * u)
    pencil.erase(tip)
    pencil.outline(tip, width = 0.9f, passes = 1)
    pencil.roofInk(Crayon.FAR).hatch(tip, tip.getBounds(), spacing = 1.4f, width = 0.5f)
    val clock = oval(Offset(x, g - 41f * u), 2.8f * u, 2.8f * u)
    pencil.erase(clock)
    pencil.outline(clock, width = 0.7f, passes = 1)
    if (lit > 0f) pencil.ink(Crayon.YELLOW).hatch(clock, clock.getBounds(), spacing = 0.8f, width = 0.5f)
    pencil.line(Offset(x, g - 41f * u), Offset(x, g - 43f * u), width = 0.5f, passes = 1)
    var dx = 7f
    while (dx < 25f) {
        pencil.line(Offset(x + dx * u, g - 16f * u), Offset(x + dx * u, g - 19f * u), width = 0.6f, passes = 1)
        dx += 4.5f
    }
}

/**
 * Pise, la place des Miracles : le Duomo roman à gauche (sa façade à loggias, la nef rayée de marbre gris, la coupole
 * ovale), et devant lui la tour de marbre blanc, haute, qui penche nettement vers la droite : la base à arcades
 * aveugles, six étages de galeries, le clocher en haut. Le pré vert au pied.
 */
private fun DrawScope.leaningTower(pencil: Pencil, ground: Offset) {
    val u = density
    val x = ground.x
    val g = ground.y
    // Le pré de la place des Miracles.
    pencil.ink(Crayon.MEADOW).hatch(rect(x - 52f * u, g - 2f * u, x + 20f * u, g + 2f * u), Rect(x - 52f * u, g - 2f * u, x + 20f * u, g + 2f * u), spacing = 1.6f, width = 0.6f)
    // Le Duomo : la façade, la nef, le transept et sa coupole ovale.
    val d = x - 18f * u
    val front = polygon(listOf(Offset(d - 30f * u, g), Offset(d - 30f * u, g - 20f * u), Offset(d - 26f * u, g - 20f * u), Offset(d - 26f * u, g - 25f * u), Offset(d - 22f * u, g - 28f * u), Offset(d - 18f * u, g - 25f * u), Offset(d - 18f * u, g - 20f * u), Offset(d - 16f * u, g - 20f * u), Offset(d - 16f * u, g)))
    val nave = rect(d - 16f * u, g - 17f * u, d + 10f * u, g)
    val transept = rect(d - 5f * u, g - 20f * u, d + 3f * u, g)
    val drum = rect(d - 4.5f * u, g - 24f * u, d + 2.5f * u, g - 20f * u)
    val dome = Path().apply { addArc(Rect(d - 5f * u, g - 31f * u, d + 3f * u, g - 17f * u), 180f, 180f); close() }
    build(pencil, listOf(nave, transept, front, drum), Crayon.WHEAT, spacing = 3.4f)
    val roof = polygon(listOf(Offset(d - 16f * u, g - 17f * u), Offset(d - 15f * u, g - 21f * u), Offset(d - 5f * u, g - 21f * u), Offset(d - 5f * u, g - 17f * u)))
    listOf(roof, dome).forEach {
        pencil.erase(it)
        pencil.outline(it, width = 0.9f, passes = 1)
        pencil.roofInk(Crayon.FAR).hatch(it, it.getBounds(), spacing = 1.6f, width = 0.5f)
    }
    pencil.line(Offset(d - 1f * u, g - 31f * u), Offset(d - 1f * u, g - 34f * u), width = 0.6f, passes = 1)
    // Les bandes de marbre gris de la nef, les loggias de la façade, les trois portails.
    val grey = pencil.ink(Crayon.FAR)
    listOf(4f, 8f, 12f).forEach { dy -> grey.line(Offset(d - 16f * u, g - dy * u), Offset(d + 10f * u, g - dy * u), width = 0.5f, passes = 1) }
    listOf(20f, 23f).forEach { dy ->
        var dx = if (dy < 22f) -29f else -25f
        while (dx <= (if (dy < 22f) -17f else -19f)) {
            pencil.line(Offset(d + dx * u, g - dy * u), Offset(d + dx * u, g - (dy - 2.5f) * u), width = 0.4f, passes = 1)
            dx += 2f
        }
    }
    listOf(-27f, -23f, -19f).forEach { dx -> pencil.stroke(listOf(Offset(d + dx * u, g), Offset(d + dx * u, g - 5f * u), Offset(d + (dx + 0.8f) * u, g - 6f * u), Offset(d + (dx + 1.6f) * u, g - 5f * u), Offset(d + (dx + 1.6f) * u, g)), width = 0.5f, passes = 1) }
    // La tour, devant, qui penche.
    val t = x + 6f * u
    rotate(degrees = 7f, pivot = Offset(t, g)) {
        val shaft = rect(t - 7.5f * u, g - 56f * u, t + 7.5f * u, g)
        val belfry = rect(t - 5.5f * u, g - 65f * u, t + 5.5f * u, g - 56f * u)
        pencil.erase(Path().apply { addPath(shaft); addPath(belfry) })
        // Le marbre blanc, à peine crème ; l'ombre du cylindre sur le côté droit.
        pencil.ink(Crayon.WHEAT).hatch(shaft, shaft.getBounds(), spacing = 5f, width = 0.4f)
        val shade = rect(t + 4f * u, g - 56f * u, t + 7.5f * u, g)
        pencil.ink(Crayon.FAR).hatch(shade, shade.getBounds(), spacing = 1.8f, width = 0.5f)
        val shadeTop = rect(t + 3f * u, g - 65f * u, t + 5.5f * u, g - 56f * u)
        pencil.ink(Crayon.FAR).hatch(shadeTop, shadeTop.getBounds(), spacing = 1.8f, width = 0.5f)
        pencil.outline(shaft, width = 1.1f)
        pencil.outline(belfry, width = 1f)
        // Les corniches qui débordent entre les étages.
        val floors = listOf(0f, 13f, 20.2f, 27.4f, 34.6f, 41.8f, 49f, 56f)
        floors.drop(1).forEach { h -> pencil.line(Offset(t - 8.5f * u, g - h * u), Offset(t + 8.5f * u, g - h * u), width = 0.8f, passes = 1) }
        // La base à grandes arcades aveugles, puis les galeries : des colonnettes et leurs petits arcs.
        fun arcades(bottom: Float, top: Float, columns: List<Float>) {
            columns.zipWithNext().forEach { (a, b) ->
                val m = (a + b) / 2
                val r = (b - a) / 2
                pencil.stroke(listOf(Offset(t + a * u, g - bottom * u), Offset(t + a * u, g - (top - r) * u), Offset(t + (m - r * 0.7f) * u, g - (top - r * 0.3f) * u), Offset(t + m * u, g - top * u), Offset(t + (m + r * 0.7f) * u, g - (top - r * 0.3f) * u), Offset(t + b * u, g - (top - r) * u), Offset(t + b * u, g - bottom * u)), width = 0.6f, passes = 1)
            }
        }
        arcades(1f, 11f, listOf(-6.5f, -2.2f, 2.2f, 6.5f))
        floors.subList(1, floors.size - 1).zip(floors.subList(2, floors.size)).forEach { (bottom, top) ->
            arcades(bottom + 0.8f, top - 0.8f, listOf(-6.5f, -3.25f, 0f, 3.25f, 6.5f))
        }
        arcades(57f, 63.5f, listOf(-4.5f, -1.5f, 1.5f, 4.5f))
        pencil.line(Offset(t - 6.2f * u, g - 65f * u), Offset(t + 6.2f * u, g - 65f * u), width = 0.9f, passes = 1)
    }
}

/** La Sagrada Família : une forêt de flèches arrondies, et la grue du chantier. */
private fun DrawScope.sagrada(pencil: Pencil, ground: Offset) {
    val u = density
    val x = ground.x
    val g = ground.y
    val base = rect(x - 20f * u, g - 16f * u, x + 20f * u, g)
    val towers = listOf(-16f to 44f, -10f to 52f, -4f to 60f, 4f to 66f, 10f to 54f, 16f to 46f).map { (dx, height) ->
        Path().apply {
            moveTo(x + (dx - 2.6f) * u, g - 14f * u)
            lineTo(x + (dx - 2.2f) * u, g - (height - 10f) * u)
            quadraticTo(x + dx * u, g - (height + 2f) * u, x + (dx + 2.2f) * u, g - (height - 10f) * u)
            lineTo(x + (dx + 2.6f) * u, g - 14f * u)
            close()
        }
    }
    build(pencil, listOf(base) + towers, Crayon.WHEAT, spacing = 1.8f)
    listOf(-16f to 44f, -10f to 52f, -4f to 60f, 4f to 66f, 10f to 54f, 16f to 46f).forEachIndexed { k, (dx, height) ->
        pencil.ink(if (k % 2 == 0) Crayon.RED else Crayon.YELLOW).dot(Offset(x + dx * u, g - (height + 1f) * u), 1.1f)
    }
    val crane = pencil.ink(Crayon.YELLOW)
    crane.line(Offset(x + 26f * u, g), Offset(x + 26f * u, g - 70f * u), width = 1f, passes = 1)
    crane.line(Offset(x + 14f * u, g - 68f * u), Offset(x + 40f * u, g - 68f * u), width = 0.9f, passes = 1)
}

/** L'Atomium : neuf boules d'acier reliées par des tubes, posées sur trois pieds. */
private fun DrawScope.atomium(pencil: Pencil, ground: Offset) {
    val u = density
    val c = ground + Offset(0f, -34f * u)
    val d = 12f * u
    val corners = listOf(Offset(0f, -1.6f), Offset(-1.2f, -0.7f), Offset(1.2f, -0.7f), Offset(0f, -0.2f), Offset(-1.2f, 0.7f), Offset(1.2f, 0.7f), Offset(0f, 1.2f), Offset(0f, 0.4f)).map { c + it * d } + c
    val links = listOf(0 to 1, 0 to 2, 1 to 4, 2 to 5, 4 to 6, 5 to 6, 0 to 8, 8 to 6, 1 to 8, 2 to 8, 4 to 8, 5 to 8, 3 to 8, 7 to 8)
    listOf(-1f, 1f, 0f).forEach { side -> pencil.line(corners[6], ground + Offset(side * 10f * u, 0f), width = 0.8f, passes = 1) }
    links.forEach { (a, b) -> pencil.line(corners[a], corners[b], width = 0.9f, passes = 1, wobble = 0.1f) }
    corners.forEach { p ->
        val ball = oval(p, 4f * u, 4f * u)
        pencil.erase(ball)
        pencil.outline(ball, width = 0.8f, passes = 1)
        pencil.ink(Crayon.FAR).hatch(ball, Rect(p.x, p.y - 4f * u, p.x + 4f * u, p.y + 4f * u), spacing = 1.2f, width = 0.5f)
    }
}

/** Saint-Sernin de Toulouse : la basilique de brique et son clocher octogonal à étages, coiffé d'une flèche. */
private fun DrawScope.octagon(pencil: Pencil, ground: Offset) {
    val u = density
    val x = ground.x
    val g = ground.y
    val nave = rect(x - 26f * u, g - 15f * u, x + 24f * u, g)
    val tiers = (0 until 5).map { k -> rect(x - (7f - k * 1f) * u, g - (23f + k * 8f) * u, x + (7f - k * 1f) * u, g - (15f + k * 8f) * u) }
    build(pencil, listOf(nave) + tiers, Crayon.ROOF, spacing = 2f)
    tiers.forEachIndexed { k, _ ->
        val y = g - (19f + k * 8f) * u
        var dx = -(5.5f - k)
        while (dx <= 5.5f - k) {
            pencil.stroke(listOf(Offset(x + (dx - 0.8f) * u, y + 2f * u), Offset(x + (dx - 0.8f) * u, y - 1f * u), Offset(x + dx * u, y - 2.2f * u), Offset(x + (dx + 0.8f) * u, y - 1f * u), Offset(x + (dx + 0.8f) * u, y + 2f * u)), width = 0.4f, passes = 1)
            dx += 2.6f
        }
    }
    val tip = spire(x - 2.5f * u, x + 2.5f * u, g - 55f * u, g - 68f * u)
    pencil.erase(tip)
    pencil.outline(tip, width = 0.8f, passes = 1)
    pencil.roofInk(Crayon.ROOF).hatch(tip, tip.getBounds(), spacing = 1.4f, width = 0.5f)
}

// --- Les lieux de culte et les monuments du monde ---

/**
 * Le lieu de culte du village ou de la ville, comme on le bâtit dans la région ([Pencil.culture]) : église (clocher
 * comtois en montagne en Europe, [comtois]), église orthodoxe à bulbes, église coloniale, mosquée, temple hindou,
 * stupa, pagode.
 */
private fun DrawScope.worship(pencil: Pencil, ground: Offset, scale: Float, comtois: Boolean = false) {
    when (pencil.culture) {
        Culture.EUROPE -> when {
            pencil.local == LocalStyle.COMTOIS -> church(pencil, ground)
            pencil.local == LocalStyle.SWISS_JURA -> temple(pencil, ground, scale)
            pencil.local == LocalStyle.PROVENCE -> provencalChurch(pencil, ground, scale)
            comtois -> church(pencil, ground)
            else -> spireChurch(pencil, ground, scale)
        }
        Culture.NEW_WORLD -> spireChurch(pencil, ground, scale)
        Culture.ORTHODOX -> onionChurch(pencil, ground, scale)
        Culture.LATIN -> colonialChurch(pencil, ground, scale)
        Culture.ISLAMIC -> mosque(pencil, ground, scale)
        Culture.SOUTH_ASIA -> hinduTemple(pencil, ground, scale)
        Culture.BUDDHIST -> stupa(pencil, ground, scale)
        Culture.EAST_ASIA -> pagoda(pencil, ground, scale)
    }
}

/** Un bulbe (dôme en oignon) de demi-largeur [r], posé sur [base], qui monte jusqu'à [base] - 2,6 [r]. */
private fun onion(c: Offset, r: Float) = Path().apply {
    moveTo(c.x - r * 0.7f, c.y)
    cubicTo(c.x - r * 1.5f, c.y - r * 0.9f, c.x - r * 0.4f, c.y - r * 1.6f, c.x, c.y - r * 2.6f)
    cubicTo(c.x + r * 0.4f, c.y - r * 1.6f, c.x + r * 1.5f, c.y - r * 0.9f, c.x + r * 0.7f, c.y)
    close()
}

/** Un bulbe peint, sa croix au-dessus. */
private fun DrawScope.onionDome(pencil: Pencil, base: Offset, r: Float, color: androidx.compose.ui.graphics.Color, cross: Boolean = true) {
    val u = density
    val dome = onion(base, r)
    pencil.erase(dome)
    pencil.outline(dome, width = 0.8f, passes = 1)
    pencil.roofInk(color).hatch(dome, dome.getBounds(), spacing = 1.2f, width = 0.6f)
    if (cross) {
        val top = base + Offset(0f, -r * 2.6f)
        pencil.line(top, top + Offset(0f, -4f * u), width = 0.6f, passes = 1)
        pencil.line(top + Offset(-1.5f * u, -2.6f * u), top + Offset(1.5f * u, -2.6f * u), width = 0.5f, passes = 1)
    }
}

/** Église orthodoxe : la nef blanche, le tambour et son grand bulbe doré, deux petits bulbes bleus. */
private fun DrawScope.onionChurch(pencil: Pencil, ground: Offset, scale: Float) {
    val u = density * scale
    val x = ground.x
    val g = ground.y
    val nave = rect(x - 12f * u, g - 12f * u, x + 12f * u, g)
    val drum = rect(x - 4f * u, g - 21f * u, x + 4f * u, g - 12f * u)
    val bell = rect(x + 12f * u, g - 24f * u, x + 17f * u, g)
    build(pencil, listOf(nave, drum, bell), Crayon.WHEAT, spacing = 3f)
    onionDome(pencil, Offset(x, g - 21f * u), 5f * u, Crayon.YELLOW)
    onionDome(pencil, Offset(x + 14.5f * u, g - 24f * u), 2.8f * u, Crayon.BLUE)
    onionDome(pencil, Offset(x - 8f * u, g - 12f * u), 2.6f * u, Crayon.BLUE)
    pencil.stroke(listOf(Offset(x - 2f * u, g), Offset(x - 2f * u, g - 5f * u), Offset(x, g - 7f * u), Offset(x + 2f * u, g - 5f * u), Offset(x + 2f * u, g)), width = 0.6f, passes = 1)
}

/** Église coloniale : la façade blanche au fronton chantourné, deux clochers à coupole. */
private fun DrawScope.colonialChurch(pencil: Pencil, ground: Offset, scale: Float) {
    val u = density * scale
    val x = ground.x
    val g = ground.y
    val front = Path().apply {
        moveTo(x - 8f * u, g)
        lineTo(x - 8f * u, g - 16f * u)
        quadraticTo(x - 4f * u, g - 17f * u, x - 3f * u, g - 20f * u)
        quadraticTo(x, g - 24f * u, x + 3f * u, g - 20f * u)
        quadraticTo(x + 4f * u, g - 17f * u, x + 8f * u, g - 16f * u)
        lineTo(x + 8f * u, g)
        close()
    }
    val towers = listOf(-11f, 11f).map { dx -> rect(x + (dx - 3.5f) * u, g - 27f * u, x + (dx + 3.5f) * u, g) }
    build(pencil, listOf(front) + towers, Crayon.WHEAT, spacing = 3.4f)
    listOf(-11f, 11f).forEach { dx ->
        val dome = Path().apply { addArc(Rect(x + (dx - 3.5f) * u, g - 31f * u, x + (dx + 3.5f) * u, g - 23f * u), 180f, 180f); close() }
        pencil.erase(dome)
        pencil.outline(dome, width = 0.8f, passes = 1)
        pencil.roofInk(Crayon.ROOF).hatch(dome, dome.getBounds(), spacing = 1.2f, width = 0.5f)
        pencil.line(Offset(x + dx * u, g - 31f * u), Offset(x + dx * u, g - 35f * u), width = 0.6f, passes = 1)
        pencil.line(Offset(x + (dx - 1.3f) * u, g - 33.5f * u), Offset(x + (dx + 1.3f) * u, g - 33.5f * u), width = 0.5f, passes = 1)
        pencil.stroke(listOf(Offset(x + (dx - 1.5f) * u, g - 18f * u), Offset(x + (dx - 1.5f) * u, g - 21f * u), Offset(x + dx * u, g - 22.5f * u), Offset(x + (dx + 1.5f) * u, g - 21f * u), Offset(x + (dx + 1.5f) * u, g - 18f * u)), width = 0.5f, passes = 1)
    }
    pencil.stroke(listOf(Offset(x - 2.5f * u, g), Offset(x - 2.5f * u, g - 6f * u), Offset(x, g - 8.5f * u), Offset(x + 2.5f * u, g - 6f * u), Offset(x + 2.5f * u, g)), width = 0.6f, passes = 1)
}

/** Mosquée : la salle de prière, sa coupole et son croissant, le minaret élancé à côté. */
private fun DrawScope.mosque(pencil: Pencil, ground: Offset, scale: Float) {
    val u = density * scale
    val x = ground.x
    val g = ground.y
    val hall = rect(x - 12f * u, g - 10f * u, x + 10f * u, g)
    val drum = rect(x - 6f * u, g - 13f * u, x + 4f * u, g - 10f * u)
    val minaret = rect(x + 13f * u, g - 32f * u, x + 16f * u, g)
    build(pencil, listOf(hall, drum, minaret), Crayon.WHEAT, spacing = 3f)
    val dome = Path().apply { addArc(Rect(x - 7.5f * u, g - 24f * u, x + 5.5f * u, g - 6f * u), 180f, 180f); close() }
    pencil.erase(dome)
    pencil.outline(dome, width = 0.9f)
    pencil.roofInk(Crayon.DOME).hatch(dome, dome.getBounds(), spacing = 1.4f, width = 0.6f)
    // Le croissant sur la coupole, le balcon et la pointe du minaret.
    val gold = pencil.ink(Crayon.YELLOW)
    gold.line(Offset(x - 1f * u, g - 24f * u), Offset(x - 1f * u, g - 26.5f * u), width = 0.7f, passes = 1)
    gold.stroke((0..6).map { i -> val a = PI * 0.25 + PI * 1.5 * i / 6; Offset(x - 1f * u + (1.6f * u * cos(a)).toFloat(), g - 28f * u + (1.6f * u * sin(a)).toFloat()) }, width = 0.7f, passes = 1)
    pencil.line(Offset(x + 12f * u, g - 25f * u), Offset(x + 17f * u, g - 25f * u), width = 0.8f, passes = 1)
    val cap = spire(x + 12.6f * u, x + 16.4f * u, g - 32f * u, g - 38f * u)
    pencil.erase(cap)
    pencil.outline(cap, width = 0.7f, passes = 1)
    pencil.roofInk(Crayon.DOME).hatch(cap, cap.getBounds(), spacing = 1f, width = 0.5f)
    listOf(-8f, -3f, 3f, 7f).forEach { dx -> pencil.stroke(listOf(Offset(x + (dx - 1.2f) * u, g - 2f * u), Offset(x + (dx - 1.2f) * u, g - 5.5f * u), Offset(x + dx * u, g - 7f * u), Offset(x + (dx + 1.2f) * u, g - 5.5f * u), Offset(x + (dx + 1.2f) * u, g - 2f * u)), width = 0.5f, passes = 1) }
}

/** Temple hindou : la tour en ruche (shikhara) et ses côtes, le disque et le pot au sommet, le porche, le fanion. */
private fun DrawScope.hinduTemple(pencil: Pencil, ground: Offset, scale: Float) {
    val u = density * scale
    val x = ground.x
    val g = ground.y
    val porch = rect(x - 16f * u, g - 9f * u, x - 5f * u, g)
    val porchRoof = spire(x - 17f * u, x - 4f * u, g - 9f * u, g - 15f * u)
    val tower = Path().apply {
        moveTo(x - 7f * u, g)
        lineTo(x - 7f * u, g - 10f * u)
        cubicTo(x - 7.5f * u, g - 22f * u, x - 3f * u, g - 30f * u, x, g - 32f * u)
        cubicTo(x + 3f * u, g - 30f * u, x + 7.5f * u, g - 22f * u, x + 7f * u, g - 10f * u)
        lineTo(x + 7f * u, g)
        close()
    }
    build(pencil, listOf(porch, porchRoof, tower), Crayon.ORANGE, spacing = 2.4f)
    listOf(-3.5f, 0f, 3.5f).forEach { dx -> pencil.stroke((0..6).map { i -> val f = i / 6f; Offset(x + dx * u * (1 - f * 0.8f), g - (10f + 21f * f) * u) }, width = 0.5f, passes = 1) }
    val disc = oval(Offset(x, g - 33f * u), 3f * u, 1.2f * u)
    pencil.erase(disc)
    pencil.outline(disc, width = 0.7f, passes = 1)
    pencil.ink(Crayon.YELLOW).dot(Offset(x, g - 35.5f * u), 1.3f)
    pencil.line(Offset(x, g - 36f * u), Offset(x, g - 42f * u), width = 0.6f, passes = 1)
    val flag = polygon(listOf(Offset(x, g - 42f * u), Offset(x + 5f * u, g - 40.5f * u), Offset(x, g - 39f * u)))
    pencil.ink(Crayon.ORANGE).hatch(flag, flag.getBounds(), spacing = 0.9f, width = 0.5f)
}

/** Stupa : la cloche blanche sur ses gradins, la flèche dorée à anneaux ; à côté, le toit étagé du temple. */
private fun DrawScope.stupa(pencil: Pencil, ground: Offset, scale: Float) {
    val u = density * scale
    val x = ground.x
    val g = ground.y
    val steps = listOf(rect(x - 10f * u, g - 3f * u, x + 10f * u, g), rect(x - 8f * u, g - 6f * u, x + 8f * u, g - 3f * u))
    val bell = Path().apply {
        moveTo(x - 7f * u, g - 6f * u)
        cubicTo(x - 7f * u, g - 15f * u, x - 3f * u, g - 17f * u, x - 2f * u, g - 19f * u)
        lineTo(x + 2f * u, g - 19f * u)
        cubicTo(x + 3f * u, g - 17f * u, x + 7f * u, g - 15f * u, x + 7f * u, g - 6f * u)
        close()
    }
    val tip = spire(x - 2f * u, x + 2f * u, g - 19f * u, g - 38f * u)
    build(pencil, steps + bell, Crayon.WHEAT, spacing = 3.2f)
    pencil.erase(tip)
    pencil.outline(tip, width = 0.8f, passes = 1)
    pencil.ink(Crayon.YELLOW).hatch(tip, tip.getBounds(), spacing = 1f, width = 0.6f)
    for (k in 1..4) pencil.line(Offset(x - (2f - k * 0.35f) * u, g - (19f + k * 3.2f) * u), Offset(x + (2f - k * 0.35f) * u, g - (19f + k * 3.2f) * u), width = 0.5f, passes = 1)
    // Le temple voisin, ses deux toits rouges l'un sur l'autre et leurs pointes recourbées.
    val hall = rect(x + 12f * u, g - 8f * u, x + 26f * u, g)
    val roofs = listOf(0f to 0f, 3f to 5f).map { (inset, lift) ->
        Path().apply {
            moveTo(x + (10f + inset) * u, g - (8f + lift) * u)
            lineTo(x + (16f + inset * 0.5f) * u, g - (15f + lift) * u)
            lineTo(x + (22f - inset * 0.5f) * u, g - (15f + lift) * u)
            lineTo(x + (28f - inset) * u, g - (8f + lift) * u)
            close()
        }
    }
    build(pencil, listOf(hall), Crayon.WHEAT, spacing = 3f)
    roofs.forEach {
        pencil.erase(it)
        pencil.outline(it, width = 0.8f, passes = 1)
        pencil.roofInk(Crayon.RED).hatch(it, it.getBounds(), spacing = 1.4f, width = 0.5f)
    }
    pencil.ink(Crayon.YELLOW).line(Offset(x + 28f * u, g - 8f * u), Offset(x + 29.5f * u, g - 10.5f * u), width = 0.8f, passes = 1)
}

/** Pagode : cinq étages de plus en plus petits, rouges, sous des toits sombres aux coins relevés, et la flèche. */
private fun DrawScope.pagoda(pencil: Pencil, ground: Offset, scale: Float) {
    val u = density * scale
    val x = ground.x
    var g = ground.y
    for (k in 0 until 5) {
        val half = (8f - k * 1.2f) * u
        val body = rect(x - half * 0.7f, g - 5f * u, x + half * 0.7f, g)
        pencil.erase(body)
        pencil.outline(body, width = 0.7f, passes = 1)
        pencil.ink(Crayon.RED).hatch(body, body.getBounds(), spacing = 1.4f, width = 0.5f)
        val top = g - 5f * u
        val eave = Path().apply {
            moveTo(x - half - 2.5f * u, top - 2.5f * u)
            quadraticTo(x - half * 0.6f, top + 0.6f * u, x - half * 0.3f, top - 3f * u)
            lineTo(x + half * 0.3f, top - 3f * u)
            quadraticTo(x + half * 0.6f, top + 0.6f * u, x + half + 2.5f * u, top - 2.5f * u)
            lineTo(x + half, top)
            lineTo(x - half, top)
            close()
        }
        pencil.erase(eave)
        pencil.outline(eave, width = 0.8f, passes = 1)
        pencil.roofInk(Crayon.FAR).hatch(eave, eave.getBounds(), spacing = 1.2f, width = 0.5f)
        g = top - 3f * u
    }
    pencil.line(Offset(x, g), Offset(x, g - 9f * u), width = 0.8f, passes = 1)
    for (k in 1..3) pencil.line(Offset(x - 1.4f * u, g - k * 2.2f * u), Offset(x + 1.4f * u, g - k * 2.2f * u), width = 0.5f, passes = 1)
}

/** Burj Khalifa : la plus haute tour du monde, en gradins de plus en plus fins jusqu'à l'aiguille. */
private fun DrawScope.burj(pencil: Pencil, ground: Offset, lit: Float) {
    val u = density
    val x = ground.x
    val g = ground.y
    val steps = listOf(9f to 0f, 7f to 0.28f, 5.5f to 0.48f, 4f to 0.64f, 2.6f to 0.78f, 1.4f to 0.88f)
    val height = 90f * u
    val shape = steps.foldIndexed(Path()) { k, path, (half, from) ->
        val to = steps.getOrNull(k + 1)?.second ?: 0.95f
        path.union(rect(x - half * u, g - height * to, x + half * u, g - height * from))
    }.union(spire(x - 0.7f * u, x + 0.7f * u, g - height * 0.95f, g - height * 1.08f))
    pencil.erase(shape)
    pencil.outline(shape, width = 0.9f)
    pencil.ink(Crayon.FAR).hatch(shape, Rect(x, g - height, x + 9f * u, g), spacing = 1.8f, width = 0.5f)
    if (lit > 0f) windows(pencil, Rect(x - 6f * u, g - height * 0.6f, x + 6f * u, g - 4f * u), 10, 2, lit, Random(11))
    pencil.ink(Crayon.RED).dot(Offset(x, g - height * 1.09f), 1f)
}

/** Une grande mosquée impériale : la coupole centrale et ses demi-coupoles en cascade, quatre minarets en crayon. */
private fun DrawScope.greatMosque(pencil: Pencil, ground: Offset) {
    val u = density
    val x = ground.x
    val g = ground.y
    val base = rect(x - 18f * u, g - 12f * u, x + 18f * u, g)
    val minarets = listOf(-24f, -20f, 20f, 24f).map { dx -> rect(x + (dx - 1.4f) * u, g - 40f * u, x + (dx + 1.4f) * u, g) }
    build(pencil, listOf(base) + minarets, Crayon.WHEAT, spacing = 3f)
    listOf(-24f, -20f, 20f, 24f).forEach { dx ->
        val cap = spire(x + (dx - 1.6f) * u, x + (dx + 1.6f) * u, g - 40f * u, g - 47f * u)
        pencil.erase(cap)
        pencil.outline(cap, width = 0.6f, passes = 1)
        pencil.roofInk(Crayon.FAR).hatch(cap, cap.getBounds(), spacing = 1f, width = 0.5f)
        listOf(24f, 32f).forEach { h -> pencil.line(Offset(x + (dx - 2.2f) * u, g - h * u), Offset(x + (dx + 2.2f) * u, g - h * u), width = 0.5f, passes = 1) }
    }
    listOf(Triple(-11f, 12f, 6f), Triple(11f, 12f, 6f), Triple(0f, 14f, 13f)).forEach { (dx, bottom, r) ->
        val dome = Path().apply { addArc(Rect(x + dx * u - r * u, g - (bottom + r) * u, x + dx * u + r * u, g - (bottom - r) * u), 180f, 180f); close() }
        pencil.erase(dome)
        pencil.outline(dome, width = 0.9f)
        pencil.roofInk(Crayon.FAR).hatch(dome, dome.getBounds(), spacing = 1.6f, width = 0.5f)
    }
    pencil.ink(Crayon.YELLOW).line(Offset(x, g - 27f * u), Offset(x, g - 31f * u), width = 0.8f, passes = 1)
}

/** Saint-Basile de Moscou : un bouquet de bulbes rayés et colorés sur leurs tours, autour d'une flèche. */
private fun DrawScope.stBasil(pencil: Pencil, ground: Offset) {
    val u = density
    val x = ground.x
    val g = ground.y
    val base = rect(x - 18f * u, g - 10f * u, x + 18f * u, g)
    val center = rect(x - 3f * u, g - 34f * u, x + 3f * u, g - 10f * u)
    val tip = spire(x - 3.5f * u, x + 3.5f * u, g - 34f * u, g - 50f * u)
    val towers = listOf(-13f to 22f, -7f to 26f, 7f to 25f, 13f to 20f)
    build(pencil, listOf(base, center) + towers.map { (dx, h) -> rect(x + (dx - 2.5f) * u, g - h * u, x + (dx + 2.5f) * u, g - 10f * u) }, Crayon.ROOF, spacing = 2.2f)
    pencil.erase(tip)
    pencil.outline(tip, width = 0.8f, passes = 1)
    pencil.roofInk(Crayon.GREEN).hatch(tip, tip.getBounds(), spacing = 1.2f, width = 0.5f)
    val colors = listOf(Crayon.BLUE, Crayon.GREEN, Crayon.YELLOW, Crayon.RED)
    towers.forEachIndexed { k, (dx, h) -> onionDome(pencil, Offset(x + dx * u, g - h * u), 3.4f * u, colors[k], cross = false) }
    onionDome(pencil, Offset(x, g - 50f * u), 1.8f * u, Crayon.YELLOW)
}

/** Le Taj Mahal : la terrasse, le grand bulbe blanc sur son tambour, les petits kiosques et les quatre minarets. */
private fun DrawScope.taj(pencil: Pencil, ground: Offset) {
    val u = density
    val x = ground.x
    val g = ground.y
    val terrace = rect(x - 26f * u, g - 3f * u, x + 26f * u, g)
    val hall = rect(x - 13f * u, g - 17f * u, x + 13f * u, g - 3f * u)
    val drum = rect(x - 6f * u, g - 21f * u, x + 6f * u, g - 17f * u)
    val minarets = listOf(-23f, 23f).map { dx -> rect(x + (dx - 1.4f) * u, g - 30f * u, x + (dx + 1.4f) * u, g - 3f * u) }
    build(pencil, listOf(terrace, hall, drum) + minarets, Crayon.SNOW, spacing = 3.6f)
    val dome = onion(Offset(x, g - 21f * u), 8.5f * u)
    pencil.erase(dome)
    pencil.outline(dome, width = 1f)
    pencil.ink(Crayon.SNOW).hatch(dome, dome.getBounds(), spacing = 3f, width = 0.5f)
    pencil.ink(Crayon.YELLOW).line(Offset(x, g - 43f * u), Offset(x, g - 47f * u), width = 0.8f, passes = 1)
    listOf(-9f, 9f).forEach { dx -> onionDome(pencil, Offset(x + dx * u, g - 17f * u), 2.2f * u, Crayon.SNOW, cross = false) }
    listOf(-23f, 23f).forEach { dx -> onionDome(pencil, Offset(x + dx * u, g - 30f * u), 1.6f * u, Crayon.SNOW, cross = false) }
    pencil.stroke(listOf(Offset(x - 4f * u, g - 3f * u), Offset(x - 4f * u, g - 11f * u), Offset(x, g - 15f * u), Offset(x + 4f * u, g - 11f * u), Offset(x + 4f * u, g - 3f * u)), width = 0.7f, passes = 1)
}

/** Les pyramides de Gizeh : trois triangles de pierre ensoleillés d'un côté, à l'ombre de l'autre. */
private fun DrawScope.pyramids(pencil: Pencil, ground: Offset) {
    val u = density
    listOf(Triple(-22f, 15f, 20f), Triple(4f, 20f, 30f), Triple(30f, 11f, 15f)).forEach { (dx, half, height) ->
        val apex = ground + Offset(dx * u, -height * u)
        val sunny = polygon(listOf(ground + Offset((dx - half) * u, 0f), apex, ground + Offset((dx + half * 0.2f) * u, 0f)))
        val shade = polygon(listOf(ground + Offset((dx + half * 0.2f) * u, 0f), apex, ground + Offset((dx + half) * u, 0f)))
        pencil.erase(Path().apply { addPath(sunny) }.union(shade))
        pencil.outline(sunny, width = 0.9f, passes = 1)
        pencil.outline(shade, width = 0.9f, passes = 1)
        pencil.ink(Crayon.WHEAT).hatch(sunny, sunny.getBounds(), spacing = 3f, width = 0.5f)
        pencil.ink(Crayon.EARTH).hatch(shade, shade.getBounds(), spacing = 1.6f, width = 0.5f)
    }
}

/** L'Opéra de Sydney : ses voiles blanches en coquilles, posées sur le socle au bord de l'eau. */
private fun DrawScope.opera(pencil: Pencil, ground: Offset) {
    val u = density
    val base = rect(ground.x - 26f * u, ground.y - 5f * u, ground.x + 26f * u, ground.y)
    build(pencil, listOf(base), Crayon.EARTH, spacing = 2f)
    listOf(Triple(-18f, 10f, 14f), Triple(-6f, 12f, 22f), Triple(7f, 11f, 18f), Triple(17f, 8f, 12f)).forEach { (dx, half, height) ->
        val shell = Path().apply {
            moveTo(ground.x + (dx - half) * u, ground.y - 5f * u)
            quadraticTo(ground.x + (dx - half * 0.2f) * u, ground.y - (5f + height * 1.1f) * u, ground.x + (dx + half) * u, ground.y - (5f + height) * u)
            quadraticTo(ground.x + (dx + half * 0.4f) * u, ground.y - (5f + height * 0.4f) * u, ground.x + (dx + half * 0.5f) * u, ground.y - 5f * u)
            close()
        }
        pencil.erase(shell)
        pencil.outline(shell, width = 0.9f)
        pencil.ink(Crayon.SNOW).hatch(shell, shell.getBounds(), spacing = 2.6f, width = 0.5f)
    }
}

/** Le Christ rédempteur : la statue aux bras ouverts sur son pain de sucre boisé. */
private fun DrawScope.statueOnHill(pencil: Pencil, ground: Offset) {
    val u = density
    val hill = polygon(listOf(ground + Offset(-30f * u, 0f), ground + Offset(-8f * u, -28f * u), ground + Offset(2f * u, -31f * u), ground + Offset(10f * u, -27f * u), ground + Offset(30f * u, 0f)))
    pencil.erase(hill)
    pencil.outline(hill, width = 1f)
    pencil.ink(Crayon.FOREST).hatch(hill, hill.getBounds(), spacing = 2.4f, width = 0.5f)
    val foot = ground + Offset(0f, -31f * u)
    pencil.line(foot, foot + Offset(0f, -14f * u), width = 1.6f, passes = 2)
    pencil.line(foot + Offset(-7f * u, -11f * u), foot + Offset(7f * u, -11f * u), width = 1.2f, passes = 2)
    pencil.dot(foot + Offset(0f, -15.5f * u), 1.4f)
}

// --- Le désert ---

/** Des dunes au premier plan : de longues courbes de sable, ombrées du côté opposé au vent. */
private fun DrawScope.dunes(pencil: Pencil, t: SketchTerrain, w: Float, h: Float) {
    val u = density
    val sand = pencil.ink(Crayon.WHEAT)
    val shade = pencil.ink(Crayon.EARTH)
    listOf(Triple(0.10f, 0.30f, 0.955f), Triple(0.42f, 0.62f, 0.975f), Triple(0.70f, 0.95f, 0.96f)).forEach { (x0, x1, base) ->
        if (t.isWater(x0) || t.isWater(x1)) return@forEach
        val crest = (0..12).map { i ->
            val f = i / 12f
            Offset(w * (x0 + (x1 - x0) * f), h * base - (sin(PI * f).toFloat() * 7f * u) * (if (f < 0.6f) f / 0.6f else (1 - f) / 0.4f).coerceIn(0f, 1f) * 1.4f)
        }
        val body = polygon(crest + listOf(Offset(w * x1, h), Offset(w * x0, h)))
        pencil.erase(body)
        sand.stroke(crest, width = 0.9f, passes = 1)
        sand.hatch(body, body.getBounds(), spacing = 3.4f, width = 0.5f)
        val top = crest.maxByOrNull { -it.y } ?: return@forEach
        val lee = polygon(listOf(top, Offset(w * x1, h * base), Offset(w * x1, h), Offset(top.x, h)))
        lee.op(lee, body, PathOperation.Intersect)
        shade.hatch(lee, lee.getBounds(), spacing = 2f, width = 0.5f, rising = false)
    }
}

/** Un palmier : le tronc annelé un peu courbé, et ses palmes qui retombent (penchées par le vent). */
private fun DrawScope.palm(pencil: Pencil, base: Offset, height: Float, lean: Float = 0f) {
    val u = density
    val top = base + Offset(height * (0.18f + lean * 0.15f), -height)
    val trunk = (0..8).map { i ->
        val f = i / 8f
        Offset(base.x + (top.x - base.x) * f * f, base.y + (top.y - base.y) * f)
    }
    val wood = pencil.ink(Crayon.WOOD)
    wood.stroke(trunk, width = 1.6f)
    trunk.drop(1).dropLast(1).forEach { p -> wood.line(p + Offset(-1.2f * u, 0f), p + Offset(1.2f * u, 0.5f * u), width = 0.5f, passes = 1, wobble = 0.05f) }
    val green = pencil.ink(Crayon.FOREST)
    for (k in 0 until 6) {
        val a = PI * (1.05 + k * 0.18) + lean * 0.3
        val length = height * (0.42f + 0.08f * (k % 2))
        val frond = (0..6).map { i ->
            val f = i / 6f
            top + Offset((cos(a) * length * f).toFloat(), (sin(a) * length * f).toFloat() + length * 0.55f * f * f)
        }
        green.stroke(frond, width = 1.1f, passes = 1)
        frond.drop(1).forEach { p -> green.line(p, p + Offset(0f, 2.2f * u), width = 0.5f, passes = 1, wobble = 0.05f) }
    }
    pencil.ink(Crayon.WOOD).dot(top + Offset(0f, 1.5f * u), 1.2f)
}

/** Un dromadaire : le corps et sa bosse, le long cou, les pattes fines ; tourné à gauche ou à droite. */
private fun DrawScope.camel(pencil: Pencil, ground: Offset, facingRight: Boolean) {
    val u = density
    val s = if (facingRight) 1f else -1f
    val body = Path().apply {
        moveTo(ground.x - s * 6f * u, ground.y - 7f * u)
        quadraticTo(ground.x - s * 3f * u, ground.y - 14f * u, ground.x, ground.y - 12.5f * u)
        quadraticTo(ground.x + s * 4f * u, ground.y - 11f * u, ground.x + s * 6f * u, ground.y - 8f * u)
        lineTo(ground.x + s * 5f * u, ground.y - 6f * u)
        lineTo(ground.x - s * 5.5f * u, ground.y - 6f * u)
        close()
    }
    val wood = pencil.ink(Crayon.WOOD)
    listOf(-4.5f, -3f, 3f, 4.5f).forEach { dx -> wood.line(ground + Offset(s * dx * u, -6.5f * u), ground + Offset(s * dx * u, 0f), width = 0.7f, passes = 1) }
    pencil.erase(body)
    wood.outline(body, width = 0.9f)
    pencil.ink(Crayon.WHEAT).hatch(body, body.getBounds(), spacing = 1.3f, width = 0.5f)
    val neck = listOf(ground + Offset(s * 5.5f * u, -8f * u), ground + Offset(s * 8.5f * u, -10f * u), ground + Offset(s * 9.5f * u, -14f * u))
    wood.stroke(neck, width = 1.3f, passes = 1)
    wood.line(ground + Offset(s * 9.5f * u, -14f * u), ground + Offset(s * 12f * u, -13.2f * u), width = 1.4f, passes = 1)
}

// --- Chez nous : Franche-Comté, Jura suisse, Provence ---

/**
 * La ferme à tuyé du Haut-Doubs (et la ferme neuchâteloise) : un grand bâtiment trapu sous un immense toit peu pentu,
 * le pignon en tavaillons de bois, et la grosse cheminée pyramidale de bois où l'on fume les saucisses.
 */
private fun DrawScope.jurassienFarm(pencil: Pencil, ground: Offset, width: Float): Offset {
    val u = density
    val x = ground.x
    val g = ground.y
    val wall = width * 0.36f
    val walls = rect(x - width * 0.5f, g - wall, x + width * 0.5f, g)
    val roof = polygon(listOf(Offset(x - width * 0.62f, g - wall + 1f * u), Offset(x, g - wall - width * 0.34f), Offset(x + width * 0.62f, g - wall + 1f * u)))
    val tuye = polygon(listOf(Offset(x - width * 0.12f, g - wall - width * 0.24f), Offset(x - width * 0.05f, g - wall - width * 0.52f), Offset(x + width * 0.05f, g - wall - width * 0.52f), Offset(x + width * 0.12f, g - wall - width * 0.24f)))
    pencil.erase(Path().apply { addPath(walls) }.union(roof).union(tuye))
    pencil.outline(walls, width = 0.9f)
    pencil.ink(Crayon.WHEAT).hatch(walls, walls.getBounds(), spacing = 3.4f, width = 0.5f)
    pencil.outline(roof, width = 1f)
    pencil.roofInk(Crayon.FAR).hatch(roof, roof.getBounds(), spacing = 1.8f, width = 0.6f)
    // Les tavaillons du pignon, sous le toit.
    val gable = polygon(listOf(Offset(x - width * 0.3f, g - wall), Offset(x, g - wall - width * 0.18f), Offset(x + width * 0.3f, g - wall)))
    pencil.ink(Crayon.WOOD).hatch(gable, gable.getBounds(), spacing = 1.2f, width = 0.5f, rising = false)
    pencil.outline(tuye, width = 0.9f, passes = 1)
    pencil.ink(Crayon.WOOD).hatch(tuye, tuye.getBounds(), spacing = 1.1f, width = 0.5f)
    // La porte de grange en arc et deux petites fenêtres.
    pencil.stroke(listOf(Offset(x + width * 0.12f, g), Offset(x + width * 0.12f, g - wall * 0.6f), Offset(x + width * 0.24f, g - wall * 0.78f), Offset(x + width * 0.36f, g - wall * 0.6f), Offset(x + width * 0.36f, g)), width = 0.7f, passes = 1)
    listOf(-0.36f, -0.2f).forEach { f -> pencil.outline(rect(x + width * f, g - wall * 0.7f, x + width * (f + 0.08f), g - wall * 0.4f), width = 0.6f, passes = 1) }
    // Le haut du tuyé, d'où sort la fumée.
    return Offset(x, g - wall - width * 0.52f)
}

/** La ferme bressane : longue et basse, les murs à colombages et briques, le large toit de tuiles et sa cheminée sarrasine. */
private fun DrawScope.bresseFarm(pencil: Pencil, ground: Offset, width: Float): Offset {
    val u = density
    val x = ground.x
    val g = ground.y
    val wall = width * 0.3f
    val walls = rect(x - width * 0.5f, g - wall, x + width * 0.5f, g)
    val roof = polygon(listOf(Offset(x - width * 0.6f, g - wall), Offset(x - width * 0.3f, g - wall - width * 0.24f), Offset(x + width * 0.3f, g - wall - width * 0.24f), Offset(x + width * 0.6f, g - wall)))
    val stack = rect(x - width * 0.22f, g - wall - width * 0.42f, x - width * 0.14f, g - wall - width * 0.16f)
    val hood = polygon(listOf(Offset(x - width * 0.25f, g - wall - width * 0.42f), Offset(x - width * 0.18f, g - wall - width * 0.5f), Offset(x - width * 0.11f, g - wall - width * 0.42f)))
    pencil.erase(Path().apply { addPath(walls) }.union(roof).union(stack).union(hood))
    pencil.outline(walls, width = 0.9f)
    pencil.ink(Crayon.ROOF).hatch(walls, walls.getBounds(), spacing = 3f, width = 0.5f)
    // Les pans de bois : poteaux et croix de Saint-André.
    var k = 0
    var px = x - width * 0.5f
    while (px < x + width * 0.49f) {
        val next = px + width * 0.125f
        pencil.ink(Crayon.WOOD).line(Offset(px, g), Offset(px, g - wall), width = 0.7f, passes = 1)
        if (k % 2 == 0) {
            pencil.ink(Crayon.WOOD).line(Offset(px, g), Offset(next, g - wall), width = 0.5f, passes = 1)
            pencil.ink(Crayon.WOOD).line(Offset(px, g - wall), Offset(next, g), width = 0.5f, passes = 1)
        }
        px = next
        k++
    }
    pencil.outline(roof, width = 1f)
    pencil.roofInk(Crayon.ROOF).hatch(roof, roof.getBounds(), spacing = 1.8f, width = 0.6f)
    pencil.outline(stack, width = 0.8f, passes = 1)
    pencil.outline(hood, width = 0.8f, passes = 1)
    pencil.roofInk(Crayon.ROOF).hatch(hood, hood.getBounds(), spacing = 1f, width = 0.5f)
    return Offset(x - width * 0.18f, g - wall - width * 0.5f)
}

/** Le temple protestant du Jura suisse : la nef blanche, le clocher carré et sa flèche, le drapeau suisse à côté. */
private fun DrawScope.temple(pencil: Pencil, ground: Offset, scale: Float) {
    val u = density * scale
    val x = ground.x
    val g = ground.y
    val nave = rect(x - 3f * u, g - 11f * u, x + 18f * u, g)
    val roof = polygon(listOf(Offset(x - 4f * u, g - 11f * u), Offset(x + 7.5f * u, g - 17f * u), Offset(x + 19f * u, g - 11f * u)))
    val tower = rect(x - 7f * u, g - 24f * u, x - 1f * u, g)
    val tip = spire(x - 8f * u, x, g - 24f * u, g - 36f * u)
    pencil.erase(Path().apply { addPath(nave) }.union(roof).union(tower).union(tip))
    pencil.outline(nave, width = 0.9f)
    pencil.outline(tower, width = 1f)
    listOf(roof, tip).forEach {
        pencil.outline(it, width = 0.9f)
        pencil.roofInk(Crayon.ROOF).hatch(it, it.getBounds(), spacing = 1.8f, width = 0.6f)
    }
    pencil.outline(oval(Offset(x - 4f * u, g - 19f * u), 1.6f * u, 1.6f * u), width = 0.6f, passes = 1)
    listOf(4f, 9f, 14f).forEach { dx -> pencil.stroke(listOf(Offset(x + dx * u, g - 3f * u), Offset(x + dx * u, g - 7f * u), Offset(x + (dx + 1.2f) * u, g - 8.5f * u), Offset(x + (dx + 2.4f) * u, g - 7f * u), Offset(x + (dx + 2.4f) * u, g - 3f * u)), width = 0.5f, passes = 1) }
    // Le drapeau rouge à croix blanche.
    val pole = Offset(x + 23f * u, g)
    pencil.line(pole, pole + Offset(0f, -18f * u), width = 0.6f, passes = 1)
    val flag = rect(pole.x, pole.y - 18f * u, pole.x + 6f * u, pole.y - 12f * u)
    pencil.ink(Crayon.RED).hatch(flag, flag.getBounds(), spacing = 0.8f, width = 0.6f)
    pencil.scope.drawRect(androidx.compose.ui.graphics.Color.White, Offset(pole.x + 2.5f * u, pole.y - 16.8f * u), Size(1f * u, 3.6f * u))
    pencil.scope.drawRect(androidx.compose.ui.graphics.Color.White, Offset(pole.x + 1.2f * u, pole.y - 15.5f * u), Size(3.6f * u, 1f * u))
}

/** L'église de Provence : la nef sous ses tuiles rondes, le clocher carré coiffé de son campanile de fer forgé. */
private fun DrawScope.provencalChurch(pencil: Pencil, ground: Offset, scale: Float) {
    val u = density * scale
    val x = ground.x
    val g = ground.y
    val nave = rect(x + 3f * u, g - 11f * u, x + 22f * u, g)
    val roof = polygon(listOf(Offset(x + 2f * u, g - 11f * u), Offset(x + 12.5f * u, g - 15f * u), Offset(x + 23f * u, g - 11f * u)))
    val tower = rect(x - 3f * u, g - 24f * u, x + 3f * u, g)
    build(pencil, listOf(nave, tower), Crayon.WHEAT, spacing = 3.4f)
    pencil.erase(roof)
    pencil.outline(roof, width = 0.9f)
    pencil.roofInk(Crayon.ROOF).hatch(roof, roof.getBounds(), spacing = 1.6f, width = 0.6f)
    // Le campanile : une cage de fer en bulbe, sa cloche dedans, la croix au-dessus.
    val top = Offset(x, g - 24f * u)
    for (k in -2..2) {
        val points = (0..8).map { i ->
            val f = i / 8f
            top + Offset(k * 1.4f * u * sin(PI * (0.15 + 0.85 * f)).toFloat() * 1.6f, -10f * u * f)
        }
        pencil.stroke(points, width = 0.5f, passes = 1)
    }
    pencil.outline(oval(top + Offset(0f, -3.5f * u), 1.6f * u, 2f * u), width = 0.6f, passes = 1)
    pencil.line(top + Offset(0f, -10f * u), top + Offset(0f, -14f * u), width = 0.6f, passes = 1)
    pencil.line(top + Offset(-1.4f * u, -12.6f * u), top + Offset(1.4f * u, -12.6f * u), width = 0.5f, passes = 1)
    pencil.stroke(listOf(Offset(x + 10f * u, g), Offset(x + 10f * u, g - 5f * u), Offset(x + 12f * u, g - 7f * u), Offset(x + 14f * u, g - 5f * u), Offset(x + 14f * u, g)), width = 0.6f, passes = 1)
}

/** Le pin parasol : un long tronc un peu tordu, la couronne plate et large. */
private fun DrawScope.umbrellaPine(pencil: Pencil, base: Offset, height: Float, lean: Float = 0f) {
    val top = base + Offset(height * (0.12f + lean * 0.1f), -height * 0.82f)
    pencil.ink(Crayon.WOOD).stroke(listOf(base, base + Offset(height * 0.02f, -height * 0.4f), top), width = 1.2f)
    val crown = oval(top + Offset(0f, -height * 0.06f), height * 0.42f, height * 0.14f)
        .union(oval(top + Offset(-height * 0.18f, height * 0.03f), height * 0.22f, height * 0.1f))
    pencil.erase(crown)
    pencil.outline(crown, width = 0.9f)
    pencil.ink(Crayon.FOREST).hatch(crown, crown.getBounds(), spacing = 2f, width = 0.6f)
}

/** Le cyprès : une haute flamme vert sombre. */
private fun DrawScope.cypress(pencil: Pencil, base: Offset, height: Float, lean: Float = 0f) {
    val r = height * 0.13f
    val tip = base + Offset(lean * height * 0.1f, -height)
    val shape = Path().apply {
        moveTo(base.x, base.y)
        cubicTo(base.x - r * 1.4f, base.y - height * 0.3f, tip.x - r, tip.y + height * 0.35f, tip.x, tip.y)
        cubicTo(tip.x + r, tip.y + height * 0.35f, base.x + r * 1.4f, base.y - height * 0.3f, base.x, base.y)
        close()
    }
    pencil.erase(shape)
    pencil.outline(shape, width = 0.9f)
    pencil.ink(Crayon.FOREST).hatch(shape, shape.getBounds(), spacing = 1.4f, width = 0.6f)
}

/** Une butte rocheuse en plaine, pour le château ou les ruines ; renvoie son sommet. */
private fun DrawScope.butte(pencil: Pencil, ground: Offset): Offset {
    val u = density
    val shape = polygon(listOf(ground + Offset(-22f * u, 0f), ground + Offset(-10f * u, -9f * u), ground + Offset(-4f * u, -14f * u), ground + Offset(8f * u, -14.5f * u), ground + Offset(14f * u, -8f * u), ground + Offset(24f * u, 0f)))
    pencil.erase(shape)
    pencil.outline(shape, width = 1f)
    pencil.ink(Crayon.ROCK).hatch(shape, shape.getBounds(), spacing = 2.4f, width = 0.5f)
    pencil.ink(Crayon.FOREST).hatch(shape, Rect(ground.x - 22f * u, ground.y - 6f * u, ground.x + 24f * u, ground.y), spacing = 2.2f, width = 0.5f, rising = false)
    return ground + Offset(2f * u, -14f * u)
}

/**
 * Crussol, vu de Valence : la montagne monte doucement par la garrigue et tombe sur le Rhône par sa falaise de
 * calcaire ; tout en haut, au bord de la falaise, les deux « cornes » du donjon en ruine, et les pans de mur du vieux
 * bourg dans la pente. Sans [hill], seulement les ruines, sur la colline que la coupe dessine déjà.
 */
private fun DrawScope.crussol(pencil: Pencil, ground: Offset, hill: Boolean) {
    val u = density
    fun p(dx: Float, dy: Float) = Offset(ground.x + dx * u, ground.y + dy * u)
    // Le haut de la montagne, où sont les ruines.
    val top = if (hill) p(0f, -21f) else ground
    fun q(dx: Float, dy: Float) = top + Offset(dx * u, dy * u)
    if (hill) {
        val crest = listOf(p(-34f, 0f), p(-27f, -5f), p(-18f, -11f), p(-9f, -17f), p(-2f, -21f), p(5f, -22f), p(9f, -21f))
        val cliff = listOf(p(10.5f, -17f), p(11f, -10f), p(14f, -6f), p(20f, 0f))
        val shape = polygon(crest + cliff)
        pencil.erase(shape)
        pencil.outline(shape, width = 1f)
        // La garrigue sur la pente, le calcaire nu en haut et sur la falaise, ses strates.
        pencil.ink(Crayon.FOREST).hatch(shape, Rect(ground.x - 34f * u, ground.y - 13f * u, ground.x - 2f * u, ground.y), spacing = 2.2f, width = 0.5f, rising = false)
        pencil.ink(Crayon.ROCK).hatch(shape, Rect(ground.x - 2f * u, ground.y - 23f * u, ground.x + 20f * u, ground.y), spacing = 2f, width = 0.5f)
        listOf(Triple(-17f, 4f, 10f), Triple(-12f, 3f, 10.5f), Triple(-7f, 2f, 12.5f)).forEach { (dy, from, to) -> pencil.line(p(from, dy), p(to, dy + 0.8f), width = 0.5f, passes = 1) }
        // Le vieux bourg abandonné, en gradins dans la pente.
        listOf(Triple(-8f, -17f, 4f), Triple(-14f, -13f, 3f), Triple(-20f, -9.5f, 3.5f)).forEach { (dx, dy, height) ->
            val wall = polygon(listOf(p(dx - 2.5f, dy + 1.5f), p(dx - 2.5f, dy - height), p(dx - 0.5f, dy - height + 1f), p(dx + 1f, dy - height - 0.5f), p(dx + 2.5f, dy - height + 1.5f), p(dx + 2.5f, dy + 1f)))
            pencil.erase(wall)
            pencil.outline(wall, width = 0.6f, passes = 1)
            pencil.ink(Crayon.WHEAT).hatch(wall, wall.getBounds(), spacing = 1.6f, width = 0.4f)
        }
    }
    // Les cornes du donjon, une baie en arc entre les deux.
    val keep = polygon(listOf(q(-1f, 0.5f), q(-1f, -8f), q(0f, -13f), q(1.2f, -17f), q(2.4f, -10f), q(3.6f, -9f), q(5f, -14f), q(6f, -8f), q(7.5f, -5f), q(8.5f, 0.5f)))
    pencil.erase(keep)
    pencil.outline(keep, width = 0.9f)
    pencil.ink(Crayon.WHEAT).hatch(keep, keep.getBounds(), spacing = 1.8f, width = 0.5f)
    val bay = Path().apply {
        val x = top.x
        val g = top.y
        moveTo(x + 2.4f * u, g - 2f * u)
        lineTo(x + 2.4f * u, g - 5f * u)
        quadraticTo(x + 3.4f * u, g - 7f * u, x + 4.4f * u, g - 5f * u)
        lineTo(x + 4.4f * u, g - 2f * u)
        close()
    }
    pencil.erase(bay)
    pencil.outline(bay, width = 0.5f, passes = 1)
}

/** Les îles au large (Porquerolles, Port-Cros) : de longues bosses bleutées sur la mer. */
private fun DrawScope.islands(pencil: Pencil, w: Float, h: Float) {
    val u = density
    // Posées sur l'eau, un peu au large du rivage.
    val sea = h * SEA + 8f * u
    listOf(Triple(0.58f, 0.07f, 5f), Triple(0.75f, 0.05f, 6f)).forEach { (x, half, height) ->
        val shape = Path().apply {
            moveTo(w * (x - half), sea)
            cubicTo(w * (x - half * 0.6f), sea - height * u, w * (x - half * 0.1f), sea - height * 1.1f * u, w * (x + half * 0.3f), sea - height * 0.8f * u)
            quadraticTo(w * (x + half * 0.7f), sea - height * 0.6f * u, w * (x + half), sea)
            close()
        }
        pencil.erase(shape)
        pencil.ink(Crayon.FAR).outline(shape, width = 0.8f, passes = 1)
        pencil.ink(Crayon.FAR).hatch(shape, shape.getBounds(), spacing = 2.2f, width = 0.5f)
    }
}

/** Morteau : l'église Notre-Dame et son clocher comtois, le château Pertusier et sa tour ronde à côté. */
private fun DrawScope.pertusier(pencil: Pencil, ground: Offset) {
    val u = density
    church(pencil, ground)
    val x = ground.x + 16f * u
    val g = ground.y
    val body = rect(x, g - 14f * u, x + 18f * u, g)
    val roof = polygon(listOf(Offset(x - 1f * u, g - 14f * u), Offset(x + 3f * u, g - 21f * u), Offset(x + 15f * u, g - 21f * u), Offset(x + 19f * u, g - 14f * u)))
    val tower = rect(x + 17f * u, g - 19f * u, x + 22f * u, g)
    val cap = spire(x + 16.5f * u, x + 22.5f * u, g - 19f * u, g - 26f * u)
    build(pencil, listOf(body, tower), Crayon.WHEAT, spacing = 3f)
    listOf(roof, cap).forEach {
        pencil.erase(it)
        pencil.outline(it, width = 0.9f)
        pencil.roofInk(Crayon.FAR).hatch(it, it.getBounds(), spacing = 1.5f, width = 0.5f)
    }
    // Les fenêtres à meneaux de la Renaissance.
    listOf(3f, 8f, 13f).forEach { dx ->
        val win = rect(x + dx * u, g - 11f * u, x + (dx + 2.6f) * u, g - 6f * u)
        pencil.outline(win, width = 0.5f, passes = 1)
        pencil.line(Offset(x + (dx + 1.3f) * u, g - 11f * u), Offset(x + (dx + 1.3f) * u, g - 6f * u), width = 0.4f, passes = 1)
    }
}

/** Valence : la cathédrale Saint-Apollinaire, romane, son clocher-porche carré et sa flèche de pierre, l'abside ronde. */
private fun DrawScope.apollinaire(pencil: Pencil, ground: Offset) {
    val u = density
    val x = ground.x
    val g = ground.y
    val nave = rect(x - 2f * u, g - 13f * u, x + 22f * u, g)
    val roof = polygon(listOf(Offset(x - 2f * u, g - 13f * u), Offset(x + 10f * u, g - 18f * u), Offset(x + 22f * u, g - 13f * u)))
    val apse = Path().apply { addArc(Rect(x + 17f * u, g - 11f * u, x + 29f * u, g + 1f * u), 270f, 180f); close() }
    val tower = rect(x - 9f * u, g - 32f * u, x - 1f * u, g)
    val tip = spire(x - 10f * u, x, g - 32f * u, g - 48f * u)
    build(pencil, listOf(nave, apse, tower), Crayon.WHEAT, spacing = 2.6f)
    pencil.erase(roof)
    pencil.outline(roof, width = 0.9f)
    pencil.roofInk(Crayon.ROOF).hatch(roof, roof.getBounds(), spacing = 1.6f, width = 0.5f)
    pencil.erase(tip)
    pencil.outline(tip, width = 0.9f)
    pencil.ink(Crayon.ROCK).hatch(tip, tip.getBounds(), spacing = 1.4f, width = 0.5f)
    // Les baies géminées du clocher, le grand portail en plein cintre.
    listOf(-7f, -4f).forEach { dx -> pencil.stroke(listOf(Offset(x + dx * u, g - 22f * u), Offset(x + dx * u, g - 26f * u), Offset(x + (dx + 1f) * u, g - 27f * u), Offset(x + (dx + 2f) * u, g - 26f * u), Offset(x + (dx + 2f) * u, g - 22f * u)), width = 0.5f, passes = 1) }
    pencil.stroke((0..8).map { i -> val a = PI + PI * i / 8; Offset(x - 5f * u + (2.4f * u * cos(a)).toFloat(), g - 5f * u + (2.4f * u * sin(a)).toFloat()) }, width = 0.6f, passes = 1)
    listOf(4f, 10f, 16f).forEach { dx -> pencil.stroke(listOf(Offset(x + dx * u, g - 5f * u), Offset(x + dx * u, g - 9f * u), Offset(x + (dx + 1f) * u, g - 10f * u), Offset(x + (dx + 2f) * u, g - 9f * u), Offset(x + (dx + 2f) * u, g - 5f * u)), width = 0.5f, passes = 1) }
}

/**
 * Besançon : la colline Saint-Étienne et, tout en haut, la citadelle de Vauban, ses murailles en dents de scie et ses
 * casernes ; un clocher comtois au pied.
 */
private fun DrawScope.citadel(pencil: Pencil, ground: Offset) {
    val u = density
    val x = ground.x
    val g = ground.y
    // Assez haute pour dominer les toits de la vieille ville.
    val top = g - 54f * u
    val hill = Path().apply {
        moveTo(x - 50f * u, g)
        quadraticTo(x - 34f * u, top + 6f * u, x - 14f * u, top)
        lineTo(x + 16f * u, top)
        quadraticTo(x + 34f * u, top + 6f * u, x + 52f * u, g)
        close()
    }
    pencil.erase(hill)
    pencil.outline(hill, width = 1f)
    pencil.ink(Crayon.FOREST).hatch(hill, Rect(x - 50f * u, top + 10f * u, x + 52f * u, g), spacing = 2.4f, width = 0.5f)
    pencil.ink(Crayon.ROCK).hatch(hill, Rect(x - 50f * u, top, x + 52f * u, top + 10f * u), spacing = 2f, width = 0.5f)
    // Les remparts : une ligne brisée de bastions, puis les casernes et la chapelle.
    val wall = listOf(-20f to 0f, -16f to -4f, -11f to 0f, -6f to -4f, 0f to 0f, 6f to -4f, 11f to 0f, 16f to -4f, 21f to 0f)
        .map { (dx, dy) -> Offset(x + dx * u, top + 1f * u + dy * u) }
    val walls = polygon(wall + listOf(Offset(x + 21f * u, top + 6f * u), Offset(x - 20f * u, top + 6f * u)))
    pencil.erase(walls)
    pencil.outline(walls, width = 0.9f)
    pencil.ink(Crayon.WHEAT).hatch(walls, walls.getBounds(), spacing = 1.8f, width = 0.5f)
    listOf(-13f to 9f, 2f to 11f).forEach { (dx, width) ->
        val barracks = rect(x + dx * u, top - 7f * u, x + (dx + width) * u, top - 3f * u)
        val roof = polygon(listOf(Offset(x + (dx - 0.5f) * u, top - 7f * u), Offset(x + (dx + 1.5f) * u, top - 10f * u), Offset(x + (dx + width - 1.5f) * u, top - 10f * u), Offset(x + (dx + width + 0.5f) * u, top - 7f * u)))
        build(pencil, listOf(barracks), Crayon.WHEAT, spacing = 2.6f)
        pencil.erase(roof)
        pencil.outline(roof, width = 0.8f, passes = 1)
        pencil.roofInk(Crayon.FAR).hatch(roof, roof.getBounds(), spacing = 1.4f, width = 0.5f)
    }
    church(pencil, ground + Offset(-30f * u, 0f))
}
