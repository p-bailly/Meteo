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

    /** Une île plus grande que ça (Grande-Bretagne, Irlande) ne se dessine pas comme une île. */
    private const val MAX_ISLAND_KM = 350.0

    fun url(latitude: Double, longitude: Double, server: String = Overpass.SERVERS.first()): String {
        val at = "%.5f,%.5f".format(Locale.ROOT, latitude, longitude)
        fun around(m: Int) = "(around:$m,$at)"
        // Les réponses arrivent dans l'ordre : lieux habités, puis un compte par question, les îles, et le ski.
        val query = "[out:json][timeout:30];" +
            "(node[\"place\"~\"^(city|town)$\"]${around(15000)};node[\"place\"~\"^(village|hamlet|isolated_dwelling)$\"]${around(2500)};);out body;" +
            "(way[\"landuse\"=\"forest\"]${around(FOREST_M)};way[\"natural\"=\"wood\"]${around(FOREST_M)};" +
            "relation[\"landuse\"=\"forest\"]${around(FOREST_M)};relation[\"natural\"=\"wood\"]${around(FOREST_M)};);out count;" +
            "(way[\"waterway\"=\"river\"]${around(RIVER_M)};way[\"water\"=\"river\"]${around(RIVER_M)};);out count;" +
            "(way[\"natural\"=\"water\"][\"water\"~\"^(lake|reservoir)$\"]${around(LAKE_M)};" +
            "relation[\"natural\"=\"water\"][\"water\"~\"^(lake|reservoir)$\"]${around(LAKE_M)};);out count;" +
            "way[\"natural\"=\"coastline\"]${around(COAST_M)};out count;" +
            "is_in($at)->.a;" +
            "(area.a[\"landuse\"=\"forest\"];area.a[\"natural\"=\"wood\"];);out count;" +
            "(way[\"place\"~\"^(island|islet)$\"](pivot.a);relation[\"place\"~\"^(island|islet)$\"](pivot.a););out tags bb;" +
            "(way[\"piste:type\"=\"downhill\"]${around(SKI_M)};way[\"landuse\"=\"winter_sports\"]${around(SKI_M)};" +
            "way[\"aerialway\"~\"^(chair_lift|gondola|cable_car|mixed_lift|drag_lift|t-bar|j-bar|platter)$\"]${around(SKI_M)};);out count;"
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
        return Surroundings(
            settlement = settlement(nodes),
            forest = count(0) || count(4),
            river = count(1),
            lake = count(2),
            coast = count(3),
            island = island,
            ski = count(5),
        )
    }

    /**
     * Le plus grand lieu habité qui couvre le lieu : une ville s'étend d'autant plus loin de son centre qu'elle est
     * peuplée (Paris sur 8 km, une ville de 50 000 habitants sur 2 km, un village sur 700 m).
     */
    fun settlement(nodes: List<SettlementNode>): Settlement {
        val best = nodes.mapNotNull { n ->
            val people = n.population ?: when (n.kind) {
                "city" -> 150_000L
                "town" -> 8_000L
                "village" -> 500L
                "hamlet" -> 50L
                else -> 5L
            }
            people.takeIf { n.distanceKm <= 0.6 + sqrt(people.toDouble()) / 180 }
        }.maxOrNull() ?: return Settlement.NONE
        return when {
            best >= 50_000 -> Settlement.CITY
            best >= 2_000 -> Settlement.TOWN
            best >= 150 -> Settlement.VILLAGE
            else -> Settlement.HAMLET
        }
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
) {
    suspend fun of(place: Place): Surroundings = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "surroundings2_${place.id}.json")
        if (file.exists()) runCatching { decode(file.readText()) }.getOrNull()?.let { return@withContext it }
        var failure: Exception = IOException("Aucun serveur Overpass ne répond")
        for (server in Overpass.SERVERS) {
            try {
                val found = SurroundingsQuery.parse(httpGet(SurroundingsQuery.url(place.latitude, place.longitude, server)), place.latitude, place.longitude)
                cacheDir.mkdirs()
                file.writeText(encode(found))
                return@withContext found
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        throw failure
    }

    private fun encode(s: Surroundings): String = buildJsonObject {
        put("settlement", s.settlement.name)
        put("forest", s.forest)
        put("river", s.river)
        put("lake", s.lake)
        put("coast", s.coast)
        put("island", s.island)
        put("ski", s.ski)
    }.toString()

    private fun decode(raw: String): Surroundings {
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
        )
    }
}
