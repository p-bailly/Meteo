package io.github.pbailly.meteo.ui

import io.github.pbailly.meteo.ui.theme.WebFonts
import org.jetbrains.skia.Data
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.TextLine

// L'écriture d'un texte sur le canevas d'Android (Canvas.drawText avec un Paint), sur celui de Skia dans le navigateur.

private val handTypeface by lazy { WebFonts.handBytes?.let { FontMgr.default.makeFromData(Data.makeFromBytes(it)) } }

internal fun org.jetbrains.skia.Canvas.drawText(text: String, x: Float, y: Float, paint: android.graphics.Paint) {
    val font = Font(handTypeface, paint.textSize)
    val line = TextLine.make(text, font)
    val left = when (paint.textAlign) {
        android.graphics.Paint.Align.LEFT -> x
        android.graphics.Paint.Align.CENTER -> x - line.width / 2
        android.graphics.Paint.Align.RIGHT -> x - line.width
    }
    val skPaint = org.jetbrains.skia.Paint().apply {
        color = paint.color
        isAntiAlias = true
    }
    drawTextLine(line, left, y, skPaint)
}
