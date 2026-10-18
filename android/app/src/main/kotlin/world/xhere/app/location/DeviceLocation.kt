package world.xhere.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import world.xhere.shared.geo.LatLng

/**
 * Foreground fixes only. Collected while the map is on screen, dropped when it leaves.
 * No background permission, no foreground service, no stored history.
 */
interface DeviceLocation {
    fun hasPermission(): Boolean

    fun fixes(): Flow<LatLng>
}

class AndroidDeviceLocation(private val context: Context) : DeviceLocation {
    override fun hasPermission(): Boolean = ForegroundLocation.isGranted(context)

    @SuppressLint("MissingPermission")
    override fun fixes(): Flow<LatLng> {
        if (!hasPermission()) return emptyFlow()
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return emptyFlow()
        return callbackFlow {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
            if (providers.isEmpty()) {
                close()
                return@callbackFlow
            }
            val listener = LocationListener { location ->
                trySend(LatLng(location.latitude, location.longitude))
            }
            providers.forEach { provider ->
                runCatching { manager.getLastKnownLocation(provider) }
                    .getOrNull()
                    ?.let { trySend(LatLng(it.latitude, it.longitude)) }
                runCatching {
                    manager.requestLocationUpdates(provider, 2_000L, 8f, listener, Looper.getMainLooper())
                }
            }
            awaitClose { runCatching { manager.removeUpdates(listener) } }
        }
    }
}
