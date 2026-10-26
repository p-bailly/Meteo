package io.github.pbailly.meteo.data

import android.content.Context

/** Nom d'un point (commune, département, pays) d'après Nominatim (OpenStreetMap) : le navigateur n'a pas de géocodeur. */
class PlaceNames(private val context: Context, private val httpGet: suspend (String) -> String) {
    /** Le lieu au point donné, sans altitude ; null si personne ne lui trouve de nom (en mer, sans réseau). */
    suspend fun at(latitude: Double, longitude: Double): Place? =
        runCatching { Nominatim.parse(httpGet(Nominatim.reverseUrl(latitude, longitude)), latitude, longitude) }.getOrNull()
}
