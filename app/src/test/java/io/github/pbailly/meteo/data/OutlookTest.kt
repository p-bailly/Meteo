package io.github.pbailly.meteo.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class OutlookTest {

    private val day = LocalDate.of(2026, 10, 26)

    @Test
    fun `one stormy hour at night does not make a stormy day`() {
        val f = forecast(codes = { h -> if (h == 3) 96 else if (h in 10..16) 1 else 2 }, rain = { h -> if (h == 3) 1.5 else 0.0 })
        // 1,5 mm en une heure : journée sèche dans l'ensemble, ciel peu nuageux le jour.
        assertEquals(1, Outlook.dayCode(f, day))
    }

    @Test
    fun `an afternoon of storms is a stormy day`() {
        val f = forecast(codes = { h -> if (h in 14..17) 95 else 2 }, rain = { h -> if (h in 14..17) 3.0 else 0.0 })
        assertEquals(95, Outlook.dayCode(f, day))
    }

    @Test
    fun `rain intensity follows the total of the day`() {
        val light = forecast(codes = { h -> if (h in 8..11) 61 else 3 }, rain = { h -> if (h in 8..11) 0.4 else 0.0 })
        assertEquals(61, Outlook.dayCode(light, day))
        val heavy = forecast(codes = { h -> if (h in 6..17) 63 else 3 }, rain = { h -> if (h in 6..17) 1.5 else 0.0 })
        assertEquals(65, Outlook.dayCode(heavy, day))
        val showers = forecast(codes = { h -> if (h in 12..15) 81 else 2 }, rain = { h -> if (h in 12..15) 1.2 else 0.0 })
        assertEquals(81, Outlook.dayCode(showers, day))
    }

    @Test
    fun `snow when most wet hours are snowy`() {
        val f = forecast(codes = { h -> if (h in 0..11) 73 else if (h in 12..14) 61 else 3 }, rain = { h -> if (h in 0..14) 0.6 else 0.0 })
        assertEquals(73, Outlook.dayCode(f, day))
    }

    @Test
    fun `hail codes are drawn and named as storms`() {
        // 96 seul sur trois heures : orage, pas de grêle.
        val f = forecast(codes = { h -> if (h == 13) 96 else 3 }, rain = { h -> if (h == 13) 2.0 else 0.0 })
        assertEquals(95, Outlook.representativeCode(f, listOf(12, 13, 14)))
    }

    @Test
    fun `fog only when it lasts`() {
        val foggy = forecast(codes = { h -> if (h in 0..10) 45 else 2 }, rain = { 0.0 })
        assertEquals(45, Outlook.dayCode(foggy, day))
        val brief = forecast(codes = { h -> if (h in 6..8) 45 else 0 }, rain = { 0.0 })
        assertEquals(0, Outlook.dayCode(brief, day))
    }

    @Test
    fun `snow is counted at 2000 m only with mountains around`() {
        // Annecy, montagnes à 2 351 m : neige à 2 000 m.
        assertEquals(2000.0, Outlook.snowReference(448.0, 2351.0), 0.0)
        // Lyon, collines à 625 m : neige sur le lieu.
        assertEquals(170.0, Outlook.snowReference(173.0, 625.0), 0.0)
        // Lieu plus haut que 2 000 m : sa propre altitude.
        assertEquals(3800.0, Outlook.snowReference(3842.0, 4808.0), 0.0)
        // Relief inconnu : 2 000 m.
        assertEquals(2000.0, Outlook.snowReference(173.0), 0.0)
    }

    private fun forecast(codes: (Int) -> Int, rain: (Int) -> Double): Forecast {
        val n = 24
        val start = LocalDateTime.of(2026, 10, 26, 0, 0)
        fun filled(v: Double) = DoubleArray(n) { v }
        val hourly = HourlySeries(
            time = List(n) { start.plusHours(it.toLong()) },
            temperature = filled(10.0), apparentTemperature = filled(9.0), humidity = filled(80.0),
            precipitation = DoubleArray(n) { rain(it) }, snowfall = filled(0.0), precipitationProbability = filled(Double.NaN),
            weatherCode = IntArray(n) { codes(it) }, cloudCoverLow = filled(0.0), cloudCoverMid = filled(0.0),
            cloudCoverHigh = filled(0.0), windSpeed = filled(5.0), windDirection = filled(180.0), windGusts = filled(10.0),
            pressure = filled(1015.0), isDay = BooleanArray(n) { it in 8..18 }, uvIndex = filled(1.0), freezingLevel = filled(2500.0),
        )
        val daily = DailySeries(
            date = listOf(day), weatherCode = IntArray(1) { 96 }, temperatureMax = DoubleArray(1), temperatureMin = DoubleArray(1),
            precipitationSum = DoubleArray(1), precipitationProbabilityMax = DoubleArray(1), windSpeedMax = DoubleArray(1),
            windGustsMax = DoubleArray(1), windDirectionDominant = DoubleArray(1), sunshineDuration = DoubleArray(1),
            uvIndexMax = DoubleArray(1), sunrise = listOf(null), sunset = listOf(null),
        )
        return Forecast(45.9, 6.1, 448.0, "Europe/Paris", 7200, null, hourly, daily)
    }
}
