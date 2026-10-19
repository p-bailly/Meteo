package io.github.pbailly.meteo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF256ABF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE2FB),
    onPrimaryContainer = Color(0xFF0D366B),
    secondary = Color(0xFF1C5CAB),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE2FB),
    onSecondaryContainer = Color(0xFF0D366B),
    background = Color(0xFFF9F9F7),
    onBackground = Color(0xFF0B0B0B),
    surface = Color(0xFFFCFCFB),
    onSurface = Color(0xFF0B0B0B),
    surfaceVariant = Color(0xFFF0EFEC),
    onSurfaceVariant = Color(0xFF52514E),
    surfaceTint = Color(0xFF256ABF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9F9F7),
    surfaceContainer = Color(0xFFF4F4F1),
    surfaceContainerHigh = Color(0xFFEFEEEA),
    surfaceContainerHighest = Color(0xFFE9E8E3),
    outline = Color(0xFF898781),
    outlineVariant = Color(0xFFE1E0D9),
    error = Color(0xFFD03B3B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF86B6EF),
    onPrimary = Color(0xFF0D366B),
    primaryContainer = Color(0xFF184F95),
    onPrimaryContainer = Color(0xFFCDE2FB),
    secondary = Color(0xFF6DA7EC),
    onSecondary = Color(0xFF0D366B),
    secondaryContainer = Color(0xFF184F95),
    onSecondaryContainer = Color(0xFFCDE2FB),
    background = Color(0xFF0D0D0D),
    onBackground = Color(0xFFF0EFEC),
    surface = Color(0xFF1A1A19),
    onSurface = Color(0xFFF0EFEC),
    surfaceVariant = Color(0xFF2C2C2A),
    onSurfaceVariant = Color(0xFFC3C2B7),
    surfaceTint = Color(0xFF86B6EF),
    surfaceContainerLowest = Color(0xFF0D0D0D),
    surfaceContainerLow = Color(0xFF161615),
    surfaceContainer = Color(0xFF1A1A19),
    surfaceContainerHigh = Color(0xFF222221),
    surfaceContainerHighest = Color(0xFF2C2C2A),
    outline = Color(0xFF898781),
    outlineVariant = Color(0xFF383835),
    error = Color(0xFFE66767),
)

/** Couleurs des graphiques, déclinées pour le thème clair et le thème sombre. */
@Immutable
data class ChartColors(
    val ink: Color,
    val secondaryInk: Color,
    val muted: Color,
    val grid: Color,
    val axis: Color,
    /** Fond des zones de tracé (transparent en clair, gris foncé en sombre pour que la nuit ressorte). */
    val plot: Color,
    val night: Color,
    val highlight: Color,
    val temperature: Color,
    val precipitation: Color,
    val snow: Color,
    val wind: Color,
    val pressure: Color,
    val cloud: Color,
    /** Une couleur par modèle, dans l'ordre de [io.github.pbailly.meteo.data.WeatherModel]. */
    val series: List<Color>,
    val darkTheme: Boolean,
)

private val LightChart = ChartColors(
    ink = Color(0xFF0B0B0B),
    secondaryInk = Color(0xFF52514E),
    muted = Color(0xFF898781),
    grid = Color(0xFFE1E0D9),
    axis = Color(0xFFC3C2B7),
    plot = Color.Transparent,
    night = Color(0x140D366B),
    highlight = Color(0x140B0B0B),
    temperature = Color(0xFFE34948),
    precipitation = Color(0xFF2A78D6),
    snow = Color(0xFF6250D6),
    wind = Color(0xFF1BAF7A),
    pressure = Color(0xFF52514E),
    cloud = Color(0xFF52514E),
    series = listOf(
        Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100),
        Color(0xFFE87BA4), Color(0xFF008300), Color(0xFF6250D6), Color(0xFFE34948),
    ),
    darkTheme = false,
)

private val DarkChart = ChartColors(
    ink = Color(0xFFF0EFEC),
    secondaryInk = Color(0xFFC3C2B7),
    muted = Color(0xFF898781),
    grid = Color(0xFF2C2C2A),
    axis = Color(0xFF383835),
    plot = Color(0xFF1A1A19),
    night = Color(0xFF0D0D0D),
    highlight = Color(0x1AF0EFEC),
    temperature = Color(0xFFE66767),
    precipitation = Color(0xFF3987E5),
    snow = Color(0xFF9085E9),
    wind = Color(0xFF199E70),
    pressure = Color(0xFFC3C2B7),
    cloud = Color(0xFFC3C2B7),
    series = listOf(
        Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500),
        Color(0xFFD55181), Color(0xFF008300), Color(0xFF9085E9), Color(0xFFE66767),
    ),
    darkTheme = true,
)

/** Bleu marine de Meteoblue : bandeau du haut et cartes « heure par heure ». */
val HeaderBlue = Color(0xFF0C304E)
val HeaderBlueDark = Color(0xFF0A2238)

/** Texte de probabilité de pluie sur fond marine. */
val RainChanceOnNavy = Color(0xFFA3CEF5)

val LocalChartColors = staticCompositionLocalOf { LightChart }

@Composable
fun MeteoTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalChartColors provides if (darkTheme) DarkChart else LightChart,
    ) {
        MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
    }
}
