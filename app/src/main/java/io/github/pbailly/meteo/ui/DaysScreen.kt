package io.github.pbailly.meteo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pbailly.meteo.data.Altitude
import io.github.pbailly.meteo.data.AltitudeDay
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.data.Outlook
import io.github.pbailly.meteo.data.Place
import io.github.pbailly.meteo.data.TerrainProfile
import io.github.pbailly.meteo.ui.theme.LocalChartColors
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/** Une ligne du tableau des jours, calculée une fois. */
private class DayLine(
    val index: Int,
    val date: LocalDate,
    val code: Int,
    val min: Double,
    val max: Double,
    val rain: Double,
    /** Vent maximal du jour (km/h) et sa direction dominante (d'où il vient, en degrés). */
    val wind: Double,
    val windDirection: Double,
    /** Ensoleillement, en secondes. */
    val sunshine: Double,
    val altitude: AltitudeDay?,
    val snow: Double,
    /** Iso 0 °C maintenant, pour la ligne d'aujourd'hui seulement. */
    val freezingNow: Double? = null,
)

/**
 * Onglet « Prévisions » : les 14 (ou 7) jours, avec pour chacun le ciel, le mini et le maxi, puis la pluie, le vent
 * (et sa direction) et l'ensoleillement l'un sous l'autre ; la neige s'il en tombe et l'iso 0 °C d'aujourd'hui.
 * Toucher un jour ouvre son détail heure par heure.
 */
@Composable
fun DaysScreen(
    state: MeteoUiState,
    place: Place,
    onOpenDay: (Int) -> Unit,
    onRefresh: () -> Unit,
) {
    var span by rememberSaveable { mutableIntStateOf(1) }
    val remote = state.forecast
    val terrain = state.terrain.data
    val allLines = remember(remote.data, terrain) { remote.data?.let { dayLines(it, snowReference(it, terrain)) }.orEmpty() }
    PullToRefreshBox(isRefreshing = remote.loading && remote.data != null, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        val f = remote.data
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "title") {
                ScreenHeader("Prévisions") {
                    SegmentedToggle(listOf("7 jours", "14 jours"), span, { span = it })
                }
                PlaceLine(place.name, place.elevation ?: f?.elevation)
                StatusLine(statusText(remote), Modifier.padding(top = 6.dp))
                Gap(8.dp)
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (f == null) {
                item(key = "loading") {
                    if (remote.error != null) {
                        ErrorState(remote.error, onRefresh)
                    } else {
                        Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }
                return@LazyColumn
            }
            val today = f.daily.date.firstOrNull() ?: LocalDate.now()
            val shown = allLines.take(if (span == 0) 7 else 14)
            val low = shown.minOfOrNull { it.min.takeIf { v -> !v.isNaN() } ?: Double.MAX_VALUE } ?: 0.0
            val high = shown.maxOfOrNull { it.max.takeIf { v -> !v.isNaN() } ?: -Double.MAX_VALUE } ?: 1.0
            shown.forEach { line ->
                item(key = "day-${line.index}") {
                    DayTableRow(line, today, low, high, snowReference(f, terrain), onClick = { onOpenDay(line.index) })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (span == 0) allLines.getOrNull(7)?.let { line ->
                item(key = "teaser") { Teaser(line, snowReference(f, terrain), onClick = { span = 1 }) }
            }
            item(key = "end") { Gap(16.dp) }
        }
    }
}

/** Neige à 2 000 m s'il y a des montagnes autour du lieu, sinon sur le lieu même. */
private fun snowReference(f: Forecast, terrain: TerrainProfile?): Double =
    Outlook.snowReference(Altitude.ground(f), terrain?.elevations?.maxOrNull())

private fun dayLines(f: Forecast, reference: Double): List<DayLine> {
    val d = f.daily
    val altitudeDays = Altitude.days(f).associateBy { it.date }
    val now = Outlook.nowIndex(f)
    return (0 until d.size).map { i ->
        val alt = altitudeDays[d.date[i]]
        DayLine(
            index = i,
            date = d.date[i],
            code = Outlook.dayCode(f, d.date[i]),
            min = d.temperatureMin[i],
            max = d.temperatureMax[i],
            rain = d.precipitationSum[i],
            wind = d.windSpeedMax[i],
            windDirection = d.windDirectionDominant[i],
            sunshine = d.sunshineDuration[i],
            altitude = alt,
            snow = alt?.let { Altitude.snow(f, it.hours, reference) } ?: 0.0,
            freezingNow = if (i == 0) Altitude.freezingLevel(f, now).takeIf { !it.isNaN() } else null,
        )
    }
}

private val DAY_WIDTH = 54.dp
private val ICON_WIDTH = 36.dp
private val DETAILS_WIDTH = 92.dp

@Composable
private fun DayTableRow(line: DayLine, today: LocalDate, low: Double, high: Double, reference: Double, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val chart = LocalChartColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 18.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(DAY_WIDTH)) {
                Text(
                    if (line.date == today) "Auj." else Fmt.dayName(line.date).removeSuffix(".").replaceFirstChar { it.titlecase(Locale.FRANCE) },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                )
                MonoText(Fmt.dayMonth(line.date), size = 9.5.sp)
            }
            Box(Modifier.width(ICON_WIDTH)) { WeatherIcon(line.code, true, size = 30.dp) }
            Row(Modifier.weight(1f).padding(start = 4.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                MonoText(Fmt.temp(line.min), color = colors.primary, size = 12.sp, modifier = Modifier.width(30.dp))
                TemperatureBar(line.min, line.max, low, high, Modifier.weight(1f).height(7.dp))
                Text(
                    Fmt.temp(line.max),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.width(36.dp),
                )
            }
            // Pluie, vent et soleil l'un sous l'autre, chacun avec son pictogramme.
            Column(Modifier.width(DETAILS_WIDTH)) {
                val wet = !line.rain.isNaN() && line.rain >= 0.1
                Detail(icon = { Icon(Icons.Outlined.WaterDrop, null, tint = colors.primary, modifier = Modifier.size(13.dp)) }) {
                    MonoText("${Fmt.decimal(line.rain)} mm", color = if (wet) colors.primary else colors.onSurfaceVariant, bold = wet, size = 11.5.sp)
                }
                Detail(icon = {
                    Canvas(Modifier.size(13.dp)) {
                        drawWindArrow(center, line.windDirection, size.minDimension, chart.secondaryInk, 1.5.dp.toPx())
                    }
                }) {
                    val windy = !line.wind.isNaN() && line.wind >= 40
                    MonoText(
                        if (line.wind.isNaN()) "–" else "${line.wind.roundToInt()} km/h",
                        color = if (windy) Color(0xFFC8202A) else colors.onBackground,
                        bold = windy,
                        size = 11.5.sp,
                    )
                }
                Detail(icon = { Icon(Icons.Outlined.WbSunny, null, tint = chart.accent, modifier = Modifier.size(13.dp)) }) {
                    MonoText("${sunshineHours(line.sunshine)} h", color = colors.onBackground, size = 11.5.sp)
                }
            }
        }
        // Neige attendue, sur sa propre ligne sous le jour.
        if (line.snow >= 1) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = DAY_WIDTH, top = 4.dp)) {
                Icon(Icons.Outlined.AcUnit, null, tint = colors.primary, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                MonoText("neige : +${line.snow.roundToInt()} cm à ${Fmt.altitude(reference)}", color = colors.primary, bold = true, size = 11.sp)
            }
        }
        line.freezingNow?.let { iso ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = DAY_WIDTH, top = 4.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(colors.primary))
                Spacer(Modifier.width(6.dp))
                MonoText("iso 0 °C maintenant : ${Fmt.altitude(iso, 100)}", size = 10.5.sp)
            }
        }
    }
}

@Composable
private fun Detail(icon: @Composable () -> Unit, text: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
        Box(Modifier.width(18.dp), contentAlignment = Alignment.CenterStart) { icon() }
        text()
    }
}

/** Ensoleillement en heures : « 0 », « 4,5 », « 9 ». */
private fun sunshineHours(seconds: Double): String = when {
    seconds.isNaN() -> "–"
    seconds < 900 -> "0"
    else -> Fmt.decimal(Math.round(seconds / 1800.0) / 2.0)
}

/** Barre mini-maxi sur l'échelle des jours affichés, du bleu (mini) à l'orange (maxi). */
@Composable
private fun TemperatureBar(min: Double, max: Double, low: Double, high: Double, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val cold = MaterialTheme.colorScheme.primary
    val warm = LocalChartColors.current.accent
    Canvas(modifier) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, size = size, cornerRadius = r)
        if (min.isNaN() || max.isNaN() || high <= low) return@Canvas
        val x0 = ((min - low) / (high - low) * size.width).toFloat()
        val x1 = ((max - low) / (high - low) * size.width).toFloat().coerceAtLeast(x0 + size.height)
        drawRoundRect(
            Brush.horizontalGradient(listOf(cold, warm), startX = 0f, endX = size.width),
            topLeft = Offset(x0, 0f),
            size = Size(x1 - x0, size.height),
            cornerRadius = r,
        )
    }
}

/** Le 8e jour en résumé, sous le tableau des 7 jours. */
@Composable
private fun Teaser(line: DayLine, reference: Double, onClick: () -> Unit) {
    val title = Fmt.dayShort(line.date).replaceFirstChar { it.titlecase(Locale.FRANCE) } + " · " + when {
        line.snow >= 1 -> "+${line.snow.roundToInt()} cm à ${Fmt.altitude(reference)}"
        line.rain >= 1 -> "${Fmt.decimal(line.rain)} mm de pluie"
        else -> weatherDescription(line.code)
    }
    val detail = "${Fmt.temp(line.max)} / ${Fmt.temp(line.min)} · voir 14 jours"
    LinkCard(null, title, detail, onClick, weatherCode = line.code)
}

/** Carte cliquable avec un chevron à droite. */
@Composable
private fun LinkCard(icon: ImageVector?, title: String, detail: String, onClick: () -> Unit, weatherCode: Int? = null) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(14.dp))
            .background(colors.surfaceContainerLowest)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            weatherCode != null -> WeatherIcon(weatherCode, true, size = 30.dp)
            icon != null -> Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(26.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
            MonoText(detail, size = 10.sp, maxLines = 2)
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = colors.primary)
    }
}
