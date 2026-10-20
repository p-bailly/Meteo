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
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.TextAlign
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.data.Outlook
import io.github.pbailly.meteo.ui.Fmt
import io.github.pbailly.meteo.ui.weatherDescription

/**
 * Widget « Prévisions » : le temps actuel, puis les prochains jours (pictogramme du jour, maxi, mini, pluie) ;
 * en plus haut, une ligne des prochaines heures, toutes les 3 heures.
 */
class ForecastWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(NARROW, WIDE, TALL))

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
        // Une colonne pour ~52 dp de large : 3 jours en 3 × 2, 5 en 4 × 2, jusqu'à 7.
        val columns = ((size.width.value - 24) / 52).toInt().coerceIn(3, 7)
        Column(GlanceModifier.fillMaxSize()) {
            Header(data, f, painter)
            if (size.height >= TALL.height) {
                Spacer(GlanceModifier.height(6.dp))
                Hours(data, f, painter, columns)
            }
            Spacer(GlanceModifier.defaultWeight())
            Days(data, f, painter, columns, showRain = size.height >= RAIN_ROW)
        }
    }

    @Composable
    private fun Header(data: WidgetData, f: Forecast, painter: WidgetPainter) {
        val c = f.current!!
        val colors = data.colors
        val today = f.daily.date.indexOf(data.today).coerceAtLeast(0)
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            WIcon(painter.icon(c.weatherCode, c.isDay, 36.dp), 36.dp, weatherDescription(c.weatherCode))
            Spacer(GlanceModifier.width(6.dp))
            WText(Fmt.temp(c.temperature), colors.ink, 28.sp, condensed = true)
            Spacer(GlanceModifier.width(8.dp))
            Column(GlanceModifier.defaultWeight()) {
                WText(data.place!!.name, colors.ink, 13.sp, bold = true)
                WText(weatherDescription(c.weatherCode), colors.secondaryInk, 11.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row {
                    WText(Fmt.temp(f.daily.temperatureMax.getOrElse(today) { Double.NaN }), colors.accent, 12.sp, bold = true)
                    WText(" / " + Fmt.temp(f.daily.temperatureMin.getOrElse(today) { Double.NaN }), colors.primary, 12.sp, bold = true)
                }
                WText(updatedLabel(data), colors.muted, 10.sp)
            }
        }
    }

    /** Une heure sur trois : heure, pictogramme, température. */
    @Composable
    private fun Hours(data: WidgetData, f: Forecast, painter: WidgetPainter, columns: Int) {
        val h = f.hourly
        val start = Outlook.nowIndex(f)
        val indices = (0 until columns).map { start + 3 * (it + 1) }.filter { it < h.size }
        Row(GlanceModifier.fillMaxWidth()) {
            indices.forEach { i ->
                Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    val time = h.time[i]
                    val label = if (time.hour == 0) Fmt.dayName(time.toLocalDate()) else "${time.hour}h"
                    WText(label, data.colors.muted, 10.sp, align = TextAlign.Center)
                    WIcon(painter.icon(h.weatherCode[i], h.isDay[i], 22.dp), 22.dp, weatherDescription(h.weatherCode[i]))
                    WText(Fmt.temp(h.temperature[i]), data.colors.ink, 12.sp, bold = true, align = TextAlign.Center)
                }
            }
        }
    }

    /** Les jours, à partir d'aujourd'hui : jour, pictogramme qui résume la journée, maxi et mini, pluie s'il y a la place. */
    @Composable
    private fun Days(data: WidgetData, f: Forecast, painter: WidgetPainter, columns: Int, showRain: Boolean) {
        val d = f.daily
        val first = d.date.indexOf(data.today).coerceAtLeast(0)
        val days = (first until minOf(d.size, first + columns)).toList()
        val colors = data.colors
        Row(GlanceModifier.fillMaxWidth()) {
            days.forEach { i ->
                val date = d.date[i]
                Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    val label = if (date == data.today) "auj." else Fmt.dayName(date)
                    WText(label, if (date == data.today) colors.accent else colors.secondaryInk, 11.sp, bold = true, align = TextAlign.Center)
                    val code = Outlook.dayCode(f, date).takeIf { it >= 0 } ?: d.weatherCode[i]
                    WIcon(painter.icon(code, true, 26.dp), 26.dp, weatherDescription(code), GlanceModifier.padding(vertical = 1.dp))
                    Row {
                        WText(Fmt.temp(d.temperatureMax[i]), colors.accent, 12.sp, bold = true)
                        WText(" " + Fmt.temp(d.temperatureMin[i]), colors.primary, 12.sp, bold = true)
                    }
                    if (showRain) {
                        // Toujours une ligne, vide les jours secs : les colonnes restent alignées.
                        val rain = d.precipitationSum[i]
                        WText(if (!rain.isNaN() && rain >= 1) Fmt.decimal(rain) + " mm" else " ", colors.primary, 9.sp, align = TextAlign.Center)
                    }
                }
            }
        }
    }

    private companion object {
        val NARROW = DpSize(180.dp, 110.dp)
        val WIDE = DpSize(250.dp, 110.dp)
        val TALL = DpSize(250.dp, 200.dp)
        /** Hauteur à partir de laquelle on ajoute la pluie de chaque jour. */
        val RAIN_ROW = 140.dp
    }
}

class ForecastWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ForecastWidget()
}
