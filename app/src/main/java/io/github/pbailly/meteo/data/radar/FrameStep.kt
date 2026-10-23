package io.github.pbailly.meteo.data.radar

import java.time.Instant

/**
 * Pas de la frise des cartes : une image toutes les 5, 10 ou 15 minutes. Les images sont prises de part et d'autre
 * d'une image de référence (la dernière observation), espacées d'au moins le pas ; une série déjà plus espacée
 * (satellite toutes les 10 min, prévision Météo-France toutes les 15 min) est gardée telle quelle.
 */
object FrameStep {
    val CHOICES = listOf(5, 10, 15)
    const val DEFAULT = 5

    /** Les indices de [times] (croissants) gardés au pas de [minutes], toujours avec celui de [anchor]. */
    fun keep(times: List<Instant>, minutes: Int, anchor: Int = times.lastIndex): List<Int> {
        if (times.isEmpty()) return emptyList()
        val start = anchor.coerceIn(0, times.lastIndex)
        // Une demi-minute de marge : les images radar ne tombent pas toujours à la seconde près.
        val gap = minutes * 60L - 30
        val kept = mutableListOf(start)
        var last = times[start]
        for (i in start - 1 downTo 0) {
            if (last.epochSecond - times[i].epochSecond >= gap) {
                kept.add(i)
                last = times[i]
            }
        }
        last = times[start]
        for (i in start + 1..times.lastIndex) {
            if (times[i].epochSecond - last.epochSecond >= gap) {
                kept.add(i)
                last = times[i]
            }
        }
        return kept.sorted()
    }
}
