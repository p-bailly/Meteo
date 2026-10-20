package io.github.pbailly.meteo.ui

import io.github.pbailly.meteo.data.Settlement
import io.github.pbailly.meteo.data.Backdrop
import io.github.pbailly.meteo.data.Surroundings
import io.github.pbailly.meteo.data.TerrainProfile
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
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
    // Les cultures : vignes, vergers, lavande de Provence en été, tournesols du Sud-Ouest.
    VINES, ORCHARD, LAVENDER, SUNFLOWERS,
    // Le patrimoine : un château, des ruines, une abbaye.
    CASTLE, RUINS, ABBEY,
    // Des éoliennes qui tournent avec le vent, le train dans la vallée, le port et ses bateaux.
    TURBINES, TRAIN, HARBOUR,
    // Les guirlandes et le sapin de décembre, les réverbères du soir.
    GARLANDS, LAMPS,
    // Le désert : des palmiers (à la place des arbres), des dromadaires.
    PALMS, CAMELS,
    // Des îles au large (les îles d'Hyères).
    ISLANDS,
}

/**
 * La silhouette d'un monument : dessiné pour lui seul ([OWN]), ou d'une famille de monuments (cathédrale à deux tours,
 * à deux flèches, à flèche centrale, beffroi, dôme, tour de télévision, remparts…), dessinée à la manière du lieu.
 */
internal enum class Silhouette {
    /** Pas de monument : un lieu dont on connaît seulement les environs ([Landmark.known]). */
    NONE,
    OWN, TWO_TOWERS, TWO_SPIRES, CENTRAL_SPIRE, BELFRY, BRICK_BELFRY, DOME, NEEDLE, RAMPARTS, MOUNT, CLOCK_TOWER, LEANING_TOWER,
    SPIRES, ATOMIUM, OCTAGON, BURJ, GREAT_MOSQUE, ONIONS, RED_TOWER, TAJ, PYRAMIDS, OPERA, STATUE_ON_HILL,
    /** Morteau : l'église au clocher comtois et le château Pertusier. */
    PERTUSIER,
    /** Valence : la cathédrale Saint-Apollinaire et son clocher-porche. */
    APOLLINAIRE,
    /** Besançon : la citadelle de Vauban sur sa colline, au-dessus de la boucle du Doubs. */
    CITADEL,
}

/**
 * Les monuments qui font la silhouette d'une ville, vus de loin : dessinés parmi les immeubles d'une grande ville, ou
 * à la place de l'église d'une petite ville ou d'un village.
 */
internal enum class Landmark(
    val latitude: Double,
    val longitude: Double,
    val radiusKm: Double,
    val look: Silhouette = Silhouette.OWN,
    /** Ce que l'on sait de ses environs, sûr, même sans OpenStreetMap (et pour dessiner tout de suite). */
    val known: Surroundings? = null,
) {
    EIFFEL_TOWER(48.8584, 2.2945, 12.0),
    FOURVIERE(45.7623, 4.8225, 8.0),
    BONNE_MERE(43.2840, 5.3713, 9.0),
    STRASBOURG_CATHEDRAL(48.5818, 7.7509, 7.0),
    JET_D_EAU(46.2073, 6.1557, 7.0),
    EMPIRE_STATE(40.7484, -73.9857, 15.0),

    // Les cathédrales.
    REIMS(49.2536, 4.0341, 8.0, Silhouette.TWO_TOWERS),
    AMIENS(49.8945, 2.3022, 7.0, Silhouette.TWO_TOWERS),
    METZ(49.1202, 6.1757, 7.0, Silhouette.CENTRAL_SPIRE),
    BOURGES(47.0822, 2.3995, 6.0, Silhouette.TWO_TOWERS),
    ORLEANS(47.9017, 1.9101, 7.0, Silhouette.TWO_TOWERS),
    NANTES(47.2181, -1.5508, 9.0, Silhouette.TWO_TOWERS),
    TOURS(47.3956, 0.6926, 7.0, Silhouette.TWO_TOWERS),
    CHARTRES(48.4477, 1.4878, 6.0, Silhouette.TWO_SPIRES),
    CLERMONT(45.7788, 3.0860, 8.0, Silhouette.TWO_SPIRES),
    COLOGNE(50.9413, 6.9583, 10.0, Silhouette.TWO_SPIRES),
    ROUEN(49.4402, 1.0951, 8.0, Silhouette.CENTRAL_SPIRE),
    BORDEAUX(44.8358, -0.5730, 10.0, Silhouette.CENTRAL_SPIRE),
    LOURDES(43.0976, -0.0587, 4.0, Silhouette.CENTRAL_SPIRE),
    QUIMPER(47.9960, -4.1024, 5.0, Silhouette.TWO_SPIRES),

    // Les beffrois du Nord et de Belgique, la cathédrale de brique d'Albi.
    LILLE(50.6308, 3.0705, 9.0, Silhouette.BELFRY),
    ARRAS(50.2913, 2.7774, 5.0, Silhouette.BELFRY),
    DOUAI(50.3700, 3.0790, 5.0, Silhouette.BELFRY),
    CALAIS(50.9513, 1.8542, 6.0, Silhouette.BELFRY),
    DUNKERQUE(51.0343, 2.3768, 7.0, Silhouette.BELFRY),
    BRUGES(51.2081, 3.2246, 5.0, Silhouette.BELFRY),
    ALBI(43.9286, 2.1424, 5.0, Silhouette.BRICK_BELFRY),
    TOULOUSE(43.6085, 1.4420, 10.0, Silhouette.OCTAGON),

    // Les remparts et le Mont.
    CARCASSONNE(43.2063, 2.3637, 5.0, Silhouette.RAMPARTS),
    AVIGNON(43.9508, 4.8075, 6.0, Silhouette.RAMPARTS),
    SAINT_MALO(48.6493, -2.0257, 5.0, Silhouette.RAMPARTS),
    MONT_SAINT_MICHEL(48.6361, -1.5115, 5.0, Silhouette.MOUNT),

    // Ailleurs en Europe et dans le monde.
    BRUSSELS(50.8467, 4.3525, 12.0, Silhouette.ATOMIUM),
    LONDON(51.5007, -0.1246, 15.0, Silhouette.CLOCK_TOWER),
    PISA(43.7230, 10.3966, 6.0, Silhouette.LEANING_TOWER),
    ROME(41.9022, 12.4539, 15.0, Silhouette.DOME),
    FLORENCE(43.7731, 11.2560, 7.0, Silhouette.DOME),
    BARCELONA(41.4036, 2.1744, 12.0, Silhouette.SPIRES),
    BERLIN(52.5208, 13.4094, 15.0, Silhouette.NEEDLE),
    TORONTO(43.6426, -79.3871, 15.0, Silhouette.NEEDLE),
    DUBAI(25.1972, 55.2744, 25.0, Silhouette.BURJ),
    ABU_DHABI(24.4128, 54.4750, 20.0, Silhouette.GREAT_MOSQUE),
    ISTANBUL(41.0054, 28.9768, 15.0, Silhouette.GREAT_MOSQUE),
    MOSCOW(55.7525, 37.6231, 20.0, Silhouette.ONIONS),
    TOKYO(35.6586, 139.7454, 20.0, Silhouette.RED_TOWER),
    AGRA(27.1751, 78.0421, 8.0, Silhouette.TAJ),
    CAIRO(29.9792, 31.1342, 25.0, Silhouette.PYRAMIDS),
    SYDNEY(-33.8568, 151.2153, 15.0, Silhouette.OPERA),
    RIO(-22.9519, -43.2105, 15.0, Silhouette.STATUE_ON_HILL),

    // Des lieux qu'on connaît bien, dessinés au plus juste.
    MORTEAU(47.0578, 6.6072, 3.0, Silhouette.PERTUSIER, Surroundings(Settlement.TOWN, forest = true, river = true, railway = true, population = 6_900)),
    VALENCE(44.9334, 4.8924, 6.0, Silhouette.APOLLINAIRE, Surroundings(Settlement.CITY, river = true, railway = true, ruins = true, population = 64_000)),
    BESANCON(47.2378, 6.0241, 7.0, Silhouette.CITADEL, Surroundings(Settlement.CITY, forest = true, river = true, railway = true, population = 119_000)),
    VALDAHON(47.1500, 6.3333, 3.0, Silhouette.NONE, Surroundings(Settlement.TOWN, forest = true, railway = true, population = 5_600)),
    ASNANS(46.9480, 5.3720, 2.5, Silhouette.NONE, Surroundings(Settlement.VILLAGE, population = 560)),
    LA_BREVINE(46.9806, 6.6064, 2.5, Silhouette.NONE, Surroundings(Settlement.VILLAGE, forest = true, lake = true, population = 640)),
    LA_LONDE(43.1386, 6.2342, 4.0, Silhouette.NONE, Surroundings(Settlement.TOWN, forest = true, coast = true, vineyard = true, harbour = true, population = 10_500)),
    ;

    /** Les environs d'OpenStreetMap [around], complétés de ce que l'on sait du lieu (la taille de la ville, sûre). */
    fun completed(around: Surroundings?): Surroundings? {
        val k = known ?: return around
        val a = around ?: return k
        return a.copy(
            settlement = k.settlement,
            population = k.population ?: a.population,
            forest = a.forest || k.forest,
            river = a.river || k.river,
            lake = a.lake || k.lake,
            coast = a.coast || k.coast,
            vineyard = a.vineyard || k.vineyard,
            castle = a.castle || k.castle,
            ruins = a.ruins || k.ruins,
            railway = a.railway || k.railway,
            harbour = a.harbour || k.harbour,
        )
    }

    companion object {
        /** Le monument (ou le lieu connu) le plus proche : d'abord les lieux connus, qui sont plus petits. */
        fun near(latitude: Double, longitude: Double): Landmark? = entries.sortedBy { it.radiusKm }.firstOrNull {
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
 * Sahara ou la pollution ; la température (canicule, grand froid), l'isotherme 0 °C (la limite de la neige sur les
 * montagnes), l'activité magnétique (aurores) et le décalage horaire du lieu (le soleil et la lune à leur place).
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
    val temperature: Double? = null,
    /** Altitude de l'isotherme 0 °C : maintenant s'il pleut ou neige ([precipitating]), sinon la plus basse des 24 h. */
    val freezingLevel: Double? = null,
    val precipitating: Boolean = false,
    /** Indice Kp (0 à 9). */
    val kp: Double? = null,
    /** Décalage de l'heure du lieu sur le temps universel, en secondes. */
    val utcOffsetSeconds: Int? = null,
)

/**
 * Les grandes régions du monde, pour bâtir comme on bâtit là-bas : le lieu de culte du village, la forme des toits, et
 * les fêtes que l'on y célèbre.
 */
internal enum class Culture {
    /** L'Europe de l'Ouest et du Centre : églises (clocher comtois en montagne, flèche d'ardoise), toits de tuiles. */
    EUROPE,
    /** L'Amérique du Nord, l'Australie, l'Afrique au sud du Sahara : églises à flèche, toits de tuiles. */
    NEW_WORLD,
    /** Russie, Ukraine, Balkans de l'Est, Grèce, Caucase : églises orthodoxes à bulbes. */
    ORTHODOX,
    /** Amérique latine, Philippines : églises coloniales blanches à deux clochers. */
    LATIN,
    /** Afrique du Nord, Sahel, Moyen-Orient, Turquie, Iran, Asie centrale, Pakistan, Malaisie, Indonésie : mosquées, toits plats. */
    ISLAMIC,
    /** Inde, Népal : temples hindous, toits plats. */
    SOUTH_ASIA,
    /** Birmanie, Thaïlande, Laos, Cambodge, Vietnam, Sri Lanka : stupas dorés. */
    BUDDHIST,
    /** Chine, Corée, Japon, Taïwan, Mongolie : pagodes, toits aux bords relevés. */
    EAST_ASIA;

    /** Des maisons à toit plat (terrasses), aux murs clairs. */
    val flatRoofs: Boolean get() = this == ISLAMIC || this == SOUTH_ASIA

    /** Des toits aux coins relevés. */
    val curvedRoofs: Boolean get() = this == EAST_ASIA

    /** On y fête Noël et Pâques. */
    val christian: Boolean get() = this == EUROPE || this == NEW_WORLD || this == ORTHODOX || this == LATIN

    companion object {
        /** La région du lieu, d'après ses coordonnées (des contours simplifiés, à quelques dizaines de kilomètres près). */
        fun of(latitude: Double, longitude: Double): Culture {
            val lat = latitude
            val lon = longitude
            return when {
                // Asie : de l'est vers l'ouest.
                lat in 4.5..21.0 && lon in 116.5..127.0 -> LATIN
                lat in 18.0..54.0 && lon in 97.0..146.0 && !(lat < 23.5 && lon < 109.5) -> EAST_ASIA
                lat in 5.8..9.9 && lon in 79.5..82.0 -> BUDDHIST
                lat in 6.5..28.5 && lon in 92.0..110.0 -> BUDDHIST
                lat in -9.0..-8.0 && lon in 114.4..115.8 -> SOUTH_ASIA
                lat in -11.0..7.0 && lon in 95.0..141.0 -> ISLAMIC
                lat in 20.5..26.6 && lon in 88.0..92.7 -> ISLAMIC
                lat in 23.5..37.5 && lon in 60.0..74.5 -> ISLAMIC
                lat in 6.0..36.0 && lon in 68.0..97.5 -> SOUTH_ASIA
                // Le Caucase chrétien et Chypre, avant le monde musulman qui les entoure.
                lat in 38.8..43.6 && lon in 40.0..46.7 -> ORTHODOX
                lat in 34.5..35.7 && lon in 32.0..34.6 -> ORTHODOX
                // La Grèce et ses îles de la mer Égée, avant la Turquie voisine.
                lat in 34.8..41.8 && lon in 19.5..26.6 -> ORTHODOX
                // Moyen-Orient, Turquie, Iran, Afghanistan, Asie centrale (pas la Russie de la basse Volga).
                lat in 12.0..42.2 && lon in 26.0..63.0 -> ISLAMIC
                lat in 35.0..50.0 && lon in 46.0..87.0 && !(lat > 45.5 && lon < 50.5) -> ISLAMIC
                // Afrique du Nord et Sahel (l'Éthiopie chrétienne à part).
                lat in 10.0..37.5 && lon in -17.5..35.0 && !(lat < 15.0 && lon > 33.0) -> ISLAMIC
                // Europe orthodoxe : Grèce, Balkans de l'Est, Roumanie, Moldavie, Ukraine, Biélorussie, Russie.
                lat in 41.0..45.5 && lon in 20.0..30.0 -> ORTHODOX
                lat in 45.5..48.3 && lon in 21.5..30.0 -> ORTHODOX
                lat in 44.0..78.0 && lon in 24.0..180.0 && !(lat in 53.9..59.7 && lon < 28.2) && !(lat > 59.8 && lon < 31.5) -> ORTHODOX
                // Amérique latine : au sud de la frontière des États-Unis (le Rio Grande), les Caraïbes, l'Amérique du Sud.
                lon in -118.0..-34.0 && lat > -56.0 && lat < latinBorder(lon) -> LATIN
                lat in 35.0..71.5 && lon in -11.0..40.0 -> EUROPE
                else -> NEW_WORLD
            }
        }

        /** La frontière nord de l'Amérique latine, selon la longitude. */
        private fun latinBorder(lon: Double): Double = when {
            lon < -106.5 -> 32.7
            lon < -97.2 -> 31.8 + (lon + 106.5) * (25.9 - 31.8) / (106.5 - 97.2)
            else -> 24.0
        }

        /**
         * Les grands déserts chauds : Sahara, Arabie (Dubaï), sud-ouest des États-Unis, Atacama, Namib et Kalahari,
         * centre de l'Australie, Gobi. Le sol y est de sable, sans prés ni champs.
         */
        fun desert(latitude: Double, longitude: Double): Boolean {
            val lat = latitude
            val lon = longitude
            return lat in 16.0..31.0 && lon in -17.0..32.0 ||
                lat in 13.0..31.0 && lon in 35.0..60.0 ||
                lat in 31.0..37.0 && lon in -117.0..-109.0 ||
                lat in -27.0..-18.0 && lon in -71.0..-68.0 ||
                lat in -28.0..-17.0 && lon in 12.0..22.0 ||
                lat in -32.0..-19.0 && lon in 117.0..146.0 ||
                lat in 40.0..46.0 && lon in 95.0..112.0
        }

        /** Le désert des dromadaires : Sahara et Arabie. */
        fun camels(latitude: Double, longitude: Double) = latitude in 13.0..31.0 && longitude in -17.0..60.0

        /** La France (pour ses fêtes : le 14 Juillet, le muguet du 1er mai, le poisson d'avril). */
        fun france(latitude: Double, longitude: Double) = latitude in 41.3..51.1 && longitude in -5.2..9.6
    }
}

/**
 * Les manières de bâtir de chez nous, plus fines que [Culture] : le clocher comtois de Franche-Comté (et ses fermes à
 * tuyé, ou bressanes en plaine), le temple et la grande ferme du Jura suisse, le campanile et les pins de Provence.
 */
internal enum class LocalStyle {
    NONE, COMTOIS, SWISS_JURA, PROVENCE;

    companion object {
        fun of(latitude: Double, longitude: Double): LocalStyle {
            val lat = latitude
            val lon = longitude
            // La frontière franco-suisse dans le Jura, de Sainte-Croix au Doubs puis vers Delle.
            val border = if (lon < 6.70) 46.85 + (lon - 6.43) * 0.815 else 47.07 + (lon - 6.70) * 0.8
            return when {
                lat in 46.5..47.6 && lon in 6.43..7.4 && lat < border -> SWISS_JURA
                lat in 46.25..48.05 && lon in 5.25..7.15 -> COMTOIS
                lat in 42.9..44.6 && lon in 4.3..7.55 -> PROVENCE
                else -> NONE
            }
        }
    }
}

/** Un sommet nommé, à l'abscisse [x] de la crête : « Mont Vouillot », 1 145 m. */
internal class PeakName(val x: Float, val name: String, val elevation: Double)

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
    // Le ciel rare : aurores, pluie d'étoiles filantes ; l'air qui tremble de chaleur, le givre des grands froids ;
    // les feuilles mortes qui s'envolent.
    AURORA, METEORS, HEAT, FROST, LEAVES,
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
    /** Le lieu est sur l'eau (en mer, au milieu d'un lac) : ni village ni champs autour. */
    val onWater: Boolean = false,
    /** Faux tant que la coupe du relief n'est pas arrivée : un sol plat, sans décor inventé. */
    val known: Boolean = true,
    /** Direction de la coupe (la droite du dessin), en degrés depuis le nord : on regarde 90° à gauche de celle-ci. */
    val bearing: Double = 90.0,
    /** Une autre chaîne, au loin, derrière le relief : (x, hauteur) comme [ridge], vide s'il n'y en a pas. */
    val backdrop: List<Pair<Float, Float>> = emptyList(),
    /** Les sommets nommés de la coupe (OpenStreetMap), à écrire au crayon au-dessus de la crête. */
    val labels: List<PeakName> = emptyList(),
) {
    /** La direction où l'on regarde (le fond du dessin), en degrés depuis le nord. */
    val view: Double get() = ((bearing - 90) % 360 + 360) % 360

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

    /**
     * Le sol mis à plat de [x0] à [x1], à la hauteur [level], avec une pente douce de part et d'autre : la ville est
     * au niveau du lieu, pas sur la colline voisine que la coupe, très resserrée, met juste à côté.
     */
    fun flattened(x0: Float, x1: Float, level: Float, ramp: Float = 0.035f): SketchTerrain {
        fun weight(x: Float): Float {
            val d = when {
                x < x0 -> (x0 - x) / ramp
                x > x1 -> (x - x1) / ramp
                else -> 0f
            }.coerceAtMost(1f)
            return d * d * (3 - 2 * d)
        }
        val points = (ridge + listOf(x0 to heightAt(x0), x1 to heightAt(x1))).sortedBy { it.first }.distinctBy { it.first }
        return copy(ridge = points.map { (x, y) -> x to level + (y - level) * weight(x) })
    }

    fun copy(
        ridge: List<Pair<Float, Float>> = this.ridge,
        water: List<ClosedFloatingPointRange<Float>> = this.water,
        sea: Boolean = this.sea,
        river: Boolean = this.river,
        island: Boolean = this.island,
    ) = SketchTerrain(ridge, relief, water, sea, treeline, snowline, rockline, glacierline, placeX, farX, river, island, onWater, known, bearing, backdrop, labels)
}

internal object SketchTerrains {
    /** Abscisses du début de la coupe, du lieu et de la fin de la coupe (le sommet tombe vers 0,6, avant le texte de droite). */
    private const val LEFT = 0.14f
    private const val PLACE = 0.36f
    /** Sur un sommet, le lieu est plus au milieu : la pente descend des deux côtés. */
    private const val SUMMIT_PLACE = 0.47f
    private const val RIGHT = 0.64f
    /** Le plus haut sommet monte au plus à 31 % de la hauteur du dessin au-dessus du pied. */
    const val MAX_LIFT = 0.31f
    private const val FAR_KM = 20.0
    /** Le relief qui compte pour le lieu : celui des 15 km autour (pas la montagne qu'on voit à 50 km). */
    private const val LOCAL_KM = 15.0
    /** Sur un sommet : rien de plus haut à 2,5 km, et le terrain qui tombe des deux côtés. */
    private const val SUMMIT_KM = 2.5
    /**
     * La perspective : ce qui est près paraît plus grand. Les hauteurs varient comme 1 / (distance + 12 km), de 0,75 à
     * 1,3 fois celle du sommet (en plaine, jamais plus grand : une butte voisine ne devient pas une colline).
     */
    private const val PERSPECTIVE_KM = 12.0
    /** Abscisses plus serrées vers le loin : t^0,75. */
    private const val PERSPECTIVE_X = 0.75

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

    /**
     * En attendant la coupe (une seconde, ou plus sans réseau) : un sol presque plat, sans rien dessus ; pas de
     * collines ni de village qui disparaîtraient l'instant d'après.
     */
    val DEFAULT = SketchTerrain(
        ridge = listOf(0f to 0.005f, 0.3f to 0.01f, 0.5f to 0.008f, 0.7f to 0.012f, 1f to 0.005f),
        relief = Relief.PLAIN,
        water = emptyList(),
        sea = false,
        treeline = 1f,
        snowline = 2f,
        placeX = 0.38f,
        known = false,
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
    fun from(
        profile: TerrainProfile?,
        month: Int,
        snowing: Boolean,
        latitude: Double = 45.0,
        around: Surroundings? = null,
        conditions: SketchConditions? = null,
    ): SketchTerrain = shape(profile, month, snowing, latitude, conditions).with(around)

    /**
     * Altitude de la neige sur les montagnes : celle du mois, rapprochée de l'isotherme 0 °C de la prévision (300 m
     * dessous, à 600 m au plus de celle du mois) ; quand il tombe quelque chose, la neige fraîche juste sous l'isotherme.
     */
    fun snowAltitude(month: Int, latitude: Double, conditions: SketchConditions?): Double {
        val seasonal = snowAltitude(month) - latitudeShift(latitude)
        val freezing = conditions?.freezingLevel ?: return seasonal
        return if (conditions.precipitating) freezing - 300 else (freezing - 300).coerceIn(seasonal - 600, seasonal + 600)
    }

    private fun shape(profile: TerrainProfile?, month: Int, snowing: Boolean, latitude: Double, conditions: SketchConditions?): SketchTerrain {
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
        // En perspective (pas au sommet, d'où tout est en dessous) : le près s'étale et grandit, le loin se resserre.
        val bend = if (summit) 1.0 else PERSPECTIVE_X
        fun x(k: Int): Float = if (k < p) {
            place - (place - LEFT) * ((p - k).toDouble() / p).pow(bend).toFloat()
        } else {
            place + (RIGHT - place) * ((k - p).toDouble() / max(n - 1 - p, 1)).pow(bend).toFloat()
        }
        val topKm = distanceKm
        val nearest = if (relief == Relief.PLAIN) 1.0 else 1.3
        fun perspective(k: Int): Double = if (summit) 1.0 else ((topKm + PERSPECTIVE_KM) / (abs(k - p) * kmPerSample + PERSPECTIVE_KM)).coerceIn(0.75, nearest)
        fun lift(v: Double, k: Int = p) = ((v - base) * perMeter * if (v > base) perspective(k) else 1.0).toFloat().coerceIn(-0.05f, MAX_LIFT * 1.05f)
        val profilePoints = (0 until n).map { k -> x(k) to lift(smooth[k], k) * envelope(x(k)) }
        // Au-delà de la coupe, le relief redescend doucement vers les bords (sous le texte).
        val first = profilePoints.first().second
        val last = profilePoints.last().second
        val ridge = listOf(0f to first * 0.3f, LEFT / 2 to first * 0.6f) + profilePoints +
            (1..6).map { i -> val t = i / 6f; RIGHT + (1 - RIGHT) * t to last * (1 - t) * (1 - t) }
        val runs = waterRuns(e, ground, kmPerSample)
        val water = runs.map { (a, b) -> (if (a == 0) 0f else x(a))..x(b) }
        val sea = runs.any { (a, _) -> e[a] <= 1.0 }
        val shift = latitudeShift(latitude)
        val snowAltitude = if (snowing) ground - 50 else snowAltitude(month, latitude, conditions)
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
            onWater = runs.any { (a, b) -> p in a..b },
            bearing = profile.bearing ?: 90.0,
            backdrop = profile.backdrop?.let { backdrop(it, ground) }.orEmpty(),
            labels = profile.labels.mapNotNull { l -> l.name?.takeIf { l.index in 0 until n }?.let { PeakName(x(l.index), it, l.elevation) } },
        )
    }

    /**
     * La deuxième chaîne, vue du lieu : chaque point aussi haut que l'angle sous lequel on le voit (la même loi que pour
     * le sommet de la coupe), un peu tassée par l'éloignement, étalée sur toute la largeur. Rien si elle ne dépasse pas
     * l'horizon de plus de 1 % de la hauteur du dessin.
     */
    private fun backdrop(b: Backdrop, ground: Double): List<Pair<Float, Float>> {
        val e = b.elevations
        val n = e.size
        if (n < 10) return emptyList()
        val kmPerSample = b.lengthKm / (n - 1)
        val lifts = (1 until n).map { k ->
            val angle = Math.toDegrees(atan((e[k] - ground) / (k * kmPerSample * 1000)))
            val x = 0.30f + 0.66f * (k - 1) / (n - 2)
            x to if (angle <= 0) 0f else (MAX_LIFT * 0.85 * (1 - exp(-angle / 3.5))).toFloat() * envelope(x)
        }
        val smooth = lifts.indices.map { k -> lifts[k].first to (k - 1..k + 1).filter { it in lifts.indices }.map { lifts[it].second }.average().toFloat() }
        return if (smooth.maxOf { it.second } < 0.01f) emptyList() else smooth
    }

    /** Ce que la coupe ne montre pas toujours : un lac tout près, la mer, la rivière qui passe, l'île. */
    private fun SketchTerrain.with(around: Surroundings?): SketchTerrain {
        if (around == null) return this
        var t = copy(sea = sea || around.coast, river = around.river && !sea && !around.coast && !onWater, island = around.island)
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

    /**
     * Les côtés du dessin restent bas : à gauche la grande température, à droite le ciel en toutes lettres et les
     * extrêmes ; le relief n'y monte pas jusqu'au texte.
     */
    private fun envelope(x: Float): Float = when {
        x < 0.36f -> {
            val t = ((x - 0.10f) / 0.26f).coerceIn(0f, 1f)
            0.35f + 0.65f * t * t * (3 - 2 * t)
        }
        x > 0.58f -> {
            val t = ((x - 0.58f) / 0.20f).coerceIn(0f, 1f)
            1f - 0.5f * t * t * (3 - 2 * t)
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
    /** Orage de grêle : des grêlons parmi la pluie, et sur le sol. */
    val hail: Boolean = false,
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
            hail = code == 96 || code == 99,
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
    /** Où sont posés le lieu habité et l'objet du premier plan, choisi une fois pour toutes ([layoutOf]). */
    val layout: Layout? = null,
    /** Habitants du lieu habité : sa taille dessinée en dépend. */
    val population: Long? = null,
    /** Le soleil et la lune à leur vraie place dans le ciel, quand on connaît le lieu et l'heure. */
    val sun: SkyPoint? = null,
    val moon: SkyPoint? = null,
    /** Part des fenêtres allumées : aucune le jour, de plus en plus au crépuscule, moins au cœur de la nuit. */
    val lit: Float = 0f,
    /** La région du monde : le lieu de culte, les toits. */
    val culture: Culture = Culture.EUROPE,
    /** Un désert chaud : du sable plutôt que des prés. */
    val desert: Boolean = false,
    /** La manière de bâtir de la région (clocher comtois, temple, campanile de Provence). */
    val local: LocalStyle = LocalStyle.NONE,
) {
    val clouds: SketchClouds? get() = conditions.clouds

    /** Ce que le paysage fixe laisse à la couche animée (cheminées, éoliennes, voie ferrée…), rempli en le dessinant. */
    val anchors = SketchAnchors()

    /**
     * Les arbres, la fumée et la pluie penchent avec le vent : de 0 (sous 15 km/h) à 1 (60 km/h et plus), du côté où il
     * souffle dans le dessin (d'après la direction où l'on regarde) ; pas du tout quand il souffle vers nous ou dans
     * notre dos.
     */
    val lean: Float get() {
        val strength = ((conditions.windKmh - 15) / 45).coerceIn(0.0, 1.0).toFloat()
        // Le vent va vers windFrom + 180 ; la droite du dessin est la direction de la coupe.
        val toward = -cos(Math.toRadians(conditions.windFrom - terrain.bearing)).toFloat()
        return strength * toward
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

/** Un astre dans le ciel : sa direction (depuis le nord, vers l'est) et sa hauteur au-dessus de l'horizon, en degrés. */
internal class SkyPosition(val azimuth: Double, val elevation: Double)

/** Un astre dans le dessin : [x] en fraction de la largeur, et sa hauteur au-dessus de l'horizon (degrés). */
internal class SkyPoint(val x: Float, val elevation: Double)

/** Le ciel vrai : le soleil, la lune, les aurores et les pluies d'étoiles filantes. */
internal object SketchSkies {
    private val J2000 = LocalDateTime.of(2000, 1, 1, 12, 0)

    /** Pôle nord géomagnétique (modèle du dipôle). */
    private const val POLE_LATITUDE = 80.7
    private const val POLE_LONGITUDE = -72.7

    private fun days(utc: LocalDateTime) = ChronoUnit.SECONDS.between(J2000, utc) / 86_400.0

    private fun rad(deg: Double) = deg * PI / 180

    /** Le soleil à [utc] (temps universel), vu du lieu ; à un dixième de degré près. */
    fun sun(utc: LocalDateTime, latitude: Double, longitude: Double): SkyPosition {
        val d = days(utc)
        val g = rad(357.529 + 0.98560028 * d)
        val q = 280.459 + 0.98564736 * d
        return horizontal(d, rad(q + 1.915 * sin(g) + 0.020 * sin(2 * g)), 0.0, latitude, longitude)
    }

    /** La lune, à un degré près (sans la parallaxe, qui ne se voit pas au crayon). */
    fun moon(utc: LocalDateTime, latitude: Double, longitude: Double): SkyPosition {
        val d = days(utc)
        val l = 218.316 + 13.176396 * d
        val m = rad(134.963 + 13.064993 * d)
        val f = rad(93.272 + 13.229350 * d)
        return horizontal(d, rad(l + 6.289 * sin(m)), rad(5.128 * sin(f)), latitude, longitude)
    }

    /** Coordonnées écliptiques → hauteur et azimut au lieu. */
    private fun horizontal(d: Double, lambda: Double, beta: Double, latitude: Double, longitude: Double): SkyPosition {
        val e = rad(23.4393 - 0.0000004 * d)
        val ra = atan2(sin(lambda) * cos(e) - tan(beta) * sin(e), cos(lambda))
        val dec = asin(sin(beta) * cos(e) + cos(beta) * sin(e) * sin(lambda))
        val hourAngle = rad(280.46061837 + 360.98564736629 * d + longitude) - ra
        val phi = rad(latitude)
        val altitude = asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(hourAngle))
        val azimuth = atan2(-sin(hourAngle) * cos(dec), sin(dec) * cos(phi) - cos(dec) * cos(hourAngle) * sin(phi))
        return SkyPosition((Math.toDegrees(azimuth) + 360) % 360, Math.toDegrees(altitude))
    }

    /**
     * Où mettre l'astre dans le dessin, quand on regarde vers [view] : droit devant au milieu du ciel libre (x 0,59),
     * à 90° sur les côtés (0,40 et 0,78) ; derrière soi, contre le bord le plus proche.
     */
    fun onScreen(position: SkyPosition, view: Double): SkyPoint {
        val relative = ((position.azimuth - view) % 360 + 540) % 360 - 180
        return SkyPoint((0.59 + 0.19 * relative / 90).toFloat().coerceIn(0.42f, 0.78f), position.elevation)
    }

    /** Latitude géomagnétique du lieu, en degrés (positive au nord comme au sud). */
    fun magneticLatitude(latitude: Double, longitude: Double): Double {
        val s = sin(rad(latitude)) * sin(rad(POLE_LATITUDE)) + cos(rad(latitude)) * cos(rad(POLE_LATITUDE)) * cos(rad(longitude - POLE_LONGITUDE))
        return abs(Math.toDegrees(asin(s.coerceIn(-1.0, 1.0))))
    }

    /**
     * Les aurores sont visibles depuis le lieu : à 66° de latitude magnétique par temps calme (Kp 0, Tromsø), vers 45°
     * pour une grande tempête (Kp 9 : toute la France en mai 2024).
     */
    fun auroraVisible(latitude: Double, longitude: Double, kp: Double): Boolean = magneticLatitude(latitude, longitude) >= 66.5 - 2.3 * kp

    /** Les grandes pluies d'étoiles filantes : leur nuit de maximum, et combien de jours autour. */
    private val SHOWERS = listOf(
        MonthDay.of(1, 3) to 1, // Quadrantides
        MonthDay.of(4, 22) to 1, // Lyrides
        MonthDay.of(5, 6) to 2, // Êta Aquarides
        MonthDay.of(8, 12) to 2, // Perséides
        MonthDay.of(10, 21) to 2, // Orionides
        MonthDay.of(11, 17) to 1, // Léonides
        MonthDay.of(12, 14) to 2, // Géminides
    )

    /** La nuit du [night] (le soir où elle commence) tombe pendant une pluie d'étoiles filantes. */
    fun meteorShower(night: LocalDate): Boolean = SHOWERS.any { (peak, days) ->
        val date = peak.atYear(night.year)
        abs(ChronoUnit.DAYS.between(date, night)) <= days
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

    /**
     * Quelque chose bouge dans la scène : des nuages, de la pluie, de la neige ; des oiseaux, un avion, un bateau, la
     * fumée, les éoliennes, le train, les aurores, les étoiles filantes, l'air chaud, les feuilles mortes.
     */
    fun moves(scene: SketchScene): Boolean = scene.sky != SketchSky.CLEAR && scene.sky != SketchSky.FOG ||
        scene.surprises.any { it in ANIMATED } || scene.decor.any { it in ANIMATED_DECOR }

    private val ANIMATED = setOf(
        Surprise.BIRDS, Surprise.PLANE, Surprise.BALLOON, Surprise.SAILBOAT, Surprise.SMOKE, Surprise.AURORA,
        Surprise.METEORS, Surprise.HEAT, Surprise.LEAVES,
    )
    private val ANIMATED_DECOR = setOf(Decor.TURBINES, Decor.TRAIN, Decor.HARBOUR, Decor.GULLS)

    /**
     * Part des fenêtres allumées selon la lumière : quelques-unes au coucher, la moitié au crépuscule, la plupart le
     * soir, beaucoup moins au cœur de la nuit ; quelques-unes en plein jour sous l'orage ou le brouillard.
     */
    fun litWindows(light: Light, time: LocalDateTime, sky: SketchSky): Float = when (light) {
        Light.NIGHT -> if (time.hour in 1..5) 0.2f else 0.65f
        Light.DUSK -> 0.45f
        Light.SUNSET -> 0.15f
        Light.DAWN -> 0.3f
        Light.SUNRISE -> 0.1f
        Light.DAY -> if (sky == SketchSky.STORM || sky == SketchSky.FOG) 0.1f else 0f
    }

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
        latitude: Double? = null,
        longitude: Double? = null,
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
        val season = seasonMonth(date.monthValue, latitude ?: 45.0)
        val decor = decorFor(terrain, around, rnd, season, latitude, longitude).toMutableSet()
        val surprises = mutableSetOf<Surprise>()
        val clearish = sky == SketchSky.CLEAR || sky == SketchSky.PARTLY
        val wet = sky == SketchSky.RAIN || sky == SketchSky.STORM || sky == SketchSky.SNOW
        val day = MonthDay.from(date)
        val eve = MonthDay.from(night)
        val easter = easterSunday(date.year)
        val culture = if (latitude != null && longitude != null) Culture.of(latitude, longitude) else Culture.EUROPE
        val christian = culture.christian
        // Sans coordonnées, on fait comme en France.
        val france = latitude == null || longitude == null || Culture.france(latitude, longitude)
        val halloween = culture == Culture.EUROPE || culture == Culture.NEW_WORLD

        // Dans le ciel : les fêtes d'abord, sinon un peu de vie selon le temps.
        val skyThing = when {
            variant > 0 && variant % ABDUCTION_TAPS == 0 -> Surprise.ABDUCTION
            christian && (!isDay && eve == MonthDay.of(12, 24) || day == MonthDay.of(12, 25)) -> Surprise.SLEIGH
            !isDay && (eve == MonthDay.of(12, 31) || france && eve == MonthDay.of(7, 14)) -> Surprise.FIREWORKS
            halloween && !isDay && eve == MonthDay.of(10, 31) -> Surprise.BATS
            day == MonthDay.of(2, 14) -> Surprise.HEART
            france && day == MonthDay.of(4, 1) -> Surprise.FISH
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
            // Sur l'eau, rien ne tient debout au premier plan.
            skyThing == Surprise.ABDUCTION || terrain.onWater -> null
            christian && (date == easter || date == easter.plusDays(1)) -> Surprise.EGGS
            halloween && (day == MonthDay.of(10, 31) || eve == MonthDay.of(10, 31)) -> Surprise.PUMPKIN
            date.dayOfMonth == 13 && date.dayOfWeek == DayOfWeek.FRIDAY -> Surprise.BLACK_CAT
            france && day == MonthDay.of(5, 1) -> Surprise.LILY
            (sky == SketchSky.SNOW || conditions.snowOnGround && isDay && !wet) && rnd.nextFloat() < 0.5f -> Surprise.SNOWMAN
            sky == SketchSky.RAIN && rnd.nextFloat() < 0.3f -> Surprise.UMBRELLA
            else -> null
        }
        groundThing?.let(surprises::add)

        // Le soleil et la lune à leur place, vus dans la direction où l'on regarde (l'heure du lieu ramenée au temps
        // universel).
        val utc = conditions.utcOffsetSeconds?.let { time.minusSeconds(it.toLong()) }
        val sun = if (utc != null && latitude != null && longitude != null) SketchSkies.onScreen(SketchSkies.sun(utc, latitude, longitude), terrain.view) else null
        val moon = if (utc != null && latitude != null && longitude != null) SketchSkies.onScreen(SketchSkies.moon(utc, latitude, longitude), terrain.view) else null
        // Une averse en plein jour : souvent un arc-en-ciel (seulement quand le soleil est à moins de 42° : l'arc est
        // à 42° de l'ombre de notre tête).
        if (isDay && code in 80..82 && rnd.nextFloat() < 0.7f && (sun == null || sun.elevation < 42)) surprises += Surprise.RAINBOW

        // Au sommet, toujours la croix (dessinée sur le lieu) ; ailleurs en montagne, parfois sur le plus haut.
        if (relief == Relief.SUMMIT || mountains && rnd.nextFloat() < 0.35f) surprises += Surprise.SUMMIT_CROSS
        // En mer ou sur le lac, de jour : un voilier, toujours.
        if (terrain.onWater && isDay && !wet && !conditions.frozen) surprises += Surprise.SAILBOAT
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
        if (conditions.windKmh >= 15 && relief != Relief.SUMMIT && !terrain.onWater) decor += Decor.WINDSOCK
        // Le monument de la grande ville, quand on y est.
        // Les monuments propres à une grande ville seulement avec elle ; les autres aussi à la place de l'église du village.
        val monument = landmark?.takeIf { it.look != Silhouette.NONE }?.takeIf {
            around == null || Decor.CITY in decor || Decor.TOWN in decor ||
                it.look != Silhouette.OWN && (Decor.VILLAGE in decor || Decor.HAMLET in decor)
        }
        val chimneys = decor.any { it in CHIMNEYS }
        if (chimneys && (!wet || sky == SketchSky.SNOW) && rnd.nextFloat() < 0.6f) surprises += Surprise.SMOKE
        val light = lightAt(time, isDay, conditions.sunrise, conditions.sunset)
        val weather = SketchWeather.of(code)
        // Le ciel rare de la nuit noire : les aurores quand l'activité magnétique les descend jusqu'au lieu, la pluie
        // d'étoiles filantes de la saison (à la place de l'étoile filante du hasard).
        val dark = !isDay && light == Light.NIGHT
        val kp = conditions.kp
        if (dark && clearish && kp != null && latitude != null && longitude != null && SketchSkies.auroraVisible(latitude, longitude, kp)) {
            surprises += Surprise.AURORA
        }
        if (dark && sky == SketchSky.CLEAR && SketchSkies.meteorShower(night)) {
            surprises -= Surprise.SHOOTING_STAR
            surprises += Surprise.METEORS
        }
        // Canicule et grand froid.
        val temperature = conditions.temperature
        if (temperature != null && temperature >= 32 && isDay) surprises += Surprise.HEAT
        if (temperature != null && temperature <= -10) surprises += Surprise.FROST
        // À l'automne, les feuilles mortes s'envolent (toujours par grand vent).
        val leafy = decor.any { it == Decor.TREES || it == Decor.FOREST || it == Decor.ORCHARD }
        if (leafy && season in 10..11 && sky != SketchSky.SNOW && (conditions.windKmh >= 12 || rnd.nextFloat() < 0.4f)) surprises += Surprise.LEAVES
        // Décembre : les guirlandes et le sapin illuminé ; le soir, les réverbères.
        val inhabited = decor.any { it == Decor.HAMLET || it == Decor.VILLAGE || it == Decor.TOWN || it == Decor.CITY }
        if (inhabited && christian && date.monthValue == 12 && relief != Relief.SUMMIT) decor += Decor.GARLANDS
        if (decor.any { it == Decor.VILLAGE || it == Decor.TOWN || it == Decor.CITY }) decor += Decor.LAMPS
        // Le désert : ni prés, ni champs, ni forêt, ni vaches ; des palmiers autour du lieu habité, des dromadaires.
        val desert = latitude != null && longitude != null && Culture.desert(latitude, longitude)
        // En Provence, au bord de la mer : des palmiers sur le front de mer ; les îles d'Hyères au large de La Londe.
        val local = if (latitude != null && longitude != null && culture == Culture.EUROPE) LocalStyle.of(latitude, longitude) else LocalStyle.NONE
        if (local == LocalStyle.PROVENCE && terrain.sea && inhabited) decor += Decor.PALMS
        if (latitude != null && longitude != null && terrain.sea && latitude in 42.9..43.2 && longitude in 5.95..6.6) decor += Decor.ISLANDS
        if (desert) {
            val green = decor.any { it == Decor.TREES || it == Decor.FOREST || it == Decor.ORCHARD || it == Decor.FIELDS }
            decor -= setOf(Decor.FIELDS, Decor.TREES, Decor.FOREST, Decor.FIRS, Decor.ORCHARD, Decor.VINES, Decor.LAVENDER, Decor.SUNFLOWERS, Decor.COWS)
            if (green || inhabited) decor += Decor.PALMS
            if (Culture.camels(latitude!!, longitude!!) && isDay && !terrain.onWater && Decor.CITY !in decor && rnd.nextFloat() < 0.5f) decor += Decor.CAMELS
            surprises -= Surprise.LEAVES
        }
        // Le lieu habité au niveau du lieu : le sol est aplani sous lui.
        val population = around?.population
        val layout = layoutOf(SketchScene(sky, terrain, decor, surprises, seed, skyVariant, weather, conditions, light, monument, population = population))
        val built = decor.any { it in BUILT } && relief != Relief.SUMMIT
        val ground = layout.settlement?.takeIf { built }?.let { x ->
            terrain.flattened(x - layout.half - 0.015f, x + layout.half + 0.015f, terrain.heightAt(terrain.placeX).coerceAtLeast(0f))
        } ?: terrain
        return SketchScene(
            sky, ground, decor, surprises, seed, skyVariant, weather, conditions, light, monument, layout, population,
            sun = sun,
            moon = moon,
            lit = litWindows(light, time, sky),
            culture = culture,
            desert = desert,
            local = local,
        )
    }

    /** Les lieux habités dont le sol est aplani (pas le chalet ni le refuge, posés sur la pente). */
    private val BUILT = setOf(Decor.FARM, Decor.HAMLET, Decor.VILLAGE, Decor.TOWN, Decor.CITY)

    /** Les maisons qui ont une cheminée qui fume (pas les immeubles de la grande ville). */
    private val CHIMNEYS = setOf(Decor.FARM, Decor.CHALET, Decor.REFUGE, Decor.HAMLET, Decor.VILLAGE, Decor.TOWN)

    /**
     * Le décor du lieu : la ville ou le village qu'il est vraiment, la forêt s'il y en a une tout près, et ce que le
     * relief permet (champs en plaine, feuillus en basse montagne, sapins plus haut). Sans [around] (pas encore chargé,
     * ou sans réseau), un peu de variété d'un jour à l'autre, d'après le seul relief.
     */
    private fun decorFor(
        terrain: SketchTerrain,
        around: Surroundings?,
        rnd: Random,
        month: Int = 6,
        latitude: Double? = null,
        longitude: Double? = null,
    ): Set<Decor> {
        val relief = terrain.relief
        val decor = mutableSetOf<Decor>()
        // Au-dessus de la limite des arbres, ni forêt ni ferme : un refuge.
        val aboveTrees = terrain.treeline <= 0.005f
        // Sur l'eau, rien de bâti ni de planté autour : la mer ou le lac (et le bateau). Sans la coupe, rien non plus.
        if (terrain.onWater || !terrain.known) return decor
        if (around == null) {
            // Sans les environs (pas encore chargés, ou OpenStreetMap ne répond pas), pas de village inventé : seulement
            // ce que le relief fait pousser.
            if (relief == Relief.SUMMIT || aboveTrees) return decor
            when (relief) {
                Relief.PLAIN -> {
                    decor += Decor.FIELDS
                    if (rnd.nextFloat() < 0.7f) decor += Decor.TREES
                }
                Relief.LOW -> decor += if (rnd.nextFloat() < 0.7f) Decor.TREES else Decor.FIRS
                else -> if (rnd.nextFloat() < 0.7f) decor += Decor.FIRS
            }
            return decor
        }
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
        // Le phare, seulement là où il y en a un vrai.
        if ((terrain.sea || terrain.island) && around.lighthouse) decor += Decor.LIGHTHOUSE
        // Les cultures que l'on voit vraiment autour (vignes, vergers), et celles de la saison et de la région.
        val farmland = !aboveTrees && relief <= Relief.MEDIUM && around.settlement != Settlement.CITY
        if (farmland && around.vineyard) decor += Decor.VINES
        if (farmland && around.orchard) decor += Decor.ORCHARD
        val lat = latitude ?: 0.0
        val lon = longitude ?: 0.0
        // La lavande en fleur en Provence, de juin à juillet ; les tournesols des plaines du Sud-Ouest l'été.
        if (farmland && !terrain.sea && month in 6..7 && lat in 43.2..44.7 && lon in 4.6..7.2) decor += Decor.LAVENDER
        if (Decor.FIELDS in decor && month in 7..8 && lat in 42.5..47.5 && lon in -1.5..5.0 && rnd.nextFloat() < 0.6f) decor += Decor.SUNFLOWERS
        // Le patrimoine des environs : un château (ou des ruines) sur une hauteur, une abbaye au fond du vallon.
        if (relief != Relief.SUMMIT) {
            if (around.castle) decor += Decor.CASTLE else if (around.ruins) decor += Decor.RUINS
            if (around.abbey) decor += Decor.ABBEY
        }
        if (around.windTurbines && relief != Relief.SUMMIT) decor += Decor.TURBINES
        if (around.railway && !terrain.sea && !terrain.island && relief != Relief.SUMMIT) decor += Decor.TRAIN
        if (around.harbour && (terrain.sea || terrain.water.isNotEmpty())) decor += Decor.HARBOUR
        return decor
    }
}
