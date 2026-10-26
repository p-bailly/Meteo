package io.github.pbailly.meteo.data.maps

import io.github.pbailly.meteo.data.HttpStatusException
import io.github.pbailly.meteo.data.fetchBytes
import kotlinx.coroutines.delay
import java.io.IOException

// Téléchargements des couches de la carte dans le navigateur (comme app/…/data/maps/MapsHttp.kt, par fetch).

/** GET simple ; [headers] pour les clés d'API. Réessaie deux fois si la connexion est coupée en route. */
suspend fun httpBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
    var last: IOException? = null
    for (k in 0 until 3) {
        try {
            val reply = fetchBytes(url, headers)
            return when (val code = reply.status) {
                in 200..299 -> reply.bytes
                401, 403 -> throw MapKeyException()
                else -> throw HttpStatusException(code)
            }
        } catch (e: HttpStatusException) {
            throw e
        } catch (e: IOException) {
            last = e
            if (k < 2) delay(500L * (k + 1))
        }
    }
    throw last!!
}
