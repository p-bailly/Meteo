package io.github.pbailly.meteo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Terrain
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pbailly.meteo.data.Altitude
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.data.Outlook
import io.github.pbailly.meteo.data.Place
import io.github.pbailly.meteo.data.TerrainProfile
import io.github.pbailly.meteo.ui.theme.AppMono
import io.github.pbailly.meteo.ui.theme.LocalChartColors
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

private const val SLOTS = 8
private val AlertRed = Color(0xFFC8202A)

/** Une case du sélecteur : un jour (mode Jours) ou trois heures d'un jour (mode Heures). */
private class Slot(
    val date: LocalDate,
    val hours: IntRange,
    /** Heure dont on prend la température et le vent. */
    val sample: Int,
    val top: String,
    val main: String,
    val axis: String,
    val freezingLevel: Double,
    val snowLine: Double?,
)

/**
 * Page « Neige & altitude » : les températures par altitude maintenant, puis choix du jour (ou de l'heure), coupe
 * du relief avec l'iso 0 °C, la limite pluie-neige et la neige attendue, évolution jour par jour, puis température,
 * ressenti, vent et neige au sommet, à 2 000 m et sur le lieu.
 */
@Composable
fun AltitudeScreen(state: MeteoUiState, place: Place, onRefresh: () -> Unit, onBack: (() -> Unit)? = null) {
    if (onBack != null) androidx.activity.compose.BackHandler(onBack = onBack)
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var day by rememberSaveable { mutableIntStateOf(0) }
    var hour by rememberSaveable { mutableIntStateOf(4) }
    val remote = state.forecast
    val allDays = remember(remote.data) { remote.data?.let { daySlots(it) }.orEmpty() }
    PullToRefreshBox(isRefreshing = remote.loading && remote.data != null, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        val f = remote.data
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "title") {
                val toggle: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                    SegmentedToggle(listOf("Jours", "Heures"), mode, { mode = it })
                }
                if (onBack != null) DetailHeader("Neige & altitude", onBack, trailing = toggle) else ScreenHeader("Neige & altitude", trailing = toggle)
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
            val days = allDays
            if (days.isEmpty()) return@LazyColumn
            val selectedDay = day.coerceIn(0, days.lastIndex)
            val slots = if (mode == 0) days else hourSlots(f, days[selectedDay])
            val selected = if (mode == 0) selectedDay else hour.coerceIn(0, slots.lastIndex)
            val slot = slots[selected]
            val reference = Outlook.snowReference(Altitude.ground(f))
            item(key = "now") { AltitudeNowPanel(f, state.terrain.data, Modifier.padding(top = 12.dp, bottom = 6.dp)) }
            item(key = "chips") {
                SlotChips(f, slots, selected, reference) { if (mode == 0) day = it else hour = it }
            }
            item(key = "relief") {
                val title = if (mode == 0) "Coupe du relief · ${Fmt.dayShort(slot.date)}" else "Coupe du relief · ${Fmt.dayShort(slot.date)}, ${slot.main}"
                Panel(Modifier.padding(top = 12.dp)) {
                    PanelTitle(Icons.Outlined.Terrain, title, "vallée → sommets")
                    CrossSection(f, slot, state.terrain.data, place, Modifier.fillMaxWidth().height(290.dp).padding(top = 10.dp))
                    Legend()
                }
            }
            item(key = "trend") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (mode == 0) "Jour par jour" else "Heure par heure", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    MonoText("altitudes en km")
                }
                TrendChart(f, slots, selected, reference, Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(260.dp))
            }
            item(key = "table") { AltitudeTable(f, slot, mode, state.terrain.data, place) }
            item(key = "note") {
                MonoText(
                    "Températures du modèle entre le sol et les niveaux de pression · limite pluie-neige : 300 m sous l'iso 0 · " +
                        "neige : pluie du lieu tombant en neige à cette altitude (1 mm ≈ 1 cm).",
                    size = 10.sp,
                    maxLines = 4,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }
        }
    }
}

private fun daySlots(f: Forecast): List<Slot> {
    val now = Outlook.nowIndex(f)
    val today = f.hourly.time.getOrNull(now)?.toLocalDate() ?: return emptyList()
    return Altitude.days(f).filter { !it.date.isBefore(today) }.take(SLOTS).map { d ->
        // Midi, ou l'heure en cours si on est déjà l'après-midi.
        val noon = d.hours.firstOrNull { f.hourly.time[it].hour == 12 } ?: d.hours.first
        Slot(
            date = d.date,
            hours = d.hours,
            sample = if (d.date == today) maxOf(noon, now) else noon,
            top = Fmt.dayName(d.date).removeSuffix("."),
            main = d.date.dayOfMonth.toString(),
            axis = Fmt.dayName(d.date).removeSuffix(".").replaceFirstChar { it.titlecase(Locale.FRANCE) } + "\n" + d.date.dayOfMonth,
            freezingLevel = d.freezingLevel,
            snowLine = d.snowLine,
        )
    }
}

/** Les huit tranches de trois heures du jour choisi. */
private fun hourSlots(f: Forecast, day: Slot): List<Slot> {
    val h = f.hourly
    return day.hours.chunked(3).take(SLOTS).map { chunk ->
        val hours = chunk.first()..chunk.last()
        val sample = chunk[chunk.size / 2]
        val levels = chunk.map { Altitude.freezingLevel(f, it) }.filter { !it.isNaN() }
        val wet = chunk.filter { (h.precipitation[it].takeIf { v -> !v.isNaN() } ?: 0.0) >= 0.1 }
        val label = "%02d h".format(h.time[chunk.first()].hour)
        Slot(
            date = day.date,
            hours = hours,
            sample = sample,
            top = day.top,
            main = "%02d".format(h.time[chunk.first()].hour),
            axis = label,
            freezingLevel = if (levels.isEmpty()) Double.NaN else levels.average(),
            snowLine = if (wet.isEmpty()) null else wet.map { Altitude.snowLine(f, it) }.filter { !it.isNaN() }.takeIf { it.isNotEmpty() }?.average(),
        )
    }
}

@Composable
private fun SlotChips(f: Forecast, slots: List<Slot>, selected: Int, reference: Double, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        slots.forEachIndexed { k, slot ->
            val active = k == selected
            val snowy = Altitude.snow(f, slot.hours, reference) >= 1
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) colors.onBackground else colors.surfaceContainerLowest)
                    .border(1.dp, if (active) colors.onBackground else colors.outlineVariant, RoundedCornerShape(12.dp))
                    .clickable { onSelect(k) }
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MonoText(slot.top, color = if (active) colors.background else colors.onSurfaceVariant, size = 10.sp)
                Text(
                    slot.main,
                    color = if (active) colors.background else colors.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    maxLines = 1,
                )
                Box(Modifier.height(13.dp), contentAlignment = Alignment.Center) {
                    if (snowy) {
                        Icon(
                            Icons.Outlined.AcUnit,
                            contentDescription = "neige",
                            tint = if (active) colors.background else colors.primary,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Legend() {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 22.dp, height = 10.dp)) {
            drawLine(colors.primary, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))
        }
        MonoText(" Iso 0 °C", size = 10.5.sp)
        Spacer(Modifier.width(14.dp))
        Canvas(Modifier.size(width = 22.dp, height = 10.dp)) {
            drawLine(colors.onBackground, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 5f)), cap = StrokeCap.Round)
        }
        MonoText(" Pluie-neige", size = 10.5.sp)
        Spacer(Modifier.width(14.dp))
        Canvas(Modifier.size(12.dp)) {
            drawRect(colors.primary, style = Stroke(1.dp.toPx()))
            clipRect { hatch(colors.primary.copy(alpha = 0.7f), 4.dp.toPx(), 1.dp.toPx()) }
        }
        MonoText(" Enneigé", size = 10.5.sp)
    }
}

/** Hachures en diagonale sur toute la zone de dessin (à découper par l'appelant). */
private fun DrawScope.hatch(color: Color, spacing: Float, width: Float) {
    var x = -size.height
    while (x < size.width) {
        drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), width)
        x += spacing
    }
}

/**
 * Coupe du relief : le terrain en aplat, enneigé (hachuré) au-dessus de la limite pluie-neige quand il neige,
 * l'iso 0 °C, la limite pluie-neige, les sommets nommés, et la neige attendue à 2 000 et 3 000 m.
 */
@Composable
private fun CrossSection(f: Forecast, slot: Slot, terrain: TerrainProfile?, place: Place, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val chart = LocalChartColors.current
    val ground = Altitude.ground(f)
    val elevations = terrain?.elevations ?: DoubleArray(FLAT_SAMPLES) { ground }
    val placeIndex = terrain?.placeIndex ?: 0
    val iso = slot.freezingLevel
    val snowLine = slot.snowLine
    val snowing = snowLine != null && Altitude.snow(f, slot.hours, (elevations.maxOrNull() ?: ground)) >= 0.5
    Canvas(modifier) {
        val axis = 40.dp.toPx()
        val left = axis
        val right = size.width
        val top = 20.dp.toPx()
        val bottom = size.height - 2.dp.toPx()
        val peak = elevations.maxOrNull() ?: ground
        val maxAlt = maxOf(peak, if (iso.isNaN() || iso > 5200) 0.0 else iso + 200) + 450
        val minAlt = (elevations.minOrNull() ?: ground) - 250
        fun y(alt: Double) = (bottom - (alt - minAlt) / (maxAlt - minAlt) * (bottom - top)).toFloat()
        val step = (right - left) / (elevations.size - 1)
        fun x(k: Int) = left + k * step

        val tick = if (maxAlt - minAlt > 2500) 1000.0 else 500.0
        var t = Math.ceil(minAlt / tick) * tick
        while (t < maxAlt) {
            if (y(t) > top) {
                drawLine(chart.grid, Offset(left, y(t)), Offset(right, y(t)), 1.dp.toPx())
                drawLabel(measurer, Fmt.thousands(t), Offset(0f, y(t)), chart.muted, 11f, anchorY = 0.5f)
            }
            t += tick
        }

        val line = Path()
        elevations.forEachIndexed { k, e -> if (k == 0) line.moveTo(x(k), y(e)) else line.lineTo(x(k), y(e)) }
        val fill = Path().apply {
            addPath(line)
            lineTo(right, bottom)
            lineTo(left, bottom)
            close()
        }
        drawPath(fill, colors.primary.copy(alpha = 0.16f))
        if (snowing) {
            clipPath(fill) {
                clipRect(left, 0f, right, y(snowLine!!), ClipOp.Intersect) {
                    hatch(colors.primary.copy(alpha = 0.45f), 7.dp.toPx(), 1.dp.toPx())
                }
            }
        }
        drawPath(line, colors.primary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))

        // Pluie au fond de la vallée quand elle tombe sous la limite pluie-neige.
        if (snowLine != null && ground < snowLine && Altitude.rain(f, slot.hours, ground) >= 0.5) {
            val px = x(placeIndex)
            val py = y(elevations[placeIndex]) - 4.dp.toPx()
            for (k in -1..1) {
                val cx = px + k * 7.dp.toPx() + 10.dp.toPx()
                drawLine(colors.primary, Offset(cx, py), Offset(cx - 3.dp.toPx(), py - 7.dp.toPx()), 1.4.dp.toPx(), cap = StrokeCap.Round)
            }
        }

        // Lignes et étiquettes : iso 0 au-dessus de sa ligne, pluie-neige en dessous, côté vallée (à gauche) où le
        // relief ne les cache pas. Les étiquettes suivantes évitent la place déjà prise.
        val background = colors.surfaceContainerLowest
        val taken = ArrayList<Rect>()
        if (!iso.isNaN() && iso < maxAlt) {
            val yy = y(iso)
            drawLine(colors.primary, Offset(left, yy), Offset(right, yy), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 9f)))
            taken += drawTag(measurer, "ISO 0 °C · ${Fmt.altitude(iso, 100)}", left + 6.dp.toPx(), yy, colors.primary, background, above = true)
        }
        if (snowLine != null && snowLine > minAlt && snowLine < maxAlt) {
            val yy = y(snowLine)
            drawLine(colors.onBackground, Offset(left, yy), Offset(right, yy), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 6f)), cap = StrokeCap.Round)
            taken += drawTag(measurer, "PLUIE-NEIGE · ${Fmt.altitude(snowLine, 100)}", left + 6.dp.toPx(), yy, colors.onBackground, background, above = false)
        }

        // Neige attendue à 2 000 et 3 000 m, sur le versant vers le sommet.
        val summitIndex = terrain?.summit?.index ?: elevations.indices.maxByOrNull { elevations[it] } ?: 0
        for (alt in listOf(2000.0, 3000.0)) {
            val cm = Altitude.snow(f, slot.hours, alt)
            if (cm < 1 || alt > peak) continue
            val range = if (summitIndex >= placeIndex) placeIndex..summitIndex else summitIndex..placeIndex
            val k = range.firstOrNull { elevations[it] >= alt } ?: continue
            val point = Offset(x(k), y(elevations[k]))
            drawCircle(colors.primary, 4.dp.toPx(), point)
            drawPill(measurer, "+${cm.roundToInt()} cm", Offset(point.x - 6.dp.toPx(), point.y - 6.dp.toPx()), colors.primary, background, 13f, 1f, 1f, left, right, taken)
                ?.let { taken += it }
        }

        // Sommets nommés, sur fond pour rester lisibles ; un nom qui en chevaucherait un autre est omis.
        terrain?.labels?.forEach { label ->
            val px = x(label.index)
            val py = y(elevations[label.index])
            val text = shortName(label.name) + Fmt.thousands(label.elevation)
            drawPill(measurer, text, Offset(px, py - 5.dp.toPx()), colors.onBackground, background, 11.5f, 0.5f, 1f, left, right, taken)
                ?.let { taken += it }
        }

        drawPill(
            measurer,
            place.name,
            Offset(x(placeIndex), bottom - 4.dp.toPx()),
            colors.onBackground,
            background,
            11.5f,
            0f,
            1f,
            left,
            right,
        )
    }
}

/** Sans coupe du relief (hors ligne), un sol plat au niveau du lieu. */
private const val FLAT_SAMPLES = 2

/** « Aig. du Midi 3 842 » : noms raccourcis pour tenir sur la coupe. */
private fun shortName(name: String?): String {
    if (name == null) return ""
    val short = name.replace("Aiguille", "Aig.").replace("Pointe", "Pte").replace("Mont ", "Mt ")
        .let { if (it.startsWith("Mt Blanc")) "Mont Blanc" + it.removePrefix("Mt Blanc") else it }
    return (if (short.length > 18) short.take(17) + "…" else short) + " "
}

/** Étiquette encadrée posée au-dessus ou au-dessous d'une ligne, à partir de [left] ; renvoie la place occupée. */
private fun DrawScope.drawTag(
    measurer: TextMeasurer,
    text: String,
    left: Float,
    y: Float,
    color: Color,
    background: Color,
    above: Boolean,
): Rect {
    val layout = measurer.measure(text, TextStyle(color = color, fontSize = 11.sp, fontFamily = AppMono, fontWeight = FontWeight.SemiBold))
    val pad = 5.dp.toPx()
    val w = layout.size.width + 2 * pad
    val h = layout.size.height + pad
    val top = if (above) y - h - 3.dp.toPx() else y + 3.dp.toPx()
    drawRoundRect(background, Offset(left, top), Size(w, h), CornerRadius(5.dp.toPx()))
    drawRoundRect(color, Offset(left, top), Size(w, h), CornerRadius(5.dp.toPx()), style = Stroke(1.dp.toPx()))
    drawText(layout, topLeft = Offset(left + pad, top + pad / 2))
    return Rect(left, top, left + w, top + h)
}

/**
 * Texte sur un fond arrondi, ancré en [at] ([anchorX], [anchorY] comme [drawLabel]) et gardé entre [minX] et
 * [maxX]. Rien n'est dessiné s'il chevauche une des zones [avoid] : renvoie alors null, sinon la place occupée.
 */
private fun DrawScope.drawPill(
    measurer: TextMeasurer,
    text: String,
    at: Offset,
    color: Color,
    background: Color,
    sizeSp: Float,
    anchorX: Float,
    anchorY: Float,
    minX: Float,
    maxX: Float,
    avoid: List<Rect> = emptyList(),
): Rect? {
    val layout = measurer.measure(text, TextStyle(color = color, fontSize = sizeSp.sp, fontFamily = AppMono, fontWeight = FontWeight.SemiBold))
    val padX = 4.dp.toPx()
    val padY = 1.dp.toPx()
    val w = layout.size.width + 2 * padX
    val h = layout.size.height + 2 * padY
    val left = (at.x - w * anchorX).coerceIn(minX, maxOf(minX, maxX - w))
    val rect = Rect(left, at.y - h * anchorY, left + w, at.y - h * anchorY + h)
    if (avoid.any { it.overlaps(rect) }) return null
    drawRoundRect(background.copy(alpha = 0.88f), rect.topLeft, rect.size, CornerRadius(5.dp.toPx()))
    drawText(layout, topLeft = Offset(rect.left + padX, rect.top + padY))
    return rect
}

/** Iso 0 °C (tirets, points) et limite pluie-neige (pointillés, losanges) jour par jour, puis la neige à 2 000 m. */
@Composable
private fun TrendChart(f: Forecast, slots: List<Slot>, selected: Int, reference: Double, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val chart = LocalChartColors.current
    val snow = remember(f, slots) { slots.map { Altitude.snow(f, it.hours, reference) } }
    Canvas(modifier.padding(top = 6.dp)) {
        val axis = 40.dp.toPx()
        val col = (size.width - axis) / slots.size
        fun cx(k: Int) = axis + col * k + col / 2
        val plotTop = 18.dp.toPx()
        val plotBottom = size.height - 84.dp.toPx()
        val values = slots.flatMap { listOfNotNull(it.freezingLevel.takeIf { v -> !v.isNaN() }, it.snowLine) }
        // Marge sous la plus basse limite pluie-neige pour sa valeur, au-dessus des jours.
        val lo = ((values.minOrNull() ?: 1000.0) - 600).coerceAtLeast(0.0)
        val hi = (values.maxOrNull() ?: 3000.0) + 300
        val scaleMin = Math.floor(lo / 1000) * 1000
        val scaleMax = maxOf(Math.ceil(hi / 1000) * 1000, scaleMin + 2000)
        fun y(alt: Double) = (plotBottom - (alt - scaleMin) / (scaleMax - scaleMin) * (plotBottom - plotTop)).toFloat()

        // Colonne du jour choisi.
        drawRoundRect(
            colors.surfaceContainer,
            topLeft = Offset(axis + col * selected + 2.dp.toPx(), 0f),
            size = Size(col - 4.dp.toPx(), size.height),
            cornerRadius = CornerRadius(8.dp.toPx()),
        )
        var t = scaleMin
        while (t <= scaleMax) {
            if (t > 0) {
                drawLine(chart.grid, Offset(axis, y(t)), Offset(size.width, y(t)), 1.dp.toPx())
                drawLabel(measurer, Fmt.thousands(t), Offset(0f, y(t)), chart.muted, 11f, anchorY = 0.5f)
            }
            t += 1000
        }
        fun km(v: Double) = String.format(Locale.FRANCE, "%.1f", v / 1000)

        val isoPath = Path()
        var started = false
        slots.forEachIndexed { k, s ->
            if (s.freezingLevel.isNaN()) return@forEachIndexed
            if (!started) isoPath.moveTo(cx(k), y(s.freezingLevel)) else isoPath.lineTo(cx(k), y(s.freezingLevel))
            started = true
        }
        drawPath(isoPath, colors.primary, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
        val snowPath = Path()
        var previous: Int? = null
        slots.forEachIndexed { k, s ->
            val line = s.snowLine ?: run { previous = null; return@forEachIndexed }
            if (previous == k - 1) snowPath.lineTo(cx(k), y(line)) else snowPath.moveTo(cx(k), y(line))
            previous = k
        }
        drawPath(snowPath, colors.onBackground, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 6f))))

        // Valeurs sur fond, iso 0 au-dessus de son point et pluie-neige sous son losange : elles ne se croisent pas.
        val background = colors.surfaceContainerLowest
        slots.forEachIndexed { k, s ->
            val bold = k == selected
            val tint = if (bold) colors.surfaceContainer else background
            if (!s.freezingLevel.isNaN()) {
                val p = Offset(cx(k), y(s.freezingLevel))
                drawCircle(colors.primary, 4.dp.toPx(), p)
                drawPill(measurer, km(s.freezingLevel), Offset(p.x, p.y - 6.dp.toPx()), colors.primary, tint, 12f, 0.5f, 1f, axis, size.width)
            }
            val line = s.snowLine
            if (line != null) {
                val p = Offset(cx(k), y(line))
                val r = 4.dp.toPx()
                val diamond = Path().apply {
                    moveTo(p.x, p.y - r); lineTo(p.x + r, p.y); lineTo(p.x, p.y + r); lineTo(p.x - r, p.y); close()
                }
                drawPath(diamond, background)
                drawPath(diamond, colors.onBackground, style = Stroke(1.6.dp.toPx()))
                drawPill(measurer, km(line), Offset(p.x, p.y + 7.dp.toPx()), colors.onBackground, tint, 12f, 0.5f, 0f, axis, size.width)
            }
            // Jour (ou heure) sur deux lignes : « Lun » puis « 20 ».
            s.axis.split("\n").forEachIndexed { n, text ->
                val color = if (bold) colors.onBackground else chart.muted
                drawLabel(measurer, text, Offset(cx(k), plotBottom + (8 + 15 * n).dp.toPx()), color, 11f, anchorX = 0.5f, bold = bold || n == 0)
            }
        }

        // Neige à 2 000 m.
        val rowTop = plotBottom + 42.dp.toPx()
        val rowBottom = size.height - 4.dp.toPx()
        drawLine(chart.axis, Offset(axis, rowBottom), Offset(size.width, rowBottom), 1.dp.toPx())
        drawLabel(measurer, "cm à", Offset(0f, rowTop + 4.dp.toPx()), chart.muted, 10f)
        drawLabel(measurer, Fmt.thousands(reference), Offset(0f, rowTop + 18.dp.toPx()), chart.muted, 10f)
        val maxCm = maxOf(10.0, snow.maxOrNull() ?: 0.0)
        snow.forEachIndexed { k, cm ->
            if (cm < 1) {
                drawLabel(measurer, "0", Offset(cx(k), rowBottom - 2.dp.toPx()), chart.muted, 11f, anchorX = 0.5f, anchorY = 1f)
                return@forEachIndexed
            }
            val h = ((cm / maxCm) * (rowBottom - rowTop - 16.dp.toPx())).toFloat().coerceAtLeast(10.dp.toPx())
            val w = col * 0.62f
            val rect = Rect(Offset(cx(k) - w / 2, rowBottom - h), Size(w, h))
            drawRoundRect(colors.primary.copy(alpha = 0.12f), rect.topLeft, rect.size, CornerRadius(3.dp.toPx()))
            clipRect(rect.left, rect.top, rect.right, rect.bottom) {
                var x = rect.left - rect.height
                while (x < rect.right) {
                    drawLine(colors.primary.copy(alpha = 0.6f), Offset(x, rect.bottom), Offset(x + rect.height, rect.top), 1.dp.toPx())
                    x += 5.dp.toPx()
                }
            }
            drawRoundRect(colors.primary, rect.topLeft, rect.size, CornerRadius(3.dp.toPx()), style = Stroke(1.dp.toPx()))
            drawLabel(measurer, "+${cm.roundToInt()}", Offset(cx(k), rect.top - 2.dp.toPx()), colors.primary, 11.5f, anchorX = 0.5f, anchorY = 1f, bold = true)
        }
    }
}

/** Sommet, 2 000 m (ou 3 000 m) et lieu : température, ressenti, vent et neige du jour (ou des trois heures). */
@Composable
private fun AltitudeTable(f: Forecast, slot: Slot, mode: Int, terrain: TerrainProfile?, place: Place) {
    val colors = MaterialTheme.colorScheme
    val ground = Altitude.ground(f)
    val rows = altitudeRows(ground, terrain, place)
    val title = (if (mode == 0) Fmt.dayShort(slot.date) else "${Fmt.dayShort(slot.date)} · ${slot.axis}").uppercase(Locale.FRANCE)
    Column(Modifier.padding(start = 18.dp, end = 16.dp, top = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            MonoText(title, size = 10.sp, modifier = Modifier.weight(1f))
            MonoText("TEMP.", size = 10.sp, modifier = Modifier.width(52.dp))
            MonoText("RESSENTI", size = 10.sp, modifier = Modifier.width(62.dp))
            MonoText("VENT", size = 10.sp, modifier = Modifier.width(72.dp))
            MonoText("NEIGE", size = 10.sp, align = TextAlign.End, modifier = Modifier.width(56.dp))
        }
        HorizontalDivider(color = colors.outlineVariant)
        rows.forEach { (alt, name) ->
            val i = slot.sample
            val t = Altitude.temperature(f, i, alt)
            val wind = Altitude.wind(f, i, alt)
            val feel = if (alt == ground) f.hourly.apparentTemperature.getOrElse(i) { Double.NaN } else Altitude.windChill(t, wind.speed)
            val cm = Altitude.snow(f, slot.hours, alt)
            val rain = Altitude.rain(f, slot.hours, alt)
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    MonoText(Fmt.altitude(alt), color = colors.onBackground, size = 13.sp, bold = true)
                    if (name != null) MonoText(name, size = 10.sp)
                }
                Text(Fmt.temp(t), fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.width(52.dp))
                Text(
                    Fmt.temp(feel),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    color = if (!feel.isNaN() && feel <= -20) AlertRed else colors.onBackground,
                    modifier = Modifier.width(62.dp),
                )
                MonoText("${Outlook.compass(wind.direction)} ${Fmt.kmh(wind.speed)}", size = 11.sp, modifier = Modifier.width(72.dp))
                Text(
                    when {
                        cm >= 1 -> "+${cm.roundToInt()} cm"
                        rain >= 0.5 -> "pluie"
                        else -> "–"
                    },
                    color = if (cm >= 1 || rain >= 0.5) colors.primary else colors.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(56.dp),
                )
            }
            HorizontalDivider(color = colors.outlineVariant)
        }
    }
}

/** Altitudes du tableau, de la plus haute à la plus basse, avec leur nom quand on en a un. */
private fun altitudeRows(ground: Double, terrain: TerrainProfile?, place: Place): List<Pair<Double, String?>> {
    val rows = ArrayList<Pair<Double, String?>>()
    val summit = terrain?.summit
    if (summit != null && summit.elevation > ground + 300) rows += summit.elevation to summit.name
    if ((summit == null || summit.elevation < 2700) && ground < 2700) rows += 3000.0 to terrain?.nameNear(3000.0)
    if (ground < 1800) rows += 2000.0 to terrain?.nameNear(2000.0)
    rows += ground to place.name
    val kept = ArrayList<Pair<Double, String?>>()
    for (row in rows.sortedByDescending { it.first }) {
        if (kept.none { Math.abs(it.first - row.first) < 150 } || row.first == ground) {
            kept.removeAll { row.first == ground && it.first != ground && Math.abs(it.first - ground) < 150 }
            kept += row
        }
    }
    return kept
}
