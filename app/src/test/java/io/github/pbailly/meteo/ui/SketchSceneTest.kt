package io.github.pbailly.meteo.ui

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
        assertEquals(a.landscape, b.landscape)
        assertEquals(a.surprises, b.surprises)
        assertTrue(SketchScenes.scene(0, true, time, placeSeed = 42, variant = 1).seed != a.seed)
        // Au fil des jours, tous les paysages passent.
        val landscapes = (0L until 60L).map { SketchScenes.scene(0, true, time.plusDays(it), placeSeed = 42).landscape }.toSet()
        assertEquals(Landscape.entries.toSet(), landscapes)
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
        assertFalse((1 until SketchScenes.ABDUCTION_TAPS).any { Surprise.ABDUCTION in SketchScenes.scene(0, true, time, 42, it).surprises })
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
