package io.github.pbailly.meteo.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import io.github.pbailly.meteo.data.Surroundings
import io.github.pbailly.meteo.data.TerrainProfile
import io.github.pbailly.meteo.ui.theme.LocalChartColors
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * Dessin aux crayons de couleur du temps qu'il fait, en fond du haut d'Aujourd'hui. D'abord où l'on est : en plaine, en
 * basse, moyenne, haute ou très haute montagne, ou au sommet (d'après la coupe du terrain), au bord d'un lac, de la
 * mer ou d'une rivière, sur une île, près d'une forêt ou d'une station de ski ; dans une grande ville (et son monument),
 * une petite ville, un village, un hameau ou une ferme isolée ([surroundings], d'après OpenStreetMap). Puis le temps,
 * chacun de plusieurs façons, avec ce que la prévision de l'heure y ajoute ([conditions]) : les nuages à leur hauteur,
 * le vent qui couche les arbres, la lumière de l'aube et du couchant, la neige au sol, les lacs gelés, le sable du
 * Sahara ou la pollution. Des bêtes selon le lieu, parfois une petite surprise, et certains jours de fête une autre.
 * Traits doublés, un peu tremblés, et hachures, en couleurs plus ou moins douces ([style] : 0 discret, 1 normal,
 * 2 coloré) pour ne pas gêner la lecture. Avec [motion], la pluie et la neige tombent et les nuages glissent. Toucher
 * le dessin en montre une autre version.
 */
@Composable
fun WeatherSketch(
    code: Int,
    isDay: Boolean,
    time: LocalDateTime,
    latitude: Double,
    longitude: Double,
    placeSeed: Int,
    terrain: TerrainProfile?,
    surroundings: Surroundings?,
    conditions: SketchConditions,
    style: Int = 1,
    motion: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val dark = LocalChartColors.current.darkTheme
    val strength = sketchStrength(style)
    val graphite = sketchGraphite(dark, strength)
    val taps = remember(placeSeed, time.toLocalDate()) { mutableIntStateOf(0) }
    // La neige qui tombe, ou qui est déjà au sol, blanchit tout le paysage.
    val snowing = SketchSky.of(code) == SketchSky.SNOW || conditions.snowOnGround
    // Les saisons de l'hémisphère sud sont à l'envers.
    val month = SketchScenes.seasonMonth(time.monthValue, latitude)
    val relief = remember(terrain, surroundings, month, snowing, latitude) { SketchTerrains.from(terrain, month, snowing, latitude, surroundings) }
    val landmark = remember(latitude, longitude) { Landmark.near(latitude, longitude) }
    val scene = remember(code, isDay, time, placeSeed, relief, surroundings, conditions, landmark, taps.intValue) {
        SketchScenes.scene(code, isDay, time, placeSeed, relief, taps.intValue, surroundings, conditions, landmark)
    }
    val moonAge = remember(time) { SketchScenes.moonAge(time) }
    // L'animation : une phase de 0 à 1 en 20 secondes, mise à jour une quinzaine de fois par seconde (assez pour la
    // pluie au crayon, sans user la batterie), seulement s'il y a quelque chose qui bouge.
    val phase = remember { mutableFloatStateOf(0f) }
    val moving = motion && SketchScenes.moves(scene)
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            phase.floatValue = ((now - start) % SketchScenes.CYCLE_MS) / SketchScenes.CYCLE_MS.toFloat()
            delay(66)
        }
    }
    Spacer(
        modifier
            // Calque à part : les nuages et les objets du premier plan effacent ce qui passe derrière eux.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .pointerInput(taps) { detectTapGestures { taps.intValue++ } }
            .drawWithCache {
                // Le paysage et le ciel fixes, dessinés une fois ; à chaque image, seulement ce qui bouge par-dessus.
                // Toujours le même trait pour la même scène : pas de scintillement.
                val still = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
                CanvasDrawScope().draw(this, layoutDirection, Canvas(still), size) {
                    val pencil = Pencil(this, graphite, Random(scene.seed * 31 + code), dark, strength)
                    drawScene(pencil, scene, isDay, moonAge, southern = latitude < 0, month = month, part = SketchPart.STILL)
                }
                onDrawBehind {
                    drawImage(still)
                    val pencil = Pencil(this, graphite, Random(scene.seed * 37 + code), dark, strength)
                    drawScene(pencil, scene, isDay, moonAge, southern = latitude < 0, month = month, part = SketchPart.MOVING, phase = phase.floatValue)
                }
            },
    )
}
