package io.github.pbailly.meteo.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Épisode neigeux : jours consécutifs avec au moins 1 cm à l'altitude de référence. */
class SnowEvent(
    val days: List<AltitudeDay>,
    /** Cumul de chaque jour à [reference], en cm. */
    val amounts: List<Double>,
    val reference: Double,
    /** Iso 0 °C le plus bas de l'épisode. */
    val freezingLevel: Double,
    /** Neige sur le lieu lui-même pendant l'épisode, en cm. */
    val atPlace: Double,
    /** Vrai quand l'épisode ne commence pas aujourd'hui (« retour de la neige »). */
    val afterDryDays: Boolean,
)

/** Note d'un lieu dans la liste : une alerte (ressenti, verglas) ou une information (neige à venir). */
data class PlaceNote(val text: String, val alert: Boolean)

/** Ce qu'on annonce à partir de la prévision : neige, verglas, notes des lieux. */
object Outlook {
    /** Altitude des cumuls de neige : 2 000 m, ou le lieu lui-même s'il est plus haut. */
    fun snowReference(elevation: Double): Double =
        if (elevation.isNaN() || elevation <= Altitude.SNOW_REFERENCE) Altitude.SNOW_REFERENCE else Math.round(elevation / 100.0) * 100.0

    /** Jour neigeux : au moins 2 cm ; épisode annoncé : au moins 3 cm en tout. */
    private const val SNOWY_DAY_CM = 2.0
    private const val SNOW_EVENT_CM = 3.0

    /** Premier épisode neigeux des [maxDays] prochains jours, à partir d'aujourd'hui. */
    fun snowEvent(f: Forecast, today: LocalDate, maxDays: Int = 8): SnowEvent? {
        val ground = Altitude.ground(f)
        val reference = snowReference(ground)
        val days = Altitude.days(f).filter { !it.date.isBefore(today) }.take(maxDays)
        val amounts = days.map { Altitude.snow(f, it.hours, reference) }
        var first = -1
        var last = -1
        var k = 0
        while (k < days.size) {
            if (amounts[k] < SNOWY_DAY_CM) {
                k++
                continue
            }
            var end = k
            while (end + 1 < days.size && amounts[end + 1] >= SNOWY_DAY_CM) end++
            if ((k..end).sumOf { amounts[it] } >= SNOW_EVENT_CM) {
                first = k
                last = end
                break
            }
            k = end + 1
        }
        if (first < 0) return null
        val event = days.subList(first, last + 1)
        return SnowEvent(
            days = event,
            amounts = amounts.subList(first, last + 1),
            reference = reference,
            freezingLevel = event.minOf { it.freezingLevel.takeIf { v -> !v.isNaN() } ?: Double.MAX_VALUE },
            atPlace = event.sumOf { Altitude.snow(f, it.hours, ground) },
            afterDryDays = first > 0,
        )
    }

    /** Index de l'heure en cours dans la prévision. */
    fun nowIndex(f: Forecast, now: LocalDateTime = LocalDateTime.now(ZoneOffset.ofTotalSeconds(f.utcOffsetSeconds))): Int {
        val times = f.hourly.time
        val hour = now.truncatedTo(ChronoUnit.HOURS)
        val index = times.indexOf(hour)
        return if (index >= 0) index else if (times.isNotEmpty() && hour.isAfter(times.last())) times.lastIndex else 0
    }

    /** Note d'un favori : ressenti glacial, verglas dans la semaine, ou prochaine neige sur place. */
    fun placeNote(f: Forecast, today: LocalDate, now: Int = nowIndex(f)): PlaceNote? {
        f.current?.let { c ->
            if (!c.apparentTemperature.isNaN() && c.apparentTemperature <= -10) {
                return PlaceNote("ressenti ${degrees(c.apparentTemperature)} · ${compass(c.windDirection)} ${kmh(c.windSpeed)}", alert = true)
            }
        }
        Altitude.iceRisks(f, now, now + 8 * 24).firstOrNull { it.atPlace }?.let { risk ->
            return PlaceNote("verglas possible ${dayLabel(risk.start.toLocalDate(), today)} ${partOfDay(risk.start.hour)}", alert = true)
        }
        val ground = Altitude.ground(f)
        for (day in Altitude.days(f).filter { !it.date.isBefore(today) }) {
            val cm = Altitude.snow(f, day.hours, ground)
            if (cm >= 1.0) return PlaceNote("neige ${dayLabel(day.date, today)} : +${Math.round(cm)} cm", alert = false)
        }
        return null
    }

    /** « auj. », « demain » ou « lun. 26 ». */
    fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "auj."
        today.plusDays(1) -> "demain"
        else -> date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d", java.util.Locale.FRANCE))
    }

    fun partOfDay(hour: Int): String = when (hour) {
        in 5..11 -> "matin"
        in 12..17 -> "après-midi"
        in 18..22 -> "soir"
        else -> "nuit"
    }

    private fun degrees(v: Double) = "${Math.round(v)}°"

    private fun kmh(v: Double) = if (v.isNaN()) "–" else "${Math.round(v)} km/h"

    private val COMPASS = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")

    /** Direction sur 8 points, d'où vient le vent. */
    fun compass(degrees: Double): String {
        if (degrees.isNaN()) return "–"
        return COMPASS[(Math.round(((degrees % 360) + 360) % 360 / 45.0) % 8).toInt()]
    }
}
