package io.github.pbailly.meteo.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
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
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import io.github.pbailly.meteo.data.Outlook
import io.github.pbailly.meteo.ui.Fmt

/**
 * Widget « Prochaines heures » : quand la pluie arrive (ou s'arrête) et combien, puis le graphique des 24 prochaines
 * heures (12 en étroit) : pictogrammes, courbe de température et barres de pluie, comme dans Aujourd'hui.
 */
class HoursWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(NARROW, WIDE, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetData.load(context)
        provideContent { Content(data) }
    }

    @Composable
    private fun Content(data: WidgetData) = WidgetFrame(data, padding = PADDING) {
        val f = data.forecast
        if (f?.current == null) {
            WidgetMessage(data)
            return@WidgetFrame
        }
        val colors = data.colors
        val size = LocalSize.current
        val hours = if (size.width >= WIDE.width) 24 else 12
        Column(GlanceModifier.fillMaxSize()) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    WText(data.place!!.name, colors.ink, 13.sp, bold = true)
                    WText(rainHeadline(Outlook.nextRain(f, hours), data.today!!, hours), colors.primary, 11.sp)
                }
                WText(Fmt.temp(f.current.temperature), colors.ink, 28.sp, condensed = true)
            }
            Spacer(GlanceModifier.height(4.dp))
            val chart = DpSize(size.width - PADDING * 2, size.height - PADDING * 2 - HEADER)
            if (chart.height > 40.dp) {
                val bitmap = WidgetPainter(LocalContext.current, colors)
                    .hoursChart(f, Outlook.nowIndex(f), hours, step = if (hours > 12) 3 else 2, size = chart)
                Image(
                    ImageProvider(bitmap),
                    contentDescription = "Température et pluie des $hours prochaines heures",
                    contentScale = ContentScale.FillBounds,
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                )
            }
        }
    }

    private companion object {
        val PADDING = 12.dp
        /** Hauteur de l'en-tête (lieu, phrase sur la pluie) et de l'espace qui le suit. */
        val HEADER = 40.dp
        val NARROW = DpSize(180.dp, 110.dp)
        val WIDE = DpSize(250.dp, 110.dp)
        val TALL = DpSize(250.dp, 180.dp)
    }
}

class HoursWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HoursWidget()
}
