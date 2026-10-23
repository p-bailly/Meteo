package io.github.pbailly.meteo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException

/**
 * Une donnée chargée, avec l'heure du téléchargement et un indicateur « hors ligne ». [downloaded] : elle vient d'être
 * téléchargée (et non relue dans le cache).
 */
data class Loaded<T>(val value: T, val fetchedAt: Long, val stale: Boolean, val downloaded: Boolean = false)

/**
 * Accès à Open-Meteo avec un cache disque : on ne rappelle pas l'API tant que les données
 * ont moins de [FORECAST_MAX_AGE_MS], et on réaffiche le dernier résultat quand il n'y a pas de réseau.
 */
class WeatherRepository(
    private val cacheDir: File,
    val httpGet: (String) -> String = ::defaultHttpGet,
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

    suspend fun ensemble(place: Place, force: Boolean): Loaded<EnsembleForecast> =
        cached("ensembles_${place.id}", OpenMeteoApi.ensembleUrl(place), ENSEMBLE_MAX_AGE_MS, force) {
            OpenMeteoApi.parseEnsemble(it)
        }

    /**
     * La dernière prévision gardée du lieu quand elle n'est plus fraîche (moins d'un jour) : elle s'affiche tout de
     * suite, le temps que la nouvelle arrive. Null si elle est fraîche ([forecast] la rend alors sans attendre).
     */
    suspend fun lastForecast(place: Place): Loaded<Forecast>? =
        last("forecast_${place.id}", FORECAST_MAX_AGE_MS) { OpenMeteoApi.parseForecast(it) }

    suspend fun lastMultiModel(place: Place): Loaded<MultiModelForecast>? =
        last("multimodel_${place.id}", MULTI_MODEL_MAX_AGE_MS) { OpenMeteoApi.parseMultiModel(it, WeatherModel.entries) }

    private suspend fun <T> last(key: String, maxAgeMs: Long, parse: (String) -> T): Loaded<T>? = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "$key.json")
        val age = System.currentTimeMillis() - file.lastModified()
        if (!file.exists() || age < maxAgeMs || age > LAST_MAX_AGE_MS) return@withContext null
        runCatching { parse(file.readText()) }.getOrNull()?.let { Loaded(it, file.lastModified(), stale = true) }
    }

    /** Prévision courte de tous les lieux de la liste, en un seul appel ; dans l'ordre de [places]. */
    suspend fun summaries(places: List<Place>, force: Boolean): Loaded<List<Forecast>> {
        val key = "summary_" + Integer.toHexString(places.joinToString("|") { it.id }.hashCode())
        return cached(key, OpenMeteoApi.summaryUrl(places), FORECAST_MAX_AGE_MS, force) { body ->
            OpenMeteoApi.parseForecasts(body).also {
                if (it.size != places.size) throw OpenMeteoException("Réponse incomplète pour la liste des lieux")
            }
        }
    }

    suspend fun search(query: String): List<Place> = withContext(Dispatchers.IO) {
        OpenMeteoApi.parseSearch(httpGet(OpenMeteoApi.searchUrl(query)), query)
    }

    /** Altitude d'un point (modèle de terrain Copernicus à 90 m) ; null si l'API n'en donne pas. */
    suspend fun elevation(latitude: Double, longitude: Double): Double? = withContext(Dispatchers.IO) {
        OpenMeteoApi.parseElevation(httpGet(OpenMeteoApi.elevationUrl(listOf(latitude to longitude)))).firstOrNull()?.takeIf { !it.isNaN() }
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
            Loaded(value, file.lastModified(), stale = false, downloaded = true)
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
        /** L'ensemble est recalculé toutes les 6 heures (et pèse plus lourd dans le quota d'Open-Meteo). */
        const val ENSEMBLE_MAX_AGE_MS = 3 * 60 * 60 * 1000L
        /** Au-delà d'un jour, une prévision gardée ne s'affiche plus en attendant la nouvelle. */
        const val LAST_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}

private fun defaultHttpGet(url: String): String = retryOnNetworkError { httpGetOnce(url) }

/**
 * Pour OpenStreetMap (Overpass) : une requête sur les environs d'un lieu prend souvent 15 à 25 s, au-delà des 20 s
 * accordées à Open-Meteo. Un serveur qui ne répond pas en 30 s n'est pas réessayé : on passe au suivant.
 */
fun slowHttpGet(url: String): String = httpGetOnce(url, readTimeoutMs = 30_000)

private fun httpGetOnce(url: String, readTimeoutMs: Int = 20_000): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        // Un serveur surchargé ne répond parfois même pas à la connexion : mieux vaut réessayer tôt qu'attendre.
        connection.connectTimeout = 8_000
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Meteo-perso/1.0 (Android; github.com/p-bailly/Meteo)")
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code in 502..504) throw ServerBusyException(code)
        if (code !in 200..299) {
            // Open-Meteo renvoie {"error":true,"reason":"..."} : on laisse le parseur en tirer le message.
            if (body.contains("\"reason\"")) return body
            throw HttpStatusException(code)
        }
        return body
    } finally {
        connection.disconnect()
    }
}
