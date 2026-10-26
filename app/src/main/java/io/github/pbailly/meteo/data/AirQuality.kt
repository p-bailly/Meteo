package io.github.pbailly.meteo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Locale

/**
 * L'air du moment : poussières (sable du Sahara, µg/m³), particules fines PM2,5 (µg/m³) et indice européen de la
 * qualité de l'air (0 bon, au-delà de 60 médiocre, de 80 mauvais). NaN quand Open-Meteo ne le donne pas.
 */
data class AirQuality(val dust: Double, val pm25: Double, val europeanAqi: Double) {
    /** Assez de sable du Sahara pour jaunir le ciel. */
    val saharanDust: Boolean get() = dust >= DUST
    /** Air pollué : le lointain se voile. */
    val haze: Boolean get() = europeanAqi >= HAZE_AQI || pm25 >= HAZE_PM25

    companion object {
        const val DUST = 80.0
        const val HAZE_AQI = 60.0
        const val HAZE_PM25 = 35.0
    }
}

/** API de qualité de l'air d'Open-Meteo (gratuite, sans clé, modèle européen CAMS). */
object AirQualityApi {
    fun url(latitude: Double, longitude: Double): String =
        "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=%.4f&longitude=%.4f&current=dust,pm2_5,european_aqi&timezone=auto"
            .format(Locale.ROOT, latitude, longitude)

    fun parse(body: String): AirQuality {
        val current = Json.parseToJsonElement(body).jsonObject["current"] as? JsonObject
        fun value(key: String) = current?.get(key)?.jsonPrimitive?.doubleOrNull ?: Double.NaN
        return AirQuality(value("dust"), value("pm2_5"), value("european_aqi"))
    }
}

/** La qualité de l'air d'un lieu, gardée une heure : elle change lentement. */
class AirQualityRepository(
    private val cacheDir: File,
    private val httpGet: suspend (String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun of(place: Place): AirQuality = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "air_${place.id}.json")
        if (file.exists() && clock() - file.lastModified() < MAX_AGE_MS) {
            runCatching { AirQualityApi.parse(file.readText()) }.getOrNull()?.let { return@withContext it }
        }
        val body = httpGet(AirQualityApi.url(place.latitude, place.longitude))
        val air = AirQualityApi.parse(body)
        cacheDir.mkdirs()
        file.writeText(body)
        air
    }

    private companion object {
        const val MAX_AGE_MS = 60 * 60 * 1000L
    }
}
