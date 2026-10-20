package world.xhere.shared.places

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import world.xhere.shared.geo.BoundingBox
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.http.platformHttpEngine
import world.xhere.shared.map.InterestingFirst

/**
 * Wikipedia geosearch, Commons geotagged photos, and historic places from
 * OpenStreetMap. Wikidata is the fallback when Overpass returns nothing.
 *
 * One request at a time, at least [minIntervalMs] apart, with a User-Agent
 * that names the app. Answers are cached. Nothing here is paid and nothing
 * is keyed to one town.
 */
class PublicPlaceClient(
    engine: HttpClientEngine = platformHttpEngine(),
    private val minIntervalMs: Long = 1_100,
    private val cacheTtlMs: Long = 6 * 60 * 60 * 1000,
    private val emptyTtlMs: Long = 10 * 60 * 1000,
    private val clock: Clock = Clock.System,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : NearbyPublicPlaces {
    private val http = HttpClient(engine) { expectSuccess = false }
    private val gate = Mutex()
    private var nextAtMs = 0L
    private val cache = mutableMapOf<String, Cached>()

    override suspend fun around(box: BoundingBox): List<PublicPlace> {
        if (!Geo.isValidBoundingBox(box)) return emptyList()
        val key = cacheKey(box)
        val now = clock.now().toEpochMilliseconds()
        cached(key, now)?.let { return it }
        val center = LatLng(
            (box.minLatitude + box.maxLatitude) / 2.0,
            (box.minLongitude + box.maxLongitude) / 2.0,
        )
        val radius = Geo.distanceMeters(center, LatLng(box.maxLatitude, box.maxLongitude))
            .toInt()
            .coerceIn(400, 10_000)
        val articles = safely { wikipedia(center, radius) }
        val photos = safely { commons(center, radius) }
        val fromOsm = safely { overpass(box) }
        val historic = if (fromOsm.isNotEmpty()) fromOsm else safely { wikidata(box) }
        val merged = InterestingFirst.pickPublic(emptyList(), articles + historic + photos)
        remember(key, merged, clock.now().toEpochMilliseconds())
        return merged
    }

    private suspend fun wikipedia(center: LatLng, radius: Int): List<PublicPlace> {
        val body = get(PublicSources.WIKIPEDIA) {
            parameter("action", "query")
            parameter("format", "json")
            parameter("formatversion", "2")
            parameter("generator", "geosearch")
            parameter("ggscoord", "${center.latitude}|${center.longitude}")
            parameter("ggsradius", radius)
            parameter("ggslimit", "8")
            parameter("prop", "extracts|pageimages|coordinates")
            parameter("exintro", "1")
            parameter("explaintext", "1")
            parameter("exchars", "280")
            parameter("pithumbsize", "400")
            parameter("piprop", "thumbnail")
        }
        return body?.let(WikipediaGeo::parse).orEmpty()
    }

    private suspend fun commons(center: LatLng, radius: Int): List<PublicPlace> {
        val body = get(PublicSources.COMMONS) {
            parameter("action", "query")
            parameter("format", "json")
            parameter("formatversion", "2")
            parameter("generator", "geosearch")
            parameter("ggscoord", "${center.latitude}|${center.longitude}")
            parameter("ggsradius", radius)
            parameter("ggslimit", "6")
            parameter("ggsnamespace", "6")
            parameter("prop", "imageinfo|coordinates")
            parameter("iiprop", "url|extmetadata")
            parameter("iiurlwidth", "400")
        }
        return body?.let(CommonsGeo::parse).orEmpty()
    }

    private suspend fun overpass(box: BoundingBox): List<PublicPlace> {
        val body = get(PublicSources.OVERPASS) {
            parameter("data", PublicSources.overpassQuery(box))
        }
        return body?.let(OsmHistoric::parse).orEmpty()
    }

    private suspend fun wikidata(box: BoundingBox): List<PublicPlace> {
        val body = get(PublicSources.WIKIDATA) {
            header(HttpHeaders.Accept, "application/sparql-results+json")
            parameter("query", PublicSources.wikidataQuery(box))
            parameter("format", "json")
        }
        return body?.let(WikidataHistoric::parse).orEmpty()
    }

    private suspend fun get(
        url: String,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): String? {
        pace()
        val response = http.get(url) {
            header(HttpHeaders.UserAgent, PublicSources.USER_AGENT)
            header(HttpHeaders.Accept, "application/json")
            block()
        }
        if (!response.status.isSuccess()) return null
        return response.bodyAsText()
    }

    private suspend fun pace() {
        val wait = gate.withLock {
            val now = clock.now().toEpochMilliseconds()
            val wait = (nextAtMs - now).coerceAtLeast(0)
            nextAtMs = now + wait + minIntervalMs
            wait
        }
        if (wait > 0) sleep(wait)
    }

    private suspend fun cached(key: String, now: Long): List<PublicPlace>? = gate.withLock {
        val hit = cache[key] ?: return@withLock null
        if (now - hit.savedAtMs > hit.ttlMs) {
            cache.remove(key)
            null
        } else {
            hit.places
        }
    }

    private suspend fun remember(key: String, places: List<PublicPlace>, now: Long) {
        val ttl = if (places.isEmpty()) emptyTtlMs else cacheTtlMs
        gate.withLock { cache[key] = Cached(places, now, ttl) }
    }

    private fun cacheKey(box: BoundingBox): String {
        val lat = (((box.minLatitude + box.maxLatitude) / 2.0) * 50.0).toInt()
        val lng = (((box.minLongitude + box.maxLongitude) / 2.0) * 50.0).toInt()
        return "$lat|$lng"
    }

    private suspend fun safely(block: suspend () -> List<PublicPlace>): List<PublicPlace> = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        emptyList()
    }

    private data class Cached(val places: List<PublicPlace>, val savedAtMs: Long, val ttlMs: Long)
}
