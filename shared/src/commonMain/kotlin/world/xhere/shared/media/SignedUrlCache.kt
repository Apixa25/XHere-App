package world.xhere.shared.media

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Remembers a signed Storage URL for one object until shortly before it expires.
 * The next view of the same path reuses it instead of minting a new token,
 * which is what was making Coil treat every visit as a new image.
 */
class SignedUrlCache(
    private val lifetime: Duration = MediaCache.SIGNED_URL_MINUTES.minutes,
    private val refreshBefore: Duration = MediaCache.SIGNED_URL_REFRESH_SECONDS.seconds,
    private val now: () -> Instant = { Clock.System.now() },
    private val maxEntries: Int = 200,
) {
    private data class Entry(val url: String, val expiresAt: Instant)

    private val entries = LinkedHashMap<String, Entry>()
    private val gate = Mutex()

    suspend fun get(bucket: String, path: String): String? = gate.withLock {
        val key = MediaCache.key(bucket, path)
        val entry = entries[key] ?: return@withLock null
        if (entry.expiresAt - refreshBefore <= now()) {
            entries.remove(key)
            null
        } else {
            entry.url
        }
    }

    suspend fun put(bucket: String, path: String, url: String) {
        if (url.isBlank()) return
        gate.withLock {
            if (entries.size >= maxEntries) {
                val eldest = entries.keys.firstOrNull()
                if (eldest != null) entries.remove(eldest)
            }
            entries[MediaCache.key(bucket, path)] = Entry(url, now() + lifetime)
        }
    }
}
