package io.github.pbailly.meteo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException

/** Une donnée chargée, avec l'heure du téléchargement et un indicateur « hors ligne ». */
data class Loaded<T>(val value: T, val fetchedAt: Long, val stale: Boolean)

/**
 * Accès à Open-Meteo avec un cache disque : on ne rappelle pas l'API tant que les données
 * ont moins de [FORECAST_MAX_AGE_MS], et on réaffiche le dernier résultat quand il n'y a pas de réseau.
 */
class WeatherRepository(
    private val cacheDir: File,
    private val httpGet: (String) -> String = ::defaultHttpGet,
) {
    suspend fun forecast(place: Place, force: Boolean): Loaded<Forecast> =
        cached("forecast_${place.id}", OpenMeteoApi.forecastUrl(place), FORECAST_MAX_AGE_MS, force) {
            OpenMeteoApi.parseForecast(it)
        }

    suspend fun multiModel(place: Place, force: Boolean): Loaded<MultiModelForecast> {
        val models = WeatherModel.entries
        return cached("multimodel_${place.id}", OpenMeteoApi.multiModelUrl(place, models), MULTI_MODEL_MAX_AGE_MS, force) {
            OpenMeteoApi.parseMultiModel(it, models)
        }
    }

    suspend fun search(query: String): List<Place> = withContext(Dispatchers.IO) {
        OpenMeteoApi.parseSearch(httpGet(OpenMeteoApi.searchUrl(query)))
    }

    private suspend fun <T> cached(
        key: String,
        url: String,
        maxAgeMs: Long,
        force: Boolean,
        parse: (String) -> T,
    ): Loaded<T> = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "$key.json")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < maxAgeMs
        if (fresh && !force) {
            val cachedValue = runCatching { parse(file.readText()) }.getOrNull()
            if (cachedValue != null) return@withContext Loaded(cachedValue, file.lastModified(), stale = false)
        }
        try {
            val body = httpGet(url)
            // On lit la réponse avant de l'écrire : une réponse invalide n'écrase jamais un bon cache.
            val value = parse(body)
            cacheDir.mkdirs()
            file.writeText(body)
            Loaded(value, file.lastModified(), stale = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val fallback = if (file.exists()) runCatching { parse(file.readText()) }.getOrNull() else null
            fallback?.let { Loaded(it, file.lastModified(), stale = true) } ?: throw e
        }
    }

    companion object {
        const val FORECAST_MAX_AGE_MS = 30 * 60 * 1000L
        const val MULTI_MODEL_MAX_AGE_MS = 60 * 60 * 1000L
    }
}

private fun defaultHttpGet(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/json")
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            // Open-Meteo renvoie {"error":true,"reason":"..."} : on laisse le parseur en tirer le message.
            if (body.contains("\"reason\"")) return body
            throw IOException("Erreur HTTP $code")
        }
        return body
    } finally {
        connection.disconnect()
    }
}
