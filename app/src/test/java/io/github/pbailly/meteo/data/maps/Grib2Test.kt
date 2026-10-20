package io.github.pbailly.meteo.data.maps

import io.github.pbailly.meteo.data.radar.GeoBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class Grib2Test {

    @Test
    fun `ccsds compressed icon-eu temperature`() {
        // 24 premières lignes (29,5° N → 30,94° N) d'un vrai champ T_2M d'ICON-EU ; valeurs de référence décodées
        // avec libaec (imagecodecs).
        val bytes = javaClass.getResourceAsStream("/grib/icon_eu_t2m_24rows.grib2")!!.readBytes()
        val image = Grib2.read(bytes, GeoBounds(north = 30.9375, south = 29.5, west = 0.0, east = 16.0)) { it - 273.15 }
        assertEquals(259, image.width)
        assertEquals(24, image.height)
        assertEquals(-0.09375, image.originX!!, 1e-9)
        assertEquals(30.96875, image.originY!!, 1e-9)
        assertEquals(0.0625, image.scaleX!!, 1e-9)
        // Ligne 0 de l'image au nord : la ligne 10 du fichier (en partant du sud) est la ligne 13 de l'image.
        assertEquals(19.1105, image.values[13 * 259 + 1].toDouble(), 1e-3)
        assertEquals(22.7258, image.values[0 * 259 + 125].toDouble(), 1e-3)
        var sum = 0.0
        for (y in 0 until 24) for (x in 1 until 259) sum += image.values[y * 259 + x]
        assertEquals(132540.954, sum, 0.1)
    }

    @Test
    fun `latest published icon-eu run for the hour`() {
        val now = Instant.parse("2026-10-19T23:40:00Z")
        val urls = DwdTemperature.candidateUrls(Instant.parse("2026-10-20T00:00:00Z"), now)
        // 23 h 40 − 2 h 50 = 20 h 50 → réseau de 18 h, puis 15 h et 12 h.
        assertEquals(
            "https://opendata.dwd.de/weather/nwp/icon-eu/grib/18/t_2m/icon-eu_europe_regular-lat-lon_single-level_2026101918_006_T_2M.grib2.bz2",
            urls.first(),
        )
        assertEquals(3, urls.size)
        assertTrue(urls.last().contains("2026101912_012_T_2M"))
    }
}
