package io.github.pbailly.meteo.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.data.Outlook
import io.github.pbailly.meteo.ui.Fmt
import io.github.pbailly.meteo.ui.weatherDescription

/**
 * Widget « Maintenant » : la température et le ciel du lieu ouvert dans l'app, les extrêmes du jour ;
 * en plus large, le ressenti et l'arrivée de la pluie.
 */
class NowWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, SQUARE, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetData.load(context)
        provideContent { Content(data) }
    }

    @Composable
    private fun Content(data: WidgetData) = WidgetFrame(data) {
        val f = data.forecast
        if (f?.current == null) {
            WidgetMessage(data)
            return@WidgetFrame
        }
        val painter = WidgetPainter(LocalContext.current, data.colors)
        val size = LocalSize.current
        when {
            size.height < SQUARE.height -> Small(data, f, painter)
            size.width < WIDE.width -> Square(data, f, painter)
            else -> Wide(data, f, painter)
        }
    }

    /** Sur une ligne : pictogramme, température, lieu et extrêmes. */
    @Composable
    private fun Small(data: WidgetData, f: Forecast, painter: WidgetPainter) {
        val c = f.current!!
        val colors = data.colors
        Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            WIcon(painter.icon(c.weatherCode, c.isDay, 34.dp), 34.dp, weatherDescription(c.weatherCode))
            Spacer(GlanceModifier.width(6.dp))
            WText(Fmt.temp(c.temperature), colors.ink, 30.sp, condensed = true)
            Spacer(GlanceModifier.width(8.dp))
            Column {
                WText(data.place!!.name, colors.ink, 12.sp, bold = true)
                Extremes(data, f, 11.sp)
            }
        }
    }

    /** Carré : lieu, température et pictogramme, ciel (s'il y a la place), extrêmes. */
    @Composable
    private fun Square(data: WidgetData, f: Forecast, painter: WidgetPainter) {
        val c = f.current!!
        val colors = data.colors
        Column(GlanceModifier.fillMaxSize()) {
            WText(data.place!!.name, colors.ink, 13.sp, bold = true)
            Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                WText(Fmt.temp(c.temperature), colors.ink, 34.sp, condensed = true)
                Spacer(GlanceModifier.defaultWeight())
                WIcon(painter.icon(c.weatherCode, c.isDay, 38.dp), 38.dp, weatherDescription(c.weatherCode))
            }
            if (LocalSize.current.height >= ROOMY) WText(weatherDescription(c.weatherCode), colors.secondaryInk, 12.sp)
            Extremes(data, f, 12.sp)
        }
    }

    /** En largeur : la température en grand et le ressenti à gauche ; à droite le ciel ; en dessous, la pluie à venir. */
    @Composable
    private fun Wide(data: WidgetData, f: Forecast, painter: WidgetPainter) {
        val c = f.current!!
        val colors = data.colors
        Column(GlanceModifier.fillMaxSize()) {
            Row(GlanceModifier.fillMaxWidth()) {
                WText(data.place!!.name, colors.ink, 13.sp, bold = true, modifier = GlanceModifier.defaultWeight())
                WText(updatedLabel(data), colors.muted, 10.sp)
            }
            Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                Column {
                    WText(Fmt.temp(c.temperature), colors.ink, 40.sp, condensed = true)
                    WText("ressenti ${Fmt.temp(c.apparentTemperature)}", colors.secondaryInk, 11.sp)
                }
                Spacer(GlanceModifier.width(12.dp))
                Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.End) {
                    WIcon(painter.icon(c.weatherCode, c.isDay, 40.dp), 40.dp, weatherDescription(c.weatherCode))
                    WText(weatherDescription(c.weatherCode), colors.ink, 12.sp, bold = true)
                    Extremes(data, f, 12.sp)
                }
            }
            if (LocalSize.current.height >= ROOMY) {
                WText(
                    rainHeadline(Outlook.nextRain(f), data.today!!),
                    colors.primary,
                    11.sp,
                    modifier = GlanceModifier.padding(top = 2.dp),
                )
            }
        }
    }

    @Composable
    private fun Extremes(data: WidgetData, f: Forecast, size: androidx.compose.ui.unit.TextUnit) {
        val today = f.daily.date.indexOf(data.today).coerceAtLeast(0)
        Row {
            WText(Fmt.temp(f.daily.temperatureMax.getOrElse(today) { Double.NaN }), data.colors.accent, size, bold = true)
            WText(" / " + Fmt.temp(f.daily.temperatureMin.getOrElse(today) { Double.NaN }), data.colors.primary, size, bold = true)
        }
    }

    private companion object {
        val SMALL = DpSize(110.dp, 40.dp)
        val SQUARE = DpSize(110.dp, 100.dp)
        val WIDE = DpSize(200.dp, 100.dp)
        /** À partir de cette hauteur, la place pour une ligne de plus (ciel en carré, pluie en large). */
        val ROOMY = 130.dp
    }
}

class NowWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowWidget()
}
