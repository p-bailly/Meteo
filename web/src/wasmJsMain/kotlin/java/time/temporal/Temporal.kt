package java.time.temporal

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** Une date, une date-heure ou un instant. */
interface Temporal

enum class ChronoUnit(private val seconds: Long) {
    SECONDS(1), MINUTES(60), HOURS(3600), DAYS(86_400);

    /** Nombre d'unités entières de [start] à [end] (tronqué vers zéro, comme java.time). */
    fun between(start: Temporal, end: Temporal): Long {
        if (this == DAYS && start is LocalDate && end is LocalDate) return end.toEpochDay() - start.toEpochDay()
        return (secondsOf(end) - secondsOf(start)) / seconds
    }

    private fun secondsOf(t: Temporal): Long = when (t) {
        is LocalDate -> t.toEpochDay() * 86_400
        is LocalDateTime -> t.toEpochSecond(java.time.ZoneOffset.UTC)
        is Instant -> t.epochSecond
        else -> error("Type de date non pris en charge")
    }
}
