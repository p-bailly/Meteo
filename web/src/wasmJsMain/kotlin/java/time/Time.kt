package java.time

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.Temporal
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

// Ce que l'appli Android utilise de java.time, réécrit pour le navigateur (calendrier grégorien, sans fuseaux nommés :
// le décalage horaire vient d'Open-Meteo, et celui du téléphone de JavaScript).

@OptIn(ExperimentalTime::class)
internal fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

private fun floorDiv(a: Long, b: Long): Long = a.floorDiv(b)
private fun floorMod(a: Long, b: Long): Long = a.mod(b)

enum class DayOfWeek {
    MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY;

    /** 1 (lundi) à 7 (dimanche). */
    val value: Int get() = ordinal + 1

    companion object {
        fun of(value: Int): DayOfWeek = entries[value - 1]
    }
}

class LocalDate private constructor(val year: Int, val monthValue: Int, val dayOfMonth: Int) : Comparable<LocalDate>, Temporal {
    fun toEpochDay(): Long {
        // Algorithme « days_from_civil » de Howard Hinnant.
        val y = (if (monthValue <= 2) year - 1 else year).toLong()
        val era = floorDiv(y, 400)
        val yoe = y - era * 400
        val mp = (monthValue + 9) % 12
        val doy = (153 * mp + 2) / 5 + dayOfMonth - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    val dayOfWeek: DayOfWeek get() = DayOfWeek.of((floorMod(toEpochDay() + 3, 7) + 1).toInt())
    val dayOfYear: Int get() = (toEpochDay() - of(year, 1, 1).toEpochDay()).toInt() + 1
    val isLeapYear: Boolean get() = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    fun lengthOfMonth(): Int = when (monthValue) {
        2 -> if (isLeapYear) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    fun plusDays(days: Long): LocalDate = if (days == 0L) this else ofEpochDay(toEpochDay() + days)
    fun plusDays(days: Int): LocalDate = plusDays(days.toLong())
    fun minusDays(days: Long): LocalDate = plusDays(-days)
    fun minusDays(days: Int): LocalDate = plusDays(-days.toLong())
    fun withDayOfMonth(day: Int): LocalDate = of(year, monthValue, day)

    fun atStartOfDay(): LocalDateTime = LocalDateTime.of(this, 0, 0, 0)
    fun atTime(hour: Int, minute: Int): LocalDateTime = LocalDateTime.of(this, hour, minute, 0)

    fun isBefore(other: LocalDate): Boolean = this < other
    fun isAfter(other: LocalDate): Boolean = this > other
    fun isEqual(other: LocalDate): Boolean = this == other

    fun format(formatter: DateTimeFormatter): String = formatter.format(this, null)

    override fun compareTo(other: LocalDate): Int = toEpochDay().compareTo(other.toEpochDay())
    override fun equals(other: Any?): Boolean = other is LocalDate && other.toEpochDay() == toEpochDay()
    override fun hashCode(): Int = toEpochDay().hashCode()
    override fun toString(): String =
        "$year-${monthValue.toString().padStart(2, '0')}-${dayOfMonth.toString().padStart(2, '0')}"

    companion object {
        fun of(year: Int, month: Int, day: Int): LocalDate {
            require(month in 1..12) { "Mois invalide : $month" }
            val d = LocalDate(year, month, 1)
            require(day in 1..d.lengthOfMonth()) { "Jour invalide : $day" }
            return LocalDate(year, month, day)
        }

        fun ofEpochDay(epochDay: Long): LocalDate {
            val z = epochDay + 719468
            val era = floorDiv(z, 146097)
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
            return LocalDate(y, m, d)
        }

        /** « 2026-10-25 ». */
        fun parse(text: CharSequence): LocalDate {
            val s = text.toString()
            val parts = s.split('-')
            if (parts.size != 3) throw DateTimeException("Date illisible : $s")
            return of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }

        fun now(): LocalDate = LocalDateTime.now().toLocalDate()
        fun now(zone: ZoneId): LocalDate = LocalDateTime.now(zone).toLocalDate()
    }
}

class LocalDateTime private constructor(
    private val date: LocalDate,
    val hour: Int,
    val minute: Int,
    val second: Int,
) : Comparable<LocalDateTime>, Temporal {
    val year: Int get() = date.year
    val monthValue: Int get() = date.monthValue
    val dayOfMonth: Int get() = date.dayOfMonth
    val dayOfWeek: DayOfWeek get() = date.dayOfWeek
    val dayOfYear: Int get() = date.dayOfYear

    fun toLocalDate(): LocalDate = date

    /** Secondes depuis le 1er janvier 1970 à minuit, heure locale. */
    internal fun localSeconds(): Long = date.toEpochDay() * 86_400 + hour * 3600L + minute * 60L + second

    fun toEpochSecond(offset: ZoneOffset): Long = localSeconds() - offset.totalSeconds

    fun plusSeconds(seconds: Long): LocalDateTime = if (seconds == 0L) this else ofLocalSeconds(localSeconds() + seconds)
    fun plusMinutes(minutes: Long): LocalDateTime = plusSeconds(minutes * 60)
    fun plusHours(hours: Long): LocalDateTime = plusSeconds(hours * 3600)
    fun plusDays(days: Long): LocalDateTime = plusSeconds(days * 86_400)
    fun minusSeconds(seconds: Long): LocalDateTime = plusSeconds(-seconds)
    fun minusMinutes(minutes: Long): LocalDateTime = plusSeconds(-minutes * 60)
    fun minusHours(hours: Long): LocalDateTime = plusSeconds(-hours * 3600)
    fun minusDays(days: Long): LocalDateTime = plusSeconds(-days * 86_400)
    fun plusSeconds(seconds: Int): LocalDateTime = plusSeconds(seconds.toLong())
    fun plusMinutes(minutes: Int): LocalDateTime = plusMinutes(minutes.toLong())
    fun plusHours(hours: Int): LocalDateTime = plusHours(hours.toLong())
    fun plusDays(days: Int): LocalDateTime = plusDays(days.toLong())
    fun minusSeconds(seconds: Int): LocalDateTime = minusSeconds(seconds.toLong())
    fun minusMinutes(minutes: Int): LocalDateTime = minusMinutes(minutes.toLong())
    fun minusHours(hours: Int): LocalDateTime = minusHours(hours.toLong())
    fun minusDays(days: Int): LocalDateTime = minusDays(days.toLong())

    fun withHour(hour: Int): LocalDateTime = LocalDateTime(date, hour, minute, second)
    fun withMinute(minute: Int): LocalDateTime = LocalDateTime(date, hour, minute, second)
    fun withSecond(second: Int): LocalDateTime = LocalDateTime(date, hour, minute, second)

    fun truncatedTo(unit: ChronoUnit): LocalDateTime = when (unit) {
        ChronoUnit.SECONDS -> this
        ChronoUnit.MINUTES -> LocalDateTime(date, hour, minute, 0)
        ChronoUnit.HOURS -> LocalDateTime(date, hour, 0, 0)
        ChronoUnit.DAYS -> LocalDateTime(date, 0, 0, 0)
    }

    fun atZone(zone: ZoneId): ZonedDateTime = ZonedDateTime(this)

    fun isBefore(other: LocalDateTime): Boolean = this < other
    fun isAfter(other: LocalDateTime): Boolean = this > other
    fun isEqual(other: LocalDateTime): Boolean = this == other

    fun format(formatter: DateTimeFormatter): String = formatter.format(date, this)

    override fun compareTo(other: LocalDateTime): Int = localSeconds().compareTo(other.localSeconds())
    override fun equals(other: Any?): Boolean = other is LocalDateTime && other.localSeconds() == localSeconds()
    override fun hashCode(): Int = localSeconds().hashCode()
    override fun toString(): String = "$date" + "T" + hour.toString().padStart(2, '0') + ":" +
        minute.toString().padStart(2, '0') + if (second != 0) ":" + second.toString().padStart(2, '0') else ""

    companion object {
        internal fun of(date: LocalDate, hour: Int, minute: Int, second: Int): LocalDateTime {
            require(hour in 0..23 && minute in 0..59 && second in 0..59) { "Heure invalide" }
            return LocalDateTime(date, hour, minute, second)
        }

        fun of(year: Int, month: Int, day: Int, hour: Int, minute: Int): LocalDateTime =
            of(LocalDate.of(year, month, day), hour, minute, 0)

        fun of(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): LocalDateTime =
            of(LocalDate.of(year, month, day), hour, minute, second)

        fun of(date: LocalDate, time: LocalTime): LocalDateTime = of(date, time.hour, time.minute, time.second)

        internal fun ofLocalSeconds(seconds: Long): LocalDateTime {
            val day = floorDiv(seconds, 86_400)
            val rest = (seconds - day * 86_400).toInt()
            return LocalDateTime(LocalDate.ofEpochDay(day), rest / 3600, rest / 60 % 60, rest % 60)
        }

        fun ofEpochSecond(epochSecond: Long, nanos: Int, offset: ZoneOffset): LocalDateTime =
            ofLocalSeconds(epochSecond + offset.totalSeconds)

        fun ofInstant(instant: Instant, zone: ZoneId): LocalDateTime =
            ofLocalSeconds(instant.epochSecond + zone.offsetSecondsAt(instant.epochSecond))

        /** « 2026-10-25T14:00 » ou « 2026-10-25T14:00:30 ». */
        fun parse(text: CharSequence): LocalDateTime {
            val s = text.toString()
            val t = s.indexOf('T')
            if (t < 0) throw DateTimeException("Heure illisible : $s")
            val clock = s.substring(t + 1).split(':')
            if (clock.size < 2) throw DateTimeException("Heure illisible : $s")
            val second = clock.getOrNull(2)?.substringBefore('.')?.toInt() ?: 0
            return of(LocalDate.parse(s.substring(0, t)), clock[0].toInt(), clock[1].toInt(), second)
        }

        fun now(): LocalDateTime = now(ZoneId.systemDefault())
        fun now(zone: ZoneId): LocalDateTime = ofInstant(Instant.now(), zone)
    }
}

class LocalTime private constructor(val hour: Int, val minute: Int, val second: Int) {
    companion object {
        fun of(hour: Int, minute: Int): LocalTime = LocalTime(hour, minute, 0)
        val MIDNIGHT = LocalTime(0, 0, 0)
        val NOON = LocalTime(12, 0, 0)
    }
}

class Instant private constructor(private val millis: Long) : Comparable<Instant>, Temporal {
    val epochSecond: Long get() = floorDiv(millis, 1000)
    fun toEpochMilli(): Long = millis
    fun atZone(zone: ZoneId): ZonedDateTime = ZonedDateTime(LocalDateTime.ofInstant(this, zone))
    fun plusSeconds(seconds: Long): Instant = Instant(millis + seconds * 1000)
    fun isBefore(other: Instant): Boolean = millis < other.millis
    fun isAfter(other: Instant): Boolean = millis > other.millis
    override fun compareTo(other: Instant): Int = millis.compareTo(other.millis)
    override fun equals(other: Any?): Boolean = other is Instant && other.millis == millis
    override fun hashCode(): Int = millis.hashCode()

    companion object {
        fun now(): Instant = Instant(nowMillis())
        fun ofEpochMilli(millis: Long): Instant = Instant(millis)
        fun ofEpochSecond(seconds: Long): Instant = Instant(seconds * 1000)
    }
}

class ZonedDateTime internal constructor(private val local: LocalDateTime) {
    fun toLocalDateTime(): LocalDateTime = local
    fun toLocalDate(): LocalDate = local.toLocalDate()
    val hour: Int get() = local.hour
    val minute: Int get() = local.minute
    fun format(formatter: DateTimeFormatter): String = local.format(formatter)
}

/** Fuseau : soit un décalage fixe ([ZoneOffset]), soit celui du téléphone (d'après JavaScript, heure d'été comprise). */
abstract class ZoneId {
    abstract fun offsetSecondsAt(epochSecond: Long): Int

    companion object {
        fun systemDefault(): ZoneId = SystemZone
    }
}

private fun jsTimezoneOffsetMinutes(millis: Double): Int = js("new Date(millis).getTimezoneOffset()")

private object SystemZone : ZoneId() {
    override fun offsetSecondsAt(epochSecond: Long): Int = -jsTimezoneOffsetMinutes(epochSecond * 1000.0) * 60
}

class ZoneOffset private constructor(val totalSeconds: Int) : ZoneId() {
    override fun offsetSecondsAt(epochSecond: Long): Int = totalSeconds
    override fun equals(other: Any?): Boolean = other is ZoneOffset && other.totalSeconds == totalSeconds
    override fun hashCode(): Int = totalSeconds

    companion object {
        val UTC = ZoneOffset(0)
        fun ofTotalSeconds(seconds: Int): ZoneOffset = if (seconds == 0) UTC else ZoneOffset(seconds)
        fun ofHours(hours: Int): ZoneOffset = ofTotalSeconds(hours * 3600)
    }
}

class Duration private constructor(val seconds: Long) : Comparable<Duration> {
    fun toMinutes(): Long = seconds / 60
    fun toHours(): Long = seconds / 3600
    fun toMillis(): Long = seconds * 1000
    val isNegative: Boolean get() = seconds < 0
    override fun compareTo(other: Duration): Int = seconds.compareTo(other.seconds)

    companion object {
        fun between(start: Temporal, end: Temporal): Duration = Duration(ChronoUnit.SECONDS.between(start, end))
        fun ofMinutes(minutes: Long): Duration = Duration(minutes * 60)
        fun ofHours(hours: Long): Duration = Duration(hours * 3600)
        fun ofSeconds(seconds: Long): Duration = Duration(seconds)
    }
}

class MonthDay private constructor(val monthValue: Int, val dayOfMonth: Int) : Comparable<MonthDay> {
    fun isBefore(other: MonthDay): Boolean = this < other
    fun isAfter(other: MonthDay): Boolean = this > other
    fun atYear(year: Int): LocalDate = LocalDate.of(year, monthValue, minOf(dayOfMonth, LocalDate.of(year, monthValue, 1).lengthOfMonth()))
    override fun compareTo(other: MonthDay): Int = (monthValue * 32 + dayOfMonth).compareTo(other.monthValue * 32 + other.dayOfMonth)
    override fun equals(other: Any?): Boolean = other is MonthDay && compareTo(other) == 0
    override fun hashCode(): Int = monthValue * 32 + dayOfMonth

    companion object {
        fun of(month: Int, day: Int): MonthDay = MonthDay(month, day)
        fun from(temporal: Temporal): MonthDay = when (temporal) {
            is LocalDate -> MonthDay(temporal.monthValue, temporal.dayOfMonth)
            is LocalDateTime -> MonthDay(temporal.monthValue, temporal.dayOfMonth)
            else -> throw DateTimeException("MonthDay.from : type non pris en charge")
        }
    }
}

open class DateTimeException(message: String) : RuntimeException(message)
