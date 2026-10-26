package io.github.pbailly.meteo.data.radar

import io.github.pbailly.meteo.data.HttpStatusException
import io.github.pbailly.meteo.data.retryOnNetworkError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

// Téléchargements Météo-France du téléphone (la webapp a les siens, par fetch).

/** GET sur les API Météo-France, avec la clé dans l'en-tête `apikey`. */
internal suspend fun meteoFranceGet(url: String, apiKey: String, accept: String): ByteArray = withContext(Dispatchers.IO) {
    retryOnNetworkError { meteoFranceGetOnce(url, apiKey, accept) }
}

private fun meteoFranceGetOnce(url: String, apiKey: String, accept: String): ByteArray {
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("apikey", apiKey)
        connection.setRequestProperty("Accept", accept)
        when (val code = connection.responseCode) {
            in 200..299 -> connection.inputStream.use { it.readBytes() }
            401, 403 -> throw RadarAuthException()
            429 -> throw HttpStatusException(429, "Trop de demandes à Météo-France, réessaie dans quelques minutes")
            else -> throw HttpStatusException(code, "Météo-France indisponible (erreur $code), réessaie plus tard")
        }
    } finally {
        connection.disconnect()
    }
}
