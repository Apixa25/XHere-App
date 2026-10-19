package world.xhere.shared.live

import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import world.xhere.shared.data.LiveRepository
import world.xhere.shared.geo.LatLng

/**
 * Official alerts for the point the map is showing.
 *
 * Provider choice: the server row for that point, then the bundled catalog,
 * then whichever free provider [WeatherAlertProvider.covers] the point.
 * `weather_provider = none` returns nothing, even inside a coverage box.
 *
 * Cache-Control max-age is honored, floored at 30 seconds and capped at one hour.
 * A missing header caches for 2 minutes. An empty result, including an NWS 404,
 * is kept for at least an hour.
 */
class CachingLiveConditions(
    private val live: LiveRepository,
    private val providers: List<WeatherAlertProvider>,
    private val catalog: LiveRegionCatalog = LiveRegionCatalog,
    private val now: () -> Instant = { Clock.System.now() },
) : LiveConditions {
    private val mutex = Mutex()
    private val cache = HashMap<String, Cached>()

    override suspend fun alertsAt(point: LatLng): List<WeatherAlert> {
        val provider = resolve(point) ?: return emptyList()
        if (!provider.covers(point)) return emptyList()
        val key = cacheKey(provider.id, point)
        val current = now()
        cache[key]?.let { entry -> if (entry.until > current) return entry.alerts }
        return mutex.withLock {
            val again = now()
            cache[key]?.let { entry -> if (entry.until > again) return entry.alerts }
            val fetched = provider.fetchActive(point)
            val seconds = cacheSeconds(fetched.maxAgeSeconds, fetched.alerts.isEmpty())
            val alerts = fetched.alerts.sortedWith(compareBy({ it.severity.ordinal }, { it.event }))
            cache[key] = Cached(alerts, again + seconds.seconds)
            alerts
        }
    }

    private suspend fun resolve(point: LatLng): WeatherAlertProvider? {
        val fromServer = try {
            live.regionAt(point)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val configured = fromServer ?: catalog.containing(point)
        val providerId = configured?.weatherProvider
        if (providerId == "none") return null
        if (providerId != null) return providers.firstOrNull { it.id == providerId }
        return providers.firstOrNull { it.covers(point) }
    }

    private fun cacheKey(providerId: String, point: LatLng): String =
        "$providerId:${point.latitude.round2()}:${point.longitude.round2()}"

    private fun Double.round2(): Double = (this * 100.0).roundToInt() / 100.0

    private data class Cached(val alerts: List<WeatherAlert>, val until: Instant)

    companion object {
        fun cacheSeconds(headerMaxAge: Long?, empty: Boolean): Long {
            val base = when (headerMaxAge) {
                null -> 120L
                else -> headerMaxAge.coerceIn(30L, 3600L)
            }
            return if (empty) maxOf(base, 3600L) else base
        }
    }
}
