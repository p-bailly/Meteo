package io.github.pbailly.meteo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * L'activité magnétique de la Terre (indice Kp, de 0 calme à 9 tempête), d'après la NOAA : les aurores boréales
 * descendent d'autant plus au sud qu'il est élevé (SketchSkies.auroraVisible).
 */
object SpaceWeather {
    const val URL = "https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json"

    /**
     * L'indice Kp de la tranche de trois heures qui contient [nowUtc] (observée ou prévue), sinon le dernier connu.
     * Lit les deux formats de la NOAA : une liste d'objets, ou une liste de lignes précédée de l'en-tête.
     */
    fun parse(body: String, nowUtc: LocalDateTime): Double? {
        val rows = Json.parseToJsonElement(body) as? JsonArray ?: return null
        val slots = rows.mapNotNull { row ->
            when (row) {
                is JsonObject -> {
                    val time = row["time_tag"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val kp = row["kp"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    time to kp
                }
                is JsonArray -> {
                    val time = row.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val kp = row.getOrNull(1)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
                    time to kp
                }
                else -> null
            }
        }.mapNotNull { (time, kp) ->
            runCatching { LocalDateTime.parse(time.trim().replace(' ', 'T').removeSuffix("Z")) }.getOrNull()?.let { it to kp }
        }.sortedBy { it.first }
        if (slots.isEmpty()) return null
        return slots.lastOrNull { !it.first.isAfter(nowUtc) }?.second ?: slots.first().second
    }
}

/** L'indice Kp, gardé trois heures (il est donné par tranches de trois heures). */
class SpaceWeatherRepository(
    private val cacheDir: File,
    private val httpGet: (String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun kp(): Double? = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "kp.json")
        val now = LocalDateTime.now(ZoneOffset.UTC)
        if (file.exists() && clock() - file.lastModified() < MAX_AGE_MS) {
            runCatching { SpaceWeather.parse(file.readText(), now) }.getOrNull()?.let { return@withContext it }
        }
        val body = httpGet(SpaceWeather.URL)
        val kp = SpaceWeather.parse(body, now)
        cacheDir.mkdirs()
        file.writeText(body)
        kp
    }

    private companion object {
        const val MAX_AGE_MS = 3 * 60 * 60 * 1000L
    }
}
