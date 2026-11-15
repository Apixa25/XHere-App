package world.xhere.shared.here

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import world.xhere.shared.json.XHereJson
import world.xhere.shared.model.PlaceLabel

/**
 * Parses a Nominatim reverse-geocode body.
 *
 * The phone does not call Nominatim. [GeoProxyClient] asks the `geo-proxy`
 * Edge Function, which is the only place that talks to the upstream host.
 * Usage policy (https://operations.osmfoundation.org/policies/nominatim/):
 * at most one request per second for the whole app, a User-Agent that names
 * the application, and visible attribution of © OpenStreetMap contributors.
 * No API key and no paid geocoder.
 */
object Nominatim {
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

private fun JsonObject.text(name: String): String? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.takeIf { it.isNotBlank() }
}
