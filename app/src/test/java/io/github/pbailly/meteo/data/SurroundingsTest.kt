package io.github.pbailly.meteo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurroundingsTest {
    @Test
    fun `population reads the usual spellings`() {
        assertEquals(2165423L, SurroundingsQuery.parsePopulation("2165423"))
        assertEquals(2165423L, SurroundingsQuery.parsePopulation("2 165 423"))
        assertEquals(12345L, SurroundingsQuery.parsePopulation("12,345"))
        assertEquals(5000L, SurroundingsQuery.parsePopulation("~5000"))
        assertNull(SurroundingsQuery.parsePopulation("beaucoup"))
        assertNull(SurroundingsQuery.parsePopulation(null))
    }

    @Test
    fun `the biggest place that covers the spot wins`() {
        fun of(vararg nodes: SettlementNode) = SurroundingsQuery.settlement(nodes.toList())
        // Au centre de Paris, ou à 6 km : la grande ville, même avec un village tout près.
        assertEquals(Settlement.CITY, of(SettlementNode("city", 2_100_000, 6.0), SettlementNode("village", 400, 0.3)))
        // Morteau (7 000 habitants), en son centre ; à 3 km, on n'y est plus.
        assertEquals(Settlement.TOWN, of(SettlementNode("town", 7_000, 0.2)))
        assertEquals(Settlement.NONE, of(SettlementNode("town", 7_000, 3.0)))
        assertEquals(Settlement.VILLAGE, of(SettlementNode("village", null, 0.4)))
        assertEquals(Settlement.HAMLET, of(SettlementNode("hamlet", null, 0.2)))
        // Une « city » sans population : une grande ville.
        assertEquals(Settlement.CITY, of(SettlementNode("city", null, 2.0)))
        assertEquals(Settlement.NONE, of())
    }

    @Test
    fun `overpass answer gives the surroundings`() {
        // Réponse au format d'Overpass : les lieux habités, cinq comptes dans l'ordre de la requête, puis les îles.
        val body = """
            {"elements":[
              {"type":"node","id":1,"lat":42.6973,"lon":9.4509,"tags":{"place":"town","name":"Bastia","population":"48 503"}},
              {"type":"node","id":2,"lat":42.70,"lon":9.44,"tags":{"place":"hamlet","name":"Cardo"}},
              {"type":"count","id":0,"tags":{"nodes":"0","ways":"4","relations":"1","total":"5"}},
              {"type":"count","id":0,"tags":{"nodes":"0","ways":"0","relations":"0","total":"0"}},
              {"type":"count","id":0,"tags":{"nodes":"0","ways":"0","relations":"0","total":"0"}},
              {"type":"count","id":0,"tags":{"nodes":"0","ways":"2","relations":"0","total":"2"}},
              {"type":"count","id":0,"tags":{"areas":"0","total":"0"}},
              {"type":"relation","id":3,"bounds":{"minlat":41.33,"minlon":8.53,"maxlat":43.03,"maxlon":9.56},"tags":{"place":"island","name":"Corse"}}
            ]}
        """.trimIndent()
        val s = SurroundingsQuery.parse(body, 42.6973, 9.4509)
        assertEquals(Settlement.TOWN, s.settlement)
        assertTrue(s.forest)
        assertFalse(s.river)
        assertFalse(s.lake)
        assertTrue(s.coast)
        assertTrue(s.island)
        // La Grande-Bretagne est une île, mais trop grande pour être dessinée comme telle.
        val london = """
            {"elements":[
              {"type":"node","id":1,"lat":51.507,"lon":-0.128,"tags":{"place":"city","population":"8908081"}},
              {"type":"count","id":0,"tags":{"total":"3"}},
              {"type":"count","id":0,"tags":{"total":"1"}},
              {"type":"count","id":0,"tags":{"total":"0"}},
              {"type":"count","id":0,"tags":{"total":"0"}},
              {"type":"count","id":0,"tags":{"total":"0"}},
              {"type":"relation","id":3,"bounds":{"minlat":49.9,"minlon":-6.4,"maxlat":58.7,"maxlon":1.8},"tags":{"place":"island","name":"Great Britain"}}
            ]}
        """.trimIndent()
        val l = SurroundingsQuery.parse(london, 51.507, -0.128)
        assertEquals(Settlement.CITY, l.settlement)
        assertTrue(l.river)
        assertFalse(l.island)
    }

    @Test
    fun `query asks overpass for everything at once`() {
        val url = java.net.URLDecoder.decode(SurroundingsQuery.url(47.0577, 6.5664), "UTF-8")
        assertTrue(url.startsWith(Overpass.SERVERS.first()))
        assertTrue("around:15000,47.05770,6.56640" in url)
        assertEquals(5, Regex("out count;").findAll(url).count())
        assertTrue("is_in(47.05770,6.56640)" in url)
    }
}
