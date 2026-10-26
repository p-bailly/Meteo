package java.time.format

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * Motifs de date à la java.time, en français : EEEE (lundi), EEE (lun.), d, dd, MMMM (octobre), MMM (oct.), MM,
 * yyyy, HH, H, mm. Le texte entre apostrophes est recopié tel quel.
 */
class DateTimeFormatter private constructor(private val pattern: String) {
    /** Une date, une date-heure, ou un instant pour [ISO_INSTANT] (« 2026-10-26T04:50:00Z »). */
    fun format(temporal: java.time.temporal.Temporal): String = when (temporal) {
        is LocalDate -> format(temporal, null)
        is LocalDateTime -> format(temporal.toLocalDate(), temporal)
        is java.time.Instant -> {
            val t = LocalDateTime.ofInstant(temporal, java.time.ZoneOffset.UTC)
            if (this === ISO_INSTANT) {
                t.toLocalDate().toString() + "T" + listOf(t.hour, t.minute, t.second).joinToString(":") { it.toString().padStart(2, '0') } + "Z"
            } else {
                format(t.toLocalDate(), t)
            }
        }
        else -> throw IllegalArgumentException("Date non prise en charge")
    }

    internal fun format(date: LocalDate, time: LocalDateTime?): String {
        val out = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\'') {
                val end = pattern.indexOf('\'', i + 1).let { if (it < 0) pattern.length else it }
                out.append(pattern, i + 1, end)
                i = end + 1
                continue
            }
            if (!c.isLetter()) {
                out.append(c)
                i++
                continue
            }
            var n = 1
            while (i + n < pattern.length && pattern[i + n] == c) n++
            out.append(field(c, n, date, time))
            i += n
        }
        return out.toString()
    }

    private fun field(c: Char, n: Int, date: LocalDate, time: LocalDateTime?): String = when (c) {
        'E' -> if (n >= 4) DAYS[date.dayOfWeek.ordinal] else DAYS_SHORT[date.dayOfWeek.ordinal]
        'd' -> pad(date.dayOfMonth, n)
        'M', 'L' -> when {
            n >= 4 -> MONTHS[date.monthValue - 1]
            n == 3 -> MONTHS_SHORT[date.monthValue - 1]
            else -> pad(date.monthValue, n)
        }
        'y', 'u' -> if (n == 2) pad(date.year % 100, 2) else pad(date.year, n)
        'H' -> pad(requireTime(time).hour, n)
        'm' -> pad(requireTime(time).minute, n)
        's' -> pad(requireTime(time).second, n)
        else -> throw IllegalArgumentException("Motif de date non pris en charge : $c")
    }

    private fun requireTime(time: LocalDateTime?): LocalDateTime =
        time ?: throw java.time.DateTimeException("Pas d'heure dans une date seule")

    private fun pad(value: Int, n: Int) = value.toString().padStart(n, '0')

    companion object {
        private val DAYS = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
        private val DAYS_SHORT = listOf("lun.", "mar.", "mer.", "jeu.", "ven.", "sam.", "dim.")
        private val MONTHS = listOf(
            "janvier", "février", "mars", "avril", "mai", "juin",
            "juillet", "août", "septembre", "octobre", "novembre", "décembre",
        )
        private val MONTHS_SHORT = listOf(
            "janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.",
        )

        val ISO_INSTANT = DateTimeFormatter("yyyy-MM-dd'T'HH:mm:ss'Z'")

        fun ofPattern(pattern: String): DateTimeFormatter = DateTimeFormatter(pattern)
        fun ofPattern(pattern: String, locale: Locale): DateTimeFormatter = DateTimeFormatter(pattern)
    }
}
