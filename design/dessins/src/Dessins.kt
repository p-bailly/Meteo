package io.github.pbailly.meteo.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.pbailly.meteo.data.Settlement
import io.github.pbailly.meteo.data.Surroundings
import io.github.pbailly.meteo.data.TerrainProfile
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Image
import java.io.File
import java.time.LocalDateTime
import kotlin.random.Random

/*
 * Catalogue des coloriages d'Aujourd'hui : chaque combinaison (relief, eau, lieu habité, forêt, temps et ses versions,
 * saison, fêtes) dessinée avec le code de l'appli, sous un faux texte de l'écran pour juger de la lisibilité. Écrit les
 * images et catalogue.tsv (section, image, légende) ; catalogue.py en fait un PDF.
 */

/** Taille du haut d'Aujourd'hui sur un téléphone courant, en dp, et finesse des images. */
private const val WIDTH_DP = 411
private const val HEIGHT_DP = 287
private const val DENSITY = 2.5f

private class Spot(
    val name: String,
    val label: String,
    val profile: TerrainProfile?,
    val latitude: Double,
    val around: Surroundings?,
    val landmark: Landmark? = null,
)

private class Item(
    val section: String,
    val caption: String,
    val spot: Spot,
    val code: Int,
    val time: LocalDateTime,
    val isDay: Boolean,
    val sky: Int? = null,
    val dark: Boolean = false,
    val temperature: Int = 12,
    val taps: Int = 0,
    val conditions: SketchConditions = SketchConditions(),
    val style: Int = 1,
    /** Ce que la scène doit montrer : on touche le dessin jusqu'à l'obtenir (une bête, le bonhomme de neige…). */
    val want: (SketchScene) -> Boolean = { true },
)

/** Lecture des coupes de test (format simple, sans bibliothèque JSON). */
private fun profiles(file: File): Map<String, TerrainProfile> {
    val text = file.readText()
    return Regex(""""(\w+)":\{"lengthKm":([0-9.]+),"placeIndex":(\d+),"elevations":\[([^\]]*)]""").findAll(text).associate { m ->
        m.groupValues[1] to TerrainProfile(
            elevations = m.groupValues[4].split(",").map { it.toDouble() }.toDoubleArray(),
            lengthKm = m.groupValues[2].toDouble(),
            placeIndex = m.groupValues[3].toInt(),
            labels = emptyList(),
            peaks = emptyList(),
        )
    }
}

/** Une coupe déformée : même dessin, altitudes changées (pour une haute montagne, une basse montagne…). */
private fun TerrainProfile.remap(f: (Double) -> Double) = TerrainProfile(elevations.map(f).toDoubleArray(), lengthKm, placeIndex, labels, peaks)

/** Un sommet pointu au lieu même, les vallées 900 m plus bas, d'autres sommets plus loin. */
private fun summit(top: Double): TerrainProfile {
    val p = 18
    val e = DoubleArray(100) { k ->
        top - 900.0 * minOf(1.0, kotlin.math.abs(k - p) / 12.0) + 25 * kotlin.math.sin(k * 0.7) + if (k > 55) (k - 55) * 12.0 + 150 * kotlin.math.sin(k * 0.4) else 0.0
    }
    return TerrainProfile(e, 25.0, p, emptyList(), emptyList())
}

private fun spots(p: Map<String, TerrainProfile>): Map<String, Spot> {
    fun at(s: Settlement, forest: Boolean = false, river: Boolean = false, lake: Boolean = false, coast: Boolean = false, island: Boolean = false, ski: Boolean = false, people: Long? = null) =
        Surroundings(s, forest, river, lake, coast, island, ski, people)
    val low = p.getValue("morteau").remap { 300 + (it - 771) * 1.5 }
    val high = p.getValue("chamonix").remap { 1041 + (it - 1041) * 0.58 }
    return listOf(
        Spot("paris", "Paris : plaine, grande ville, la Seine", p["paris"], 48.86, at(Settlement.CITY, river = true, people = 2_100_000), Landmark.EIFFEL_TOWER),
        Spot("newyork", "New York : grande ville au bord de la mer", p["newyork"], 40.71, at(Settlement.CITY, coast = true, people = 8_800_000), Landmark.EMPIRE_STATE),
        Spot("lyon", "Lyon : Fourvière au-dessus de la ville", p["paris"], 45.76, at(Settlement.CITY, river = true), Landmark.FOURVIERE),
        Spot("marseille", "Marseille : la Bonne Mère et la mer", p["nice"], 43.30, at(Settlement.CITY, coast = true), Landmark.BONNE_MERE),
        Spot("strasbourg", "Strasbourg : la cathédrale", p["paris"], 48.58, at(Settlement.CITY, river = true), Landmark.STRASBOURG_CATHEDRAL),
        Spot("geneve", "Genève : le jet d'eau sur le lac", p["annecy"], 46.20, at(Settlement.CITY), Landmark.JET_D_EAU),
        Spot("station", "Station de ski : petite ville, télésiège", p["chamonix"], 45.92, at(Settlement.TOWN, forest = true, ski = true)),
        Spot("alpage", "Alpage : chalet isolé", p["annecy"], 45.85, at(Settlement.NONE)),
        Spot("beauce", "Beauce : plaine, ferme isolée", p["paris"], 48.3, at(Settlement.NONE)),
        Spot("bourg", "Plaine : village, rivière, bois", p["paris"], 47.0, at(Settlement.VILLAGE, forest = true, river = true)),
        Spot("valence", "Valence : plaine du Rhône, le Vercors au loin", p["valence"], 44.93, at(Settlement.CITY, river = true, people = 64_000)),
        Spot("morvan", "Basse montagne : petite ville et forêt", low, 47.1, at(Settlement.TOWN, forest = true)),
        Spot("morvan-hameau", "Basse montagne : hameau", low, 47.1, at(Settlement.HAMLET)),
        Spot("nice", "Nice : basse montagne, grande ville, la mer", p["nice"], 43.70, at(Settlement.CITY, coast = true, people = 340_000)),
        Spot("tromso", "Tromsø : collines du Grand Nord, la mer", p["tromso"], 69.65, at(Settlement.CITY, coast = true)),
        Spot("morteau", "Morteau : moyenne montagne, petite ville, forêt", p["morteau"], 47.06, at(Settlement.TOWN, forest = true, river = true, people = 6_343)),
        Spot("jura", "Moyenne montagne : village au clocher comtois", p["morteau"], 47.0, at(Settlement.VILLAGE)),
        Spot("jura-ferme", "Moyenne montagne : ferme isolée, lac", p["morteau"], 46.9, at(Settlement.NONE, lake = true)),
        Spot("annecy", "Annecy : moyenne montagne, grande ville, le lac", p["annecy"], 45.90, at(Settlement.CITY, people = 130_000)),
        Spot("talloires", "Bord du lac : village", p["annecy"], 45.84, at(Settlement.VILLAGE, forest = true)),
        Spot("haute", "Haute montagne : village, sapins", high, 45.45, at(Settlement.VILLAGE, forest = true)),
        Spot("haute-chalet", "Haute montagne : chalet isolé", high, 45.45, at(Settlement.NONE)),
        Spot("chamonix", "Chamonix : très haute montagne, petite ville", p["chamonix"], 45.92, at(Settlement.TOWN, forest = true, river = true)),
        Spot("chamonix-hameau", "Très haute montagne : hameau", p["chamonix"], 45.92, at(Settlement.HAMLET)),
        Spot("sommet", "Au sommet (2 900 m) : refuge possible", summit(2900.0), 45.0, at(Settlement.NONE)),
        Spot("puy", "Au sommet d'un puy (1 460 m)", summit(1460.0), 45.77, at(Settlement.NONE, forest = true)),
        Spot("corse", "Île : petite ville au bord de la mer", p["nice"], 42.7, at(Settlement.TOWN, coast = true, island = true)),
        Spot("ile", "Île : village de pêcheurs", p["tromso"], 47.3, at(Settlement.VILLAGE, coast = true, island = true)),
        Spot("asnans", "Asnans-Beauvoisin : plaine, village", p["asnans"], 46.95, at(Settlement.VILLAGE, people = 560)),
        Spot("v200", "Village de 200 habitants", p["paris"], 47.0, at(Settlement.VILLAGE, people = 200)),
        Spot("v1800", "Village de 1 800 habitants", p["paris"], 47.0, at(Settlement.VILLAGE, people = 1_800)),
        Spot("t3000", "Petite ville de 3 000 habitants", p["paris"], 47.0, at(Settlement.TOWN, people = 3_000)),
        Spot("t40000", "Ville de 40 000 habitants", p["paris"], 47.0, at(Settlement.TOWN, people = 40_000)),
        Spot("c60000", "Grande ville de 60 000 habitants", p["paris"], 47.0, at(Settlement.CITY, people = 60_000)),
        Spot("c2m", "Grande ville de 2 millions d'habitants", p["paris"], 47.0, at(Settlement.CITY, people = 2_000_000)),
        Spot("sans", "Morteau : avant de connaître ses environs", p["morteau"], 47.06, null),
        Spot("montblanc", "Mont Blanc : au sommet, 4 808 m", p["montblanc"], 45.83, at(Settlement.NONE)),
        Spot("montblanc-hl", "Mont Blanc, sans OpenStreetMap", p["montblanc"], 45.83, null),
        Spot("ocean", "En mer : au large de la Bretagne", p["ocean"], 47.0, at(Settlement.NONE, coast = false)),
        Spot("ocean-hl", "En mer, sans OpenStreetMap", p["ocean"], 47.0, null),
        Spot("defaut", "Hors ligne : sans coupe du relief", null, 46.0, null),
    ).associateBy { it.name }
}

private fun catalog(s: Map<String, Spot>): List<Item> {
    val items = ArrayList<Item>()
    val summer = LocalDateTime.of(2026, 7, 8, 15, 0)
    val autumn = LocalDateTime.of(2026, 10, 20, 15, 0)
    val winter = LocalDateTime.of(2027, 1, 19, 14, 0)
    val spring = LocalDateTime.of(2027, 4, 22, 11, 0)
    val nightTime = LocalDateTime.of(2026, 10, 22, 22, 0)
    fun add(section: String, caption: String, spot: String, code: Int, time: LocalDateTime = summer, isDay: Boolean = time.hour in 8..19, sky: Int? = null, dark: Boolean = !isDay, temperature: Int = 12, taps: Int = 0, clouds: SketchClouds? = null, conditions: SketchConditions = SketchConditions(clouds = clouds), style: Int = 1, want: (SketchScene) -> Boolean = { true }) {
        items += Item(section, caption, s.getValue(spot), code, time, isDay, sky, dark, temperature, taps, conditions, style, want)
    }

    var section = "1. Le relief : de la plaine au sommet"
    for (name in listOf("beauce", "valence", "morvan", "jura", "haute", "chamonix", "sommet", "puy")) {
        add(section, s.getValue(name).label + " · été, soleil", name, 0, sky = 0, temperature = 24)
    }
    for (name in listOf("jura", "haute", "chamonix", "sommet")) {
        add(section, s.getValue(name).label + " · hiver, soleil", name, 0, winter, sky = 0, temperature = -3)
    }

    section = "2. L'eau : lac, mer, rivière, île"
    add(section, s.getValue("annecy").label, "annecy", 1, sky = 0, temperature = 25)
    add(section, s.getValue("talloires").label + " · voilier possible", "talloires", 0, sky = 1, temperature = 25)
    add(section, s.getValue("jura-ferme").label + " (lac voisin, hors de la coupe)", "jura-ferme", 0, sky = 0)
    add(section, s.getValue("nice").label, "nice", 0, sky = 0, temperature = 27)
    add(section, s.getValue("tromso").label + " · janvier", "tromso", 2, winter, sky = 0, temperature = -6)
    add(section, s.getValue("bourg").label, "bourg", 2, spring, sky = 2)
    add(section, s.getValue("corse").label, "corse", 0, sky = 1, temperature = 28)
    add(section, s.getValue("ile").label + " · nuit", "ile", 0, nightTime, sky = 0)

    section = "3 bis. La taille selon la population"
    for (name in listOf("v200", "v1800", "t3000", "t40000", "c60000", "c2m", "asnans")) add(section, s.getValue(name).label, name, 1, summer, sky = 0, temperature = 22)

    section = "3. Les lieux habités"
    add(section, s.getValue("paris").label + " · jour", "paris", 2, autumn, sky = 0)
    add(section, s.getValue("paris").label + " · nuit, fenêtres allumées", "paris", 0, nightTime, sky = 0)
    add(section, s.getValue("valence").label, "valence", 3, autumn, sky = 0)
    add(section, s.getValue("morteau").label, "morteau", 1, autumn, sky = 0)
    add(section, s.getValue("morvan").label, "morvan", 2, autumn, sky = 1)
    add(section, s.getValue("jura").label, "jura", 0, summer, sky = 1, taps = 1)
    add(section, s.getValue("bourg").label + " (un autre jour, autre clocher)", "bourg", 0, summer, sky = 2, taps = 4)
    add(section, s.getValue("morvan-hameau").label, "morvan-hameau", 1, spring, sky = 2)
    add(section, s.getValue("chamonix-hameau").label, "chamonix-hameau", 0, winter, sky = 1)
    add(section, s.getValue("beauce").label, "beauce", 0, summer, sky = 2)
    add(section, s.getValue("haute-chalet").label, "haute-chalet", 2, autumn, sky = 0)
    add(section, s.getValue("sommet").label, "sommet", 0, summer, sky = 1, taps = 3)

    section = "4. La forêt et les saisons"
    for ((t, name) in listOf(spring to "printemps", summer to "été", autumn to "automne", winter to "hiver")) {
        add(section, s.getValue("morvan").label + " · $name", "morvan", 1, t, sky = 0)
    }
    for ((t, name) in listOf(spring to "printemps", summer to "été (blé mûr)", autumn to "automne (labours)", winter to "hiver")) {
        add(section, s.getValue("bourg").label + " · $name", "bourg", 0, t, sky = 1)
    }
    for ((t, name) in listOf(summer to "été", winter to "hiver")) {
        add(section, s.getValue("morteau").label + " · $name", "morteau", 1, t, sky = 2)
    }

    section = "5. Le soleil et la nuit, en plusieurs versions"
    for (v in 0..2) add(section, "Grand soleil · version ${v + 1}", "jura", 0, summer, sky = v, temperature = 26)
    for (v in 0..2) add(section, "Nuit claire · version ${v + 1}", "jura", 0, nightTime, sky = v, temperature = 4)
    add(section, "Nuit claire sans lune (nouvelle lune) · voie lactée", "haute", 0, LocalDateTime.of(2026, 11, 9, 23, 0), sky = 2, temperature = -2)
    add(section, "Nuit claire, fin croissant", "beauce", 0, LocalDateTime.of(2026, 11, 12, 21, 0), sky = 0, temperature = 3)

    section = "6. Nuages et brouillard"
    for (v in 0..2) add(section, "Éclaircies · version ${v + 1}", "morteau", 2, autumn, sky = v)
    add(section, "Éclaircies de nuit", "morteau", 2, nightTime, sky = 0)
    for (v in 0..2) add(section, "Couvert · version ${v + 1}", "paris", 3, autumn, sky = v)
    for (v in 0..2) add(section, "Brouillard · version ${v + 1}", "bourg", 45, autumn, sky = v, temperature = 6)
    add(section, "Brouillard de vallée en montagne", "chamonix", 45, autumn, sky = 1, temperature = 3)
    add(section, "Mer de nuages, vue du sommet", "sommet", 45, autumn, sky = 0, temperature = 8)

    section = "6 bis. La hauteur des nuages (prévision bas, moyens, hauts)"
    val low = SketchClouds(0.9f, 0.2f, 0f)
    val middle = SketchClouds(0.1f, 0.9f, 0.2f)
    val high = SketchClouds(0f, 0.1f, 0.9f)
    add(section, "Couvert, nuages moyens : à leur place", "chamonix", 3, autumn, sky = 0, clouds = middle)
    add(section, "Couvert, nuages bas : ils coiffent les sommets", "chamonix", 3, autumn, sky = 0, clouds = low)
    add(section, "Pluie sous des nuages moyens", "haute", 63, autumn, sky = 0, clouds = middle)
    add(section, "Pluie sous des nuages bas accrochés aux sommets", "haute", 63, autumn, sky = 0, clouds = low)
    add(section, "Neige, nuages bas sur le Jura", "jura", 73, winter, sky = 0, temperature = -2, clouds = low)
    add(section, "Nuages bas en plaine : un peu plus bas", "paris", 3, autumn, sky = 1, clouds = low)
    add(section, "Voile de nuages hauts : le soleil pâle au travers", "bourg", 3, autumn, clouds = high)
    add(section, "Beau temps et cirrus très haut", "morvan", 1, summer, sky = 0, clouds = SketchClouds(0f, 0f, 0.6f), temperature = 25)
    add(section, "Au sommet, nuages bas : la mer de nuages en dessous", "sommet", 3, autumn, sky = 0, clouds = low, temperature = 4)
    add(section, "Au sommet, nuages moyens : au-dessus de la croix", "sommet", 3, autumn, sky = 1, clouds = middle, temperature = 2)

    section = "7. Pluie et neige, de faible à forte"
    add(section, "Bruine", "paris", 53, autumn, sky = 0)
    add(section, "Pluie verglaçante", "jura", 66, winter, sky = 0, temperature = -1)
    for ((code, name) in listOf(61 to "faible", 63 to "modérée", 65 to "forte")) add(section, "Pluie $name · traits", "morteau", code, autumn, sky = 0)
    add(section, "Pluie modérée · chassée par le vent", "nice", 63, autumn, sky = 1)
    add(section, "Pluie modérée · gouttes et flaques", "bourg", 63, autumn, sky = 2)
    add(section, "Pluie forte · gros nuages", "valence", 65, autumn, sky = 1)
    add(section, "Averses, éclaircie", "morvan", 80, spring, sky = 0)
    add(section, "Averse et arc-en-ciel", "talloires", 81, spring, want = { Surprise.RAINBOW in it.surprises })
    for ((code, name) in listOf(71 to "faible", 73 to "modérée", 75 to "forte")) add(section, "Neige $name · petits flocons", "jura", code, winter, sky = 0, temperature = -2)
    add(section, "Neige · gros flocons étoilés", "chamonix", 73, winter, sky = 1, temperature = -4)
    add(section, "Neige · flocons ronds", "haute", 75, winter, sky = 2, temperature = -5)
    add(section, "Neige en grains", "morvan", 77, winter, sky = 0, temperature = 0)
    add(section, "Averses de neige", "haute-chalet", 85, winter, sky = 0, temperature = -3)

    section = "8. Orages"
    for (v in 0..2) add(section, "Orage · version ${v + 1}", "valence", 95, summer, sky = v, temperature = 28)
    add(section, "Orage violent en montagne", "chamonix", 99, summer, sky = 2, temperature = 22)
    add(section, "Orage de nuit", "paris", 95, LocalDateTime.of(2026, 7, 8, 23, 0), sky = 1, temperature = 21)

    section = "9. Surprises et jours de fête"
    add(section, "Noël : le traîneau", "jura", 3, LocalDateTime.of(2026, 12, 24, 23, 0), temperature = -1)
    add(section, "Saint-Sylvestre : feu d'artifice", "paris", 0, LocalDateTime.of(2026, 12, 31, 23, 30), temperature = 2)
    add(section, "14 juillet : feu d'artifice", "valence", 0, LocalDateTime.of(2026, 7, 14, 23, 0), temperature = 22)
    add(section, "Halloween : citrouille", "morteau", 3, LocalDateTime.of(2026, 10, 31, 15, 0))
    add(section, "Halloween la nuit : chauves-souris", "morteau", 0, LocalDateTime.of(2026, 10, 31, 22, 0))
    add(section, "Pâques : les œufs", "bourg", 1, LocalDateTime.of(2027, 3, 28, 11, 0))
    add(section, "1er mai : le muguet", "morvan", 1, LocalDateTime.of(2027, 5, 1, 11, 0))
    add(section, "Saint-Valentin : le cœur", "annecy", 0, LocalDateTime.of(2027, 2, 14, 12, 0))
    add(section, "Neige : bonhomme de neige (parfois)", "jura", 73, LocalDateTime.of(2027, 1, 10, 13, 0), sky = 0, temperature = -2, want = { Surprise.SNOWMAN in it.surprises })
    add(section, "7 touches du dessin : la soucoupe", "beauce", 0, summer, taps = SketchScenes.ABDUCTION_TAPS)

    section = "10. La lumière : aube, heure dorée, crépuscule"
    val rise = LocalDateTime.of(2026, 7, 8, 6, 0)
    val set = LocalDateTime.of(2026, 7, 8, 21, 30)
    val sun = SketchConditions(sunrise = rise, sunset = set)
    add(section, "Aube : le ciel violet et rose, les dernières étoiles", "jura", 0, LocalDateTime.of(2026, 7, 8, 5, 35), isDay = false, sky = 0, conditions = sun, temperature = 9)
    add(section, "Lever du soleil : il sort de derrière le relief, à gauche", "chamonix", 0, LocalDateTime.of(2026, 7, 8, 6, 20), isDay = true, sky = 0, conditions = sun, temperature = 8)
    add(section, "Heure dorée du soir en plaine", "beauce", 0, LocalDateTime.of(2026, 7, 8, 21, 0), isDay = true, sky = 1, conditions = sun, temperature = 23)
    add(section, "Coucher de soleil derrière les montagnes", "haute", 1, LocalDateTime.of(2026, 7, 8, 21, 10), isDay = true, sky = 0, conditions = sun, temperature = 18)
    add(section, "Coucher de soleil sur la mer, avec des nuages", "nice", 2, LocalDateTime.of(2026, 7, 8, 21, 5), isDay = true, sky = 0, conditions = sun, temperature = 26)
    add(section, "Crépuscule en ville", "paris", 0, LocalDateTime.of(2026, 7, 8, 21, 50), isDay = false, sky = 0, conditions = sun, temperature = 20)

    section = "11. Le vent, la neige au sol, le froid, l'air"
    add(section, "Vent fort d'ouest (60 km/h) : arbres couchés, fumée à plat, manche à air tendue", "bourg", 2, autumn, sky = 0, conditions = SketchConditions(windKmh = 60.0, windFrom = 260.0))
    add(section, "Vent d'est modéré (30 km/h)", "morteau", 1, autumn, sky = 0, conditions = SketchConditions(windKmh = 30.0, windFrom = 80.0))
    add(section, "Tempête : pluie chassée par le vent", "beauce", 65, autumn, sky = 0, conditions = SketchConditions(windKmh = 75.0, windFrom = 250.0))
    add(section, "Neige au sol, grand soleil : toits blancs, bonhomme de neige", "jura", 0, winter, sky = 0, temperature = -4, conditions = SketchConditions(snowOnGround = true), want = { Surprise.SNOWMAN in it.surprises })
    add(section, "Neige au sol en plaine", "bourg", 3, winter, sky = 0, temperature = -1, conditions = SketchConditions(snowOnGround = true))
    add(section, "Grand froid : le lac est gelé, on patine", "talloires", 0, winter, sky = 0, temperature = -9, conditions = SketchConditions(frozen = true, snowOnGround = true))
    add(section, "Lac d'Annecy gelé (froid durable)", "talloires", 1, winter, sky = 1, temperature = -7, conditions = SketchConditions(frozen = true))
    add(section, "Sable du Sahara : ciel jaune, soleil pâle", "valence", 1, spring, sky = 0, temperature = 21, conditions = SketchConditions(dust = true))
    add(section, "Pollution : le lointain se voile", "paris", 1, summer, sky = 0, temperature = 31, conditions = SketchConditions(haze = true))

    section = "12. Les bêtes, le ski, les monuments"
    add(section, "Vaches à l'alpage, l'été", "alpage", 0, summer, sky = 0, temperature = 22, want = { Decor.COWS in it.decor })
    add(section, "Chamois dans les rochers", "chamonix-hameau", 1, summer, sky = 1, temperature = 18, want = { Decor.CHAMOIS in it.decor })
    add(section, "Mouettes au bord de la mer", "nice", 1, summer, sky = 2, temperature = 27)
    add(section, "Station de ski l'hiver : télésiège et pistes", "station", 0, winter, sky = 0, temperature = -5, conditions = SketchConditions(snowOnGround = true))
    add(section, "Station de ski l'été : le télésiège seul", "station", 2, summer, sky = 0, temperature = 19)
    add(section, "Paris : la tour Eiffel", "paris", 1, summer, sky = 0, temperature = 24)
    add(section, "Lyon : Fourvière", "lyon", 2, autumn, sky = 0)
    add(section, "Marseille : Notre-Dame de la Garde", "marseille", 0, summer, sky = 1, temperature = 29)
    add(section, "Strasbourg : la cathédrale", "strasbourg", 3, autumn, sky = 0)
    add(section, "Genève : le jet d'eau", "geneve", 1, summer, sky = 0, temperature = 25)
    add(section, "Paris la nuit", "paris", 0, nightTime, sky = 1)
    add(section, "New York : l'Empire State Building", "newyork", 1, summer, sky = 0, temperature = 29)
    add(section, "New York la nuit", "newyork", 0, nightTime, sky = 0, temperature = 18)

    section = "13. Les couleurs du dessin (réglage)"
    for ((style, name) in listOf(0 to "discret", 1 to "normal", 2 to "coloré")) {
        add(section, "Dessin $name", "chamonix", 2, summer, sky = 0, temperature = 21, style = style)
    }
    add(section, "Dessin coloré, thème sombre", "annecy", 0, nightTime, sky = 2, style = 2)

    section = "14. Thème sombre et cas limites"
    add(section, s.getValue("montblanc").label + " · été", "montblanc", 0, summer, sky = 0, temperature = -6)
    add(section, s.getValue("montblanc-hl").label + " (pas de village inventé)", "montblanc-hl", 2, autumn, sky = 1, temperature = -12)
    add(section, s.getValue("ocean").label, "ocean", 1, summer, sky = 0, temperature = 19)
    add(section, s.getValue("ocean-hl").label + " (pas de village inventé)", "ocean-hl", 3, autumn, sky = 1, temperature = 15)
    add(section, "Thème sombre, de jour : Annecy", "annecy", 2, summer, isDay = true, sky = 2, dark = true)
    add(section, "Thème sombre, de jour : Chamonix sous la neige", "chamonix", 73, winter, isDay = true, sky = 0, dark = true)
    add(section, s.getValue("sans").label, "sans", 0, summer, sky = 0)
    add(section, s.getValue("defaut").label + " (en attendant la coupe)", "defaut", 2, summer, sky = 0)
    return items
}

/** La scène de [item] ; avec [Item.sky], on touche le dessin jusqu'à tomber sur cette version du ciel. */
private fun sceneOf(item: Item): Pair<SketchTerrain, SketchScene> {
    val month = SketchScenes.seasonMonth(item.time.monthValue, item.spot.latitude)
    val snowing = SketchSky.of(item.code) == SketchSky.SNOW || item.conditions.snowOnGround
    val terrain = SketchTerrains.from(item.spot.profile, month, snowing, item.spot.latitude, item.spot.around)
    val seed = item.spot.name.hashCode()
    fun at(taps: Int) = SketchScenes.scene(item.code, item.isDay, item.time, seed, terrain, taps, item.spot.around, item.conditions, item.spot.landmark)
    val scene = (item.taps until item.taps + 200).filter { it == item.taps || it % SketchScenes.ABDUCTION_TAPS != 0 }.map(::at)
        .firstOrNull { (item.sky == null || it.skyVariant == item.sky) && item.want(it) }
        ?: error("Aucune version ne convient : ${item.caption}")
    return terrain to scene
}

private val fontDir = File("../../app/src/main/res/font")
private fun font(name: String, size: Float) = Font(FontMgr.default.makeFromFile(File(fontDir, "$name.ttf").path), size * DENSITY)

/** Les symboles de la maquette (la montagne de la pastille, l'étoile), absents des polices de l'appli. */
private fun symbols(size: Float) = Font(FontMgr.default.matchFamilyStyle("DejaVu Sans", org.jetbrains.skia.FontStyle.NORMAL), size * DENSITY)

/** Faux texte de l'écran, aux places et tailles de l'appli : le lieu, la grande température, le ciel, les extrêmes. */
private fun overlay(canvas: Canvas, item: Item, width: Float) {
    val c = canvas.nativeCanvas
    val ink = org.jetbrains.skia.Paint().apply { color = (if (item.dark) Color(0xFFEAF2F8) else Color(0xFF0F2133)).toArgb(); isAntiAlias = true }
    val soft = org.jetbrains.skia.Paint().apply { color = (if (item.dark) Color(0xFF9FB2C4) else Color(0xFF5A6B7C)).toArgb(); isAntiAlias = true }
    val accent = org.jetbrains.skia.Paint().apply { color = Color(0xFFE8711C).toArgb(); isAntiAlias = true }
    val blue = org.jetbrains.skia.Paint().apply { color = (if (item.dark) Color(0xFF7DB4F0) else Color(0xFF1F5FA8)).toArgb(); isAntiAlias = true }
    fun dp(v: Number) = v.toFloat() * DENSITY
    c.drawString(item.spot.label.substringBefore(" :"), dp(18), dp(30), font("archivo_semibold", 22f), ink)
    c.drawString(item.spot.label.substringAfter(": ", "").substringBefore(",").substringBefore(" ·"), dp(18), dp(47), font("archivo_regular", 12f), soft)
    c.drawString("Mis à jour il y a 5 min", dp(18), dp(66), font("martian_mono_condensed_regular", 10f), soft)
    // La pastille Neige & altitude, à gauche de l'étoile des favoris (à la saison de la neige).
    if (item.time.monthValue !in 4..9) {
        val chip = org.jetbrains.skia.Rect.makeXYWH(width - dp(168), dp(18), dp(102), dp(30))
        val line = org.jetbrains.skia.Paint().apply { color = soft.color; mode = org.jetbrains.skia.PaintMode.STROKE; strokeWidth = dp(1); isAntiAlias = true }
        c.drawRRect(org.jetbrains.skia.RRect.makeLTRB(chip.left, chip.top, chip.right, chip.bottom, dp(14)), line)
        c.drawString("▲", chip.left + dp(8), chip.top + dp(20), symbols(13f), blue)
        c.drawString("0° 1 800 m", chip.left + dp(25), chip.top + dp(20), font("martian_mono_condensed_medium", 11f), blue)
    }
    c.drawString("☆", width - dp(50), dp(44), symbols(28f), soft)
    val big = font("archivo_condensed_light", 112f)
    val temp = item.temperature.toString().replace("-", "−")
    c.drawString(temp, dp(18), dp(168), big, ink)
    val tw = big.measureTextWidth(temp)
    c.drawString("°C", dp(18) + tw, dp(112), font("archivo_regular", 26f), ink)
    c.drawString("ressenti ${item.temperature - 2}°", dp(22), dp(196), font("martian_mono_condensed_regular", 13f), ink)
    val right = width - dp(18)
    val sky = font("archivo_semibold", 17f)
    val words = describe(item.code)
    c.drawString(words, right - sky.measureTextWidth(words), dp(168), sky, ink)
    val mono = font("martian_mono_condensed_medium", 13f)
    val lo = " / ${item.temperature - 6}°"
    val hi = "${item.temperature + 3}°"
    c.drawString(lo, right - mono.measureTextWidth(lo), dp(188), mono, blue)
    c.drawString(hi, right - mono.measureTextWidth(lo) - mono.measureTextWidth(hi), dp(188), mono, accent)
}

private fun describe(code: Int) = when (code) {
    0 -> "Ensoleillé"
    1 -> "Plutôt ensoleillé"
    2 -> "Éclaircies"
    3 -> "Couvert"
    45, 48 -> "Brouillard"
    51, 53, 55 -> "Bruine"
    56, 57, 66, 67 -> "Pluie verglaçante"
    61 -> "Pluie faible"
    63 -> "Pluie"
    65 -> "Pluie forte"
    71 -> "Neige faible"
    73 -> "Neige"
    75 -> "Neige forte"
    77 -> "Neige en grains"
    80, 81, 82 -> "Averses"
    85, 86 -> "Averses de neige"
    95 -> "Orage"
    else -> "Orage violent"
}

private fun render(item: Item, out: File) {
    val (_, scene) = sceneOf(item)
    val w = (WIDTH_DP * DENSITY).toInt()
    val h = (HEIGHT_DP * DENSITY).toInt()
    val size = Size(w.toFloat(), h.toFloat())
    // Le dessin sur son propre calque transparent (comme dans l'appli), puis le fond, le dessin et le texte.
    val sketch = ImageBitmap(w, h)
    val strength = sketchStrength(item.style)
    val graphite = sketchGraphite(item.dark, strength)
    val month = SketchScenes.seasonMonth(item.time.monthValue, item.spot.latitude)
    CanvasDrawScope().draw(Density(DENSITY), LayoutDirection.Ltr, Canvas(sketch), size) {
        val pencil = Pencil(this, graphite, Random(scene.seed * 31 + item.code), item.dark, strength)
        drawScene(pencil, scene, item.isDay, SketchScenes.moonAge(item.time), southern = item.spot.latitude < 0, month = month)
    }
    val page = ImageBitmap(w, h)
    val canvas = Canvas(page)
    canvas.drawRect(0f, 0f, size.width, size.height, Paint().apply { color = if (item.dark) Color(0xFF0B1826) else Color(0xFFF7FAFC) })
    canvas.drawImage(sketch, Offset.Zero, Paint())
    overlay(canvas, item, size.width)
    val png = Image.makeFromBitmap(page.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG) ?: error("PNG")
    out.writeBytes(png.bytes)
}

private val RELIEF = mapOf(
    Relief.PLAIN to "plaine", Relief.LOW to "basse montagne", Relief.MEDIUM to "moyenne montagne",
    Relief.HIGH to "haute montagne", Relief.VERY_HIGH to "très haute montagne", Relief.SUMMIT to "au sommet",
)

private val DECOR = mapOf(
    Decor.FARM to "ferme", Decor.CHALET to "chalet", Decor.REFUGE to "refuge", Decor.HAMLET to "hameau", Decor.VILLAGE to "village",
    Decor.TOWN to "petite ville", Decor.CITY to "grande ville", Decor.FIELDS to "champs", Decor.TREES to "arbres", Decor.FIRS to "sapins",
    Decor.FOREST to "forêt", Decor.LIGHTHOUSE to "phare", Decor.COWS to "vaches", Decor.CHAMOIS to "chamois",
    Decor.GULLS to "mouettes", Decor.SKI to "station de ski", Decor.WINDSOCK to "manche à air",
)

fun main(args: Array<String>) {
    val profiles = profiles(File(args[0]))
    val dir = File(args[1]).apply { mkdirs() }
    val items = catalog(spots(profiles))
    val index = StringBuilder()
    items.forEachIndexed { k, item ->
        val (terrain, scene) = sceneOf(item)
        val name = "%03d.png".format(k)
        render(item, File(dir, name))
        val decor = scene.decor.sortedBy { it.ordinal }.joinToString(", ") { DECOR[it] ?: it.name.lowercase() }
        val water = listOfNotNull("lac".takeIf { terrain.water.isNotEmpty() }, "mer".takeIf { terrain.sea }, "rivière".takeIf { terrain.river }, "île".takeIf { terrain.island })
        val detail = (listOf(RELIEF.getValue(terrain.relief)) + listOf(decor).filter { it.isNotEmpty() } + water + "ciel, version ${scene.skyVariant + 1}").joinToString(" · ")
        index.append(item.section).append('\t').append(name).append('\t').append(item.caption).append('\t').append(detail).append('\n')
    }
    File(dir, "catalogue.tsv").writeText(index.toString())
    println("${items.size} dessins dans $dir")
}
