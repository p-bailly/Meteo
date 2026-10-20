package io.github.pbailly.meteo.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.glance.appwidget.updateAll
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.data.Place
import io.github.pbailly.meteo.data.PlacesStore
import io.github.pbailly.meteo.data.SettingsStore
import io.github.pbailly.meteo.data.WeatherRepository
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException

/** Ce qu'affiche un widget : le lieu ouvert dans l'app et sa prévision (celle du cache de l'app, ou une nouvelle). */
class WidgetData(
    val place: Place?,
    val forecast: Forecast?,
    val fetchedAt: Long,
    /** Vrai quand on montre le cache faute de réseau. */
    val stale: Boolean,
    val colors: WidgetColors,
) {
    /** L'heure du lieu (et non celle du téléphone, s'il est ailleurs). */
    val now: LocalDateTime?
        get() = forecast?.let { LocalDateTime.now(ZoneOffset.ofTotalSeconds(it.utcOffsetSeconds)) }

    val today: LocalDate? get() = now?.toLocalDate()

    companion object {
        /**
         * Le cache de l'app sert tant qu'il a moins de 30 minutes : les widgets ne coûtent un appel à Open-Meteo que
         * quand l'app n'a pas été ouverte entre deux mises à jour.
         */
        suspend fun load(context: Context): WidgetData {
            val colors = WidgetColors.of(darkTheme(context))
            val place = PlacesStore(context).lastPlace() ?: return WidgetData(null, null, 0L, false, colors)
            val repository = WeatherRepository(File(context.cacheDir, "openmeteo"))
            return try {
                val loaded = repository.forecast(place, force = false)
                WidgetData(place, loaded.value, loaded.fetchedAt, loaded.stale, colors)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                WidgetData(place, null, 0L, true, colors)
            }
        }

        /** Le thème choisi dans les Réglages de l'app, sinon celui du téléphone. */
        private fun darkTheme(context: Context): Boolean = when (SettingsStore(context).theme()) {
            1 -> false
            2 -> true
            else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
    }
}

/** Couleurs « Glacier » des widgets, en clair et en sombre (les mêmes que l'app). */
class WidgetColors(
    val dark: Boolean,
    val background: Color,
    val ink: Color,
    val secondaryInk: Color,
    val muted: Color,
    val grid: Color,
    /** Orange de l'identité : maxima, heure actuelle. */
    val accent: Color,
    /** Bleu glacier : minima, pluie. */
    val primary: Color,
) {
    companion object {
        private val Light = WidgetColors(
            dark = false,
            background = Color(0xF2F7FAFC),
            ink = Color(0xFF0F2133),
            secondaryInk = Color(0xFF4B5F73),
            muted = Color(0xFF66798C),
            grid = Color(0xFFDCE6EF),
            accent = Color(0xFFE2701F),
            primary = Color(0xFF1C6497),
        )

        private val Dark = WidgetColors(
            dark = true,
            background = Color(0xF20F2133),
            ink = Color(0xFFEAF2F8),
            secondaryInk = Color(0xFFA9BDCF),
            muted = Color(0xFF7D93A8),
            grid = Color(0xFF1D3550),
            accent = Color(0xFFFF9E58),
            primary = Color(0xFF9BCBE9),
        )

        fun of(dark: Boolean) = if (dark) Dark else Light
    }
}

/** Mise à jour de tous les widgets : après un nouveau téléchargement dans l'app, un changement de lieu ou de thème. */
object Widgets {
    suspend fun updateAll(context: Context) {
        NowWidget().updateAll(context)
        ForecastWidget().updateAll(context)
        HoursWidget().updateAll(context)
    }
}
