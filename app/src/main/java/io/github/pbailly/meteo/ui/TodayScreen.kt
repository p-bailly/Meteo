package io.github.pbailly.meteo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pbailly.meteo.data.Altitude
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.data.Outlook
import io.github.pbailly.meteo.data.Place
import io.github.pbailly.meteo.data.TerrainProfile
import io.github.pbailly.meteo.ui.theme.AppCondensed
import io.github.pbailly.meteo.ui.theme.AppMono
import io.github.pbailly.meteo.ui.theme.AppSans
import io.github.pbailly.meteo.ui.theme.LocalChartColors
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Onglet « Aujourd'hui » : le lieu, la température en grand, la météo en altitude (coupe du relief, iso 0 °C,
 * limite pluie-neige), les mesures du moment et les prochaines heures.
 */
@Composable
fun TodayScreen(
    state: MeteoUiState,
    place: Place,
    onToggleFavorite: () -> Unit,
    onOpenPlaces: () -> Unit,
    onRefresh: () -> Unit,
) {
    val remote = state.forecast
    PullToRefreshBox(
        isRefreshing = remote.loading && remote.data != null,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        val f = remote.data
        when {
            f == null && remote.error != null -> ErrorState(remote.error, onRefresh)
            f == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item(key = "header") { TodayHeader(f, place, state.isFavorite, onToggleFavorite, onOpenPlaces) }
                item(key = "status") { StatusLine(statusText(remote)) }
                item(key = "now") { NowBlock(f) }
                item(key = "altitude") { AltitudeNowCard(f, state.terrain.data) }
                item(key = "tiles") { MetricsGrid(f) }
                item(key = "hours") { NextHours(f) }
                item(key = "attribution") {
                    Attribution(updated = remote.fetchedAt)
                }
            }
        }
    }
}

@Composable
private fun TodayHeader(f: Forecast, place: Place, favorite: Boolean, onToggleFavorite: () -> Unit, onOpenPlaces: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val elevation = place.elevation ?: f.elevation
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 18.dp, end = 16.dp, top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clickable(onClick = onOpenPlaces)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Place, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    place.name,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!elevation.isNaN()) {
                    Spacer(Modifier.width(8.dp))
                    MonoText(
                        Fmt.altitude(elevation),
                        color = colors.primary,
                        size = 11.sp,
                        bold = true,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(colors.primaryContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            val time = f.current?.time ?: LocalDateTime.now()
            MonoText(
                listOfNotNull(
                    (place.admin1 ?: place.country)?.takeIf { it.isNotBlank() },
                    Fmt.dateLine(time.toLocalDate()),
                    Fmt.clock(time),
                ).joinToString(" · "),
                modifier = Modifier.padding(start = 2.dp, top = 4.dp),
            )
        }
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .border(1.dp, colors.outlineVariant, CircleShape)
                .clickable(onClick = onToggleFavorite),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (favorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = if (favorite) "Retirer des favoris" else "Ajouter aux favoris",
                tint = colors.primary,
            )
        }
    }
}

/** « 14 °C » en grand, le ciel à droite, le ressenti et les extrêmes du jour. */
@Composable
private fun NowBlock(f: Forecast) {
    val c = f.current ?: return
    val today = f.daily.date.indexOf(c.time.toLocalDate()).coerceAtLeast(0)
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp), verticalAlignment = Alignment.Top) {
        Text(
            if (c.temperature.isNaN()) "–" else c.temperature.roundToInt().toString(),
            fontFamily = AppCondensed,
            fontWeight = FontWeight.Light,
            fontSize = 112.sp,
            lineHeight = 112.sp,
        )
        Text("°C", fontFamily = AppSans, fontWeight = FontWeight.Light, fontSize = 26.sp, modifier = Modifier.padding(top = 16.dp))
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(top = 20.dp)) {
            WeatherIcon(c.weatherCode, c.isDay, size = 50.dp)
            Text(weatherDescription(c.weatherCode), fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                MonoText("ressenti ${Fmt.temp(c.apparentTemperature)}")
                Spacer(Modifier.width(10.dp))
                MonoText(
                    "${Fmt.temp(f.daily.temperatureMax.getOrElse(today) { Double.NaN })} / " +
                        Fmt.temp(f.daily.temperatureMin.getOrElse(today) { Double.NaN }),
                    color = MaterialTheme.colorScheme.onBackground,
                    bold = true,
                )
            }
        }
    }
}

/** Bloc « Altitude · maintenant » : coupe du relief et températures par altitude, iso 0, pluie-neige, ressenti en haut. */
@Composable
private fun AltitudeNowCard(f: Forecast, terrain: TerrainProfile?) {
    val i = Outlook.nowIndex(f)
    val ground = Altitude.ground(f)
    val top = if (ground < 2500) 3000.0 else ground + 1000
    val lapse = Altitude.lapseRate(f, i, ground, top)
    val iso = Altitude.freezingLevel(f, i)
    val snowLine = Altitude.snowLine(f, i)
    val colors = MaterialTheme.colorScheme
    Panel(Modifier.padding(top = 10.dp)) {
        PanelTitle(
            Icons.Outlined.Terrain,
            "Altitude · maintenant",
            if (lapse.isNaN()) null else String.format(Locale.FRANCE, "%.1f °C / 100 m", lapse),
        )
        ReliefNowChart(f, i, terrain, ground, iso, snowLine, Modifier.fillMaxWidth().height(184.dp).padding(top = 8.dp))
        HorizontalDivider(Modifier.padding(top = 10.dp, bottom = 8.dp), color = colors.outlineVariant)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            val days = Altitude.days(f).filter { !it.date.isBefore(f.hourly.time[i].toLocalDate()) }.take(8)
            val lowest = days.filter { !it.freezingLevel.isNaN() }.minByOrNull { it.freezingLevel }
            val isoNote = if (lowest != null && !iso.isNaN() && iso - lowest.freezingLevel >= 300) {
                "${Fmt.dayShort(lowest.date)} : ${Fmt.thousands(lowest.freezingLevel, 100)}"
            } else {
                "stable"
            }
            AltitudeCell("ISO 0 °C", Fmt.altitude(iso, 100), isoNote, colors.primary, Modifier.weight(1f))
            VerticalDivider(color = colors.outlineVariant)
            AltitudeCell("PLUIE-NEIGE", Fmt.altitude(snowLine, 100), "verglas : ${iceToday(f, i, terrain)}", colors.onBackground, Modifier.weight(1f))
            VerticalDivider(color = colors.outlineVariant)
            val high = if (ground < 1700) 2000.0 else 3000.0
            val wind = Altitude.wind(f, i, high)
            val feel = Altitude.windChill(Altitude.temperature(f, i, high), wind.speed)
            AltitudeCell(
                "RESS. ${Fmt.thousands(high)} M",
                Fmt.temp(feel),
                "vent ${Outlook.compass(wind.direction)} ${Fmt.kmh(wind.speed)}",
                colors.onBackground,
                Modifier.weight(1f),
            )
        }
    }
}

/** Verglas des 24 prochaines heures : « nul », « possible » sur place, ou « > 900 m ». */
private fun iceToday(f: Forecast, now: Int, terrain: TerrainProfile?): String {
    val risks = Altitude.iceRisks(f, now, now + 24, iceCeiling(f, terrain))
    return when {
        risks.isEmpty() -> "nul"
        risks.any { it.atPlace } -> "possible"
        else -> "> ${Fmt.thousands(risks.minOf { it.altitude }, 100)} m"
    }
}

@Composable
private fun AltitudeCell(label: String, value: String, note: String, valueColor: Color, modifier: Modifier) {
    Column(modifier.padding(horizontal = 6.dp)) {
        MonoText(label, size = 10.sp)
        Text(value, color = valueColor, fontFamily = AppCondensed, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, maxLines = 1)
        MonoText(note, size = 10.sp)
    }
}

/**
 * Coupe du lieu vers le sommet le plus haut des environs, avec les températures à gauche (lieu, 1 000, 1 500,
 * 2 000, 3 000 m, sommet), l'iso 0 °C en tirets et la limite pluie-neige en pointillés.
 */
@Composable
private fun ReliefNowChart(
    f: Forecast,
    i: Int,
    terrain: TerrainProfile?,
    ground: Double,
    iso: Double,
    snowLine: Double,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val chart = LocalChartColors.current
    Canvas(modifier) {
        val labelWidth = 86.dp.toPx()
        val left = labelWidth + 6.dp.toPx()
        val right = size.width
        val topPx = 8.dp.toPx()
        val bottomPx = size.height - 8.dp.toPx()
        val profile = terrain?.let { it.elevations.copyOfRange(it.placeIndex, it.elevations.size) }
        val summit = terrain?.summit?.takeIf { it.index >= terrain.placeIndex }
        val peak = summit?.elevation ?: profile?.maxOrNull() ?: ground
        val maxAlt = maxOf(peak, 3000.0, if (iso.isNaN() || iso > 4500) 0.0 else iso + 250, ground + 800) + 120
        val minAlt = minOf(ground, profile?.minOrNull() ?: ground) - 60
        fun y(alt: Double) = (bottomPx - (alt - minAlt) / (maxAlt - minAlt) * (bottomPx - topPx)).toFloat()

        // Altitudes de la colonne de gauche, sans chevauchement.
        val wanted = listOfNotNull(summit?.elevation?.takeIf { it > 3250 }, 3000.0, 2000.0, 1500.0, 1000.0)
            .filter { it > ground + 150 && it < maxAlt }
        val rows = ArrayList<Double>()
        val minGap = 15.dp.toPx()
        for (alt in wanted.sortedDescending()) {
            if (rows.isEmpty() || y(alt) - y(rows.last()) >= minGap) rows += alt
        }
        while (rows.isNotEmpty() && y(ground) - y(rows.last()) < minGap) rows.removeAt(rows.lastIndex)
        rows += ground

        for (alt in rows) {
            val yy = y(alt)
            drawLine(chart.grid, Offset(left, yy), Offset(right, yy), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 5f)))
            drawLabel(measurer, Fmt.altitude(alt), Offset(0f, yy), chart.muted, 10f, anchorY = 0.5f)
            drawText(
                measurer.measure(Fmt.temp(Altitude.temperature(f, i, alt)), TextStyle(color = colors.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppSans)),
                topLeft = Offset(labelWidth - measureWidth(measurer, Fmt.temp(Altitude.temperature(f, i, alt))), yy - 9.sp.toPx()),
            )
        }

        if (profile != null && profile.size > 1) {
            val step = (right - left) / (profile.size - 1)
            val line = Path()
            profile.forEachIndexed { k, e -> if (k == 0) line.moveTo(left, y(e)) else line.lineTo(left + k * step, y(e)) }
            val fill = Path().apply {
                addPath(line)
                lineTo(right, bottomPx)
                lineTo(left, bottomPx)
                close()
            }
            drawPath(fill, colors.primary.copy(alpha = 0.13f))
            drawPath(line, colors.primary, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        }

        val textBackground = colors.surfaceContainer
        if (!iso.isNaN() && iso < maxAlt) {
            val yy = y(iso)
            drawLine(colors.primary, Offset(left, yy), Offset(right, yy), 1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 9f)))
            drawTag(measurer, "ISO 0 °C ${Fmt.altitude(iso, 100)}", Offset(left + 4.dp.toPx(), yy - 2.dp.toPx()), colors.primary, textBackground, above = true)
        }
        if (!snowLine.isNaN() && snowLine < maxAlt && snowLine > minAlt) {
            val yy = y(snowLine)
            drawLine(colors.onBackground, Offset(left, yy), Offset(right, yy), 1.4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 5f)), cap = StrokeCap.Round)
            drawTag(measurer, "PLUIE-NEIGE ${Fmt.altitude(snowLine, 100)}", Offset(left + 4.dp.toPx(), yy + 3.dp.toPx()), colors.onBackground, textBackground, above = false)
        }
        drawCircle(colors.primary, 4.dp.toPx(), Offset(left, y(ground)))
    }
}

private fun measureWidth(measurer: TextMeasurer, text: String): Float =
    measurer.measure(text, TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppSans)).size.width.toFloat()

/** Étiquette d'une ligne (iso 0, pluie-neige), sur un fond pour rester lisible au-dessus du relief. */
fun DrawScope.drawTag(measurer: TextMeasurer, text: String, at: Offset, color: Color, background: Color, above: Boolean, boxed: Boolean = false) {
    val layout = measurer.measure(text, TextStyle(color = color, fontSize = 10.sp, fontFamily = AppMono, fontWeight = FontWeight.Medium))
    val pad = 3.dp.toPx()
    val top = if (above) at.y - layout.size.height - pad else at.y + pad
    drawRoundRect(
        background.copy(alpha = 0.9f),
        topLeft = Offset(at.x - pad, top - pad / 2),
        size = Size(layout.size.width + 2 * pad, layout.size.height + pad),
        cornerRadius = CornerRadius(4.dp.toPx()),
    )
    if (boxed) {
        drawRoundRect(
            color,
            topLeft = Offset(at.x - pad, top - pad / 2),
            size = Size(layout.size.width + 2 * pad, layout.size.height + pad),
            cornerRadius = CornerRadius(4.dp.toPx()),
            style = Stroke(1.dp.toPx()),
        )
    }
    drawText(layout, topLeft = Offset(at.x, top))
}

/** Vent, rafales, humidité, pression (et sa tendance sur 3 h), UV, coucher et lever du soleil. */
@Composable
private fun MetricsGrid(f: Forecast) {
    val c = f.current ?: return
    val h = f.hourly
    val i = Outlook.nowIndex(f)
    val colors = MaterialTheme.colorScheme
    val trend = h.pressure.getOrElse(i) { Double.NaN } - h.pressure.getOrElse(i - 3) { Double.NaN }
    val arrow = when {
        trend.isNaN() -> ""
        trend >= 1 -> " ↗"
        trend <= -1 -> " ↘"
        else -> " →"
    }
    val uv = h.uvIndex.getOrElse(i) { Double.NaN }
    val (sunMain, sunNote) = sunTimes(f, c.time)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp))
            .background(colors.surfaceContainerLowest),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Tile(Icons.Outlined.Air, "VENT ${Outlook.compass(c.windDirection)}", number(c.windSpeed), "km/h", Modifier.weight(1f))
            VerticalDivider(color = colors.outlineVariant)
            Tile(Icons.Outlined.Air, "RAFALES", number(c.windGusts), "km/h", Modifier.weight(1f))
            VerticalDivider(color = colors.outlineVariant)
            Tile(Icons.Outlined.WaterDrop, "HUMIDITÉ", number(c.humidity), "%", Modifier.weight(1f))
        }
        HorizontalDivider(color = colors.outlineVariant)
        Row(Modifier.height(IntrinsicSize.Min)) {
            Tile(Icons.Outlined.Speed, "PRESSION", number(c.pressure), "hPa$arrow", Modifier.weight(1f))
            VerticalDivider(color = colors.outlineVariant)
            Tile(Icons.Outlined.WbSunny, "INDICE UV", if (uv.isNaN()) "–" else uv.roundToInt().toString(), uvLabel(uv), Modifier.weight(1f))
            VerticalDivider(color = colors.outlineVariant)
            Tile(Icons.Outlined.WbTwilight, "SOLEIL", sunMain, sunNote, Modifier.weight(1f))
        }
    }
}

private fun number(v: Double) = if (v.isNaN()) "–" else v.roundToInt().toString()

private fun uvLabel(uv: Double) = when {
    uv.isNaN() -> ""
    uv < 3 -> "faible"
    uv < 6 -> "modéré"
    uv < 8 -> "élevé"
    uv < 11 -> "très élevé"
    else -> "extrême"
}

/** Avant le coucher : coucher en grand, lever en petit ; après : le prochain lever, puis le coucher suivant. */
private fun sunTimes(f: Forecast, now: LocalDateTime): Pair<String, String> {
    val d = f.daily
    val today = d.date.indexOf(now.toLocalDate()).coerceAtLeast(0)
    val sunset = d.sunset.getOrNull(today)
    return if (sunset == null || now.isBefore(sunset)) {
        Fmt.clock(sunset) to "${Fmt.clock(d.sunrise.getOrNull(today))} ↑"
    } else {
        Fmt.clock(d.sunrise.getOrNull(today + 1)) to "${Fmt.clock(d.sunset.getOrNull(today + 1))} ↓"
    }
}

@Composable
private fun Tile(icon: ImageVector, label: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier.padding(horizontal = 9.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            MonoText(label, size = 10.sp)
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 3.dp)) {
            Text(value, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, maxLines = 1, softWrap = false)
            Spacer(Modifier.width(3.dp))
            MonoText(unit, size = 9.5.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

/** Heures restantes de la journée (au moins 8) : pictogramme, température, probabilité et cumul de pluie. */
@Composable
private fun NextHours(f: Forecast) {
    val h = f.hourly
    val start = Outlook.nowIndex(f)
    val day = h.time.getOrNull(start)?.toLocalDate() ?: return
    var end = start
    while (end < h.size && h.time[end].toLocalDate() == day) end++
    val hours = (start until minOf(h.size, maxOf(end, start + 8))).toList()
    if (hours.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Prochaines heures", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
        val (summary, wet) = rainSummary(f, hours)
        MonoText(summary, color = if (wet) colors.primary else colors.onSurfaceVariant, bold = wet)
    }
    val measurer = rememberTextMeasurer()
    val chart = LocalChartColors.current
    val dark = chart.darkTheme
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Canvas(Modifier.width(46.dp * hours.size).height(184.dp)) {
            val col = size.width / hours.size
            val temps = hours.map { h.temperature[it] }.filter { !it.isNaN() }
            val tMin = temps.minOrNull() ?: 0.0
            val tMax = (temps.maxOrNull() ?: 1.0).let { if (it - tMin < 4) tMin + 4 else it }
            val maxMm = maxOf(2.0, hours.maxOf { h.precipitation[it].takeIf { v -> !v.isNaN() } ?: 0.0 })
            fun ty(t: Double) = (100.dp.toPx() - (t - tMin) / (tMax - tMin) * 26.dp.toPx()).toFloat()
            val barBottom = size.height - 4.dp.toPx()
            val barTop = 140.dp.toPx()

            for (k in 1 until hours.size) {
                drawLine(chart.grid, Offset(col * k, 18.dp.toPx()), Offset(col * k, barBottom), 1.dp.toPx())
            }
            drawLine(chart.axis, Offset(0f, barBottom), Offset(size.width, barBottom), 1.dp.toPx())
            val line = Path()
            hours.forEachIndexed { k, idx ->
                val cx = col * k + col / 2
                val label = if (k == 0) "Maint." else "%d h".format(h.time[idx].hour)
                drawLabel(measurer, label, Offset(cx, 0f), if (k == 0) colors.onBackground else chart.muted, 10.5f, anchorX = 0.5f, bold = k == 0)
                val icon = 30.dp.toPx()
                drawWeatherIcon(h.weatherCode[idx], h.isDay[idx], dark, Offset(cx - icon / 2, 20.dp.toPx()), icon)
                val t = h.temperature[idx]
                if (!t.isNaN()) {
                    val yy = ty(t)
                    if (k == 0) line.moveTo(cx, yy) else line.lineTo(cx, yy)
                    drawText(
                        measurer.measure(Fmt.temp(t), TextStyle(color = colors.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppSans)),
                        topLeft = Offset(cx - 9.dp.toPx(), yy - 21.dp.toPx()),
                    )
                }
                val p = h.precipitationProbability[idx]
                if (!p.isNaN()) {
                    drawLabel(measurer, "${p.roundToInt()} %", Offset(cx, 116.dp.toPx()), if (p >= 50) colors.primary else chart.muted, 10.5f, anchorX = 0.5f, bold = p >= 50)
                }
                val mm = h.precipitation[idx]
                val barWidth = 20.dp.toPx()
                if (!mm.isNaN() && mm >= 0.05) {
                    val height = ((mm / maxMm) * (barBottom - barTop)).toFloat().coerceAtLeast(3.dp.toPx())
                    drawRoundRect(
                        colors.primary,
                        topLeft = Offset(cx - barWidth / 2, barBottom - height),
                        size = Size(barWidth, height),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                } else {
                    drawLine(chart.grid, Offset(cx - barWidth / 2, barBottom - 1.dp.toPx()), Offset(cx + barWidth / 2, barBottom - 1.dp.toPx()), 2.dp.toPx())
                }
            }
            drawPath(line, colors.onBackground, style = Stroke(1.5.dp.toPx()))
            hours.forEachIndexed { k, idx ->
                val t = h.temperature[idx]
                if (t.isNaN()) return@forEachIndexed
                val center = Offset(col * k + col / 2, ty(t))
                drawCircle(colors.surfaceContainerLowest, 3.5.dp.toPx(), center)
                drawCircle(colors.onBackground, 3.5.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/** « pluie 17-20 h · 3,0 mm », « neige 2-6 h · 4 cm », ou « sec jusqu'à 23 h ». */
private fun rainSummary(f: Forecast, hours: List<Int>): Pair<String, Boolean> {
    val h = f.hourly
    val wet = hours.filter { (h.precipitation[it].takeIf { v -> !v.isNaN() } ?: 0.0) >= 0.1 }
    if (wet.isEmpty()) return "sec jusqu'à ${h.time[hours.last()].hour + 1} h" to false
    val first = wet.first()
    var last = first
    for (idx in wet.drop(1)) if (idx - last <= 2) last = idx else break
    val total = (first..last).sumOf { h.precipitation[it].takeIf { v -> !v.isNaN() } ?: 0.0 }
    val ground = Altitude.ground(f)
    val snow = Altitude.snow(f, first..last, ground)
    // Heures des barres, bornes comprises : « 17-20 h ».
    val span = when {
        first == hours.first() -> "jusqu'à ${(h.time[last].hour + 1) % 24} h"
        first == last -> "${h.time[first].hour} h"
        else -> "${h.time[first].hour}-${h.time[last].hour} h"
    }
    return if (snow >= total / 2 && snow >= 0.5) {
        "neige $span · ${snow.roundToInt()} cm" to true
    } else {
        "pluie $span · ${String.format(Locale.FRANCE, "%.1f", total)} mm" to true
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message)
        Gap(16.dp)
        androidx.compose.material3.Button(onClick = onRetry) { Text("Réessayer") }
    }
}

/** Sources des données, en bas des onglets, avec l'heure de mise à jour. */
@Composable
fun Attribution(updated: Long = 0L) {
    val minutes = ((System.currentTimeMillis() - updated) / 60_000).coerceAtLeast(0)
    Text(
        (if (updated > 0) "Mis à jour " + (if (minutes < 1) "à l'instant" else "il y a $minutes min") + ". " else "") +
            "Données météo : Open-Meteo.com (licence CC BY 4.0), d'après les modèles de Météo-France, DWD, ECMWF, NOAA, " +
            "UK Met Office, MeteoSwiss et ECCC. Relief : Copernicus DEM, sommets © OpenStreetMap.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}
