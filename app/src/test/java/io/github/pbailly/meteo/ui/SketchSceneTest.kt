package io.github.pbailly.meteo.ui

import io.github.pbailly.meteo.data.Settlement
import io.github.pbailly.meteo.data.Surroundings
import io.github.pbailly.meteo.data.TerrainProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class SketchSceneTest {
    @Test
    fun `easter sunday follows the gregorian computus`() {
        assertEquals(LocalDate.of(2024, 3, 31), SketchScenes.easterSunday(2024))
        assertEquals(LocalDate.of(2025, 4, 20), SketchScenes.easterSunday(2025))
        assertEquals(LocalDate.of(2026, 4, 5), SketchScenes.easterSunday(2026))
        assertEquals(LocalDate.of(2027, 3, 28), SketchScenes.easterSunday(2027))
        assertEquals(LocalDate.of(2038, 4, 25), SketchScenes.easterSunday(2038))
    }

    @Test
    fun `moon phase matches known new and full moons`() {
        // Nouvelle lune le 9 novembre 2026 vers 8 h, pleine lune le 26 octobre 2026 vers 6 h (heure de Paris).
        val newMoon = SketchScenes.moonAge(LocalDateTime.of(2026, 11, 9, 8, 0))
        assertTrue(newMoon < 0.8 || newMoon > 28.7)
        assertTrue(SketchScenes.moonIllumination(newMoon) < 0.03)
        val fullMoon = SketchScenes.moonAge(LocalDateTime.of(2026, 10, 26, 6, 0))
        assertEquals(14.77, fullMoon, 0.6)
        assertTrue(SketchScenes.moonIllumination(fullMoon) > 0.97)
        // Le 20 octobre 2026, deux jours après le premier quartier : la Lune croît, éclairée aux trois quarts.
        val age = SketchScenes.moonAge(LocalDateTime.of(2026, 10, 20, 22, 0))
        assertTrue(SketchScenes.waxing(age))
        assertEquals(0.72, SketchScenes.moonIllumination(age), 0.08)
    }

    @Test
    fun `same day and place give the same drawing, tapping gives another`() {
        val time = LocalDateTime.of(2026, 10, 20, 15, 0)
        val a = SketchScenes.scene(0, true, time, placeSeed = 42)
        val b = SketchScenes.scene(0, true, time.plusMinutes(45), placeSeed = 42)
        assertEquals(a.seed, b.seed)
        assertEquals(a.decor, b.decor)
        assertEquals(a.surprises, b.surprises)
        assertTrue(SketchScenes.scene(0, true, time, placeSeed = 42, variant = 1).seed != a.seed)
        // Au fil des jours, le décor change (village, sapins…), sans sortir de ce que le relief permet.
        val plain = SketchTerrains.from(profile("paris"), 10, snowing = false)
        val decors = (0L until 60L).map { SketchScenes.scene(0, true, time.plusDays(it), placeSeed = 42, terrain = plain).decor }.toSet()
        assertTrue(decors.size >= 2)
    }

    private val profiles = Json.parseToJsonElement(javaClass.getResourceAsStream("/terrain/profiles.json")!!.readBytes().decodeToString()).jsonObject

    /** Coupes réelles (altitudes Open-Meteo vers le sommet qui domine chaque lieu). */
    private fun profile(name: String): TerrainProfile {
        val o = profiles.getValue(name).jsonObject
        return TerrainProfile(
            elevations = o.getValue("elevations").jsonArray.map { it.jsonPrimitive.double }.toDoubleArray(),
            lengthKm = o.getValue("lengthKm").jsonPrimitive.double,
            placeIndex = o.getValue("placeIndex").jsonPrimitive.int,
            labels = emptyList(),
            peaks = emptyList(),
        )
    }

    @Test
    fun `relief follows the real terrain`() {
        fun terrain(name: String, month: Int = 10) = SketchTerrains.from(profile(name), month, snowing = false)
        assertEquals(Relief.VERY_HIGH, terrain("chamonix").relief)
        // Les Préalpes autour d'Annecy (la Tournette, 2 351 m), le Haut-Doubs à 770 m : moyenne montagne.
        assertEquals(Relief.MEDIUM, terrain("annecy").relief)
        assertEquals(Relief.MEDIUM, terrain("morteau").relief)
        // Les collines de Nice, de Tromsø : basse montagne.
        assertEquals(Relief.LOW, terrain("nice").relief)
        assertEquals(Relief.LOW, terrain("tromso").relief)
        // Valence voit le Vercors à 50 km, mais elle est dans la plaine du Rhône.
        assertEquals(Relief.PLAIN, terrain("valence").relief)
        assertEquals(Relief.PLAIN, terrain("paris").relief)
        // Le Mont Blanc monte presque en haut du dessin ; Paris reste plat.
        assertTrue(terrain("chamonix").ridge.maxOf { it.second } > 0.25f)
        assertTrue(terrain("paris").ridge.maxOf { it.second } < 0.05f)
        // Sans coupe (pas encore chargée), un sol plat, sans décor inventé.
        val unknown = SketchTerrains.from(null, 10, snowing = false)
        assertEquals(Relief.PLAIN, unknown.relief)
        assertTrue(SketchScenes.scene(0, true, LocalDateTime.of(2026, 6, 1, 14, 0), 3, unknown).decor.isEmpty())
        // Asnans-Beauvoisin, dans la plaine du Doubs : la coupe va jusqu'au Jura, à 57 km, mais on est en plaine.
        assertEquals(Relief.PLAIN, terrain("asnans").relief)
    }

    /** Une coupe inventée : un sommet pointu au lieu même, les vallées 900 m plus bas des deux côtés. */
    private fun summitProfile(): TerrainProfile {
        val n = 100
        val p = 18
        val e = DoubleArray(n) { k -> 1900.0 - 900.0 * minOf(1.0, kotlin.math.abs(k - p) / 12.0) + if (k > 60) (k - 60) * 20.0 else 0.0 }
        return TerrainProfile(e, lengthKm = 25.0, placeIndex = p, labels = emptyList(), peaks = emptyList())
    }

    @Test
    fun `a place on a summit sees the land below`() {
        val t = SketchTerrains.from(summitProfile(), 7, snowing = false)
        assertEquals(Relief.SUMMIT, t.relief)
        // Le lieu est le haut du dessin, au milieu ; la croix y est toujours.
        assertTrue(t.heightAt(t.placeX) > 0.2f)
        assertTrue(t.placeX in 0.42f..0.52f)
        val days = (0L until 30L).map { LocalDateTime.of(2026, 7, 1, 14, 0).plusDays(it) }
        assertTrue(days.all { Surprise.SUMMIT_CROSS in SketchScenes.scene(0, true, it, 3, t).surprises })
    }

    @Test
    fun `tree line and snow come down towards the poles`() {
        assertEquals(1900.0, SketchTerrains.treelineAltitude(45.0), 1.0)
        assertTrue(SketchTerrains.treelineAltitude(69.6) < 500)
        assertTrue(SketchTerrains.treelineAltitude(42.0) > 2100)
        // En janvier, la neige descend jusqu'à la mer à Tromsø, pas à Nice.
        assertTrue(SketchTerrains.from(profile("tromso"), 1, snowing = false, latitude = 69.6).snowline <= 0f)
        assertTrue(SketchTerrains.from(profile("nice"), 1, snowing = false, latitude = 43.7).snowline > 0f)
        // L'été austral : janvier au sud est un mois d'été.
        assertEquals(7, SketchScenes.seasonMonth(1, -33.0))
        assertEquals(1, SketchScenes.seasonMonth(1, 45.0))
    }

    @Test
    fun `lakes and sea are found where the terrain is perfectly flat`() {
        val annecy = SketchTerrains.from(profile("annecy"), 10, snowing = false)
        assertEquals(1, annecy.water.size)
        assertFalse(annecy.sea)
        // Le lac commence au bord de la ville et s'étend vers la Tournette.
        assertTrue(annecy.water.first().start - annecy.placeX in 0f..0.03f)
        assertTrue(annecy.water.first().endInclusive - annecy.water.first().start > 0.06f)
        assertTrue(SketchTerrains.from(profile("nice"), 10, snowing = false).sea)
        assertTrue(SketchTerrains.from(profile("tromso"), 10, snowing = false).sea)
        // Les paliers du modèle de terrain (mêmes valeurs sur 100 m) ne font pas un lac.
        assertTrue(SketchTerrains.from(profile("morteau"), 10, snowing = false).water.isEmpty())
        assertTrue(SketchTerrains.from(profile("chamonix"), 10, snowing = false).water.isEmpty())
    }

    @Test
    fun `snow line follows the season and falling snow`() {
        val summer = SketchTerrains.from(profile("chamonix"), 7, snowing = false)
        val winter = SketchTerrains.from(profile("chamonix"), 1, snowing = false)
        val snowing = SketchTerrains.from(profile("chamonix"), 7, snowing = true)
        assertTrue(winter.snowline < summer.snowline)
        assertTrue(snowing.snowline <= 0f)
        // La forêt monte jusque vers 1 900 m : au-dessus de Chamonix (1 040 m), sous le sommet.
        assertTrue(summer.treeline > 0f && summer.treeline < summer.ridge.maxOf { it.second })
    }

    @Test
    fun `decor and surprises fit the terrain`() {
        val days = (0L until 200L).map { LocalDateTime.of(2026, 6, 1, 14, 0).plusDays(it) }
        val paris = SketchTerrains.from(profile("paris"), 6, snowing = false)
        val annecy = SketchTerrains.from(profile("annecy"), 6, snowing = false)
        assertTrue(days.none { Surprise.PARAGLIDER in SketchScenes.scene(0, true, it, 3, paris).surprises })
        assertTrue(days.none { Surprise.SAILBOAT in SketchScenes.scene(0, true, it, 3, paris).surprises })
        assertTrue(days.none { Surprise.SUMMIT_CROSS in SketchScenes.scene(0, true, it, 3, paris).surprises })
        assertTrue(days.all { Decor.FIELDS in SketchScenes.scene(0, true, it, 3, paris).decor })
        assertTrue(days.any { Surprise.SAILBOAT in SketchScenes.scene(0, true, it, 3, annecy).surprises })
        assertTrue(days.none { Decor.FIELDS in SketchScenes.scene(0, true, it, 3, annecy).decor })
    }

    @Test
    fun `decor follows the real place`() {
        val time = LocalDateTime.of(2026, 6, 10, 14, 0)
        val days = (0L until 60L).map { time.plusDays(it) }
        fun decors(name: String, around: Surroundings, month: Int = 6): List<Set<Decor>> {
            val t = SketchTerrains.from(profile(name), month, snowing = false, around = around)
            return days.map { SketchScenes.scene(0, true, it, 3, t, around = around).decor }
        }
        // La ville qu'il est vraiment, chaque jour.
        assertTrue(decors("paris", Surroundings(Settlement.CITY, river = true)).all { Decor.CITY in it && Decor.FIELDS !in it })
        assertTrue(decors("morteau", Surroundings(Settlement.TOWN, forest = true)).all { Decor.TOWN in it && Decor.FOREST in it })
        assertTrue(decors("annecy", Surroundings(Settlement.VILLAGE)).all { Decor.VILLAGE in it && Decor.CITY !in it })
        // En pleine campagne : une ferme et ses champs ; en montagne, un chalet ou une ferme.
        assertTrue(decors("paris", Surroundings(Settlement.NONE)).all { Decor.FARM in it && Decor.FIELDS in it })
        assertTrue(decors("chamonix", Surroundings(Settlement.NONE)).all { Decor.CHALET in it })
        // Au bord de la mer, parfois un phare ; jamais loin de l'eau.
        assertTrue(decors("nice", Surroundings(Settlement.TOWN, coast = true)).any { Decor.LIGHTHOUSE in it })
        assertTrue(decors("morteau", Surroundings(Settlement.TOWN)).none { Decor.LIGHTHOUSE in it })
    }

    @Test
    fun `water from the surroundings shows in the drawing`() {
        // Une rivière qui passe à Paris, un lac que la coupe ne traverse pas, la mer d'une île.
        assertTrue(SketchTerrains.from(profile("paris"), 6, false, around = Surroundings(Settlement.CITY, river = true)).river)
        assertFalse(SketchTerrains.from(profile("paris"), 6, false, around = Surroundings(Settlement.CITY)).river)
        val lake = SketchTerrains.from(profile("morteau"), 6, false, around = Surroundings(Settlement.TOWN, lake = true))
        assertEquals(1, lake.water.size)
        val run = lake.water.first()
        assertEquals(lake.heightAt(run.start), lake.heightAt(run.endInclusive), 0.001f)
        val island = SketchTerrains.from(profile("nice"), 6, false, around = Surroundings(Settlement.TOWN, coast = true, island = true))
        assertTrue(island.island && island.sea)
        // La terre s'enfonce dans la mer des deux côtés.
        assertTrue(island.isWater(0.27f) && island.isWater(0.75f))
        assertFalse(island.isWater(island.placeX + 0.05f))
    }

    @Test
    fun `clouds are drawn at their height`() {
        val time = LocalDateTime.of(2026, 10, 20, 15, 0)
        val chamonix = SketchTerrains.from(profile("chamonix"), 10, snowing = false)
        val paris = SketchTerrains.from(profile("paris"), 10, snowing = false)
        fun drop(t: SketchTerrain, clouds: SketchClouds?) = SketchScenes.scene(3, true, time, 1, t, conditions = SketchConditions(clouds = clouds)).cloudDrop
        val low = SketchClouds(low = 0.9f, mid = 0.2f, high = 0f)
        val mid = SketchClouds(low = 0.1f, mid = 0.9f, high = 0.3f)
        // Nuages bas en montagne : ils descendent sur les sommets ; en plaine, un peu ; moyens : à leur place.
        assertTrue(drop(chamonix, low) > 0.08f)
        assertEquals(0.05f, drop(paris, low), 0.001f)
        assertEquals(0f, drop(chamonix, mid), 0f)
        assertEquals(0f, drop(chamonix, null), 0f)
        // Seulement des nuages hauts : un voile.
        assertTrue(SketchScenes.scene(3, true, time, 1, paris, conditions = SketchConditions(clouds = SketchClouds(0.05f, 0.1f, 0.9f))).highVeil)
        assertFalse(SketchScenes.scene(3, true, time, 1, paris, conditions = SketchConditions(clouds = mid)).highVeil)
    }

    @Test
    fun `no village is made up where there is none`() {
        val days = (0L until 120L).map { LocalDateTime.of(2026, 6, 1, 14, 0).plusDays(it) }
        val homes = setOf(Decor.VILLAGE, Decor.TOWN, Decor.CITY, Decor.HAMLET, Decor.FARM, Decor.CHALET)
        // Sans les environs (OpenStreetMap injoignable), le dessin n'invente pas de village.
        for (name in listOf("paris", "morteau", "chamonix", "valence")) {
            val t = SketchTerrains.from(profile(name), 6, snowing = false)
            assertTrue(name, days.none { d -> SketchScenes.scene(0, true, d, 3, t).decor.any { it in homes } })
        }
        // Au sommet, ni village ni ferme.
        val summit = SketchTerrains.from(summitProfile(), 7, snowing = false)
        assertTrue(days.none { d -> SketchScenes.scene(0, true, d, 3, summit, around = Surroundings(Settlement.NONE)).decor.any { it in homes } })
    }

    @Test
    fun `a point at sea is drawn on the water`() {
        // En pleine mer : l'altitude est partout à 0 m.
        val sea = TerrainProfile(DoubleArray(100) { 0.0 }, 30.0, 18, emptyList(), emptyList())
        val t = SketchTerrains.from(sea, 7, snowing = false, around = Surroundings(Settlement.NONE, coast = true))
        assertTrue(t.onWater && t.sea)
        assertTrue(t.isWater(t.placeX))
        val days = (0L until 60L).map { LocalDateTime.of(2026, 7, 1, 14, 0).plusDays(it) }
        days.forEach { d ->
            val scene = SketchScenes.scene(0, true, d, 3, t, around = Surroundings(Settlement.VILLAGE, coast = true))
            assertTrue(scene.decor.none { it in setOf(Decor.VILLAGE, Decor.FARM, Decor.FIELDS, Decor.TREES, Decor.WINDSOCK) })
            assertTrue(Surprise.SAILBOAT in scene.surprises)
        }
        // Sur la terre ferme, au bord de la mer : pas sur l'eau.
        assertFalse(SketchTerrains.from(profile("nice"), 7, snowing = false).onWater)
    }

    @Test
    fun `real summit and open sea are drawn as they are`() {
        // Sur le Mont Blanc, la coupe regarde vers les Grandes Jorasses : on est au sommet, rien de bâti autour.
        val montBlanc = SketchTerrains.from(profile("montblanc"), 7, snowing = false, latitude = 45.83)
        assertEquals(Relief.SUMMIT, montBlanc.relief)
        val days = (0L until 60L).map { LocalDateTime.of(2026, 7, 1, 14, 0).plusDays(it) }
        val homes = setOf(Decor.VILLAGE, Decor.TOWN, Decor.CITY, Decor.HAMLET, Decor.FARM, Decor.CHALET, Decor.FIELDS)
        assertTrue(days.none { d -> SketchScenes.scene(0, true, d, 3, montBlanc).decor.any { it in homes } })
        assertTrue(days.none { d -> SketchScenes.scene(0, true, d, 3, montBlanc, around = Surroundings(Settlement.NONE)).decor.any { it in homes } })
        // En pleine mer, au large de la Bretagne : de l'eau partout, un voilier, pas de village.
        val ocean = SketchTerrains.from(profile("ocean"), 7, snowing = false, latitude = 47.0)
        assertTrue(ocean.onWater && ocean.sea)
        assertTrue(days.none { d -> SketchScenes.scene(0, true, d, 3, ocean).decor.any { it in homes } })
        assertTrue(days.all { d -> Surprise.SAILBOAT in SketchScenes.scene(0, true, d, 3, ocean).surprises })
    }

    @Test
    fun `the town stands at the level of the place`() {
        val day = LocalDateTime.of(2026, 6, 10, 14, 0)
        // Valence : la coupe met les collines toutes proches de la ville ; la ville reste dans la plaine.
        for ((name, settlement) in listOf("valence" to Settlement.CITY, "morteau" to Settlement.TOWN, "newyork" to Settlement.CITY)) {
            val around = Surroundings(settlement)
            val t = SketchTerrains.from(profile(name), 6, snowing = false, around = around)
            val scene = SketchScenes.scene(0, true, day, 3, t, around = around)
            val layout = scene.layout!!
            val x = layout.settlement!!
            val heights = (0..10).map { scene.terrain.heightAt(x - layout.half + 2 * layout.half * it / 10) }
            assertTrue(name, heights.all { kotlin.math.abs(it - scene.terrain.heightAt(scene.terrain.placeX)) < 0.005f })
        }
        assertEquals(Landmark.EMPIRE_STATE, Landmark.near(40.7580, -73.9855))
    }

    @Test
    fun `the bigger the population the bigger the town`() {
        val day = LocalDateTime.of(2026, 6, 10, 14, 0)
        val plain = SketchTerrains.from(profile("paris"), 6, snowing = false)
        fun half(settlement: Settlement, people: Long) =
            SketchScenes.scene(0, true, day, 3, plain, around = Surroundings(settlement, population = people)).layout!!.half
        assertTrue(half(Settlement.CITY, 8_800_000) > half(Settlement.CITY, 60_000))
        assertTrue(half(Settlement.TOWN, 40_000) > half(Settlement.TOWN, 2_500))
        assertTrue(half(Settlement.VILLAGE, 1_800) > half(Settlement.VILLAGE, 200))
    }

    @Test
    fun `light follows sunrise and sunset`() {
        val rise = LocalDateTime.of(2026, 10, 20, 7, 30)
        val set = LocalDateTime.of(2026, 10, 20, 18, 30)
        fun at(h: Int, m: Int, day: Boolean = true) = SketchScenes.lightAt(LocalDateTime.of(2026, 10, 20, h, m), day, rise, set)
        assertEquals(Light.NIGHT, at(5, 0, day = false))
        assertEquals(Light.DAWN, at(7, 0, day = false))
        assertEquals(Light.SUNRISE, at(7, 45))
        assertEquals(Light.DAY, at(12, 0))
        assertEquals(Light.SUNSET, at(18, 0))
        assertEquals(Light.DUSK, at(18, 50, day = false))
        assertEquals(Light.NIGHT, at(22, 0, day = false))
        // Sans heures du soleil : le jour ou la nuit, simplement.
        assertEquals(Light.DAY, SketchScenes.lightAt(rise, true, null, null))
    }

    @Test
    fun `wind bends the drawing its way`() {
        val time = LocalDateTime.of(2026, 10, 20, 15, 0)
        fun lean(kmh: Double, from: Double) = SketchScenes.scene(0, true, time, 1, conditions = SketchConditions(windKmh = kmh, windFrom = from)).lean
        assertEquals(1f, lean(70.0, 270.0), 0.01f)
        assertEquals(-1f, lean(70.0, 90.0), 0.01f)
        assertEquals(0f, lean(10.0, 270.0), 0f)
        assertTrue(lean(35.0, 250.0) in 0.3f..0.6f)
        val windy = SketchScenes.scene(0, true, time, 1, conditions = SketchConditions(windKmh = 30.0))
        assertTrue(Decor.WINDSOCK in windy.decor)
        assertFalse(Decor.WINDSOCK in SketchScenes.scene(0, true, time, 1).decor)
    }

    @Test
    fun `animals ski and monuments fit the place`() {
        val days = (0L until 60L).map { LocalDateTime.of(2026, 7, 1, 14, 0).plusDays(it) }
        val alps = SketchTerrains.from(profile("annecy"), 7, snowing = false)
        val chamonix = SketchTerrains.from(profile("chamonix"), 7, snowing = false)
        val nice = SketchTerrains.from(profile("nice"), 7, snowing = false)
        // Des vaches à l'alpage l'été, jamais l'hiver ni la nuit.
        assertTrue(days.any { Decor.COWS in SketchScenes.scene(0, true, it, 3, alps).decor })
        assertTrue(days.none { Decor.COWS in SketchScenes.scene(0, false, it.withHour(23), 3, alps).decor })
        assertTrue(days.none { Decor.COWS in SketchScenes.scene(0, true, it.withMonth(1), 3, alps).decor })
        assertTrue(days.any { Decor.CHAMOIS in SketchScenes.scene(0, true, it, 3, chamonix).decor })
        assertTrue(days.all { Decor.GULLS in SketchScenes.scene(0, true, it, 3, nice).decor })
        // Le télésiège seulement dans une station de ski de montagne.
        val ski = Surroundings(Settlement.TOWN, ski = true)
        assertTrue(Decor.SKI in SketchScenes.scene(0, true, days[0], 3, chamonix, around = ski).decor)
        assertFalse(Decor.SKI in SketchScenes.scene(0, true, days[0], 3, SketchTerrains.from(profile("paris"), 7, false), around = ski).decor)
        // Les monuments des grandes villes.
        assertEquals(Landmark.EIFFEL_TOWER, Landmark.near(48.8566, 2.3522))
        assertEquals(Landmark.FOURVIERE, Landmark.near(45.7640, 4.8357))
        assertEquals(null, Landmark.near(47.0577, 6.5664))
        val plain = SketchTerrains.from(profile("paris"), 7, false)
        val paris = SketchScenes.scene(0, true, days[0], 3, plain, around = Surroundings(Settlement.CITY), landmark = Landmark.EIFFEL_TOWER)
        assertEquals(Landmark.EIFFEL_TOWER, paris.landmark)
        assertEquals(null, SketchScenes.scene(0, true, days[0], 3, plain, around = Surroundings(Settlement.VILLAGE), landmark = Landmark.EIFFEL_TOWER).landmark)
    }

    @Test
    fun `snow on the ground brings the snowman`() {
        val days = (0L until 100L).map { LocalDateTime.of(2027, 1, 1, 13, 0).plusDays(it) }
        assertTrue(days.any { Surprise.SNOWMAN in SketchScenes.scene(0, true, it, 3, conditions = SketchConditions(snowOnGround = true)).surprises })
        assertTrue(days.none { Surprise.SNOWMAN in SketchScenes.scene(0, true, it, 3).surprises })
    }

    @Test
    fun `every sky has its variants and its strength`() {
        val time = LocalDateTime.of(2026, 10, 20, 15, 0)
        for (code in listOf(0, 2, 3, 45, 61, 73, 95)) {
            val variants = (0 until 40).map { SketchScenes.scene(code, true, time, 42, variant = it).skyVariant }.toSet()
            assertEquals((0 until SketchScenes.SKY_VARIANTS).toSet(), variants)
        }
        assertEquals(0, SketchWeather.of(61).intensity)
        assertEquals(2, SketchWeather.of(65).intensity)
        assertTrue(SketchWeather.of(53).drizzle)
        assertTrue(SketchWeather.of(67).freezing)
        assertTrue(SketchWeather.of(81).showers)
        assertTrue(SketchWeather.of(77).grains)
    }

    @Test
    fun `holidays bring their surprise whatever the weather`() {
        fun surprises(date: String, hour: Int, code: Int = 3, isDay: Boolean = hour in 8..18) =
            SketchScenes.scene(code, isDay, LocalDateTime.parse("${date}T%02d:00".format(hour)), placeSeed = 7).surprises
        assertTrue(Surprise.SLEIGH in surprises("2026-12-24", 23))
        // Juste après minuit, c'est encore la nuit de Noël.
        assertTrue(Surprise.SLEIGH in surprises("2026-12-25", 1))
        assertTrue(Surprise.SLEIGH in surprises("2026-12-25", 12))
        assertTrue(Surprise.FIREWORKS in surprises("2026-12-31", 23))
        assertTrue(Surprise.FIREWORKS in surprises("2027-01-01", 0))
        assertTrue(Surprise.FIREWORKS in surprises("2026-07-14", 23, code = 0))
        assertFalse(Surprise.FIREWORKS in surprises("2026-07-14", 15, code = 0))
        assertTrue(Surprise.PUMPKIN in surprises("2026-10-31", 15, code = 61))
        assertTrue(Surprise.BATS in surprises("2026-10-31", 22))
        assertTrue(Surprise.EGGS in surprises("2026-04-05", 11))
        assertTrue(Surprise.EGGS in surprises("2026-04-06", 11))
        assertTrue(Surprise.BLACK_CAT in surprises("2026-11-13", 11))
        assertTrue(Surprise.HEART in surprises("2027-02-14", 11))
        assertTrue(Surprise.FISH in surprises("2027-04-01", 11))
        assertTrue(Surprise.LILY in surprises("2027-05-01", 11))
        assertTrue(Surprise.NOTES in surprises("2027-06-21", 11))
        // Un jour ordinaire, couvert : rien de tout ça.
        val ordinary = surprises("2026-10-20", 15)
        assertTrue(ordinary.none { it in setOf(Surprise.SLEIGH, Surprise.FIREWORKS, Surprise.PUMPKIN, Surprise.EGGS, Surprise.BLACK_CAT) })
    }

    @Test
    fun `seventh tap sends the flying saucer`() {
        val time = LocalDateTime.of(2026, 10, 20, 15, 0)
        val scene = SketchScenes.scene(0, true, time, placeSeed = 42, variant = SketchScenes.ABDUCTION_TAPS)
        assertTrue(Surprise.ABDUCTION in scene.surprises)
        assertFalse((1 until SketchScenes.ABDUCTION_TAPS).any { Surprise.ABDUCTION in SketchScenes.scene(0, true, time, 42, variant = it).surprises })
    }

    @Test
    fun `weather surprises stay plausible`() {
        val days = (0L until 400L).map { LocalDateTime.of(2026, 1, 1, 14, 0).plusDays(it) }
        // Pas de bonhomme de neige sous la pluie, ni d'arc-en-ciel sans averse.
        assertTrue(days.none { Surprise.SNOWMAN in SketchScenes.scene(61, true, it, 3).surprises })
        assertTrue(days.none { Surprise.RAINBOW in SketchScenes.scene(61, true, it, 3).surprises })
        assertTrue(days.any { Surprise.RAINBOW in SketchScenes.scene(81, true, it, 3).surprises })
        assertTrue(days.none { Surprise.RAINBOW in SketchScenes.scene(81, false, it.withHour(23), 3).surprises })
        assertTrue(days.any { Surprise.SNOWMAN in SketchScenes.scene(73, true, it, 3).surprises })
        // La soucoupe et les étoiles filantes ne passent que la nuit par ciel clair.
        assertTrue(days.none { s -> SketchScenes.scene(0, true, s, 3).surprises.any { it == Surprise.UFO || it == Surprise.SHOOTING_STAR } })
        assertTrue(days.any { Surprise.UFO in SketchScenes.scene(0, false, it.withHour(23), 3).surprises })
    }
}
