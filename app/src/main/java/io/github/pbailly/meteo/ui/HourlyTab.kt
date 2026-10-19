package io.github.pbailly.meteo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.ui.theme.PlexMono
import io.github.pbailly.meteo.ui.theme.RainChanceOnNavy

/** Au moins autant d'heures dans le bandeau, même tard le soir. */
private const val MIN_STRIP_HOURS = 6

/** Heures du bandeau : de l'heure en cours à la fin de la journée (au moins [MIN_STRIP_HOURS]). */
fun stripHours(forecast: Forecast): List<Int> {
    val h = forecast.hourly
    val start = nowIndex(h.time, forecast.utcOffsetSeconds)
    if (start >= h.size) return emptyList()
    val today = h.time[start].toLocalDate()
    var end = start
    while (end < h.size && h.time[end].toLocalDate() == today) end++
    return (start until minOf(h.size, maxOf(end, start + MIN_STRIP_HOURS))).toList()
}

/** Bandeau bleu marine des heures restantes : heure, pictogramme, probabilité de pluie, température. */
@Composable
fun HourlyStrip(forecast: Forecast, modifier: Modifier = Modifier) {
    val h = forecast.hourly
    val hours = stripHours(forecast)
    if (hours.isEmpty()) return
    LazyRow(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(NavyCard),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 12.dp),
    ) {
        items(hours) { i ->
            Column(Modifier.width(58.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "%02d".format(h.time[i].hour),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = PlexMono,
                )
                Spacer(Modifier.height(4.dp))
                WeatherIcon(h.weatherCode[i], h.isDay[i], size = 42.dp, dark = true)
                Spacer(Modifier.height(4.dp))
                Text(
                    Fmt.percent(h.precipitationProbability[i]).replace(" ", ""),
                    color = RainChanceOnNavy,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = PlexMono,
                )
                Text(Fmt.temp(h.temperature[i]), color = Color.White, fontSize = 24.sp)
            }
        }
    }
}
