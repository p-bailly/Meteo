package io.github.pbailly.meteo.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Favoris, lieux consultés récemment et dernier lieu affiché, gardés dans les préférences de l'app. */
class PlacesStore(context: Context) {
    private val prefs = context.getSharedPreferences("places", Context.MODE_PRIVATE)

    fun favorites(): List<Place> = list(KEY_FAVORITES)

    fun saveFavorites(places: List<Place>) = saveList(KEY_FAVORITES, places)

    /** Lieux consultés, du plus récent au plus ancien. */
    fun recents(): List<Place> = list(KEY_RECENTS)

    fun saveRecents(places: List<Place>) = saveList(KEY_RECENTS, places)

    private fun list(key: String): List<Place> = runCatching {
        val raw = prefs.getString(key, null) ?: return emptyList()
        (Json.parseToJsonElement(raw) as JsonArray).mapNotNull { decode(it.jsonObject) }
    }.getOrDefault(emptyList())

    private fun saveList(key: String, places: List<Place>) {
        val array = buildJsonArray { places.forEach { add(encode(it)) } }
        prefs.edit().putString(key, array.toString()).apply()
    }

    fun lastPlace(): Place? = runCatching {
        prefs.getString(KEY_LAST, null)?.let { decode(Json.parseToJsonElement(it).jsonObject) }
    }.getOrNull()

    fun saveLastPlace(place: Place) {
        prefs.edit().putString(KEY_LAST, encode(place).toString()).apply()
    }

    private fun encode(place: Place): JsonObject = buildJsonObject {
        put("name", place.name)
        put("latitude", place.latitude)
        put("longitude", place.longitude)
        place.admin1?.let { put("admin1", it) }
        place.admin2?.let { put("admin2", it) }
        place.country?.let { put("country", it) }
        place.elevation?.let { put("elevation", it) }
    }

    private fun decode(o: JsonObject): Place? {
        val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val lat = o["latitude"]?.jsonPrimitive?.doubleOrNull ?: return null
        val lon = o["longitude"]?.jsonPrimitive?.doubleOrNull ?: return null
        return Place(
            name = name,
            latitude = lat,
            longitude = lon,
            admin1 = o["admin1"]?.jsonPrimitive?.contentOrNull,
            admin2 = o["admin2"]?.jsonPrimitive?.contentOrNull,
            country = o["country"]?.jsonPrimitive?.contentOrNull,
            elevation = o["elevation"]?.jsonPrimitive?.doubleOrNull.validElevation(),
        )
    }

    private companion object {
        const val KEY_FAVORITES = "favorites"
        const val KEY_RECENTS = "recents"
        const val KEY_LAST = "last_place"
    }
}
