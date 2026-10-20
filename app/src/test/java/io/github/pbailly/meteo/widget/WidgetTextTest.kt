package io.github.pbailly.meteo.widget

import io.github.pbailly.meteo.data.Outlook
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class WidgetTextTest {

    private val today = LocalDate.of(2026, 10, 26)

    @Test
    fun `dry hours ahead`() {
        assertEquals("Pas de pluie d'ici 24 h", rainHeadline(null, today))
        assertEquals("Pas de pluie d'ici 12 h", rainHeadline(null, today, hours = 12))
    }

    @Test
    fun `rain coming today or tomorrow`() {
        val later = Outlook.NextRain(LocalDateTime.of(2026, 10, 26, 15, 0), LocalDateTime.of(2026, 10, 26, 18, 0), 2.4, snow = false)
        assertEquals("Pluie vers 15h · 2,4 mm", rainHeadline(later, today))
        val tomorrow = Outlook.NextRain(LocalDateTime.of(2026, 10, 27, 6, 0), null, 12.0, snow = true)
        assertEquals("Neige demain vers 6h · 12 mm", rainHeadline(tomorrow, today))
    }

    @Test
    fun `rain already falling`() {
        val ending = Outlook.NextRain(null, LocalDateTime.of(2026, 10, 27, 9, 0), 5.2, snow = false)
        assertEquals("Pluie jusqu'à demain 9h · 5,2 mm", rainHeadline(ending, today))
        assertEquals("Pluie continue · 0,8 mm", rainHeadline(Outlook.NextRain(null, null, 0.8, snow = false), today))
    }
}
