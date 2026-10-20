package io.github.pbailly.meteo.data

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * Nom d'un point (commune, département, pays) : géocodeur du téléphone quand il existe, sinon Nominatim
 * (OpenStreetMap), pour les téléphones sans les services Google.
 */
class PlaceNames(private val context: Context, private val httpGet: (String) -> String) {

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

/** Géocodage inverse d'OpenStreetMap (nominatim.openstreetmap.org), sans clé. Sans réseau, pour les tests. */
object Nominatim {
    /**
     * Du plus parlant au plus vague : la commune avant le hameau, le hameau avant le quartier ; en France,
     * « municipality » est l'arrondissement (Pontarlier pour Montlebon), donc en dernier.
     */
    private val NAME_KEYS = listOf("village", "town", "city", "hamlet", "isolated_dwelling", "suburb", "locality", "municipality")

    /** Niveau 14 : jusqu'aux hameaux, sans descendre aux rues. */
    fun reverseUrl(latitude: Double, longitude: Double): String =
        "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=14&accept-language=fr&lat=%.5f&lon=%.5f"
            .format(Locale.ROOT, latitude, longitude)

    /** La commune au point donné, avec son département (« county » en France) ; null si la réponse n'en donne pas. */
    fun parse(body: String, latitude: Double, longitude: Double): Place? {
        val address = Json.parseToJsonElement(body).jsonObject["address"] as? JsonObject ?: return null
        fun field(key: String) = address[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return Place(
            name = NAME_KEYS.firstNotNullOfOrNull(::field) ?: return null,
            latitude = latitude,
            longitude = longitude,
            admin1 = field("state"),
            admin2 = field("county") ?: "",
            country = field("country"),
        )
    }
}
