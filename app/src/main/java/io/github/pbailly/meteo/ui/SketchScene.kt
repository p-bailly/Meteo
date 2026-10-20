package io.github.pbailly.meteo.ui

import io.github.pbailly.meteo.data.Settlement
import io.github.pbailly.meteo.data.Surroundings
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
import kotlin.math.sin
import kotlin.math.sqrt
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

/**
 * Où se trouve le lieu, d'après sa coupe vers le sommet qui le domine : du plus bas au plus haut, puis sur un sommet.
 * L'ordre compte ([mountains]).
 */
internal enum class Relief {
    /** Plaine, ou de basses collines (sous 600 m) : des champs, des haies, des bosquets. */
    PLAIN,
    /** Basse montagne (sommets sous 1 200 m) : des croupes rondes et boisées. */
    LOW,
    /** Moyenne montagne (jusqu'à 2 500 m, ou un lieu déjà à 700 m) : des prés, des sapins, des sommets ronds. */
    MEDIUM,
    /** Haute montagne (jusqu'à 3 500 m) : la forêt en bas, la roche, la neige en haut. */
    HIGH,
    /** Très haute montagne (au-delà) : aiguilles de roche, glaciers et neiges éternelles. */
    VERY_HIGH,
    /** Le lieu est au sommet : le relief est en dessous, la croix ou le cairn au premier plan. */
    SUMMIT;

    val mountains: Boolean get() = this >= MEDIUM
}

/** Ce qu'on pose dans le paysage, selon le lieu habité, la forêt, le relief et le jour. */
internal enum class Decor {
    // Lieux habités, du plus isolé au plus grand.
    FARM, CHALET, REFUGE, HAMLET, VILLAGE, TOWN, CITY,
    // Végétation.
    FIELDS, TREES, FIRS, FOREST,
    // Au bord de la mer.
    LIGHTHOUSE,
    // Les bêtes : vaches à l'alpage, chamois dans les rochers, mouettes sur la mer.
    COWS, CHAMOIS, GULLS,
    // Une station de ski : le télésiège, et les pistes quand il y a de la neige.
    SKI,
    // La manche à air, quand il y a du vent.
    WINDSOCK,
}

/** Les monuments de quelques grandes villes, dessinés parmi les immeubles. */
internal enum class Landmark(val latitude: Double, val longitude: Double, val radiusKm: Double) {
    EIFFEL_TOWER(48.8584, 2.2945, 12.0),
    FOURVIERE(45.7623, 4.8225, 8.0),
    BONNE_MERE(43.2840, 5.3713, 9.0),
    STRASBOURG_CATHEDRAL(48.5818, 7.7509, 7.0),
    JET_D_EAU(46.2073, 6.1557, 7.0);

    companion object {
        fun near(latitude: Double, longitude: Double): Landmark? = entries.firstOrNull {
            val k = cos(Math.toRadians((latitude + it.latitude) / 2)) * 111.2
            val dx = (longitude - it.longitude) * k
            val dy = (latitude - it.latitude) * 111.2
            sqrt(dx * dx + dy * dy) <= it.radiusKm
        }
    }
}

/** La lumière du moment : la nuit, l'aube, le lever (heure dorée), le jour, le coucher, le crépuscule. */
internal enum class Light { NIGHT, DAWN, SUNRISE, DAY, SUNSET, DUSK }

/**
 * Ce que la prévision de l'heure ajoute au dessin, en plus du code météo : la hauteur des nuages, le vent (km/h, et
 * d'où il vient en degrés), le lever et le coucher du soleil, la neige au sol, un froid qui gèle les lacs, le sable du
 * Sahara ou la pollution.
 */
data class SketchConditions(
    val clouds: SketchClouds? = null,
    val windKmh: Double = 0.0,
    val windFrom: Double = 270.0,
    val sunrise: LocalDateTime? = null,
    val sunset: LocalDateTime? = null,
    val snowOnGround: Boolean = false,
    val frozen: Boolean = false,
    val dust: Boolean = false,
    val haze: Boolean = false,
)

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
    /** La mer au premier plan, au bord du rivage. */
    val sea: Boolean,
    /** Limites de la forêt, de la roche (au-dessus des alpages), de la neige et des glaciers, à la même échelle que la
     *  crête (hors du dessin si le relief n'y monte pas). */
    val treeline: Float,
    val snowline: Float,
    val rockline: Float = treeline,
    val glacierline: Float = 2f,
    /** Position du lieu sur la crête. */
    val placeX: Float,
    /** Au-delà de cette abscisse, le relief est loin (plus de 20 km) : bleuté, sans arbres. */
    val farX: Float = 2f,
    /** Une rivière coule au pied du relief, devant le lieu. */
    val river: Boolean = false,
    /** Une île : la terre s'arrête des deux côtés, la mer tout autour. */
    val island: Boolean = false,
) {
    fun heightAt(x: Float): Float {
        val k = ridge.indexOfFirst { it.first >= x }
        if (k < 0) return ridge.last().second
        if (k == 0) return ridge.first().second
        val (x0, y0) = ridge[k - 1]
        val (x1, y1) = ridge[k]
        return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    }

    fun isWater(x: Float, margin: Float = 0f) = water.any { x in (it.start - margin)..(it.endInclusive + margin) } ||
        island && heightAt(x) <= 0f

    fun copy(
        ridge: List<Pair<Float, Float>> = this.ridge,
        water: List<ClosedFloatingPointRange<Float>> = this.water,
        sea: Boolean = this.sea,
        river: Boolean = this.river,
        island: Boolean = this.island,
    ) = SketchTerrain(ridge, relief, water, sea, treeline, snowline, rockline, glacierline, placeX, farX, river, island)
}

internal object SketchTerrains {
    /** Abscisses du début de la coupe, du lieu et de la fin de la coupe (le sommet tombe vers 0,65, avant le texte de droite). */
    private const val LEFT = 0.14f
    private const val PLACE = 0.36f
    /** Sur un sommet, le lieu est plus au milieu : la pente descend des deux côtés. */
    private const val SUMMIT_PLACE = 0.47f
    private const val RIGHT = 0.70f
    /** Le plus haut sommet monte au plus à 31 % de la hauteur du dessin au-dessus du pied. */
    const val MAX_LIFT = 0.31f
    private const val FAR_KM = 20.0
    /** Le relief qui compte pour le lieu : celui des 15 km autour (pas la montagne qu'on voit à 50 km). */
    private const val LOCAL_KM = 15.0
    /** Sur un sommet : rien de plus haut à 2,5 km, et le terrain qui tombe des deux côtés. */
    private const val SUMMIT_KM = 2.5

    /** Altitude de la neige selon le mois : basse en hiver, sur les plus hauts sommets en été (vers 45° de latitude). */
    fun snowAltitude(month: Int): Double = when (month) {
        12, 1, 2 -> 1100.0
        3, 11 -> 1500.0
        4 -> 1900.0
        5, 10 -> 2400.0
        6, 9 -> 2900.0
        else -> 3200.0
    }

    /** Plus on va vers les pôles, plus la forêt et la neige descendent : 1 900 m dans les Alpes, 300 m à Tromsø. */
    private fun latitudeShift(latitude: Double) = (abs(latitude) - 45) * 70

    fun treelineAltitude(latitude: Double) = (1900 - latitudeShift(latitude)).coerceIn(300.0, 3800.0)

    /** En attendant la coupe (ou sans réseau) : des collines douces. */
    val DEFAULT = SketchTerrain(
        ridge = listOf(
            0f to 0.02f, 0.15f to 0.04f, 0.28f to 0.06f, 0.38f to 0.12f, 0.46f to 0.09f,
            0.56f to 0.17f, 0.66f to 0.11f, 0.74f to 0.14f, 0.84f to 0.05f, 1f to 0.02f,
        ),
        relief = Relief.LOW,
        water = emptyList(),
        sea = false,
        treeline = 1f,
        snowline = 2f,
        placeX = 0.38f,
    )

    /**
     * Où est le lieu : au sommet, en plaine, ou à quel étage de la montagne. [gain] et [angle] : dénivelé jusqu'au
     * sommet qui domine le lieu, et l'angle sous lequel on le voit.
     */
    fun classify(e: DoubleArray, p: Int, kmPerSample: Double, gain: Double, angle: Double): Relief {
        val ground = e[p]
        val near = e.indices.filter { abs(it - p) * kmPerSample <= SUMMIT_KM }
        val before = near.filter { it < p }
        val after = near.filter { it > p }
        if (ground >= 400 && before.size >= 2 && after.size >= 2 && near.all { e[it] <= ground + 40 } &&
            ground - before.minOf { e[it] } >= 120 && ground - after.minOf { e[it] } >= 120
        ) {
            return Relief.SUMMIT
        }
        // Une butte lointaine ne fait pas un relief : on la voit à moins d'un degré au-dessus de l'horizon.
        if (gain < 120 || angle < 0.8) return Relief.PLAIN
        val local = e.indices.filter { abs(it - p) * kmPerSample <= LOCAL_KM }.maxOf { e[it] }
        // Des montagnes seulement au loin (Valence et le Vercors) : on est en plaine, elles restent à l'horizon.
        if (local - ground < 150 && ground < 500) return Relief.PLAIN
        val byPeaks = when {
            local < 600 -> Relief.PLAIN
            local < 1200 -> Relief.LOW
            local < 2500 -> Relief.MEDIUM
            local < 3500 -> Relief.HIGH
            else -> Relief.VERY_HIGH
        }
        // Un lieu déjà haut : au moins en moyenne montagne (le Haut-Doubs), en haute ou très haute montagne.
        val byPlace = when {
            ground >= 2800 -> Relief.VERY_HIGH
            ground >= 1600 -> Relief.HIGH
            ground >= 700 -> Relief.MEDIUM
            else -> Relief.PLAIN
        }
        return maxOf(byPeaks, byPlace)
    }

    /**
     * Le relief de la coupe [profile], la neige du mois [month] (jusqu'au lieu quand il neige, [snowing]), à la
     * [latitude] du lieu ; puis ce qui l'entoure ([around]) : un lac, la mer, une rivière, une île.
     */
    fun from(profile: TerrainProfile?, month: Int, snowing: Boolean, latitude: Double = 45.0, around: Surroundings? = null): SketchTerrain =
        shape(profile, month, snowing, latitude).with(around)

    private fun shape(profile: TerrainProfile?, month: Int, snowing: Boolean, latitude: Double): SketchTerrain {
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
        val relief = classify(e, p, kmPerSample, gain, angle)
        val summit = relief == Relief.SUMMIT
        // Au sommet, tout se mesure depuis le fond de la vallée, et le lieu est le haut du dessin.
        val base = if (summit) e.min() else ground
        val perMeter = if (summit) {
            MAX_LIFT * 0.8f / max(ground - base, 150.0)
        } else {
            max(MAX_LIFT * (1 - exp(-angle / 3.5)), 0.025) / max(gain, 60.0)
        }
        // Un relief doux est lissé ; une montagne garde ses arêtes.
        val window = when (relief) {
            Relief.PLAIN, Relief.LOW -> 2
            Relief.MEDIUM -> 1
            else -> 0
        }
        val smooth = DoubleArray(n) { k -> (k - window..k + window).filter { it in 0 until n }.map { e[it] }.average() }
        val place = if (summit) SUMMIT_PLACE else PLACE
        fun x(k: Int) = if (k < p) place - (place - LEFT) * (p - k) / p else place + (RIGHT - place) * (k - p) / max(n - 1 - p, 1)
        fun lift(v: Double) = ((v - base) * perMeter).toFloat().coerceIn(-0.05f, MAX_LIFT * 1.05f)
        val profilePoints = (0 until n).map { k -> x(k) to lift(smooth[k]) * envelope(x(k)) }
        // Au-delà de la coupe, le relief redescend doucement vers les bords (sous le texte).
        val first = profilePoints.first().second
        val last = profilePoints.last().second
        val ridge = listOf(0f to first * 0.3f, LEFT / 2 to first * 0.6f) + profilePoints +
            (1..6).map { i -> val t = i / 6f; RIGHT + (1 - RIGHT) * t to last * (1 - t) * (1 - t) }
        val runs = waterRuns(e, ground, kmPerSample)
        val water = runs.map { (a, b) -> (if (a == 0) 0f else x(a))..x(b) }
        val sea = runs.any { (a, _) -> e[a] <= 1.0 }
        val shift = latitudeShift(latitude)
        val snowAltitude = if (snowing) ground - 50 else snowAltitude(month) - shift
        val treeline = treelineAltitude(latitude)
        val far = (p until n).firstOrNull { (it - p) * kmPerSample > FAR_KM }
        fun at(altitude: Double) = ((altitude - base) * perMeter).toFloat()
        return SketchTerrain(
            ridge = ridge,
            relief = relief,
            water = water,
            sea = sea,
            treeline = at(treeline),
            snowline = at(snowAltitude),
            // Au-dessus de la forêt, les alpages ; la roche plus haut (plus tôt dans les très hautes montagnes).
            rockline = at(treeline + if (relief == Relief.MEDIUM || relief == Relief.LOW) 350.0 else 150.0),
            // Les glaciers ne fondent pas l'été : seulement dans les hautes montagnes.
            glacierline = if (relief >= Relief.HIGH) at(3000 - shift) else 2f,
            placeX = x(p),
            farX = far?.let { x(it) } ?: 2f,
        )
    }

    /** Ce que la coupe ne montre pas toujours : un lac tout près, la mer, la rivière qui passe, l'île. */
    private fun SketchTerrain.with(around: Surroundings?): SketchTerrain {
        if (around == null) return this
        var t = copy(sea = sea || around.coast, river = around.river && !sea && !around.coast, island = around.island)
        if (around.lake && t.water.isEmpty() && !t.sea) t = t.withLake()
        if (t.island) t = t.copy(ridge = t.ridge.map { (x, y) -> x to y * islandTaper(x) - 0.012f * (1 - islandTaper(x)) })
        return t
    }

    /**
     * Un lac au fond de la vallée, à côté du lieu : le plus large creux du relief (là où il ne monte pas de plus de
     * 1,5 % de la hauteur du dessin), mis bien à plat.
     */
    private fun SketchTerrain.withLake(): SketchTerrain {
        fun extent(x0: Float): Float {
            val floor = heightAt(x0)
            var x = x0
            while (x < 0.74f && x - x0 < 0.14f && heightAt(x + 0.005f) <= floor + 0.015f) x += 0.005f
            return x
        }
        val start = (0..80).map { 0.30f + it * 0.005f }.filter { it <= 0.68f }
            .maxByOrNull { x0 -> extent(x0) - x0 - abs(x0 - placeX) * 0.1f } ?: return this
        val end = extent(start).coerceAtLeast(start + 0.04f)
        val level = (0..10).minOf { heightAt(start + (end - start) * it / 10) }
        val flattened = ridge.filter { it.first < start } + listOf(start to level, end to level) + ridge.filter { it.first > end }
        return copy(ridge = flattened, water = water + listOf(start..end))
    }

    /** L'île : la terre descend dans la mer avant les bords du dessin. */
    private fun islandTaper(x: Float): Float {
        fun ramp(a: Float, b: Float) = ((x - a) / (b - a)).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        return ramp(0.27f, 0.37f) * (1 - ramp(0.67f, 0.76f))
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

/** Force de la pluie ou de la neige, et sa forme, d'après le code météo WMO. */
internal class SketchWeather(
    /** 0 faible, 1 modérée, 2 forte. */
    val intensity: Int,
    val drizzle: Boolean,
    val freezing: Boolean,
    /** Averses : un seul gros nuage, et du soleil autour. */
    val showers: Boolean,
    /** Neige en grains : des points plutôt que des flocons. */
    val grains: Boolean = false,
) {
    companion object {
        fun of(code: Int) = SketchWeather(
            intensity = when (code) {
                53, 63, 73, 81, 95 -> 1
                55, 57, 65, 67, 75, 82, 86, 96, 99 -> 2
                else -> 0
            },
            drizzle = code in 51..57,
            freezing = code in setOf(56, 57, 66, 67),
            showers = code in 80..86,
            grains = code == 77,
        )
    }
}

/**
 * Part du ciel couverte par les nuages bas (sous 2 km), moyens (2 à 6 km) et hauts (au-delà), de 0 à 1, d'après la
 * prévision de l'heure : les nuages bas coiffent les montagnes, les hauts ne sont qu'un voile ou des filaments.
 */
data class SketchClouds(val low: Float, val mid: Float, val high: Float)

internal class SketchScene(
    val sky: SketchSky,
    val terrain: SketchTerrain,
    val decor: Set<Decor>,
    val surprises: Set<Surprise>,
    val seed: Long,
    /** Quelle version du ciel : chaque temps se dessine de plusieurs façons ([SketchScenes.SKY_VARIANTS]). */
    val skyVariant: Int = 0,
    val weather: SketchWeather = SketchWeather.of(0),
    val conditions: SketchConditions = SketchConditions(),
    val light: Light = Light.DAY,
    val landmark: Landmark? = null,
) {
    val clouds: SketchClouds? get() = conditions.clouds

    /**
     * Les arbres, la fumée et la pluie penchent avec le vent : de 0 (sous 15 km/h) à 1 (60 km/h et plus), vers la
     * droite quand il vient de l'ouest (on regarde vers le nord), vers la gauche quand il vient de l'est.
     */
    val lean: Float get() {
        val strength = ((conditions.windKmh - 15) / 45).coerceIn(0.0, 1.0).toFloat()
        val toward = -sin(Math.toRadians(conditions.windFrom))
        return if (toward >= 0) strength else -strength
    }

    /** Surtout des nuages bas : ils descendent sur le relief. */
    val lowClouds: Boolean get() = clouds?.let { it.low >= 0.4f && it.low >= it.mid } == true

    /** Seulement un voile de nuages hauts : le soleil passe au travers. */
    val highVeil: Boolean get() = clouds?.let { it.high >= 0.4f && it.low < 0.25f && it.mid < 0.25f } == true

    /**
     * De combien les nuages descendent (en fraction de la hauteur du dessin) : quand ils sont bas, ils accrochent le
     * haut des montagnes (le dernier tiers des sommets disparaît dedans) ; en plaine, ils descendent un peu.
     */
    val cloudDrop: Float get() {
        if (!lowClouds) return 0f
        val top = terrain.ridge.filter { it.first in 0.40f..0.76f }.maxOfOrNull { it.second } ?: 0f
        return if (terrain.relief.mountains) ((FOOT - top * 0.7f - 0.06f) - 0.50f).coerceIn(0f, 0.15f) else 0.05f
    }
}

internal object SketchScenes {
    private const val SYNODIC_MONTH = 29.530588853

    /** Nouvelle lune du 6 janvier 2000 à 18 h 14 TU. */
    private val NEW_MOON = LocalDateTime.of(2000, 1, 6, 18, 14)

    /** Toucher le dessin autant de fois fait passer la soucoupe. */
    const val ABDUCTION_TAPS = 7

    /** Durée d'un tour de l'animation (pluie, neige, nuages). */
    const val CYCLE_MS = 20_000L

    /** Quelque chose bouge dans la scène : des nuages, de la pluie, de la neige. */
    fun moves(scene: SketchScene): Boolean = scene.sky != SketchSky.CLEAR && scene.sky != SketchSky.FOG

    /** Chaque ciel a trois versions : une autre chaque jour, et à chaque fois qu'on touche le dessin. */
    const val SKY_VARIANTS = 3

    /** Âge de la Lune, en jours depuis la dernière nouvelle lune (de 0 à 29,5). */
    fun moonAge(time: LocalDateTime): Double {
        val days = ChronoUnit.MINUTES.between(NEW_MOON, time) / 1440.0
        return ((days % SYNODIC_MONTH) + SYNODIC_MONTH) % SYNODIC_MONTH
    }

    /** Part éclairée du disque, de 0 (nouvelle lune) à 1 (pleine lune). */
    fun moonIllumination(age: Double): Double = (1 - cos(2 * PI * age / SYNODIC_MONTH)) / 2

    fun waxing(age: Double): Boolean = age < SYNODIC_MONTH / 2

    /** La lumière à [time] : 40 min d'aube avant le lever, 50 min d'heure dorée après, de même au coucher. */
    fun lightAt(time: LocalDateTime, isDay: Boolean, sunrise: LocalDateTime?, sunset: LocalDateTime?): Light {
        if (sunrise == null || sunset == null) return if (isDay) Light.DAY else Light.NIGHT
        return when {
            time.isBefore(sunrise.minusMinutes(40)) -> Light.NIGHT
            time.isBefore(sunrise) -> Light.DAWN
            time.isBefore(sunrise.plusMinutes(50)) -> Light.SUNRISE
            time.isBefore(sunset.minusMinutes(50)) -> Light.DAY
            time.isBefore(sunset) -> Light.SUNSET
            time.isBefore(sunset.plusMinutes(40)) -> Light.DUSK
            else -> Light.NIGHT
        }
    }

    /** Le mois tel que la saison le vit : dans l'hémisphère sud, janvier est un mois d'été. */
    fun seasonMonth(month: Int, latitude: Double): Int = if (latitude < 0) (month + 5) % 12 + 1 else month

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
        around: Surroundings? = null,
        conditions: SketchConditions = SketchConditions(),
        landmark: Landmark? = null,
    ): SketchScene {
        val sky = SketchSky.of(code)
        val date = time.toLocalDate()
        // Avant l'aube, la nuit compte encore pour la veille (nuit de Noël, de la Saint-Sylvestre…).
        val night = if (!isDay && time.hour < 8) date.minusDays(1) else date
        val seed = placeSeed * 1_000_003L + date.toEpochDay() * 7_919L + variant * 104_729L
        val rnd = Random(seed)
        val relief = terrain.relief
        val mountains = relief.mountains
        val skyVariant = rnd.nextInt(SKY_VARIANTS)
        val decor = decorFor(terrain, around, rnd).toMutableSet()
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
                    it < 0.52f -> if (relief != Relief.PLAIN) Surprise.PARAGLIDER else Surprise.BIRDS
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
            (sky == SketchSky.SNOW || conditions.snowOnGround && isDay && !wet) && rnd.nextFloat() < 0.5f -> Surprise.SNOWMAN
            sky == SketchSky.RAIN && rnd.nextFloat() < 0.3f -> Surprise.UMBRELLA
            else -> null
        }
        groundThing?.let(surprises::add)

        // Une averse en plein jour : souvent un arc-en-ciel.
        if (isDay && code in 80..82 && rnd.nextFloat() < 0.7f) surprises += Surprise.RAINBOW

        // Au sommet, toujours la croix (dessinée sur le lieu) ; ailleurs en montagne, parfois sur le plus haut.
        if (relief == Relief.SUMMIT || mountains && rnd.nextFloat() < 0.35f) surprises += Surprise.SUMMIT_CROSS
        // Pas de bateau ni de monstre sur un lac gelé.
        if (terrain.water.isNotEmpty() && !conditions.frozen || terrain.sea) when {
            !terrain.sea && rnd.nextFloat() < if (sky == SketchSky.FOG) 0.25f else 0.03f -> surprises += Surprise.NESSIE
            isDay && !wet && rnd.nextFloat() < 0.45f -> surprises += Surprise.SAILBOAT
        }
        // Les bêtes, selon le relief, la saison et le temps.
        val month = date.monthValue
        val calm = sky == SketchSky.CLEAR || sky == SketchSky.PARTLY || sky == SketchSky.CLOUDY
        val snowy = terrain.snowline <= 0f
        if (isDay && calm && !snowy && month in 6..9 && relief in Relief.MEDIUM..Relief.HIGH && rnd.nextFloat() < 0.6f &&
            Decor.CITY !in decor
        ) {
            decor += Decor.COWS
        }
        if (isDay && relief >= Relief.HIGH && !wet && rnd.nextFloat() < 0.4f) decor += Decor.CHAMOIS
        if (isDay && (terrain.sea || terrain.island) && sky != SketchSky.STORM) decor += Decor.GULLS
        if (around?.ski == true && relief.mountains) decor += Decor.SKI
        if (conditions.windKmh >= 15 && relief != Relief.SUMMIT) decor += Decor.WINDSOCK
        // Le monument de la grande ville, quand on y est.
        val monument = landmark?.takeIf { around == null || Decor.CITY in decor || Decor.TOWN in decor }
        val chimneys = decor.any { it in CHIMNEYS }
        if (chimneys && (!wet || sky == SketchSky.SNOW) && rnd.nextFloat() < 0.6f) surprises += Surprise.SMOKE
        return SketchScene(
            sky, terrain, decor, surprises, seed, skyVariant, SketchWeather.of(code), conditions,
            lightAt(time, isDay, conditions.sunrise, conditions.sunset), monument,
        )
    }

    /** Les maisons qui ont une cheminée qui fume (pas les immeubles de la grande ville). */
    private val CHIMNEYS = setOf(Decor.FARM, Decor.CHALET, Decor.REFUGE, Decor.HAMLET, Decor.VILLAGE, Decor.TOWN)

    /**
     * Le décor du lieu : la ville ou le village qu'il est vraiment, la forêt s'il y en a une tout près, et ce que le
     * relief permet (champs en plaine, feuillus en basse montagne, sapins plus haut). Sans [around] (pas encore chargé,
     * ou sans réseau), un peu de variété d'un jour à l'autre, d'après le seul relief.
     */
    private fun decorFor(terrain: SketchTerrain, around: Surroundings?, rnd: Random): Set<Decor> {
        val relief = terrain.relief
        val decor = mutableSetOf<Decor>()
        if (around == null) {
            when (relief) {
                Relief.PLAIN -> {
                    decor += Decor.FIELDS
                    decor += if (rnd.nextFloat() < 0.6f) Decor.VILLAGE else Decor.TREES
                }
                Relief.LOW, Relief.MEDIUM -> {
                    if (rnd.nextFloat() < 0.7f) decor += if (relief == Relief.LOW) Decor.TREES else Decor.FIRS
                    if (rnd.nextFloat() < 0.55f) decor += Decor.VILLAGE
                    if (decor.isEmpty()) decor += Decor.FIRS
                }
                Relief.HIGH, Relief.VERY_HIGH -> {
                    if (rnd.nextFloat() < 0.65f) decor += Decor.FIRS
                    when (rnd.nextFloat()) {
                        in 0f..0.35f -> decor += Decor.CHALET
                        in 0.35f..0.65f -> decor += Decor.VILLAGE
                        else -> Unit
                    }
                }
                Relief.SUMMIT -> Unit
            }
            return decor
        }
        // Au-dessus de la limite des arbres, ni forêt ni ferme : un refuge.
        val aboveTrees = terrain.treeline <= 0.005f
        when (around.settlement) {
            Settlement.CITY -> decor += Decor.CITY
            Settlement.TOWN -> decor += Decor.TOWN
            Settlement.VILLAGE -> decor += Decor.VILLAGE
            Settlement.HAMLET -> decor += Decor.HAMLET
            Settlement.NONE -> when {
                relief == Relief.SUMMIT -> if (rnd.nextFloat() < 0.4f) decor += Decor.REFUGE
                aboveTrees -> decor += Decor.REFUGE
                relief <= Relief.LOW -> decor += Decor.FARM
                relief == Relief.MEDIUM -> decor += if (rnd.nextFloat() < 0.5f) Decor.FARM else Decor.CHALET
                else -> decor += Decor.CHALET
            }
        }
        if (!aboveTrees && relief != Relief.SUMMIT) {
            if (around.forest) decor += Decor.FOREST
            when (relief) {
                Relief.PLAIN -> {
                    if (around.settlement != Settlement.CITY && !terrain.sea) decor += Decor.FIELDS
                    if (!around.forest && rnd.nextFloat() < 0.6f) decor += Decor.TREES
                }
                Relief.LOW -> if (!around.forest) decor += Decor.TREES
                else -> if (!around.forest && rnd.nextFloat() < 0.6f) decor += Decor.FIRS
            }
        }
        if ((terrain.sea || terrain.island) && around.settlement != Settlement.CITY && rnd.nextFloat() < 0.5f) decor += Decor.LIGHTHOUSE
        return decor
    }
}
