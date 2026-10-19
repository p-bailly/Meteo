package io.github.pbailly.meteo.data.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NowcastTest {
    private val width = 512
    private val height = 384

    /** Image radar avec une zone de pluie modérée à cœur fort, centrée en (cx, cy). */
    private fun shower(cx: Int, cy: Int): IntArray = IntArray(width * height) { i ->
        val dx = i % width - cx
        val dy = i / width - cy
        val d2 = dx * dx + dy * dy
        when {
            d2 < 10 * 10 -> RainClass.HEAVY.argb
            d2 < 30 * 30 -> RainClass.MODERATE.argb
            d2 < 30 * 30 + 20 * 20 && dx > 0 -> RainClass.LIGHT.argb // traîne asymétrique
            else -> 0
        }
    }

    @Test
    fun `measures how far the shower moved`() {
        // 8 pixels vers l'est et 4 vers le sud en 10 minutes.
        val motion = Nowcast.estimateMotion(shower(200, 150), shower(208, 154), width, height, minutes = 10.0)
        assertNotNull(motion)
        val v = FloatArray(2)
        motion!!.at(208f, 154f, v)
        assertEquals(0.8f, v[0], 0.15f)
        assertEquals(0.4f, v[1], 0.15f)
        // Loin de l'averse, le mouvement est complété à partir des blocs mesurés.
        motion.at(480f, 360f, v)
        assertEquals(0.8f, v[0], 0.3f)
    }

    @Test
    fun `extrapolation moves the shower forward`() {
        val latest = shower(208, 154)
        val motion = Nowcast.estimateMotion(shower(200, 150), latest, width, height, minutes = 10.0)!!
        val in20min = Nowcast.extrapolate(latest, motion, minutes = 20.0)
        // Le cœur devrait être autour de (224, 162) dans 20 minutes.
        assertEquals(RainClass.HEAVY.argb, in20min[162 * width + 224])
        assertEquals(RainClass.HEAVY.argb, in20min[162 * width + 220])
        // Là où était le bord ouest de la zone, il ne pleut plus.
        assertEquals(0, in20min[154 * width + 180])
    }

    @Test
    fun `no rain means no motion`() {
        val empty = IntArray(width * height)
        assertNull(Nowcast.estimateMotion(empty, empty, width, height, minutes = 5.0))
    }

    @Test
    fun `full size image is processed quickly`() {
        val w = MeteoFranceRadar.IMAGE_WIDTH
        val h = 1356
        fun field(shift: Int) = IntArray(w * h) { i ->
            val x = i % w - shift
            val y = i / w
            if ((x / 40 + y / 40) % 3 == 0 && x in 200..1300 && y in 200..1100) RainClass.MODERATE.argb else 0
        }
        val start = System.nanoTime()
        val motion = Nowcast.estimateMotion(field(0), field(12), w, h, minutes = 15.0)!!
        Nowcast.extrapolate(field(12), motion, minutes = 30.0)
        val ms = (System.nanoTime() - start) / 1_000_000
        println("Prévision radar pleine taille : $ms ms")
        val v = FloatArray(2)
        motion.at(700f, 600f, v)
        assertEquals(0.8f, v[0], 0.2f)
    }
}
