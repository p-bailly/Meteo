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
        val mm = forecast(15.0 to 0.0, 11.0 to 0.5, 17.0 to 0.0, 13.0 to 0.4)
        assertEquals(1, Predictability.daily(null, mm, LocalDate.of(2026, 10, 20)))
    }

    @Test
    fun `the ensemble is preferred and its spread lowers the confidence`() {
        val mm = forecast(15.0 to 0.0, 15.3 to 0.0, 14.8 to 0.0, 15.1 to 0.0)
        val start = LocalDateTime.of(2026, 10, 20, 0, 0)
        val times = (0 until 24).map { start.plusHours(it.toLong()) }
        // 51 scénarios secs dont les maxima s'étalent de 11 à 19 °C (écart-type 2,4 °C) : les modèles d'accord
        // donneraient 5, l'ensemble ne donne que 4.
        val temps = (0 until 51).map { m -> DoubleArray(24) { h -> 3 + m * 0.16 + 8 * kotlin.math.sin(Math.PI * h / 24) } }
        val ensemble = EnsembleForecast(times, temps, List(51) { DoubleArray(24) })
        assertEquals(4, Predictability.daily(ensemble, mm, LocalDate.of(2026, 10, 20)))
        assertEquals(2, Predictability.level(0.4))
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
