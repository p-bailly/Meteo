package io.github.pbailly.meteo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Warning
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pbailly.meteo.data.Altitude
import io.github.pbailly.meteo.data.AltitudeDay
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.data.IceRisk
import io.github.pbailly.meteo.data.Outlook
import io.github.pbailly.meteo.data.Place
import io.github.pbailly.meteo.data.SnowEvent
import io.github.pbailly.meteo.data.TerrainProfile
import io.github.pbailly.meteo.ui.theme.LocalChartColors
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/** Rouge d'alerte de la direction Glacier et son fond pâle. */
private val AlertRed = Color(0xFFC8202A)
private val AlertRedBackground = Color(0xFFFBE9EA)

/** Une ligne du tableau des jours, calculée une fois. */
private class DayLine(
    val index: Int,
    val date: LocalDate,
    val code: Int,
    val min: Double,
    val max: Double,
    val rain: Double,
    val altitude: AltitudeDay?,
    val snow: Double,
    /** Fenêtre de beau temps, « 9-17 h », ou null. */
    val window: String?,
)

/**
 * Onglet « Prévisions » : alertes (retour de la neige, verglas), puis un tableau des 7 ou 14 jours avec mini et
 * maxi, pluie, iso 0 °C et neige à 2 000 m, et les fenêtres de beau temps. Toucher un jour ouvre son détail.
 */
@Composable
fun DaysScreen(
    state: MeteoUiState,
    place: Place,
    onOpenDay: (Int) -> Unit,
    onOpenMeteograms: () -> Unit,
    onRefresh: () -> Unit,
) {
    var span by rememberSaveable { mutableIntStateOf(0) }
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
            val days = if (span == 0) 7 else 14
            val lines = allLines
            val today = f.daily.date.firstOrNull() ?: LocalDate.now()
            item(key = "alerts") { Alerts(f, place, today, terrain) }
            item(key = "header") { TableHeader() }
            val shown = lines.take(days)
            val low = shown.minOfOrNull { it.min.takeIf { v -> !v.isNaN() } ?: Double.MAX_VALUE } ?: 0.0
            val high = shown.maxOfOrNull { it.max.takeIf { v -> !v.isNaN() } ?: -Double.MAX_VALUE } ?: 1.0
            shown.forEachIndexed { k, line ->
                item(key = "day-${line.index}") {
                    DayTableRow(line, today, low, high, onClick = { onOpenDay(line.index) })
                    val next = shown.getOrNull(k + 1)
                    if (next != null && line.window == null && next.window == null) {
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            if (span == 0) lines.getOrNull(7)?.let { line ->
                item(key = "teaser") { Teaser(line, snowReference(f, terrain), onClick = { span = 1 }) }
            }
            item(key = "legend") {
                MonoText(
                    "pluie en mm · iso 0 en m · neige en 24 h à ${Fmt.altitude(snowReference(f, state.terrain.data))}",
                    size = 10.sp,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
            item(key = "meteograms") {
                LinkCard(Icons.Outlined.BarChart, "Météogrammes", "graphiques heure par heure et comparaison des modèles", onOpenMeteograms)
            }
            item(key = "attribution") { Attribution(updated = remote.fetchedAt) }
        }
    }
}

/** Neige à 2 000 m s'il y a des montagnes autour du lieu, sinon sur le lieu même. */
private fun snowReference(f: Forecast, terrain: TerrainProfile?): Double =
    Outlook.snowReference(Altitude.ground(f), terrain?.elevations?.maxOrNull())

/** Verglas : jusqu'au plus haut point des environs (sans relief connu, 300 m au-dessus du lieu). */
fun iceCeiling(f: Forecast, terrain: TerrainProfile?): Double =
    terrain?.elevations?.maxOrNull() ?: (Altitude.ground(f) + 300)

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
            altitude = alt,
            snow = alt?.let { Altitude.snow(f, it.hours, reference) } ?: 0.0,
            window = if (i < 7) {
                alt?.let { Altitude.fairWindow(f, it.hours, from = now) }?.let { w ->
                    "${f.hourly.time[w.first].hour}-${f.hourly.time[w.last].hour + 1} h"
                }
            } else {
                null
            },
        )
    }
}

/** Alertes de la semaine : le prochain épisode neigeux et le premier risque de verglas. */
@Composable
private fun Alerts(f: Forecast, place: Place, today: LocalDate, terrain: TerrainProfile?) {
    val snow = remember(f, terrain) { Outlook.snowEvent(f, today, reference = snowReference(f, terrain)) }
    val ice = remember(f) {
        val now = Outlook.nowIndex(f)
        Altitude.iceRisks(f, now, now + 8 * 24, iceCeiling(f, terrain)).firstOrNull()
    }
    if (snow == null && ice == null) return
    Column(Modifier.padding(top = 12.dp)) {
        snow?.let { SnowCard(it, today) }
        ice?.let { IceCard(it, place, today) }
    }
}

@Composable
private fun SnowCard(event: SnowEvent, today: LocalDate) {
    val first = event.days.first()
    val last = event.days.last()
    val span = if (first.date == last.date) {
        Outlook.dayLabel(first.date, today)
    } else {
        "${Outlook.dayLabel(first.date, today)} → ${Outlook.dayLabel(last.date, today)}"
    }
    val lines = event.days.mapNotNull { it.snowLine }
    val limit = when {
        lines.isEmpty() -> null
        Math.abs(lines.first() - lines.last()) < 100 -> "limite pluie-neige ${Fmt.altitude(lines.last(), 100)}"
        else -> "limite pluie-neige ${Fmt.thousands(lines.first(), 100)} → ${Fmt.altitude(lines.last(), 100)}"
    }
    val amounts = event.amounts.map { "+${it.roundToInt()}" }
    val amountText = when (amounts.size) {
        1 -> amounts[0]
        else -> amounts.dropLast(1).joinToString(", ") + " puis " + amounts.last()
    } + " cm à ${Fmt.altitude(event.reference)}"
    val iso = event.freezingLevel.takeIf { it < Double.MAX_VALUE }?.let { " · iso 0 à ${Fmt.thousands(it, 100)}" }.orEmpty()
    AlertCard(
        icon = Icons.Outlined.AcUnit,
        title = (if (event.afterDryDays) "Retour de la neige · " else "Neige · ") + span,
        lines = listOfNotNull(limit, amountText + iso),
        accent = MaterialTheme.colorScheme.primary,
        background = MaterialTheme.colorScheme.secondaryContainer,
    )
}

@Composable
private fun IceCard(risk: IceRisk, place: Place, today: LocalDate) {
    // « mar. 27, 6–10 h », ou sur une nuit « lun. 26, 23 h → 9 h ».
    val hours = if (risk.start.toLocalDate() == risk.end.toLocalDate()) {
        "${risk.start.hour}–${risk.end.hour + 1} h"
    } else {
        "${risk.start.hour} h → ${risk.end.hour + 1} h"
    }
    val where = when {
        risk.freezingRain -> "pluie verglaçante possible à ${place.name}"
        risk.atPlace -> "à ${place.name} et sur les routes au-dessus"
        else -> "routes au-dessus de ${Fmt.altitude(risk.altitude, 100)}"
    }
    val detail = (if (risk.atPlace) "" else "à ${Fmt.altitude(risk.referenceAltitude, 100)} : ") + "mini ${Fmt.temp(risk.minTemperature)} · " +
        if (risk.freezingRain) "pluie qui gèle au sol" else "sol mouillé par la pluie"
    AlertCard(
        icon = Icons.Outlined.Warning,
        title = "Verglas possible · ${Outlook.dayLabel(risk.start.toLocalDate(), today)}, $hours",
        lines = listOf(where, detail),
        accent = AlertRed,
        background = if (LocalChartColors.current.darkTheme) AlertRed.copy(alpha = 0.18f) else AlertRedBackground,
    )
}

/** Carte d'alerte : filet de couleur à gauche, pictogramme, titre en couleur et deux lignes en Martian Mono. */
@Composable
private fun AlertCard(icon: ImageVector, title: String, lines: List<String>, accent: Color, background: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(14.dp))
            .background(background),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.padding(start = 14.dp, top = 14.dp).size(22.dp))
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
            Text(title, color = accent, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            lines.forEachIndexed { k, line ->
                MonoText(
                    line,
                    color = if (k == 0) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 11.sp,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

private val DAY_WIDTH = 64.dp
private val ICON_WIDTH = 34.dp
private val RAIN_WIDTH = 42.dp
private val ISO_WIDTH = 52.dp
private val SNOW_WIDTH = 48.dp

@Composable
private fun TableHeader() {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)) {
        HeaderCell("JOUR", DAY_WIDTH + ICON_WIDTH)
        HeaderCell("MINI · MAXI", null, Modifier.weight(1f).padding(start = 8.dp))
        HeaderCell("PLUIE", RAIN_WIDTH, align = TextAlign.End)
        HeaderCell("ISO 0", ISO_WIDTH, align = TextAlign.End)
        HeaderCell("NEIGE", SNOW_WIDTH, align = TextAlign.End)
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun HeaderCell(text: String, width: Dp?, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    Text(
        text,
        fontFamily = io.github.pbailly.meteo.ui.theme.AppMono,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        maxLines = 1,
        modifier = if (width != null) modifier.width(width) else modifier,
    )
}

@Composable
private fun DayTableRow(line: DayLine, today: LocalDate, low: Double, high: Double, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val highlighted = line.window != null
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = if (highlighted) 10.dp else 0.dp, vertical = if (highlighted) 3.dp else 0.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlighted) colors.surfaceContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = if (highlighted) 8.dp else 18.dp, end = if (highlighted) 6.dp else 16.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(DAY_WIDTH)) {
                Text(
                    if (line.date == today) "Auj." else Fmt.dayName(line.date).removeSuffix(".").replaceFirstChar { it.titlecase(Locale.FRANCE) },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                MonoText(Fmt.dayMonth(line.date), size = 10.sp)
            }
            Box(Modifier.width(ICON_WIDTH)) { WeatherIcon(line.code, true, size = 28.dp) }
            Row(Modifier.weight(1f).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                MonoText(Fmt.temp(line.min), size = 12.sp, modifier = Modifier.width(30.dp))
                TemperatureBar(line.min, line.max, low, high, Modifier.weight(1f).height(8.dp))
                Text(
                    Fmt.temp(line.max),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(36.dp),
                )
            }
            val wet = !line.rain.isNaN() && line.rain >= 0.1
            MonoText(
                Fmt.decimal(line.rain),
                color = if (wet) colors.primary else colors.onSurfaceVariant,
                bold = wet,
                size = 12.5.sp,
                align = TextAlign.End,
                modifier = Modifier.width(RAIN_WIDTH),
            )
            MonoText(
                line.altitude?.freezingLevel?.let { Fmt.thousands(it, 100) } ?: "–",
                color = colors.onBackground,
                bold = true,
                size = 12.5.sp,
                align = TextAlign.End,
                modifier = Modifier.width(ISO_WIDTH),
            )
            Text(
                if (line.snow >= 1) "+${line.snow.roundToInt()} cm" else "–",
                color = if (line.snow >= 1) colors.primary else colors.onSurfaceVariant,
                fontWeight = if (line.snow >= 1) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(SNOW_WIDTH),
            )
        }
        line.window?.let { window ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = DAY_WIDTH, top = 6.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(colors.primary))
                Spacer(Modifier.width(6.dp))
                MonoText(
                    "Fenêtre $window · sec, vent < 20 km/h",
                    color = colors.primary,
                    size = 10.5.sp,
                )
            }
        }
    }
}

/** Barre mini-maxi, placée sur l'échelle de la semaine affichée. */
@Composable
private fun TemperatureBar(min: Double, max: Double, low: Double, high: Double, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val fill = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, size = size, cornerRadius = r)
        if (min.isNaN() || max.isNaN() || high <= low) return@Canvas
        val x0 = ((min - low) / (high - low) * size.width).toFloat()
        val x1 = ((max - low) / (high - low) * size.width).toFloat().coerceAtLeast(x0 + size.height)
        drawRoundRect(fill, topLeft = Offset(x0, 0f), size = Size(x1 - x0, size.height), cornerRadius = r)
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
    val detail = listOfNotNull(
        line.altitude?.snowLine?.let { "limite ${Fmt.altitude(it, 100)}" },
        "${Fmt.temp(line.max)} / ${Fmt.temp(line.min)}",
        "voir 14 jours",
    ).joinToString(" · ")
    LinkCard(null, title, detail, onClick, weatherCode = line.code)
}

/** Carte cliquable avec un chevron à droite. */
@Composable
private fun LinkCard(icon: ImageVector?, title: String, detail: String, onClick: () -> Unit, weatherCode: Int? = null) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
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
