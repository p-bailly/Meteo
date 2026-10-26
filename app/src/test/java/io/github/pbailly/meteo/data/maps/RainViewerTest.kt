package io.github.pbailly.meteo.data.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RainViewerTest {
    @Test
    fun `past frames become tile templates, oldest first`() {
        val body = """
            {"version":"2.0","generated":1792989931,"host":"https://tilecache.rainviewer.com",
             "radar":{"past":[{"time":1792989600,"path":"/v2/radar/bbd918c5e1a3"},{"time":1792982400,"path":"/v2/radar/f616aeca28a6"}],
                      "nowcast":[]},
             "satellite":{"infrared":[]}}
        """.trimIndent()
        val frames = RainViewer.parse(body)
        assertEquals(listOf(Instant.ofEpochSecond(1792982400), Instant.ofEpochSecond(1792989600)), frames.map { it.time })
        assertEquals("https://tilecache.rainviewer.com/v2/radar/f616aeca28a6/256/{z}/{x}/{y}/2/1_1.png", frames[0].tileUrl)
    }

    @Test
    fun `an incomplete answer gives no frame`() {
        assertTrue(RainViewer.parse("""{"radar":{"past":[]}}""").isEmpty())
        assertTrue(RainViewer.parse("""{"host":"h","radar":{}}""").isEmpty())
    }
}
