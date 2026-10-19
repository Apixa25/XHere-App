package world.xhere.shared.live

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import world.xhere.shared.geo.LatLng
import world.xhere.shared.http.platformHttpEngine
import world.xhere.shared.json.XHereJson

enum class AlertSeverity {
    EXTREME,
    SEVERE,
    MODERATE,
    MINOR,
    UNKNOWN,
}

/**
 * One official alert for the area on screen. Not a drop, and not stored.
 * [attribution] is the publisher the banner must show.
 */
data class WeatherAlert(
    val id: String,
    val event: String,
    val severity: AlertSeverity,
    val expiresAt: Instant?,
    val headline: String?,
    val attribution: String,
    val senderName: String,
    val sourceUrl: String?,
)

data class WeatherFetch(
    val alerts: List<WeatherAlert>,
    val maxAgeSeconds: Long?,
)

/** A free official-alert feed. Another country adds another implementation. */
interface WeatherAlertProvider {
    val id: String

    fun covers(point: LatLng): Boolean

    suspend fun fetchActive(point: LatLng): WeatherFetch
}

/** What the map and the Here Card ask. */
interface LiveConditions {
    suspend fun alertsAt(point: LatLng): List<WeatherAlert>
}

/** Tests and previews that should not call a weather service. */
object QuietConditions : LiveConditions {
    override suspend fun alertsAt(point: LatLng): List<WeatherAlert> = emptyList()
}

/**
 * Coarse boxes for api.weather.gov. A point outside them is not polled.
 * Toronto sits inside the lower-48 box; a 404 from NWS is cached so it is not polled again soon.
 * Paris and London are outside every box.
 */
object NwsCoverage {
    private data class Box(val south: Double, val north: Double, val west: Double, val east: Double)

    private val boxes = listOf(
        Box(24.4, 49.4, -125.0, -66.8),
        Box(51.0, 71.6, -170.0, -129.0),
        Box(18.8, 22.3, -160.3, -154.7),
        Box(17.6, 18.6, -67.3, -65.2),
        Box(17.6, 18.5, -65.1, -64.5),
        Box(13.2, 13.7, 144.6, 145.0),
        Box(-14.4, -14.2, -170.9, -169.4),
    )

    fun covers(point: LatLng): Boolean = boxes.any { box ->
        point.latitude in box.south..box.north && point.longitude in box.west..box.east
    }
}

object NwsAlerts {
    const val ENDPOINT = "https://api.weather.gov/alerts/active"
    const val USER_AGENT = "XHere/0.1 (https://xhere.world; weather-alerts)"
    const val ATTRIBUTION = "National Weather Service"

    /** `yyyy-mm-dd HH:mm UTC` from an [Instant]. */
    fun formatExpiry(instant: Instant): String {
        val iso = instant.toString()
        val date = iso.take(10)
        val time = iso.drop(11).take(5)
        return "$date $time UTC"
    }

    fun parse(body: String): List<WeatherAlert> {
        val root = runCatching { XHereJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val features = root["features"] as? JsonArray ?: return emptyList()
        return features.mapNotNull { element ->
            val feature = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val props = feature["properties"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return@mapNotNull null
            val id = props.text("id") ?: feature.text("id") ?: return@mapNotNull null
            val event = props.text("event") ?: return@mapNotNull null
            val sender = props.text("senderName") ?: ATTRIBUTION
            val link = props.text("@id") ?: props.text("uri") ?: feature.text("id")
            WeatherAlert(
                id = id,
                event = event,
                severity = severity(props.text("severity")),
                expiresAt = props.text("expires")?.let { runCatching { Instant.parse(it) }.getOrNull() },
                headline = props.text("headline"),
                attribution = ATTRIBUTION,
                senderName = sender,
                sourceUrl = link?.takeIf { it.startsWith("https://") },
            )
        }.sortedWith(compareBy({ it.severity.ordinal }, { it.event }))
    }

    private fun severity(value: String?): AlertSeverity = when (value?.lowercase()) {
        "extreme" -> AlertSeverity.EXTREME
        "severe" -> AlertSeverity.SEVERE
        "moderate" -> AlertSeverity.MODERATE
        "minor" -> AlertSeverity.MINOR
        else -> AlertSeverity.UNKNOWN
    }
}

class NwsWeatherAlertProvider(
    engine: HttpClientEngine = platformHttpEngine(),
) : WeatherAlertProvider {
    override val id: String = "nws"

    private val http = HttpClient(engine) {
        expectSuccess = false
    }

    override fun covers(point: LatLng): Boolean = NwsCoverage.covers(point)

    override suspend fun fetchActive(point: LatLng): WeatherFetch {
        val response = http.get(NwsAlerts.ENDPOINT) {
            header(HttpHeaders.UserAgent, NwsAlerts.USER_AGENT)
            header(HttpHeaders.Accept, "application/geo+json")
            parameter("point", "${point.latitude},${point.longitude}")
        }
        if (response.status.value == 404) return WeatherFetch(emptyList(), 3600)
        if (!response.status.isSuccess()) error("nws_${response.status.value}")
        val maxAge = response.headers[HttpHeaders.CacheControl].maxAgeSeconds()
        return WeatherFetch(NwsAlerts.parse(response.bodyAsText()), maxAge)
    }
}

internal fun String?.maxAgeSeconds(): Long? {
    if (this.isNullOrBlank()) return null
    return Regex("""(?i)max-age=(\d+)""").find(this)?.groupValues?.get(1)?.toLongOrNull()
}

private fun JsonObject.text(name: String): String? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.takeIf { it.isNotBlank() }
}
