package io.github.pbailly.meteo.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Ce qu'on annonce à partir de la prévision : pictogramme du jour, moment de la pluie, altitude des cumuls de neige. */
object Outlook {
    /** En dessous de ce relief, pas de montagne autour du lieu : la neige compte sur le lieu, pas à 2 000 m. */
    private const val MOUNTAIN_RELIEF = 1500.0

    /**
     * Altitude des cumuls de neige : 2 000 m (ou le lieu lui-même s'il est plus haut) quand il y a des montagnes
     * autour ; en plaine ([reliefTop], le plus haut point des environs, sous 1 500 m), le lieu lui-même.
     */
    fun snowReference(elevation: Double, reliefTop: Double? = null): Double = when {
        elevation.isNaN() -> Altitude.SNOW_REFERENCE
        reliefTop != null && reliefTop < MOUNTAIN_RELIEF -> Math.round(elevation / 10.0) * 10.0
        elevation <= Altitude.SNOW_REFERENCE -> Altitude.SNOW_REFERENCE
        else -> Math.round(elevation / 100.0) * 100.0
    }

    /** Index de l'heure en cours dans la prévision. */
    fun nowIndex(f: Forecast, now: LocalDateTime = LocalDateTime.now(ZoneOffset.ofTotalSeconds(f.utcOffsetSeconds))): Int {
        val times = f.hourly.time
        val hour = now.truncatedTo(ChronoUnit.HOURS)
        val index = times.indexOf(hour)
        return if (index >= 0) index else if (times.isNotEmpty() && hour.isAfter(times.last())) times.lastIndex else 0
    }

    private val THUNDER_CODES = setOf(95, 96, 99)
    private val SNOW_CODES = setOf(71, 73, 75, 77, 85, 86)
    private val FREEZING_CODES = setOf(56, 57, 66, 67)
    private val SHOWER_CODES = setOf(80, 81, 82, 85, 86)
    private val DRIZZLE_CODES = setOf(51, 53, 55)

    /** Pictogramme d'un jour : voir [representativeCode]. */
    fun dayCode(f: Forecast, date: LocalDate): Int =
        representativeCode(f, f.hourly.time.indices.filter { f.hourly.time[it].toLocalDate() == date })

    /**
     * Code météo qui résume une période (un jour, trois heures) : le temps qui domine, pas le pire moment.
     * Open-Meteo donne pour chaque jour le code le plus grave des 24 heures : une heure d'orage la nuit suffit
     * à faire de toute la journée un jour d'orage.
     */
    fun representativeCode(f: Forecast, hours: List<Int>): Int {
        val h = f.hourly
        val valid = hours.filter { h.weatherCode.getOrElse(it) { -1 } >= 0 }
        if (valid.isEmpty()) return -1
        val long = valid.size > 6
        val total = valid.sumOf { h.precipitation.getOrElse(it) { 0.0 }.takeIf { v -> !v.isNaN() } ?: 0.0 }
        val wet = valid.filter { h.weatherCode[it] >= 51 && h.weatherCode[it] !in 45..48 }
        // Un épisode bref (au moins 2 h sur une journée, 1 h sur trois heures) suffit pour l'orage et le verglas.
        val enough = if (long) 2 else 1
        // Sur une journée, une seule heure mouillée ne suffit pas (sauf une vraie averse de 3 mm).
        val rainy = if (long) (wet.size >= 2 && total >= 1.0) || total >= 3.0 else total >= 0.3
        if (wet.isNotEmpty() && (rainy || wet.size * 2 >= valid.size)) {
            val codes = wet.map { h.weatherCode[it] }
            if (codes.count { it in THUNDER_CODES } >= enough) return 95
            if (codes.count { it in FREEZING_CODES } >= enough) return 66
            val (light, moderate) = if (long) 3.0 to 10.0 else 1.0 to 4.0
            val showers = codes.count { it in SHOWER_CODES } * 2 > codes.size
            if (codes.count { it in SNOW_CODES } * 2 >= codes.size) {
                return when {
                    total < light -> if (showers) 85 else 71
                    total < moderate -> if (showers) 85 else 73
                    else -> if (showers) 86 else 75
                }
            }
            if (codes.count { it in DRIZZLE_CODES } * 2 > codes.size && total < light) return if (total < light / 3) 51 else 53
            return when {
                total < light -> if (showers) 80 else 61
                total < moderate -> if (showers) 81 else 63
                else -> if (showers) 82 else 65
            }
        }
        if (valid.count { h.weatherCode[it] in 45..48 } * 3 >= valid.size) return 45
        // Ciel : moyenne des heures de jour (les précipitations faibles comptent comme un ciel couvert).
        val day = valid.filter { h.isDay.getOrElse(it) { true } }.ifEmpty { valid }
        return day.map { minOf(h.weatherCode[it], 3) }.average().let { Math.round(it).toInt() }.coerceIn(0, 3)
    }

    /**
     * Quand tombe une pluie qui commence à [start], vu depuis [now] : le matin et en journée, une pluie du jour
     * même est pour « aujourd'hui » ; le soir (à partir de 18 h), et pour une pluie d'après minuit, c'est « cette
     * nuit » jusqu'à 7 h le lendemain ; ensuite « demain » ou le jour (« jeu. 22 »).
     */
    fun rainPeriod(start: LocalDateTime, now: LocalDateTime): String {
        val today = now.toLocalDate()
        val evening = now.hour >= 18 || now.hour < 6
        // La nuit en cours (avant 6 h) ou la prochaine se termine à 7 h.
        val nightEnd = if (now.hour < 6) today.atTime(7, 0) else today.plusDays(1).atTime(7, 0)
        return when {
            start.toLocalDate() == today && !evening -> "aujourd'hui"
            start.isBefore(nightEnd) -> "cette nuit"
            start.toLocalDate() == today -> "aujourd'hui"
            start.toLocalDate() == today.plusDays(1) -> "demain"
            else -> dayLabel(start.toLocalDate(), today)
        }
    }

    /** « auj. », « demain » ou « lun. 26 ». */
    fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "auj."
        today.plusDays(1) -> "demain"
        else -> date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d", java.util.Locale.FRANCE))
    }

    private val COMPASS = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")

    /** Direction sur 8 points, d'où vient le vent. */
    fun compass(degrees: Double): String {
        if (degrees.isNaN()) return "–"
        return COMPASS[(Math.round(((degrees % 360) + 360) % 360 / 45.0) % 8).toInt()]
    }
}
