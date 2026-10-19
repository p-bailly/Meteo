package io.github.pbailly.meteo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.pbailly.meteo.R

/** IBM Plex Sans (licence SIL OFL 1.1, voir licences/) : titres, températures et texte. */
val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_light, FontWeight.Light),
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)

/** IBM Plex Mono : données, axes des graphiques, heures et unités. */
val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

private val Base = Typography()

private fun TextStyle.plex() = copy(fontFamily = PlexSans)

/** Échelle typographique Material, entièrement en IBM Plex Sans. */
val MeteoTypography = Typography(
    displayLarge = Base.displayLarge.plex(),
    displayMedium = Base.displayMedium.plex(),
    displaySmall = Base.displaySmall.plex(),
    headlineLarge = Base.headlineLarge.plex(),
    headlineMedium = Base.headlineMedium.plex(),
    headlineSmall = Base.headlineSmall.plex(),
    titleLarge = Base.titleLarge.plex(),
    titleMedium = Base.titleMedium.plex(),
    titleSmall = Base.titleSmall.plex(),
    bodyLarge = Base.bodyLarge.plex(),
    bodyMedium = Base.bodyMedium.plex(),
    bodySmall = Base.bodySmall.plex(),
    labelLarge = Base.labelLarge.plex(),
    labelMedium = Base.labelMedium.plex(),
    labelSmall = Base.labelSmall.plex(),
)
