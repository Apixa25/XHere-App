package world.xhere.shared.here

import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import world.xhere.shared.data.PlaceRepository
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.PlaceLabel

/**
 * Place name for a Here Card.
 *
 * Reads the database cache first. On a miss, asks for the global Nominatim
 * slot and, if this device is also inside its own 1.1 second gap, does one
 * reverse lookup. The name is stored with attribution the database stamps.
 * A failure leaves the card without a name. The drops still load.
 */
class HerePlaces(
    private val places: PlaceRepository,
    private val reverse: suspend (LatLng) -> PlaceLabel,
    private val minIntervalMs: Long = Here.NOMINATIM_MIN_INTERVAL_MS,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val gate = Mutex()
    private var nextAllowedAt: Long = 0

    suspend fun describe(spot: LatLng): PlaceLabel? {
        if (!Geo.isValid(spot)) return null
        places.cachedLabel(spot)?.let { return it }
        return gate.withLock {
            places.cachedLabel(spot)?.let { return@withLock it }
            val time = now()
            if (time < nextAllowedAt) return@withLock null
            nextAllowedAt = time + minIntervalMs
            if (!runCatching { places.claimNominatimSlot() }.getOrDefault(false)) return@withLock null
            val fetched = runCatching { reverse(spot) }.getOrNull() ?: return@withLock null
            val name = fetched.name.trim().take(160)
            if (name.isEmpty()) return@withLock null
            val address = fetched.address?.trim()?.take(300)?.ifEmpty { null }
            val stored = fetched.copy(
                name = name,
                address = address,
                attribution = Here.ATTRIBUTION,
                license = Here.LICENSE,
                provider = Here.PROVIDER,
            )
            runCatching { places.remember(spot, stored.name, stored.address) }
            stored
        }
    }
}
