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
        assertTrue(Overpass.peaksUrl(45.9237, 6.8694).contains("around%3A60000%2C45.9237%2C6.8694"))
    }

    @Test
    fun `the highest peak within the radius is the target`() {
        val far = Peak("Loin", 46.5, 6.8694, 5000.0)
        assertEquals(montBlanc, Terrain.highestPeak(chamonix.latitude, chamonix.longitude, listOf(midi, montBlanc, far)))
    }

    @Test
    fun `the dominant peak is the one that stands out the most from the place`() {
        // Morteau (769 m) : sommets d'OpenStreetMap ; le Chasseron est plus haut, le Mont Vouillot domine la ville.
        val vouillot = Peak("Mont Vouillot", 47.078, 6.604, 1160.0)
        val morteauPeaks = listOf(
            Peak("Le Chasseron", 46.852, 6.538, 1607.0),
            Peak("Mont Racine", 47.022, 6.816, 1439.0),
            Peak("Mont Châteleu", 46.99, 6.575, 1301.0),
            vouillot,
            Peak("Sommet des Ages", 47.0, 6.575, 1114.0),
        )
        assertEquals(vouillot, Terrain.dominantPeak(47.05784, 6.60716, 769.0, morteauPeaks))
        // Valence (126 m) : le Grand Veymont à 49 km passe devant la colline de Crussol et le rebord du Vercors.
        val veymont = Peak("Grand Veymont", 44.866, 5.523, 2341.0)
        val valencePeaks = listOf(veymont, Peak("Crussol", 44.94, 4.86, 400.0), Peak("Rebord du Vercors", 44.95, 5.27, 1350.0))
        assertEquals(veymont, Terrain.dominantPeak(44.9256, 4.90956, 126.0, valencePeaks))
        // Chamonix : le Mont Blanc reste la cible.
        assertEquals(montBlanc, Terrain.dominantPeak(chamonix.latitude, chamonix.longitude, 1035.0, listOf(midi, montBlanc, brevent)))
        assertNull(Terrain.dominantPeak(47.05784, 6.60716, Double.NaN, morteauPeaks))
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

    @Test
    fun `on a summit the profile looks towards another peak`() {
        val jorasses = Peak("Grandes Jorasses", 45.8687, 6.9879, 4208.0)
        val courmayeur = Peak("Mont Blanc de Courmayeur", 45.8295, 6.8565, 4748.0)
        // Sur le Mont Blanc, rien ne le domine : on regarde le plus haut sommet à plus de 3 km, pas le Mont Blanc lui-même.
        val target = Terrain.viewTarget(montBlanc.latitude, montBlanc.longitude, 4808.0, listOf(montBlanc, courmayeur, jorasses, midi))
        assertEquals("Grandes Jorasses", target?.name)
        // À Chamonix, toujours le sommet qui domine.
        assertEquals("Mont Blanc", Terrain.viewTarget(chamonix.latitude, chamonix.longitude, 1035.0, listOf(montBlanc, midi, brevent))?.name)
    }

    @Test
    fun `quick profile looks towards what dominates on the grid`() {
        val grid = Terrain.gridPoints(chamonix.latitude, chamonix.longitude, Terrain.QUICK_RADIUS_KM)
        // Une grille plate à 1 000 m, avec un point à 4 000 m au sud et un autre à 1 300 m tout près.
        val heights = DoubleArray(grid.size) { 1000.0 }
        val south = grid.indices.minBy { Terrain.distanceKm(montBlanc.latitude, montBlanc.longitude, grid[it].first, grid[it].second) }
        heights[south] = 4000.0
        val near = grid.indices.filter { Terrain.distanceKm(chamonix.latitude, chamonix.longitude, grid[it].first, grid[it].second) >= 3 }
            .minBy { Terrain.distanceKm(chamonix.latitude, chamonix.longitude, grid[it].first, grid[it].second) }
        heights[near] = 1300.0
        assertEquals(grid[south], Terrain.dominantPoint(chamonix.latitude, chamonix.longitude, 1035.0, grid, heights))
        // En plaine sans relief : le plus haut point, au moins à 3 km.
        val flat = DoubleArray(grid.size) { 100.0 }.also { it[near] = 120.0 }
        assertEquals(grid[near], Terrain.dominantPoint(chamonix.latitude, chamonix.longitude, 100.0, grid, flat))
    }
}
