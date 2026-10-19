package io.github.pbailly.meteo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Direction artistique « Isobare » (design/README.md) : marine, encre, papier, et les deux couleurs
// des fronts météo, froid (bleu) et chaud (rouge).

private val LightColors = lightColorScheme(
    primary = Color(0xFF0C304E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E6F7),
    onPrimaryContainer = Color(0xFF0C304E),
    secondary = Color(0xFF1F6FAE),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCEBF8),
    onSecondaryContainer = Color(0xFF0C304E),
    background = Color(0xFFF4F6F8),
    onBackground = Color(0xFF0B1B2B),
    surface = Color(0xFFF7F9FA),
    onSurface = Color(0xFF0B1B2B),
    surfaceVariant = Color(0xFFE7ECF0),
    onSurfaceVariant = Color(0xFF46525E),
    surfaceTint = Color(0xFF0C304E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F9FA),
    surfaceContainer = Color(0xFFEDF1F4),
    surfaceContainerHigh = Color(0xFFE7ECF0),
    surfaceContainerHighest = Color(0xFFE1E7EC),
    outline = Color(0xFF7D8894),
    outlineVariant = Color(0xFFD3DBE2),
    error = Color(0xFFC8372D),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7CC4F8),
    onPrimary = Color(0xFF06263F),
    primaryContainer = Color(0xFF12456E),
    onPrimaryContainer = Color(0xFFD3E6F7),
    secondary = Color(0xFF8FC2EA),
    onSecondary = Color(0xFF0C304E),
    secondaryContainer = Color(0xFF163E60),
    onSecondaryContainer = Color(0xFFD3E6F7),
    background = Color(0xFF07111A),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0D1A26),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1C3044),
    onSurfaceVariant = Color(0xFFA9B8C6),
    surfaceTint = Color(0xFF7CC4F8),
    surfaceContainerLowest = Color(0xFF07111A),
    surfaceContainerLow = Color(0xFF0A1520),
    surfaceContainer = Color(0xFF0F1E2B),
    surfaceContainerHigh = Color(0xFF152636),
    surfaceContainerHighest = Color(0xFF1C3044),
    outline = Color(0xFF6F8091),
    outlineVariant = Color(0xFF22384C),
    error = Color(0xFFFF6B5F),
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
    /** Une couleur par modèle, dans l'ordre de [io.github.pbailly.meteo.data.WeatherModel]. */
    val series: List<Color>,
    val darkTheme: Boolean,
)

private val LightChart = ChartColors(
    ink = Color(0xFF0B1B2B),
    secondaryInk = Color(0xFF46525E),
    muted = Color(0xFF6B7785),
    grid = Color(0xFFDCE3EA),
    axis = Color(0xFFC9D3DD),
    plot = Color.Transparent,
    night = Color(0x140C304E),
    highlight = Color(0x140B1B2B),
    temperature = Color(0xFFF04A3E),
    precipitation = Color(0xFF2A78D6),
    snow = Color(0xFF6250D6),
    wind = Color(0xFF1BAF7A),
    pressure = Color(0xFF46525E),
    cloud = Color(0xFF46525E),
    series = listOf(
        Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100),
        Color(0xFFE87BA4), Color(0xFF008300), Color(0xFF6250D6), Color(0xFFE34948),
    ),
    darkTheme = false,
)

private val DarkChart = ChartColors(
    ink = Color(0xFFE6EDF3),
    secondaryInk = Color(0xFFA9B8C6),
    muted = Color(0xFF7D8C9A),
    grid = Color(0xFF1C2E3F),
    axis = Color(0xFF2A3F52),
    plot = Color(0xFF0D1A26),
    night = Color(0xFF07111A),
    highlight = Color(0x1AE6EDF3),
    temperature = Color(0xFFFF6B5F),
    precipitation = Color(0xFF4AA8F0),
    snow = Color(0xFF9085E9),
    wind = Color(0xFF199E70),
    pressure = Color(0xFFA9B8C6),
    cloud = Color(0xFFA9B8C6),
    series = listOf(
        Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500),
        Color(0xFFD55181), Color(0xFF008300), Color(0xFF9085E9), Color(0xFFE66767),
    ),
    darkTheme = true,
)

/** Bleu marine de l'identité : barres du haut, frise radar et cartes « heure par heure ». */
val HeaderBlue = Color(0xFF0C304E)
val HeaderBlueDark = Color(0xFF0A2640)

/** Symboles des fronts météo (bande sous les barres du haut, logo). */
val FrontCold = Color(0xFF3FA9F5)
val FrontWarm = Color(0xFFFF5A4E)

/** Texte secondaire et probabilité de pluie sur fond marine. */
val SecondaryOnNavy = Color(0xFFA9C3DA)
val RainChanceOnNavy = Color(0xFF8CCBF8)

val LocalChartColors = staticCompositionLocalOf { LightChart }

/** Marine des barres du haut, un peu plus sombre en thème sombre. */
@Composable
fun headerBlue(): Color = if (LocalChartColors.current.darkTheme) HeaderBlueDark else HeaderBlue

@Composable
fun MeteoTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalChartColors provides if (darkTheme) DarkChart else LightChart,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = MeteoTypography,
            content = content,
        )
    }
}
