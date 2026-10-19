package io.github.pbailly.meteo.data

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.pow

/** Vent à une altitude : vitesse en km/h et direction d'où il vient, en degrés. */
data class Wind(val speed: Double, val direction: Double)

/** Une journée vue de la montagne : iso 0 °C, limite pluie-neige (s'il pleut) et cumul de pluie. */
class AltitudeDay(
    val date: LocalDate,
    /** Indices des heures du jour dans [HourlySeries]. */
    val hours: IntRange,
    /** Iso 0 °C du jour, en m : pendant les précipitations s'il y en a, sinon moyenne de la journée. */
    val freezingLevel: Double,
    /** Limite pluie-neige pendant les précipitations ; null quand la journée est sèche. */
    val snowLine: Double?,
    val precipitation: Double,
)

/** Créneau de verglas possible : sur le lieu même, ou sur les routes au-dessus de [altitude]. */
class IceRisk(
    val start: LocalDateTime,
    val end: LocalDateTime,
    val altitude: Double,
    val atPlace: Boolean,
    val freezingRain: Boolean,
    /** Température la plus basse du créneau, 300 m au-dessus de [altitude] (ou sur le lieu). */
    val minTemperature: Double,
    val referenceAltitude: Double,
)

/**
 * Météo en altitude, calculée sur le téléphone à partir de la prévision du lieu : la température entre le sol
 * et chaque niveau de pression du modèle (dont l'altitude réelle est connue), le vent des niveaux, la limite
 * pluie-neige, la neige tombée à une altitude donnée, le verglas et les fenêtres de beau temps.
 */
object Altitude {
    /** La limite pluie-neige se tient en moyenne 300 m sous l'isotherme 0 °C. */
    const val SNOW_LINE_BELOW_FREEZING = 300.0

    /** Altitude de référence des cumuls de neige (« cm à 2 000 m »). */
    const val SNOW_REFERENCE = 2000.0

    /** Gradient de l'atmosphère standard (−0,65 °C / 100 m), hors des niveaux connus. */
    private const val STANDARD_LAPSE = 0.0065

    /** Neige fraîche : environ 1 cm par mm d'eau. */
    private const val SNOW_CM_PER_MM = 1.0

    /** Une heure est « mouillée » à partir de 0,1 mm, une journée à partir de 0,5 mm. */
    private const val WET_HOUR_MM = 0.1
    private const val WET_DAY_MM = 0.5

    /** Verglas : on cherche jusqu'à 1 200 m au-dessus du lieu (routes de col). */
    private const val ICE_SEARCH_ABOVE = 1200.0
    private const val ICE_TEMPERATURE = 0.5
    private const val ICE_WET_MM = 0.3
    private const val ICE_WET_HOURS = 12
    private val FREEZING_CODES = setOf(56, 57, 66, 67)

    /** Altitude du point de prévision (modèle de terrain à 90 m d'Open-Meteo). */
    fun ground(f: Forecast, fallback: Double? = null): Double =
        f.elevation.takeIf { !it.isNaN() } ?: fallback?.takeIf { !it.isNaN() } ?: 0.0

    /** Température à [altitude] à l'heure [i], interpolée entre la température à 2 m et les niveaux de pression. */
    fun temperature(f: Forecast, i: Int, altitude: Double): Double {
        val points = temperatureProfile(f, i)
        if (points.isEmpty()) return Double.NaN
        val (z0, t0) = points.first()
        if (altitude <= z0) return t0 + (z0 - altitude) * STANDARD_LAPSE
        val (zn, tn) = points.last()
        if (altitude >= zn) return tn - (altitude - zn) * STANDARD_LAPSE
        for (k in 1 until points.size) {
            val (z1, t1) = points[k]
            if (altitude <= z1) {
                val (za, ta) = points[k - 1]
                return ta + (t1 - ta) * (altitude - za) / (z1 - za)
            }
        }
        return tn
    }

    /** Points (altitude, température) de l'heure [i], du sol vers le haut. */
    private fun temperatureProfile(f: Forecast, i: Int): List<Pair<Double, Double>> {
        val h = f.hourly
        val ground = ground(f)
        val points = ArrayList<Pair<Double, Double>>(h.levels.size + 1)
        val t2 = h.temperature.getOrElse(i) { Double.NaN }
        if (!t2.isNaN()) points += ground to t2
        for (level in h.levels) {
            val z = level.height.getOrElse(i) { Double.NaN }
            val t = level.temperature.getOrElse(i) { Double.NaN }
            if (!z.isNaN() && !t.isNaN() && z > ground + 50) points += z to t
        }
        // Sans niveaux de pression (liste des lieux), l'iso 0 °C donne le gradient.
        if (points.size == 1) {
            val iso = h.freezingLevel.getOrElse(i) { Double.NaN }
            if (!iso.isNaN() && iso > ground + 100 && t2 > 0) points += iso to 0.0
        }
        return points.sortedBy { it.first }
    }

    /** Vent à [altitude] : 10 m au sol, puis celui des niveaux de pression (vitesse interpolée, direction la plus proche). */
    fun wind(f: Forecast, i: Int, altitude: Double): Wind {
        val h = f.hourly
        val ground = ground(f)
        val points = ArrayList<Triple<Double, Double, Double>>()
        val surface = h.windSpeed.getOrElse(i) { Double.NaN }
        if (!surface.isNaN()) points += Triple(ground + 10, surface, h.windDirection.getOrElse(i) { Double.NaN })
        for (level in h.levels) {
            val z = level.height.getOrElse(i) { Double.NaN }
            val s = level.windSpeed.getOrElse(i) { Double.NaN }
            if (!z.isNaN() && !s.isNaN() && z > ground + 50) points += Triple(z, s, level.windDirection.getOrElse(i) { Double.NaN })
        }
        if (points.isEmpty()) return Wind(Double.NaN, Double.NaN)
        points.sortBy { it.first }
        if (altitude <= points.first().first) return Wind(points.first().second, points.first().third)
        if (altitude >= points.last().first) return Wind(points.last().second, points.last().third)
        for (k in 1 until points.size) {
            val (z1, s1, d1) = points[k]
            if (altitude <= z1) {
                val (z0, s0, d0) = points[k - 1]
                val u = (altitude - z0) / (z1 - z0)
                return Wind(s0 + (s1 - s0) * u, if (u < 0.5) d0 else d1)
            }
        }
        return Wind(points.last().second, points.last().third)
    }

    /** Iso 0 °C de l'heure [i] : celui du modèle, sinon calculé sur le profil de température. */
    fun freezingLevel(f: Forecast, i: Int): Double {
        val model = f.hourly.freezingLevel.getOrElse(i) { Double.NaN }
        if (!model.isNaN()) return model
        if (temperatureProfile(f, i).isEmpty()) return Double.NaN
        // Le plus haut passage à 0 °C, en descendant depuis 6 000 m.
        var z = 6000.0
        var above = temperature(f, i, z)
        while (z > -200) {
            val below = temperature(f, i, z - 25)
            if (above <= 0 && below > 0) return (z - 25) + 25 * below / (below - above)
            above = below
            z -= 25
        }
        return if (above > 0) 6000.0 else ground(f)
    }

    /** Limite pluie-neige de l'heure [i]. */
    fun snowLine(f: Forecast, i: Int): Double {
        val iso = freezingLevel(f, i)
        return if (iso.isNaN()) Double.NaN else (iso - SNOW_LINE_BELOW_FREEZING).coerceAtLeast(0.0)
    }

    /** Neige tombée à [altitude] sur les heures [hours], en cm (la pluie du lieu, quand elle tombe en neige là-haut). */
    fun snow(f: Forecast, hours: IntRange, altitude: Double): Double {
        var cm = 0.0
        for (i in hours) {
            val mm = f.hourly.precipitation.getOrElse(i) { Double.NaN }
            if (mm.isNaN() || mm < WET_HOUR_MM) continue
            val line = snowLine(f, i)
            if (!line.isNaN() && altitude >= line) cm += mm * SNOW_CM_PER_MM
        }
        return cm
    }

    /** Pluie (mm) qui tombe en pluie, et non en neige, à [altitude] sur les heures [hours]. */
    fun rain(f: Forecast, hours: IntRange, altitude: Double): Double {
        var mm = 0.0
        for (i in hours) {
            val p = f.hourly.precipitation.getOrElse(i) { Double.NaN }
            if (p.isNaN() || p < WET_HOUR_MM) continue
            val line = snowLine(f, i)
            if (line.isNaN() || altitude < line) mm += p
        }
        return mm
    }

    /** Les journées de la prévision, avec leur iso 0 °C et leur limite pluie-neige. */
    fun days(f: Forecast): List<AltitudeDay> {
        val h = f.hourly
        if (h.size == 0) return emptyList()
        val result = ArrayList<AltitudeDay>()
        var start = 0
        while (start < h.size) {
            val date = h.time[start].toLocalDate()
            var end = start
            while (end + 1 < h.size && h.time[end + 1].toLocalDate() == date) end++
            result += day(f, date, start..end)
            start = end + 1
        }
        return result
    }

    private fun day(f: Forecast, date: LocalDate, hours: IntRange): AltitudeDay {
        val precip = f.hourly.precipitation
        val total = hours.sumOf { precip[it].takeIf { v -> !v.isNaN() } ?: 0.0 }
        val wet = hours.filter { precip[it] >= WET_HOUR_MM }
        return if (total >= WET_DAY_MM && wet.isNotEmpty()) {
            // Pendant les précipitations : c'est là que la limite pluie-neige compte.
            AltitudeDay(
                date, hours,
                freezingLevel = weighted(wet, precip) { freezingLevel(f, it) },
                snowLine = weighted(wet, precip) { snowLine(f, it) },
                precipitation = total,
            )
        } else {
            val levels = hours.map { freezingLevel(f, it) }.filter { !it.isNaN() }
            AltitudeDay(date, hours, if (levels.isEmpty()) Double.NaN else levels.average(), null, total)
        }
    }

    private fun weighted(hours: List<Int>, weights: DoubleArray, value: (Int) -> Double): Double {
        var sum = 0.0
        var total = 0.0
        for (i in hours) {
            val v = value(i)
            if (v.isNaN()) continue
            sum += v * weights[i]
            total += weights[i]
        }
        return if (total > 0) sum / total else Double.NaN
    }

    /** Gradient de température entre [from] et [to] mètres, en °C par 100 m (négatif quand il fait plus froid en haut). */
    fun lapseRate(f: Forecast, i: Int, from: Double, to: Double): Double {
        if (to == from) return Double.NaN
        return (temperature(f, i, to) - temperature(f, i, from)) / (to - from) * 100
    }

    /** Température ressentie par le vent (formule d'Environnement Canada), en °C. */
    fun windChill(celsius: Double, kmh: Double): Double {
        if (celsius.isNaN() || kmh.isNaN() || celsius > 10 || kmh < 4.8) return celsius
        val v = kmh.pow(0.16)
        return 13.12 + 0.6215 * celsius - 11.37 * v + 0.3965 * celsius * v
    }

    /**
     * Verglas possible entre les heures [from] et [to] : pluie verglaçante, ou routes mouillées par la pluie des
     * 12 dernières heures quand la température retombe vers 0 °C, sur le lieu ou jusqu'à 1 200 m au-dessus.
     */
    fun iceRisks(f: Forecast, from: Int, to: Int): List<IceRisk> {
        val h = f.hourly
        val ground = ground(f)
        class Hour(val i: Int, val altitude: Double, val freezingRain: Boolean)
        val risky = ArrayList<Hour>()
        for (i in from.coerceAtLeast(0) until to.coerceAtMost(h.size)) {
            val code = h.weatherCode.getOrElse(i) { -1 }
            if (code in FREEZING_CODES) {
                risky += Hour(i, ground, freezingRain = true)
                continue
            }
            val iceLevel = iceAltitude(f, i, ground) ?: continue
            // S'il neige à cette altitude, c'est de la neige, pas du verglas.
            val now = h.precipitation.getOrElse(i) { 0.0 }
            if (now >= WET_HOUR_MM && iceLevel >= snowLine(f, i)) continue
            var wet = 0.0
            for (j in (i - ICE_WET_HOURS).coerceAtLeast(0)..i) {
                val p = h.precipitation.getOrElse(j) { 0.0 }
                if (p >= WET_HOUR_MM && iceLevel < snowLine(f, j)) wet += p
            }
            if (wet >= ICE_WET_MM) risky += Hour(i, iceLevel, freezingRain = false)
        }
        // Heures voisines (à 2 h près) regroupées en créneaux.
        val result = ArrayList<IceRisk>()
        var k = 0
        while (k < risky.size) {
            var end = k
            while (end + 1 < risky.size && risky[end + 1].i - risky[end].i <= 2) end++
            val group = risky.subList(k, end + 1)
            val altitude = group.minOf { it.altitude }
            val atPlace = altitude <= ground + 1
            val reference = if (atPlace) ground else (altitude + 300).coerceAtMost(ground + ICE_SEARCH_ABOVE)
            result += IceRisk(
                start = h.time[group.first().i],
                end = h.time[group.last().i],
                altitude = altitude,
                atPlace = atPlace,
                freezingRain = group.any { it.freezingRain },
                minTemperature = group.minOf { temperature(f, it.i, reference) },
                referenceAltitude = reference,
            )
            k = end + 1
        }
        return result
    }

    /** Plus basse altitude (entre le lieu et 1 200 m au-dessus) où il fait au plus 0,5 °C, ou null. */
    private fun iceAltitude(f: Forecast, i: Int, ground: Double): Double? {
        var z = ground
        while (z <= ground + ICE_SEARCH_ABOVE) {
            val t = temperature(f, i, z)
            if (t.isNaN()) return null
            if (t <= ICE_TEMPERATURE) return z
            z += 50
        }
        return null
    }

    /**
     * Fenêtre de beau temps d'une journée : la plus longue suite d'heures entre 8 h et 20 h, sèches, peu nuageuses,
     * avec moins de 20 km/h de vent. Au moins 6 heures, sinon null. Ne commence pas avant l'heure [from].
     */
    fun fairWindow(f: Forecast, hours: IntRange, from: Int = 0): IntRange? {
        val h = f.hourly
        var best: IntRange? = null
        var runStart = -1
        fun close(endExclusive: Int) {
            if (runStart >= 0 && endExclusive - runStart >= 6 && (best == null || endExclusive - runStart > best!!.count())) {
                best = runStart until endExclusive
            }
            runStart = -1
        }
        for (i in hours) {
            val hour = h.time[i].hour
            val ok = i >= from && hour in 8..19 &&
                h.precipitation.getOrElse(i) { 0.0 }.let { it.isNaN() || it < WET_HOUR_MM } &&
                h.precipitationProbability.getOrElse(i) { Double.NaN }.let { it.isNaN() || it <= 20 } &&
                h.windSpeed.getOrElse(i) { Double.NaN }.let { !it.isNaN() && it < 20 } &&
                h.weatherCode.getOrElse(i) { -1 } in 0..2
            if (ok && runStart < 0) runStart = i
            if (!ok) close(i)
        }
        close(hours.last + 1)
        return best
    }
}
