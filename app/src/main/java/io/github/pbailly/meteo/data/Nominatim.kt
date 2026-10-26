package io.github.pbailly.meteo.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

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
