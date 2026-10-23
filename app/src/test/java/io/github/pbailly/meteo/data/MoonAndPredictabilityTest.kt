package io.github.pbailly.meteo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

class MoonAndPredictabilityTest {

    @Test
    fun `moon rise and set in Valence match meteoblue within a few minutes`() {
        // Meteoblue, Valence (44,93° N ; 4,91° E), mardi 20 octobre 2026 : lever 16:16, coucher 01:20 (heure d'été).
        val moon = Moon.times(LocalDate.of(2026, 10, 20), 44.93, 4.91, utcOffsetSeconds = 7200)
        assertNotNull(moon.rise)
        assertNotNull(moon.set)
        val rise = Duration.between(LocalDateTime.of(2026, 10, 20, 16, 16), moon.rise).toMinutes()
        val set = Duration.between(LocalDateTime.of(2026, 10, 20, 1, 20), moon.set).toMinutes()
        assertTrue("lever décalé de $rise min", kotlin.math.abs(rise) <= 10)
        assertTrue("coucher décalé de $set min", kotlin.math.abs(set) <= 10)
    }

    private fun forecast(vararg models: Pair<Double, Double>): MultiModelForecast {
        val start = LocalDateTime.of(2026, 10, 20, 0, 0)
        val times = (0 until 24).map { start.plusHours(it.toLong()) }
        val series = WeatherModel.entries.take(models.size).mapIndexed { m, model ->
            val (tmax, rainPerHour) = models[m]
            model to ModelSeries(
                temperature = DoubleArray(24) { h -> tmax - 8 + 8 * kotlin.math.sin(Math.PI * h / 24) },
                precipitation = DoubleArray(24) { rainPerHour },
                windSpeed = DoubleArray(24) { 10.0 },
            )
        }.toMap()
        return MultiModelForecast(times, 7200, series)
    }

    @Test
    fun `models in agreement give a high confidence`() {
        val mm = forecast(15.0 to 0.0, 15.3 to 0.0, 14.8 to 0.0, 15.1 to 0.0)
        assertEquals(5, Predictability.daily(null, mm, LocalDate.of(2026, 10, 20)))
    }

    @Test
    fun `models that disagree on rain give a low confidence`() {
        // Médiane : 13 °C et sec. Seul le modèle à 15 °C et sec donne ce temps : 1 sur 4, 25 %.
        val mm = forecast(15.0 to 0.0, 11.0 to 0.5, 17.0 to 0.0, 13.0 to 0.4)
        assertEquals(2, Predictability.daily(null, mm, LocalDate.of(2026, 10, 20)))
    }

    /** Scénarios secs d'une journée : maxima [tmax] (un modèle par liste), nuages et vent constants. */
    private fun ensemble(vararg models: List<Double>, cloud: Double = 20.0): EnsembleForecast {
        val start = LocalDateTime.of(2026, 10, 20, 0, 0)
        val times = (0 until 24).map { start.plusHours(it.toLong()) }
        val tmax = models.flatMap { it }
        return EnsembleForecast(
            time = times,
            temperature = tmax.map { t -> DoubleArray(24) { h -> t - 8 + 8 * kotlin.math.sin(Math.PI * h / 24) } },
            precipitation = tmax.map { DoubleArray(24) },
            model = models.indices.flatMap { m -> List(models[m].size) { "model$m" } },
            cloudCover = tmax.map { DoubleArray(24) { cloud } },
            windSpeed = tmax.map { DoubleArray(24) { 10.0 } },
        )
    }

    @Test
    fun `the ensemble is preferred and its spread lowers the confidence`() {
        val mm = forecast(15.0 to 0.0, 15.3 to 0.0, 14.8 to 0.0, 15.1 to 0.0)
        // Deux ensembles secs aux maxima étalés de 11 à 19 °C : 56 % des scénarios à 2 °C de la médiane.
        val spread = (0..50).map { 11 + it * 0.16 }
        val ensemble = ensemble(spread, spread.take(40))
        assertEquals(3, Predictability.daily(ensemble, mm, LocalDate.of(2026, 10, 20)))
        assertEquals(2, Predictability.level(0.3))
    }

    @Test
    fun `each model weighs the same whatever its number of scenarios`() {
        // 51 scénarios d'accord à 15 °C, et un seul modèle de 3 scénarios à 20 °C : moitié-moitié, pas 94 %.
        val ensemble = ensemble(List(51) { 15.0 }, List(3) { 20.0 })
        assertEquals(3, Predictability.daily(ensemble, null, LocalDate.of(2026, 10, 20)))
    }

    @Test
    fun `scenarios that agree on temperature but not on the sky lower the confidence`() {
        val agree = ensemble(List(51) { 15.0 }, List(31) { 15.2 })
        assertEquals(5, Predictability.daily(agree, null, LocalDate.of(2026, 10, 20)))
        val start = LocalDateTime.of(2026, 10, 20, 0, 0)
        val times = (0 until 24).map { start.plusHours(it.toLong()) }
        // Le même ensemble, mais un scénario sur deux couvert (90 %) quand l'autre est ensoleillé (10 %).
        val sky = EnsembleForecast(
            agree.time, agree.temperature, agree.precipitation, agree.model,
            cloudCover = agree.temperature.indices.map { DoubleArray(24) { _ -> if (it % 2 == 0) 90.0 else 10.0 } },
            windSpeed = agree.windSpeed,
        )
        assertEquals(3, Predictability.daily(sky, null, LocalDate.of(2026, 10, 20)))
        assertEquals(3, Predictability.hours(sky, null, times.subList(12, 15)))
    }

    @Test
    fun `a single model ensemble falls back to the models`() {
        val mm = forecast(15.0 to 0.0, 15.3 to 0.0, 14.8 to 0.0, 15.1 to 0.0)
        val ensemble = ensemble((0..50).map { 11 + it * 0.16 })
        assertEquals(5, Predictability.daily(ensemble, mm, LocalDate.of(2026, 10, 20)))
    }

    @Test
    fun `rain range spans the middle of the models`() {
        // Cumuls journaliers : 0, 2,4, 4,8 et 24 mm.
        val mm = forecast(15.0 to 0.0, 15.0 to 0.1, 15.0 to 0.2, 15.0 to 1.0)
        val range = Predictability.rainRange(mm, LocalDate.of(2026, 10, 20))!!
        assertEquals(1.8, range.low, 0.01)
        assertEquals(9.6, range.high, 0.01)
    }

    @Test
    fun `too few models means no predictability`() {
        val mm = forecast(15.0 to 0.0, 15.0 to 0.0)
        assertEquals(null, Predictability.daily(null, mm, LocalDate.of(2026, 10, 20)))
    }
}
