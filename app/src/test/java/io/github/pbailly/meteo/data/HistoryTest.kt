package io.github.pbailly.meteo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class HistoryTest {

    private val start = LocalDate.of(1940, 1, 1)
    private val end = LocalDate.of(2026, 10, 25)

    /** Un historique régulier : maxi 12 °C et mini 2 °C, 2 mm un jour sur deux, 5 h de soleil. */
    private fun history(
        to: LocalDate = end,
        max: (LocalDate) -> Double = { 12.0 },
        min: (LocalDate) -> Double = { 2.0 },
        rain: (LocalDate) -> Double = { if (it.toEpochDay() % 2 == 0L) 2.0 else 0.0 },
    ): DailyHistory {
        val n = (to.toEpochDay() - start.toEpochDay() + 1).toInt()
        val dates = List(n) { start.plusDays(it.toLong()) }
        return DailyHistory(
            start,
            temperatureMax = DoubleArray(n) { max(dates[it]) },
            temperatureMin = DoubleArray(n) { min(dates[it]) },
            precipitation = DoubleArray(n) { rain(dates[it]) },
            snowfall = DoubleArray(n),
            windGusts = DoubleArray(n) { 30.0 },
            sunshine = DoubleArray(n) { 5 * 3600.0 },
            weatherCode = IntArray(n) { 3 },
        )
    }

    @Test
    fun `normal averages 1991 to 2020 around the day`() {
        // Un degré de plus chaque année : la moyenne de 1991–2020 tombe entre 2005 et 2006.
        val h = history(max = { it.year - 1990.0 })
        val normal = History.normal(h, LocalDate.of(2026, 10, 26))
        assertNotNull(normal)
        assertEquals(15.5, normal!!.max, 1e-9)
        assertEquals(2.0, normal.min, 1e-9)
    }

    @Test
    fun `no normal without the normal period`() {
        val short = DailyHistory(
            LocalDate.of(2024, 1, 1), DoubleArray(10), DoubleArray(10), DoubleArray(10), DoubleArray(10),
            DoubleArray(10), DoubleArray(10), IntArray(10),
        )
        assertNull(History.normal(short, LocalDate.of(2024, 1, 5)))
    }

    @Test
    fun `day records come from earlier years only`() {
        val hot = LocalDate.of(1995, 10, 26)
        val cold = LocalDate.of(1974, 10, 26)
        val h = history(
            max = { if (it == hot) 22.4 else 12.0 },
            min = { if (it == cold) -7.2 else 2.0 },
        )
        val records = History.dayRecords(h, LocalDate.of(2026, 10, 26))
        assertEquals(22.4, records.hottest!!.value, 1e-9)
        assertEquals(hot, records.hottest.date)
        assertEquals(-7.2, records.coldest!!.value, 1e-9)
        assertEquals(cold, records.coldest.date)
    }

    @Test
    fun `place records by month and longest dry spell`() {
        val heat = LocalDate.of(2003, 8, 3)
        val dryFrom = LocalDate.of(2018, 7, 10)
        val h = history(
            max = { if (it == heat) 34.8 else 12.0 },
            rain = { if (!it.isBefore(dryFrom) && it.isBefore(dryFrom.plusDays(32))) 0.0 else 3.0 },
        )
        val all = History.allTimeRecords(h)
        assertEquals(34.8, all.hottest!!.value, 1e-9)
        assertEquals(heat, all.hottest.date)
        assertEquals(32.0, all.drySpell!!.value, 1e-9)
        assertEquals(dryFrom, all.drySpell.date)
        assertNull(all.snowfall)
        // En octobre, ni la canicule d'août ni la sécheresse de l'été.
        val october = History.monthRecords(h, 10)
        assertEquals(12.0, october.hottest!!.value, 1e-9)
        assertNull(october.drySpell)
        // Juillet seul : la suite de jours secs s'arrête au 31.
        assertEquals(22.0, History.monthRecords(h, 7).drySpell!!.value, 1e-9)
    }

    @Test
    fun `recent days follow the past and empty days are dropped`() {
        val past = history(to = LocalDate.of(2025, 12, 31))
        val n = 30
        val recent = DailyHistory(
            LocalDate.of(2026, 1, 1),
            DoubleArray(n) { if (it < 28) 5.0 else Double.NaN }, DoubleArray(n), DoubleArray(n), DoubleArray(n),
            DoubleArray(n), DoubleArray(n), IntArray(n) { 61 },
        )
        val merged = past.followedBy(recent)
        assertEquals(LocalDate.of(2026, 1, 28), merged.end)
        assertEquals(5.0, merged.temperatureMax[merged.index(LocalDate.of(2026, 1, 10))], 1e-9)
        assertEquals(61, merged.weatherCode[merged.index(LocalDate.of(2026, 1, 10))])
        assertEquals(12.0, merged.temperatureMax[merged.index(LocalDate.of(2025, 12, 31))], 1e-9)
        assertEquals(past.size, past.followedBy(null).size)
    }

    @Test
    fun `a period is compared with the same days of 1991 to 2020`() {
        // 2026 deux degrés au-dessus, et deux fois plus de pluie.
        val h = history(
            max = { if (it.year == 2026) 14.0 else 12.0 },
            min = { if (it.year == 2026) 4.0 else 2.0 },
            rain = { if (it.year == 2026) 4.0 else 2.0 },
        )
        val summary = History.period(h, LocalDate.of(2026, 9, 26), end)!!
        assertEquals(2.0, summary.temperatureGap, 1e-9)
        assertEquals(30 * 4.0, summary.precipitation.value, 1e-9)
        assertEquals(30 * 2.0, summary.precipitation.normal, 1e-9)
        assertEquals(150.0, summary.sunshine.value, 1e-9)
        assertEquals(0.0, summary.frostDays.value, 1e-9)
    }

    @Test
    fun `february 29 falls back to the 28th in ordinary years`() {
        assertEquals(LocalDate.of(2023, 2, 28), History.sameDay(2023, LocalDate.of(2024, 2, 29)))
        assertEquals(LocalDate.of(2020, 2, 29), History.sameDay(2020, LocalDate.of(2024, 2, 29)))
    }
}
