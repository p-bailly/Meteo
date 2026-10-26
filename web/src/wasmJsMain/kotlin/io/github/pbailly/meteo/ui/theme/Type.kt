package io.github.pbailly.meteo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// Comme app/…/ui/theme/Type.kt, mais les polices sont téléchargées avec la page (fonts/) avant le premier affichage.

/** Les polices de l'appli, chargées par [loadFonts]. */
object WebFonts {
    internal lateinit var sans: FontFamily
    internal lateinit var condensed: FontFamily
    internal lateinit var mono: FontFamily
    internal lateinit var typography: Typography

    /** Archivo normal, pour l'écriture à la main du dessin (le navigateur n'a pas la police manuscrite d'Android). */
    var handBytes: ByteArray? = null
        internal set

    /** Les huit fichiers de police, en même temps ; [fetchBytes] les télécharge. */
    suspend fun load(fetchBytes: suspend (String) -> ByteArray) = coroutineScope {
        val names = listOf(
            "archivo_condensed_light", "archivo_regular", "archivo_medium", "archivo_semibold",
            "archivo_condensed_regular", "archivo_condensed_semibold",
            "martian_mono_condensed_regular", "martian_mono_condensed_medium",
        )
        val bytes = names.map { name -> async { name to fetchBytes("fonts/$name.ttf") } }.awaitAll().toMap()
        fun font(name: String, weight: FontWeight) = Font(name, bytes.getValue(name), weight)
        sans = FontFamily(
            font("archivo_condensed_light", FontWeight.Light),
            font("archivo_regular", FontWeight.Normal),
            font("archivo_medium", FontWeight.Medium),
            font("archivo_semibold", FontWeight.SemiBold),
        )
        condensed = FontFamily(
            font("archivo_condensed_light", FontWeight.Light),
            font("archivo_condensed_regular", FontWeight.Normal),
            font("archivo_condensed_semibold", FontWeight.SemiBold),
        )
        mono = FontFamily(
            font("martian_mono_condensed_regular", FontWeight.Normal),
            font("martian_mono_condensed_medium", FontWeight.Medium),
        )
        handBytes = bytes.getValue("archivo_regular")
        val base = Typography()
        fun TextStyle.sans() = copy(fontFamily = sans)
        typography = Typography(
            displayLarge = base.displayLarge.sans(),
            displayMedium = base.displayMedium.sans(),
            displaySmall = base.displaySmall.sans(),
            headlineLarge = base.headlineLarge.sans(),
            headlineMedium = base.headlineMedium.sans(),
            headlineSmall = base.headlineSmall.sans(),
            titleLarge = base.titleLarge.sans(),
            titleMedium = base.titleMedium.sans(),
            titleSmall = base.titleSmall.sans(),
            bodyLarge = base.bodyLarge.sans(),
            bodyMedium = base.bodyMedium.sans(),
            bodySmall = base.bodySmall.sans(),
            labelLarge = base.labelLarge.sans(),
            labelMedium = base.labelMedium.sans(),
            labelSmall = base.labelSmall.sans(),
        )
    }
}

/** Archivo (licence SIL OFL 1.1) : titres et texte. */
val AppSans: FontFamily get() = WebFonts.sans

/** Archivo étroit : grands chiffres et titres serrés. */
val AppCondensed: FontFamily get() = WebFonts.condensed

/** Martian Mono étroit (licence SIL OFL 1.1) : données, axes des graphiques, heures, altitudes et unités. */
val AppMono: FontFamily get() = WebFonts.mono

/** Échelle typographique Material, entièrement en Archivo. */
val MeteoTypography: Typography get() = WebFonts.typography
