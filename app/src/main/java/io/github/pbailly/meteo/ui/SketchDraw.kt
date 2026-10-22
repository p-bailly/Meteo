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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/*
 * Le dessin aux crayons de couleur d'Aujourd'hui : le crayon, la boîte de couleurs, et le ciel. Chaque temps se dessine
 * de trois façons (SketchScene.skyVariant), plus ou moins fort selon le code météo (SketchWeather) ; le paysage et les
 * surprises sont dans SketchDecor.kt.
 */

/**
 * Hauteur du cadre du ciel : celle du texte du haut d'Aujourd'hui (le lieu, la température, le ressenti), en dp. Le
 * soleil, les nuages, la pluie et ce qui vole s'y placent, entre les écritures ; le dessin continue en dessous, où le
 * relief, le village et la rivière ont la place pour eux seuls.
 */
internal const val SKY_FRAME_DP = 215f

internal fun DrawScope.skyHeight() = minOf(size.height, SKY_FRAME_DP * density)

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
    layer(pencil, scene, isDay, moonAge, southern, month, part, phase)
    // Rien ne touche le texte de l'écran.
    pencil.clearText()
}

/** Une couche de la scène : le fixe ([SketchPart.STILL]) ou ce qui bouge ([SketchPart.MOVING]). */
private fun DrawScope.layer(
    pencil: Pencil,
    scene: SketchScene,
    isDay: Boolean,
    moonAge: Double,
    southern: Boolean,
    month: Int,
    part: SketchPart,
    phase: Float,
) {
    val still = part == SketchPart.STILL
    val moving = part == SketchPart.MOVING
    val w = size.width
    val h = size.height
    // Le ciel se place par rapport au texte de l'écran (en dp depuis le haut), le relief par rapport au bas.
    val sh = skyHeight()
    val u = density
    val v = scene.skyVariant
    // Le dessin occupe l'espace libre : entre la grande température (à gauche) et le ciel en toutes lettres (à
    // droite), sous la ligne du lieu et au-dessus du ressenti. Le soleil (ou la lune) à sa vraie place quand on la
    // connaît : à gauche, au milieu ou à droite selon la direction où l'on regarde, plus haut ou plus bas selon
    // l'heure ; à l'heure dorée, il est bas et passe derrière le relief. Jamais sur le texte : poussé à côté.
    val low = scene.light == Light.SUNRISE || scene.light == Light.SUNSET
    val body = if (isDay) scene.sun else scene.moon
    val wanted = when {
        body != null && low && isDay -> Offset(w * body.x, h * 0.64f)
        body != null -> Offset(w * body.x, sh * (0.62f - 0.22f * ((body.elevation - 6) / 40).toFloat().coerceIn(0f, 1f)))
        scene.light == Light.SUNRISE -> Offset(w * 0.47f, h * 0.64f)
        scene.light == Light.SUNSET -> Offset(w * 0.56f, h * 0.64f)
        else -> Offset(w * 0.57f, sh * 0.47f)
    }
    // Sans papier blanc autour du texte, rien ne gomme les rayons : ils s'écartent avec le soleil.
    val reach = if (pencil.blank) 24f else 36f
    val celestial = pencil.clear(wanted, reach * u, reach * u)
    if (still) {
        skyColor(pencil, scene, w, h, isDay, celestial)
        landscape(pencil, scene, w, h, month, isDay)
        if (scene.conditions.haze) haze(pencil, w, h)
        if (Surprise.FROST in scene.surprises) frost(pencil, w, h)
    }
    if (moving) {
        // Les aurores et les étoiles filantes derrière tout ; l'air qui tremble au-dessus du sol ; ce qui bouge dans le
        // paysage (fumée, éoliennes, train, bateaux, oiseaux).
        if (Surprise.AURORA in scene.surprises) aurora(pencil, w, sh, phase)
        if (Surprise.METEORS in scene.surprises) meteors(pencil, w, sh, phase)
        if (Surprise.HEAT in scene.surprises) shimmer(pencil, w, h, phase)
        animated(pencil, scene, w, h, sh, phase)
    }
    // La lune couchée ne se dessine pas.
    val moonUp = scene.moon?.let { it.elevation > -1 } ?: true
    val sunColor = when {
        Surprise.HEAT in scene.surprises -> Crayon.RED
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
        if (isDay) sunAt(at, radius, rays) else if (moonUp) moon(pencil, at, radius, moonAge, southern)
    }
    // Les nuages à leur hauteur : plus bas quand ce sont des nuages bas (ils accrochent les sommets). Ils glissent
    // doucement d'un côté à l'autre, d'autant plus qu'il y a du vent.
    val drop = scene.cloudDrop * h
    val sway = 0.010f + 0.015f * abs(scene.lean)
    val drift = (sin(2 * PI * phase).toFloat()) * sway
    // Un nuage ne passe jamais sur le texte, même au bout de son balancement ; celui des éclaircies suit le soleil
    // quand il a dû se pousser ([follow]).
    val moved = celestial - wanted
    fun cloudAt(x: Float, y: Float, width: Float, shade: Float, follow: Boolean = false) {
        val at = Offset(w * x, sh * y + drop) + if (follow) moved else Offset.Zero
        if (moving) cloud(pencil, pencil.clear(at, width * u * 0.56f + w * sway, width * u * 0.34f) + Offset(w * drift, 0f), width * u, shade)
    }
    // Au sommet, les nuages bas sont en dessous : une mer de nuages.
    val belowSummit = scene.terrain.relief == Relief.SUMMIT && scene.lowClouds && (scene.clouds?.mid ?: 1f) < 0.3f &&
        scene.sky in setOf(SketchSky.PARTLY, SketchSky.CLOUDY)
    // Des cirrus très haut, par beau temps.
    if (still && (scene.clouds?.high ?: 0f) >= 0.4f && scene.sky in setOf(SketchSky.CLEAR, SketchSky.PARTLY)) {
        cirrus(pencil, Offset(w * 0.66f, sh * 0.38f), w * 0.13f)
    }
    if (belowSummit) {
        if (still) {
            seaOfClouds(pencil, scene.terrain, w, h)
            light(celestial + Offset(0f, -6f * u), 14f * u)
            surprises(pencil, scene, w, h, sh)
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
                    if (still) cirrus(pencil, Offset(w * 0.68f, sh * 0.41f), w * 0.12f)
                }
            }
        } else if (still) {
            night(pencil, celestial, w, sh, moonAge, southern, v, twilight = scene.light == Light.DAWN || scene.light == Light.DUSK, moonUp = moonUp)
        }
        SketchSky.PARTLY -> {
            // Les nuages de beau temps se placent par rapport au soleil voulu, et le suivent s'il a dû se pousser.
            val wx = wanted.x / w
            when (v) {
                0 -> {
                    light(celestial + Offset(-12f * u, -6f * u), 14f * u)
                    cloudAt((wx + 0.04f).coerceIn(0.44f, 0.74f), 0.55f, 68f, shade = 0.5f, follow = true)
                }
                // Le nuage passe devant, à gauche.
                1 -> {
                    light(celestial + Offset(14f * u, -5f * u), 14f * u)
                    cloudAt((wx - 0.05f).coerceIn(0.44f, 0.74f), 0.56f, 60f, shade = 0.45f, follow = true)
                }
                // Deux petits cumulus de beau temps, le soleil entre eux.
                else -> {
                    light(celestial + Offset(0f, -3f * u), 13f * u)
                    cloudAt((wx - 0.12f).coerceAtLeast(0.43f), 0.55f, 44f, shade = 0.35f, follow = true)
                    cloudAt((wx + 0.13f).coerceAtMost(0.76f), 0.53f, 52f, shade = 0.45f, follow = true)
                }
            }
        }
        // Un voile de nuages hauts : le soleil pâle au travers, une couche fine tout en haut.
        SketchSky.CLOUDY -> if (scene.highVeil) {
            light(celestial + Offset(0f, 4f * u), 13f * u, Rays.NONE)
            if (moving) stratus(pencil, w * (0.39f + drift), w * (0.80f + drift), sh * 0.40f, 6f * u)
            if (still) cirrus(pencil, Offset(w * 0.42f, sh * 0.55f), w * 0.12f)
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
                if (moving) stratus(pencil, w * (0.39f + drift), w * (0.80f + drift), sh * 0.45f + drop, 9f * u)
                cloudAt(0.60f, 0.57f, 70f, shade = 0.8f)
            }
        }
        SketchSky.FOG -> if (still) {
            // Au sommet, toujours la mer de nuages, d'en haut.
            val mist = if (scene.terrain.relief == Relief.SUMMIT) 1 else v
            when (mist) {
                0 -> {
                    if (isDay) sunAt(celestial, 14f * u, Rays.NONE)
                    fog(pencil, w, sh)
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
        SketchSky.RAIN, SketchSky.SNOW, SketchSky.STORM -> precipitation(pencil, scene, isDay, moonAge, southern, w, h, sh, still, phase, drift)
    }
    if (still) surprises(pencil, scene, w, h, sh)
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
    sh: Float,
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
    val drop = if (Surprise.RAINBOW in scene.surprises) 0f else scene.cloudDrop * h
    val moving = !still
    val sway = 0.010f + 0.015f * abs(scene.lean)
    fun cloudAt(x: Float, y: Float, width: Float, shade: Float) {
        if (moving) cloud(pencil, pencil.clear(Offset(w * x, sh * y + drop), width * u * 0.56f + w * sway, width * u * 0.34f) + Offset(w * drift, 0f), width * u, shade)
    }
    var from = 0.42f
    var to = 0.66f
    when {
        Surprise.RAINBOW in scene.surprises -> {
            // Averse : un seul nuage, et l'arc-en-ciel à côté.
            // À l'opposé du soleil, d'autant plus bas que le soleil est haut.
            val opposite = scene.sun?.let { if (it.x >= 0.59f) 0.46f else 0.69f } ?: 0.69f
            val sunk = scene.sun?.let { (it.elevation / 42).toFloat().coerceIn(0f, 0.8f) } ?: 0f
            if (still) rainbow(pencil, Offset(w * opposite, h * (0.88f + 0.06f * sunk)), w * 0.12f)
            if (moving) cloud(pencil, Offset(w * (0.50f + drift), sh * 0.45f), 70f * u, shade = 0.9f)
            to = 0.58f
        }
        storm && v == 2 -> {
            if (moving) cumulonimbus(pencil, Offset(w * (0.57f + drift * 0.5f), sh * 0.57f + drop), 88f * u)
            from = 0.47f
            to = 0.68f
        }
        wx.showers && !storm -> {
            // Averse : un gros nuage, et le soleil (ou la lune) qui perce à côté.
            if (v != 2 && still) {
                val at = pencil.clear(Offset(w * 0.70f, sh * 0.45f + drop), 20f * u, 20f * u)
                if (isDay) sun(pencil, at, 11f * u, Rays.SHORT) else moon(pencil, at + Offset(w * 0.01f, 0f), 11f * u, moonAge, southern)
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
        SketchSky.SNOW -> snow(pencil, w, sh, from, to, wx, v, drop / sh, phase, scene.lean, ground = h)
        SketchSky.STORM -> {
            rain(pencil, w, sh, from, to, wx, style = if (v == 1) 1 else 0, drop = drop / sh, phase = phase, lean = scene.lean, ground = h)
            if (wx.hail) hail(pencil, w, sh, from, to, drop / sh, phase, ground = h)
            // L'éclair claque par moments (toujours là sur une image fixe, quand la phase vaut 0).
            if ((phase * 5) % 1f < 0.14f) {
                when (v) {
                    0 -> lightning(pencil, Offset(w * (0.58f + drift), sh * 0.60f + drop), 34f * u)
                    1 -> {
                        lightning(pencil, Offset(w * (0.50f + drift), sh * 0.58f + drop), 28f * u)
                        lightning(pencil, Offset(w * (0.68f + drift), sh * 0.62f + drop), 32f * u)
                    }
                    else -> lightning(pencil, Offset(w * (0.57f + drift * 0.5f), sh * 0.62f + drop), 38f * u, forked = true)
                }
            }
        }
        else -> rain(pencil, w, sh, from, to, wx, style = v, drop = drop / sh, phase = phase, lean = scene.lean, ground = h)
    }
}

/**
 * La couleur du ciel, d'un bord à l'autre au-dessus du relief et tout autour du texte de l'écran, comme un ciel
 * colorié : le bleu du beau temps (soutenu en haut, de plus en plus pâle vers l'horizon), le gris des jours de pluie,
 * le bleu de la nuit ; à l'heure dorée, du violet au rose, à l'orange et au jaune vers l'horizon, et une lueur autour
 * du soleil ; à l'aube et au crépuscule, du bleu de la nuit au rose et à l'orange ; le jaune du sable du Sahara. Chaque
 * couleur est appuyée plus fort au milieu de sa bande et se perd dans les voisines, en hachures croisées : les couleurs
 * se mélangent, sans limite tirée à la règle.
 */
private fun DrawScope.skyColor(pencil: Pencil, scene: SketchScene, w: Float, h: Float, isDay: Boolean, sun: Offset) {
    val wet = scene.sky == SketchSky.RAIN || scene.sky == SketchSky.SNOW || scene.sky == SketchSky.STORM
    val golden = isDay && (scene.light == Light.SUNRISE || scene.light == Light.SUNSET) && !wet && scene.sky != SketchSky.CLOUDY
    val twilight = !isDay && (scene.light == Light.DAWN || scene.light == Light.DUSK) && !wet
    // Chaque couleur : du haut au bas de sa bande (en fraction de la hauteur), son appui le plus fort, l'écart de ses
    // hachures en dp.
    class Tint(val color: Color, val from: Float, val to: Float, val pressure: Float, val spacing: Float)
    val tints = when {
        scene.sky == SketchSky.FOG -> emptyList()
        scene.conditions.dust -> listOf(Tint(Crayon.WHEAT, 0.24f, 0.80f, 0.8f, 5.5f), Tint(Crayon.EARTH, 0.55f, 1f, 0.6f, 7f))
        golden -> listOf(
            Tint(Crayon.VIOLET, 0.20f, 0.50f, 0.55f, 6.5f),
            Tint(Crayon.PINK, 0.36f, 0.66f, 0.75f, 5.5f),
            Tint(Crayon.ORANGE, 0.50f, 0.82f, 0.85f, 5f),
            Tint(Crayon.YELLOW, 0.66f, 1f, 0.95f, 4.5f),
        )
        twilight -> listOf(
            Tint(Crayon.NIGHT, 0.20f, 0.56f, 0.7f, 6f),
            Tint(Crayon.VIOLET, 0.38f, 0.72f, 0.7f, 5.5f),
            Tint(Crayon.PINK, 0.56f, 0.88f, 0.75f, 5.5f),
            Tint(Crayon.ORANGE, 0.74f, 1f, 0.7f, 5.5f),
        )
        wet || scene.sky == SketchSky.CLOUDY -> listOf(Tint(if (isDay) Crayon.CLOUD else Crayon.NIGHT, 0.20f, 0.95f, 0.45f, 7f))
        isDay -> listOf(Tint(Crayon.SKY, 0.20f, 0.86f, 0.55f, 6f))
        else -> listOf(Tint(Crayon.NIGHT, 0.20f, 1f, 0.6f, 6f))
    }
    if (tints.isEmpty()) return
    val u = density
    val rnd = Random(scene.seed)
    // Le ciel colorié : un haut et des côtés aux bords irréguliers, le relief en dessous.
    val top = h * tints.minOf { it.from } + 6f * u
    val steps = 18
    val upper = (0..steps).map { i ->
        Offset(w * (0.012f + 0.976f * i / steps), top + (rnd.nextFloat() - 0.5f) * 10f * u + (sin(i * 0.9) * 4f * u).toFloat())
    }
    val sides = (1..8).map { top + it * (h - top) / 8 }
    val area = polygon(
        upper +
            sides.map { y -> Offset(w * 0.988f + (rnd.nextFloat() - 0.5f) * 7f * u, y) } +
            sides.reversed().map { y -> Offset(w * 0.012f + (rnd.nextFloat() - 0.5f) * 7f * u, y) },
    )
    val sky = Path().apply { op(area, skyOver(scene.terrain, w, h), PathOperation.Intersect) }
    fun smooth(t: Float) = t.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
    // Une seule couleur pâlit vers l'horizon ; dans un dégradé, chacune monte et se perd dans les voisines.
    val alone = tints.size == 1
    tints.forEachIndexed { k, tint ->
        val y0 = h * tint.from
        val y1 = h * tint.to
        val pressure = { y: Float ->
            val t = (y - y0) / (y1 - y0)
            val edge = smooth(t / 0.3f) * (if (alone) 1f else smooth((1 - t) / 0.3f))
            val fade = if (alone) 1f - 0.85f * smooth((t - 0.25f) / 0.75f) else 1f
            tint.pressure * edge * fade
        }
        pencil.ink(tint.color).wash(sky, Rect(0f, y0, w, minOf(y1, h)), spacing = tint.spacing, rising = k % 2 == 0, pressure = pressure)
    }
    // À l'heure dorée, la lueur du soleil bas sur l'horizon, qui se perd autour de lui.
    if (golden) {
        for ((radius, pressure) in listOf(0.20f to 0.35f, 0.14f to 0.5f, 0.08f to 0.7f)) {
            val glow = Path().apply { op(sky, Path().apply { addOval(Rect(sun, w * radius)) }, PathOperation.Intersect) }
            pencil.ink(Crayon.YELLOW).pressed(pressure).hatch(glow, Rect(sun, w * radius), spacing = 5f, width = 0.7f, rising = false)
        }
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

/** Les crayons de couleur de la boîte, francs et lumineux (adoucis au trait par [Pencil.ink]). */
internal object Crayon {
    val FOREST = Color(0xFF2E9150)
    val MEADOW = Color(0xFF6DBA3A)
    val ROCK = Color(0xFF9A7A5E)
    val SNOW = Color(0xFF6AA6EC)
    val FAR = Color(0xFF7690D6)
    val WATER = Color(0xFF1D7FE3)
    val SUN = Color(0xFFFFA514)
    val MOON = Color(0xFFEBB728)
    val CLOUD = Color(0xFF6A86BC)
    val RAIN = Color(0xFF2273E0)
    val BOLT = Color(0xFFFFBF00)
    val ROOF = Color(0xFFE04A26)
    val DOME = Color(0xFF1F8F7C)
    val WOOD = Color(0xFFA66A32)
    val WHEAT = Color(0xFFEDB421)
    val EARTH = Color(0xFFA8744A)
    val AUTUMN = Color(0xFFF2861A)
    val RED = Color(0xFFE8392E)
    val ORANGE = Color(0xFFFF7A1F)
    val PINK = Color(0xFFF2689F)
    val YELLOW = Color(0xFFFFC81A)
    val BLUE = Color(0xFF3D8BEA)
    val GREEN = Color(0xFF3FAE55)
    val VIOLET = Color(0xFF8E5FDC)

    /** Le bleu du ciel de beau temps, et celui de la nuit. */
    val SKY = Color(0xFF4FA8F2)
    val NIGHT = Color(0xFF4253B8)

    /** Les reflets et les ombres des étages du relief, croisés par-dessus : vert tendre, vert sombre des sapins. */
    val LIME = Color(0xFFB4D82C)
    val PINE = Color(0xFF16704A)
}

/**
 * La deuxième couleur d'un étage du relief, en hachures croisées par-dessus la première : la lumière sur les prés,
 * l'ombre des sapins, le rouge des feuilles d'automne, le mauve des rochers à l'ombre. Les couleurs se mélangent et
 * chatoient, comme un vrai coloriage repassé.
 */
internal fun shadeOf(color: Color): Color? = when (color) {
    Crayon.MEADOW -> Crayon.LIME
    Crayon.FOREST -> Crayon.PINE
    Crayon.GREEN -> Crayon.LIME
    Crayon.AUTUMN -> Crayon.RED
    Crayon.WOOD -> Crayon.EARTH
    Crayon.ROCK -> Crayon.FAR
    Crayon.WHEAT -> Crayon.ORANGE
    Crayon.FAR -> Crayon.SKY
    Crayon.SNOW -> Crayon.SKY
    else -> null
}

/** Trait de crayon : chaque ligne repassée deux fois, avec un léger tremblé. */
internal class Pencil(val scope: DrawScope, val color: Color, val rnd: Random, private val dark: Boolean, private val strength: Float = 1f) {
    val d = scope.density

    /** Il a neigé : les toits sont blancs ([roofInk]). */
    var snowyRoofs = false

    /** La région du monde : on y bâtit des mosquées ou des églises, des toits plats ou de tuiles. */
    var culture = Culture.EUROPE

    /** La manière de bâtir de la région : clocher comtois, temple du Jura suisse, campanile de Provence. */
    var local = LocalStyle.NONE

    /**
     * Le texte de l'écran posé sur le dessin (en pixels du dessin) : le coloriage s'arrête à [TEXT_MARGIN_DP] de lui, et
     * le soleil, les nuages et ce qui vole se placent à côté ([clear]).
     */
    var text: List<Rect> = emptyList()

    /**
     * Du papier blanc autour du [text] ([clearText]) ; sans lui (Nimbus), le ciel passe sous les lettres, et seuls le
     * soleil, les nuages et ce qui vole s'écartent, rayons compris.
     */
    var blank = true

    /**
     * L'écriture à la main (noms des sommets), fournie par l'appli : du texte centré sur [at] (sa ligne de base), de
     * [size] dp ; sans elle, rien n'est écrit.
     */
    var write: ((scope: DrawScope, text: String, at: Offset, color: Color, size: Float) -> Unit)? = null

    /** Écrit [text] au crayon, centré au-dessus de [at]. */
    fun write(text: String, at: Offset, size: Float) {
        write?.invoke(scope, text, at, color.copy(alpha = (color.alpha * 2.2f).coerceAtMost(0.85f)), size)
    }

    /**
     * Le même crayon dans une autre couleur, adoucie pour rester en fond (plus claire en thème sombre), plus ou moins
     * appuyée selon le réglage du dessin ([strength]).
     */
    fun ink(crayon: Color): Pencil = Pencil(
        scope,
        if (dark) lerp(crayon, Color.White, 0.14f).copy(alpha = (0.7f * strength).coerceAtMost(0.95f)) else crayon.copy(alpha = (0.64f * strength).coerceAtMost(0.94f)),
        rnd,
        dark,
        strength,
    ).also {
        it.snowyRoofs = snowyRoofs
        it.write = write
        it.culture = culture
        it.local = local
        it.text = text
        it.blank = blank
    }

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
        // Un morceau tous les 5 dp environ : un trait court reste droit.
        val n = ((b - a).getDistance() / (5f * d)).toInt().coerceIn(1, 6)
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
            // Les hachures suivent une même grille sur tout le dessin : deux zones voisines se raccordent sans cassure.
            val step = spacing * d
            val anchor = if (rising) area.bottom else -area.top
            var x = kotlin.math.floor((area.left - area.height + anchor) / step) * step - anchor
            while (x < area.right) {
                if (rising) {
                    line(Offset(x, area.bottom), Offset(x + area.height, area.top), width, passes = 1, wobble = 0.4f)
                } else {
                    line(Offset(x, area.top), Offset(x + area.height, area.bottom), width, passes = 1, wobble = 0.4f)
                }
                x += step
            }
        }
    }

    /** Le même crayon, appuyé à [pressure] (de 0, il ne marque plus, à 1). */
    fun pressed(pressure: Float): Pencil = Pencil(scope, color.copy(alpha = color.alpha * pressure.coerceIn(0f, 1f)), rnd, dark, strength).also {
        it.snowyRoofs = snowyRoofs
        it.write = write
        it.culture = culture
        it.local = local
        it.text = text
        it.blank = blank
    }

    /**
     * Hachures dans [shape] appuyées plus ou moins fort selon la hauteur ([pressure] pour un y en pixels, de 0 à 1) :
     * un dégradé au crayon, par bandes de quelques dp sur les mêmes traits.
     */
    fun wash(shape: Path, area: Rect, spacing: Float, width: Float = 0.7f, rising: Boolean = true, pressure: (Float) -> Float) {
        val step = 5f * d
        var y = area.top
        while (y < area.bottom) {
            val bottom = minOf(y + step, area.bottom)
            val p = pressure((y + bottom) / 2)
            if (p > 0.03f) pressed(p).hatch(shape, Rect(area.left, y, area.right, bottom), spacing, width, rising)
            y = bottom
        }
    }

    /** Gomme : ce qui est derrière la forme disparaît (un nuage cache le soleil, un bonhomme la montagne). */
    fun erase(shape: Path) = scope.drawPath(shape, Color.Black, blendMode = BlendMode.Clear)

    /** Gomme passée légèrement : ce qui est derrière pâlit (le brouillard), d'autant plus que [amount] approche de 1. */
    fun fade(shape: Path, amount: Float) = scope.drawPath(shape, Color.Black.copy(alpha = amount), blendMode = BlendMode.DstOut)

    /** Le tour du texte de l'écran, à [margin] dp de lui, aux coins arrondis. */
    fun aroundText(margin: Float = TEXT_MARGIN_DP): Path = Path().apply {
        val m = margin * d
        text.forEach { addRoundRect(RoundRect(it.inflate(m), m, m)) }
    }

    /** Le rectangle [box] passe trop près du texte de l'écran. */
    fun nearText(box: Rect, margin: Float = TEXT_MARGIN_DP): Boolean = text.any { it.inflate(margin * d).overlaps(box) }

    /**
     * Où poser une chose de [rx] × [ry] pixels (demi-largeur et demi-hauteur) voulue en [at] sans toucher le texte de
     * l'écran : là même si c'est libre, sinon la place libre la plus proche (de côté d'abord, puis en biais, puis plus
     * bas ou plus haut), sans sortir de [within].
     */
    fun clear(at: Offset, rx: Float, ry: Float, within: Rect = Rect(0f, 0f, scope.size.width, scope.size.height)): Offset {
        if (text.isEmpty()) return at
        fun free(c: Offset) = Rect(c.x - rx, c.y - ry, c.x + rx, c.y + ry).let { box ->
            !nearText(box) && box.left >= within.left && box.right <= within.right && box.top >= within.top && box.bottom <= within.bottom
        }
        if (free(at)) return at
        val step = 3f * d
        for (k in 1..70) {
            val r = k * step
            val tries = listOf(Offset(r, 0f), Offset(-r, 0f), Offset(r, r * 0.4f), Offset(-r, r * 0.4f), Offset(0f, r * 0.5f), Offset(r, -r * 0.4f), Offset(-r, -r * 0.4f), Offset(0f, -r * 0.5f))
            tries.map { at + it }.firstOrNull(::free)?.let { return it }
        }
        return at
    }

    /**
     * La gomme autour du texte de l'écran : rien du dessin ne le touche. Son bord est adouci, comme un coloriage qui
     * s'arrête juste avant les lettres.
     */
    fun clearText() {
        if (text.isEmpty() || !blank) return
        fade(aroundText(TEXT_MARGIN_DP + 6f), 0.3f)
        fade(aroundText(TEXT_MARGIN_DP + 3f), 0.5f)
        erase(aroundText())
    }
}

/** Ce que le coloriage laisse de papier blanc autour du texte de l'écran, en dp. */
internal const val TEXT_MARGIN_DP = 5f

/** Les rayons du soleil : aucun (dans la brume), droits, courts, ondulés, ou longs et courts en alternance. */
internal enum class Rays { NONE, STRAIGHT, SHORT, WAVY, LONG }

internal fun DrawScope.sun(pencil: Pencil, center: Offset, radius: Float, rays: Rays = Rays.STRAIGHT, color: Color = Crayon.SUN) {
    val gold = pencil.ink(color)
    // Les rayons, un sur deux d'un jaune plus clair.
    val light = pencil.ink(if (color == Crayon.YELLOW) Crayon.SUN else Crayon.YELLOW)
    val disc = Path().apply { addOval(Rect(center, radius)) }
    // Le soleil sur le papier blanc, pas sur le bleu du ciel ; son cœur croisé de jaune.
    pencil.erase(Path().apply { addOval(Rect(center, radius + 1.5f * density)) })
    gold.outline(disc, width = 1.5f)
    gold.hatch(disc, Rect(center, radius), spacing = if (rays != Rays.NONE) 3.2f else 4.5f, width = 0.8f)
    light.hatch(disc, Rect(center, radius), spacing = 4f, width = 0.7f, rising = false)
    val u = density
    fun at(a: Double, r: Float) = Offset(center.x + (r * cos(a)).toFloat(), center.y + (r * sin(a)).toFloat())
    when (rays) {
        Rays.NONE -> Unit
        Rays.STRAIGHT, Rays.SHORT -> for (k in 0 until 12) {
            val a = (k * 30.0 + 8) * PI / 180
            val long = if (rays == Rays.SHORT) 9f else if (k % 2 == 0) 17f else 11f
            (if (k % 2 == 0) gold else light).line(at(a, radius + 6f * u), at(a, radius + long * u), width = 1.3f)
        }
        Rays.WAVY -> for (k in 0 until 10) {
            val a = (k * 36.0 + 12) * PI / 180
            val points = (0..8).map { i ->
                val r = radius + (6f + 1.6f * i) * u
                val wiggle = sin(i * 1.6) * 0.09
                at(a + wiggle, r)
            }
            (if (k % 2 == 0) gold else light).stroke(points, width = 1.2f)
        }
        Rays.LONG -> for (k in 0 until 16) {
            val a = (k * 22.5 + 5) * PI / 180
            val long = if (k % 2 == 0) 24f else 10f
            (if (k % 2 == 0) gold else light).line(at(a, radius + 5f * u), at(a, radius + long * u), width = if (k % 2 == 0) 1.3f else 1f)
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
    val pale = pencil.ink(Crayon.YELLOW)
    val disc = Path().apply { addOval(Rect(center, radius)) }
    if (lit > 0.97f) {
        pencil.erase(disc)
        gold.outline(disc, width = 1.3f)
        gold.hatch(disc, Rect(center, radius), spacing = 5f, width = 0.6f)
        pale.hatch(disc, Rect(center, radius), spacing = 5.5f, width = 0.6f, rising = false)
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
    pencil.erase(shape)
    gold.outline(shape, width = 1.3f)
    gold.hatch(shape, Rect(center, radius), spacing = 3f)
    pale.hatch(shape, Rect(center, radius), spacing = 4.5f, width = 0.6f, rising = false)
}

/** Petite étoile en croix. */
private fun star(gold: Pencil, c: Offset, r: Float) {
    gold.line(c + Offset(-r, 0f), c + Offset(r, 0f), passes = 1, width = 1.2f)
    gold.line(c + Offset(0f, -r), c + Offset(0f, r), passes = 1, width = 1.2f)
}

/** La nuit claire : la Lune, et des étoiles en croix, la Grande Ourse, ou une poussière d'étoiles (la Voie lactée). */
private fun DrawScope.night(
    pencil: Pencil,
    center: Offset,
    w: Float,
    h: Float,
    moonAge: Double,
    southern: Boolean,
    variant: Int,
    twilight: Boolean = false,
    moonUp: Boolean = true,
) {
    val u = density
    val gold = pencil.ink(Crayon.MOON)
    val noMoon = !moonUp || SketchScenes.moonIllumination(moonAge) < 0.03
    // La lune couchée : seulement les étoiles.
    fun moon(pencil: Pencil, center: Offset, radius: Float, age: Double, southern: Boolean) {
        if (moonUp) this.moon(pencil, center, radius, age, southern)
    }
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
    // Tout en bas, il chatoie : un peu de mauve sous un nuage clair, du bleu sombre sous un nuage de pluie.
    val under = Rect(bounds.left, bounds.bottom - bounds.height * 0.3f, bounds.right, bounds.bottom)
    pencil.ink(if (shade < 0.75f) Crayon.VIOLET else Crayon.NIGHT).pressed(0.6f).hatch(shape, under, spacing = 5f, width = 0.6f, rising = false)
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
    ground: Float = h,
) {
    val u = density
    val blue = pencil.ink(Crayon.RAIN)
    val top = 0.61f + drop
    if (wx.drizzle) {
        // Bruine : de tout petits tirets serrés.
        val fall = phase * 25 * 0.045f
        val rows = ((ground * 0.84f / h - top) / 0.045f).toInt().coerceAtLeast(3)
        var k = 0
        var x = from
        while (x <= to) {
            for (row in 0 until rows) {
                val y = h * fallen(top + 0.045f * row + if (k % 2 == 0) 0f else 0.02f, top, 0.045f * rows, fall)
                val p = Offset(w * x + pencil.jitter(2f), y)
                if (p.x > w * 0.60f && p.y > h * 0.66f) continue
                blue.line(p, p + Offset((-1f + 2f * lean) * u, 3f * u), width = 1f, passes = 1, wobble = 0.1f)
            }
            x += 0.018f
            k++
        }
        if (wx.freezing) iceCrystals(pencil, w, h, from, to, drop, phase)
        return
    }
    val spacing = listOf(0.032f, 0.024f, 0.018f)[wx.intensity]
    val dense = wx.intensity == 2
    val length = (10f + 3f * wx.intensity) * u
    // Penchée par le vent : vers la gauche par défaut, vers la droite quand il souffle de l'ouest.
    val dx = -(if (style == 1) length * 0.75f else length * 0.36f) + lean * length * 0.9f
    // Elle tombe jusqu'au relief, en laissant libre le texte de droite (le ciel en toutes lettres, les extrêmes).
    val rows = ((ground * 0.84f / h - top) / 0.065f).toInt().coerceAtLeast(2)
    val span = 0.065f * rows
    val fall = phase * 40 * 0.065f
    var k = 0
    var x = from
    while (x <= to) {
        for (row in 0 until rows) {
            if (!dense && (row + k) % 2 == 1) continue
            val y0 = top + 0.065f * row
            val y = fallen(y0, top, span, fall)
            val p = Offset(w * x + pencil.jitter(3f) + dx / length * (y - y0) * h, h * y)
            if (p.x > w * 0.60f && p.y > h * 0.66f) continue
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
            val c = Offset(w * px, ground * 0.985f)
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
    ground: Float = h,
) {
    val u = density
    val count = listOf(7, 10, 15)[wx.intensity] + if (variant == 2) 4 else if (variant == 1) -3 else 0
    val step = (to - from) / count
    // Les flocons descendent doucement en se balançant, poussés par le vent, jusqu'au relief (pas sur le texte de
    // droite) : trois rangs de flocons sur la hauteur du ciel, autant en dessous.
    val top = 0.61f + drop
    val rows = ((ground * 0.84f / h - top) / 0.045f).toInt().coerceAtLeast(3)
    val span = 0.045f * rows
    val fall = phase * 10 * 0.045f
    for (k in 0 until count * rows / 3) {
        val column = k % count
        val y = fallen(0.63f + drop + (k % rows) * 0.045f, top, span, fall)
        val sway = (sin(2 * PI * (phase * 4 + k * 0.37)).toFloat() * 2.5f + lean * 6f * (y - top) / span) * u
        val c = Offset(w * (from + step * (column + 0.5f)) + pencil.jitter(4f) + sway, h * y + pencil.jitter(2f))
        if (c.x > w * 0.60f && c.y > h * 0.66f) continue
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

/**
 * Grêle d'orage : des grêlons ronds et blancs qui tombent parmi la pluie et rebondissent sur le sol, où d'autres
 * restent posés.
 */
private fun DrawScope.hail(pencil: Pencil, w: Float, h: Float, from: Float, to: Float, drop: Float, phase: Float, ground: Float) {
    val u = density
    val ice = pencil.ink(Crayon.SNOW)
    val top = 0.63f + drop
    val rows = ((ground * 0.84f / h - top) / 0.06f).toInt().coerceAtLeast(2)
    val span = 0.06f * rows
    var x = from + 0.01f
    var k = 0
    while (x < to) {
        val y = fallen(top + 0.06f * (k % rows) + if (k % 2 == 0) 0f else 0.03f, top, span, phase * 30 * 0.06f)
        val c = Offset(w * x + pencil.jitter(2f), h * y)
        if (!(c.x > w * 0.60f && c.y > h * 0.66f)) {
            val r = (2f + (k % 3) * 0.5f) * u
            val stone = Path().apply { addOval(Rect(c, r)) }
            pencil.erase(stone)
            pencil.outline(stone, width = 0.9f, passes = 1)
            ice.hatch(stone, Rect(c, r), spacing = 1.1f, width = 0.5f)
        }
        x += 0.022f
        k++
    }
    // Ceux qui rebondissent, et ceux qui sont tombés.
    listOf(0.44f, 0.52f, 0.58f, 0.66f, 0.71f).forEachIndexed { i, px ->
        val bounce = abs(sin(2 * PI * (phase * 8 + i * 0.23))).toFloat() * 4f * u
        val c = Offset(w * px, ground * 0.985f - 2f * u - if (i % 2 == 0) bounce else 0f)
        val stone = Path().apply { addOval(Rect(c, 2f * u)) }
        pencil.erase(stone)
        pencil.outline(stone, width = 0.8f, passes = 1)
    }
}

/**
 * Les aurores boréales : des rideaux verts qui ondulent en haut du ciel, frangés de rose ; elles bougent doucement
 * au fil de l'animation.
 */
internal fun DrawScope.aurora(pencil: Pencil, w: Float, sh: Float, phase: Float) {
    val u = density
    val green = pencil.ink(Crayon.GREEN)
    val pink = pencil.ink(Crayon.PINK)
    val left = w * 0.38f
    val width = w * 0.44f
    var x = left
    var k = 0
    while (x < left + width) {
        val f = (x - left) / width
        val wave = sin(2 * PI * (f * 1.5 + phase)).toFloat()
        val base = sh * (0.60f - 0.12f * f + 0.03f * wave)
        val height = sh * (0.08f + 0.07f * abs(sin(2 * PI * (f * 3 - phase * 2))).toFloat())
        green.line(Offset(x, base), Offset(x + wave * 1.5f * u, base - height), width = 0.9f, passes = 1, wobble = 0.2f)
        if (k % 3 == 0) pink.line(Offset(x + wave * 1.5f * u, base - height), Offset(x + wave * 2f * u, base - height * 1.3f), width = 0.8f, passes = 1, wobble = 0.2f)
        x += 2.6f * u
        k++
    }
}

/**
 * Une pluie d'étoiles filantes (Perséides, Géminides…) : elles partent toutes du même point du ciel, à gauche, et
 * filent l'une après l'autre ; sur une image fixe, toutes à la fois.
 */
internal fun DrawScope.meteors(pencil: Pencil, w: Float, sh: Float, phase: Float) {
    val u = density
    val gold = pencil.ink(Crayon.MOON)
    val radiant = Offset(w * 0.34f, sh * 0.30f)
    listOf(0.10 to 0.30f, 0.32 to 0.22f, 0.55 to 0.34f, 0.78 to 0.26f, 0.20 to 0.42f).forEachIndexed { k, (angle, start) ->
        val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
        val t = (phase * 5 + k * 0.2f) % 1f
        val progress = if (phase == 0f) 1f else if (t < 0.3f) t / 0.3f else return@forEachIndexed
        val from = radiant + dir * (w * start)
        val head = from + dir * (w * 0.10f * progress)
        val tail = head - dir * (w * 0.06f * minOf(progress * 2, 1f))
        gold.line(tail, head, width = 0.9f, passes = 1, wobble = 0.1f)
        gold.dot(head, 1.1f)
    }
}

/** La canicule : l'air qui tremble au-dessus du sol, en traits ondulés qui bougent. */
internal fun DrawScope.shimmer(pencil: Pencil, w: Float, h: Float, phase: Float) {
    val u = density
    val ink = pencil.ink(Crayon.ORANGE)
    for (row in 0 until 3) {
        val y = h * (0.80f + 0.035f * row)
        var x = w * (0.34f + 0.03f * row)
        while (x < w * 0.74f) {
            val points = (0..6).map { i ->
                val px = x + i * 2f * u
                Offset(px, y + (sin(px / (4f * u) + 2 * PI * phase * 4 + row) * 1.2 * u).toFloat())
            }
            ink.stroke(points, width = 0.6f, passes = 1, shake = 0.1f)
            x += 26f * u
        }
    }
}

/** Le grand froid : du givre en fougères dans les coins du bas, comme sur une vitre. */
private fun DrawScope.frost(pencil: Pencil, w: Float, h: Float) {
    val u = density
    val ice = pencil.ink(Crayon.SNOW)
    listOf(Offset(0f, h) to 1f, Offset(w, h) to -1f).forEach { (corner, side) ->
        for (k in 0 until 5) {
            val a = Math.toRadians(-10.0 - k * 18.0)
            val dir = Offset(side * cos(a).toFloat(), sin(a).toFloat())
            val length = (26f + 8f * (k % 2)) * u
            ice.line(corner, corner + dir * length, width = 0.7f, passes = 1, wobble = 0.3f)
            // Les petites branches, de chaque côté.
            var d = 5f * u
            while (d < length) {
                val p = corner + dir * d
                val twig = (length - d) * 0.22f
                listOf(0.7, -0.7).forEach { da ->
                    val b = a + if (side > 0) da else -da
                    ice.line(p, p + Offset(side * cos(b).toFloat(), sin(b).toFloat()) * twig, width = 0.5f, passes = 1, wobble = 0.1f)
                }
                d += 4.5f * u
            }
        }
    }
}
