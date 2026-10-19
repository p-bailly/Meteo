package io.github.pbailly.meteo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.pbailly.meteo.R

/**
 * Archivo (licence SIL OFL 1.1, voir licences/) : titres et texte. La graisse Light est la coupe étroite,
 * pour que les grandes températures (écrites en Light) prennent l'allure serrée de la direction Glacier.
 */
val AppSans = FontFamily(
    Font(R.font.archivo_condensed_light, FontWeight.Light),
    Font(R.font.archivo_regular, FontWeight.Normal),
    Font(R.font.archivo_medium, FontWeight.Medium),
    Font(R.font.archivo_semibold, FontWeight.SemiBold),
)

/** Archivo étroit : grands chiffres et titres serrés. */
val AppCondensed = FontFamily(
    Font(R.font.archivo_condensed_light, FontWeight.Light),
    Font(R.font.archivo_condensed_regular, FontWeight.Normal),
    Font(R.font.archivo_condensed_semibold, FontWeight.SemiBold),
)

/** Martian Mono étroit (licence SIL OFL 1.1) : données, axes des graphiques, heures, altitudes et unités. */
val AppMono = FontFamily(
    Font(R.font.martian_mono_condensed_regular, FontWeight.Normal),
    Font(R.font.martian_mono_condensed_medium, FontWeight.Medium),
)

private val Base = Typography()

private fun TextStyle.sans() = copy(fontFamily = AppSans)

/** Échelle typographique Material, entièrement en Archivo. */
val MeteoTypography = Typography(
    displayLarge = Base.displayLarge.sans(),
    displayMedium = Base.displayMedium.sans(),
    displaySmall = Base.displaySmall.sans(),
    headlineLarge = Base.headlineLarge.sans(),
    headlineMedium = Base.headlineMedium.sans(),
    headlineSmall = Base.headlineSmall.sans(),
    titleLarge = Base.titleLarge.sans(),
    titleMedium = Base.titleMedium.sans(),
    titleSmall = Base.titleSmall.sans(),
    bodyLarge = Base.bodyLarge.sans(),
    bodyMedium = Base.bodyMedium.sans(),
    bodySmall = Base.bodySmall.sans(),
    labelLarge = Base.labelLarge.sans(),
    labelMedium = Base.labelMedium.sans(),
    labelSmall = Base.labelSmall.sans(),
)
