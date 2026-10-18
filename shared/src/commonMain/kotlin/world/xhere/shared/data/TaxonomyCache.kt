package world.xhere.shared.data

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import world.xhere.shared.model.Taxonomy

/**
 * In-memory taxonomy cache. PLAN §4 says the app caches categories and refreshes them;
 * M2 does that here. It is not the offline map cache (Room, last viewed area), which
 * PLAN schedules for v1.2. Process death drops this cache.
 */
class TaxonomyCache(
    private val maxAge: Duration = 6.hours,
    private val clock: () -> Instant = { kotlin.time.Clock.System.now() },
) {
    private val mutex = Mutex()
    private var value: Taxonomy? = null
    private var loadedAt: Instant? = null

    fun peek(): Taxonomy? = value

    suspend fun getOrLoad(force: Boolean = false, loader: suspend () -> Taxonomy): Taxonomy = mutex.withLock {
        val cached = value
        val at = loadedAt
        if (cached != null && at != null && !force && clock() - at < maxAge) return cached
        val loaded = loader()
        value = loaded
        loadedAt = clock()
        loaded
    }

    fun invalidate() {
        value = null
        loadedAt = null
    }
}
