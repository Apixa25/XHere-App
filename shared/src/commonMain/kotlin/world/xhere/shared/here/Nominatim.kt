package world.xhere.shared.here

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import world.xhere.shared.geo.LatLng
import world.xhere.shared.http.platformHttpEngine
import world.xhere.shared.json.XHereJson
import world.xhere.shared.model.PlaceLabel

/**
 * Reverse geocoding against the public Nominatim service.
 *
 * Usage policy (https://operations.osmfoundation.org/policies/nominatim/):
 * at most one request per second, a User-Agent that names the application,
 * and visible attribution of © OpenStreetMap contributors. This client does
 * the request only. [HerePlaces] caches the result and keeps the rate.
 * No API key and no paid geocoder.
 */
object Nominatim {
    fun reverseUrl(point: LatLng): String =
        "${Here.ENDPOINT}?format=jsonv2&lat=${point.latitude}&lon=${point.longitude}&zoom=17&addressdetails=1"

    /** Null when Nominatim has no name for this coordinate. */
    fun parse(body: String): PlaceLabel? {
        val json = runCatching { XHereJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        if (json["error"] != null) return null
        val address = json["address"]?.let { runCatching { it.jsonObject }.getOrNull() }
        val display = json.text("display_name")
        val name = listOf(json.text("name"), address?.text("road"), display?.substringBefore(","))
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            ?: return null
        return PlaceLabel(
            name = name.take(160),
            address = display?.trim()?.take(300)?.ifEmpty { null },
            attribution = Here.ATTRIBUTION,
            license = Here.LICENSE,
            provider = Here.PROVIDER,
        )
    }
}

class NominatimClient(
    engine: HttpClientEngine = platformHttpEngine(),
) {
    private val http = HttpClient(engine) {
        expectSuccess = false
    }

    suspend fun reverse(point: LatLng): PlaceLabel {
        val response = http.get(Here.ENDPOINT) {
            header(HttpHeaders.UserAgent, Here.USER_AGENT)
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.Referrer, Here.REFERER)
            parameter("format", "jsonv2")
            parameter("lat", point.latitude)
            parameter("lon", point.longitude)
            parameter("zoom", 17)
            parameter("addressdetails", 1)
        }
        if (!response.status.isSuccess()) error("nominatim_${response.status.value}")
        return Nominatim.parse(response.bodyAsText()) ?: error("nominatim_empty")
    }
}

private fun JsonObject.text(name: String): String? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.takeIf { it.isNotBlank() }
}
