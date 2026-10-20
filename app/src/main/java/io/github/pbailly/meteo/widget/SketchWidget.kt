package io.github.pbailly.meteo.widget

import android.content.Context
import androidx.compose.runtime.Composable
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
        // Seulement ce que l'app a déjà gardé : un widget ne télécharge pas le relief.
        val offline: (String) -> String = { throw IOException("hors ligne") }
        val dir = File(context.filesDir, "terrain")
        val terrain = data.place?.let { runCatching { TerrainRepository(dir, offline).cached(it) }.getOrNull() }
        val around = data.place?.let { runCatching { SurroundingsRepository(dir, offline).cached(it) }.getOrNull()?.first }
        val style = SettingsStore(context).sketchStyle()
        provideContent { Content(data, terrain, around, style) }
    }

    @Composable
    private fun Content(data: WidgetData, terrain: TerrainProfile?, around: Surroundings?, style: Int) = WidgetFrame(data, padding = 0.dp) {
        val f = data.forecast
        val c = f?.current
        if (c == null) {
            Box(GlanceModifier.padding(12.dp)) { WidgetMessage(data) }
            return@WidgetFrame
        }
        val place = data.place!!
        val size = LocalSize.current
        // Deux pixels par dp au plus : assez fin pour le crayon, sans dépasser la mémoire permise aux widgets.
        val density = LocalContext.current.resources.displayMetrics.density.coerceAtMost(2f)
        val image = sketchImage(
            c.weatherCode, c.isDay, c.time, place.latitude, place.longitude, place.id.hashCode(), terrain, around,
            sketchConditions(f, null), style, data.colors.dark,
            (size.width.value * density).toInt(), (size.height.value * density).toInt(), density,
        )
        Box(GlanceModifier.fillMaxSize()) {
            Image(ImageProvider(image.asAndroidBitmap()), contentDescription = weatherDescription(c.weatherCode), modifier = GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Column(GlanceModifier.padding(12.dp)) {
                WText(place.name, data.colors.ink, 13.sp, bold = true)
                WText(Fmt.temp(c.temperature), data.colors.ink, 30.sp, condensed = true)
            }
        }
    }
}

class SketchWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SketchWidget()
}
