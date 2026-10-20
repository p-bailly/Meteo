package io.github.pbailly.meteo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Construction de la coupe : choix du sommet, points à échantillonner, repères. Sans réseau, pour les tests. */
object Terrain {
    const val SEARCH_RADIUS_KM = 25.0
    /** Sommets cherchés jusqu'à 60 km : le Grand Veymont domine Valence à 49 km. */
    const val PEAK_RADIUS_KM = 60.0
    /** Distance ajoutée au calcul de la domination : une colline voisine ne passe pas devant une montagne. */
    private const val DOMINANCE_OFFSET_KM = 10.0
    const val SAMPLES = 100
    /** La coupe va au moins à 3 km du lieu. */
    const val MIN_VIEW_KM = 3.0
    private const val BEFORE = 0.25
    private const val AFTER = 0.15
    /** Sommets dessinés sur la coupe : à moins de 2 km de la ligne ; sommets servant à nommer une altitude : 3 km. */
    private const val LABEL_CORRIDOR_KM = 2.0
    private const val NAME_CORRIDOR_KM = 3.0
    private const val GRID = 10

    /** Grille de 10 × 10 points autour du lieu, pour trouver le point culminant quand on n'a pas les sommets. */
    fun gridPoints(latitude: Double, longitude: Double, radiusKm: Double = SEARCH_RADIUS_KM): List<Pair<Double, Double>> {
        val dLat = radiusKm / 111.2
        val dLon = radiusKm / (111.2 * cos(latitude * PI / 180))
        return (0 until GRID).flatMap { r ->
            (0 until GRID).map { c ->
                (latitude - dLat + 2 * dLat * r / (GRID - 1)) to (longitude - dLon + 2 * dLon * c / (GRID - 1))
            }
        }
    }

    /**
     * Le sommet qui domine le lieu : le plus grand dénivelé au-dessus de [ground] rapporté à la distance (plus 10 km),
     * à moins de [radiusKm] et au moins 200 m plus haut. Le Mont Vouillot pour Morteau, le Grand Veymont pour Valence.
     */
    fun dominantPeak(latitude: Double, longitude: Double, ground: Double, peaks: List<Peak>, radiusKm: Double = PEAK_RADIUS_KM): Peak? {
        if (ground.isNaN()) return null
        return peaks.mapNotNull { peak ->
            val d = distanceKm(latitude, longitude, peak.latitude, peak.longitude)
            val gain = peak.elevation - ground
            if (d > radiusKm || gain < 200) null else peak to gain / (d + DOMINANCE_OFFSET_KM)
        }.maxByOrNull { it.second }?.first
    }

    /**
     * Où regarder : le sommet qui domine le lieu ; sinon (le lieu est lui-même un sommet, le Mont Blanc) le plus haut
     * sommet nommé à plus de 3 km, pour que la coupe descende dans la vallée au lieu de rester sur place.
     */
    fun viewTarget(latitude: Double, longitude: Double, ground: Double, peaks: List<Peak>): Peak? =
        dominantPeak(latitude, longitude, ground, peaks)
            ?: peaks.filter { distanceKm(latitude, longitude, it.latitude, it.longitude) in MIN_VIEW_KM..SEARCH_RADIUS_KM }.maxByOrNull { it.elevation }

    /** Le plus haut sommet nommé à moins de [radiusKm] du lieu. */
    fun highestPeak(latitude: Double, longitude: Double, peaks: List<Peak>, radiusKm: Double = SEARCH_RADIUS_KM): Peak? =
        peaks.filter { distanceKm(latitude, longitude, it.latitude, it.longitude) <= radiusKm }.maxByOrNull { it.elevation }

    /** Points de la coupe : un quart de la distance avant le lieu, jusqu'à 15 % au-delà du sommet. */
    fun line(latitude: Double, longitude: Double, toLatitude: Double, toLongitude: Double): List<Pair<Double, Double>> {
        val dLat = toLatitude - latitude
        val dLon = toLongitude - longitude
        return (0 until SAMPLES).map { k ->
            val u = -BEFORE + (1 + BEFORE + AFTER) * k / (SAMPLES - 1)
            (latitude + dLat * u) to (longitude + dLon * u)
        }
    }

    fun placeIndex(): Int = (BEFORE / (1 + BEFORE + AFTER) * (SAMPLES - 1)).roundToInt()

    fun targetIndex(): Int = ((1 + BEFORE) / (1 + BEFORE + AFTER) * (SAMPLES - 1)).roundToInt()

    /** Assemble la coupe : altitudes du terrain, sommet visé et autres sommets nommés proches de la ligne. */
    fun build(
        line: List<Pair<Double, Double>>,
        elevations: DoubleArray,
        target: Peak?,
        peaks: List<Peak>,
    ): TerrainProfile {
        val values = elevations.copyOf()
        // Trous éventuels (mer, données absentes) : la valeur voisine.
        for (k in values.indices) if (values[k].isNaN()) values[k] = values.getOrElse(k - 1) { 0.0 }.takeIf { !it.isNaN() } ?: 0.0
        val first = line.first()
        val last = line.last()
        val length = distanceKm(first.first, first.second, last.first, last.second)
        val labels = ArrayList<ProfileLabel>()
        val corridor = ArrayList<Peak>()
        val targetIndex = targetIndex()
        if (target != null) {
            values[targetIndex] = maxOf(values[targetIndex], target.elevation)
            labels += ProfileLabel(targetIndex, target.name, target.elevation)
        } else {
            val top = values.indices.maxByOrNull { values[it] } ?: targetIndex
            labels += ProfileLabel(top, null, values[top])
        }
        for (peak in peaks.sortedByDescending { it.elevation }) {
            val (u, offset) = project(first, last, peak.latitude, peak.longitude)
            if (u !in 0.0..1.0 || offset > NAME_CORRIDOR_KM) continue
            corridor += peak
            if (peak == target || labels.size >= 4 || offset > LABEL_CORRIDOR_KM) continue
            val index = (u * (SAMPLES - 1)).roundToInt()
            if (labels.any { abs(it.index - index) < 12 }) continue
            if (peak.elevation < values[placeIndex()] + 150) continue
            // Seulement un sommet que la coupe traverse vraiment : le terrain y est à moins de 300 m de son altitude.
            val near = (index - 2..index + 2).filter { it in values.indices }.maxByOrNull { values[it] } ?: index
            if (peak.elevation - values[near] > 300) continue
            labels += ProfileLabel(near, peak.name, peak.elevation)
        }
        return TerrainProfile(values, length, placeIndex(), labels, corridor)
    }

    /** Position d'un point le long du segment (0 au début, 1 à la fin) et sa distance au segment, en km. */
    private fun project(a: Pair<Double, Double>, b: Pair<Double, Double>, lat: Double, lon: Double): Pair<Double, Double> {
        val k = cos(a.first * PI / 180) * 111.2
        val bx = (b.second - a.second) * k
        val by = (b.first - a.first) * 111.2
        val px = (lon - a.second) * k
        val py = (lat - a.first) * 111.2
        val len2 = bx * bx + by * by
        if (len2 == 0.0) return 0.0 to sqrt(px * px + py * py)
        val u = (px * bx + py * by) / len2
        val dx = px - u * bx
        val dy = py - u * by
        return u to sqrt(dx * dx + dy * dy)
    }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val k = cos((lat1 + lat2) / 2 * PI / 180) * 111.2
        val dx = (lon2 - lon1) * k
        val dy = (lat2 - lat1) * 111.2
        return sqrt(dx * dx + dy * dy)
    }
}

/** Sommets nommés d'OpenStreetMap, par l'API Overpass (gratuite, sans clé). */
object Overpass {
    /** Le serveur principal, puis deux miroirs si celui-ci ne répond pas. */
    val SERVERS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    fun peaksUrl(latitude: Double, longitude: Double, radiusKm: Double = Terrain.PEAK_RADIUS_KM, server: String = SERVERS.first()): String {
        val query = "[out:json][timeout:60];node[\"natural\"=\"peak\"][\"name\"][\"ele\"]" +
            "(around:${(radiusKm * 1000).roundToInt()},${"%.4f".format(java.util.Locale.ROOT, latitude)}," +
            "${"%.4f".format(java.util.Locale.ROOT, longitude)});out body;"
        return "$server?data=" + URLEncoder.encode(query, "UTF-8")
    }

    fun parsePeaks(body: String): List<Peak> {
        val root = Json.parseToJsonElement(body).jsonObject
        val elements = root["elements"] as? JsonArray ?: return emptyList()
        return elements.mapNotNull { e ->
            val o = e.jsonObject
            val tags = o["tags"] as? JsonObject ?: return@mapNotNull null
            val name = tags["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val ele = parseElevation(tags["ele"]?.jsonPrimitive?.contentOrNull) ?: return@mapNotNull null
            Peak(
                name = name,
                latitude = o["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                longitude = o["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                elevation = ele,
            )
        }
    }

    /** « 4808 », « 4808.7 », « 4 808 m », « 4808,7 » : l'altitude en mètres, ou null. */
    fun parseElevation(raw: String?): Double? {
        if (raw == null) return null
        val cleaned = raw.replace(" ", "").replace(" ", "").replace(",", ".").removeSuffix("m").trim()
        return cleaned.toDoubleOrNull()?.takeIf { it in -500.0..9000.0 }
    }
}

/**
 * Coupe du relief d'un lieu, gardée sur le téléphone : le relief ne change pas, on ne la télécharge qu'une fois.
 * Les sommets viennent d'Overpass ; sans eux, on prend le point culminant d'une grille d'altitudes.
 */
class TerrainRepository(
    private val cacheDir: File,
    private val httpGet: (String) -> String,
) {
    suspend fun profile(place: Place): TerrainProfile = withContext(Dispatchers.IO) {
        // « terrain2 » : coupes vers le sommet qui domine le lieu (avant : le plus haut à 25 km).
        val file = File(cacheDir, "terrain2_${place.id}.json")
        // Une coupe de moins d'un kilomètre (vers le lieu lui-même, quand il est un sommet) est refaite.
        if (file.exists()) runCatching { decode(file.readText()) }.getOrNull()?.takeIf { it.lengthKm >= 1.0 }?.let { return@withContext it }
        val peaks = peaks(place)
        val ground = place.elevation ?: try {
            OpenMeteoApi.parseElevation(httpGet(OpenMeteoApi.elevationUrl(listOf(place.latitude to place.longitude)))).firstOrNull() ?: Double.NaN
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Double.NaN
        }
        val target = Terrain.viewTarget(place.latitude, place.longitude, ground, peaks)
        val (toLat, toLon) = if (target != null) {
            target.latitude to target.longitude
        } else {
            val grid = Terrain.gridPoints(place.latitude, place.longitude)
            val heights = OpenMeteoApi.parseElevation(httpGet(OpenMeteoApi.elevationUrl(grid)))
            // Le point culminant de la grille, à plus de 3 km (au sommet, la coupe ne reste pas sur place).
            grid[
                heights.indices.filter { Terrain.distanceKm(place.latitude, place.longitude, grid[it].first, grid[it].second) >= Terrain.MIN_VIEW_KM }
                    .maxByOrNull { if (heights[it].isNaN()) -1e9 else heights[it] } ?: 0,
            ]
        }
        val line = Terrain.line(place.latitude, place.longitude, toLat, toLon)
        val elevations = OpenMeteoApi.parseElevation(httpGet(OpenMeteoApi.elevationUrl(line)))
        val profile = Terrain.build(line, elevations, target, peaks)
        cacheDir.mkdirs()
        cacheDir.listFiles { f -> f.name.startsWith("terrain_") }?.forEach { it.delete() }
        file.writeText(encode(profile))
        profile
    }

    /** Sommets nommés autour du lieu, du premier serveur Overpass qui répond ; aucun si tous échouent. */
    private fun peaks(place: Place): List<Peak> {
        for (server in Overpass.SERVERS) {
            try {
                return Overpass.parsePeaks(httpGet(Overpass.peaksUrl(place.latitude, place.longitude, server = server)))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
        }
        return emptyList()
    }

    private fun encode(p: TerrainProfile): String = buildJsonObject {
        put("lengthKm", p.lengthKm)
        put("placeIndex", p.placeIndex)
        put("elevations", buildJsonArray { p.elevations.forEach { add(JsonPrimitive(it)) } })
        put("labels", buildJsonArray {
            p.labels.forEach { l ->
                add(buildJsonObject {
                    put("index", l.index)
                    l.name?.let { put("name", it) }
                    put("elevation", l.elevation)
                })
            }
        })
        put("peaks", buildJsonArray {
            p.peaks.forEach { k ->
                add(buildJsonObject {
                    put("name", k.name)
                    put("lat", k.latitude)
                    put("lon", k.longitude)
                    put("ele", k.elevation)
                })
            }
        })
    }.toString()

    private fun decode(raw: String): TerrainProfile {
        val o = Json.parseToJsonElement(raw).jsonObject
        val elevations = o["elevations"]!!.jsonArray.map { it.jsonPrimitive.doubleOrNull ?: 0.0 }.toDoubleArray()
        return TerrainProfile(
            elevations = elevations,
            lengthKm = o["lengthKm"]!!.jsonPrimitive.doubleOrNull ?: 0.0,
            placeIndex = o["placeIndex"]!!.jsonPrimitive.intOrNull ?: 0,
            labels = o["labels"]!!.jsonArray.map {
                val l = it.jsonObject
                ProfileLabel(
                    l["index"]!!.jsonPrimitive.intOrNull ?: 0,
                    l["name"]?.jsonPrimitive?.contentOrNull,
                    l["elevation"]!!.jsonPrimitive.doubleOrNull ?: 0.0,
                )
            },
            peaks = o["peaks"]?.jsonArray?.map {
                val k = it.jsonObject
                Peak(
                    k["name"]!!.jsonPrimitive.content,
                    k["lat"]!!.jsonPrimitive.doubleOrNull ?: 0.0,
                    k["lon"]!!.jsonPrimitive.doubleOrNull ?: 0.0,
                    k["ele"]!!.jsonPrimitive.doubleOrNull ?: 0.0,
                )
            }.orEmpty(),
        )
    }
}
