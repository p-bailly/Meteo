package io.github.pbailly.meteo.data

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.sqrt

/** Fourchette de pluie d'une journée selon les modèles (quartiles des cumuls), en mm. */
data class RainRange(val low: Double, val high: Double)

/**
 * Indice de confiance, de 1 à 5 comme celui de Météo-France, mesuré par la dispersion des 51 scénarios de l'ensemble
 * ECMWF ; à défaut (ensemble pas encore chargé), par celle des 8 modèles, qui s'accordent plus souvent qu'ils ne le
 * devraient. Deux critères à parts égales :
 * - la température maximale : sûre quand les scénarios s'écartent de moins de 0,5 °C (écart-type), incertaine à 4 °C ;
 * - la pluie (au moins 1 mm) : sûre quand presque tous disent « sec » ou presque tous « pluie », incertaine quand ils se
 *   partagent ; s'il pleut, la dispersion des cumuls compte aussi.
 */
object Predictability {
    /** Il faut au moins 3 scénarios (ou modèles) qui couvrent la période pour parler d'accord. */
    private const val MIN_MEMBERS = 3

    fun daily(ensemble: EnsembleForecast?, multiModel: MultiModelForecast?, date: LocalDate): Int? {
        val members = ensemble?.let { dailyTotals(it.time, it.temperature, it.precipitation, date) }?.takeIf { it.size >= MIN_MEMBERS }
            ?: multiModel?.let { dailyTotals(it, date) }?.takeIf { it.size >= MIN_MEMBERS }
            ?: return null
        return level(score(members.map { it.first }, members.map { it.second }, wetMm = 1.0, sureSpread = 0.5, unsureSpread = 4.0))
    }

    /** Même indice pour une heure : température et pluie (au moins 0,1 mm) de cette heure. */
    fun hourly(ensemble: EnsembleForecast?, multiModel: MultiModelForecast?, time: LocalDateTime): Int? {
        fun at(times: List<LocalDateTime>, temps: List<DoubleArray>, rains: List<DoubleArray>): List<Pair<Double, Double>> {
            val i = times.indexOf(time)
            if (i < 0) return emptyList()
            return temps.indices.mapNotNull { m ->
                val t = temps[m].getOrElse(i) { Double.NaN }
                val p = rains[m].getOrElse(i) { Double.NaN }
                if (t.isNaN() || p.isNaN()) null else t to p
            }
        }
        val members = ensemble?.let { at(it.time, it.temperature, it.precipitation) }?.takeIf { it.size >= MIN_MEMBERS }
            ?: multiModel?.let { mm -> at(mm.time, mm.series.values.map { it.temperature }, mm.series.values.map { it.precipitation }) }
                ?.takeIf { it.size >= MIN_MEMBERS }
            ?: return null
        return level(score(members.map { it.first }, members.map { it.second }, wetMm = 0.1, sureSpread = 0.3, unsureSpread = 3.0))
    }

    /** Note sur 5 d'un score d'accord de 0 à 1. */
    fun level(score: Double): Int = when {
        score >= 0.8 -> 5
        score >= 0.65 -> 4
        score >= 0.5 -> 3
        score >= 0.35 -> 2
        else -> 1
    }

    /** Accord des scénarios, de 0 à 1 : moitié température, moitié pluie. */
    internal fun score(temps: List<Double>, rains: List<Double>, wetMm: Double, sureSpread: Double, unsureSpread: Double): Double {
        val tempScore = (1 - (std(temps) - sureSpread) / (unsureSpread - sureSpread)).coerceIn(0.0, 1.0)
        val wet = rains.count { it >= wetMm }.toDouble() / rains.size
        var rainScore = abs(2 * wet - 1)
        if (wet > 0.5) {
            // D'accord pour de la pluie : reste à savoir combien.
            val rainy = rains.filter { it >= wetMm }
            val variation = std(rainy) / rainy.average()
            rainScore *= 0.6 + 0.4 * (1 - variation).coerceIn(0.0, 1.0)
        }
        return 0.5 * tempScore + 0.5 * rainScore
    }

    /** Cumul de pluie probable (du 1er au 3e quartile des modèles). */
    fun rainRange(mm: MultiModelForecast, date: LocalDate): RainRange? {
        val sums = dailyTotals(mm, date).map { it.second }.sorted()
        if (sums.size < MIN_MEMBERS) return null
        return RainRange(quantile(sums, 0.25), quantile(sums, 0.75))
    }

    /** Pour chaque modèle couvrant la journée : (température maxi, cumul de pluie). */
    private fun dailyTotals(mm: MultiModelForecast, date: LocalDate): List<Pair<Double, Double>> =
        dailyTotals(mm.time, mm.series.values.map { it.temperature }, mm.series.values.map { it.precipitation }, date)

    /** Maximum de température et cumul de pluie du jour, pour chaque scénario ou modèle qui couvre la journée. */
    private fun dailyTotals(times: List<LocalDateTime>, temps: List<DoubleArray>, rains: List<DoubleArray>, date: LocalDate): List<Pair<Double, Double>> {
        val hours = times.indices.filter { times[it].toLocalDate() == date }
        if (hours.isEmpty()) return emptyList()
        return temps.indices.mapNotNull { m ->
            val t = hours.map { temps[m].getOrElse(it) { Double.NaN } }.filter { !it.isNaN() }
            val r = hours.map { rains[m].getOrElse(it) { Double.NaN } }.filter { !it.isNaN() }
            // Un scénario qui s'arrête en cours de journée ne compte pas.
            if (t.size < hours.size * 0.9 || r.size < hours.size * 0.9) null else t.max() to r.sum()
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
