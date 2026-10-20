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
        // Valeurs fictives (102 mm en 5 min) : seul le rapport entre deux cases compte ici.
        assertEquals(image.values[1 * 40 + 2] / image.values[1 * 40 + 3], grid.mmPerHour[1 * 40 + 2] / grid.mmPerHour[1 * 40 + 3], 1e-4f)
        assertTrue(grid.mmPerHour[0].isNaN()) // nodata
        val out = DoubleArray(2)
        grid.projection.toGrid(45.985, 4.525, out) // centre du pixel (2, 1)
        assertEquals(2, out[0].roundToInt())
        assertEquals(1, out[1].roundToInt())
    }

    @Test
    fun `unit of the values from the coverage description`() {
        val xml = """
            <wcs:CoverageDescriptions><wcs:CoverageDescription><gmlcov:rangeType><swe:DataRecord><swe:field name="TP">
              <swe:Quantity><swe:nilValues><swe:NilValues><swe:nilValue reason="missing">9999</swe:nilValue></swe:NilValues></swe:nilValues>
              <swe:uom code="kg m-2 s-1"/></swe:Quantity></swe:field></swe:DataRecord></gmlcov:rangeType></wcs:CoverageDescription></wcs:CoverageDescriptions>
        """.trimIndent()
        val description = CoverageDescription.parse(xml)
        assertEquals("kg m-2 s-1", description.unit)
        assertEquals(listOf(9999.0), description.nilValues)
        fun factor(unit: String?, id: String) = (Conversion.of(unit, id) as Conversion.Factor).factor
        val id = "TOTAL_PRECIPITATION__GROUND_OR_WATER_SURFACE___2026-10-19T17.10.00Z_PT5M"
        assertEquals(3600f, factor("kg m-2 s-1", id), 0f)
        assertEquals(1f, factor("mm/h", id), 0f)
        assertEquals(12f, factor("kg m-2", id), 0f)
        assertEquals(1f, factor("kg.m-2", id.replace("PT5M", "PT1H")), 0f)
        assertEquals(12f, factor(null, id), 0f)
        assertEquals(3600f, factor(null, "TOTAL_PRECIPITATION_RATE__GROUND_OR_WATER_SURFACE___2026-10-19T17.10.00Z"), 0f)
        assertTrue(Conversion.of("dBZ", id) is Conversion.Reflectivity)
    }

    @Test
    fun `fill values and impossible intensities do not turn the map yellow`() {
        val area = MeteoFrancePiaf.Area(45.0, 46.0, 4.0, 5.0)
        // 100 × 100 : une zone de pluie (0,1 à 0,4 mm en 5 min), du sec, et une valeur de remplissage 9999 ailleurs.
        val values = FloatArray(10_000) { i ->
            when {
                i < 2_000 -> 0.1f + (i % 4) * 0.1f
                i < 4_000 -> 0f
                else -> 9999f
            }
        }
        val image = GeoTiffImage(100, 100, values, 4.0, 46.0, 0.01, 0.01, noData = null)
        val grid = MeteoFrancePiaf.toGrid(image, "TOTAL_PRECIPITATION__GROUND_OR_WATER_SURFACE___2026-10-19T17.10.00Z_PT5M", area)
        assertEquals(0.1f * 12, grid.mmPerHour[0], 1e-4f)
        assertEquals(0f, grid.mmPerHour[3_000], 0f)
        assertTrue(grid.mmPerHour[9_000].isNaN())
        // Valeurs déjà en mm/h annoncées comme un flux par seconde : ramenées à une échelle vraisemblable.
        val rates = FloatArray(10_000) { i -> if (i < 3_000) 0.5f + (i % 10) * 0.3f else 0f }
        assertEquals(1f, MeteoFrancePiaf.plausibleFactor(rates, 3600f), 0f)
        assertEquals(3600f, MeteoFrancePiaf.plausibleFactor(FloatArray(1_000) { 0.0004f }, 3600f), 0f)
    }
}
