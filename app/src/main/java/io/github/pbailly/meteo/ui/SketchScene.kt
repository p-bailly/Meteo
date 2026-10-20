package io.github.pbailly.meteo.ui

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.temporal.ChronoUnit
import kotlin.math.cos
import kotlin.math.PI
import kotlin.random.Random

/** Grandes familles de ciel, d'après le code météo WMO. */
internal enum class SketchSky {
    CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, STORM;

    companion object {
        fun of(code: Int): SketchSky = when (code) {
            0, 1 -> CLEAR
            2 -> PARTLY
            3 -> CLOUDY
            45, 48 -> FOG
            71, 73, 75, 77, 85, 86 -> SNOW
            95, 96, 99 -> STORM
            in 51..67, in 80..82 -> RAIN
            else -> CLOUDY
        }
    }
}

/** Le décor sous le ciel : il change d'un jour à l'autre. */
internal enum class Landscape { MOUNTAINS, LAKE, FOREST, VILLAGE }

/** Petites surprises du dessin : certaines selon le temps, d'autres seulement certains jours. */
internal enum class Surprise {
    // Dans le ciel.
    BIRDS, PLANE, PARAGLIDER, BALLOON, SHOOTING_STAR, UFO, SLEIGH, FIREWORKS, BATS, HEART, FISH, NOTES,
    // Au premier plan.
    SNOWMAN, UMBRELLA, PUMPKIN, EGGS, BLACK_CAT, LILY,
    // Dans le paysage.
    SUMMIT_CROSS, SAILBOAT, NESSIE, SMOKE,
    // Arc-en-ciel d'averse, et la soucoupe qui emporte un mouton (à force de toucher le dessin).
    RAINBOW, ABDUCTION,
}

internal class SketchScene(val sky: SketchSky, val landscape: Landscape, val surprises: Set<Surprise>, val seed: Long)

internal object SketchScenes {
    private const val SYNODIC_MONTH = 29.530588853

    /** Nouvelle lune du 6 janvier 2000 à 18 h 14 TU. */
    private val NEW_MOON = LocalDateTime.of(2000, 1, 6, 18, 14)

    /** Toucher le dessin autant de fois fait passer la soucoupe. */
    const val ABDUCTION_TAPS = 7

    /** Âge de la Lune, en jours depuis la dernière nouvelle lune (de 0 à 29,5). */
    fun moonAge(time: LocalDateTime): Double {
        val days = ChronoUnit.MINUTES.between(NEW_MOON, time) / 1440.0
        return ((days % SYNODIC_MONTH) + SYNODIC_MONTH) % SYNODIC_MONTH
    }

    /** Part éclairée du disque, de 0 (nouvelle lune) à 1 (pleine lune). */
    fun moonIllumination(age: Double): Double = (1 - cos(2 * PI * age / SYNODIC_MONTH)) / 2

    fun waxing(age: Double): Boolean = age < SYNODIC_MONTH / 2

    /** Dimanche de Pâques (calendrier grégorien, algorithme de Meeus). */
    fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    /**
     * Le décor et les surprises du jour, pour un lieu : toujours les mêmes ce jour-là (pas de changement à chaque
     * mise à jour), différents le lendemain ou ailleurs. [variant] compte les fois où l'on a touché le dessin.
     */
    fun scene(code: Int, isDay: Boolean, time: LocalDateTime, placeSeed: Int, variant: Int = 0): SketchScene {
        val sky = SketchSky.of(code)
        val date = time.toLocalDate()
        // Avant l'aube, la nuit compte encore pour la veille (nuit de Noël, de la Saint-Sylvestre…).
        val night = if (!isDay && time.hour < 8) date.minusDays(1) else date
        val seed = placeSeed * 1_000_003L + date.toEpochDay() * 7_919L + variant * 104_729L
        val rnd = Random(seed)
        val landscape = Landscape.entries[rnd.nextInt(Landscape.entries.size)]
        val surprises = mutableSetOf<Surprise>()
        val clearish = sky == SketchSky.CLEAR || sky == SketchSky.PARTLY
        val wet = sky == SketchSky.RAIN || sky == SketchSky.STORM || sky == SketchSky.SNOW
        val day = MonthDay.from(date)
        val eve = MonthDay.from(night)
        val easter = easterSunday(date.year)

        // Dans le ciel : les fêtes d'abord, sinon un peu de vie selon le temps.
        val skyThing = when {
            variant > 0 && variant % ABDUCTION_TAPS == 0 -> Surprise.ABDUCTION
            !isDay && eve == MonthDay.of(12, 24) || day == MonthDay.of(12, 25) -> Surprise.SLEIGH
            !isDay && (eve == MonthDay.of(12, 31) || eve == MonthDay.of(7, 14)) -> Surprise.FIREWORKS
            !isDay && eve == MonthDay.of(10, 31) -> Surprise.BATS
            day == MonthDay.of(2, 14) -> Surprise.HEART
            day == MonthDay.of(4, 1) -> Surprise.FISH
            day == MonthDay.of(6, 21) -> Surprise.NOTES
            isDay && clearish -> rnd.nextFloat().let {
                when {
                    it < 0.30f -> Surprise.BIRDS
                    it < 0.42f -> Surprise.PLANE
                    it < 0.52f -> if (landscape == Landscape.MOUNTAINS || landscape == Landscape.LAKE) Surprise.PARAGLIDER else Surprise.BIRDS
                    it < 0.58f -> Surprise.BALLOON
                    else -> null
                }
            }
            !isDay && sky == SketchSky.CLEAR -> rnd.nextFloat().let {
                when {
                    it < 0.30f -> Surprise.SHOOTING_STAR
                    it < 0.34f -> Surprise.UFO
                    else -> null
                }
            }
            else -> null
        }
        skyThing?.let(surprises::add)

        // Au premier plan.
        val groundThing = when {
            skyThing == Surprise.ABDUCTION -> null
            date == easter || date == easter.plusDays(1) -> Surprise.EGGS
            day == MonthDay.of(10, 31) || eve == MonthDay.of(10, 31) -> Surprise.PUMPKIN
            date.dayOfMonth == 13 && date.dayOfWeek == DayOfWeek.FRIDAY -> Surprise.BLACK_CAT
            day == MonthDay.of(5, 1) -> Surprise.LILY
            sky == SketchSky.SNOW && rnd.nextFloat() < 0.5f -> Surprise.SNOWMAN
            sky == SketchSky.RAIN && rnd.nextFloat() < 0.3f -> Surprise.UMBRELLA
            else -> null
        }
        groundThing?.let(surprises::add)

        // Une averse en plein jour : souvent un arc-en-ciel.
        if (isDay && code in 80..82 && rnd.nextFloat() < 0.7f) surprises += Surprise.RAINBOW

        when (landscape) {
            Landscape.MOUNTAINS -> if (rnd.nextFloat() < 0.35f) surprises += Surprise.SUMMIT_CROSS
            Landscape.LAKE -> when {
                rnd.nextFloat() < if (sky == SketchSky.FOG) 0.25f else 0.03f -> surprises += Surprise.NESSIE
                isDay && !wet && rnd.nextFloat() < 0.45f -> surprises += Surprise.SAILBOAT
            }
            Landscape.VILLAGE -> if (!wet || sky == SketchSky.SNOW) {
                if (rnd.nextFloat() < 0.6f) surprises += Surprise.SMOKE
            }
            Landscape.FOREST -> Unit
        }
        return SketchScene(sky, landscape, surprises, seed)
    }
}
