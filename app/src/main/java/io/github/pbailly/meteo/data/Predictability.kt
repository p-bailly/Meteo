package io.github.pbailly.meteo.data

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Fourchette de pluie d'une journée selon les modèles (quartiles des cumuls), en mm. */
data class RainRange(val low: Double, val high: Double)

/**
 * Prévisibilité, de 0 à 100 % : plus les modèles s'accordent, plus la prévision est sûre.
 * On regarde l'écart entre leurs températures et leur accord sur « pluie ou pas pluie »,
 * comme le fait Meteoblue avec son ensemble de modèles.
 */
object Predictability {
    /** Il faut au moins 3 modèles qui couvrent la période pour parler d'accord. */
    private const val MIN_MODELS = 3

    fun daily(mm: MultiModelForecast, date: LocalDate): Int? {
        val days = dailyTotals(mm, date)
        if (days.size < MIN_MODELS) return null
        val tempScore = (1 - std(days.map { it.first }) / 4.0).coerceIn(0.0, 1.0)
        val sums = days.map { it.second }
        val wet = sums.count { it >= 1.0 }.toDouble() / sums.size
        var rainScore = abs(2 * wet - 1)
        if (wet > 0.5) {
            // Tous d'accord pour de la pluie : reste à savoir combien.
            val rainy = sums.filter { it >= 1.0 }
            val variation = std(rainy) / rainy.average()
            rainScore *= 0.5 + 0.5 * (1 - variation).coerceIn(0.0, 1.0)
        }
        return (100 * (0.45 * tempScore + 0.55 * rainScore)).roundToInt()
    }

    fun hourly(mm: MultiModelForecast, time: LocalDateTime): Int? {
        val i = mm.time.indexOf(time)
        if (i < 0) return null
        val values = mm.series.values.mapNotNull { s ->
            val t = s.temperature.getOrElse(i) { Double.NaN }
            val p = s.precipitation.getOrElse(i) { Double.NaN }
            if (t.isNaN() || p.isNaN()) null else t to p
        }
        if (values.size < MIN_MODELS) return null
        val tempScore = (1 - std(values.map { it.first }) / 3.0).coerceIn(0.0, 1.0)
        val wet = values.count { it.second >= 0.1 }.toDouble() / values.size
        val rainScore = abs(2 * wet - 1)
        return (100 * (0.5 * tempScore + 0.5 * rainScore)).roundToInt()
    }

    /** Cumul de pluie probable (du 1er au 3e quartile des modèles). */
    fun rainRange(mm: MultiModelForecast, date: LocalDate): RainRange? {
        val sums = dailyTotals(mm, date).map { it.second }.sorted()
        if (sums.size < MIN_MODELS) return null
        return RainRange(quantile(sums, 0.25), quantile(sums, 0.75))
    }

    /** Niveau de 1 à 4 pour les pictogrammes de cible. */
    fun level(percent: Int): Int = when {
        percent >= 75 -> 4
        percent >= 55 -> 3
        percent >= 35 -> 2
        else -> 1
    }

    /** Pour chaque modèle couvrant la journée : (température maxi, cumul de pluie). */
    private fun dailyTotals(mm: MultiModelForecast, date: LocalDate): List<Pair<Double, Double>> {
        val hours = mm.time.indices.filter { mm.time[it].toLocalDate() == date }
        if (hours.isEmpty()) return emptyList()
        return mm.series.values.mapNotNull { s ->
            val temps = hours.map { s.temperature.getOrElse(it) { Double.NaN } }.filter { !it.isNaN() }
            val rain = hours.map { s.precipitation.getOrElse(it) { Double.NaN } }.filter { !it.isNaN() }
            // Un modèle qui s'arrête en cours de journée ne compte pas.
            if (temps.size < hours.size * 0.9 || rain.size < hours.size * 0.9) null else temps.max() to rain.sum()
        }
    }

    private fun std(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    private fun quantile(sorted: List<Double>, q: Double): Double {
        val pos = q * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, sorted.lastIndex)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }
}
