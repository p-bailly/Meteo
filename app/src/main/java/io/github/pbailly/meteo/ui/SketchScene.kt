package io.github.pbailly.meteo.ui

import io.github.pbailly.meteo.data.TerrainProfile
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
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

/** Le relief autour du lieu, d'après sa coupe vers le sommet qui le domine. */
internal enum class Relief { FLAT, HILLS, MOUNTAINS, HIGH_MOUNTAINS }

/** Ce qu'on pose dans le paysage, selon le relief et le jour. */
internal enum class Decor { VILLAGE, CHALET, FIRS, TREES, FIELDS }

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

/**
 * Le relief tel que le dessin le montre : la coupe du terrain (celle de Neige & altitude), du lieu vers le sommet qui
 * le domine, ramenée à l'espace du dessin. Hauteurs en fraction de la hauteur du dessin, au-dessus du pied du relief.
 */
internal class SketchTerrain(
    /** Crête de gauche à droite : (x en fraction de la largeur, hauteur). */
    val ridge: List<Pair<Float, Float>>,
    val relief: Relief,
    /** Lacs ou mer traversés par la coupe, de x à x. */
    val water: List<ClosedFloatingPointRange<Float>>,
    val sea: Boolean,
    /** Limite des forêts et limite de la neige, à la même échelle que la crête (hors du dessin si le relief n'y monte pas). */
    val treeline: Float,
    val snowline: Float,
    /** Position du lieu sur la crête. */
    val placeX: Float,
    /** Au-delà de cette abscisse, le relief est loin (plus de 20 km) : bleuté, sans arbres. */
    val farX: Float = 2f,
) {
    fun heightAt(x: Float): Float {
        val k = ridge.indexOfFirst { it.first >= x }
        if (k <= 0) return ridge.first().second
        val (x0, y0) = ridge[k - 1]
        val (x1, y1) = ridge[k]
        return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    }

    fun isWater(x: Float, margin: Float = 0f) = water.any { x in (it.start - margin)..(it.endInclusive + margin) }
}

internal object SketchTerrains {
    /** Abscisses du début de la coupe, du lieu et de la fin de la coupe (le sommet tombe vers 0,65, avant le texte de droite). */
    private const val LEFT = 0.14f
    private const val PLACE = 0.36f
    private const val RIGHT = 0.70f
    /** Le plus haut sommet monte au plus à 31 % de la hauteur du dessin au-dessus du pied. */
    const val MAX_LIFT = 0.31f
    private const val TREELINE_M = 1900.0
    private const val FAR_KM = 20.0

    /** Altitude de la neige selon le mois : basse en hiver, sur les plus hauts sommets en été. */
    fun snowAltitude(month: Int): Double = when (month) {
        12, 1, 2 -> 1100.0
        3, 11 -> 1500.0
        4 -> 1900.0
        5, 10 -> 2400.0
        6, 9 -> 2900.0
        else -> 3200.0
    }

    /** En attendant la coupe (ou sans réseau) : des collines douces. */
    val DEFAULT = SketchTerrain(
        ridge = listOf(
            0f to 0.02f, 0.15f to 0.04f, 0.28f to 0.06f, 0.38f to 0.12f, 0.46f to 0.09f,
            0.56f to 0.17f, 0.66f to 0.11f, 0.74f to 0.14f, 0.84f to 0.05f, 1f to 0.02f,
        ),
        relief = Relief.HILLS,
        water = emptyList(),
        sea = false,
        treeline = 1f,
        snowline = 2f,
        placeX = 0.38f,
    )

    /** Le relief de la coupe [profile], la neige du mois [month] (jusqu'au lieu quand il neige, [snowing]). */
    fun from(profile: TerrainProfile?, month: Int, snowing: Boolean): SketchTerrain {
        val e = profile?.elevations ?: return DEFAULT
        val n = e.size
        if (n < 10 || e.any { it.isNaN() }) return DEFAULT
        val p = profile.placeIndex.coerceIn(0, n - 1)
        val ground = e[p]
        val top = (p until n).maxBy { e[it] }
        val gain = max(e[top] - ground, 0.0)
        val kmPerSample = profile.lengthKm / (n - 1)
        // Hauteur dessinée du sommet : d'après l'angle sous lequel on le voit du lieu (sa raideur apparente).
        val distanceKm = max((top - p) * kmPerSample, 0.3)
        val angle = Math.toDegrees(atan(gain / (distanceKm * 1000)))
        val height = max(MAX_LIFT * (1 - exp(-angle / 3.5)), 0.025).toFloat()
        val perMeter = height / max(gain, 60.0)
        val relief = when {
            // Une butte lointaine ne fait pas un relief : on la voit à moins d'un degré au-dessus de l'horizon.
            gain < 120 || angle < 0.8 -> Relief.FLAT
            e[top] < 1500 || gain < 500 -> Relief.HILLS
            e[top] >= 2800 && gain >= 1200 -> Relief.HIGH_MOUNTAINS
            else -> Relief.MOUNTAINS
        }
        val sharp = relief == Relief.MOUNTAINS || relief == Relief.HIGH_MOUNTAINS
        // Un relief doux est lissé ; une montagne garde ses arêtes.
        val window = if (sharp) 0 else 2
        val smooth = DoubleArray(n) { k -> (k - window..k + window).filter { it in 0 until n }.map { e[it] }.average() }
        fun x(k: Int) = if (k < p) PLACE - (PLACE - LEFT) * (p - k) / p else PLACE + (RIGHT - PLACE) * (k - p) / max(n - 1 - p, 1)
        fun lift(v: Double) = ((v - ground) * perMeter).toFloat().coerceIn(-0.05f, MAX_LIFT * 1.05f)
        val profilePoints = (0 until n).map { k -> x(k) to lift(smooth[k]) * envelope(x(k)) }
        // Au-delà de la coupe, le relief redescend doucement vers les bords (sous le texte).
        val first = profilePoints.first().second
        val last = profilePoints.last().second
        val ridge = listOf(0f to first * 0.3f, LEFT / 2 to first * 0.6f) + profilePoints +
            (1..6).map { i -> val t = i / 6f; RIGHT + (1 - RIGHT) * t to last * (1 - t) * (1 - t) }
        val water = waterRuns(e, ground, kmPerSample).map { (a, b) -> (if (a == 0) 0f else x(a))..x(b) }
        val sea = waterRuns(e, ground, kmPerSample).any { (a, _) -> e[a] <= 1.0 }
        val snowAltitude = if (snowing) ground - 50 else snowAltitude(month)
        val far = (p until n).firstOrNull { (it - p) * kmPerSample > FAR_KM }
        return SketchTerrain(
            ridge = ridge,
            relief = relief,
            water = water,
            sea = sea,
            treeline = ((TREELINE_M - ground) * perMeter).toFloat(),
            snowline = ((snowAltitude - ground) * perMeter).toFloat(),
            placeX = x(p),
            farX = far?.let { x(it) } ?: 2f,
        )
    }

    /** Les côtés du dessin restent bas : à gauche la grande température, à droite le ciel et les extrêmes. */
    private fun envelope(x: Float): Float = when {
        x < 0.36f -> {
            val t = ((x - 0.10f) / 0.26f).coerceIn(0f, 1f)
            0.35f + 0.65f * t * t * (3 - 2 * t)
        }
        else -> 1f
    }

    /**
     * Lacs et mer : le modèle de terrain les rend parfaitement plats (le lac d'Annecy à 446 m, la mer à 0 m). Une suite
     * d'au moins trois points à la même altitude sur au moins 800 m, pas très au-dessus du lieu, est de l'eau.
     */
    fun waterRuns(e: DoubleArray, ground: Double, kmPerSample: Double): List<Pair<Int, Int>> {
        val runs = ArrayList<Pair<Int, Int>>()
        var start = 0
        for (k in 1..e.size) {
            if (k < e.size && abs(e[k] - e[start]) < 0.01) continue
            val end = k - 1
            if (end - start + 1 >= 3 && (end - start) * kmPerSample >= 0.8 && e[start] - ground < 150) runs += start to end
            start = k
        }
        return runs
    }
}

internal class SketchScene(
    val sky: SketchSky,
    val terrain: SketchTerrain,
    val decor: Set<Decor>,
    val surprises: Set<Surprise>,
    val seed: Long,
)

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
     * Le décor et les surprises du jour, pour un lieu et son relief : toujours les mêmes ce jour-là (pas de changement à
     * chaque mise à jour), différents le lendemain. [variant] compte les fois où l'on a touché le dessin.
     */
    fun scene(
        code: Int,
        isDay: Boolean,
        time: LocalDateTime,
        placeSeed: Int,
        terrain: SketchTerrain = SketchTerrains.DEFAULT,
        variant: Int = 0,
    ): SketchScene {
        val sky = SketchSky.of(code)
        val date = time.toLocalDate()
        // Avant l'aube, la nuit compte encore pour la veille (nuit de Noël, de la Saint-Sylvestre…).
        val night = if (!isDay && time.hour < 8) date.minusDays(1) else date
        val seed = placeSeed * 1_000_003L + date.toEpochDay() * 7_919L + variant * 104_729L
        val rnd = Random(seed)
        val relief = terrain.relief
        val mountains = relief == Relief.MOUNTAINS || relief == Relief.HIGH_MOUNTAINS
        val decor = decorFor(relief, rnd)
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
                    // Le parapente part d'un relief.
                    it < 0.52f -> if (relief != Relief.FLAT) Surprise.PARAGLIDER else Surprise.BIRDS
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

        if (mountains && rnd.nextFloat() < 0.35f) surprises += Surprise.SUMMIT_CROSS
        if (terrain.water.isNotEmpty()) when {
            !terrain.sea && rnd.nextFloat() < if (sky == SketchSky.FOG) 0.25f else 0.03f -> surprises += Surprise.NESSIE
            isDay && !wet && rnd.nextFloat() < 0.45f -> surprises += Surprise.SAILBOAT
        }
        if ((Decor.VILLAGE in decor || Decor.CHALET in decor) && (!wet || sky == SketchSky.SNOW) && rnd.nextFloat() < 0.6f) {
            surprises += Surprise.SMOKE
        }
        return SketchScene(sky, terrain, decor, surprises, seed)
    }

    /** Un peu de variété d'un jour à l'autre, sans sortir de ce que le relief permet. */
    private fun decorFor(relief: Relief, rnd: Random): Set<Decor> {
        val decor = mutableSetOf<Decor>()
        when (relief) {
            Relief.FLAT -> {
                decor += Decor.FIELDS
                decor += if (rnd.nextFloat() < 0.6f) Decor.VILLAGE else Decor.TREES
            }
            Relief.HILLS -> {
                if (rnd.nextFloat() < 0.7f) decor += Decor.FIRS
                if (rnd.nextFloat() < 0.55f) decor += Decor.VILLAGE
                if (decor.isEmpty()) decor += Decor.TREES
            }
            Relief.MOUNTAINS, Relief.HIGH_MOUNTAINS -> {
                if (rnd.nextFloat() < 0.65f) decor += Decor.FIRS
                when (rnd.nextFloat()) {
                    in 0f..0.35f -> decor += Decor.CHALET
                    in 0.35f..0.65f -> decor += Decor.VILLAGE
                    else -> Unit
                }
            }
        }
        return decor
    }
}
