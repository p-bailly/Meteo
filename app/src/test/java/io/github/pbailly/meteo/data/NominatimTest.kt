package io.github.pbailly.meteo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NominatimTest {

    @Test
    fun `reverse url asks for french names down to hamlets`() {
        assertEquals(
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=14&accept-language=fr&lat=47.03861&lon=6.61140",
            Nominatim.reverseUrl(47.038614, 6.6114),
        )
    }

    @Test
    fun `names the commune, not its arrondissement, with its department`() {
        val place = Nominatim.parse(MONTLEBON, 47.0386, 6.6114)!!
        assertEquals("Montlebon", place.name)
        assertEquals("Doubs", place.department)
        assertEquals("Bourgogne-Franche-Comté", place.admin1)
        assertEquals("France", place.country)
        assertEquals(47.0386, place.latitude, 0.0)
        assertNull(place.elevation)
    }

    @Test
    fun `prefers the commune to its hamlet, the hamlet to nothing`() {
        val hamlet = MONTLEBON.replace("\"village\":\"Montlebon\",", "\"hamlet\":\"Le Bizot\",\"village\":\"Montlebon\",")
        assertEquals("Montlebon", Nominatim.parse(hamlet, 47.0, 6.6)!!.name)
        assertEquals("Le Bizot", Nominatim.parse(hamlet.replace("\"village\":\"Montlebon\",", ""), 47.0, 6.6)!!.name)
    }

    @Test
    fun `nothing at sea`() {
        assertNull(Nominatim.parse("""{"error":"Unable to geocode"}""", 43.0, 5.0))
        assertNull(Nominatim.parse("""{"address":{"state":"Bretagne","country":"France"}}""", 48.0, -4.0))
    }

    @Test
    fun `department stays empty when unknown, so it is not searched again`() {
        val place = Nominatim.parse("""{"address":{"town":"Monaco","country":"Monaco"}}""", 43.73, 7.42)!!
        assertEquals("", place.admin2)
        assertEquals("Monaco", place.subtitle)
    }

    private companion object {
        val MONTLEBON = """
            {"place_id":95283447,"licence":"Data © OpenStreetMap contributors, ODbL 1.0. http://osm.org/copyright",
            "osm_type":"relation","osm_id":151398,"lat":"47.0386140","lon":"6.6114022","category":"boundary",
            "type":"administrative","place_rank":16,"importance":0.38,"addresstype":"village","name":"Montlebon",
            "display_name":"Montlebon, Pontarlier, Doubs, Bourgogne-Franche-Comté, France métropolitaine, 25500, France",
            "address":{"village":"Montlebon","municipality":"Pontarlier","county":"Doubs","ISO3166-2-lvl6":"FR-25",
            "state":"Bourgogne-Franche-Comté","ISO3166-2-lvl4":"FR-BFC","region":"France métropolitaine","postcode":"25500",
            "country":"France","country_code":"fr"},"boundingbox":["47.0029","47.0711","6.5643","6.6624"]}
        """.trimIndent()
    }
}
