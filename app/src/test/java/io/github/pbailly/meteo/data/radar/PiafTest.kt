package io.github.pbailly.meteo.data.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.roundToInt

class PiafTest {

    @Test
    fun `picks the latest precipitation run from the capabilities`() {
        val caps = """
            <wcs:Capabilities xmlns:wcs="http://www.opengis.net/wcs/2.0">
              <wcs:Contents>
                <wcs:CoverageSummary><wcs:CoverageId>TOTAL_PRECIPITATION__GROUND_OR_WATER_SURFACE___2026-10-19T17.00.00Z_PT5M</wcs:CoverageId></wcs:CoverageSummary>
                <wcs:CoverageSummary><wcs:CoverageId>TOTAL_PRECIPITATION__GROUND_OR_WATER_SURFACE___2026-10-19T17.10.00Z_PT5M</wcs:CoverageId></wcs:CoverageSummary>
                <wcs:CoverageSummary><wcs:CoverageId>TOTAL_PRECIPITATION__GROUND_OR_WATER_SURFACE___2026-10-19T17.05.00Z_PT5M</wcs:CoverageId></wcs:CoverageSummary>
              </wcs:Contents>
            </wcs:Capabilities>
        """.trimIndent()
        val run = MeteoFrancePiaf.latestRun(caps)!!
        assertEquals(Instant.parse("2026-10-19T17:10:00Z"), run.runTime)
        assertEquals("TOTAL_PRECIPITATION__GROUND_OR_WATER_SURFACE___2026-10-19T17.10.00Z_PT5M", run.coverageId)
        assertNull(MeteoFrancePiaf.latestRun("<Capabilities/>"))
    }

    @Test
    fun `forecast times every 15 minutes up to 3 hours after the run`() {
        val run = Instant.parse("2026-10-19T17:10:00Z")
        val times = MeteoFrancePiaf.validTimes(run, start = Instant.parse("2026-10-19T17:15:00Z"))
        assertEquals(Instant.parse("2026-10-19T17:30:00Z"), times.first())
        assertEquals(Instant.parse("2026-10-19T20:00:00Z"), times.last())
        assertEquals(11, times.size)
    }

    @Test
    fun `area around the place stays inside France`() {
        val lyon = MeteoFrancePiaf.Area.around(45.76, 4.84)
        assertEquals(44.0, lyon.south, 1e-9) // centre arrondi au demi-degré : 46,0° N ; 5,0° E
        assertEquals(48.0, lyon.north, 1e-9)
        assertEquals(2.0, lyon.west, 1e-9)
        assertEquals(8.0, lyon.east, 1e-9)
        val brest = MeteoFrancePiaf.Area.around(48.39, -4.49)
        assertEquals(-5.5, brest.west, 1e-9)
        assertEquals(0.5, brest.east, 1e-9)
    }

    @Test
    fun `five minute accumulation becomes an hourly rate on a lat lon grid`() {
        val image = GeoTiff.read(javaClass.getResourceAsStream("/tiff/float32_deflate_pred3_tiles_le.tif")!!.readBytes())
        val area = MeteoFrancePiaf.Area(45.7, 46.0, 4.5, 4.9)
        val grid = MeteoFrancePiaf.toGrid(image, "TOTAL_PRECIPITATION__GROUND_OR_WATER_SURFACE___2026-10-19T17.10.00Z", area)
        assertEquals(102f * 12, grid.mmPerHour[1 * 40 + 2], 0.01f)
        assertTrue(grid.mmPerHour[0].isNaN()) // nodata
        val out = DoubleArray(2)
        grid.projection.toGrid(45.985, 4.525, out) // centre du pixel (2, 1)
        assertEquals(2, out[0].roundToInt())
        assertEquals(1, out[1].roundToInt())
    }
}
