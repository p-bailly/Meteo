package io.github.pbailly.meteo.widget

import io.github.pbailly.meteo.data.Outlook
import io.github.pbailly.meteo.ui.Fmt
import java.time.LocalDate

/**
 * La phrase du widget sur la pluie : « Pluie vers 15h · 2,4 mm », « Neige jusqu'à 18h », « Pas de pluie d'ici 24 h ».
 * [today] : la date du lieu, pour dire « demain » plutôt qu'une heure ambiguë.
 */
internal fun rainHeadline(rain: Outlook.NextRain?, today: LocalDate, hours: Int = 24): String {
    if (rain == null) return "Pas de pluie d'ici $hours h"
    val what = if (rain.snow) "Neige" else "Pluie"
    val start = rain.start
    val end = rain.end
    val total = " · " + Fmt.mm(rain.total)
    return when {
        start == null && end == null -> "$what continue$total"
        start == null -> "$what jusqu'à ${dayPrefix(end!!.toLocalDate(), today)}${end.hour}h$total"
        else -> "$what ${dayPrefix(start.toLocalDate(), today)}vers ${start.hour}h$total"
    }
}

private fun dayPrefix(date: LocalDate, today: LocalDate) = if (date == today) "" else "demain "
