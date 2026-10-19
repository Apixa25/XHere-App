package world.xhere.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.provider.Settings
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import world.xhere.shared.geo.LatLng
import world.xhere.shared.integrity.LocationSignal

/**
 * One foreground fix plus the coarse signals the server uses to decide
 * whether the action counts. Nothing here is written to a trail.
 */
data class LocationReading(
    val point: LatLng,
    val signal: LocationSignal,
)

/**
 * Foreground fixes only. Collected while the map is on screen, dropped when it leaves.
 * No background permission, no foreground service, no stored history.
 */
interface DeviceLocation {
    fun hasPermission(): Boolean

    fun fixes(): Flow<LocationReading>
}

class AndroidDeviceLocation(
    private val context: Context,
    private val clock: Clock = Clock.System,
) : DeviceLocation {
    override fun hasPermission(): Boolean = ForegroundLocation.isGranted(context)

    @SuppressLint("MissingPermission")
    override fun fixes(): Flow<LocationReading> {
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
                trySend(location.toReading(developerOptionsEnabled(context), clock.now()))
            }
            providers.forEach { provider ->
                runCatching { manager.getLastKnownLocation(provider) }
                    .getOrNull()
                    ?.let { trySend(it.toReading(developerOptionsEnabled(context), clock.now())) }
                runCatching {
                    manager.requestLocationUpdates(provider, 2_000L, 8f, listener, Looper.getMainLooper())
                }
            }
            awaitClose { runCatching { manager.removeUpdates(listener) } }
        }
    }
}

internal fun developerOptionsEnabled(context: Context): Boolean =
    runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    }.getOrDefault(false)

/** Mock-location and developer-option bits. Play Integrity is not called. */
@Suppress("DEPRECATION")
internal fun Location.toReading(developerOptions: Boolean, now: Instant): LocationReading {
    val mock = if (Build.VERSION.SDK_INT >= 31) isMock else isFromMockProvider
    val fixAt = time.takeIf { it > 0L }?.let(Instant::fromEpochMilliseconds)
    return LocationReading(
        point = LatLng(latitude, longitude),
        signal = LocationSignal(
            mock = mock,
            developerOptions = developerOptions,
            deviceFix = true,
            accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
            fixAt = fixAt,
            deviceAt = now,
        ),
    )
}
