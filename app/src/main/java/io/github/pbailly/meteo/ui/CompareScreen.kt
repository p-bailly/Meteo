package io.github.pbailly.meteo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.data.Outlook
import io.github.pbailly.meteo.data.Place
import java.time.LocalDate

/** Largeur d'une journée et hauteur d'une ligne de lieu (en sp, pour suivre la taille du texte). */
private val DAY_WIDTH = 62.sp
private val ROW_HEIGHT = 84.sp
private val NAME_WIDTH = 108.sp

/**
 * « Comparer » : les lieux (favoris, puis consultés récemment) côte à côte, jour par jour sur 8 jours : pictogramme,
 * maxi et mini, cumul de pluie. Les noms restent à gauche pendant que les jours défilent ; toucher un lieu l'ouvre.
 */
@Composable
fun CompareScreen(state: MeteoUiState, onSelect: (Place) -> Unit, onRefresh: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { onRefresh() }
    val colors = MaterialTheme.colorScheme
    val places = (state.favorites + state.recents).distinctBy { it.id }
    fun forecastOf(p: Place): Forecast? =
        state.summaries.data?.get(p.id) ?: state.forecast.data?.takeIf { p.id == state.place?.id }
    val days = places.firstNotNullOfOrNull { forecastOf(it) }?.daily?.date?.take(8).orEmpty()
    val dayWidth = DAY_WIDTH.asWidth()
    val rowHeight = ROW_HEIGHT.asWidth()
    val nameWidth = NAME_WIDTH.asWidth()

    Column(Modifier.fillMaxSize()) {
        DetailHeader("Comparer", onBack)
        if (days.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.summaries.loading) CircularProgressIndicator() else Text(state.summaries.error ?: "Pas encore de prévisions.")
            }
            return@Column
        }
        val today = days.first()
        Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 12.dp)) {
            // Noms des lieux, fixes.
            Column(Modifier.width(nameWidth)) {
                Box(Modifier.height(44.dp))
                places.forEach { place ->
                    val current = place.id == state.place?.id
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(rowHeight)
                            .background(if (current) colors.surfaceContainer else Color.Transparent)
                            .clickable { onSelect(place) }
                            .padding(start = 16.dp, end = 6.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(place.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp)
                        place.department?.let { MonoText(it, size = 9.5.sp) }
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                }
            }
            // Jours, à faire défiler.
            Column(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Row(Modifier.height(44.dp)) {
                    days.forEach { date -> DayHeader(date, today, Modifier.width(dayWidth)) }
                }
                places.forEach { place ->
                    val f = forecastOf(place)
                    val current = place.id == state.place?.id
                    Row(Modifier.height(rowHeight).background(if (current) colors.surfaceContainer else Color.Transparent)) {
                        days.forEach { date -> DayCell(f, date, Modifier.width(dayWidth).fillMaxHeight()) }
                    }
                    HorizontalDivider(Modifier.width(dayWidth * days.size), color = colors.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate, today: LocalDate, modifier: Modifier) {
    val weekend = date.dayOfWeek.value >= 6
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        MonoText(
            if (date == today) "auj." else Fmt.dayName(date),
            color = if (weekend || date == today) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
            bold = true,
            size = 11.sp,
        )
        MonoText(date.dayOfMonth.toString(), size = 10.sp)
    }
}

/** Un jour d'un lieu : pictogramme, maxi et mini, pluie. */
@Composable
private fun DayCell(f: Forecast?, date: LocalDate, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val d = f?.daily
        val i = d?.date?.indexOf(date) ?: -1
        if (f == null || d == null || i < 0) {
            MonoText("…")
            return@Column
        }
        WeatherIcon(Outlook.dayCode(f, date), true, size = 28.dp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Fmt.temp(d.temperatureMax[i]), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
            MonoText(" " + Fmt.temp(d.temperatureMin[i]), color = colors.primary, size = 10.5.sp)
        }
        val rain = d.precipitationSum[i]
        val wet = !rain.isNaN() && rain >= 0.1
        MonoText(
            if (rain.isNaN()) "–" else "${Fmt.decimal(rain)} mm",
            color = if (wet) colors.primary else colors.onSurfaceVariant,
            bold = wet,
            size = 10.sp,
        )
    }
}
