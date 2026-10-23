package io.github.pbailly.meteo.data

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.max

/** Fourchette de pluie d'une journée selon les modèles (quartiles des cumuls), en mm. */
data class RainRange(val low: Double, val high: Double)

/**
 * Indice de confiance, de 1 à 5 comme celui de Météo-France : la part des scénarios qui donnent le temps le plus
 * probable, comme la « predictability » de Meteoblue (« la probabilité que le temps prévu se produise, pour toute la
 * journée et toutes les variables »). Les scénarios viennent de cinq prévisions d'ensemble (européennes IFS et AIFS,
 * américaine, allemande, canadienne), chaque centre pesant autant quel que soit son nombre de scénarios : un seul
 * ensemble s'accorde plus souvent qu'il ne le devrait, ses scénarios partageant les défauts de leur modèle. À défaut
 * (ensembles pas encore chargés), les 8 modèles, qui s'accordent plus souvent encore.
 *
 * Un scénario donne le même temps que le scénario médian quand il s'accorde sur tout à la fois :
 * - la température maximale, à 2 °C près ;
 * - la pluie : sec ou pluie, et s'il pleut, le cumul du simple au double (ou à 3 fois le seuil de pluie près) ;
 * - la nébulosité de la journée (8 h–20 h), à 30 % près ;
 * - le vent le plus fort, à 10 km/h près (30 % au-delà de 33 km/h).
 * 5/5 au-dessus de 80 %, 4/5 de 60 %, 3/5 de 40 %, 2/5 de 20 %, comme les classes de Meteoblue.
 */
object Predictability {
    /** Il faut au moins 3 scénarios, d'au moins 2 modèles, qui couvrent la période pour parler d'accord. */
    private const val MIN_MEMBERS = 3
    private const val MIN_MODELS = 2

    fun daily(ensemble: EnsembleForecast?, multiModel: MultiModelForecast?, date: LocalDate): Int? =
        confidence(ensemble, multiModel, wetMm = 1.0, cloudHours = 8..19) { it.toLocalDate() == date }

    /** Même indice pour quelques heures (une, ou un bloc de 3) : pluie à partir de 0,1 mm par heure. */
    fun hours(ensemble: EnsembleForecast?, multiModel: MultiModelForecast?, times: List<LocalDateTime>): Int? {
        if (times.isEmpty()) return null
        val set = times.toSet()
        return confidence(ensemble, multiModel, wetMm = 0.1 * times.size, cloudHours = 0..23) { it in set }
    }

    /** Note sur 5 d'une part de scénarios d'accord, de 0 à 1. */
    fun level(agreement: Double): Int = when {
        agreement >= 0.8 -> 5
        agreement >= 0.6 -> 4
        agreement >= 0.4 -> 3
        agreement >= 0.2 -> 2
        else -> 1
    }

    /** Un scénario (ou un modèle) sur la période ; NaN quand il ne dit rien des nuages ou du vent. */
    internal class Scenario(val model: String, val temperature: Double, val rain: Double, val cloud: Double, val wind: Double)

    private fun confidence(
        ensemble: EnsembleForecast?,
        multiModel: MultiModelForecast?,
        wetMm: Double,
        cloudHours: IntRange,
        inPeriod: (LocalDateTime) -> Boolean,
    ): Int? {
        fun enough(s: List<Scenario>) = s.size >= MIN_MEMBERS && s.map { it.model }.distinct().size >= MIN_MODELS
        val scenarios = ensemble?.let {
            scenarios(it.time, it.model, it.temperature, it.precipitation, it.cloudCover, it.windSpeed, cloudHours, inPeriod)
        }?.takeIf(::enough)
            ?: multiModel?.let { mm ->
                val series = mm.series.entries.toList()
                scenarios(
                    mm.time, series.map { it.key.name }, series.map { it.value.temperature }, series.map { it.value.precipitation },
                    emptyList(), series.map { it.value.windSpeed }, cloudHours, inPeriod,
                )
            }?.takeIf(::enough)
            ?: return null
        return level(agreement(scenarios, wetMm))
    }

    /** Part des scénarios (pondérés pour que chaque modèle pèse autant) qui donnent le même temps que le médian. */
    internal fun agreement(scenarios: List<Scenario>, wetMm: Double): Double {
        val perModel = scenarios.groupingBy { it.model }.eachCount()
        val weights = scenarios.map { 1.0 / perModel.getValue(it.model) }
        fun median(value: (Scenario) -> Double): Double {
            val values = scenarios.indices.map { value(scenarios[it]) to weights[it] }.filter { !it.first.isNaN() }.sortedBy { it.first }
            if (values.isEmpty()) return Double.NaN
            val half = values.sumOf { it.second } / 2
            var sum = 0.0
            return values.first { sum += it.second; sum >= half }.first
        }
        val temperature = median { it.temperature }
        val rain = median { it.rain }
        val cloud = median { it.cloud }
        val wind = median { it.wind }

        fun near(a: Double, b: Double, tolerance: Double) = a.isNaN() || b.isNaN() || abs(a - b) <= tolerance
        fun sameRain(r: Double): Boolean = when {
            r < wetMm || rain < wetMm -> (r < wetMm) == (rain < wetMm)
            else -> r / rain in 0.5..2.0 || abs(r - rain) <= 3 * wetMm
        }
        val agreeing = scenarios.indices.filter { i ->
            val s = scenarios[i]
            near(s.temperature, temperature, 2.0) && sameRain(s.rain) &&
                near(s.cloud, cloud, 30.0) && near(s.wind, wind, max(10.0, 0.3 * wind))
        }
        return agreeing.sumOf { weights[it] } / weights.sum()
    }

    /** Cumul de pluie probable (du 1er au 3e quartile des modèles). */
    fun rainRange(mm: MultiModelForecast, date: LocalDate): RainRange? {
        val series = mm.series.values.toList()
        val sums = scenarios(
            mm.time, series.map { "" }, series.map { it.temperature }, series.map { it.precipitation },
            emptyList(), emptyList(), 0..23,
        ) { it.toLocalDate() == date }.map { it.rain }.sorted()
        if (sums.size < MIN_MEMBERS) return null
        return RainRange(quantile(sums, 0.25), quantile(sums, 0.75))
    }

    /**
     * Température maximale, cumul de pluie, nébulosité moyenne (aux heures [cloudHours]) et vent maximal de la
     * période, pour chaque scénario ou modèle qui la couvre.
     */
    private fun scenarios(
        times: List<LocalDateTime>,
        models: List<String>,
        temps: List<DoubleArray>,
        rains: List<DoubleArray>,
        clouds: List<DoubleArray>,
        winds: List<DoubleArray>,
        cloudHours: IntRange,
        inPeriod: (LocalDateTime) -> Boolean,
    ): List<Scenario> {
        val hours = times.indices.filter { inPeriod(times[it]) }
        if (hours.isEmpty()) return emptyList()
        val cloudIndices = hours.filter { times[it].hour in cloudHours }.ifEmpty { hours }
        fun values(series: List<DoubleArray>, m: Int, at: List<Int>) =
            series.getOrNull(m)?.let { s -> at.map { s.getOrElse(it) { Double.NaN } }.filter { !it.isNaN() } }.orEmpty()
        return temps.indices.mapNotNull { m ->
            val t = values(temps, m, hours)
            val r = values(rains, m, hours)
            // Un scénario qui s'arrête en cours de période ne compte pas.
            if (t.size < hours.size * 0.9 || r.size < hours.size * 0.9) return@mapNotNull null
            val c = values(clouds, m, cloudIndices)
            val w = values(winds, m, hours)
            Scenario(
                model = models.getOrElse(m) { "" },
                temperature = t.max(),
                rain = r.sum(),
                cloud = if (c.isEmpty()) Double.NaN else c.average(),
                wind = w.maxOrNull() ?: Double.NaN,
            )
        }
    }

    private fun quantile(sorted: List<Double>, q: Double): Double {
        val pos = q * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, sorted.lastIndex)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }
}
