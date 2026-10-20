package io.github.pbailly.meteo.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/*
 * Le dessin aux crayons de couleur d'Aujourd'hui : le crayon, la boîte de couleurs, et le ciel. Chaque temps se dessine
 * de trois façons (SketchScene.skyVariant), plus ou moins fort selon le code météo (SketchWeather) ; le paysage et les
 * surprises sont dans SketchDecor.kt.
 */

/** Ce qu'on dessine : tout, ce qui est fixe (paysage, astres, brume, surprises), ou ce qui bouge (nuages, pluie, éclairs). */
internal enum class SketchPart { ALL, STILL, MOVING }

/**
 * La scène, entière ou en deux couches : le fixe, dessiné une fois, puis ce qui bouge à chaque image de l'animation
 * ([phase] de 0 à 1 sur [SketchScenes.CYCLE_MS]). Les nuages effacent ce qui est derrière eux, y compris le fixe.
 */
internal fun DrawScope.drawScene(
    pencil: Pencil,
    scene: SketchScene,
    isDay: Boolean,
    moonAge: Double,
    southern: Boolean,
    month: Int,
    part: SketchPart = SketchPart.ALL,
    phase: Float = 0f,
) {
    if (part == SketchPart.ALL) {
        drawScene(pencil, scene, isDay, moonAge, southern, month, SketchPart.STILL, phase)
        drawScene(pencil, scene, isDay, moonAge, southern, month, SketchPart.MOVING, phase)
        return
    }
    val still = part == SketchPart.STILL
    val moving = part == SketchPart.MOVING
    val w = size.width
    val h = size.height
    val u = density
    val v = scene.skyVariant
    if (still) {
        skyLight(pencil, scene, w, h)
        landscape(pencil, scene, w, h, month, isDay)
        if (scene.conditions.haze) haze(pencil, w, h)
    }
    // Le dessin occupe l'espace libre : entre la grande température (à gauche) et le ciel en toutes lettres (à
    // droite), sous la ligne du lieu et au-dessus du ressenti. À l'heure dorée, le soleil est bas et passe derrière
    // le relief : à gauche le matin, à droite le soir.
    val low = scene.light == Light.SUNRISE || scene.light == Light.SUNSET
    val celestial = when (scene.light) {
        Light.SUNRISE -> Offset(w * 0.47f, h * 0.60f)
        Light.SUNSET -> Offset(w * 0.64f, h * 0.60f)
        else -> Offset(w * 0.57f, h * 0.47f)
    }
    val sunColor = when {
        low -> Crayon.ORANGE
        scene.conditions.dust -> Crayon.WHEAT
        else -> Crayon.SUN
    }
    fun sunAt(at: Offset, radius: Float, rays: Rays = Rays.STRAIGHT) {
        if (!still) return
        if (low) {
            clipPath(skyAbove(scene.terrain, w, h)) { sun(pencil, Offset(at.x, celestial.y), radius * 1.15f, if (rays == Rays.NONE) rays else Rays.SHORT, sunColor) }
        } else {
            sun(pencil, at, radius, rays, sunColor)
        }
    }
    fun light(at: Offset, radius: Float, rays: Rays = Rays.STRAIGHT) {
        if (!still) return
        if (isDay) sunAt(at, radius, rays) else moon(pencil, at, radius, moonAge, southern)
    }
    // Les nuages à leur hauteur : plus bas quand ce sont des nuages bas (ils accrochent les sommets). Ils glissent
    // doucement d'un côté à l'autre, d'autant plus qu'il y a du vent.
    val drop = scene.cloudDrop
    val drift = (sin(2 * PI * phase).toFloat()) * (0.010f + 0.015f * kotlin.math.abs(scene.lean))
    fun cloudAt(x: Float, y: Float, width: Float, shade: Float) {
        if (moving) cloud(pencil, Offset(w * (x + drift), h * (y + drop)), width * u, shade)
    }
    // Au sommet, les nuages bas sont en dessous : une mer de nuages.
    val belowSummit = scene.terrain.relief == Relief.SUMMIT && scene.lowClouds && (scene.clouds?.mid ?: 1f) < 0.3f &&
        scene.sky in setOf(SketchSky.PARTLY, SketchSky.CLOUDY)
    // Des cirrus très haut, par beau temps.
    if (still && (scene.clouds?.high ?: 0f) >= 0.4f && scene.sky in setOf(SketchSky.CLEAR, SketchSky.PARTLY)) {
        cirrus(pencil, Offset(w * 0.66f, h * 0.38f), w * 0.13f)
    }
    if (belowSummit) {
        if (still) {
            seaOfClouds(pencil, scene.terrain, w, h)
            light(celestial + Offset(0f, -6f * u), 14f * u)
            surprises(pencil, scene, w, h)
        }
        return
    }
    when (scene.sky) {
        SketchSky.CLEAR -> if (isDay) {
            when (v) {
                0 -> sunAt(celestial, 16f * u)
                1 -> sunAt(celestial + Offset(4f * u, -2f * u), 15f * u, Rays.WAVY)
                // Grand soleil aux longs rayons, et quelques cirrus très haut.
                else -> {
                    sunAt(celestial + Offset(-10f * u, 0f), 14f * u, Rays.LONG)
                    if (still) cirrus(pencil, Offset(w * 0.68f, h * 0.41f), w * 0.12f)
                }
            }
        } else if (still) {
            night(pencil, celestial, w, h, moonAge, southern, v, twilight = scene.light == Light.DAWN || scene.light == Light.DUSK)
        }
        SketchSky.PARTLY -> when (v) {
            0 -> {
                light(celestial + Offset(-12f * u, -6f * u), 14f * u)
                cloudAt(0.61f, 0.55f, 68f, shade = 0.5f)
            }
            // Le nuage passe devant, à gauche.
            1 -> {
                light(celestial + Offset(14f * u, -5f * u), 14f * u)
                cloudAt(0.52f, 0.56f, 60f, shade = 0.45f)
            }
            // Deux petits cumulus de beau temps, le soleil entre eux.
            else -> {
                light(celestial + Offset(0f, -3f * u), 13f * u)
                cloudAt(0.45f, 0.55f, 44f, shade = 0.35f)
                cloudAt(0.70f, 0.53f, 52f, shade = 0.45f)
            }
        }
        // Un voile de nuages hauts : le soleil pâle au travers, une couche fine tout en haut.
        SketchSky.CLOUDY -> if (scene.highVeil) {
            light(celestial + Offset(0f, 4f * u), 13f * u, Rays.NONE)
            if (moving) stratus(pencil, w * (0.39f + drift), w * (0.80f + drift), h * 0.40f, 6f * u)
            if (still) cirrus(pencil, Offset(w * 0.42f, h * 0.55f), w * 0.12f)
        } else when (v) {
            0 -> {
                cloudAt(0.50f, 0.47f, 72f, shade = 0.7f)
                cloudAt(0.64f, 0.56f, 84f, shade = 0.9f)
            }
            1 -> {
                cloudAt(0.45f, 0.47f, 58f, shade = 0.6f)
                cloudAt(0.71f, 0.46f, 64f, shade = 0.7f)
                cloudAt(0.58f, 0.56f, 86f, shade = 0.95f)
            }
            // Ciel couvert : une couche grise d'un bord à l'autre, et un nuage plus bas devant.
            else -> {
                if (moving) stratus(pencil, w * (0.39f + drift), w * (0.80f + drift), h * (0.45f + drop), 9f * u)
                cloudAt(0.60f, 0.57f, 70f, shade = 0.8f)
            }
        }
        SketchSky.FOG -> if (still) {
            // Au sommet, toujours la mer de nuages, d'en haut.
            val mist = if (scene.terrain.relief == Relief.SUMMIT) 1 else v
            when (mist) {
                0 -> {
                    if (isDay) sunAt(celestial, 14f * u, Rays.NONE)
                    fog(pencil, w, h)
                }
                1 -> {
                    seaOfClouds(pencil, scene.terrain, w, h)
                    light(celestial + Offset(0f, -6f * u), 13f * u, Rays.SHORT)
                }
                else -> {
                    if (isDay) sunAt(celestial, 12f * u, Rays.NONE)
                    thickFog(pencil, w, h)
                }
            }
        }
        SketchSky.RAIN, SketchSky.SNOW, SketchSky.STORM -> precipitation(pencil, scene, isDay, moonAge, southern, w, h, still, phase, drift)
    }
    if (still) surprises(pencil, scene, w, h)
}

/**
 * Pluie, neige et orage : les nuages, puis ce qui en tombe, plus ou moins fort. Le soleil d'averse et l'arc-en-ciel
 * sont dans la couche fixe ([still]), le reste bouge.
 */
private fun DrawScope.precipitation(
    pencil: Pencil,
    scene: SketchScene,
    isDay: Boolean,
    moonAge: Double,
    southern: Boolean,
    w: Float,
    h: Float,
    still: Boolean,
    phase: Float,
    drift: Float,
) {
    val u = density
    val v = scene.skyVariant
    val wx = scene.weather
    val storm = scene.sky == SketchSky.STORM
    val dense = if (storm) 1.6f else 0.75f + 0.3f * wx.intensity
    // Nuages bas : nuages, pluie et éclairs descendent ensemble (pas pour l'averse et son arc-en-ciel).
    val drop = if (Surprise.RAINBOW in scene.surprises) 0f else scene.cloudDrop
    val moving = !still
    fun cloudAt(x: Float, y: Float, width: Float, shade: Float) {
        if (moving) cloud(pencil, Offset(w * (x + drift), h * (y + drop)), width * u, shade)
    }
    var from = 0.42f
    var to = 0.66f
    when {
        Surprise.RAINBOW in scene.surprises -> {
            // Averse : un seul nuage, et l'arc-en-ciel à côté.
            if (still) rainbow(pencil, Offset(w * 0.69f, h * 0.88f), w * 0.12f)
            if (moving) cloud(pencil, Offset(w * (0.50f + drift), h * 0.45f), 70f * u, shade = 0.9f)
            to = 0.58f
        }
        storm && v == 2 -> {
            if (moving) cumulonimbus(pencil, Offset(w * (0.57f + drift * 0.5f), h * (0.57f + drop)), 88f * u)
            from = 0.47f
            to = 0.68f
        }
        wx.showers && !storm -> {
            // Averse : un gros nuage, et le soleil (ou la lune) qui perce à côté.
            if (v != 2 && still) {
                if (isDay) sun(pencil, Offset(w * 0.70f, h * (0.45f + drop)), 11f * u, Rays.SHORT) else moon(pencil, Offset(w * 0.71f, h * (0.45f + drop)), 11f * u, moonAge, southern)
            }
            cloudAt(0.53f, 0.47f, 80f, shade = dense)
            from = 0.44f
            to = 0.62f
        }
        else -> {
            cloudAt(0.50f, 0.44f, 72f, shade = dense)
            cloudAt(0.64f, 0.50f, 84f, shade = dense)
            if (wx.intensity == 2 && v == 1) cloudAt(0.42f, 0.52f, 50f, shade = dense)
        }
    }
    if (still) return
    from += drift
    to += drift
    when (scene.sky) {
        SketchSky.SNOW -> snow(pencil, w, h, from, to, wx, v, drop, phase, scene.lean)
        SketchSky.STORM -> {
            rain(pencil, w, h, from, to, wx, style = if (v == 1) 1 else 0, drop = drop, phase = phase, lean = scene.lean)
            // L'éclair claque par moments (toujours là sur une image fixe, quand la phase vaut 0).
            if ((phase * 5) % 1f < 0.14f) {
                when (v) {
                    0 -> lightning(pencil, Offset(w * (0.58f + drift), h * (0.60f + drop)), 34f * u)
                    1 -> {
                        lightning(pencil, Offset(w * (0.50f + drift), h * (0.58f + drop)), 28f * u)
                        lightning(pencil, Offset(w * (0.68f + drift), h * (0.62f + drop)), 32f * u)
                    }
                    else -> lightning(pencil, Offset(w * (0.57f + drift * 0.5f), h * (0.62f + drop)), 38f * u, forked = true)
                }
            }
        }
        else -> rain(pencil, w, h, from, to, wx, style = v, drop = drop, phase = phase, lean = scene.lean)
    }
}

/**
 * La couleur du ciel au-dessus du relief : l'or et le rose de l'heure dorée, le violet de l'aube et du crépuscule, le
 * jaune du sable du Sahara. Hachures en bandes, plus chaudes vers l'horizon.
 */
private fun DrawScope.skyLight(pencil: Pencil, scene: SketchScene, w: Float, h: Float) {
    val wet = scene.sky == SketchSky.RAIN || scene.sky == SketchSky.SNOW || scene.sky == SketchSky.STORM || scene.sky == SketchSky.FOG
    val bands = when {
        scene.conditions.dust -> listOf(Crayon.WHEAT to 0.38f, Crayon.EARTH to 0.62f)
        wet -> emptyList()
        scene.light == Light.SUNRISE || scene.light == Light.SUNSET -> listOf(Crayon.YELLOW to 0.46f, Crayon.ORANGE to 0.57f, Crayon.PINK to 0.68f)
        scene.light == Light.DAWN || scene.light == Light.DUSK -> listOf(Crayon.VIOLET to 0.48f, Crayon.PINK to 0.62f)
        else -> emptyList()
    }
    if (bands.isEmpty()) return
    // Une lueur aux bords irréguliers, comme un coloriage : plus large en bas, contre le relief.
    val u = density
    val rnd = Random(scene.seed)
    val top = bands.first().second
    val steps = 14
    val upper = (0..steps).map { i -> Offset(w * (0.36f + 0.40f * i / steps), h * top + (rnd.nextFloat() - 0.3f) * 9f * u + (sin(i * 0.8) * 4 * u).toFloat()) }
    val right = (1..6).map { i -> Offset(w * 0.80f + (rnd.nextFloat() - 0.5f) * 10f * u, h * (top + (FOOT - top) * i / 6)) }
    val left = (6 downTo 1).map { i -> Offset(w * 0.31f + (rnd.nextFloat() - 0.5f) * 10f * u, h * (top + (FOOT - top) * i / 6)) }
    val glow = Path().apply {
        op(polygon(upper + right + left), skyAbove(scene.terrain, w, h), PathOperation.Intersect)
    }
    bands.forEachIndexed { k, (color, from) ->
        val bottom = bands.getOrNull(k + 1)?.second ?: FOOT
        // Les bandes se chevauchent un peu : pas de limite tirée à la règle.
        pencil.ink(color).hatch(glow, Rect(0f, h * from - 4f * u, w, h * bottom + 4f * u), spacing = 6f, width = 0.6f)
    }
}

/** Un polygone fermé. */
private fun polygon(points: List<Offset>) = Path().apply {
    moveTo(points.first().x, points.first().y)
    points.drop(1).forEach { lineTo(it.x, it.y) }
    close()
}

/** Air pollué : le relief pâlit au loin, et des traînées de brume brunâtre au-dessus de l'horizon. */
private fun DrawScope.haze(pencil: Pencil, w: Float, h: Float) {
    val u = density
    pencil.fade(Path().apply { addRect(Rect(0f, 0f, w, h * (FOOT - 0.03f))) }, 0.3f)
    val ink = pencil.ink(Crayon.EARTH)
    for (k in 0 until 4) {
        val y = h * (0.60f + 0.06f * k)
        var x = w * (0.32f + 0.03f * (k % 2))
        while (x < w * 0.78f) {
            ink.line(Offset(x, y), Offset(x + 14f * u, y + 0.5f * u), width = 0.8f, passes = 1, wobble = 0.3f)
            x += 22f * u
        }
    }
}

/** Force des couleurs du dessin : discret, normal ou coloré. */
internal fun sketchStrength(style: Int) = when (style) {
    0 -> 0.65f
    2 -> 1.45f
    else -> 1f
}

/** Le crayon gris des traits, en thème clair ou sombre, plus ou moins appuyé. */
internal fun sketchGraphite(dark: Boolean, strength: Float = 1f) =
    if (dark) Color(0xFFE6ECF4).copy(alpha = (0.22f * strength).coerceAtMost(0.5f)) else Color(0xFF2E3644).copy(alpha = (0.26f * strength).coerceAtMost(0.55f))

/** Les crayons de couleur de la boîte (adoucis au trait par [Pencil.ink]). */
internal object Crayon {
    val FOREST = Color(0xFF3E8A4C)
    val MEADOW = Color(0xFF79B04F)
    val ROCK = Color(0xFF8C7866)
    val SNOW = Color(0xFF7DA6D6)
    val FAR = Color(0xFF7F95B8)
    val WATER = Color(0xFF2F7DCC)
    val SUN = Color(0xFFF09A1E)
    val MOON = Color(0xFFD8B23C)
    val CLOUD = Color(0xFF6C82A3)
    val RAIN = Color(0xFF2E74CF)
    val BOLT = Color(0xFFF2B705)
    val ROOF = Color(0xFFC4532F)
    val DOME = Color(0xFF2F7D6E)
    val WOOD = Color(0xFF9A6A3C)
    val WHEAT = Color(0xFFD9A632)
    val EARTH = Color(0xFF9C7452)
    val AUTUMN = Color(0xFFE2862C)
    val RED = Color(0xFFD9443A)
    val ORANGE = Color(0xFFEE7F2D)
    val PINK = Color(0xFFE47FA6)
    val YELLOW = Color(0xFFF0BE1E)
    val BLUE = Color(0xFF4F8FDB)
    val GREEN = Color(0xFF45A257)
    val VIOLET = Color(0xFF8D6CC9)
}

/** Trait de crayon : chaque ligne repassée deux fois, avec un léger tremblé. */
internal class Pencil(val scope: DrawScope, val color: Color, val rnd: Random, private val dark: Boolean, private val strength: Float = 1f) {
    val d = scope.density

    /** Il a neigé : les toits sont blancs ([roofInk]). */
    var snowyRoofs = false

    /**
     * Le même crayon dans une autre couleur, adoucie pour rester en fond (plus claire en thème sombre), plus ou moins
     * appuyée selon le réglage du dessin ([strength]).
     */
    fun ink(crayon: Color): Pencil = Pencil(
        scope,
        if (dark) lerp(crayon, Color.White, 0.12f).copy(alpha = (0.6f * strength).coerceAtMost(0.92f)) else crayon.copy(alpha = (0.5f * strength).coerceAtMost(0.9f)),
        rnd,
        dark,
        strength,
    ).also { it.snowyRoofs = snowyRoofs }

    /** Le crayon d'un toit : sa couleur, ou la neige. */
    fun roofInk(crayon: Color): Pencil = ink(if (snowyRoofs) Crayon.SNOW else crayon)

    fun jitter(amount: Float) = (rnd.nextFloat() * 2 - 1) * amount * d

    fun stroke(points: List<Offset>, width: Float = 1.1f, passes: Int = 2, close: Boolean = false, shake: Float = 0.7f) {
        if (points.size < 2) return
        repeat(passes) { pass ->
            val path = Path()
            points.forEachIndexed { i, p ->
                val q = Offset(p.x + jitter(shake), p.y + jitter(shake))
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

    /**
     * Hachures parallèles à 45° dans [shape], espacées de [spacing] dp, limitées à [area] : montantes vers la droite,
     * ou descendantes ([rising] faux, pour croiser d'autres hachures).
     */
    fun hatch(shape: Path, area: Rect, spacing: Float, width: Float = 0.8f, rising: Boolean = true) {
        if (area.width <= 0f || area.height <= 0f) return
        scope.clipPath(shape) {
            var x = area.left - area.height
            while (x < area.right) {
                if (rising) {
                    line(Offset(x, area.bottom), Offset(x + area.height, area.top), width, passes = 1, wobble = 0.4f)
                } else {
                    line(Offset(x, area.top), Offset(x + area.height, area.bottom), width, passes = 1, wobble = 0.4f)
                }
                x += spacing * d
            }
        }
    }

    /** Gomme : ce qui est derrière la forme disparaît (un nuage cache le soleil, un bonhomme la montagne). */
    fun erase(shape: Path) = scope.drawPath(shape, Color.Black, blendMode = BlendMode.Clear)

    /** Gomme passée légèrement : ce qui est derrière pâlit (le brouillard), d'autant plus que [amount] approche de 1. */
    fun fade(shape: Path, amount: Float) = scope.drawPath(shape, Color.Black.copy(alpha = amount), blendMode = BlendMode.DstOut)
}

/** Les rayons du soleil : aucun (dans la brume), droits, courts, ondulés, ou longs et courts en alternance. */
internal enum class Rays { NONE, STRAIGHT, SHORT, WAVY, LONG }

internal fun DrawScope.sun(pencil: Pencil, center: Offset, radius: Float, rays: Rays = Rays.STRAIGHT, color: Color = Crayon.SUN) {
    val gold = pencil.ink(color)
    val disc = Path().apply { addOval(Rect(center, radius)) }
    gold.outline(disc, width = 1.4f)
    gold.hatch(disc, Rect(center, radius), spacing = if (rays != Rays.NONE) 3.5f else 5f, width = 0.7f)
    val u = density
    fun at(a: Double, r: Float) = Offset(center.x + (r * cos(a)).toFloat(), center.y + (r * sin(a)).toFloat())
    when (rays) {
        Rays.NONE -> Unit
        Rays.STRAIGHT, Rays.SHORT -> for (k in 0 until 12) {
            val a = (k * 30.0 + 8) * PI / 180
            val long = if (rays == Rays.SHORT) 9f else if (k % 2 == 0) 17f else 11f
            gold.line(at(a, radius + 6f * u), at(a, radius + long * u))
        }
        Rays.WAVY -> for (k in 0 until 10) {
            val a = (k * 36.0 + 12) * PI / 180
            val points = (0..8).map { i ->
                val r = radius + (6f + 1.6f * i) * u
                val wiggle = sin(i * 1.6) * 0.09
                at(a + wiggle, r)
            }
            gold.stroke(points, width = 1.1f)
        }
        Rays.LONG -> for (k in 0 until 16) {
            val a = (k * 22.5 + 5) * PI / 180
            val long = if (k % 2 == 0) 24f else 10f
            gold.line(at(a, radius + 5f * u), at(a, radius + long * u), width = if (k % 2 == 0) 1.2f else 0.9f)
        }
    }
}

/**
 * La Lune dans sa phase du jour : rien à la nouvelle lune, un fin croissant, un quartier, une lune gibbeuse ou pleine
 * (avec ses mers). Côté éclairé à droite quand elle croît, dans l'hémisphère nord ; l'inverse au sud.
 */
private fun DrawScope.moon(pencil: Pencil, center: Offset, radius: Float, age: Double, southern: Boolean) {
    val lit = SketchScenes.moonIllumination(age).toFloat()
    if (lit < 0.03f) return
    val gold = pencil.ink(Crayon.MOON)
    val disc = Path().apply { addOval(Rect(center, radius)) }
    if (lit > 0.97f) {
        gold.outline(disc, width = 1.3f)
        gold.hatch(disc, Rect(center, radius), spacing = 5f, width = 0.6f)
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
    gold.outline(shape, width = 1.3f)
    gold.hatch(shape, Rect(center, radius), spacing = 3f)
}

/** Petite étoile en croix. */
private fun star(gold: Pencil, c: Offset, r: Float) {
    gold.line(c + Offset(-r, 0f), c + Offset(r, 0f), passes = 1, width = 1.2f)
    gold.line(c + Offset(0f, -r), c + Offset(0f, r), passes = 1, width = 1.2f)
}

/** La nuit claire : la Lune, et des étoiles en croix, la Grande Ourse, ou une poussière d'étoiles (la Voie lactée). */
private fun DrawScope.night(pencil: Pencil, center: Offset, w: Float, h: Float, moonAge: Double, southern: Boolean, variant: Int, twilight: Boolean = false) {
    val u = density
    val gold = pencil.ink(Crayon.MOON)
    val noMoon = SketchScenes.moonIllumination(moonAge) < 0.03
    // À l'aube et au crépuscule, seulement les premières étoiles, en haut du ciel.
    if (twilight) {
        moon(pencil, center, 14f * u, moonAge, southern)
        listOf(0.44f to 0.37f, 0.70f to 0.36f).forEach { (x, y) -> star(gold, Offset(w * x, h * y), 3.5f * u) }
        return
    }
    when (variant) {
        0 -> {
            moon(pencil, center, 15f * u, moonAge, southern)
            val stars = mutableListOf(0.43f to 0.36f, 0.47f to 0.62f, 0.68f to 0.34f, 0.72f to 0.58f, 0.62f to 0.66f)
            // Sans lune, le ciel est plus étoilé.
            if (noMoon) stars += listOf(0.55f to 0.40f, 0.52f to 0.54f, 0.64f to 0.47f)
            stars.forEach { (x, y) -> star(gold, Offset(w * x, h * y), 4f * u) }
        }
        1 -> {
            moon(pencil, center + Offset(-w * 0.08f, 0f), 14f * u, moonAge, southern)
            // La Grande Ourse (au sud, la Croix du Sud), reliée en pointillé.
            val shape = if (southern) {
                listOf(0f to -2.2f, 0f to 2.2f, -1.6f to 0f, 1.4f to -0.3f)
            } else {
                listOf(0f to 0f, 1f to -0.25f, 1.9f to -0.2f, 2.8f to 0.1f, 2.9f to 0.9f, 4.1f to 1.0f, 4.2f to 0f, 2.8f to 0.1f)
            }
            val origin = Offset(w * if (southern) 0.70f else 0.60f, h * 0.43f)
            val points = shape.map { (x, y) -> origin + Offset(x * 9f * u, y * 9f * u) }
            val links = if (southern) listOf(0 to 1, 2 to 3) else points.indices.toList().zipWithNext()
            links.forEach { (a, b) ->
                val p = points[a]
                val q = points[b]
                var t = 0.18f
                while (t < 0.82f) {
                    gold.line(p + (q - p) * t, p + (q - p) * (t + 0.08f), width = 0.6f, passes = 1, wobble = 0.1f)
                    t += 0.2f
                }
            }
            points.distinct().forEach { star(gold, it, 2.8f * u) }
            listOf(0.45f to 0.60f, 0.76f to 0.60f).forEach { (x, y) -> star(gold, Offset(w * x, h * y), 3.5f * u) }
        }
        else -> {
            moon(pencil, center + Offset(w * 0.12f, h * 0.05f), 12f * u, moonAge, southern)
            // Une bande d'étoiles fines qui traverse le ciel en biais.
            repeat(if (noMoon) 46 else 32) {
                val t = pencil.rnd.nextFloat()
                val across = (pencil.rnd.nextFloat() - 0.5f) * 0.10f
                val p = Offset(w * (0.40f + 0.38f * t + across * 0.5f), h * (0.62f - 0.26f * t + across))
                gold.dot(p, 0.4f + pencil.rnd.nextFloat() * 0.6f)
            }
            listOf(0.44f to 0.40f, 0.60f to 0.37f, 0.52f to 0.58f, 0.75f to 0.43f).forEach { (x, y) -> star(gold, Offset(w * x, h * y), 3.5f * u) }
        }
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
    pencil.ink(Crayon.CLOUD).hatch(shape, Rect(bounds.left, top, bounds.right, bounds.bottom), spacing = (6.5f - 2f * shade).coerceAtLeast(3f))
}

/** Couche de nuages d'un bord à l'autre du ciel (ciel couvert) : une longue bande à petites bosses, grisée. */
private fun DrawScope.stratus(pencil: Pencil, left: Float, right: Float, y: Float, thickness: Float) {
    val shape = Path().apply { addRoundRect(RoundRect(left, y - thickness / 2, right, y + thickness / 2, thickness / 2, thickness / 2)) }
    var x = left + thickness
    while (x < right - thickness) {
        val r = thickness * (0.55f + 0.25f * pencil.rnd.nextFloat())
        shape.op(shape, Path().apply { addOval(Rect(Offset(x, y - thickness * 0.35f), r)) }, PathOperation.Union)
        x += thickness * 1.3f
    }
    pencil.erase(shape)
    pencil.outline(shape, width = 1.2f)
    pencil.ink(Crayon.CLOUD).hatch(shape, shape.getBounds(), spacing = 3.5f)
}

/** Cirrus : quelques longs filaments recourbés, très haut (« queues de chat »). */
private fun DrawScope.cirrus(pencil: Pencil, at: Offset, length: Float) {
    val u = density
    val ink = pencil.ink(Crayon.CLOUD)
    for (k in 0..2) {
        val start = at + Offset(-k * 6f * u, k * 7f * u)
        val points = (0..10).map { i ->
            val t = i / 10f
            start + Offset(length * t * (1 - 0.15f * k), -(t * t) * 6f * u)
        }
        ink.stroke(points, width = 0.9f, passes = 1)
    }
}

/** Cumulonimbus d'orage : une tour de nuages qui monte et s'étale tout en haut en enclume. */
private fun DrawScope.cumulonimbus(pencil: Pencil, center: Offset, width: Float) {
    val shape = Path().apply {
        addOval(Rect(center + Offset(-width * 0.2f, 0f), width * 0.2f))
        op(this, Path().apply { addOval(Rect(center + Offset(width * 0.17f, -width * 0.02f), width * 0.2f)) }, PathOperation.Union)
        op(this, Path().apply { addOval(Rect(center + Offset(-width * 0.02f, -width * 0.18f), width * 0.19f)) }, PathOperation.Union)
        // Les bourgeonnements de la tour, de plus en plus haut.
        op(this, Path().apply { addOval(Rect(center + Offset(-width * 0.17f, -width * 0.16f), width * 0.13f)) }, PathOperation.Union)
        op(this, Path().apply { addOval(Rect(center + Offset(width * 0.15f, -width * 0.2f), width * 0.13f)) }, PathOperation.Union)
        op(this, Path().apply { addRect(Rect(center.x - width * 0.32f, center.y, center.x + width * 0.3f, center.y + width * 0.14f)) }, PathOperation.Union)
        // L'enclume : plate dessus, étirée vers la droite par le vent d'altitude, posée sur la tour.
        op(
            this,
            Path().apply {
                moveTo(center.x - width * 0.42f, center.y - width * 0.32f)
                quadraticTo(center.x - width * 0.1f, center.y - width * 0.46f, center.x + width * 0.62f, center.y - width * 0.43f)
                quadraticTo(center.x + width * 0.36f, center.y - width * 0.34f, center.x + width * 0.2f, center.y - width * 0.24f)
                lineTo(center.x - width * 0.22f, center.y - width * 0.24f)
                quadraticTo(center.x - width * 0.28f, center.y - width * 0.3f, center.x - width * 0.42f, center.y - width * 0.32f)
                close()
            },
            PathOperation.Union,
        )
    }
    pencil.erase(shape)
    pencil.outline(shape, width = 1.3f)
    val b = shape.getBounds()
    pencil.ink(Crayon.CLOUD).hatch(shape, Rect(b.left, b.top + b.height * 0.3f, b.right, b.bottom), spacing = 3f)
}

/** Ce qui tombe : [y0] dans la bande de [top] à [top] + [span], descendu de [fall], et qui repart d'en haut en sortant. */
private fun fallen(y0: Float, top: Float, span: Float, fall: Float): Float = top + (((y0 - top + fall) % span) + span) % span

/**
 * Pluie, de la bruine à la pluie forte : [style] 0 en traits obliques, 1 chassée par le vent, 2 en gouttes avec des
 * ronds dans les flaques. Verglaçante : quelques cristaux de glace parmi les gouttes. Elle penche avec le vent
 * ([lean]) et tombe au fil de l'animation ([phase]).
 */
private fun DrawScope.rain(
    pencil: Pencil,
    w: Float,
    h: Float,
    from: Float,
    to: Float,
    wx: SketchWeather,
    style: Int,
    drop: Float = 0f,
    phase: Float = 0f,
    lean: Float = 0f,
) {
    val u = density
    val blue = pencil.ink(Crayon.RAIN)
    val top = 0.61f + drop
    if (wx.drizzle) {
        // Bruine : de tout petits tirets serrés.
        val fall = phase * 25 * 0.045f
        var k = 0
        var x = from
        while (x <= to) {
            for (row in 0..2) {
                val y = h * fallen(top + 0.045f * row + if (k % 2 == 0) 0f else 0.02f, top, 0.135f, fall)
                val p = Offset(w * x + pencil.jitter(2f), y)
                blue.line(p, p + Offset((-1f + 2f * lean) * u, 3f * u), width = 1f, passes = 1, wobble = 0.1f)
            }
            x += 0.018f
            k++
        }
        if (wx.freezing) iceCrystals(pencil, w, h, from, to, drop, phase)
        return
    }
    val spacing = listOf(0.032f, 0.024f, 0.018f)[wx.intensity]
    val rows = if (wx.intensity == 2) 3 else 2
    val length = (10f + 3f * wx.intensity) * u
    // Penchée par le vent : vers la gauche par défaut, vers la droite quand il souffle de l'ouest.
    val dx = -(if (style == 1) length * 0.75f else length * 0.36f) + lean * length * 0.9f
    val span = 0.065f * rows
    val fall = phase * 40 * 0.065f
    var k = 0
    var x = from
    while (x <= to) {
        for (row in 0 until rows) {
            if ((row + k) % 2 == 1 && rows == 2) continue
            val y0 = top + 0.065f * row
            val y = fallen(y0, top, span, fall)
            val p = Offset(w * x + pencil.jitter(3f) + dx / length * (y - y0) * h, h * y)
            if (style == 2) drop(pencil, p, (1.8f + 0.4f * wx.intensity) * u) else blue.line(p, p + Offset(dx, length), passes = 1, width = 1.2f)
        }
        x += spacing
        k++
    }
    // Le vent : des traits de rafale qui s'enroulent, à gauche des nuages.
    if (style == 1) {
        listOf(0.53f to 0f, 0.59f to 0.03f, 0.65f to -0.01f).forEach { (y, shift) ->
            val start = Offset(w * (0.37f + shift), h * y)
            val streak = w * 0.08f
            val curl = start + Offset(streak, 0f)
            val points = (0..6).map { i -> start + Offset(streak * i / 6, (sin(i * 0.9) * 1.2 * u).toFloat()) } +
                (1..8).map { i ->
                    val a = -PI / 2 + 1.6 * PI * i / 8
                    curl + Offset((3.5f * u * cos(a)).toFloat(), 3.5f * u + (3.5f * u * sin(a)).toFloat())
                }
            pencil.stroke(points, width = 0.9f, passes = 1)
        }
    }
    // Des ronds dans les flaques, au premier plan, qui s'élargissent.
    if (style == 2) {
        listOf(0.46f, 0.6f, 0.71f).forEachIndexed { i, px ->
            val c = Offset(w * px, h * 0.985f)
            val grow = 1f + 0.3f * ((phase * 12 + i * 0.33f) % 1f)
            blue.outline(Path().apply { addOval(Rect(c.x - 5f * u * grow, c.y - 1.4f * u * grow, c.x + 5f * u * grow, c.y + 1.4f * u * grow)) }, width = 0.7f, passes = 1)
            blue.outline(Path().apply { addOval(Rect(c.x - 2.4f * u, c.y - 0.7f * u, c.x + 2.4f * u, c.y + 0.7f * u)) }, width = 0.6f, passes = 1)
        }
    }
    if (wx.freezing) iceCrystals(pencil, w, h, from, to, drop, phase)
}

/** Une goutte d'eau : pointue en haut, ronde en bas. */
private fun DrawScope.drop(pencil: Pencil, top: Offset, r: Float) {
    val shape = Path().apply {
        moveTo(top.x, top.y)
        cubicTo(top.x + r * 0.4f, top.y + r * 1.4f, top.x + r * 1.2f, top.y + r * 2.2f, top.x, top.y + r * 3f)
        cubicTo(top.x - r * 1.2f, top.y + r * 2.2f, top.x - r * 0.4f, top.y + r * 1.4f, top.x, top.y)
        close()
    }
    val blue = pencil.ink(Crayon.RAIN)
    blue.outline(shape, width = 1f, passes = 1)
    blue.hatch(shape, shape.getBounds(), spacing = 1.2f, width = 0.6f)
}

/** Pluie verglaçante : de petits cristaux de glace parmi les gouttes. */
private fun DrawScope.iceCrystals(pencil: Pencil, w: Float, h: Float, from: Float, to: Float, drop: Float, phase: Float) {
    var x = from + 0.012f
    var k = 0
    val top = 0.66f + drop
    while (x < to) {
        val y = fallen(0.70f + drop + if (k % 2 == 0) 0f else 0.04f, top, 0.10f, phase * 12 * 0.05f)
        flake(pencil, Offset(w * x, h * y), 2.4f * density, branches = false)
        x += 0.06f
        k++
    }
}

/** Un flocon à six branches ; avec [branches], de petites dents au bout (un gros flocon étoilé). */
private fun DrawScope.flake(pencil: Pencil, c: Offset, r: Float, branches: Boolean) {
    val ink = pencil.ink(Crayon.SNOW)
    for (a in listOf(0.0, 60.0, 120.0)) {
        val dx = (r * cos(a * PI / 180)).toFloat()
        val dy = (r * sin(a * PI / 180)).toFloat()
        ink.line(c + Offset(-dx, -dy), c + Offset(dx, dy), width = 1.1f, passes = 1, wobble = 0.2f)
    }
    if (!branches) return
    for (k in 0 until 6) {
        val a = k * PI / 3
        val tip = c + Offset((r * 0.62f * cos(a)).toFloat(), (r * 0.62f * sin(a)).toFloat())
        listOf(-0.6, 0.6).forEach { da ->
            ink.line(tip, tip + Offset((r * 0.35f * cos(a + da)).toFloat(), (r * 0.35f * sin(a + da)).toFloat()), width = 0.8f, passes = 1, wobble = 0.1f)
        }
    }
}

/**
 * Neige : [variant] 0 en petits flocons étoilés, 1 en gros flocons à branches, 2 en flocons ronds ; plus ou moins
 * serrée selon l'intensité. Neige en grains : des points.
 */
private fun DrawScope.snow(
    pencil: Pencil,
    w: Float,
    h: Float,
    from: Float,
    to: Float,
    wx: SketchWeather,
    variant: Int,
    drop: Float = 0f,
    phase: Float = 0f,
    lean: Float = 0f,
) {
    val u = density
    val count = listOf(7, 10, 15)[wx.intensity] + if (variant == 2) 4 else if (variant == 1) -3 else 0
    val step = (to - from) / count
    // Les flocons descendent doucement en se balançant, poussés par le vent.
    val top = 0.61f + drop
    val fall = phase * 10 * 0.045f
    for (k in 0 until count) {
        val y = fallen(0.63f + drop + (k % 3) * 0.045f, top, 0.135f, fall)
        val sway = (sin(2 * PI * (phase * 4 + k * 0.37)).toFloat() * 2.5f + lean * 6f * (y - top) / 0.135f) * u
        val c = Offset(w * (from + step * (k + 0.5f)) + pencil.jitter(4f) + sway, h * y + pencil.jitter(2f))
        when {
            wx.grains -> pencil.ink(Crayon.SNOW).dot(c, 1.1f)
            variant == 1 -> flake(pencil, c, 5.2f * u, branches = true)
            variant == 2 -> {
                val r = (1.3f + pencil.rnd.nextFloat() * 1.6f) * u
                pencil.ink(Crayon.SNOW).outline(Path().apply { addOval(Rect(c, r)) }, width = 0.9f, passes = 1)
            }
            else -> flake(pencil, c, 3.2f * u, branches = false)
        }
    }
}

/** L'éclair ; [forked] : il se divise en deux. */
private fun DrawScope.lightning(pencil: Pencil, top: Offset, height: Float, forked: Boolean = false) {
    val s = height / 4
    val bolt = pencil.ink(Crayon.BOLT)
    val knee = top + Offset(s * 0.1f, s * 1.5f)
    bolt.stroke(listOf(top, top + Offset(-s * 0.6f, s * 1.6f), knee, top + Offset(-s * 0.7f, s * 3.6f)), width = 2.2f)
    if (forked) bolt.stroke(listOf(knee, knee + Offset(s * 0.7f, s * 0.9f), knee + Offset(s * 0.45f, s * 1.1f), knee + Offset(s * 1.1f, s * 2.1f)), width = 1.5f)
}

/** Brouillard dans le ciel : des bandes ondulées. */
private fun DrawScope.fog(pencil: Pencil, w: Float, h: Float) {
    for (k in 0 until 4) {
        val y = h * (0.44f + 0.08f * k)
        val x0 = w * (0.39f + 0.03f * (k % 2))
        val points = (0..10).map { i -> Offset(x0 + (w * 0.33f) * i / 10f, y + (sin(i * 1.3 + k) * 2.5 * density).toFloat()) }
        pencil.stroke(points, width = 1.1f)
    }
}

/**
 * Brouillard de vallée, ou mer de nuages vue d'en haut : une nappe aux bords moutonnés qui noie le bas du relief, les
 * sommets en sortent. Au sommet, la nappe est loin sous le lieu.
 */
private fun DrawScope.seaOfClouds(pencil: Pencil, t: SketchTerrain, w: Float, h: Float) {
    val u = density
    val highest = t.ridge.filter { it.first in 0.3f..0.8f }.maxOf { it.second }
    val level = when {
        t.relief == Relief.SUMMIT -> t.heightAt(t.placeX) * 0.55f
        t.relief.mountains -> minOf(highest * 0.45f, 0.11f)
        else -> 0.05f
    }
    val top = h * (FOOT - level)
    val shape = Path().apply {
        moveTo(0f, h)
        lineTo(0f, top)
        var x = 0f
        var k = 0
        while (x < w) {
            val r = (7f + 4f * ((k * 7) % 3)) * u
            quadraticTo(x + r, top - r * 0.9f, x + 2 * r, top)
            x += 2 * r
            k++
        }
        lineTo(w, h)
        close()
    }
    pencil.fade(shape, 0.82f)
    // Le dessus moutonné, et quelques traînées dans la nappe.
    var x = w * 0.30f
    var k = 0
    while (x < w * 0.78f) {
        val r = (6f + 3f * (k % 3)) * u
        pencil.stroke((0..6).map { i -> val a = PI + PI * i / 6; Offset(x + r + (r * cos(a)).toFloat(), top + 2f * u + (r * 0.8f * sin(a)).toFloat()) }, width = 1f, passes = 1)
        x += 2 * r
        k++
    }
    val ink = pencil.ink(Crayon.CLOUD)
    var y = top + 7f * u
    var row = 0
    while (y < h - 2f * u) {
        val x0 = w * (0.32f + 0.05f * (row % 2))
        ink.line(Offset(x0, y), Offset(x0 + w * 0.36f, y), width = 0.8f, passes = 1, wobble = 0.5f)
        y += 6f * u
        row++
    }
}

/** Brouillard épais : tout le paysage pâlit, et des bandes de brume d'un bout à l'autre. */
private fun DrawScope.thickFog(pencil: Pencil, w: Float, h: Float) {
    pencil.fade(Path().apply { addRect(Rect(0f, h * 0.36f, w, h)) }, 0.6f)
    for (k in 0 until 7) {
        val y = h * (0.45f + 0.075f * k)
        val x0 = w * (0.30f + 0.04f * (k % 3))
        val points = (0..12).map { i -> Offset(x0 + (w * 0.44f) * i / 12f, y + (sin(i * 1.1 + k * 2) * 2 * density).toFloat()) }
        pencil.stroke(points, width = 1f, passes = if (k % 2 == 0) 2 else 1)
    }
}

/** Arc-en-ciel : rouge, jaune, vert et bleu, de l'extérieur vers l'intérieur. */
private fun DrawScope.rainbow(pencil: Pencil, center: Offset, radius: Float) {
    val gap = 3f * density
    listOf(Crayon.RED, Crayon.YELLOW, Crayon.GREEN, Crayon.BLUE).forEachIndexed { k, color ->
        val r = radius - k * gap
        val points = (0..24).map { i ->
            val a = PI + PI * i / 24
            Offset(center.x + (r * cos(a)).toFloat(), center.y + (r * sin(a)).toFloat())
        }
        pencil.ink(color).stroke(points, width = 2f, passes = 1)
    }
}
