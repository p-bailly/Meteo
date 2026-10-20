package io.github.pbailly.meteo.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.res.ResourcesCompat
import io.github.pbailly.meteo.R
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.ui.Fmt
import io.github.pbailly.meteo.ui.drawWeatherIcon

/**
 * Les widgets ne savent afficher que du texte et des images : les pictogrammes au trait et le graphique des
 * prochaines heures y sont dessinés dans des images, avec le même code que dans l'app.
 */
internal class WidgetPainter(private val context: Context, private val colors: WidgetColors) {
    /** Pixels par dp, plafonné : les images d'un widget passent par une mémoire limitée du lanceur. */
    private val scale = context.resources.displayMetrics.density.coerceAtMost(2.5f)
    private val icons = HashMap<Triple<Int, Boolean, Dp>, Bitmap>()

    /** Le même pictogramme sert plusieurs fois : une seule image par code, moment (jour ou nuit) et taille. */
    fun icon(code: Int, isDay: Boolean, size: Dp): Bitmap = icons.getOrPut(Triple(code, isDay, size)) {
        val px = (size.value * scale).toInt().coerceAtLeast(1)
        draw(px, px) { drawWeatherIcon(code, isDay, colors.dark, Offset.Zero, px.toFloat()) }
    }

    private val mono: Typeface? = runCatching { ResourcesCompat.getFont(context, R.font.martian_mono_condensed_regular) }.getOrNull()
    private val monoBold: Typeface? = runCatching { ResourcesCompat.getFont(context, R.font.martian_mono_condensed_medium) }.getOrNull()

    /**
     * Les [hours] prochaines heures en une image de [size] : pictogramme et température toutes les [step] heures,
     * courbe de température, barres de pluie et heures en bas ; l'heure actuelle en orange.
     */
    fun hoursChart(f: Forecast, start: Int, hours: Int, step: Int, size: DpSize): Bitmap {
        val w = (size.width.value * scale).toInt().coerceAtLeast(1)
        val h = (size.height.value * scale).toInt().coerceAtLeast(1)
        return draw(w, h) { chart(f, start, hours, step) }
    }

    private fun DrawScope.chart(f: Forecast, start: Int, count: Int, step: Int) {
        val hourly = f.hourly
        val indices = (start until minOf(hourly.size, start + count)).toList()
        if (indices.size < 2) return
        val dp = scale
        val col = size.width / indices.size
        val labelSize = 10f * dp
        val iconSize = minOf(22f * dp, size.height * 0.2f)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = labelSize; typeface = mono; textAlign = Paint.Align.CENTER }
        val boldPaint = Paint(textPaint).apply { typeface = monoBold ?: Typeface.DEFAULT_BOLD }
        val canvas = drawContext.canvas.nativeCanvas

        // De haut en bas : pictogrammes, courbe de température (avec ses valeurs), barres de pluie, heures.
        val hourBaseline = size.height - 2f * dp
        val barBottom = hourBaseline - labelSize - 4f * dp
        val barTop = barBottom - size.height * 0.2f
        val curveTop = iconSize + labelSize + 8f * dp
        val curveBottom = barTop - 6f * dp

        val temps = indices.map { hourly.temperature[it] }.filter { !it.isNaN() }
        val tMin = temps.minOrNull() ?: 0.0
        val tMax = (temps.maxOrNull() ?: 1.0).let { if (it - tMin < 4) tMin + 4 else it }
        fun ty(t: Double) = (curveBottom - (t - tMin) / (tMax - tMin) * (curveBottom - curveTop)).toFloat()
        val maxMm = maxOf(2.0, indices.maxOf { hourly.precipitation[it].takeIf { v -> !v.isNaN() } ?: 0.0 })

        drawLine(colors.grid, Offset(0f, barBottom), Offset(size.width, barBottom), 1f * dp)
        val line = Path()
        var started = false
        indices.forEachIndexed { k, i ->
            val cx = col * k + col / 2
            val mm = hourly.precipitation[i]
            if (!mm.isNaN() && mm >= 0.05) {
                val height = ((mm / maxMm) * (barBottom - barTop)).toFloat().coerceAtLeast(2f * dp)
                val barWidth = col * 0.7f
                drawRoundRect(
                    colors.primary,
                    topLeft = Offset(cx - barWidth / 2, barBottom - height),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(1.5f * dp),
                )
            }
            val t = hourly.temperature[i]
            if (!t.isNaN()) {
                if (started) line.lineTo(cx, ty(t)) else line.moveTo(cx, ty(t))
                started = true
            }
            if (k % step != 0) return@forEachIndexed
            // Une colonne sur [step] : pictogramme, température, heure (et un trait léger pour s'y retrouver).
            if (k > 0) drawLine(colors.grid, Offset(cx, iconSize), Offset(cx, barBottom), 1f * dp)
            drawWeatherIcon(hourly.weatherCode[i], hourly.isDay[i], colors.dark, Offset(cx - iconSize / 2, 0f), iconSize)
            val time = hourly.time[i]
            val label = when {
                k == 0 -> "maint."
                time.hour == 0 -> Fmt.dayName(time.toLocalDate())
                else -> "${time.hour}h"
            }
            val hourPaint = if (k == 0 || time.hour == 0) boldPaint else textPaint
            hourPaint.color = (if (k == 0) colors.accent else colors.muted).toArgb()
            canvas.drawText(label, cx, hourBaseline, hourPaint)
        }
        drawPath(line, colors.ink, style = Stroke(1.5f * dp))
        indices.forEachIndexed { k, i ->
            val t = hourly.temperature[i]
            if (k % step != 0 || t.isNaN()) return@forEachIndexed
            val center = Offset(col * k + col / 2, ty(t))
            drawCircle(if (k == 0) colors.accent else colors.ink, 2.5f * dp, center)
            boldPaint.color = colors.ink.toArgb()
            canvas.drawText(Fmt.temp(t), center.x, center.y - 6f * dp, boldPaint)
        }
    }

    private fun draw(width: Int, height: Int, block: DrawScope.() -> Unit): Bitmap {
        val image = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat()), block)
        return image.asAndroidBitmap()
    }
}
