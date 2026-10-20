package io.github.pbailly.meteo.ui

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Mise en forme des valeurs à la française (virgule décimale, unités, jours). */
object Fmt {
    private val FR = Locale.FRANCE
    private const val DASH = "–"

    private val dayShort = DateTimeFormatter.ofPattern("EEE d", FR)
    private val dayLong = DateTimeFormatter.ofPattern("EEEE d MMMM", FR)
    private val dayName = DateTimeFormatter.ofPattern("EEE", FR)
    private val clock = DateTimeFormatter.ofPattern("HH:mm", FR)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", FR)
    private val dateLine = DateTimeFormatter.ofPattern("EEE d MMM", FR)

    private val COMPASS = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO",
    )

    fun temp(v: Double): String = if (v.isNaN()) DASH else "${v.roundToInt()}°"

    fun mm(v: Double): String = when {
        v.isNaN() -> DASH
        v >= 10 -> "${v.roundToInt()} mm"
        else -> String.format(FR, "%.1f mm", v)
    }

    fun cm(v: Double): String = if (v.isNaN()) DASH else String.format(FR, "%.1f cm", v)

    fun kmh(v: Double): String = if (v.isNaN()) DASH else "${v.roundToInt()} km/h"

    fun percent(v: Double): String = if (v.isNaN()) DASH else "${v.roundToInt()} %"

    fun hPa(v: Double): String = if (v.isNaN()) DASH else "${v.roundToInt()} hPa"

    fun sunshine(seconds: Double): String =
        if (seconds.isNaN()) DASH else String.format(FR, "%.1f h", seconds / 3600.0).replace(",0 h", " h")

    fun uv(v: Double): String = if (v.isNaN()) DASH else String.format(FR, "%.0f", v)

    fun meters(v: Double?): String? = v?.takeIf { !it.isNaN() }?.let { "${it.roundToInt()} m" }

    /** Direction d'où vient le vent, sur 16 points (N, NNE, NE…). */
    fun compass(degrees: Double): String {
        if (degrees.isNaN()) return DASH
        val index = (((degrees % 360 + 360) % 360) / 22.5).roundToInt() % 16
        return COMPASS[index]
    }

    fun hour(t: LocalDateTime): String = "${t.hour}h"

    /** Milliers séparés par une espace insécable : « 2 800 ». */
    fun thousands(v: Double, step: Int = 1): String {
        if (v.isNaN()) return DASH
        val rounded = (v / step).roundToInt() * step
        val digits = kotlin.math.abs(rounded).toString().reversed().chunked(3).joinToString("\u00A0").reversed()
        return (if (rounded < 0) "-" else "") + digits
    }

    /** Altitude : « 2 800 m » (arrondie à [step] mètres). */
    fun altitude(v: Double, step: Int = 1): String = if (v.isNaN()) DASH else thousands(v, step) + "\u00A0m"

    /** Une décimale à la française, sans « ,0 » : « 2,1 », « 0 », « 12 ». */
    fun decimal(v: Double): String = when {
        v.isNaN() -> DASH
        v < 0.05 -> "0"
        v >= 10 -> v.roundToInt().toString()
        else -> String.format(FR, "%.1f", v).removeSuffix(",0")
    }

    /** « 19 oct. » */
    fun dayMonth(date: LocalDate): String = date.format(dayMonth)

    /** « lun. 19 oct. » */
    fun dateLine(date: LocalDate): String = date.format(dateLine)

    fun clock(t: LocalDateTime?): String = t?.format(clock) ?: DASH

    fun clockOfMillis(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).format(clock)

    fun dayShort(date: LocalDate): String = date.format(dayShort)

    /** « ma 20 » : la date courte quand la place manque (texte agrandi). */
    fun dayTiny(date: LocalDate): String = dayName(date).take(2) + " " + date.dayOfMonth

    fun dayName(date: LocalDate): String = date.format(dayName)

    fun dayLong(date: LocalDate): String = date.format(dayLong).replaceFirstChar { it.titlecase(FR) }

    /** « Aujourd'hui », « Demain » ou « mer. 22 ». */
    fun relativeDay(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Aujourd'hui"
        today.plusDays(1) -> "Demain"
        else -> dayShort(date).replaceFirstChar { it.titlecase(FR) }
    }
}

/** Libellé français d'un code météo WMO renvoyé par Open-Meteo. */
fun weatherDescription(code: Int): String = when (code) {
    0 -> "Ciel dégagé"
    1 -> "Plutôt ensoleillé"
    2 -> "Partiellement nuageux"
    3 -> "Couvert"
    45 -> "Brouillard"
    48 -> "Brouillard givrant"
    51 -> "Bruine légère"
    53 -> "Bruine"
    55 -> "Bruine dense"
    56, 57 -> "Bruine verglaçante"
    61 -> "Pluie faible"
    63 -> "Pluie modérée"
    65 -> "Forte pluie"
    66, 67 -> "Pluie verglaçante"
    71 -> "Neige faible"
    73 -> "Neige modérée"
    75 -> "Forte neige"
    77 -> "Grains de neige"
    80 -> "Averses faibles"
    81 -> "Averses"
    82 -> "Violentes averses"
    85 -> "Averses de neige"
    86 -> "Fortes averses de neige"
    95, 96 -> "Orage"
    // La grêle annoncée par les codes 96 et 99 n'est fiable qu'en Europe centrale : on n'en parle pas.
    99 -> "Orage fort"
    else -> "—"
}
