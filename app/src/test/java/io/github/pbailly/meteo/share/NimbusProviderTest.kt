package io.github.pbailly.meteo.share

import io.github.pbailly.meteo.data.OpenMeteoApi
import io.github.pbailly.meteo.data.Place
import io.github.pbailly.meteo.widget.WidgetColors
import io.github.pbailly.meteo.widget.WidgetData
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NimbusProviderTest {

    private val morteau = Place("Morteau", 47.06, 6.61, admin1 = "Bourgogne-Franche-Comté", country = "France", admin2 = "Doubs")
    private val today = LocalDate.of(2026, 10, 21)
    private val colors = WidgetColors.of(false)

    private fun data(place: Place? = morteau, body: String? = FORECAST, stale: Boolean = false) =
        WidgetData(place, body?.let { OpenMeteoApi.parseForecast(it) }, FETCHED_AT, stale, colors)

    @Test
    fun `columns in the agreed order`() {
        assertEquals(
            listOf("place", "region", "temperature", "feels_like", "sky", "today_max", "today_min", "weather_code", "is_day", "fetched_at", "stale"),
            NimbusProvider.COLUMNS,
        )
    }

    @Test
    fun `row from the widget data`() {
        val row = NimbusProvider.nowRow(data(), today)!!
        assertEquals(NimbusProvider.COLUMNS.size, row.size)
        assertArrayEquals(
            arrayOf<Any?>("Morteau", "Doubs", 9.4, 7.2, "Partiellement nuageux", 14.2, 3.1, 2, 0, FETCHED_AT, 0),
            row,
        )
    }

    @Test
    fun `extremes of the place's day`() {
        val row = NimbusProvider.nowRow(data(), today.plusDays(1))!!
        assertEquals(16.0, row[5])
        assertEquals(5.5, row[6])
    }

    @Test
    fun `region as under the name in Today`() {
        assertEquals("Suisse", NimbusProvider.nowRow(data(Place("Le Locle", 47.06, 6.75, country = "Suisse")), today)!![1])
        assertNull(NimbusProvider.nowRow(data(Place("46,5000° N, 6,6000° E", 46.5, 6.6, admin2 = "")), today)!![1])
    }

    @Test
    fun `stale cache is flagged`() {
        assertEquals(1, NimbusProvider.nowRow(data(stale = true), today)!![10])
    }

    @Test
    fun `no row without place or forecast`() {
        assertNull(NimbusProvider.nowRow(data(place = null, body = null), today))
        assertNull(NimbusProvider.nowRow(data(body = null, stale = true), today))
        assertNull(NimbusProvider.nowRow(data(body = FORECAST.replace(CURRENT, "")), today))
    }

    @Test
    fun `no row with a missing temperature`() {
        assertNull(NimbusProvider.nowRow(data(body = FORECAST.replace("\"temperature_2m\":9.4", "\"temperature_2m\":null")), today))
    }

    @Test
    fun `sketch size defaults and bounds`() {
        assertEquals(1027 to 717, NimbusProvider.sketchSize(null, null))
        assertEquals(1080 to 754, NimbusProvider.sketchSize("1080", "754"))
        assertEquals(64 to 2048, NimbusProvider.sketchSize("10", "5000"))
        assertEquals(64 to 64, NimbusProvider.sketchSize("-3", "0"))
        assertEquals(1027 to 717, NimbusProvider.sketchSize("large", ""))
    }

    @Test
    fun `only Nimbus and the app itself`() {
        val own = "io.github.pbailly.meteo"
        assertTrue(NimbusProvider.allowed("fr.perso.nimbus", own))
        assertTrue(NimbusProvider.allowed(own, own))
        assertFalse(NimbusProvider.allowed("com.example.spy", own))
        assertFalse(NimbusProvider.allowed(null, own))
    }

    private companion object {
        const val FETCHED_AT = 1_792_000_000_000L
        const val CURRENT = """
             "current":{"time":"2026-10-21T08:15","interval":900,"temperature_2m":9.4,"apparent_temperature":7.2,
               "weather_code":2,"is_day":0},"""
        val FORECAST = """
            {"latitude":47.06,"longitude":6.61,"utc_offset_seconds":7200,"timezone":"Europe/Paris","elevation":760.0,$CURRENT
             "hourly":{"time":["2026-10-21T08:00"],"temperature_2m":[9.0]},
             "daily":{"time":["2026-10-21","2026-10-22"],"temperature_2m_max":[14.2,16.0],"temperature_2m_min":[3.1,5.5]}}
        """
    }
}
