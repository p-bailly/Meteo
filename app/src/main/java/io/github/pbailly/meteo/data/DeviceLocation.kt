package io.github.pbailly.meteo.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** Position du téléphone, sans les services Google (LocationManager d'Android). */
class DeviceLocation(private val context: Context) {

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

    /** Nom de la commune la plus proche, via le géocodeur du téléphone quand il existe. */
    suspend fun placeFor(location: Location): Place = withContext(Dispatchers.IO) {
        val address = if (Geocoder.isPresent()) {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(context, Locale.FRENCH).getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
            }.getOrNull()
        } else {
            null
        }
        Place(
            name = address?.locality ?: address?.subAdminArea ?: "Ma position",
            latitude = location.latitude,
            longitude = location.longitude,
            admin1 = address?.adminArea,
            country = address?.countryName,
            elevation = if (location.hasAltitude()) location.altitude else null,
        )
    }

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
