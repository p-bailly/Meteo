package io.github.pbailly.meteo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.hypot
import kotlin.math.sqrt

/** Un lieu habité d'OpenStreetMap (nœud « place »), à [distanceKm] du lieu. */
data class SettlementNode(val kind: String, val population: Long?, val distanceKm: Double)

/** Requête Overpass et lecture de sa réponse. Sans réseau, pour les tests. */
object SurroundingsQuery {
    const val FOREST_M = 1500
    const val RIVER_M = 1000
    const val LAKE_M = 2000
    const val COAST_M = 3000
    const val SKI_M = 3000
    const val CROPS_M = 1500
    const val HERITAGE_M = 3000
    const val RUINS_M = 1500
    const val TURBINES_M = 8000
    const val RAILWAY_M = 1500
    const val HARBOUR_M = 2000
    const val LIGHTHOUSE_M = 5000

    /** Une île plus grande que ça (Grande-Bretagne, Irlande) ne se dessine pas comme une île. */
    private const val MAX_ISLAND_KM = 350.0

    fun url(latitude: Double, longitude: Double, server: String = Overpass.SERVERS.first()): String {
        val at = "%.5f,%.5f".format(Locale.ROOT, latitude, longitude)
        fun around(m: Int) = "(around:$m,$at)"
        // Les réponses arrivent dans l'ordre : lieux habités, puis un compte par question, les îles, et le ski. Pas de
        // recherche de relations autour du lieu (grandes forêts, grands lacs) : c'est ce qui rendait la requête lente ;
        // les grandes forêts sont trouvées quand le lieu est dedans, les grands lacs par la coupe du relief.
        val query = "[out:json][timeout:30];" +
            "(node[\"place\"~\"^(city|town)$\"]${around(15000)};node[\"place\"~\"^(village|hamlet|isolated_dwelling)$\"]${around(2500)};);out body;" +
            "(way[\"landuse\"=\"forest\"]${around(FOREST_M)};way[\"natural\"=\"wood\"]${around(FOREST_M)};);out count;" +
            "(way[\"waterway\"=\"river\"]${around(RIVER_M)};way[\"water\"=\"river\"]${around(RIVER_M)};);out count;" +
            "way[\"natural\"=\"water\"][\"water\"~\"^(lake|reservoir)$\"]${around(LAKE_M)};out count;" +
            "way[\"natural\"=\"coastline\"]${around(COAST_M)};out count;" +
            "is_in($at)->.a;" +
            "(area.a[\"landuse\"=\"forest\"];area.a[\"natural\"=\"wood\"];);out count;" +
            "(way[\"place\"~\"^(island|islet)$\"](pivot.a);relation[\"place\"~\"^(island|islet)$\"](pivot.a););out tags bb;" +
            "(way[\"piste:type\"=\"downhill\"]${around(SKI_M)};way[\"landuse\"=\"winter_sports\"]${around(SKI_M)};" +
            "way[\"aerialway\"~\"^(chair_lift|gondola|cable_car|mixed_lift|drag_lift|t-bar|j-bar|platter)$\"]${around(SKI_M)};);out count;" +
            // Puis le paysage et le patrimoine : vignes, vergers, château, ruines, abbaye, éoliennes, train, port, phare.
            "way[\"landuse\"=\"vineyard\"]${around(CROPS_M)};out count;" +
            "way[\"landuse\"=\"orchard\"]${around(CROPS_M)};out count;" +
            "(node[\"historic\"=\"castle\"]${around(HERITAGE_M)};way[\"historic\"=\"castle\"]${around(HERITAGE_M)};);out count;" +
            "(node[\"historic\"=\"ruins\"]${around(RUINS_M)};way[\"historic\"=\"ruins\"]${around(RUINS_M)};);out count;" +
            "(nwr[\"amenity\"=\"monastery\"]${around(HERITAGE_M)};nwr[\"historic\"=\"monastery\"]${around(HERITAGE_M)};);out count;" +
            "node[\"generator:source\"=\"wind\"]${around(TURBINES_M)};out count;" +
            "way[\"railway\"=\"rail\"]${around(RAILWAY_M)};out count;" +
            "(nwr[\"harbour\"=\"yes\"]${around(HARBOUR_M)};nwr[\"leisure\"=\"marina\"]${around(HARBOUR_M)};way[\"landuse\"=\"harbour\"]${around(HARBOUR_M)};);out count;" +
            "nwr[\"man_made\"=\"lighthouse\"]${around(LIGHTHOUSE_M)};out count;"
        return "$server?data=" + URLEncoder.encode(query, "UTF-8")
    }

    fun parse(body: String, latitude: Double, longitude: Double): Surroundings {
        val elements = Json.parseToJsonElement(body).jsonObject["elements"] as? JsonArray ?: throw IOException("Réponse Overpass vide")
        val nodes = ArrayList<SettlementNode>()
        val counts = ArrayList<Int>()
        var island = false
        for (e in elements) {
            val o = e.jsonObject
            val tags = o["tags"] as? JsonObject
            fun tag(key: String) = tags?.get(key)?.jsonPrimitive?.contentOrNull
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "count" -> counts += tag("total")?.toIntOrNull() ?: 0
                "node" -> {
                    val kind = tag("place") ?: continue
                    val lat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: continue
                    val lon = o["lon"]?.jsonPrimitive?.doubleOrNull ?: continue
                    nodes += SettlementNode(kind, parsePopulation(tag("population")), Terrain.distanceKm(latitude, longitude, lat, lon))
                }
                "way", "relation" -> if (tag("place") == "island" || tag("place") == "islet") {
                    val b = o["bounds"] as? JsonObject
                    val size = b?.let {
                        val minLat = it["minlat"]?.jsonPrimitive?.doubleOrNull ?: return@let null
                        val maxLat = it["maxlat"]?.jsonPrimitive?.doubleOrNull ?: return@let null
                        val minLon = it["minlon"]?.jsonPrimitive?.doubleOrNull ?: return@let null
                        val maxLon = it["maxlon"]?.jsonPrimitive?.doubleOrNull ?: return@let null
                        hypot(Terrain.distanceKm(minLat, minLon, maxLat, minLon), Terrain.distanceKm(minLat, minLon, minLat, maxLon))
                    }
                    if (size == null || size < MAX_ISLAND_KM) island = true
                }
            }
        }
        fun count(k: Int) = counts.getOrElse(k) { 0 } > 0
        val (settlement, population) = place(nodes)
        return Surroundings(
            settlement = settlement,
            population = population,
            forest = count(0) || count(4),
            river = count(1),
            lake = count(2),
            coast = count(3),
            island = island,
            ski = count(5),
            vineyard = count(6),
            orchard = count(7),
            castle = count(8),
            ruins = count(9),
            abbey = count(10),
            windTurbines = count(11),
            railway = count(12),
            harbour = count(13),
            lighthouse = count(14),
        )
    }

    /**
     * Le plus grand lieu habité qui couvre le lieu : une ville s'étend d'autant plus loin de son centre qu'elle est
     * peuplée (Paris sur 8 km, une ville de 50 000 habitants sur 2 km, un village sur 700 m).
     */
    fun settlement(nodes: List<SettlementNode>): Settlement = place(nodes).first

    /** Le lieu habité et sa population (estimée d'après son type quand OpenStreetMap ne la donne pas). */
    fun place(nodes: List<SettlementNode>): Pair<Settlement, Long?> {
        val best = nodes.mapNotNull { n ->
            val people = n.population ?: when (n.kind) {
                "city" -> 150_000L
                "town" -> 8_000L
                "village" -> 500L
                "hamlet" -> 50L
                else -> 5L
            }
            people.takeIf { n.distanceKm <= 0.6 + sqrt(people.toDouble()) / 180 }
        }.maxOrNull() ?: return Settlement.NONE to null
        val kind = when {
            best >= 50_000 -> Settlement.CITY
            best >= 2_000 -> Settlement.TOWN
            best >= 150 -> Settlement.VILLAGE
            else -> Settlement.HAMLET
        }
        return kind to best
    }

    /**
     * Le lieu habité d'après le géocodeur d'Open-Meteo (recherche par nom, avec la population), quand OpenStreetMap ne
     * répond pas : le même calcul que pour les nœuds d'OpenStreetMap, sur les résultats proches du lieu.
     */
    fun geocodedSettlement(body: String, latitude: Double, longitude: Double): Settlement? = geocoded(body, latitude, longitude)?.first

    fun geocoded(body: String, latitude: Double, longitude: Double): Pair<Settlement, Long?>? {
        val results = Json.parseToJsonElement(body).jsonObject["results"] as? JsonArray ?: return null
        val nodes = results.mapNotNull { e ->
            val o = e.jsonObject
            val lat = o["latitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val lon = o["longitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val population = o["population"]?.jsonPrimitive?.doubleOrNull?.toLong()
            val kind = when {
                o["feature_code"]?.jsonPrimitive?.contentOrNull == "PPLC" -> "city"
                population == null -> "village"
                population >= 50_000 -> "city"
                population >= 2_000 -> "town"
                else -> "village"
            }
            SettlementNode(kind, population, Terrain.distanceKm(latitude, longitude, lat, lon))
        }.filter { it.distanceKm <= 30 }
        return if (nodes.isEmpty()) null else place(nodes)
    }

    /** « 2165423 », « 2 165 423 », « 12,345 », « ~5000 » : le nombre d'habitants, ou null. */
    fun parsePopulation(raw: String?): Long? {
        val digits = raw?.let { Regex("[0-9][0-9 ,.  ]*").find(it)?.value } ?: return null
        return digits.filter { it.isDigit() }.toLongOrNull()
    }
}

/** L'environnement d'un lieu, gardé sur le téléphone : on ne le demande qu'une fois à OpenStreetMap. */
class SurroundingsRepository(
    private val cacheDir: File,
    private val httpGet: (String) -> String,
    private val overpassGet: (String) -> String = httpGet,
) {
    private fun file(place: Place) = File(cacheDir, "surroundings3_${place.id}.json")

    /**
     * Les environs déjà gardés, et s'il faut encore demander à OpenStreetMap : oui pour un repli (seulement la taille de
     * la ville) de plus de quelques heures.
     */
    suspend fun cached(place: Place): Pair<Surroundings, Boolean>? = withContext(Dispatchers.IO) {
        val file = file(place)
        if (!file.exists()) return@withContext null
        val (found, partial) = runCatching { decode(file.readText()) }.getOrNull() ?: return@withContext null
        found to (partial && System.currentTimeMillis() - file.lastModified() >= PARTIAL_MAX_AGE_MS)
    }

    /** Vite, d'après le géocodeur d'Open-Meteo : seulement la taille de la ville (sa population). */
    suspend fun quick(place: Place): Surroundings = withContext(Dispatchers.IO) {
        val (settlement, population) = SurroundingsQuery.geocoded(httpGet(OpenMeteoApi.searchUrl(place.name)), place.latitude, place.longitude)
            ?: throw IOException("Lieu inconnu du géocodeur")
        val partial = Surroundings(settlement, population = population)
        if (!file(place).exists()) {
            cacheDir.mkdirs()
            file(place).writeText(encode(partial, partial = true))
        }
        partial
    }

    /** Tout, d'après OpenStreetMap (lent : 15 à 25 s), sur le premier serveur qui répond ; gardé sur le téléphone. */
    suspend fun full(place: Place): Surroundings = withContext(Dispatchers.IO) {
        var failure: Exception = IOException("Aucun serveur Overpass ne répond")
        for (server in Overpass.SERVERS) {
            try {
                val found = SurroundingsQuery.parse(overpassGet(SurroundingsQuery.url(place.latitude, place.longitude, server)), place.latitude, place.longitude)
                cacheDir.mkdirs()
                file(place).writeText(encode(found, partial = false))
                return@withContext found
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        // Le repli est redemandé plus tard : on rafraîchit sa date.
        file(place).takeIf { it.exists() }?.setLastModified(System.currentTimeMillis())
        throw failure
    }

    private fun encode(s: Surroundings, partial: Boolean): String = buildJsonObject {
        put("partial", partial)
        put("settlement", s.settlement.name)
        put("forest", s.forest)
        put("river", s.river)
        put("lake", s.lake)
        put("coast", s.coast)
        put("island", s.island)
        put("ski", s.ski)
        put("vineyard", s.vineyard)
        put("orchard", s.orchard)
        put("castle", s.castle)
        put("ruins", s.ruins)
        put("abbey", s.abbey)
        put("windTurbines", s.windTurbines)
        put("railway", s.railway)
        put("harbour", s.harbour)
        put("lighthouse", s.lighthouse)
        s.population?.let { put("population", it) }
    }.toString()

    /** Les environs gardés, et s'ils viennent du repli. */
    private fun decode(raw: String): Pair<Surroundings, Boolean> {
        val o = Json.parseToJsonElement(raw).jsonObject
        fun flag(key: String) = o[key]?.jsonPrimitive?.booleanOrNull ?: false
        return Surroundings(
            settlement = Settlement.valueOf(o["settlement"]!!.jsonPrimitive.content),
            forest = flag("forest"),
            river = flag("river"),
            lake = flag("lake"),
            coast = flag("coast"),
            island = flag("island"),
            ski = flag("ski"),
            population = o["population"]?.jsonPrimitive?.longOrNull,
            vineyard = flag("vineyard"),
            orchard = flag("orchard"),
            castle = flag("castle"),
            ruins = flag("ruins"),
            abbey = flag("abbey"),
            windTurbines = flag("windTurbines"),
            railway = flag("railway"),
            harbour = flag("harbour"),
            lighthouse = flag("lighthouse"),
        ) to flag("partial")
    }

    private companion object {
        const val PARTIAL_MAX_AGE_MS = 6 * 60 * 60 * 1000L
    }
}
