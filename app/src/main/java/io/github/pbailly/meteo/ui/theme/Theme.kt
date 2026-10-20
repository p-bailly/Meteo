package io.github.pbailly.meteo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Direction artistique « Glacier » (design/README.md) : neige, glace, glacier, ardoise, nuit, et un rouge
// de secours réservé aux alertes (verglas, froid ressenti extrême).

private val LightColors = lightColorScheme(
    primary = Color(0xFF1C6497),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6EAF7),
    onPrimaryContainer = Color(0xFF0F2133),
    secondary = Color(0xFF4B5F73),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EDF5),
    onSecondaryContainer = Color(0xFF0F2133),
    tertiary = Color(0xFFC8202A),
    onTertiary = Color.White,
    background = Color(0xFFF7FAFC),
    onBackground = Color(0xFF0F2133),
    surface = Color(0xFFF7FAFC),
    onSurface = Color(0xFF0F2133),
    surfaceVariant = Color(0xFFE3EDF5),
    onSurfaceVariant = Color(0xFF4B5F73),
    surfaceTint = Color(0xFF1C6497),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F7FB),
    surfaceContainer = Color(0xFFEAF2F8),
    surfaceContainerHigh = Color(0xFFE3EDF5),
    surfaceContainerHighest = Color(0xFFDCE8F2),
    outline = Color(0xFF7A8FA3),
    outlineVariant = Color(0xFFD3E1EC),
    error = Color(0xFFC8202A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9BCBE9),
    onPrimary = Color(0xFF0F2133),
    primaryContainer = Color(0xFF1C4F78),
    onPrimaryContainer = Color(0xFFD6EAF7),
    secondary = Color(0xFFB6C7D6),
    onSecondary = Color(0xFF0F2133),
    secondaryContainer = Color(0xFF22384F),
    onSecondaryContainer = Color(0xFFD6EAF7),
    tertiary = Color(0xFFFF8A80),
    onTertiary = Color(0xFF3A0508),
    background = Color(0xFF0B1826),
    onBackground = Color(0xFFEAF2F8),
    surface = Color(0xFF0F2133),
    onSurface = Color(0xFFEAF2F8),
    surfaceVariant = Color(0xFF1D3550),
    onSurfaceVariant = Color(0xFFA9BDCF),
    surfaceTint = Color(0xFF9BCBE9),
    surfaceContainerLowest = Color(0xFF0B1826),
    surfaceContainerLow = Color(0xFF0E1D2E),
    surfaceContainer = Color(0xFF122539),
    surfaceContainerHigh = Color(0xFF172D44),
    surfaceContainerHighest = Color(0xFF1D3550),
    outline = Color(0xFF6F859A),
    outlineVariant = Color(0xFF22384F),
    error = Color(0xFFFF8A80),
)

/** Couleurs des graphiques, déclinées pour le thème clair et le thème sombre. */
@Immutable
data class ChartColors(
    val ink: Color,
    val secondaryInk: Color,
    val muted: Color,
    val grid: Color,
    val axis: Color,
    /** Fond des zones de tracé (transparent en clair, bleu nuit en sombre pour que la nuit ressorte). */
    val plot: Color,
    val night: Color,
    val highlight: Color,
    val temperature: Color,
    val precipitation: Color,
    val snow: Color,
    val wind: Color,
    val pressure: Color,
    val cloud: Color,
    /** Orange de l'identité : soleil, heure actuelle, maxima. */
    val accent: Color,
    /** Une couleur par modèle, dans l'ordre de [io.github.pbailly.meteo.data.WeatherModel]. */
    val series: List<Color>,
    val darkTheme: Boolean,
)

private val LightChart = ChartColors(
    ink = Color(0xFF0F2133),
    secondaryInk = Color(0xFF4B5F73),
    muted = Color(0xFF66798C),
    grid = Color(0xFFDCE6EF),
    axis = Color(0xFFC3D3E0),
    plot = Color.Transparent,
    night = Color(0x141C6497),
    highlight = Color(0x140F2133),
    temperature = Color(0xFFD2453B),
    precipitation = Color(0xFF1C6497),
    snow = Color(0xFF5FAEDF),
    wind = Color(0xFF1BAF7A),
    pressure = Color(0xFF4B5F73),
    cloud = Color(0xFF4B5F73),
    accent = Color(0xFFE2701F),
    series = listOf(
        Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100),
        Color(0xFFE87BA4), Color(0xFF008300), Color(0xFF6250D6), Color(0xFFE34948),
    ),
    darkTheme = false,
)

private val DarkChart = ChartColors(
    ink = Color(0xFFEAF2F8),
    secondaryInk = Color(0xFFA9BDCF),
    muted = Color(0xFF7D93A8),
    grid = Color(0xFF1D3550),
    axis = Color(0xFF2A4560),
    plot = Color(0xFF0F2133),
    night = Color(0xFF0B1826),
    highlight = Color(0x1AEAF2F8),
    temperature = Color(0xFFFF7A70),
    precipitation = Color(0xFF6FB6E3),
    snow = Color(0xFFB9DDF5),
    wind = Color(0xFF199E70),
    pressure = Color(0xFFA9BDCF),
    cloud = Color(0xFFA9BDCF),
    accent = Color(0xFFFF9E58),
    series = listOf(
        Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500),
        Color(0xFFD55181), Color(0xFF008300), Color(0xFF9085E9), Color(0xFFE66767),
    ),
    darkTheme = true,
)

/** Bleu glacier de l'identité : légendes et étiquettes posées sur les cartes. */
val HeaderBlue = Color(0xFF1C6497)

val LocalChartColors = staticCompositionLocalOf { LightChart }

/** Échelle du texte pour chaque taille des Réglages : normale, grande, très grande. */
val TEXT_SCALES = floatArrayOf(1f, 1.15f, 1.3f)

/** Taille du texte choisie (0 = normale, 1 = grande, 2 = très grande) : les écrans chargés s'en servent pour s'alléger. */
val LocalTextSize = androidx.compose.runtime.staticCompositionLocalOf { 0 }

@Composable
fun MeteoTheme(darkTheme: Boolean = isSystemInDarkTheme(), textSize: Int = 0, content: @Composable () -> Unit) {
    // Texte agrandi : on multiplie l'échelle des polices du téléphone (taille normale : rien ne change).
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scaled = if (textSize > 0) {
        androidx.compose.ui.unit.Density(density.density, density.fontScale * TEXT_SCALES[textSize.coerceIn(0, 2)])
    } else {
        density
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalChartColors provides if (darkTheme) DarkChart else LightChart,
        LocalTextSize provides textSize,
        androidx.compose.ui.platform.LocalDensity provides scaled,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = MeteoTypography,
            content = content,
        )
    }
}
