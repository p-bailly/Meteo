package io.github.pbailly.meteo.data

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Nom d'un point (commune, département, pays) : géocodeur du téléphone quand il existe, sinon Nominatim
 * (OpenStreetMap), pour les téléphones sans les services Google.
 */
class PlaceNames(private val context: Context, private val httpGet: suspend (String) -> String) {

    /** Le lieu au point donné, sans altitude ; null si personne ne lui trouve de nom (en mer, sans réseau). */
    suspend fun at(latitude: Double, longitude: Double): Place? = withContext(Dispatchers.IO) {
        fromPhone(latitude, longitude)
            ?: runCatching { Nominatim.parse(httpGet(Nominatim.reverseUrl(latitude, longitude)), latitude, longitude) }.getOrNull()
    }

    private fun fromPhone(latitude: Double, longitude: Double): Place? {
        if (!Geocoder.isPresent()) return null
        val address = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.FRENCH).getFromLocation(latitude, longitude, 1)?.firstOrNull()
        }.getOrNull() ?: return null
        return Place(
            name = address.locality ?: address.subAdminArea ?: return null,
            latitude = latitude,
            longitude = longitude,
            admin1 = address.adminArea,
            admin2 = address.subAdminArea ?: "",
            country = address.countryName,
        )
    }
}
