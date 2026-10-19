package io.github.pbailly.meteo.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import io.github.pbailly.meteo.ui.theme.PlexMono
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Graduations « rondes » (1, 2, 2,5, 5 × 10ⁿ) couvrant [min, max]. */
fun niceTicks(min: Double, max: Double, target: Int): List<Double> {
    if (min.isNaN() || max.isNaN()) return listOf(0.0, 1.0)
    val lo = minOf(min, max)
    val hi = if (max - min < 1e-9) lo + 1.0 else maxOf(min, max)
    val raw = (hi - lo) / target.coerceAtLeast(1)
    val magnitude = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= raw }
    val start = floor(lo / step) * step
    val end = ceil(hi / step) * step
    val count = ((end - start) / step).toInt()
    return (0..count).map { start + it * step }
}

/** Échelle linéaire entre un domaine de valeurs et une plage de pixels (y vers le bas). */
class LinearScale(private val d0: Double, private val d1: Double, private val top: Float, private val bottom: Float) {
    fun y(v: Double): Float = (bottom - (v - d0) / (d1 - d0) * (bottom - top)).toFloat()
}

/** Ligne qui s'interrompt sur les valeurs manquantes (NaN). */
fun DrawScope.drawSeries(
    values: DoubleArray,
    x: (Int) -> Float,
    y: (Double) -> Float,
    color: Color,
    width: Float,
    dashed: Boolean = false,
    range: IntRange = values.indices,
) {
    val path = Path()
    var penDown = false
    for (i in range) {
        val v = values.getOrElse(i) { Double.NaN }
        if (v.isNaN()) {
            penDown = false
            continue
        }
        if (penDown) path.lineTo(x(i), y(v)) else path.moveTo(x(i), y(v))
        penDown = true
    }
    drawPath(
        path,
        color,
        style = Stroke(
            width = width,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(width * 3f, width * 2.5f)) else null,
        ),
    )
}

/** Texte de graphique, en IBM Plex Mono : [anchor] désigne le point d'ancrage (0 = gauche/haut, 0,5 = centre, 1 = droite/bas). */
fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    at: Offset,
    color: Color,
    sizeSp: Float = 10f,
    anchorX: Float = 0f,
    anchorY: Float = 0f,
    bold: Boolean = false,
) {
    val layout = measurer.measure(
        text,
        TextStyle(
            color = color,
            fontSize = sizeSp.sp,
            fontFamily = PlexMono,
            fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
        ),
    )
    drawText(
        layout,
        topLeft = Offset(at.x - layout.size.width * anchorX, at.y - layout.size.height * anchorY),
    )
}

/** Petite flèche indiquant où souffle le vent (la direction météo est celle d'où il vient). */
fun DrawScope.drawWindArrow(center: Offset, directionFrom: Double, length: Float, color: Color, width: Float) {
    if (directionFrom.isNaN()) return
    val half = length / 2f
    val head = length * 0.32f
    rotate(directionFrom.toFloat() + 180f, pivot = center) {
        drawLine(color, Offset(center.x, center.y + half), Offset(center.x, center.y - half), width, StrokeCap.Round)
        drawLine(color, Offset(center.x, center.y - half), Offset(center.x - head, center.y - half + head), width, StrokeCap.Round)
        drawLine(color, Offset(center.x, center.y - half), Offset(center.x + head, center.y - half + head), width, StrokeCap.Round)
    }
}
