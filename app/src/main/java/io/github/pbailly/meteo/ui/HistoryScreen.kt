package io.github.pbailly.meteo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pbailly.meteo.data.DailyHistory
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.data.History
import io.github.pbailly.meteo.data.Place
import io.github.pbailly.meteo.data.PlaceRecords
import io.github.pbailly.meteo.data.Record
import io.github.pbailly.meteo.data.Versus
import io.github.pbailly.meteo.ui.theme.LocalChartColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private val FR = Locale.FRANCE
private val dayMonthLong = DateTimeFormatter.ofPattern("d MMMM", FR)
private val fullDate = DateTimeFormatter.ofPattern("d MMM yyyy", FR)
private val monthName = DateTimeFormatter.ofPattern("MMMM", FR)

/** « 22,4° » : les records se lisent au dixième. */
private fun tenth(v: Double): String = String.format(FR, "%.1f°", v)

/** Écart arrondi au degré : « +3° », « −2° », « ±0° ». */
private fun gapText(gap: Double): String {
    val g = gap.roundToInt()
    return (if (g > 0) "+" else if (g < 0) "−" else "±") + abs(g) + "°"
}

/** Écart au dixième : « +1,2° ». */
private fun gapTenth(gap: Double): String = (if (gap >= 0.05) "+" else if (gap <= -0.05) "−" else "±") + String.format(FR, "%.1f°", abs(gap))

/** Pourcentage de la normale : « 65 % ». */
private fun share(v: Versus): String = if (v.normal <= 0) "–" else "${(v.value / v.normal * 100).roundToInt()} %"

@Composable
private fun warmColor(): Color = LocalChartColors.current.accent

/**
 * En haut à droite de l'onglet Prévisions : la normale du jour, l'écart prévu aujourd'hui et les records du jour.
 * Toucher la carte ouvre la page « Données historiques ».
 */
@Composable
fun HistoryCard(history: Remote<DailyHistory>, today: LocalDate, forecastMax: Double?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val h = history.data
    val normal = remember(h, today) { h?.let { History.normal(it, today) } }
    val records = remember(h, today) { h?.let { History.dayRecords(it, today) } }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, colors.outlineVariant),
        color = colors.surfaceContainerLowest,
        // En grand texte, la carte grandit un peu mais laisse sa place au titre.
        modifier = modifier.width(164.sp.asWidth().coerceAtMost(196.dp)),
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoText(
                    if (normal != null) "NORMALE " + Fmt.dayMonth(today).uppercase(FR) else "HISTORIQUE",
                    size = 9.5.sp,
                    bold = true,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Données historiques", tint = colors.primary, modifier = Modifier.size(16.dp))
            }
            when {
                normal != null -> Row(verticalAlignment = Alignment.Bottom) {
                    Text(Fmt.temp(normal.max), color = warmColor(), fontWeight = FontWeight.SemiBold, fontSize = 18.sp, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    Text(Fmt.temp(normal.min), color = colors.primary, fontSize = 15.sp, maxLines = 1, modifier = Modifier.padding(bottom = 1.dp))
                    Spacer(Modifier.weight(1f))
                    if (forecastMax != null && !forecastMax.isNaN()) {
                        val gap = forecastMax - normal.max
                        MonoText("auj. " + gapText(gap), color = if (gap >= 0) warmColor() else colors.primary, size = 10.sp, bold = true, modifier = Modifier.padding(bottom = 3.dp))
                    }
                }
                history.error != null -> MonoText("indisponible", size = 11.sp)
                history.loading -> MonoText("chargement…", size = 11.sp)
                // Pas encore demandé (un lieu qui n'est pas un favori) : la page le charge.
                else -> MonoText("normales, records", color = colors.primary, size = 11.sp)
            }
            if (records != null && (records.hottest != null || records.coldest != null)) {
                HorizontalDivider(color = colors.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonoText("records", size = 9.sp, modifier = Modifier.weight(1f))
                    records.hottest?.let { RecordStack(it, warmColor()) }
                    Spacer(Modifier.width(8.dp))
                    records.coldest?.let { RecordStack(it, colors.primary) }
                }
            }
        }
    }
}

@Composable
private fun RecordStack(record: Record, color: Color) {
    Column(horizontalAlignment = Alignment.End) {
        MonoText(tenth(record.value), color = color, size = 10.5.sp, bold = true)
        MonoText(record.date.year.toString(), size = 9.sp)
    }
}

/**
 * Page « Données historiques », ouverte depuis la carte de l'onglet Prévisions : le jour face à la normale et à ses
 * records, les records du lieu, les 14 jours à venir face à la normale, le même jour année après année, il y a un an,
 * les 30 derniers jours et l'année en cours.
 */
@Composable
fun HistoryScreen(state: MeteoUiState, place: Place, onHistoryNeeded: () -> Unit, onRetry: () -> Unit, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    LaunchedEffect(place.id) { onHistoryNeeded() }
    val remote = state.history
    val h = remote.data
    val f = state.forecast.data
    val today = f?.daily?.date?.firstOrNull() ?: LocalDate.now()
    var scope by rememberSaveable { mutableIntStateOf(0) }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "title") {
            DetailHeader("Données historiques", onBack)
            PlaceLine(place.name, place.elevation ?: f?.elevation)
        }
        if (h == null) {
            item(key = "loading") {
                if (remote.error != null) {
                    ErrorState(remote.error, onRetry)
                } else {
                    Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Gap(12.dp)
                        MonoText("Le temps depuis 1940…", size = 11.sp)
                    }
                }
            }
            return@LazyColumn
        }
        item(key = "today") { TodaySection(h, f, today) }
        item(key = "records") { RecordsSection(h, today, scope) { scope = it } }
        if (f != null) item(key = "coming") { ComingSection(h, f) }
        item(key = "years") { YearsSection(h, f, today) }
        item(key = "year-ago") { YearAgoSection(h, today) }
        item(key = "month") { PeriodSection(h, h.end.minusDays(29), h.end, "Les 30 derniers jours") }
        item(key = "year") { YearSection(h) }
        item(key = "source") {
            MonoText(
                "Réanalyse ERA5 d'Open-Meteo depuis ${h.start.year}, maille de 9 à 25 km : les extrêmes y sont un peu " +
                    "adoucis par rapport aux relevés d'une station. Normales : moyenne ${History.NORMAL_FIRST_YEAR}–" +
                    "${History.NORMAL_LAST_YEAR}.",
                size = 9.5.sp,
                maxLines = 6,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun TodaySection(h: DailyHistory, f: Forecast?, today: LocalDate) {
    val colors = MaterialTheme.colorScheme
    val normal = remember(h, today) { History.normal(h, today) }
    val records = remember(h, today) { History.dayRecords(h, today) }
    val day = f?.daily?.date?.indexOf(today) ?: -1
    SectionLabel("Aujourd'hui, " + today.format(dayMonthLong))
    BorderedCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    MonoText("NORMALE ${History.NORMAL_FIRST_YEAR}–${History.NORMAL_LAST_YEAR}", size = 9.5.sp)
                    MaxMin(normal?.max ?: Double.NaN, normal?.min ?: Double.NaN)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    MonoText("PRÉVU", size = 9.5.sp)
                    if (f != null && day >= 0) {
                        val hi = f.daily.temperatureMax[day]
                        Row(verticalAlignment = Alignment.Bottom) {
                            MaxMin(hi, f.daily.temperatureMin[day])
                            if (normal != null && !hi.isNaN()) {
                                val gap = hi - normal.max
                                Spacer(Modifier.width(8.dp))
                                MonoText(gapText(gap), color = if (gap >= 0) warmColor() else colors.primary, size = 11.sp, bold = true, modifier = Modifier.padding(bottom = 4.dp))
                            }
                        }
                    } else {
                        MaxMin(Double.NaN, Double.NaN)
                    }
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            MonoText("RECORDS DU " + today.format(dayMonthLong).uppercase(FR), size = 9.5.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Figure(records.hottest?.let { tenth(it.value) }, "plus chaud", records.hottest?.date?.year, warmColor())
                Figure(records.coldest?.let { tenth(it.value) }, "plus froid", records.coldest?.date?.year, colors.primary)
                Figure(records.wettest?.let { Fmt.mm(it.value) }, "plus arrosé", records.wettest?.date?.year, colors.primary)
            }
        }
    }
}

/** Maxi en orange, mini en bleu, comme ailleurs dans l'appli. */
@Composable
private fun MaxMin(max: Double, min: Double) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(Fmt.temp(max), color = warmColor(), fontWeight = FontWeight.SemiBold, fontSize = 22.sp, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Text(Fmt.temp(min), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, fontSize = 18.sp, maxLines = 1, modifier = Modifier.padding(bottom = 1.dp))
    }
}

/** Un record du jour : la valeur, « plus chaud », puis l'année. */
@Composable
private fun RowScope.Figure(value: String?, label: String, year: Int?, color: Color) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value ?: "–", color = color, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1)
        MonoText(label, size = 9.5.sp)
        if (year != null) MonoText(year.toString(), color = MaterialTheme.colorScheme.onBackground, size = 9.5.sp)
    }
}

@Composable
private fun RecordsSection(h: DailyHistory, today: LocalDate, scope: Int, onScope: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val year = h.end.year
    val records = remember(h, scope, today) {
        when (scope) {
            1 -> History.monthRecords(h, today.monthValue)
            2 -> History.yearRecords(h, year)
            else -> History.allTimeRecords(h)
        }
    }
    SectionLabel("Records du lieu")
    SegmentedToggle(
        listOf("Depuis ${h.start.year}", "En " + today.format(monthName), "En $year"),
        selected = scope,
        onSelect = onScope,
        fill = true,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    )
    val tiles = recordTiles(records, warmColor(), colors.primary, colors.onBackground)
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { RecordTile(it) }
            }
        }
    }
}

private class Tile(val label: String, val value: String, val detail: String, val color: Color)

private fun recordTiles(r: PlaceRecords, warm: Color, cold: Color, ink: Color): List<Tile> {
    fun date(record: Record?) = record?.date?.format(fullDate) ?: "–"
    return listOf(
        Tile("PLUS CHAUD", r.hottest?.let { tenth(it.value) } ?: "–", date(r.hottest), warm),
        Tile("PLUS FROID", r.coldest?.let { tenth(it.value) } ?: "–", date(r.coldest), cold),
        Tile("PLUIE EN UN JOUR", r.wettest?.let { Fmt.mm(it.value) } ?: "–", date(r.wettest), cold),
        Tile("RAFALE", r.gust?.let { Fmt.kmh(it.value) } ?: "–", date(r.gust), ink),
        Tile("NEIGE EN UN JOUR", r.snowfall?.let { "${it.value.roundToInt()} cm" } ?: "aucune", date(r.snowfall), cold),
        Tile(
            "JOURS SECS D'AFFILÉE",
            r.drySpell?.let { "${it.value.roundToInt()} jours" } ?: "–",
            r.drySpell?.let { "dès le " + it.date.format(fullDate) } ?: "–",
            warm,
        ),
    )
}

@Composable
private fun RowScope.RecordTile(tile: Tile) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, colors.outlineVariant),
        color = colors.surfaceContainerLowest,
        modifier = Modifier.weight(1f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            MonoText(tile.label, size = 9.5.sp)
            Text(tile.value, color = tile.color, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, maxLines = 1)
            MonoText(tile.detail, size = 9.5.sp)
        }
    }
}

/** Les jours de la prévision : écart de leur température moyenne à la normale, en barres de part et d'autre de zéro. */
@Composable
private fun ComingSection(h: DailyHistory, f: Forecast) {
    val chart = LocalChartColors.current
    val cold = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()
    val days = remember(h, f) {
        val d = f.daily
        (0 until minOf(d.size, 14)).map { i ->
            val normal = History.normal(h, d.date[i])
            val mean = (d.temperatureMax[i] + d.temperatureMin[i]) / 2
            d.date[i] to (if (normal == null) Double.NaN else mean - normal.mean)
        }
    }
    if (days.none { !it.second.isNaN() }) return
    val k = textScale()
    SectionLabel("Les ${days.size} jours à venir face à la normale")
    BorderedCard {
        val span = max(4.0, days.maxOf { if (it.second.isNaN()) 0.0 else abs(it.second) })
        Canvas(Modifier.fillMaxWidth().height(150.dp * k).padding(horizontal = 10.dp, vertical = 12.dp)) {
            val dp = density * k
            val labels = 16f * dp
            val mid = (size.height - labels) / 2
            val half = mid - 14f * dp
            val col = size.width / days.size
            drawLine(chart.axis, Offset(0f, mid), Offset(size.width, mid), 1f * dp)
            days.forEachIndexed { i, (date, gap) ->
                val cx = (i + 0.5f) * col
                drawLabel(measurer, Fmt.dayName(date).take(1).uppercase(FR), Offset(cx, size.height), chart.secondaryInk, 9f, 0.5f, 1f, bold = date.dayOfWeek.value >= 6)
                if (gap.isNaN()) return@forEachIndexed
                val height = (abs(gap) / span * half).toFloat().coerceAtLeast(1.5f * dp)
                val width = (col - 4f * dp).coerceAtMost(16f * dp)
                val top = if (gap >= 0) mid - height else mid
                drawRoundRect(if (gap >= 0) chart.accent else cold, Offset(cx - width / 2, top), Size(width, height), CornerRadius(2f * dp))
                val text = gap.roundToInt().let { (if (it > 0) "+" else "") + it }
                if (gap >= 0) drawLabel(measurer, text, Offset(cx, top - 2f * dp), chart.secondaryInk, 9f, 0.5f, 1f)
                else drawLabel(measurer, text, Offset(cx, top + height + 2f * dp), chart.secondaryInk, 9f, 0.5f, 0f)
            }
        }
        MonoText(
            "Écart de la température moyenne prévue à la normale du jour : orange plus chaud, bleu plus frais.",
            size = 9.5.sp,
            maxLines = 3,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        )
    }
}

/** Le maxi du même jour les dix années d'avant, et celui prévu cette année. */
@Composable
private fun YearsSection(h: DailyHistory, f: Forecast?, today: LocalDate) {
    val chart = LocalChartColors.current
    val past = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val measurer = rememberTextMeasurer()
    val bars = remember(h, f, today) {
        val day = f?.daily?.date?.indexOf(today) ?: -1
        val forecast = if (f != null && day >= 0) f.daily.temperatureMax[day] else Double.NaN
        History.sameDayMaxima(h, today, 10) + listOfNotNull(if (forecast.isNaN()) null else today.year to forecast)
    }
    if (bars.isEmpty()) return
    val k = textScale()
    SectionLabel("Le " + today.format(dayMonthLong) + ", année après année")
    BorderedCard {
        val low = bars.minOf { it.second }
        val high = bars.maxOf { it.second }
        // Le pied des barres sous le plus bas : chaque barre garde une hauteur lisible.
        val base = low - max(2.0, (high - low) * 0.4)
        Canvas(Modifier.fillMaxWidth().height(140.dp * k).padding(horizontal = 10.dp, vertical = 12.dp)) {
            val dp = density * k
            val bottom = size.height - 16f * dp
            val top = 16f * dp
            val col = size.width / bars.size
            bars.forEachIndexed { i, (year, t) ->
                val cx = (i + 0.5f) * col
                val y = (bottom - (t - base) / (high - base) * (bottom - top)).toFloat()
                val width = (col - 6f * dp).coerceAtMost(22f * dp)
                val current = year == today.year
                drawRoundRect(if (current) chart.accent else past, Offset(cx - width / 2, y), Size(width, bottom - y), CornerRadius(3f * dp))
                drawLabel(measurer, Fmt.temp(t), Offset(cx, y - 2f * dp), chart.ink, 9f, 0.5f, 1f, bold = current)
                drawLabel(measurer, if (current) "auj." else "'" + (year % 100).toString().padStart(2, '0'), Offset(cx, size.height), chart.secondaryInk, 9f, 0.5f, 1f, bold = current)
            }
        }
        MonoText(
            "Maxi du jour" + if (bars.last().first == today.year) " ; en orange, celui prévu cette année." else ".",
            size = 9.5.sp,
            maxLines = 2,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        )
    }
}

@Composable
private fun YearAgoSection(h: DailyHistory, today: LocalDate) {
    val date = History.sameDay(today.year - 1, today)
    val i = h.index(date)
    if (i < 0) return
    val colors = MaterialTheme.colorScheme
    SectionLabel("Il y a un an · " + date.format(fullDate))
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val code = h.weatherCode[i]
            if (code >= 0) {
                WeatherIcon(code, true, size = 32.dp)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                if (code >= 0) Text(weatherDescription(code), fontSize = 15.sp, maxLines = 2)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Fmt.temp(h.temperatureMax[i]), color = warmColor(), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(Fmt.temp(h.temperatureMin[i]), color = colors.primary, fontSize = 14.sp)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                MonoText(Fmt.mm(h.precipitation[i]), color = colors.primary, size = 11.5.sp)
                if (h.snowfall[i] >= 0.5) MonoText("neige ${h.snowfall[i].roundToInt()} cm", color = colors.primary, size = 10.sp)
                MonoText("soleil ${Fmt.decimal(h.sunshine[i] / 3600)} h", size = 10.sp)
            }
        }
    }
}

/** Les jours de [from] à [to] face à la normale : température, pluie et soleil. */
@Composable
private fun PeriodSection(h: DailyHistory, from: LocalDate, to: LocalDate, title: String) {
    val summary = remember(h, from, to) { History.period(h, from, to) } ?: return
    val colors = MaterialTheme.colorScheme
    val chart = LocalChartColors.current
    SectionLabel(title)
    BorderedCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MonoText("du " + from.format(fullDate) + " au " + to.format(fullDate), size = 9.5.sp)
            val gap = summary.temperatureGap
            CompareLine("Température moyenne", gapTenth(gap) + " par rapport à la normale", if (gap >= 0) warmColor() else colors.primary) {
                AnomalyBar(gap, if (gap >= 0) warmColor() else colors.primary)
            }
            val rain = summary.precipitation
            CompareLine("Pluie", "${Fmt.decimal(rain.value)} mm · ${share(rain)} de la normale", colors.primary) {
                VersusBar(rain, colors.primary)
            }
            val sun = summary.sunshine
            CompareLine("Soleil", "${sun.value.roundToInt()} h · ${share(sun)} de la normale", chart.accent) {
                VersusBar(sun, chart.accent)
            }
            MonoText("Trait foncé : la normale.", size = 9.5.sp)
        }
    }
}

@Composable
private fun CompareLine(label: String, value: String, color: Color, bar: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        MonoText(value, color = color, size = 11.sp, maxLines = 2)
        bar()
    }
}

/** La valeur en barre, la normale en trait (la barre s'arrête au trait quand on est dans la normale). */
@Composable
private fun VersusBar(v: Versus, color: Color) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val mark = MaterialTheme.colorScheme.onBackground
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val scale = max(v.value, v.normal) * 1.15
        if (scale <= 0) return@Canvas
        val bar = 8.dp.toPx()
        val y = (size.height - bar) / 2
        drawRoundRect(track, Offset(0f, y), Size(size.width, bar), CornerRadius(bar / 2))
        val w = (v.value / scale * size.width).toFloat()
        if (w > 0) drawRoundRect(color, Offset(0f, y), Size(w.coerceAtLeast(bar), bar), CornerRadius(bar / 2))
        val x = (v.normal / scale * size.width).toFloat()
        drawLine(mark, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
    }
}

/** L'écart à la normale de part et d'autre du trait du milieu, jusqu'à ± 5°. */
@Composable
private fun AnomalyBar(gap: Double, color: Color) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val mark = MaterialTheme.colorScheme.onBackground
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val bar = 8.dp.toPx()
        val y = (size.height - bar) / 2
        val mid = size.width / 2
        drawRoundRect(track, Offset(0f, y), Size(size.width, bar), CornerRadius(bar / 2))
        val w = (gap.coerceIn(-5.0, 5.0) / 5.0 * mid).toFloat()
        if (w > 0) drawRoundRect(color, Offset(mid, y), Size(w, bar), CornerRadius(bar / 2))
        if (w < 0) drawRoundRect(color, Offset(mid + w, y), Size(-w, bar), CornerRadius(bar / 2))
        drawLine(mark, Offset(mid, 0f), Offset(mid, size.height), 2.dp.toPx())
    }
}

/** Depuis le 1er janvier : pluie, jours de gel et jours de chaleur face à la normale. */
@Composable
private fun YearSection(h: DailyHistory) {
    val from = LocalDate.of(h.end.year, 1, 1)
    val summary = remember(h) { History.period(h, from, h.end) } ?: return
    val colors = MaterialTheme.colorScheme
    SectionLabel("Depuis le 1er janvier ${h.end.year}")
    // Les trois cases à la même hauteur, celle de la plus haute.
    Row(Modifier.padding(horizontal = 16.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        YearTile("PLUIE", "${Fmt.thousands(summary.precipitation.value)} mm", "${share(summary.precipitation)} de la normale", colors.primary)
        YearTile("JOURS DE GEL", summary.frostDays.value.roundToInt().toString(), "normale : ${summary.frostDays.normal.roundToInt()}", colors.primary)
        YearTile("JOURS À 30°", summary.hotDays.value.roundToInt().toString(), "normale : ${summary.hotDays.normal.roundToInt()}", warmColor())
    }
}

@Composable
private fun RowScope.YearTile(label: String, value: String, detail: String, color: Color) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, colors.outlineVariant),
        color = colors.surfaceContainerLowest,
        modifier = Modifier.weight(1f).fillMaxHeight(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            MonoText(label, size = 9.sp)
            Text(value, color = color, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1)
            MonoText(detail, size = 9.sp, maxLines = 2)
        }
    }
}
