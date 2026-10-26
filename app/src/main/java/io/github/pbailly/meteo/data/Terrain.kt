package io.github.pbailly.meteo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    /** La coupe rapide cherche ce qui domine le lieu sur une grille de 100 km de côté (mailles de 11 km). */
    const val QUICK_RADIUS_KM = 50.0
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

    /**
     * Sans les sommets d'OpenStreetMap : le point d'une grille d'altitudes qui domine le lieu (même calcul que
     * [dominantPeak]), sinon le plus haut à plus de 3 km.
     */
    fun dominantPoint(latitude: Double, longitude: Double, ground: Double, grid: List<Pair<Double, Double>>, heights: DoubleArray): Pair<Double, Double>? {
        val candidates = grid.indices.filter { !heights.getOrElse(it) { Double.NaN }.isNaN() }.map { it to distanceKm(latitude, longitude, grid[it].first, grid[it].second) }
            .filter { (_, d) -> d >= MIN_VIEW_KM }
        val dominant = if (ground.isNaN()) null else candidates.filter { (i, _) -> heights[i] - ground >= 200 }.maxByOrNull { (i, d) -> (heights[i] - ground) / (d + DOMINANCE_OFFSET_KM) }
        return (dominant ?: candidates.maxByOrNull { (i, _) -> heights[i] })?.let { grid[it.first] }
    }

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

    /** La deuxième chaîne : au moins 60° à côté de la première, et 300 m au-dessus du lieu. */
    private const val BACKDROP_MIN_ANGLE = 60.0
    private const val BACKDROP_MIN_GAIN = 300.0

    /**
     * Le point de la grille qui domine le lieu dans une autre direction que [mainBearing] (la deuxième chaîne qu'on voit
     * au loin : le Massif central depuis Valence, quand la coupe va vers le Vercors), ou null.
     */
    fun secondPoint(latitude: Double, longitude: Double, ground: Double, grid: List<Pair<Double, Double>>, heights: DoubleArray, mainBearing: Double): Pair<Double, Double>? {
        if (ground.isNaN()) return null
        return grid.indices.filter { i ->
            val h = heights.getOrElse(i) { Double.NaN }
            !h.isNaN() && h - ground >= BACKDROP_MIN_GAIN && distanceKm(latitude, longitude, grid[i].first, grid[i].second) >= MIN_VIEW_KM &&
                angleBetween(bearing(latitude, longitude, grid[i].first, grid[i].second), mainBearing) >= BACKDROP_MIN_ANGLE
        }.maxByOrNull { i -> (heights[i] - ground) / (distanceKm(latitude, longitude, grid[i].first, grid[i].second) + DOMINANCE_OFFSET_KM) }?.let { grid[it] }
    }

    /** Le sommet nommé qui domine le lieu dans une autre direction que [mainBearing], ou null. */
    fun secondPeak(latitude: Double, longitude: Double, ground: Double, peaks: List<Peak>, mainBearing: Double): Peak? =
        dominantPeak(
            latitude,
            longitude,
            ground,
            peaks.filter { angleBetween(bearing(latitude, longitude, it.latitude, it.longitude), mainBearing) >= BACKDROP_MIN_ANGLE && it.elevation - ground >= BACKDROP_MIN_GAIN },
        )

    /** Points de la deuxième coupe : du lieu jusqu'à 10 % au-delà du sommet. */
    fun backdropLine(latitude: Double, longitude: Double, toLatitude: Double, toLongitude: Double): List<Pair<Double, Double>> =
        (0 until SAMPLES).map { k ->
            val u = 1.1 * k / (SAMPLES - 1)
            (latitude + (toLatitude - latitude) * u) to (longitude + (toLongitude - longitude) * u)
        }

    fun backdrop(line: List<Pair<Double, Double>>, elevations: DoubleArray): Backdrop? {
        if (elevations.size != line.size || elevations.any { it.isNaN() }) return null
        val first = line.first()
        val last = line.last()
        return Backdrop(elevations, distanceKm(first.first, first.second, last.first, last.second), bearing(first.first, first.second, last.first, last.second))
    }

    /** Direction de [lat2], [lon2] vue de [lat1], [lon1], en degrés depuis le nord, dans le sens des aiguilles d'une montre. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dx = (lon2 - lon1) * cos((lat1 + lat2) / 2 * PI / 180)
        val dy = lat2 - lat1
        return (Math.toDegrees(kotlin.math.atan2(dx, dy)) + 360) % 360
    }

    /** Écart entre deux directions, de 0 à 180°. */
    fun angleBetween(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
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
        return TerrainProfile(values, length, placeIndex(), labels, corridor, bearing(first.first, first.second, last.first, last.second), start = first, end = last)
    }

    /**
     * Les sommets de la coupe à nommer, du plus haut au plus bas (trois au plus) : au-delà du lieu, plus hauts que tout
     * à 3 km autour, au moins 150 m au-dessus du lieu et 80 m au-dessus des cols voisins.
     */
    fun summits(profile: TerrainProfile): List<Int> {
        val e = profile.elevations
        val n = e.size
        if (n < 10) return emptyList()
        val p = profile.placeIndex.coerceIn(0, n - 1)
        val kmPerSample = profile.lengthKm / (n - 1)
        val window = (3.0 / kmPerSample).toInt().coerceIn(2, 12)
        val ground = e[p]
        return ((p + 3) until n).filter { k ->
            val around = (k - window..k + window).filter { it in 0 until n }
            around.all { e[it] <= e[k] } && e[k] - ground >= 150 && e[k] - around.minOf { e[it] } >= 80
        }.distinctBy { k -> (k - p) / window }.sortedByDescending { e[it] }.take(3)
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

/**
 * Le nom d'un sommet proche d'un point, d'après Photon (le géocodeur d'OpenStreetMap, gratuit, sans clé, rapide) :
 * quand Overpass ne répond pas, les sommets de la coupe ont quand même leur nom.
 */
object PeakNames {
    fun url(latitude: Double, longitude: Double): String =
        "https://photon.komoot.io/reverse?lat=%.5f&lon=%.5f&radius=1.5&limit=1&osm_tag=natural:peak".format(java.util.Locale.ROOT, latitude, longitude)

    fun parse(body: String): String? {
        val features = Json.parseToJsonElement(body).jsonObject["features"] as? JsonArray ?: return null
        val properties = features.firstOrNull()?.jsonObject?.get("properties") as? JsonObject ?: return null
        return properties["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
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
        val query = "[out:json][timeout:25];node[\"natural\"=\"peak\"][\"name\"][\"ele\"]" +
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
 * D'abord une coupe rapide ([quick], une seconde : deux appels à l'API d'altitude d'Open-Meteo) ; puis, avec les
 * sommets nommés d'OpenStreetMap (Overpass, lent : [overpassGet]), la coupe définitive ([profile]).
 */
class TerrainRepository(
    private val cacheDir: File,
    private val httpGet: suspend (String) -> String,
    private val overpassGet: suspend (String) -> String = httpGet,
) {
    private fun file(place: Place) = File(cacheDir, "terrain4_${place.id}.json")

    /** La coupe déjà gardée (une coupe de moins d'un kilomètre, vers le lieu lui-même, est refaite). */
    suspend fun cached(place: Place): TerrainProfile? = withContext(Dispatchers.IO) {
        val file = file(place)
        if (file.exists()) runCatching { decode(file.readText()) }.getOrNull()?.takeIf { it.lengthKm >= 1.0 } else null
    }

    private suspend fun ground(place: Place): Double = place.elevation ?: try {
        OpenMeteoApi.parseElevation(httpGet(OpenMeteoApi.elevationUrl(listOf(place.latitude to place.longitude)))).firstOrNull() ?: Double.NaN
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Double.NaN
    }

    /** Sans OpenStreetMap : vers le point d'une grille d'altitudes qui domine le lieu, sans noms de sommets. */
    suspend fun quick(place: Place): TerrainProfile = withContext(Dispatchers.IO) {
        val ground = ground(place)
        val grid = Terrain.gridPoints(place.latitude, place.longitude, Terrain.QUICK_RADIUS_KM)
        val heights = OpenMeteoApi.parseElevation(httpGet(OpenMeteoApi.elevationUrl(grid)))
        val (toLat, toLon) = Terrain.dominantPoint(place.latitude, place.longitude, ground, grid, heights) ?: grid.first()
        val line = Terrain.line(place.latitude, place.longitude, toLat, toLon)
        val profile = Terrain.build(line, OpenMeteoApi.parseElevation(httpGet(OpenMeteoApi.elevationUrl(line))), null, emptyList())
        val second = Terrain.secondPoint(place.latitude, place.longitude, ground, grid, heights, profile.bearing ?: 0.0)
        named(profile.with(second?.let { backdrop(place, it.first, it.second) }))
    }

    /**
     * Les sommets de la coupe nommés d'après Photon, quand aucun ne l'est encore (coupe rapide, ou Overpass muet) : un
     * appel rapide par sommet, trois au plus ; ceux qui ne répondent pas restent sans nom.
     */
    suspend fun named(profile: TerrainProfile): TerrainProfile = coroutineScope {
        if (profile.named) return@coroutineScope profile
        // Les trois appels en même temps : une fraction de seconde en tout.
        val labels = Terrain.summits(profile).map { k ->
            async(Dispatchers.IO) {
                val (lat, lon) = profile.pointAt(k) ?: return@async null
                val name = try {
                    PeakNames.parse(httpGet(PeakNames.url(lat, lon)))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } ?: return@async null
                ProfileLabel(k, name, profile.elevations[k])
            }
        }.awaitAll().filterNotNull().distinctBy { it.name }
        if (labels.isEmpty()) profile else profile.withLabels(labels)
    }

    /** Nomme les sommets d'une coupe déjà gardée qui n'en a pas, et la garde ainsi. */
    suspend fun rename(place: Place, profile: TerrainProfile): TerrainProfile = withContext(Dispatchers.IO) {
        val named = named(profile)
        if (named.named) {
            cacheDir.mkdirs()
            file(place).writeText(encode(named))
        }
        named
    }

    /** La deuxième chaîne, vers [toLat], [toLon] : un appel de plus à l'API d'altitude ; rien si elle ne répond pas. */
    private suspend fun backdrop(place: Place, toLat: Double, toLon: Double): Backdrop? = try {
        val line = Terrain.backdropLine(place.latitude, place.longitude, toLat, toLon)
        Terrain.backdrop(line, OpenMeteoApi.parseElevation(httpGet(OpenMeteoApi.elevationUrl(line))))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * La coupe définitive, vers le sommet nommé qui domine le lieu, gardée sur le téléphone. Sans réponse
     * d'OpenStreetMap, la coupe rapide [fallback] est gardée telle quelle.
     */
    suspend fun profile(place: Place, fallback: TerrainProfile? = null): TerrainProfile = withContext(Dispatchers.IO) {
        cached(place)?.let { return@withContext it }
        val peaks = peaks(place)
        val ground = ground(place)
        val target = Terrain.viewTarget(place.latitude, place.longitude, ground, peaks)
        val profile = if (target == null) {
            fallback ?: quick(place)
        } else {
            val line = Terrain.line(place.latitude, place.longitude, target.latitude, target.longitude)
            val built = Terrain.build(line, OpenMeteoApi.parseElevation(httpGet(OpenMeteoApi.elevationUrl(line))), target, peaks)
            // La deuxième chaîne : celle de la coupe rapide, sinon vers un sommet nommé dans une autre direction.
            val second = fallback?.backdrop?.takeIf { Terrain.angleBetween(it.bearing, built.bearing ?: 0.0) >= 60 }
                ?: Terrain.secondPeak(place.latitude, place.longitude, ground, peaks, built.bearing ?: 0.0)?.let { backdrop(place, it.latitude, it.longitude) }
            named(built.with(second))
        }
        cacheDir.mkdirs()
        cacheDir.listFiles { f -> f.name.startsWith("terrain_") || f.name.startsWith("terrain2_") || f.name.startsWith("terrain3_") }?.forEach { it.delete() }
        file(place).writeText(encode(profile))
        profile
    }

    /** Sommets nommés autour du lieu, du premier serveur Overpass qui répond ; aucun si tous échouent. */
    private suspend fun peaks(place: Place): List<Peak> {
        for (server in Overpass.SERVERS) {
            try {
                return Overpass.parsePeaks(overpassGet(Overpass.peaksUrl(place.latitude, place.longitude, server = server)))
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
        p.bearing?.let { put("bearing", it) }
        p.start?.let { put("start", buildJsonArray { add(JsonPrimitive(it.first)); add(JsonPrimitive(it.second)) }) }
        p.end?.let { put("end", buildJsonArray { add(JsonPrimitive(it.first)); add(JsonPrimitive(it.second)) }) }
        p.backdrop?.let { b ->
            put("backdrop", buildJsonObject {
                put("lengthKm", b.lengthKm)
                put("bearing", b.bearing)
                put("elevations", buildJsonArray { b.elevations.forEach { add(JsonPrimitive(it)) } })
            })
        }
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
            bearing = o["bearing"]?.jsonPrimitive?.doubleOrNull,
            start = (o["start"] as? JsonArray)?.let { it[0].jsonPrimitive.doubleOrNull!! to it[1].jsonPrimitive.doubleOrNull!! },
            end = (o["end"] as? JsonArray)?.let { it[0].jsonPrimitive.doubleOrNull!! to it[1].jsonPrimitive.doubleOrNull!! },
            backdrop = (o["backdrop"] as? JsonObject)?.let { b ->
                Backdrop(
                    b["elevations"]!!.jsonArray.map { it.jsonPrimitive.doubleOrNull ?: 0.0 }.toDoubleArray(),
                    b["lengthKm"]!!.jsonPrimitive.doubleOrNull ?: 0.0,
                    b["bearing"]!!.jsonPrimitive.doubleOrNull ?: 0.0,
                )
            },
        )
    }
}
