package io.github.pbailly.meteo.data.radar

import org.junit.Assert.assertEquals
import org.junit.Test

/** Fichiers fabriqués par src/test/resources/tiff/make_fixtures.py. */
class GeoTiffTest {
    private fun load(name: String) = GeoTiff.read(javaClass.getResourceAsStream("/tiff/$name")!!.readBytes())

    private fun check(name: String, noData: Double) {
        val image = load(name)
        assertEquals(40, image.width)
        assertEquals(30, image.height)
        assertEquals(102f, image.values[1 * 40 + 2], 0f)
        assertEquals(2939f, image.values[29 * 40 + 39], 0f)
        assertEquals(noData, image.values[0].toDouble(), 0.0)
        assertEquals(noData, image.noData!!, 0.0)
        assertEquals(4.5, image.originX!!, 1e-9)
        assertEquals(46.0, image.originY!!, 1e-9)
        assertEquals(0.01, image.scaleX!!, 1e-12)
        assertEquals(0.01, image.scaleY!!, 1e-12)
    }

    @Test fun `float32 deflate with floating point predictor in tiles`() = check("float32_deflate_pred3_tiles_le.tif", -999.0)

    @Test fun `uncompressed big endian float32 strips`() = check("float32_none_strips_be.tif", -999.0)

    @Test fun `uint16 lzw with horizontal predictor`() = check("uint16_lzw_pred2_strips_le.tif", 65535.0)

    @Test fun `float64 packbits`() = check("float64_packbits_strips_le.tif", -999.0)
}
