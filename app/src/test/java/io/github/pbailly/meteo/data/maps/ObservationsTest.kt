package io.github.pbailly.meteo.data.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ObservationsTest {

    private fun resource(name: String) = javaClass.getResource("/obs/$name")!!.readText()

    @Test
    fun `meteoswiss stations are placed with the LV95 conversion`() {
        val stations = MeteoSwissObservations.parse(resource("meteoswiss.json"))
        assertEquals(4, stations.size)
        val geneva = stations.single { it.id == "GVE" }
        assertEquals("Genève / Cointrin", geneva.name)
        assertEquals(46.247, geneva.latitude, 0.01)
        assertEquals(6.128, geneva.longitude, 0.01)
        assertEquals(11.9, geneva.temperature, 0.0)
        assertEquals(ObservationSource.METEOSWISS, geneva.source)
        val jungfrau = stations.single { it.id == "JUN" }
        assertTrue(jungfrau.altitude!! > 3500)
    }

    @Test
    fun `swisstopo reference point`() {
        // Point de contrôle de swisstopo : Berne, ancien observatoire.
        val (lat, lon) = MeteoSwissObservations.lv95ToWgs84(2_600_000.0, 1_200_000.0)
        assertEquals(46.95108, lat, 0.0002)
        assertEquals(7.43863, lon, 0.0002)
    }

    @Test
    fun `metar temperatures and airport names`() {
        val stations = MetarObservations.parse(resource("metar.json"))
        assertEquals(4, stations.size)
        val calvi = stations.single { it.id == "LFKC" }
        assertEquals("Calvi/St Catherine (aéroport)", calvi.name)
        assertEquals(19.0, calvi.temperature, 0.0)
        assertEquals(Instant.ofEpochSecond(1792443600), calvi.time)
        assertTrue(MetarObservations.url().startsWith("https://aviationweather.gov/api/data/metar?bbox=41.0,-5.5,51.5,10.5"))
    }

    @Test
    fun `meteo-france geojson packet in kelvin, with station names`() {
        val body = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[6.1061,45.9289]},
               "properties":{"geo_id_insee":"74182001","reference_time":"2026-10-19T21:10:00Z","validity_time":"2026-10-19T21:00:00Z","t":285.45,"u":72}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[6.8758,45.9300]},
               "properties":{"geo_id_insee":"74056001","validity_time":"2026-10-19T21:00:00Z","t":null}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[6.8873,45.8786]},
               "properties":{"geo_id_insee":"74056405","validity_time":"2026-10-19T21:00:00Z","t":262.15}}
            ]}
        """.trimIndent()
        val names = MeteoFranceObservations.parseStations(
            "Id_station;Id_omm;Nom_usuel;Latitude;Longitude;Altitude;Date_ouverture;Pack\n" +
                "74182001;07497;ANNECY-MEYTHET;45.9289;6.1061;458;1960-01-01;RADOME\n" +
                "74056405;;AIGUILLE DU MIDI;45.8786;6.8873;3842;2000-01-01;ETENDU\n",
        )
        assertEquals(StationInfo("Annecy-Meythet", 458.0), names["74182001"])
        val stations = MeteoFranceObservations.parse(body, names)
        assertEquals(2, stations.size)
        assertEquals(12.3, stations[0].temperature, 1e-9)
        assertEquals("Annecy-Meythet", stations[0].name)
        assertEquals("Aiguille du Midi", stations[1].name)
        assertEquals(-11.0, stations[1].temperature, 1e-9)
        assertEquals(3842.0, stations[1].altitude!!, 0.0)
    }

    @Test
    fun `meteo-france json array also works`() {
        val body = """[{"lat":45.9289,"lon":6.1061,"geo_id_insee":"74182001","validity_time":"2026-10-19T21:00:00Z","t":285.45}]"""
        val stations = MeteoFranceObservations.parse(body)
        assertEquals(45.9289, stations.single().latitude, 0.0)
        assertNull(stations.single().name)
    }

    @Test
    fun `latest packet is the previous full hour a quarter past`() {
        assertEquals(Instant.parse("2026-10-19T20:00:00Z"), MeteoFranceObservations.latestHour(Instant.parse("2026-10-19T21:10:00Z")))
        assertEquals(Instant.parse("2026-10-19T21:00:00Z"), MeteoFranceObservations.latestHour(Instant.parse("2026-10-19T21:20:00Z")))
        assertEquals(
            "https://public-api.meteofrance.fr/public/DPPaquetObs/v2/paquet/stations/horaire?date=2026-10-19T21:00:00Z&format=geojson",
            MeteoFranceObservations.hourlyUrl(Instant.parse("2026-10-19T21:00:00Z")),
        )
    }

    @Test
    fun `merge drops old readings and duplicates of the same station`() {
        val now = Instant.parse("2026-10-19T21:30:00Z")
        fun obs(id: String, lat: Double, source: ObservationSource, time: String = "2026-10-19T21:00:00Z") =
            StationObservation(id, id, lat, 6.1, null, 10.0, Instant.parse(time), source)
        val merged = mergeObservations(
            listOf(obs("airport", 45.9290, ObservationSource.METAR), obs("old", 45.0, ObservationSource.METAR, "2026-10-19T12:00:00Z")),
            listOf(obs("mf", 45.9289, ObservationSource.METEO_FRANCE)),
            now = now,
        )
        assertEquals(listOf("mf"), merged.map { it.id })
    }

    @Test
    fun `stations are spread out at low zoom and all shown when zoomed in`() {
        val now = Instant.now()
        val grid = (0 until 20).flatMap { i ->
            (0 until 20).map { j -> StationObservation("s$i-$j", null, 45.0 + i * 0.05, 5.0 + j * 0.05, null, 10.0, now, ObservationSource.METEO_FRANCE) }
        }
        val zooms = stationMinZooms(grid, spacing = 30.0, from = 4, to = 11)
        // 400 stations sur 1° × 1° : une poignée au zoom 6, toutes au zoom 11.
        assertTrue(zooms.count { it <= 6 } in 1..30)
        assertEquals(grid.size, zooms.count { it <= 11 })
    }
}
