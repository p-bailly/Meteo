package io.github.pbailly.meteo.data.maps

import io.github.pbailly.meteo.data.HttpStatusException
import io.github.pbailly.meteo.data.retryOnNetworkError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

// Téléchargements des couches de la carte sur le téléphone (la webapp a les siens, par fetch).

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
