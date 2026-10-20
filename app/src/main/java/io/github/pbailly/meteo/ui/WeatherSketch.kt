package io.github.pbailly.meteo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import io.github.pbailly.meteo.data.Surroundings
import io.github.pbailly.meteo.data.TerrainProfile
import io.github.pbailly.meteo.ui.theme.LocalChartColors
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * Dessin aux crayons de couleur du temps qu'il fait, en fond du haut d'Aujourd'hui. D'abord où l'on est : en plaine, en
 * basse, moyenne, haute ou très haute montagne, ou au sommet (d'après la coupe du terrain), au bord d'un lac, de la
 * mer ou d'une rivière, sur une île, près d'une forêt ; dans une grande ville, une petite ville, un village, un hameau
 * ou une ferme isolée ([surroundings], d'après OpenStreetMap). Puis le temps, chacun de plusieurs façons : soleil, lune
 * dans sa vraie phase et étoiles, nuages, bruine, pluie, averses, neige, orage ou brouillard. Parfois une petite
 * surprise, et certains jours de fête une autre. Traits doublés, un peu tremblés, et hachures, en couleurs douces pour
 * ne pas gêner la lecture. Toucher le dessin en montre une autre version.
 */
@Composable
fun WeatherSketch(
    code: Int,
    isDay: Boolean,
    time: LocalDateTime,
    latitude: Double,
    placeSeed: Int,
    terrain: TerrainProfile?,
    surroundings: Surroundings?,
    modifier: Modifier = Modifier,
) {
    val dark = LocalChartColors.current.darkTheme
    val graphite = sketchGraphite(dark)
    val taps = remember(placeSeed, time.toLocalDate()) { mutableIntStateOf(0) }
    val snowing = SketchSky.of(code) == SketchSky.SNOW
    // Les saisons de l'hémisphère sud sont à l'envers.
    val month = SketchScenes.seasonMonth(time.monthValue, latitude)
    val relief = remember(terrain, surroundings, month, snowing, latitude) { SketchTerrains.from(terrain, month, snowing, latitude, surroundings) }
    val scene = remember(code, isDay, time, placeSeed, relief, surroundings, taps.intValue) {
        SketchScenes.scene(code, isDay, time, placeSeed, relief, taps.intValue, surroundings)
    }
    val moonAge = remember(time) { SketchScenes.moonAge(time) }
    Canvas(
        modifier
            // Calque à part : les nuages et les objets du premier plan effacent ce qui passe derrière eux.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .pointerInput(taps) { detectTapGestures { taps.intValue++ } },
    ) {
        // Toujours le même dessin pour la même scène : pas de scintillement à chaque recomposition.
        val pencil = Pencil(this, graphite, Random(scene.seed * 31 + code), dark)
        drawScene(pencil, scene, isDay, moonAge, southern = latitude < 0, month = month)
    }
}

/** Le crayon gris des traits, en thème clair ou sombre. */
internal fun sketchGraphite(dark: Boolean) =
    if (dark) Color(0xFFE6ECF4).copy(alpha = 0.22f) else Color(0xFF2E3644).copy(alpha = 0.26f)
