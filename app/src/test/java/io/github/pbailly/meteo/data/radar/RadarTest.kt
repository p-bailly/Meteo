package io.github.pbailly.meteo.data.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.roundToInt

class RadarTest {

    /** Fichier fabriqué par src/test/resources/odim/make_fixture.py (grille réduite à 434 × 434 cases de 4 km). */
    private val fixture = javaClass.getResourceAsStream("/odim/lame_d_eau_test.h5")!!.readBytes()

    @Test
    fun `projection puts the grid corners on the grid edges`() {
        val p = PolarStereographic.fromProjDef(
            "+proj=stere +lat_0=90 +lon_0=0 +lat_ts=45 +ellps=WGS84 +x_0=619652.07 +y_0=5262818.34 +datum=WGS84",
        )
        val out = DoubleArray(2)
        p.forward(38.14493289858148, -6.715173408021557, out) // coin bas-gauche
        assertEquals(0.0, out[0], 1.0)
        assertEquals(0.0, out[1], 1.0)
        p.forward(52.54813781717718, 17.564202528305234, out) // coin haut-droit
        assertEquals(1_736_000.0, out[0], 1.0)
        assertEquals(1_736_000.0, out[1], 1.0)
    }

    @Test
    fun `decodes the ODIM lame d'eau into mm per hour`() {
        val grid = OdimDecoder.decode(fixture)
        assertEquals(434, grid.width)
        assertEquals(434, grid.height)
        assertEquals(Instant.parse("2026-10-19T16:10:00Z"), grid.time)
        assertEquals(108f, grid.mmPerHour[222 * 434 + 248], 0.01f) // 9 mm en 5 min
        assertEquals(6f, grid.mmPerHour[220 * 434 + 246], 0.01f)
        assertEquals(0f, grid.mmPerHour[100 * 434 + 300], 0f) // undetect = pas de pluie
        assertTrue(grid.mmPerHour[100 * 434 + 5].isNaN()) // nodata = hors couverture
    }

    @Test
    fun `lyon falls on the right grid cell`() {
        val grid = OdimDecoder.decode(fixture)
        val out = DoubleArray(2)
        grid.projection.toGrid(45.76, 4.84, out)
        assertEquals(248, out[0].roundToInt())
        assertEquals(222, out[1].roundToInt())
    }

    @Test
    fun `downsampling keeps the strongest cell of each block`() {
        val grid = OdimDecoder.decode(fixture, factor = 2)
        assertEquals(217, grid.width)
        assertEquals(108f, grid.mmPerHour[111 * 217 + 124], 0.01f)
        val out = DoubleArray(2)
        grid.projection.toGrid(45.76, 4.84, out)
        assertEquals(124, out[0].roundToInt())
        assertEquals(111, out[1].roundToInt())
    }

    @Test
    fun `web mercator image shows the shower over lyon`() {
        val grid = OdimDecoder.decode(fixture)
        val bounds = GeoBounds(north = 54.2, south = 37.4, west = -10.0, east = 17.6)
        val lookup = MercatorLookup.forGrid(bounds, 600, grid)
        val pixels = lookup.render(grid.mmPerHour)
        // Pixel de Lyon : colonne d'après la longitude, ligne d'après la latitude en Mercator.
        val px = ((4.84 - bounds.west) / (bounds.east - bounds.west) * lookup.width).toInt()
        fun mercY(lat: Double) = Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2))
        val py = ((mercY(bounds.north) - mercY(45.76)) / (mercY(bounds.north) - mercY(bounds.south)) * lookup.height).toInt()
        val around = (-1..1).flatMap { dy -> (-1..1).map { dx -> pixels[(py + dy) * lookup.width + px + dx] } }
        assertTrue(RainClass.HAIL.argb in around)
        assertTrue(around.all { it == RainClass.HAIL.argb || it == RainClass.MODERATE.argb })
        assertEquals(0, pixels[0]) // coin nord-ouest, hors grille
    }

    @Test
    fun `rain classes follow the legend thresholds`() {
        assertEquals(0, RainClass.argbFor(0.05f))
        assertEquals(0, RainClass.argbFor(Float.NaN))
        assertEquals(RainClass.DRIZZLE.argb, RainClass.argbFor(0.3f))
        assertEquals(RainClass.MODERATE.argb, RainClass.argbFor(5f))
        assertEquals(RainClass.VERY_HEAVY.argb, RainClass.argbFor(60f))
        assertEquals(RainClass.HAIL.argb, RainClass.argbFor(150f))
        assertEquals(11.5, RainClass.dbzToMmPerHour(40.0), 0.1)
    }

    @Test
    fun `catalogue gives the latest 500 m validity time`() {
        val body = """
            {"links":[
              {"rel":"produit","href":"https://public-api.meteofrance.fr/.../produit?maille=1000","validity_time":"2026-10-19T16:15:00Z"},
              {"rel":"produit","href":"https://public-api.meteofrance.fr/.../produit?maille=500","validity_time":"2026-10-19T16:10:00Z"}
            ]}
        """.trimIndent()
        assertEquals(Instant.parse("2026-10-19T16:10:00Z"), MeteoFranceRadar.parseLatestValidityTime(body))
        assertNull(MeteoFranceRadar.parseLatestValidityTime("""{"links":[]}"""))
    }
}
