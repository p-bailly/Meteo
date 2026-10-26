package io.github.pbailly.meteo.data.radar

import io.github.pbailly.meteo.data.HttpStatusException
import io.github.pbailly.meteo.data.fetchBytes
import kotlinx.coroutines.delay
import java.io.IOException

// Téléchargements Météo-France du navigateur (comme app/…/data/radar/MeteoFranceHttp.kt, par fetch).

/** GET sur les API Météo-France, avec la clé dans l'en-tête `apikey` ; deux nouvelles tentatives sur une coupure. */
internal suspend fun meteoFranceGet(url: String, apiKey: String, accept: String): ByteArray {
    // Le radar arrive en HDF5 : son lecteur (h5wasm) doit être prêt quand OdimDecoder le lira.
    if (accept == "application/x-hdf") awaitHdf5()
    var last: IOException? = null
    for (k in 0 until 3) {
        try {
            val reply = fetchBytes(url, mapOf("apikey" to apiKey, "Accept" to accept))
            return when (val code = reply.status) {
                in 200..299 -> reply.bytes
                401, 403 -> throw RadarAuthException()
                429 -> throw HttpStatusException(429, "Trop de demandes à Météo-France, réessaie dans quelques minutes")
                else -> throw HttpStatusException(code, "Météo-France indisponible (erreur $code), réessaie plus tard")
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
