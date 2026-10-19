package io.github.pbailly.meteo.data.maps

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** D'où vient un relevé. */
enum class ObservationSource(val label: String) {
    METEO_FRANCE("Météo-France"),
    METEOSWISS("MétéoSuisse"),
    METAR("NOAA (METAR des aéroports)"),
}

/** Température relevée par une station, en °C. */
data class StationObservation(
    val id: String,
    val name: String?,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val temperature: Double,
    val time: Instant,
    val source: ObservationSource,
)

/** Nom et altitude d'une station Météo-France (liste des stations). */
data class StationInfo(val name: String, val altitude: Double?)

private val json = Json { ignoreUnknownKeys = true }

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull() }

/**
 * Relevés horaires de toutes les stations Météo-France (API « DonneesPubliquesPaquetObservation », gratuite avec
 * la clé du portail, abonnement à part). Environ 1 800 stations, température en kelvins, gardées 24 h.
 */
object MeteoFranceObservations {
    private const val BASE = "https://public-api.meteofrance.fr/public/DPPaquetObs/v2"
    const val STATIONS_URL = "$BASE/liste-stations"

    /** Les relevés de HH h sont publiés vers HH h 10 : on demande l'heure pleine d'il y a 15 minutes. */
    fun latestHour(now: Instant): Instant = now.minus(Duration.ofMinutes(15)).truncatedTo(ChronoUnit.HOURS)

    fun hourlyUrl(hour: Instant): String =
        "$BASE/paquet/stations/horaire?date=${DateTimeFormatter.ISO_INSTANT.format(hour.truncatedTo(ChronoUnit.HOURS))}&format=geojson"

    /** GeoJSON (ou JSON) du paquet horaire ; [stations] donne les noms et altitudes. */
    fun parse(body: String, stations: Map<String, StationInfo> = emptyMap()): List<StationObservation> {
        val root = json.parseToJsonElement(body)
        val records: List<Pair<JsonObject, Pair<Double, Double>?>> = when {
            root is JsonObject && root["features"] is JsonArray -> root["features"]!!.jsonArray.mapNotNull { f ->
                val o = f as? JsonObject ?: return@mapNotNull null
                val props = o["properties"] as? JsonObject ?: return@mapNotNull null
                val coords = (o["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray
                val lonLat = coords?.takeIf { it.size >= 2 }?.let {
                    val lon = (it[0] as? JsonPrimitive)?.doubleOrNull
                    val lat = (it[1] as? JsonPrimitive)?.doubleOrNull
                    if (lon != null && lat != null) lon to lat else null
                }
                props to lonLat
            }
            root is JsonArray -> root.mapNotNull { (it as? JsonObject)?.let { o -> o to null } }
            else -> emptyList()
        }
        return records.mapNotNull { (p, lonLat) ->
            val id = p.text("geo_id_insee") ?: p.text("id_station") ?: p.text("id") ?: return@mapNotNull null
            val lon = lonLat?.first ?: p.number("lon") ?: return@mapNotNull null
            val lat = lonLat?.second ?: p.number("lat") ?: return@mapNotNull null
            val t = p.number("t") ?: return@mapNotNull null
            val time = (p.text("validity_time") ?: p.text("reference_time"))?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: return@mapNotNull null
            val info = stations[id]
            StationObservation(
                id = id,
                name = info?.name ?: p.text("name") ?: p.text("nom_usuel"),
                latitude = lat,
                longitude = lon,
                altitude = info?.altitude ?: p.number("alti") ?: p.number("altitude"),
                // Le paquet donne des kelvins.
                temperature = if (t > 150) t - 273.15 else t,
                time = time,
                source = ObservationSource.METEO_FRANCE,
            )
        }
    }

    /** Liste des stations (CSV séparé par des points-virgules) : identifiant → nom et altitude. */
    fun parseStations(csv: String): Map<String, StationInfo> {
        val lines = csv.lineSequence().map { it.trim().removePrefix("﻿") }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return emptyMap()
        val sep = if (lines[0].count { it == ';' } >= lines[0].count { it == ',' }) ';' else ','
        val header = lines[0].split(sep).map { it.trim().trim('"').lowercase(Locale.ROOT) }
        val idCol = header.indexOfFirst { it == "id_station" || it == "geo_id_insee" || it == "id" }
        val nameCol = header.indexOfFirst { it.startsWith("nom") || it == "name" }
        val altCol = header.indexOfFirst { it.startsWith("alt") }
        if (idCol < 0 || nameCol < 0) return emptyMap()
        return lines.drop(1).mapNotNull { line ->
            val cells = line.split(sep).map { it.trim().trim('"') }
            val id = cells.getOrNull(idCol)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = cells.getOrNull(nameCol)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            id to StationInfo(prettyName(name), cells.getOrNull(altCol)?.replace(',', '.')?.toDoubleOrNull())
        }.toMap()
    }

    private val SMALL_WORDS = setOf("de", "du", "des", "la", "le", "les", "sur", "en", "et", "au", "aux")

    /** « ANNECY-MEYTHET » → « Annecy-Meythet », « AIGUILLE DU MIDI » → « Aiguille du Midi ». */
    private fun prettyName(raw: String): String {
        if (raw != raw.uppercase(Locale.FRANCE)) return raw
        return raw.lowercase(Locale.FRANCE).split(" ").mapIndexed { i, word ->
            word.split("-").joinToString("-") { part ->
                if (i > 0 && part in SMALL_WORDS) part else part.replaceFirstChar { it.titlecase(Locale.FRANCE) }
            }
        }.joinToString(" ")
    }
}

/** Stations automatiques de MétéoSuisse : température à 2 m, moyenne sur 10 minutes, sans clé (données ouvertes). */
object MeteoSwissObservations {
    const val URL =
        "https://data.geo.admin.ch/ch.meteoschweiz.messwerte-lufttemperatur-10min/ch.meteoschweiz.messwerte-lufttemperatur-10min_en.json"

    fun parse(body: String): List<StationObservation> {
        val root = json.parseToJsonElement(body).jsonObject
        val features = root["features"] as? JsonArray ?: return emptyList()
        return features.mapNotNull { f ->
            val o = f as? JsonObject ?: return@mapNotNull null
            val props = o["properties"] as? JsonObject ?: return@mapNotNull null
            val coords = (o["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray ?: return@mapNotNull null
            val e = (coords.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val n = (coords.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val value = props.number("value") ?: return@mapNotNull null
            val (lat, lon) = lv95ToWgs84(e, n)
            StationObservation(
                id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: props.text("station_name") ?: return@mapNotNull null,
                name = props.text("station_name"),
                latitude = lat,
                longitude = lon,
                altitude = props.number("altitude"),
                temperature = value,
                time = props.text("reference_ts")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@mapNotNull null,
                source = ObservationSource.METEOSWISS,
            )
        }
    }

    /** Coordonnées suisses LV95 (EPSG:2056) vers latitude et longitude (formules approchées de swisstopo, ±1 m). */
    fun lv95ToWgs84(east: Double, north: Double): Pair<Double, Double> {
        val y = (east - 2_600_000) / 1_000_000
        val x = (north - 1_200_000) / 1_000_000
        val lon = 2.6779094 + 4.728982 * y + 0.791484 * y * x + 0.1306 * y * x * x - 0.0436 * y * y * y
        val lat = 16.9023892 + 3.238272 * x - 0.270978 * y * y - 0.002528 * x * x - 0.0447 * y * y * x - 0.0140 * x * x * x
        return lat * 100 / 36 to lon * 100 / 36
    }
}

/** Observations des aéroports (METAR) du service météo de l'aviation américaine : sans clé, partout dans le monde. */
object MetarObservations {
    /** Zone de la France et des pays voisins. */
    fun url(south: Double = 41.0, west: Double = -5.5, north: Double = 51.5, east: Double = 10.5): String =
        "https://aviationweather.gov/api/data/metar?bbox=$south,$west,$north,$east&format=json"

    fun parse(body: String): List<StationObservation> {
        val root = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        return root.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val t = o.number("temp") ?: return@mapNotNull null
            val seconds = (o["obsTime"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            StationObservation(
                id = o.text("icaoId") ?: return@mapNotNull null,
                name = o.text("name")?.let(::airportName),
                latitude = o.number("lat") ?: return@mapNotNull null,
                longitude = o.number("lon") ?: return@mapNotNull null,
                altitude = o.number("elev"),
                temperature = t,
                time = Instant.ofEpochSecond(seconds),
                source = ObservationSource.METAR,
            )
        }
    }

    /** « Annecy/Meythet Arpt, AR, FR » → « Annecy/Meythet (aéroport) ». */
    private fun airportName(raw: String): String = raw.substringBefore(",").replace(" Arpt", "").replace(" Intl", "").trim() + " (aéroport)"
}

/** Mélange des sources : une station n'apparaît qu'une fois (on garde la plus récente des stations très proches). */
fun mergeObservations(vararg sources: List<StationObservation>, maxAge: Duration = Duration.ofHours(3), now: Instant = Instant.now()): List<StationObservation> {
    val fresh = sources.flatMap { it }.filter { Duration.between(it.time, now) <= maxAge && it.temperature in -60.0..60.0 }
    val result = ArrayList<StationObservation>(fresh.size)
    val taken = HashSet<Long>()
    // Deux relevés à moins de ~200 m sont la même station vue par deux réseaux (aéroport et Météo-France).
    for (o in fresh.sortedByDescending { it.source == ObservationSource.METEO_FRANCE || it.source == ObservationSource.METEOSWISS }) {
        val key = floor(o.latitude / 0.002).toLong() * 1_000_000 + floor(o.longitude / 0.003).toLong()
        if (taken.add(key)) result += o
    }
    return result
}

/**
 * Niveau de zoom à partir duquel chaque station s'affiche, pour que les étiquettes ne se chevauchent pas :
 * à chaque zoom, une station n'est ajoutée que si aucune station déjà visible n'est à moins de [spacing] dp.
 */
fun stationMinZooms(stations: List<StationObservation>, spacing: Double = 30.0, from: Int = 4, to: Int = 11): IntArray {
    val result = IntArray(stations.size) { to + 1 }
    val x = DoubleArray(stations.size) { (stations[it].longitude + 180) / 360 }
    val y = DoubleArray(stations.size) {
        val phi = stations[it].latitude * PI / 180
        (1 - ln(tan(phi) + 1 / kotlin.math.cos(phi)) / PI) / 2
    }
    // Ordre stable mais mélangé, pour que les stations retenues aux petits zooms soient bien réparties.
    val order = stations.indices.sortedBy { stations[it].id.hashCode() }
    for (z in from..to) {
        val world = 512.0 * (1 shl z)
        val cell = spacing / world
        val grid = HashMap<Long, MutableList<Int>>()
        fun key(cx: Long, cy: Long) = cx * 4_000_000 + cy
        for (i in order) if (result[i] <= z) grid.getOrPut(key(floor(x[i] / cell).toLong(), floor(y[i] / cell).toLong())) { mutableListOf() } += i
        for (i in order) {
            if (result[i] <= z) continue
            val cx = floor(x[i] / cell).toLong()
            val cy = floor(y[i] / cell).toLong()
            var free = true
            loop@ for (dx in -1L..1L) for (dy in -1L..1L) {
                for (j in grid[key(cx + dx, cy + dy)].orEmpty()) {
                    val ddx = (x[i] - x[j]) * world
                    val ddy = (y[i] - y[j]) * world
                    if (ddx * ddx + ddy * ddy < spacing * spacing) {
                        free = false
                        break@loop
                    }
                }
            }
            if (free) {
                result[i] = z
                grid.getOrPut(key(cx, cy)) { mutableListOf() } += i
            }
        }
    }
    return result
}

private fun JsonArray.getOrNull(index: Int): JsonElement? = if (index in indices) this[index] else null
