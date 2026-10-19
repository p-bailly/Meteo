package io.github.pbailly.meteo.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Lever et coucher de la Lune un jour donné, en heure locale du lieu (null si pas d'événement ce jour-là). */
data class MoonTimes(val rise: LocalDateTime?, val set: LocalDateTime?)

/**
 * Position de la Lune à basse précision (formules de l'Astronomical Almanac, environ 0,3°),
 * suffisante pour des heures de lever et de coucher à quelques minutes près.
 */
object Moon {
    /** Hauteur apparente du centre au lever : parallaxe (~0,95°) moins réfraction et demi-diamètre. */
    private const val RISE_ALTITUDE = 0.125

    fun times(date: LocalDate, latitude: Double, longitude: Double, utcOffsetSeconds: Int): MoonTimes {
        val offset = ZoneOffset.ofTotalSeconds(utcOffsetSeconds)
        val start = date.atStartOfDay()
        var rise: LocalDateTime? = null
        var set: LocalDateTime? = null
        val stepMinutes = 10L
        var previousTime = start
        var previous = altitude(start.toEpochSecond(offset), latitude, longitude) - RISE_ALTITUDE
        var t = start.plusMinutes(stepMinutes)
        while (!t.isAfter(start.plusDays(1))) {
            val current = altitude(t.toEpochSecond(offset), latitude, longitude) - RISE_ALTITUDE
            if (previous < 0 && current >= 0 && rise == null) rise = interpolate(previousTime, previous, current)
            if (previous >= 0 && current < 0 && set == null) set = interpolate(previousTime, previous, current)
            previous = current
            previousTime = t
            t = t.plusMinutes(stepMinutes)
        }
        return MoonTimes(rise?.takeIf { it.toLocalDate() == date }, set?.takeIf { it.toLocalDate() == date })
    }

    private fun interpolate(t0: LocalDateTime, h0: Double, h1: Double): LocalDateTime {
        val fraction = h0 / (h0 - h1)
        return t0.plusSeconds((fraction * 600).toLong())
    }

    /** Hauteur géocentrique de la Lune au-dessus de l'horizon, en degrés. */
    fun altitude(epochSecond: Long, latitude: Double, longitude: Double): Double {
        val d = (epochSecond - 946_728_000L) / 86_400.0 // jours depuis J2000.0 (1er janvier 2000, 12 h TU)
        val t = d / 36_525.0
        val l = rad(218.32 + 481_267.881 * t + 6.29 * sinD(135.0 + 477_198.87 * t) - 1.27 * sinD(259.3 - 413_335.36 * t) +
            0.66 * sinD(235.7 + 890_534.22 * t) + 0.21 * sinD(269.9 + 954_397.74 * t) - 0.19 * sinD(357.5 + 35_999.05 * t) -
            0.11 * sinD(186.5 + 966_404.03 * t))
        val b = rad(5.13 * sinD(93.3 + 483_202.02 * t) + 0.28 * sinD(228.2 + 960_400.89 * t) -
            0.28 * sinD(318.3 + 6_003.15 * t) - 0.17 * sinD(217.6 - 407_332.21 * t))
        val obliquity = rad(23.439 - 0.0000004 * d)
        val ra = atan2(sin(l) * cos(obliquity) - kotlin.math.tan(b) * sin(obliquity), cos(l))
        val dec = asin(sin(b) * cos(obliquity) + cos(b) * sin(obliquity) * sin(l))
        val sidereal = rad(280.46061837 + 360.98564736629 * d + longitude)
        val hourAngle = sidereal - ra
        val lat = rad(latitude)
        return Math.toDegrees(asin(sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(hourAngle)))
    }

    private fun rad(deg: Double) = Math.toRadians(deg)
    private fun sinD(deg: Double) = sin(Math.toRadians(deg))
}
