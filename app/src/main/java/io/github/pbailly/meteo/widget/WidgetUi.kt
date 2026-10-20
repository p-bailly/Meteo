package io.github.pbailly.meteo.widget

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.pbailly.meteo.MainActivity
import io.github.pbailly.meteo.ui.Fmt

/** Le cadre commun des widgets : fond Glacier arrondi ; toucher le widget ouvre l'app. */
@Composable
internal fun WidgetFrame(data: WidgetData, padding: Dp = 12.dp, content: @Composable () -> Unit) {
    Box(
        GlanceModifier
            .fillMaxSize()
            .cornerRadius(20.dp)
            .background(data.colors.background)
            .clickable(actionStartActivity<MainActivity>())
            .padding(padding),
    ) {
        content()
    }
}

/** Sans lieu ou sans prévision : une phrase à la place de la météo. */
@Composable
internal fun WidgetMessage(data: WidgetData) {
    val message = when {
        data.place == null -> "Ouvre l'app pour choisir un lieu"
        else -> "${data.place.name} : pas de connexion, la météo s'affichera au retour du réseau"
    }
    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        WText(message, data.colors.secondaryInk, 13.sp, align = TextAlign.Center, maxLines = 4)
    }
}

/** Texte de widget : Archivo n'y est pas disponible, on prend la police étroite du téléphone pour les chiffres. */
@Composable
internal fun WText(
    text: String,
    color: Color,
    size: TextUnit,
    bold: Boolean = false,
    condensed: Boolean = false,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = 1,
    modifier: GlanceModifier = GlanceModifier,
) {
    Text(
        text,
        modifier = modifier,
        maxLines = maxLines,
        style = TextStyle(
            color = ColorProvider(color),
            fontSize = size,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontFamily = if (condensed) CONDENSED else null,
            textAlign = align,
        ),
    )
}

@Composable
internal fun WIcon(bitmap: Bitmap, size: Dp, description: String, modifier: GlanceModifier = GlanceModifier) {
    Image(ImageProvider(bitmap), contentDescription = description, modifier = modifier.size(size))
}

/** « maj 14:32 », ou « hors ligne · 14:32 » quand on montre le cache faute de réseau. */
internal fun updatedLabel(data: WidgetData): String =
    (if (data.stale) "hors ligne · " else "maj ") + Fmt.clockOfMillis(data.fetchedAt)

private val CONDENSED = FontFamily("sans-serif-condensed")
