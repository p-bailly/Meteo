package io.github.pbailly.meteo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ClimateTest {

    @Test
    fun `a week shares the same window`() {
        assertEquals(Climate.anchorFor(LocalDate.of(2026, 10, 19)), Climate.anchorFor(LocalDate.of(2026, 10, 21)))
        assertTrue(Climate.windowUrl(Place("Annecy", 45.9, 6.13), 1991, LocalDate.of(2001, 10, 22))
            .contains("start_date=1991-10-15&end_date=1991-10-28"))
        // Fenêtre à cheval sur deux années : décembre de l'année d'avant.
        assertTrue(Climate.windowUrl(Place("Annecy", 45.9, 6.13), 1995, LocalDate.of(2001, 1, 4)).contains("start_date=1994-12-28"))
    }

    @Test
    fun `normals follow the autumn cooling`() {
        // Deux années de deux semaines : 15 °C / 6 °C le jour central, −0,2 °C par jour.
        val anchor = LocalDate.of(2001, 10, 20)
        fun window(year: Int): String {
            val center = anchor.withYear(year)
            val days = (-7..6).map { center.plusDays(it.toLong()) }
            return """{"daily":{"time":[${days.joinToString(",") { "\"$it\"" }}],""" +
                """"temperature_2m_max":[${(-7..6).joinToString(",") { "${15 - 0.2 * it}" }}],""" +
                """"temperature_2m_min":[${(-7..6).joinToString(",") { "${6 - 0.2 * it}" }}]}}"""
        }
        val days = listOf(1991, 1992, 1993).flatMap { Climate.parseWindow(window(it), anchor.withYear(it)) }
        val normals = Climate.normals(anchor, days)
        val (hi, lo) = normals.on(LocalDate.of(2026, 10, 23))!!
        assertEquals(14.4, hi, 1e-9)
        assertEquals(5.4, lo, 1e-9)
        assertNull(normals.on(LocalDate.of(2026, 12, 1)))
        assertEquals(15.0, Climate.decode(Climate.encode(normals)).max, 1e-9)
    }
}
