package io.github.pbailly.meteo.data

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Une position du navigateur ; l'altitude quand il la donne (GPS du téléphone). */
class Location(val latitude: Double, val longitude: Double, private val altitudeMeters: Double?) {
    fun hasAltitude(): Boolean = altitudeMeters != null
    val altitude: Double get() = altitudeMeters ?: 0.0
}

private fun locate(onFix: (Double, Double, Double) -> Unit, onError: () -> Unit): Unit = js(
    """{
        if (!navigator.geolocation) { onError(); return; }
        navigator.geolocation.getCurrentPosition(
            (p) => onFix(p.coords.latitude, p.coords.longitude, p.coords.altitude == null ? NaN : p.coords.altitude),
            () => onError(),
            { enableHighAccuracy: false, timeout: 20000, maximumAge: 900000 }
        );
    }"""
)

/** Position par le navigateur (qui demande l'autorisation la première fois). */
class DeviceLocation(private val context: Context, private val names: PlaceNames) {
    suspend fun current(): Location? = suspendCancellableCoroutine { cont ->
        locate(
            onFix = { lat, lon, alt -> if (cont.isActive) cont.resume(Location(lat, lon, alt.takeIf { !it.isNaN() })) },
            onError = { if (cont.isActive) cont.resume(null) },
        )
    }

    /** Le lieu de la position : la commune la plus proche quand on lui trouve un nom. */
    suspend fun placeFor(location: Location): Place =
        (names.at(location.latitude, location.longitude) ?: Place("Ma position", location.latitude, location.longitude, admin2 = ""))
            .copy(elevation = if (location.hasAltitude()) location.altitude else null)
}
