package world.xhere.shared.here

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import world.xhere.shared.geo.BoundingBox
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.http.platformHttpEngine
import world.xhere.shared.json.XHereJson
import world.xhere.shared.model.PlaceLabel

/**
 * The phone's door to Nominatim and Overpass.
 *
 * The upstream hosts are not in this client. They live in the `geo-proxy`
 * Edge Function (env or `private.geo_upstream`), so a mirror can be switched
 * without an app update. PLAN §2 keeps geocoding on the free public service.
 * The function caches, rounds, and rate-limits. This client only forwards the
 * caller's JWT. It never sends the service-role key.
 */
object GeoProxy {
    fun endpoint(supabaseUrl: String): String =
        supabaseUrl.trimEnd('/') + "/functions/v1/geo-proxy"

    /** The JSON body of a proxy envelope, or null when the function has nothing to give. */
    fun payload(envelope: String): String? {
        val json = runCatching { XHereJson.parseToJsonElement(envelope).jsonObject }.getOrNull() ?: return null
        val body = json["body"] ?: return null
        if (body is JsonNull) return null
        return body.toString()
    }
}

class GeoProxyClient(
    private val endpoint: String,
    private val anonKey: String,
    private val bearer: suspend () -> String? = { null },
    engine: HttpClientEngine = platformHttpEngine(),
) {
    private val http = HttpClient(engine) { expectSuccess = false }

    suspend fun reverse(point: LatLng): PlaceLabel? {
        if (!Geo.isValid(point)) return null
        val body = call(
            buildJsonObject {
                put("op", "reverse")
                put("lat", point.latitude)
                put("lng", point.longitude)
            },
        ) ?: return null
        return Nominatim.parse(body)
    }

    /** Overpass JSON, or null when the proxy is limited, signed out, or empty. */
    suspend fun overpass(box: BoundingBox): String? {
        if (!Geo.isValidBoundingBox(box)) return null
        return call(
            buildJsonObject {
                put("op", "overpass")
                put("south", box.minLatitude)
                put("west", box.minLongitude)
                put("north", box.maxLatitude)
                put("east", box.maxLongitude)
            },
        )
    }

    private suspend fun call(payload: JsonObject): String? {
        val token = bearer()?.takeIf { it.isNotBlank() } ?: return null
        if (!endpoint.contains("/functions/v1/geo-proxy")) return null
        val response = http.post(endpoint) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("apikey", anonKey)
            header(HttpHeaders.Accept, "application/json")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        if (!response.status.isSuccess()) return null
        return GeoProxy.payload(response.bodyAsText())
    }
}
