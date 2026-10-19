package io.github.pbailly.meteo.data.radar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant

/** Une image radar enregistrée sur le téléphone (PNG Web Mercator couvrant [MeteoFranceRadar.BOUNDS]). */
data class RadarFrame(val time: Instant, val file: File)

class RadarAuthException : IOException("Clé Météo-France refusée")

class RadarPixels(val width: Int, val height: Int, val argb: IntArray)

/**
 * Radar de précipitations de Météo-France (API publique « DonneesPubliquesRadar », clé gratuite).
 *
 * L'API ne donne que la dernière image (toutes les 5 minutes) : l'app garde donc elle-même
 * les images reçues pendant [KEEP], pour pouvoir les animer.
 */
class MeteoFranceRadar(
    private val dir: File,
    private val http: (url: String, apiKey: String, accept: String) -> ByteArray = ::meteoFranceGet,
) {
    private val mutex = Mutex()
    private var lookup: MercatorLookup? = null

    /** Récupère la dernière image si elle est nouvelle, puis renvoie les images gardées, de la plus ancienne à la plus récente. */
    suspend fun frames(apiKey: String): List<RadarFrame> = mutex.withLock {
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            prune()
            val latest = latestValidityTime(apiKey)
            if (latest == null || stored().none { it.time == latest }) {
                val grid = OdimDecoder.decode(http("$BASE/produit?maille=500", apiKey, "application/x-hdf"), factor = 2)
                val time = latest ?: grid.time ?: Instant.now()
                if (stored().none { it.time == time }) save(grid, time)
            }
            stored()
        }
    }

    /** Images déjà enregistrées, sans appel réseau. */
    fun stored(): List<RadarFrame> = (dir.listFiles() ?: emptyArray())
        .filter { it.name.endsWith(".png") }
        .mapNotNull { file -> file.nameWithoutExtension.toLongOrNull()?.let { RadarFrame(Instant.ofEpochSecond(it), file) } }
        .sortedBy { it.time }

    suspend fun image(frame: RadarFrame): Bitmap = withContext(Dispatchers.IO) {
        BitmapFactory.decodeFile(frame.file.path) ?: throw IOException("Image radar illisible")
    }

    /** Pixels ARGB d'une image enregistrée, pour calculer la prévision. */
    suspend fun pixels(frame: RadarFrame): RadarPixels = withContext(Dispatchers.IO) {
        val bitmap = BitmapFactory.decodeFile(frame.file.path) ?: throw IOException("Image radar illisible")
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        RadarPixels(bitmap.width, bitmap.height, pixels).also { bitmap.recycle() }
    }

    private fun latestValidityTime(apiKey: String): Instant? = runCatching {
        val body = http(BASE, apiKey, "application/json").decodeToString()
        parseLatestValidityTime(body)
    }.getOrElse { if (it is RadarAuthException) throw it else null }

    private fun save(grid: RadarGrid, time: Instant) {
        // La table de correspondance ne dépend que de la grille : on la calcule une fois.
        val table = lookup?.takeIf { it.gridWidth == grid.width && it.gridHeight == grid.height }
            ?: MercatorLookup.forGrid(BOUNDS, IMAGE_WIDTH, grid).also { lookup = it }
        val bitmap = Bitmap.createBitmap(table.render(grid.mmPerHour), table.width, table.height, Bitmap.Config.ARGB_8888)
        val tmp = File(dir, "${time.epochSecond}.tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        tmp.renameTo(File(dir, "${time.epochSecond}.png"))
    }

    private fun prune() {
        val limit = Instant.now().minus(KEEP)
        stored().filter { it.time.isBefore(limit) }.forEach { it.file.delete() }
        dir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    companion object {
        private const val BASE =
            "https://public-api.meteofrance.fr/public/DPRadar/v1/mosaiques/METROPOLE/observations/LAME_D_EAU"

        /** Emprise de la mosaïque France métropolitaine (grille stéréographique de 1736 km de côté). */
        val BOUNDS = GeoBounds(north = 54.2, south = 37.4, west = -10.0, east = 17.6)
        const val IMAGE_WIDTH = 1536
        val KEEP: Duration = Duration.ofHours(2)

        /** Heure de la dernière image annoncée par le catalogue (champ `validity_time`, maille de 500 m de préférence). */
        fun parseLatestValidityTime(body: String): Instant? {
            val candidates = mutableListOf<Pair<String, Instant>>()
            fun walk(e: JsonElement) {
                when (e) {
                    is JsonObject -> {
                        val time = (e["validity_time"] as? JsonPrimitive)?.contentOrNull
                            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                        if (time != null) candidates += e.toString() to time
                        e.values.forEach(::walk)
                    }
                    is JsonArray -> e.forEach(::walk)
                    else -> Unit
                }
            }
            walk(Json.parseToJsonElement(body))
            return (candidates.filter { "maille=500" in it.first }.ifEmpty { candidates }).maxOfOrNull { it.second }
        }
    }
}

/** GET sur les API Météo-France, avec la clé dans l'en-tête `apikey`. */
internal fun meteoFranceGet(url: String, apiKey: String, accept: String): ByteArray {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("apikey", apiKey)
        connection.setRequestProperty("Accept", accept)
        when (val code = connection.responseCode) {
            in 200..299 -> return connection.inputStream.use { it.readBytes() }
            401, 403 -> throw RadarAuthException()
            429 -> throw IOException("Trop de demandes à Météo-France, réessaie dans quelques minutes")
            else -> throw IOException("Météo-France : erreur HTTP $code")
        }
    } finally {
        connection.disconnect()
    }
}
