package io.github.pbailly.meteo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FormatTest {

    @Test
    fun `compass uses french cardinal points`() {
        assertEquals("N", Fmt.compass(0.0))
        assertEquals("N", Fmt.compass(355.0))
        assertEquals("SO", Fmt.compass(225.0))
        assertEquals("O", Fmt.compass(270.0))
        assertEquals("ONO", Fmt.compass(292.5))
        assertEquals("–", Fmt.compass(Double.NaN))
    }

    @Test
    fun `values use a decimal comma and units`() {
        assertEquals("0,4 mm", Fmt.mm(0.4))
        assertEquals("12 mm", Fmt.mm(12.4))
        assertEquals("-3°", Fmt.temp(-3.4))
        assertEquals("5 h", Fmt.sunshine(5 * 3600.0))
        assertEquals("5,5 h", Fmt.sunshine(5.5 * 3600.0))
    }

    @Test
    fun `coordinates of a nameless point`() {
        assertEquals("47,039° N, 6,611° E", Fmt.coordinates(47.0386, 6.6114))
        assertEquals("33,868° S, 1,554° O", Fmt.coordinates(-33.8679, -1.5536))
    }

    @Test
    fun `relative day names`() {
        val today = LocalDate.of(2026, 10, 19)
        assertEquals("Aujourd'hui", Fmt.relativeDay(today, today))
        assertEquals("Demain", Fmt.relativeDay(today.plusDays(1), today))
        assertEquals("Mer. 21", Fmt.relativeDay(today.plusDays(2), today))
    }

    @Test
    fun `nice ticks are round numbers covering the range`() {
        val ticks = niceTicks(3.2, 17.8, 4)
        assertEquals(0.0, ticks.first(), 0.0)
        assertEquals(20.0, ticks.last(), 0.0)
        assertTrue(ticks.zipWithNext().all { (a, b) -> b - a == 5.0 })
    }

    @Test
    fun `cumulative precipitation stops where a model ends`() {
        val sums = cumulative(doubleArrayOf(0.5, 1.0, Double.NaN))
        assertEquals(1.5, sums[1], 1e-9)
        assertTrue(sums[2].isNaN())
    }

    @Test
    fun `model mean needs at least two models`() {
        val mean = meanOf(listOf(doubleArrayOf(10.0, 4.0), doubleArrayOf(12.0, Double.NaN)), 2)
        assertEquals(11.0, mean[0], 1e-9)
        assertTrue(mean[1].isNaN())
    }
}
