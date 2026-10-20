package io.github.pbailly.meteo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.pbailly.meteo.data.AirQuality
import io.github.pbailly.meteo.data.Altitude
import io.github.pbailly.meteo.data.Forecast
import io.github.pbailly.meteo.data.Outlook
import io.github.pbailly.meteo.data.Place
import io.github.pbailly.meteo.ui.theme.AppCondensed
import io.github.pbailly.meteo.ui.theme.AppSans
import io.github.pbailly.meteo.ui.theme.LocalChartColors
import java.time.LocalDateTime
import kotlin.math.roundToInt
import io.github.pbailly.meteo.ui.theme.LocalTextSize

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
    onOpenAltitude: () -> Unit,
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
            // Tout tient sur un écran : les prochaines heures prennent la hauteur qui reste. Le défilement ne sert
            // que si l'écran est trop petit (ou le texte très grand), et au geste de mise à jour.
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val viewport = maxHeight
                FillHeight(
                    viewport = viewport,
                    minFill = 232.dp,
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    top = {
                        // Le haut de l'écran, avec en fond un dessin au crayon du temps qu'il fait (sous la barre d'état,
                        // pour que ses repères tombent au même endroit du texte sur tous les téléphones).
                        Box {
                            f.current?.let {
                                var postcard by remember { mutableStateOf(false) }
                                val conditions = sketchConditions(f, state.airQuality.data, state.kp)
                                val sketch = @Composable { modifier: Modifier, onLongPress: (() -> Unit)? ->
                                    WeatherSketch(
                                        it.weatherCode, it.isDay, it.time, place.latitude, place.longitude, place.id.hashCode(),
                                        state.terrain.data, state.surroundings.data, conditions,
                                        style = state.sketchStyle,
                                        motion = state.sketchMotion,
                                        modifier = modifier,
                                        onLongPress = onLongPress,
                                    )
                                }
                                sketch(Modifier.matchParentSize().statusBarsPadding()) { postcard = true }
                                // Appui long : le dessin en carte postale, en grand.
                                if (postcard) {
                                    SketchPostcard(place.name, it.time, it.temperature, it.weatherCode, onDismiss = { postcard = false }) { m -> sketch(m, null) }
                                }
                            }
                            Column {
                                // Neige & altitude (à la saison de la neige) : une pastille dans l'en-tête, pour laisser la
                                // place au dessin.
                                TodayHeader(f, place, state.isFavorite, onToggleFavorite, onOpenPlaces)
                                StatusLine(statusText(remote))
                                NowBlock(f)
                                // Sous le texte, le bas du dessin : le village, la rivière, les champs, bien visibles.
                                Spacer(Modifier.height(SKETCH_GROUND))
                            }
                        }
                        MetricsGrid(f, onOpenAltitude)
                    },
                    fill = { NextHours(f, Modifier.fillMaxSize()) },
                )
            }
        }
    }
}

/** Hauteur du dessin laissée libre sous le texte d'Aujourd'hui. */
private val SKETCH_GROUND = 72.dp

/**
 * Ce que la prévision de l'heure ajoute au dessin : les nuages bas, moyens et hauts (pour les dessiner à leur hauteur),
 * le vent, le lever et le coucher du soleil, la neige au sol, un froid durable qui gèle les lacs, l'air du moment.
 */
internal fun sketchConditions(f: Forecast, air: AirQuality?, kp: Double? = null): SketchConditions {
    val h = f.hourly
    val c = f.current
    val i = Outlook.nowIndex(f)
    val low = h.cloudCoverLow.getOrElse(i) { Double.NaN }
    val mid = h.cloudCoverMid.getOrElse(i) { Double.NaN }
    val high = h.cloudCoverHigh.getOrElse(i) { Double.NaN }
    val clouds = if (low.isNaN() || mid.isNaN() || high.isNaN()) null else SketchClouds((low / 100).toFloat(), (mid / 100).toFloat(), (high / 100).toFloat())
    val d = f.daily
    val today = c?.time?.toLocalDate()?.let { d.date.indexOf(it) }?.takeIf { it >= 0 } ?: 0
    // Neige au sol : l'épaisseur prévue (3 cm au moins), à défaut la neige tombée depuis minuit par temps froid.
    val depth = h.snowDepth.getOrElse(i) { Double.NaN }
    val fallen = (0..i).sumOf { k -> h.snowfall.getOrElse(k) { 0.0 }.takeIf { !it.isNaN() && h.time[k].toLocalDate() == h.time[i].toLocalDate() } ?: 0.0 }
    val snowOnGround = if (!depth.isNaN()) depth >= 0.03 else fallen >= 1.0 && (c?.temperature ?: 9.0) <= 2
    // Les lacs gèlent après des jours sans dégel : aujourd'hui et demain sous 0 °C, et une nuit sous −6 °C.
    val maxes = (today..today + 1).map { d.temperatureMax.getOrElse(it) { Double.NaN } }
    val frozen = maxes.none { it.isNaN() || it > 0 } && d.temperatureMin.getOrElse(today) { Double.NaN } <= -6
    // La limite de la neige sur les montagnes : l'isotherme 0 °C de la prévision (maintenant quand il tombe quelque
    // chose, sinon le plus bas des prochaines 24 heures).
    val precipitating = h.precipitation.getOrElse(i) { 0.0 }.let { !it.isNaN() && it >= 0.2 }
    val freezing = (i..i + 24).map { h.freezingLevel.getOrElse(it) { Double.NaN } }.filter { !it.isNaN() }
    return SketchConditions(
        clouds = clouds,
        windKmh = c?.windSpeed?.takeIf { !it.isNaN() } ?: 0.0,
        windFrom = c?.windDirection?.takeIf { !it.isNaN() } ?: 270.0,
        sunrise = d.sunrise.getOrNull(today),
        sunset = d.sunset.getOrNull(today),
        snowOnGround = snowOnGround,
        frozen = frozen,
        dust = air?.saharanDust == true,
        haze = air?.haze == true,
        temperature = c?.temperature?.takeIf { !it.isNaN() },
        freezingLevel = if (precipitating) freezing.firstOrNull() else freezing.minOrNull(),
        precipitating = precipitating,
        kp = kp,
        utcOffsetSeconds = f.utcOffsetSeconds,
    )
}

@Composable
private fun TodayHeader(
    f: Forecast,
    place: Place,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenPlaces: () -> Unit,
) {
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
                // Le nom en entier : il rapetisse pour tenir sur la ligne (jusqu'à 16 sp), puis passe sur deux lignes.
                BasicText(
                    place.name,
                    style = LocalTextStyle.current.copy(color = colors.onSurface, fontWeight = FontWeight.SemiBold, lineHeight = 1.15.em),
                    maxLines = 2,
                    autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 28.sp, stepSize = 1.sp),
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
                    place.department ?: place.country?.takeIf { it.isNotBlank() },
                    Fmt.dateLine(time.toLocalDate()),
                    // En très grand texte, l'heure laisse la place au reste de la ligne.
                    Fmt.clock(time).takeIf { LocalTextSize.current < 2 },
                ).joinToString(" · "),
                modifier = Modifier.padding(start = 2.dp, top = 4.dp),
            )
        }
        FavoriteStar(favorite, onToggleFavorite)
    }
}

/** « 14 °C » en grand, le ciel à droite, le ressenti et les extrêmes du jour. */
@Composable
private fun NowBlock(f: Forecast) {
    val c = f.current ?: return
    val today = f.daily.date.indexOf(c.time.toLocalDate()).coerceAtLeast(0)
    val accent = LocalChartColors.current.accent
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp), verticalAlignment = Alignment.Top) {
        // La température, et le ressenti juste en dessous.
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    if (c.temperature.isNaN()) "–" else c.temperature.roundToInt().toString(),
                    fontFamily = AppCondensed,
                    fontWeight = FontWeight.Light,
                    fontSize = 112.sp,
                    lineHeight = 112.sp,
                )
                Text("°C", fontFamily = AppSans, fontWeight = FontWeight.Light, fontSize = 26.sp, modifier = Modifier.padding(top = 16.dp))
            }
            MonoText("ressenti ${Fmt.temp(c.apparentTemperature)}", size = 13.sp, modifier = Modifier.padding(start = 4.dp))
        }
        Spacer(Modifier.weight(1f))
        // Pas de pictogramme : le dessin du fond montre déjà le temps qu'il fait, et prend sa place.
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(top = 70.dp)) {
            Text(weatherDescription(c.weatherCode), fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1)
            val high = f.daily.temperatureMax.getOrElse(today) { Double.NaN }
            val low = f.daily.temperatureMin.getOrElse(today) { Double.NaN }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                MonoText(Fmt.temp(high), color = accent, bold = true, size = 13.sp)
                MonoText(" / " + Fmt.temp(low), color = MaterialTheme.colorScheme.primary, bold = true, size = 13.sp)
            }
        }
    }
}

/**
 * Colonne dont les éléments [top] gardent leur hauteur et dont [fill] prend le reste de [viewport], sans descendre
 * sous [minFill] : la page tient sur un écran, et ne défile que si elle ne peut pas y tenir.
 */
@Composable
private fun FillHeight(viewport: Dp, minFill: Dp, modifier: Modifier, top: @Composable () -> Unit, fill: @Composable () -> Unit) {
    Layout(contents = listOf(top, fill), modifier = modifier) { (tops, fills), constraints ->
        val width = constraints.maxWidth
        val placed = tops.map { it.measure(Constraints(minWidth = width, maxWidth = width)) }
        val used = placed.sumOf { it.height }
        val fillHeight = maxOf(viewport.roundToPx() - used, minFill.roundToPx())
        val filled = fills.map { it.measure(Constraints.fixed(width, fillHeight)) }
        layout(width, used + fillHeight) {
            var y = 0
            placed.forEach { it.place(0, y); y += it.height }
            filled.forEach { it.place(0, y) }
        }
    }
}

/** Saison de la neige : de la mi-octobre à la mi-mars (date du lieu). */
fun snowSeason(f: Forecast): Boolean {
    val date = f.current?.time?.toLocalDate() ?: f.daily.date.firstOrNull() ?: return true
    val day = date.monthValue * 100 + date.dayOfMonth
    return day >= 1015 || day <= 315
}

/** Étoile des favoris, aux branches arrondies : pleine et orange pour un favori, au trait sinon. */
@Composable
private fun FavoriteStar(favorite: Boolean, onClick: () -> Unit) {
    val accent = LocalChartColors.current.accent
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = if (favorite) "Retirer des favoris" else "Ajouter aux favoris", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(30.dp)) {
            val outer = size.minDimension / 2
            val inner = outer * 0.47f
            val star = Path()
            for (k in 0 until 10) {
                val r = if (k % 2 == 0) outer else inner
                val angle = Math.toRadians(-90.0 + k * 36.0)
                val x = center.x + r * kotlin.math.cos(angle).toFloat()
                val y = center.y + outer * 0.06f + r * kotlin.math.sin(angle).toFloat()
                if (k == 0) star.moveTo(x, y) else star.lineTo(x, y)
            }
            star.close()
            val rounded = PathEffect.cornerPathEffect(outer * 0.22f)
            if (favorite) {
                drawPath(star, accent, style = Fill)
                drawPath(star, accent, style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round, pathEffect = rounded))
            } else {
                drawPath(star, outline, style = Stroke(width = 1.8.dp.toPx(), join = StrokeJoin.Round, pathEffect = rounded))
            }
        }
    }
}

/**
 * Vent, rafales, humidité, pression (et sa tendance sur 3 h), iso 0 °C (qui ouvre la page Neige & altitude), coucher
 * et lever du soleil.
 */
@Composable
private fun MetricsGrid(f: Forecast, onOpenAltitude: () -> Unit) {
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
    val iso = Altitude.freezingLevel(f, i)
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
            Tile(
                Icons.Outlined.Terrain,
                "ISO 0 °C",
                if (iso.isNaN()) "–" else Fmt.altitude(iso, 100).removeSuffix("\u00A0m"),
                "m ›",
                Modifier.weight(1f).clickable(onClickLabel = "Neige & altitude", onClick = onOpenAltitude),
                accent = true,
            )
            VerticalDivider(color = colors.outlineVariant)
            Tile(Icons.Outlined.WbTwilight, "SOLEIL", sunMain, if (LocalTextSize.current < 2) sunNote else "", Modifier.weight(1f), accent = true)
        }
    }
}

private fun number(v: Double) = if (v.isNaN()) "–" else v.roundToInt().toString()

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
private fun Tile(icon: ImageVector, label: String, value: String, unit: String, modifier: Modifier, accent: Boolean = false) {
    Column(modifier.padding(horizontal = 9.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val tint = if (accent) LocalChartColors.current.accent else MaterialTheme.colorScheme.onSurfaceVariant
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
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

/** Les 48 prochaines heures, à faire défiler : pictogramme, température, probabilité et cumul de pluie. */
@Composable
private fun NextHours(f: Forecast, modifier: Modifier = Modifier) = Column(modifier) {
    val h = f.hourly
    val start = Outlook.nowIndex(f)
    val hours = (start until minOf(h.size, start + 48)).toList()
    if (hours.isEmpty()) return@Column
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val chart = LocalChartColors.current
    val dark = chart.darkTheme
    Box(Modifier.fillMaxWidth().weight(1f).horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Canvas(Modifier.width(46.dp * hours.size).fillMaxHeight()) {
            val col = size.width / hours.size
            val temps = hours.map { h.temperature[it] }.filter { !it.isNaN() }
            val tMin = temps.minOrNull() ?: 0.0
            val tMax = (temps.maxOrNull() ?: 1.0).let { if (it - tMin < 4) tMin + 4 else it }
            val maxMm = maxOf(2.0, hours.maxOf { h.precipitation[it].takeIf { v -> !v.isNaN() } ?: 0.0 })
            // Hauteur au-delà du minimum (168 dp) : surtout pour la courbe de température ; les barres de pluie restent
            // basses, pour laisser la place au dessin du haut.
            val extra = (size.height - 168.dp.toPx()).coerceAtLeast(0f)
            val tempBottom = 100.dp.toPx() + extra * 0.65f
            val tempSpan = 26.dp.toPx() + extra * 0.5f
            fun ty(t: Double) = (tempBottom - (t - tMin) / (tMax - tMin) * tempSpan).toFloat()
            val probabilityY = tempBottom + 16.dp.toPx()
            val barBottom = size.height - 4.dp.toPx()
            val barTop = probabilityY + 20.dp.toPx()

            for (k in 1 until hours.size) {
                drawLine(chart.grid, Offset(col * k, 18.dp.toPx()), Offset(col * k, barBottom), 1.dp.toPx())
            }
            drawLine(chart.axis, Offset(0f, barBottom), Offset(size.width, barBottom), 1.dp.toPx())
            val line = Path()
            hours.forEachIndexed { k, idx ->
                val cx = col * k + col / 2
                // À minuit, le nom du jour remplace l'heure, avec un trait plus marqué.
                val midnight = k > 0 && h.time[idx].hour == 0
                val label = when {
                    k == 0 -> "Maint."
                    midnight -> Fmt.dayName(h.time[idx].toLocalDate())
                    else -> "%d h".format(h.time[idx].hour)
                }
                if (midnight) drawLine(chart.axis, Offset(col * k, 0f), Offset(col * k, barBottom), 1.5.dp.toPx())
                val labelColor = when {
                    k == 0 -> chart.accent
                    midnight -> colors.onBackground
                    else -> chart.muted
                }
                drawLabel(measurer, label, Offset(cx, 0f), labelColor, 10.5f, anchorX = 0.5f, bold = k == 0 || midnight)
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
                    drawLabel(measurer, "${p.roundToInt()} %", Offset(cx, probabilityY), if (p >= 50) colors.primary else chart.muted, 10.5f, anchorX = 0.5f, bold = p >= 50)
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

