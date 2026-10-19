package io.github.pbailly.meteo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class AltitudeTest {

    @Test
    fun `temperature is interpolated between the ground and the pressure levels`() {
        // Sol à 1 035 m (14 °C), 850 hPa à 1 500 m (10 °C), 700 hPa à 3 000 m (−1 °C).
        val f = forecast(hours = 1, ground = 1035.0, t2m = 14.0, levels = listOf(level(850, 1500.0, 10.0), level(700, 3000.0, -1.0)))
        assertEquals(14.0, Altitude.temperature(f, 0, 1035.0), 1e-9)
        assertEquals(10.0, Altitude.temperature(f, 0, 1500.0), 1e-9)
        assertEquals(4.5, Altitude.temperature(f, 0, 2250.0), 1e-9)
        // Au-dessus du dernier niveau : −0,65 °C / 100 m.
        assertEquals(-1.0 - 6.5, Altitude.temperature(f, 0, 4000.0), 1e-9)
        // Sous le sol (fond de vallée voisin) : plus chaud du même gradient.
        assertEquals(14.0 + 0.65, Altitude.temperature(f, 0, 935.0), 1e-9)
    }

    @Test
    fun `levels below the ground are ignored`() {
        val f = forecast(hours = 1, ground = 1800.0, t2m = 2.0, levels = listOf(level(850, 1500.0, 10.0), level(700, 3000.0, -4.0)))
        assertEquals(-1.0, Altitude.temperature(f, 0, 2400.0), 1e-9)
    }

    @Test
    fun `without pressure levels the freezing level gives the lapse rate`() {
        val f = forecast(hours = 1, ground = 448.0, t2m = 14.0, freezing = 2848.0)
        assertEquals(7.0, Altitude.temperature(f, 0, 1648.0), 1e-9)
        assertEquals(-0.58, Altitude.lapseRate(f, 0, 448.0, 2848.0), 0.01)
    }

    @Test
    fun `freezing level is computed when the model does not give it`() {
        val f = forecast(hours = 1, ground = 200.0, t2m = 10.0, levels = listOf(level(850, 1500.0, 4.0), level(700, 3000.0, -6.0)))
        // 4 °C à 1 500 m, −6 °C à 3 000 m : 0 °C vers 2 100 m.
        assertEquals(2100.0, Altitude.freezingLevel(f, 0), 15.0)
        assertEquals(1800.0, Altitude.snowLine(f, 0), 15.0)
    }

    @Test
    fun `wind takes the surface below the first level and the levels above`() {
        val f = forecast(
            hours = 1, ground = 448.0, t2m = 14.0, wind = 12.0, windDir = 225.0,
            levels = listOf(level(850, 1500.0, 8.0, wind = 30.0, dir = 270.0), level(700, 3000.0, -1.0, wind = 60.0, dir = 315.0)),
        )
        assertEquals(12.0, Altitude.wind(f, 0, 448.0).speed, 1e-9)
        assertEquals(45.0, Altitude.wind(f, 0, 2250.0).speed, 1e-9)
        assertEquals(315.0, Altitude.wind(f, 0, 2900.0).direction, 1e-9)
        assertEquals(60.0, Altitude.wind(f, 0, 3842.0).speed, 1e-9)
    }

    @Test
    fun `snow falls above the rain-snow limit only`() {
        // 2 mm/h pendant 3 heures, iso 0 à 1 700 m : neige au-dessus de 1 400 m.
        val f = forecast(hours = 3, ground = 1035.0, t2m = 3.0, freezing = 1700.0, precipitation = 2.0)
        assertEquals(6.0, Altitude.snow(f, 0..2, 2000.0), 1e-9)
        assertEquals(0.0, Altitude.snow(f, 0..2, 1035.0), 1e-9)
        assertEquals(6.0, Altitude.rain(f, 0..2, 1035.0), 1e-9)
    }

    @Test
    fun `a wet day reports the rain-snow limit during the precipitation`() {
        val f = forecast(hours = 24, ground = 448.0, t2m = 8.0, freezing = 2000.0, precipitation = 0.0).withHourly {
            // Il pleut de 12 h à 15 h, avec l'iso 0 à 1 600 m.
            for (i in 12..15) {
                precipitation[i] = 1.0
                freezingLevel[i] = 1600.0
            }
        }
        val day = Altitude.days(f).single()
        assertEquals(LocalDate.of(2026, 10, 26), day.date)
        assertEquals(1600.0, day.freezingLevel, 1e-9)
        assertEquals(1300.0, day.snowLine!!, 1e-9)
        assertEquals(4.0, day.precipitation, 1e-9)
    }

    @Test
    fun `a dry day has no rain-snow limit`() {
        val f = forecast(hours = 24, ground = 448.0, t2m = 15.0, freezing = 3100.0)
        val day = Altitude.days(f).single()
        assertNull(day.snowLine)
        assertEquals(3100.0, day.freezingLevel, 1e-9)
    }

    @Test
    fun `wind chill only applies when it is cold and windy`() {
        assertEquals(-24.0, Altitude.windChill(-11.0, 60.0), 1.0)
        assertEquals(12.0, Altitude.windChill(12.0, 60.0), 0.0)
        assertEquals(-5.0, Altitude.windChill(-5.0, 3.0), 0.0)
    }

    @Test
    fun `black ice after evening rain followed by a cold night`() {
        // Pluie de 18 h à 21 h (iso 0 à 2 500 m), puis la nuit l'iso 0 descend à 700 m, lieu à 448 m.
        val f = forecast(hours = 36, ground = 448.0, t2m = 6.0, freezing = 2500.0).withHourly {
            for (i in 18..21) precipitation[i] = 1.0
            for (i in 26..34) {
                freezingLevel[i] = 700.0
                temperature[i] = 1.6
            }
        }
        val risks = Altitude.iceRisks(f, 0, 36)
        assertEquals(1, risks.size)
        val risk = risks.single()
        assertFalse(risk.atPlace)
        assertEquals(LocalDateTime.of(2026, 10, 27, 2, 0), risk.start)
        // 0,5 °C : un peu sous l'iso 0, à 50 m près.
        assertTrue(risk.altitude in 500.0..700.0)
    }

    @Test
    fun `no black ice above the local relief`() {
        // Même nuit qu'au-dessus, mais en plaine : rien ne monte au-delà de 500 m autour du lieu.
        val f = forecast(hours = 36, ground = 448.0, t2m = 6.0, freezing = 2500.0).withHourly {
            for (i in 18..21) precipitation[i] = 1.0
            for (i in 26..34) {
                freezingLevel[i] = 700.0
                temperature[i] = 1.6
            }
        }
        assertTrue(Altitude.iceRisks(f, 0, 36, ceiling = 500.0).isEmpty())
        assertEquals(1, Altitude.iceRisks(f, 0, 36, ceiling = 900.0).size)
    }

    @Test
    fun `no black ice on a dry cold night`() {
        val f = forecast(hours = 24, ground = 448.0, t2m = -2.0, freezing = 300.0)
        assertTrue(Altitude.iceRisks(f, 0, 24).isEmpty())
    }

    @Test
    fun `freezing rain is always a black ice risk at the place`() {
        val f = forecast(hours = 3, ground = 448.0, t2m = 1.0, freezing = 1200.0).withHourly { weatherCode[1] = 67 }
        val risk = Altitude.iceRisks(f, 0, 3).single()
        assertTrue(risk.atPlace)
        assertTrue(risk.freezingRain)
    }

    @Test
    fun `fair weather window is the longest dry calm sunny spell of the day`() {
        val f = forecast(hours = 24, ground = 448.0, t2m = 15.0, freezing = 3000.0, wind = 10.0, code = 1).withHourly {
            precipitation[13] = 0.5
            precipitation[17] = 0.5
        }
        assertNull(Altitude.fairWindow(f, 0..23))
        val dry = forecast(hours = 24, ground = 448.0, t2m = 15.0, freezing = 3000.0, wind = 10.0, code = 0).withHourly {
            windSpeed[8] = 25.0
            windSpeed[17] = 25.0
        }
        assertEquals(9..16, Altitude.fairWindow(dry, 0..23))
        assertNotNull(Altitude.fairWindow(dry, 0..23, from = 10))
        assertNull(Altitude.fairWindow(dry, 0..23, from = 12))
    }

    private class Level(val hPa: Int, val height: Double, val t: Double, val wind: Double, val dir: Double)

    private fun level(hPa: Int, height: Double, t: Double, wind: Double = Double.NaN, dir: Double = Double.NaN) = Level(hPa, height, t, wind, dir)

    private fun forecast(
        hours: Int,
        ground: Double,
        t2m: Double,
        freezing: Double = Double.NaN,
        precipitation: Double = 0.0,
        wind: Double = 5.0,
        windDir: Double = 180.0,
        code: Int = 3,
        levels: List<Level> = emptyList(),
    ): Forecast {
        val start = LocalDateTime.of(2026, 10, 26, 0, 0)
        fun filled(v: Double) = DoubleArray(hours) { v }
        val hourly = HourlySeries(
            time = List(hours) { start.plusHours(it.toLong()) },
            temperature = filled(t2m),
            apparentTemperature = filled(t2m),
            humidity = filled(80.0),
            precipitation = filled(precipitation),
            snowfall = filled(0.0),
            precipitationProbability = filled(Double.NaN),
            weatherCode = IntArray(hours) { code },
            cloudCoverLow = filled(0.0),
            cloudCoverMid = filled(0.0),
            cloudCoverHigh = filled(0.0),
            windSpeed = filled(wind),
            windDirection = filled(windDir),
            windGusts = filled(wind * 2),
            pressure = filled(1015.0),
            isDay = BooleanArray(hours) { true },
            uvIndex = filled(1.0),
            freezingLevel = filled(freezing),
            levels = levels.map {
                PressureLevel(it.hPa, filled(it.t), filled(it.height), filled(it.wind), filled(it.dir))
            },
        )
        val daily = DailySeries(
            date = emptyList(), weatherCode = IntArray(0), temperatureMax = DoubleArray(0), temperatureMin = DoubleArray(0),
            precipitationSum = DoubleArray(0), precipitationProbabilityMax = DoubleArray(0), windSpeedMax = DoubleArray(0),
            windGustsMax = DoubleArray(0), windDirectionDominant = DoubleArray(0), sunshineDuration = DoubleArray(0),
            uvIndexMax = DoubleArray(0), sunrise = emptyList(), sunset = emptyList(),
        )
        return Forecast(45.9, 6.1, ground, "Europe/Paris", 7200, null, hourly, daily)
    }

    private fun Forecast.withHourly(block: HourlySeries.() -> Unit): Forecast = apply { hourly.block() }
}
