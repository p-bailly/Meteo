package io.github.pbailly.meteo.data.radar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class FrameStepTest {
    private val t0 = Instant.parse("2026-10-23T10:00:00Z")
    private fun at(vararg minutes: Int) = minutes.map { t0.plusSeconds(it * 60L) }

    @Test
    fun `every 5 minutes keeps all the radar images`() {
        val times = at(0, 5, 10, 15, 20, 25)
        assertEquals(times.indices.toList(), FrameStep.keep(times, 5))
    }

    @Test
    fun `every 15 minutes counts back from the latest image and forward into the forecast`() {
        // Observations de 10:00 à 10:25 (la dernière à l'indice 5), puis prévision calculée toutes les 5 min.
        val times = at(0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55)
        val kept = FrameStep.keep(times, 15, anchor = 5).map { times[it] }
        assertEquals(at(10, 25, 40, 55), kept)
    }

    @Test
    fun `a series already coarser than the step is kept as is`() {
        // Prévision Météo-France toutes les 15 min, satellite toutes les 10 min.
        // Observations jusqu'à 10:10, puis Météo-France à +15, +30, +45 min : au pas de 10 min, toutes les prévisions.
        val piaf = at(0, 5, 10, 25, 40, 55)
        assertEquals(listOf(0, 2, 3, 4, 5), FrameStep.keep(piaf, 10, anchor = 2))
        val satellite = at(0, 10, 20, 30)
        assertEquals(listOf(0, 1, 2, 3), FrameStep.keep(satellite, 10))
        assertEquals(listOf(1, 3), FrameStep.keep(satellite, 15))
    }

    @Test
    fun `images a few seconds early still count as on time`() {
        val times = listOf(t0, t0.plusSeconds(10 * 60L - 12), t0.plusSeconds(20 * 60L))
        assertEquals(listOf(0, 1, 2), FrameStep.keep(times, 10))
    }
}
