package io.github.pbailly.meteo.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Position du téléphone, sans les services Google (LocationManager d'Android). */
class DeviceLocation(private val context: Context, private val names: PlaceNames) {

    /** Renvoie la position actuelle, ou null si elle est indisponible. La permission doit être accordée. */
    @SuppressLint("MissingPermission")
    suspend fun current(): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

        val lastKnown = providers
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < RECENT_MS) return lastKnown

        for (provider in providers) {
            val fix = withTimeoutOrNull(FIX_TIMEOUT_MS) { requestFix(manager, provider) }
            if (fix != null) return fix
        }
        return lastKnown
    }

    /** Le lieu de la position : la commune la plus proche quand on lui trouve un nom. */
    suspend fun placeFor(location: Location): Place =
        (names.at(location.latitude, location.longitude) ?: Place("Ma position", location.latitude, location.longitude, admin2 = ""))
            .copy(elevation = if (location.hasAltitude()) location.altitude else null)

    @SuppressLint("MissingPermission")
    private suspend fun requestFix(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            try {
                LocationManagerCompat.getCurrentLocation(
                    manager, provider, signal, ContextCompat.getMainExecutor(context),
                ) { location -> if (cont.isActive) cont.resume(location) }
            } catch (_: SecurityException) {
                if (cont.isActive) cont.resume(null)
            } catch (_: IllegalArgumentException) {
                if (cont.isActive) cont.resume(null)
            }
        }

    private companion object {
        const val RECENT_MS = 15 * 60 * 1000L
        const val FIX_TIMEOUT_MS = 20_000L
    }
}
