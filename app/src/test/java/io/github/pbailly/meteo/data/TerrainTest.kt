package io.github.pbailly.meteo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainTest {

    private val chamonix = Place("Chamonix-Mont-Blanc", 45.9237, 6.8694, elevation = 1035.0)
    private val montBlanc = Peak("Mont Blanc", 45.8326, 6.8652, 4808.0)
    private val midi = Peak("Aiguille du Midi", 45.8789, 6.8874, 3842.0)
    private val brevent = Peak("Le Brévent", 45.9346, 6.8381, 2525.0)
    private val planPraz = Peak("Plan Praz", 45.9324, 6.8470, 2000.0)

    @Test
    fun `overpass peaks are read with their elevation`() {
        val body = """
            {"elements":[
              {"type":"node","id":1,"lat":45.8326,"lon":6.8652,"tags":{"natural":"peak","name":"Mont Blanc","ele":"4808"}},
              {"type":"node","id":2,"lat":45.8789,"lon":6.8874,"tags":{"natural":"peak","name":"Aiguille du Midi","ele":"3 842 m"}},
              {"type":"node","id":3,"lat":45.9,"lon":6.9,"tags":{"natural":"peak","ele":"2000"}},
              {"type":"node","id":4,"lat":45.9,"lon":6.9,"tags":{"natural":"peak","name":"Sans altitude","ele":"haut"}}
            ]}
        """.trimIndent()
        val peaks = Overpass.parsePeaks(body)
        assertEquals(listOf("Mont Blanc", "Aiguille du Midi"), peaks.map { it.name })
        assertEquals(3842.0, peaks[1].elevation, 0.0)
        assertEquals(4808.7, Overpass.parseElevation("4808,7")!!, 1e-9)
        assertNull(Overpass.parseElevation("12000"))
        assertTrue(Overpass.peaksUrl(45.9237, 6.8694).contains("around%3A25000%2C45.9237%2C6.8694"))
    }

    @Test
    fun `the highest peak within the radius is the target`() {
        val far = Peak("Loin", 46.5, 6.8694, 5000.0)
        assertEquals(montBlanc, Terrain.highestPeak(chamonix.latitude, chamonix.longitude, listOf(midi, montBlanc, far)))
    }

    @Test
    fun `profile runs from before the place to beyond the summit`() {
        val line = Terrain.line(chamonix.latitude, chamonix.longitude, montBlanc.latitude, montBlanc.longitude)
        assertEquals(Terrain.SAMPLES, line.size)
        val place = line[Terrain.placeIndex()]
        assertEquals(chamonix.latitude, place.first, 0.003)
        val summit = line[Terrain.targetIndex()]
        assertEquals(montBlanc.latitude, summit.first, 0.003)
        assertTrue(line.first().first > chamonix.latitude)
        assertTrue(line.last().first < montBlanc.latitude)
    }

    @Test
    fun `peaks near the line are labelled, the summit first`() {
        val line = Terrain.line(chamonix.latitude, chamonix.longitude, montBlanc.latitude, montBlanc.longitude)
        // Relief simplifié : la vallée, une pente régulière jusqu'au sommet, et l'arête de l'Aiguille du Midi.
        val elevations = DoubleArray(Terrain.SAMPLES) { k ->
            when {
                k <= Terrain.placeIndex() -> 1035.0
                k in 50..54 -> 3700.0
                else -> 1035.0 + (k - Terrain.placeIndex()) * 50.0
            }
        }
        val profile = Terrain.build(line, elevations, montBlanc, listOf(montBlanc, midi, brevent, planPraz))
        assertEquals("Mont Blanc", profile.summit!!.name)
        assertEquals(Terrain.targetIndex(), profile.summit!!.index)
        assertEquals(4808.0, profile.elevations[Terrain.targetIndex()], 0.0)
        // L'Aiguille du Midi est à 1,6 km de la ligne, et la coupe passe sur son arête : elle est dessinée.
        assertTrue(profile.labels.any { it.name == "Aiguille du Midi" })
        // Le Brévent est à plus de 2 km : il ne l'est pas.
        assertTrue(profile.labels.none { it.name == "Le Brévent" })
        assertTrue(profile.lengthKm in 13.0..15.0)
        assertEquals("Plan Praz", profile.nameNear(2000.0))
    }

    @Test
    fun `without peaks the highest sample is the summit`() {
        val line = Terrain.line(45.0, 6.0, 45.1, 6.0)
        val elevations = DoubleArray(Terrain.SAMPLES) { k -> if (k == 70) 2500.0 else 500.0 }
        val profile = Terrain.build(line, elevations, null, emptyList())
        assertEquals(ProfileLabel(70, null, 2500.0), profile.summit)
    }
}
