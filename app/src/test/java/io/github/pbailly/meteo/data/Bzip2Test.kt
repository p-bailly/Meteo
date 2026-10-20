package io.github.pbailly.meteo.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Bzip2Test {

    private fun resource(name: String) = javaClass.getResourceAsStream("/bzip2/$name")!!.readBytes()

    @Test
    fun `long runs of the same byte`() {
        val expected = ("Météo à Annecy : " + "a".repeat(300) + "b".repeat(4) + "c".repeat(5) + "\n").repeat(20)
        assertEquals(expected, Bzip2.decompress(resource("runs.txt.bz2")).decodeToString())
    }

    @Test
    fun `several blocks`() {
        // Mêmes octets que ceux compressés par Python (blocs de 100 000 octets).
        val expected = ByteArray(250_000) { i -> ((((i * 7919) % 251) xor ((i / 1000) % 7)) and 0xFF).toByte() }
        assertArrayEquals(expected, Bzip2.decompress(resource("blocks.bin.bz2")))
    }

    @Test
    fun `concatenated streams`() {
        assertEquals("premier second", Bzip2.decompress(resource("concat.bz2")).decodeToString())
    }
}
