package io.github.pbailly.meteo.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import io.github.pbailly.meteo.data.Surroundings
import io.github.pbailly.meteo.data.TerrainProfile
import io.github.pbailly.meteo.ui.theme.LocalChartColors
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import kotlin.math.round
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
 * le dessin en montre une autre version ; un appui long l'ouvre en grand ([onLongPress]).
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
    /** Appui long sur le dessin (la carte postale en grand). */
    onLongPress: (() -> Unit)? = null,
    /** Le texte de l'écran posé sur le dessin : le coloriage ne le touche pas. */
    text: SketchText? = null,
) {
    val dark = LocalChartColors.current.darkTheme
    val strength = sketchStrength(style)
    val graphite = sketchGraphite(dark, strength)
    val taps = remember(placeSeed, time.toLocalDate()) { mutableIntStateOf(0) }
    // La neige qui tombe, ou qui est déjà au sol, blanchit tout le paysage.
    val snowing = SketchSky.of(code) == SketchSky.SNOW || conditions.snowOnGround
    // Les saisons de l'hémisphère sud sont à l'envers.
    val month = SketchScenes.seasonMonth(time.monthValue, latitude)
    val landmark = remember(latitude, longitude) { Landmark.near(latitude, longitude) }
    // Ce qu'on sait déjà du lieu complète OpenStreetMap (et dessine tout de suite, avant sa réponse).
    val around = remember(surroundings, landmark) { landmark?.completed(surroundings) ?: surroundings }
    val relief = remember(terrain, around, month, snowing, latitude, conditions) { SketchTerrains.from(terrain, month, snowing, latitude, around, conditions) }
    val scene = remember(code, isDay, time, placeSeed, relief, around, conditions, landmark, taps.intValue) {
        SketchScenes.scene(code, isDay, time, placeSeed, relief, taps.intValue, around, conditions, landmark, latitude, longitude)
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
            .then(text?.frame ?: Modifier)
            // Calque à part : les nuages et les objets du premier plan effacent ce qui passe derrière eux.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .pointerInput(taps, onLongPress) { detectTapGestures(onLongPress = onLongPress?.let { { _ -> it() } }) { taps.intValue++ } }
            .drawWithCache {
                // Le paysage et le ciel fixes, dessinés une fois ; à chaque image, seulement ce qui bouge par-dessus.
                // Toujours le même trait pour la même scène : pas de scintillement.
                val zones = text?.zones.orEmpty()
                val still = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
                CanvasDrawScope().draw(this, layoutDirection, Canvas(still), size) {
                    val pencil = Pencil(this, graphite, Random(scene.seed * 31 + code), dark, strength).also {
                        it.write = handwriting
                        it.text = zones
                    }
                    drawScene(pencil, scene, isDay, moonAge, southern = latitude < 0, month = month, part = SketchPart.STILL)
                }
                onDrawBehind {
                    drawImage(still)
                    val pencil = Pencil(this, graphite, Random(scene.seed * 37 + code), dark, strength).also { it.text = zones }
                    drawScene(pencil, scene, isDay, moonAge, southern = latitude < 0, month = month, part = SketchPart.MOVING, phase = phase.floatValue)
                }
            },
    )
}

/**
 * Le même dessin, en image fixe de [widthPx] × [heightPx] pixels (le widget de l'écran d'accueil) : le relief et les
 * environs gardés sur le téléphone, la prévision de l'heure, le style des Réglages.
 */
fun sketchImage(
    code: Int,
    isDay: Boolean,
    time: LocalDateTime,
    latitude: Double,
    longitude: Double,
    placeSeed: Int,
    terrain: TerrainProfile?,
    surroundings: Surroundings?,
    conditions: SketchConditions,
    style: Int,
    dark: Boolean,
    widthPx: Int,
    heightPx: Int,
    density: Float,
    /** Le texte qui sera posé sur l'image, en pixels de l'image : le coloriage ne le touche pas. */
    text: List<Rect> = emptyList(),
): ImageBitmap {
    val strength = sketchStrength(style)
    val snowing = SketchSky.of(code) == SketchSky.SNOW || conditions.snowOnGround
    val month = SketchScenes.seasonMonth(time.monthValue, latitude)
    val landmark = Landmark.near(latitude, longitude)
    val around = landmark?.completed(surroundings) ?: surroundings
    val relief = SketchTerrains.from(terrain, month, snowing, latitude, around, conditions)
    val scene = SketchScenes.scene(code, isDay, time, placeSeed, relief, 0, around, conditions, landmark, latitude, longitude)
    val image = ImageBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1))
    val size = Size(image.width.toFloat(), image.height.toFloat())
    CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(image), size) {
        val pencil = Pencil(this, sketchGraphite(dark, strength), Random(scene.seed * 31 + code), dark, strength).also {
            it.write = handwriting
            it.text = text
        }
        drawScene(pencil, scene, isDay, SketchScenes.moonAge(time), southern = latitude < 0, month = month)
    }
    return image
}

/** L'écriture à la main du dessin (les noms des sommets) : la police manuscrite d'Android, centrée. */
private val handwriting: (DrawScope, String, Offset, Color, Float) -> Unit = { scope, text, at, color, size ->
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        textSize = size * scope.density
        typeface = android.graphics.Typeface.create("casual", android.graphics.Typeface.NORMAL)
        textAlign = android.graphics.Paint.Align.CENTER
    }
    scope.drawContext.canvas.nativeCanvas.drawText(text, at.x, at.y, paint)
}

/**
 * Le texte d'un écran posé sur le dessin, mesuré une fois placé : chaque écriture le dit ([of], et [layout] pour un
 * texte, dont on garde alors les lettres plutôt que toute la boîte), le dessin aussi ([frame]). [zones] dit, en pixels
 * du dessin, ce que le coloriage doit laisser blanc ; elle ne change pas quand tout défile ensemble.
 */
@Stable
class SketchText {
    private val layouts = HashMap<String, TextLayoutResult>()
    private val nodes = HashMap<String, LayoutCoordinates>()
    private var drawing: LayoutCoordinates? = null

    /** Ce que couvre le texte, en pixels du dessin. */
    var zones by mutableStateOf(emptyList<Rect>())
        private set

    /** À poser sur le dessin. */
    val frame: Modifier = Modifier.onGloballyPositioned {
        drawing = it
        update()
    }

    /** À poser sur l'écriture [key] (un texte, une icône, une pastille). */
    fun of(key: String): Modifier = Modifier.onGloballyPositioned {
        nodes[key] = it
        update()
    }

    /** À donner au `onTextLayout` du texte [key] : on ne garde que ses lettres. */
    fun layout(key: String): (TextLayoutResult) -> Unit = { layouts[key] = it }

    private fun update() {
        val f = drawing?.takeIf { it.isAttached } ?: return
        // Une écriture qui a disparu (le bandeau d'erreur) ne compte plus.
        nodes.entries.removeAll { !it.value.isAttached }
        val next = nodes.flatMap { (key, node) ->
            val origin = f.localPositionOf(node, Offset.Zero)
            val parts = layouts[key]?.let(::letters) ?: listOf(Rect(Offset.Zero, node.size.toSize()))
            parts.map { r -> Rect(round(r.left + origin.x), round(r.top + origin.y), round(r.right + origin.x), round(r.bottom + origin.y)) }
        }
        if (next != zones) zones = next
    }
}

/**
 * Ce que couvrent les lettres de [layout], ligne par ligne : de la hauteur des majuscules (des accents s'il y en a) au
 * bas des jambages (à la ligne de base s'il n'y en a pas).
 */
internal fun letters(layout: TextLayoutResult): List<Rect> {
    val size = layout.layoutInput.style.fontSize
    // Sans taille connue, celle qu'on lit dans la hauteur de la première ligne.
    val em = if (size.isSp) with(layout.layoutInput.density) { size.toPx() } else (layout.getLineBottom(0) - layout.getLineTop(0)) / 1.25f
    val text = layout.layoutInput.text.text
    return (0 until layout.lineCount).mapNotNull { i ->
        val line = text.substring(layout.getLineStart(i), layout.getLineEnd(i, visibleEnd = true))
        if (line.isBlank()) return@mapNotNull null
        val baseline = layout.getLineBaseline(i)
        val high = line.any { it.isUpperCase() && it.code > 0x7F }
        val low = line.any { it in "gjpqyçQ,;()[]{}|/@" }
        Rect(layout.getLineLeft(i), baseline - em * if (high) 0.96f else 0.8f, layout.getLineRight(i), baseline + em * if (low) 0.25f else 0.06f)
    }
}
