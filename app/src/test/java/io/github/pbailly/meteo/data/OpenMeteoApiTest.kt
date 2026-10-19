package io.github.pbailly.meteo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class OpenMeteoApiTest {

    private val lyon = Place("Lyon", 45.7578, 4.832, "Auvergne-Rhône-Alpes", "France", 173.0)

    @Test
    fun `forecast url asks for local time, km per hour and 14 days`() {
        val url = OpenMeteoApi.forecastUrl(lyon)
        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?latitude=45.7578&longitude=4.8320&"))
        assertTrue(url.contains("timezone=auto"))
        assertTrue(url.contains("wind_speed_unit=kmh"))
        assertTrue(url.contains("forecast_days=14"))
        assertTrue(url.contains("hourly=temperature_2m%2Capparent_temperature"))
        assertTrue(url.contains("freezing_level_height%2Ctemperature_925hPa%2Cgeopotential_height_925hPa%2Cwind_speed_925hPa"))
        assertTrue(url.contains("wind_direction_500hPa"))
    }

    @Test
    fun `summary url asks for every place in one call`() {
        val annecy = Place("Annecy", 45.8992, 6.1294, elevation = 448.0)
        val url = OpenMeteoApi.summaryUrl(listOf(lyon, annecy))
        assertTrue(url.contains("latitude=45.7578%2C45.8992&longitude=4.8320%2C6.1294&"))
        assertTrue(url.contains("forecast_days=8"))
    }

    @Test
    fun `parses a multi place response in order, and a single object too`() {
        val two = OpenMeteoApi.parseForecasts("[$FORECAST,${FORECAST.replace("\"elevation\":173.0", "\"elevation\":448.0")}]")
        assertEquals(listOf(173.0, 448.0), two.map { it.elevation })
        assertEquals(1, OpenMeteoApi.parseForecasts(FORECAST).size)
    }

    @Test
    fun `elevation url and response`() {
        val url = OpenMeteoApi.elevationUrl(listOf(45.9 to 6.87, 45.88 to 6.89))
        assertEquals("https://api.open-meteo.com/v1/elevation?latitude=45.9000%2C45.8800&longitude=6.8700%2C6.8900", url)
        val e = OpenMeteoApi.parseElevation("""{"elevation":[1035.0,3842.0,null]}""")
        assertEquals(3842.0, e[1], 0.0)
        assertTrue(e[2].isNaN())
    }

    @Test
    fun `multi model url lists every model id`() {
        val url = OpenMeteoApi.multiModelUrl(lyon, WeatherModel.entries)
        val models = url.substringAfter("models=").substringBefore("&").replace("%2C", ",")
        assertEquals(WeatherModel.entries.joinToString(",") { it.apiId }, models)
    }

    @Test
    fun `search url encodes accents and spaces`() {
        val url = OpenMeteoApi.searchUrl(" Saint-Étienne de Tinée ")
        assertTrue(url.contains("name=Saint-%C3%89tienne+de+Tin%C3%A9e&"))
        assertTrue(url.contains("language=fr"))
    }

    @Test
    fun `parses search results and skips incomplete entries`() {
        val body = """
            {"results":[
              {"id":2996944,"name":"Lyon","latitude":45.74846,"longitude":4.84671,"elevation":173.0,
               "country":"France","admin1":"Auvergne-Rhône-Alpes"},
              {"id":1,"name":"Sans coordonnées"}
            ],"generationtime_ms":0.5}
        """.trimIndent()
        val places = OpenMeteoApi.parseSearch(body)
        assertEquals(1, places.size)
        assertEquals("Lyon", places[0].name)
        assertEquals("Auvergne-Rhône-Alpes, France", places[0].subtitle)
        assertEquals(173.0, places[0].elevation!!, 0.0)
    }

    @Test
    fun `search without results returns an empty list`() {
        assertTrue(OpenMeteoApi.parseSearch("""{"generationtime_ms":0.3}""").isEmpty())
    }

    @Test
    fun `parses forecast with missing values as NaN`() {
        val forecast = OpenMeteoApi.parseForecast(FORECAST)
        assertEquals(7200, forecast.utcOffsetSeconds)
        assertEquals(173.0, forecast.elevation, 0.0)

        val h = forecast.hourly
        assertEquals(3, h.size)
        assertEquals(LocalDateTime.of(2026, 10, 19, 0, 0), h.time[0])
        assertEquals(12.4, h.temperature[0], 0.0)
        assertTrue(h.precipitationProbability[2].isNaN())
        assertEquals(-1, h.weatherCode[2])
        assertFalse(h.isDay[0])
        assertTrue(h.isDay[1])
        assertEquals(2450.0, h.freezingLevel[0], 0.0)
        assertTrue(h.freezingLevel[2].isNaN())
        // Seul le niveau 850 hPa est dans la réponse : les autres ne sont pas créés.
        assertEquals(listOf(850), h.levels.map { it.hPa })
        assertEquals(4.2, h.levels[0].temperature[0], 0.0)
        assertEquals(1523.0, h.levels[0].height[0], 0.0)
        assertTrue(h.levels[0].windSpeed[0].isNaN())

        val d = forecast.daily
        assertEquals(LocalDate.of(2026, 10, 19), d.date[0])
        assertEquals(18.2, d.temperatureMax[0], 0.0)
        assertEquals(LocalDateTime.of(2026, 10, 19, 8, 12), d.sunrise[0])
        assertNull(d.sunset[0])

        val c = forecast.current!!
        assertEquals(15.3, c.temperature, 0.0)
        assertEquals(3, c.weatherCode)
        assertTrue(c.isDay)
    }

    @Test
    fun `parses multi model response with per-model suffixes`() {
        val models = listOf(WeatherModel.METEO_FRANCE, WeatherModel.GFS, WeatherModel.METEOSWISS)
        val body = """
            {"utc_offset_seconds":7200,"hourly":{
              "time":["2026-10-19T00:00","2026-10-19T01:00"],
              "temperature_2m_meteofrance_seamless":[11.0,10.5],
              "precipitation_meteofrance_seamless":[0.0,0.2],
              "wind_speed_10m_meteofrance_seamless":[5.0,6.0],
              "temperature_2m_gfs_seamless":[12.0,null],
              "precipitation_gfs_seamless":[0.1,null],
              "wind_speed_10m_gfs_seamless":[7.0,null],
              "temperature_2m_meteoswiss_icon_seamless":[null,null]
            }}
        """.trimIndent()
        val mm = OpenMeteoApi.parseMultiModel(body, models)
        assertEquals(2, mm.time.size)
        assertEquals(10.5, mm.series.getValue(WeatherModel.METEO_FRANCE).temperature[1], 0.0)
        assertTrue(mm.series.getValue(WeatherModel.GFS).temperature[1].isNaN())
        assertTrue(mm.series.getValue(WeatherModel.GFS).hasData)
        assertFalse(mm.series.getValue(WeatherModel.METEOSWISS).hasData)
    }

    @Test(expected = OpenMeteoException::class)
    fun `api error becomes an OpenMeteoException`() {
        OpenMeteoApi.parseForecast("""{"error":true,"reason":"Latitude must be in range of -90 to 90°."}""")
    }

    private companion object {
        val FORECAST = """
            {"latitude":45.76,"longitude":4.84,"utc_offset_seconds":7200,"timezone":"Europe/Paris","elevation":173.0,
             "current":{"time":"2026-10-19T14:15","interval":900,"temperature_2m":15.3,"apparent_temperature":14.1,
               "relative_humidity_2m":70,"weather_code":3,"is_day":1,"precipitation":0.0,"wind_speed_10m":12.0,
               "wind_direction_10m":200,"wind_gusts_10m":25.0,"pressure_msl":1015.2},
             "hourly":{"time":["2026-10-19T00:00","2026-10-19T01:00","2026-10-19T02:00"],
               "temperature_2m":[12.4,12.0,11.8],
               "precipitation_probability":[10,20,null],
               "weather_code":[0,2,null],
               "is_day":[0,1,0],
               "freezing_level_height":[2450.0,2380.0,null],
               "temperature_850hPa":[4.2,3.8,3.5],"geopotential_height_850hPa":[1523.0,1520.0,1518.0]},
             "daily":{"time":["2026-10-19"],"temperature_2m_max":[18.2],"temperature_2m_min":[9.1],
               "sunrise":["2026-10-19T08:12"],"sunset":[null]}}
        """.trimIndent()
    }
}
