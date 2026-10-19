package io.github.pbailly.meteo.data.maps

import io.github.pbailly.meteo.data.radar.GeoBounds
import io.github.pbailly.meteo.data.radar.GeoTiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MapLayersTest {
    private fun t(s: String) = Instant.parse(s)

    @Test
    fun `interval times are expanded only inside the window`() {
        // Format EUMETSAT : des années d'archives, une image toutes les 10 minutes.
        val times = WmsTimes.parse(
            "2024-09-23T00:00:00.000Z/2026-10-19T18:30:00.000Z/PT10M",
            from = t("2026-10-19T17:45:00Z"), to = t("2026-10-19T19:00:00Z"),
        )
        assertEquals(t("2026-10-19T17:50:00Z"), times.first())
        assertEquals(t("2026-10-19T18:30:00Z"), times.last())
        assertEquals(5, times.size)
    }

    @Test
    fun `abbreviated lists are filled in`() {
        // Format Météo-France : « a,b,...,y,z ».
        val times = WmsTimes.parse(
            "2026-10-19T00:00:00Z,2026-10-19T01:00:00Z,...,2026-10-19T05:00:00Z,2026-10-19T06:00:00Z",
            from = t("2026-10-19T00:00:00Z"), to = t("2026-10-20T00:00:00Z"),
        )
        assertEquals((0..6).map { t("2026-10-19T0$it:00:00Z") }, times)
    }

    @Test
    fun `capabilities dimension is found`() {
        val xml = """<Layer><Name>dwd:Icon-eu_reg00625_fd_gl_T</Name>
            <Dimension name="time" default="current" units="ISO8601">2026-10-18T06:00:00.000Z/2026-10-24T12:00:00.000Z/PT1H</Dimension>
            <Dimension name="elevation" default="2.0" units="EPSG:5030">2.0,50.0</Dimension></Layer>"""
        val times = WmsTimes.fromCapabilities(xml, t("2026-10-19T20:00:00Z"), t("2026-10-19T23:00:00Z"))
        assertEquals(4, times.size)
    }

    @Test
    fun `satellite tile url keeps the bbox placeholder for the map`() {
        val url = TileLayer.SATELLITE.tileUrl(t("2026-10-19T18:30:00Z"))
        assertTrue(url.contains("BBOX={bbox-epsg-3857}"))
        assertTrue(url.contains("LAYERS=mtg_fd:rgb_geocolour"))
        assertTrue(url.endsWith("TIME=2026-10-19T18:30:00Z"))
    }

    @Test
    fun `dwd temperature geotiff is georeferenced by its transformation matrix`() {
        // Extrait réel du DWD (ICON-EU, 2 m, 19/10/2026 21 h TU) autour de Lyon : 24 × 16 cases de 0,0625°.
        val image = GeoTiff.read(javaClass.getResourceAsStream("/dwd/icon_eu_t2m_lyon.tif")!!.readBytes())
        assertEquals(24, image.width)
        assertEquals(16, image.height)
        assertEquals(4.03125, image.originX!!, 1e-9)
        assertEquals(45.96875, image.originY!!, 1e-9)
        assertEquals(0.0625, image.scaleY!!, 1e-12)
        assertEquals(9.9823f, image.values[0], 0.001f)
        assertEquals(10.3251f, image.values[5 * 24 + 10], 0.001f)
    }

    @Test
    fun `lat lon grid is rendered to a smooth mercator image`() {
        val image = GeoTiff.read(javaClass.getResourceAsStream("/dwd/icon_eu_t2m_lyon.tif")!!.readBytes())
        val bounds = GeoBounds(north = 46.5, south = 44.5, west = 3.5, east = 6.0)
        val (pixels, height) = renderLatLonGrid(image, bounds, 100) { 0xFF000000.toInt() or it.toInt() }
        assertTrue(height in 100..140)
        // Coin nord-ouest hors de la grille : transparent ; centre : une température vers 10 °C.
        assertEquals(0, pixels[0])
        val center = pixels[(height / 2) * 100 + 50] and 0xFF
        assertTrue("valeur $center", center in 5..20)
    }

    @Test
    fun `windy webcams are parsed`() {
        val body = """{"total":1,"webcams":[{"webcamId":1612345678,"title":"Lyon : Fourvière","status":"active",
            "lastUpdatedOn":"2026-10-19T18:45:12.000Z",
            "images":{"current":{"icon":"https://imgproxy.windy.com/i.jpg","preview":"https://imgproxy.windy.com/p.jpg"}},
            "location":{"city":"Lyon","latitude":45.7623,"longitude":4.8222},
            "urls":{"detail":"https://www.windy.com/webcams/1612345678"}}]}"""
        val cams = WindyWebcams.parse(body)
        assertEquals(1, cams.size)
        assertEquals("Lyon : Fourvière", cams[0].title)
        assertEquals("https://imgproxy.windy.com/p.jpg", cams[0].imageUrl)
        assertEquals(45.7623, cams[0].latitude, 1e-9)
    }
}
