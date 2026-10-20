package io.github.pbailly.meteo.ui

import io.github.pbailly.meteo.data.Backdrop
import io.github.pbailly.meteo.data.Settlement
import io.github.pbailly.meteo.data.SpaceWeather
import io.github.pbailly.meteo.data.Surroundings
import io.github.pbailly.meteo.data.Terrain
import io.github.pbailly.meteo.data.TerrainProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Le ciel vrai et ce que les environs ajoutent au dessin. */
class SketchSkyTest {
    @Test
    fun `sun is where it really is`() {
        // Paris, solstice d'été, midi solaire (vers 11 h 50 TU) : plein sud, à 64,6°.
        val noon = SketchSkies.sun(LocalDateTime.of(2026, 6, 21, 11, 50), 48.85, 2.35)
        assertEquals(64.6, noon.elevation, 0.6)
        assertEquals(180.0, noon.azimuth, 4.0)
        // Le matin, à l'est et bas ; la nuit, sous l'horizon.
        val morning = SketchSkies.sun(LocalDateTime.of(2026, 6, 21, 5, 0), 48.85, 2.35)
        assertTrue(morning.azimuth in 50.0..80.0 && morning.elevation in 0.0..12.0)
        assertTrue(SketchSkies.sun(LocalDateTime.of(2026, 6, 21, 23, 0), 48.85, 2.35).elevation < 0)
    }

    @Test
    fun `full moon rises opposite the sun`() {
        // Pleine lune du 26 octobre 2026 : à minuit (TU), haute dans le sud.
        val moon = SketchSkies.moon(LocalDateTime.of(2026, 10, 26, 0, 0), 48.85, 2.35)
        assertTrue(moon.elevation > 25)
        assertEquals(180.0, moon.azimuth, 35.0)
    }

    @Test
    fun `sky bodies are placed by the direction we look`() {
        // On regarde vers le sud : le soleil de midi au milieu, celui du matin (à l'est) à gauche.
        assertEquals(0.59f, SketchSkies.onScreen(SkyPosition(180.0, 40.0), view = 180.0).x, 0.01f)
        assertTrue(SketchSkies.onScreen(SkyPosition(100.0, 10.0), view = 180.0).x < 0.45f)
        assertTrue(SketchSkies.onScreen(SkyPosition(260.0, 10.0), view = 180.0).x > 0.73f)
        // Le soleil dans notre dos (au nord-nord-est) reste contre le bord gauche, du côté de l'est.
        assertEquals(0.42f, SketchSkies.onScreen(SkyPosition(10.0, 30.0), view = 180.0).x, 0.001f)
    }

    @Test
    fun `scene puts the sun from the time and place`() {
        val time = LocalDateTime.of(2026, 6, 21, 13, 50)
        val south = SketchTerrains.DEFAULT
        val scene = SketchScenes.scene(0, true, time, 1, south, conditions = SketchConditions(utcOffsetSeconds = 7200), latitude = 48.85, longitude = 2.35)
        // Par défaut on regarde vers le nord : le soleil de midi est dans notre dos.
        assertTrue(scene.sun != null && scene.sun!!.elevation > 60)
        assertNull(SketchScenes.scene(0, true, time, 1, south).sun)
    }

    @Test
    fun `aurora comes down with the magnetic activity`() {
        // Tromsø : presque toujours ; Paris : seulement pendant une grande tempête ; Nice : jamais vraiment.
        assertTrue(SketchSkies.auroraVisible(69.65, 18.96, 1.0))
        assertFalse(SketchSkies.auroraVisible(48.85, 2.35, 5.0))
        assertTrue(SketchSkies.auroraVisible(48.85, 2.35, 8.7))
        assertFalse(SketchSkies.auroraVisible(43.70, 7.27, 6.0))
        val night = LocalDateTime.of(2026, 1, 10, 22, 0)
        val conditions = SketchConditions(kp = 7.0, sunrise = night.withHour(9), sunset = night.withHour(16))
        val scene = SketchScenes.scene(0, false, night, 1, conditions = conditions, latitude = 59.9, longitude = 10.7)
        assertTrue(Surprise.AURORA in scene.surprises)
        assertTrue(SketchScenes.moves(scene))
        assertFalse(Surprise.AURORA in SketchScenes.scene(3, false, night, 1, conditions = conditions, latitude = 59.9, longitude = 10.7).surprises)
    }

    @Test
    fun `kp index is read from noaa`() {
        val body = """[{"time_tag":"2026-10-20T00:00:00","kp":2.33,"observed":"observed"},
            {"time_tag":"2026-10-20T03:00:00","kp":4.67,"observed":"estimated"},
            {"time_tag":"2026-10-20T06:00:00","kp":3.00,"observed":"predicted"}]"""
        assertEquals(4.67, SpaceWeather.parse(body, LocalDateTime.of(2026, 10, 20, 4, 30))!!, 0.001)
        val old = """[["time_tag","kp","observed","noaa_scale"],["2026-10-20 00:00:00","5.33","observed",null]]"""
        assertEquals(5.33, SpaceWeather.parse(old, LocalDateTime.of(2026, 10, 20, 1, 0))!!, 0.001)
    }

    @Test
    fun `meteor showers on their nights`() {
        assertTrue(SketchSkies.meteorShower(LocalDate.of(2026, 8, 12)))
        assertTrue(SketchSkies.meteorShower(LocalDate.of(2026, 10, 20)))
        assertTrue(SketchSkies.meteorShower(LocalDate.of(2026, 12, 13)))
        assertFalse(SketchSkies.meteorShower(LocalDate.of(2026, 9, 1)))
        val night = LocalDateTime.of(2026, 8, 12, 23, 30)
        val scene = SketchScenes.scene(0, false, night, 5, conditions = SketchConditions(sunrise = night.withHour(6), sunset = night.withHour(21)))
        assertTrue(Surprise.METEORS in scene.surprises && Surprise.SHOOTING_STAR !in scene.surprises)
    }

    @Test
    fun `wind leans the way it blows in the drawing`() {
        val time = LocalDateTime.of(2026, 10, 20, 15, 0)
        fun lean(bearing: Double, from: Double): Float {
            val t = SketchTerrains.DEFAULT.copy().let { SketchTerrain(it.ridge, it.relief, it.water, it.sea, it.treeline, it.snowline, placeX = it.placeX, bearing = bearing) }
            return SketchScenes.scene(0, true, time, 1, t, conditions = SketchConditions(windKmh = 70.0, windFrom = from)).lean
        }
        // On regarde vers le nord (la coupe va vers l'est) : le vent d'ouest pousse vers la droite.
        assertEquals(1f, lean(90.0, 270.0), 0.01f)
        // On regarde vers le sud (la coupe va vers l'ouest) : le même vent pousse vers la gauche.
        assertEquals(-1f, lean(270.0, 270.0), 0.01f)
        // Un vent qui souffle vers nous ne couche rien de côté.
        assertEquals(0f, lean(90.0, 0.0), 0.01f)
    }

    @Test
    fun `snow line follows the freezing level`() {
        val seasonal = SketchTerrains.snowAltitude(10, 45.0, null)
        assertEquals(2400.0, seasonal, 0.1)
        // Il pleut avec l'isotherme à 1 500 m : neige fraîche dès 1 200 m.
        assertEquals(1200.0, SketchTerrains.snowAltitude(10, 45.0, SketchConditions(freezingLevel = 1500.0, precipitating = true)), 0.1)
        // Sans précipitations, la neige suit l'isotherme sans s'éloigner de plus de 600 m de celle du mois.
        assertEquals(3000.0, SketchTerrains.snowAltitude(10, 45.0, SketchConditions(freezingLevel = 4200.0)), 0.1)
        assertEquals(2100.0, SketchTerrains.snowAltitude(10, 45.0, SketchConditions(freezingLevel = 2400.0)), 0.1)
    }

    @Test
    fun `windows light up at dusk`() {
        val time = LocalDateTime.of(2026, 10, 20, 19, 0)
        assertEquals(0f, SketchScenes.litWindows(Light.DAY, time, SketchSky.CLEAR))
        assertTrue(SketchScenes.litWindows(Light.SUNSET, time, SketchSky.CLEAR) < SketchScenes.litWindows(Light.DUSK, time, SketchSky.CLEAR))
        assertTrue(SketchScenes.litWindows(Light.DUSK, time, SketchSky.CLEAR) < SketchScenes.litWindows(Light.NIGHT, time, SketchSky.CLEAR))
        assertTrue(SketchScenes.litWindows(Light.NIGHT, time.withHour(3), SketchSky.CLEAR) < SketchScenes.litWindows(Light.NIGHT, time, SketchSky.CLEAR))
    }

    @Test
    fun `surroundings add heritage, turbines, train, harbour and crops`() {
        val time = LocalDateTime.of(2026, 7, 10, 14, 0)
        val around = Surroundings(
            Settlement.VILLAGE, vineyard = true, castle = true, abbey = true, windTurbines = true, railway = true, harbour = true,
        )
        val plain = SketchTerrains.from(flat(), 7, snowing = false, around = around)
        val decor = SketchScenes.scene(0, true, time, 2, plain, around = around, latitude = 44.0, longitude = 5.0).decor
        assertTrue(decor.containsAll(listOf(Decor.VINES, Decor.CASTLE, Decor.ABBEY, Decor.TURBINES, Decor.TRAIN, Decor.LAMPS)))
        // Pas de port sans eau ; de la lavande seulement en Provence l'été.
        assertFalse(Decor.HARBOUR in decor)
        assertTrue(Decor.LAVENDER in decor)
        assertFalse(Decor.LAVENDER in SketchScenes.scene(0, true, time, 2, plain, around = around, latitude = 47.0, longitude = 5.0).decor)
        // Décembre : les guirlandes.
        assertTrue(Decor.GARLANDS in SketchScenes.scene(0, true, time.withMonth(12), 2, plain, around = around).decor)
        assertTrue(SketchScenes.moves(SketchScenes.scene(0, true, time, 2, plain, around = around)))
    }

    @Test
    fun `heat, frost and hail`() {
        val time = LocalDateTime.of(2026, 7, 10, 15, 0)
        assertTrue(Surprise.HEAT in SketchScenes.scene(0, true, time, 1, conditions = SketchConditions(temperature = 35.0)).surprises)
        assertTrue(Surprise.FROST in SketchScenes.scene(0, true, time, 1, conditions = SketchConditions(temperature = -14.0)).surprises)
        assertFalse(Surprise.HEAT in SketchScenes.scene(0, true, time, 1, conditions = SketchConditions(temperature = 28.0)).surprises)
        assertTrue(SketchWeather.of(99).hail && !SketchWeather.of(95).hail)
    }

    @Test
    fun `a second chain is seen in another direction`() {
        // Depuis Valence (120 m), le Vercors à l'est ; le Massif central à 40 km à l'ouest, vers 1 400 m.
        val line = (0 until 100).map { 1400.0 * it / 99 }.toDoubleArray().also { it[0] = 120.0 }
        val profile = flat().with(Backdrop(line, 44.0, 270.0))
        val t = SketchTerrains.from(profile, 10, snowing = false)
        assertTrue(t.backdrop.isNotEmpty() && t.backdrop.maxOf { it.second } > 0.03f)
        assertTrue(SketchTerrains.from(flat(), 10, snowing = false).backdrop.isEmpty())
        // La deuxième chaîne est à au moins 60° de la première.
        assertEquals(90.0, Terrain.angleBetween(10.0, 280.0), 0.001)
        assertEquals(90.0, Terrain.bearing(45.0, 5.0, 45.0, 5.1), 0.5)
    }

    /** Une plaine à 120 m, la coupe vers l'est. */
    private fun flat() = TerrainProfile(DoubleArray(100) { 120.0 + (it % 3) }, 40.0, 25, emptyList(), emptyList(), bearing = 90.0)
}

/** Les noms des sommets et les silhouettes des villes. */
class SketchNamesTest {
    @Test
    fun `named peaks are placed on the ridge`() {
        val e = DoubleArray(100) { 500.0 + if (it in 60..70) 600.0 else 0.0 + it % 3 }
        val profile = TerrainProfile(
            e, 30.0, 25,
            listOf(io.github.pbailly.meteo.data.ProfileLabel(65, "Mont Vouillot", 1145.0), io.github.pbailly.meteo.data.ProfileLabel(80, null, 900.0)),
            emptyList(),
        )
        val t = SketchTerrains.from(profile, 10, snowing = false)
        assertEquals(listOf("Mont Vouillot"), t.labels.map { it.name })
        // Le nom est au-dessus du relief qui monte, à droite du lieu.
        val x = t.labels.first().x
        assertTrue(x > t.placeX && t.heightAt(x) > t.heightAt(t.placeX))
    }

    @Test
    fun `cities are recognised by their monuments`() {
        assertEquals(Landmark.REIMS, Landmark.near(49.258, 4.03))
        assertEquals(Landmark.LILLE, Landmark.near(50.63, 3.06))
        assertEquals(Landmark.MONT_SAINT_MICHEL, Landmark.near(48.636, -1.51))
        assertEquals(Landmark.LONDON, Landmark.near(51.507, -0.128))
        assertEquals(Landmark.EIFFEL_TOWER, Landmark.near(48.86, 2.35))
        assertNull(Landmark.near(47.06, 6.6))
        assertEquals(Silhouette.BELFRY, Landmark.ARRAS.look)
        // Le monument remplace l'église du village (pas la tour Eiffel, qui reste à la grande ville).
        val time = LocalDateTime.of(2026, 7, 10, 14, 0)
        val village = Surroundings(Settlement.HAMLET, population = 30)
        assertEquals(Landmark.MONT_SAINT_MICHEL, SketchScenes.scene(0, true, time, 1, flatTerrain(village), around = village, landmark = Landmark.MONT_SAINT_MICHEL).landmark)
        assertNull(SketchScenes.scene(0, true, time, 1, flatTerrain(village), around = village, landmark = Landmark.EIFFEL_TOWER).landmark)
    }

    private fun flatTerrain(around: Surroundings) =
        SketchTerrains.from(TerrainProfile(DoubleArray(100) { 100.0 + it % 3 }, 30.0, 25, emptyList(), emptyList(), bearing = 90.0), 7, snowing = false, around = around)
}

/** Bâtir comme dans la région du monde. */
class SketchCultureTest {
    @Test
    fun `each region builds its own way`() {
        assertEquals(Culture.EUROPE, Culture.of(47.06, 6.6))
        assertEquals(Culture.ISLAMIC, Culture.of(25.2, 55.27))
        assertEquals(Culture.ISLAMIC, Culture.of(32.89, 13.19))
        assertEquals(Culture.ISLAMIC, Culture.of(41.0, 28.97))
        assertEquals(Culture.ORTHODOX, Culture.of(55.75, 37.62))
        assertEquals(Culture.ORTHODOX, Culture.of(37.98, 23.73))
        assertEquals(Culture.EAST_ASIA, Culture.of(35.68, 139.69))
        assertEquals(Culture.EAST_ASIA, Culture.of(39.9, 116.4))
        assertEquals(Culture.BUDDHIST, Culture.of(13.75, 100.5))
        assertEquals(Culture.SOUTH_ASIA, Culture.of(28.61, 77.2))
        assertEquals(Culture.LATIN, Culture.of(19.43, -99.13))
        assertEquals(Culture.LATIN, Culture.of(-22.9, -43.2))
        assertEquals(Culture.NEW_WORLD, Culture.of(40.71, -74.0))
        assertEquals(Culture.NEW_WORLD, Culture.of(29.76, -95.37))
        assertTrue(Culture.desert(25.2, 55.27) && !Culture.desert(47.06, 6.6))
    }

    @Test
    fun `no church nor fields in dubai, no french holidays abroad`() {
        val time = LocalDateTime.of(2026, 12, 24, 22, 0)
        val around = Surroundings(Settlement.VILLAGE, forest = true, population = 900)
        val t = SketchTerrains.from(TerrainProfile(DoubleArray(100) { 10.0 + it % 3 }, 30.0, 25, emptyList(), emptyList(), bearing = 90.0), 12, snowing = false, around = around)
        val dubai = SketchScenes.scene(0, false, time, 1, t, around = around, latitude = 25.2, longitude = 55.27)
        assertEquals(Culture.ISLAMIC, dubai.culture)
        assertTrue(dubai.desert && Decor.PALMS in dubai.decor && Decor.FIELDS !in dubai.decor && Decor.FOREST !in dubai.decor)
        assertFalse(Decor.GARLANDS in dubai.decor || Surprise.SLEIGH in dubai.surprises)
        val paris = SketchScenes.scene(0, false, time, 1, t, around = around, latitude = 48.86, longitude = 2.35)
        assertTrue(Decor.GARLANDS in paris.decor && Surprise.SLEIGH in paris.surprises)
        // Le 14 Juillet ne se fête qu'en France.
        val july = LocalDateTime.of(2026, 7, 14, 23, 0)
        assertTrue(Surprise.FIREWORKS in SketchScenes.scene(0, false, july, 1, t, around = around, latitude = 48.86, longitude = 2.35).surprises)
        assertFalse(Surprise.FIREWORKS in SketchScenes.scene(0, false, july, 1, t, around = around, latitude = 40.71, longitude = -74.0).surprises)
        assertEquals(Landmark.DUBAI, Landmark.near(25.2, 55.27))
    }
}
