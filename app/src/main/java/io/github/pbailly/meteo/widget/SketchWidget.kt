package io.github.pbailly.meteo.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import io.github.pbailly.meteo.data.Place
import io.github.pbailly.meteo.data.SettingsStore
import io.github.pbailly.meteo.data.Surroundings
import io.github.pbailly.meteo.data.SurroundingsRepository
import io.github.pbailly.meteo.data.TerrainProfile
import io.github.pbailly.meteo.data.TerrainRepository
import io.github.pbailly.meteo.ui.Fmt
import io.github.pbailly.meteo.ui.sketchConditions
import io.github.pbailly.meteo.ui.sketchImage
import io.github.pbailly.meteo.ui.weatherDescription
import java.io.File
import java.io.IOException

/**
 * Widget « Dessin » : le dessin aux crayons d'Aujourd'hui sur l'écran d'accueil (le relief et les environs gardés
 * par l'app, le temps de l'heure), avec le lieu et la température.
 */
class SketchWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetData.load(context)
        val source = SketchSource.load(context, data.place)
        provideContent { Content(data, source) }
    }

    @Composable
    private fun Content(data: WidgetData, source: SketchSource) = WidgetFrame(data, padding = 0.dp) {
        val c = data.forecast?.current
        if (c == null) {
            Box(GlanceModifier.padding(12.dp)) { WidgetMessage(data) }
            return@WidgetFrame
        }
        val place = data.place!!
        val size = LocalSize.current
        // Deux pixels par dp au plus : assez fin pour le crayon, sans dépasser la mémoire permise aux widgets.
        val density = LocalContext.current.resources.displayMetrics.density.coerceAtMost(2f)
        val image = widgetSketch(
            data, source, data.colors.dark, (size.width.value * density).toInt(), (size.height.value * density).toInt(), density,
        ) ?: return@WidgetFrame
        Box(GlanceModifier.fillMaxSize()) {
            Image(ImageProvider(image.asAndroidBitmap()), contentDescription = weatherDescription(c.weatherCode), modifier = GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Column(GlanceModifier.padding(12.dp)) {
                WText(place.name, data.colors.ink, 13.sp, bold = true)
                WText(Fmt.temp(c.temperature), data.colors.ink, 30.sp, condensed = true)
            }
        }
    }
}

/** Ce que le dessin prend dans l'app, sans rien télécharger : le relief et les environs déjà gardés, le style. */
internal class SketchSource(val terrain: TerrainProfile?, val around: Surroundings?, val style: Int) {
    companion object {
        suspend fun load(context: Context, place: Place?): SketchSource {
            val offline: (String) -> String = { throw IOException("hors ligne") }
            val dir = File(context.filesDir, "terrain")
            val terrain = place?.let { runCatching { TerrainRepository(dir, offline).cached(it) }.getOrNull() }
            val around = place?.let { runCatching { SurroundingsRepository(dir, offline).cached(it) }.getOrNull()?.first }
            return SketchSource(terrain, around, SettingsStore(context).sketchStyle())
        }
    }
}

/**
 * Le dessin d'Aujourd'hui au temps qu'il fait maintenant (null sans prévision), en [widthPx] × [heightPx] pixels sur
 * fond transparent : le widget « Dessin » le pose sur son fond, Nimbus sur le sien.
 */
internal fun widgetSketch(data: WidgetData, source: SketchSource, dark: Boolean, widthPx: Int, heightPx: Int, density: Float): ImageBitmap? {
    val place = data.place ?: return null
    val f = data.forecast ?: return null
    val c = f.current ?: return null
    return sketchImage(
        c.weatherCode, c.isDay, c.time, place.latitude, place.longitude, place.id.hashCode(), source.terrain, source.around,
        sketchConditions(f, null), source.style, dark, widthPx, heightPx, density,
    )
}

class SketchWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SketchWidget()
}
