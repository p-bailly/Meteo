package io.github.pbailly.meteo.data.maps

import io.github.pbailly.meteo.data.Bzip2
import io.github.pbailly.meteo.data.HttpStatusException
import io.github.pbailly.meteo.data.retryOnNetworkError
import io.github.pbailly.meteo.data.radar.GeoBounds
import io.github.pbailly.meteo.data.radar.GeoTiffImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Heures disponibles d'une couche WMS (dimension « time » de GetCapabilities) : liste d'heures,
 * intervalles « début/fin/pas » (ISO 8601) ou listes abrégées « a,b,…,y,z ».
 * Seules les heures comprises entre [from] et [to] sont gardées.
 */
object WmsTimes {
    private val DIMENSION = Regex("<Dimension[^>]*name=\"time\"[^>]*>([^<]*)</Dimension>", RegexOption.IGNORE_CASE)

    fun fromCapabilities(xml: String, from: Instant, to: Instant): List<Instant> =
        DIMENSION.find(xml)?.groupValues?.get(1)?.let { parse(it, from, to) } ?: emptyList()

    fun parse(text: String, from: Instant, to: Instant): List<Instant> {
        val out = sortedSetOf<Instant>()
        val items = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        var previous: Instant? = null
        var step: Duration? = null
        var pendingGap = false
        for (item in items) {
            if (item == "...") {
                pendingGap = true
                continue
            }
            val parts = item.split('/')
            if (parts.size == 3) {
                val start = Instant.parse(parts[0])
                val end = Instant.parse(parts[1])
                val period = Duration.parse(parts[2])
                expand(start, end, period, from, to, out)
                previous = end
                step = period
                continue
            }
            val time = Instant.parse(item)
            if (pendingGap && previous != null && step != null) expand(previous, time, step, from, to, out)
            if (!time.isBefore(from) && !time.isAfter(to)) out += time
            previous?.let { step = Duration.between(it, time).takeIf { d -> !d.isNegative && !d.isZero } ?: step }
            previous = time
            pendingGap = false
        }
        return out.toList()
    }

    /** Ajoute les heures start, start + period… jusqu'à end, sans parcourir des années d'archives. */
    private fun expand(start: Instant, end: Instant, period: Duration, from: Instant, to: Instant, out: MutableSet<Instant>) {
        if (period.isZero || period.isNegative || end.isBefore(from) || start.isAfter(to)) return
        val skip = if (start.isBefore(from)) Duration.between(start, from).toMillis() / period.toMillis() else 0
        var t = start.plusMillis(skip * period.toMillis())
        while (!t.isAfter(end) && !t.isAfter(to)) {
            if (!t.isBefore(from)) out += t
            t = t.plus(period)
        }
    }
}

/** Couche WMS servie en tuiles Web Mercator (EPSG:3857), utilisable directement par la carte. */
data class TileLayer(
    val baseUrl: String,
    val layer: String,
    val format: String,
    val transparent: Boolean,
    val capabilitiesUrl: String,
    val attribution: String,
    /** Fenêtre d'heures proposée dans la frise, avant maintenant. */
    val history: Duration,
) {
    /** Modèle d'URL de tuile pour MapLibre : la carte remplace {bbox-epsg-3857}. */
    fun tileUrl(time: Instant): String =
        "$baseUrl?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=$layer&STYLES=&CRS=EPSG:3857" +
            "&BBOX={bbox-epsg-3857}&WIDTH=512&HEIGHT=512&FORMAT=$format&TRANSPARENT=$transparent" +
            "&TIME=" + DateTimeFormatter.ISO_INSTANT.format(time)

    companion object {
        /** Meteosat de 3e génération (MTG), couleurs « GeoColour » : vraies couleurs le jour, nuages et lumières la nuit. */
        val SATELLITE = TileLayer(
            baseUrl = "https://view.eumetsat.int/geoserver/wms",
            layer = "mtg_fd:rgb_geocolour",
            format = "image/jpeg",
            transparent = false,
            capabilitiesUrl = "https://view.eumetsat.int/geoserver/mtg_fd/rgb_geocolour/ows?service=WMS&request=GetCapabilities&version=1.3.0",
            attribution = "EUMETSAT Meteosat",
            history = Duration.ofHours(2),
        )

        /** Imageur d'éclairs de Meteosat MTG : nombre d'éclairs en 5 minutes, sur une grille de 2 km. */
        val LIGHTNING = TileLayer(
            baseUrl = "https://view.eumetsat.int/geoserver/wms",
            layer = "mtg_fd:li_afa",
            format = "image/png",
            transparent = true,
            capabilitiesUrl = "https://view.eumetsat.int/geoserver/mtg_fd/li_afa/ows?service=WMS&request=GetCapabilities&version=1.3.0",
            attribution = "EUMETSAT Meteosat Lightning Imager",
            history = Duration.ofHours(1),
        )
    }
}

/**
 * Température à 2 m du modèle ICON-EU du service météo allemand (DWD), sans clé : fichiers GRIB2 de son serveur de
 * données ouvertes (environ 1 Mo par heure pour toute l'Europe), plus fiable que son serveur de cartes.
 */
object DwdTemperature {
    const val ATTRIBUTION = "© DWD, modèle ICON-EU"

    /** Zone lue et couverte par l'image : la France et ses voisins. */
    val BOUNDS = GeoBounds(north = 52.0, south = 41.0, west = -6.0, east = 10.0)

    /** ICON-EU tourne toutes les 3 heures ; ses fichiers sont en ligne environ 2 h 45 après l'heure du réseau. */
    private val PUBLICATION_DELAY = Duration.ofMinutes(170)
    private const val RUN_SECONDS = 3 * 3600L

    /** Fichiers à essayer pour l'heure [time], du réseau le plus récent au plus ancien. */
    fun candidateUrls(time: Instant, now: Instant = Instant.now()): List<String> {
        val latest = now.minus(PUBLICATION_DELAY).epochSecond / RUN_SECONDS * RUN_SECONDS
        return (0 until 3).map { Instant.ofEpochSecond(latest - it * RUN_SECONDS) }
            .filter { !it.isAfter(time) }
            .map { run -> url(run, Duration.between(run, time).toHours()) }
    }

    fun url(run: Instant, step: Long): String {
        val r = run.atOffset(ZoneOffset.UTC)
        val stamp = String.format(Locale.ROOT, "%04d%02d%02d%02d", r.year, r.monthValue, r.dayOfMonth, r.hour)
        return String.format(
            Locale.ROOT,
            "https://opendata.dwd.de/weather/nwp/icon-eu/grib/%02d/t_2m/icon-eu_europe_regular-lat-lon_single-level_%s_%03d_T_2M.grib2.bz2",
            r.hour, stamp, step,
        )
    }

    /** Télécharge le champ de l'heure [time] (en °C), en remontant aux réseaux précédents si le dernier n'est pas en ligne. */
    suspend fun download(time: Instant): GeoTiffImage {
        var missing: HttpStatusException? = null
        for (url in candidateUrls(time)) {
            val bytes = try {
                httpBytes(url)
            } catch (e: HttpStatusException) {
                if (e.code != 404) throw e
                missing = e
                continue
            }
            return withContext(Dispatchers.Default) { read(bytes) }
        }
        throw missing ?: HttpStatusException(404)
    }

    fun read(bz2: ByteArray): GeoTiffImage = Grib2.read(Bzip2.decompress(bz2), BOUNDS) { kelvin -> kelvin - 273.15 }
}

/**
 * Image Web Mercator couvrant [bounds], interpolée (bilinéaire) depuis une grille régulière en latitude/longitude :
 * dégradé continu plutôt que des cases. [color] renvoie un ARGB (0 = transparent).
 */
fun renderLatLonGrid(image: GeoTiffImage, bounds: GeoBounds, width: Int, color: (Float) -> Int): Pair<IntArray, Int> {
    fun mercY(lat: Double) = ln(tan(PI / 4 + Math.toRadians(lat) / 2))
    val yNorth = mercY(bounds.north)
    val ySouth = mercY(bounds.south)
    val height = (width * (yNorth - ySouth) / Math.toRadians(bounds.east - bounds.west)).roundToInt()
    val originX = image.originX ?: bounds.west
    val originY = image.originY ?: bounds.north
    val sx = image.scaleX ?: ((bounds.east - bounds.west) / image.width)
    val sy = image.scaleY ?: ((bounds.north - bounds.south) / image.height)
    val noData = image.noData
    fun value(c: Int, r: Int): Float {
        val v = image.values[r * image.width + c]
        return if (v.isNaN() || (noData != null && v.toDouble() == noData) || v > 1e6f || v < -1e6f) Float.NaN else v
    }
    val out = IntArray(width * height)
    for (py in 0 until height) {
        val lat = Math.toDegrees(atan(sinh(yNorth + (ySouth - yNorth) * (py + 0.5) / height)))
        val gy = ((originY - lat) / sy - 0.5).toFloat()
        val r0 = gy.toInt().coerceIn(0, image.height - 1)
        val r1 = (r0 + 1).coerceAtMost(image.height - 1)
        val fy = (gy - r0).coerceIn(0f, 1f)
        if (gy < -0.5f || gy > image.height - 0.5f) continue
        for (px in 0 until width) {
            val lon = bounds.west + (bounds.east - bounds.west) * (px + 0.5) / width
            val gx = ((lon - originX) / sx - 0.5).toFloat()
            if (gx < -0.5f || gx > image.width - 0.5f) continue
            val c0 = gx.toInt().coerceIn(0, image.width - 1)
            val c1 = (c0 + 1).coerceAtMost(image.width - 1)
            val fx = (gx - c0).coerceIn(0f, 1f)
            val a = value(c0, r0)
            val b = value(c1, r0)
            val c = value(c0, r1)
            val d = value(c1, r1)
            val v = if (a.isNaN() || b.isNaN() || c.isNaN() || d.isNaN()) {
                listOf(a, b, c, d).firstOrNull { !it.isNaN() } ?: continue
            } else {
                (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
            }
            out[py * width + px] = color(v)
        }
    }
    return out to height
}

/** Une webcam proche (API Windy Webcams). Les adresses d'image expirent au bout d'environ 10 minutes. */
data class Webcam(
    val id: Long,
    val title: String,
    val latitude: Double,
    val longitude: Double,
    val imageUrl: String?,
    val pageUrl: String,
    val updated: String?,
)

object WindyWebcams {
    const val ATTRIBUTION = "Webcams : windy.com"

    fun nearbyUrl(lat: Double, lon: Double, radiusKm: Int = 60): String =
        "https://api.windy.com/webcams/api/v3/webcams?nearby=${"%.4f".format(Locale.ROOT, lat)}," +
            "${"%.4f".format(Locale.ROOT, lon)},$radiusKm&include=images,location,urls&limit=50&lang=fr"

    fun parse(body: String): List<Webcam> {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val list = root["webcams"] as? JsonArray ?: return emptyList()
        fun JsonObject.obj(k: String) = this[k] as? JsonObject
        fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content
        return list.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val location = o.obj("location") ?: return@mapNotNull null
            val lat = location.str("latitude")?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = location.str("longitude")?.toDoubleOrNull() ?: return@mapNotNull null
            val id = (o.str("webcamId") ?: o.str("id"))?.toLongOrNull() ?: return@mapNotNull null
            val images = o.obj("images")
            Webcam(
                id = id,
                title = o.str("title") ?: location.str("city") ?: "Webcam",
                latitude = lat,
                longitude = lon,
                imageUrl = images?.obj("current")?.str("preview") ?: images?.obj("daylight")?.str("preview"),
                pageUrl = o.obj("urls")?.str("detail") ?: "https://www.windy.com/webcams/$id",
                updated = o.str("lastUpdatedOn"),
            )
        }
    }
}

/** GET simple ; [headers] pour les clés d'API. Réessaie deux fois si la connexion est coupée en route. */
suspend fun httpBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray = withContext(Dispatchers.IO) {
    retryOnNetworkError {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            when (val code = connection.responseCode) {
                in 200..299 -> connection.inputStream.use { it.readBytes() }
                401, 403 -> throw MapKeyException()
                else -> throw HttpStatusException(code)
            }
        } finally {
            connection.disconnect()
        }
    }
}

class MapKeyException : HttpStatusException(401, "Clé refusée")
