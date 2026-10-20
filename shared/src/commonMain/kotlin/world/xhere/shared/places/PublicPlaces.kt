package world.xhere.shared.places

import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import world.xhere.shared.geo.BoundingBox
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.json.XHereJson
import world.xhere.shared.model.DropSource
import world.xhere.shared.model.MapFeature

/**
 * A place already described somewhere public. Not a drop, and not stored.
 * The sheet shows the summary, the picture, and who published it.
 *
 * PLAN §3: Wikipedia, Wikimedia Commons, and historic OpenStreetMap or
 * Wikidata are the free global layers. No key and no paid feed.
 */
enum class PublicPlaceKind {
    ARTICLE,
    PHOTO,
    HISTORIC,
}

data class PublicPlace(
    val id: String,
    val title: String,
    val summary: String?,
    val latitude: Double,
    val longitude: Double,
    val imageUrl: String?,
    val pageUrl: String,
    val attribution: String,
    val license: String,
    val kind: PublicPlaceKind,
) {
    val location: LatLng get() = LatLng(latitude, longitude)

    fun asFeature(): MapFeature = MapFeature(
        kind = KIND,
        shortId = id,
        categorySlug = CATEGORY,
        groupSlug = CATEGORY,
        title = title,
        latitude = latitude,
        longitude = longitude,
        pointCount = 1,
        source = DropSource.OPEN_DATA,
    )

    companion object {
        const val KIND = "public"
        const val CATEGORY = "things_around"
    }
}

/** What the map asks when a viewport has almost no interesting drops. */
interface NearbyPublicPlaces {
    suspend fun around(box: BoundingBox): List<PublicPlace>
}

/** Tests and previews. The phone uses [PublicPlaceClient]. */
object QuietPublicPlaces : NearbyPublicPlaces {
    override suspend fun around(box: BoundingBox): List<PublicPlace> = emptyList()
}

object PublicSources {
    const val USER_AGENT = "XHere/0.1 (https://xhere.world; public-places)"
    const val WIKIPEDIA = "https://en.wikipedia.org/w/api.php"
    const val COMMONS = "https://commons.wikimedia.org/w/api.php"
    const val OVERPASS = "https://overpass-api.de/api/interpreter"
    const val WIKIDATA = "https://query.wikidata.org/sparql"
    const val WIKI_CREDIT = "Wikipedia contributors"
    const val WIKI_LICENSE = "CC BY-SA 4.0"
    const val OSM_CREDIT = "© OpenStreetMap contributors"
    const val OSM_LICENSE = "ODbL 1.0"
    const val WIKIDATA_CREDIT = "Wikidata contributors"
    const val WIKIDATA_LICENSE = "CC0 1.0"
    const val MAP_CREDIT = "Places from Wikipedia, Wikimedia Commons, and OpenStreetMap"

    /** The same allowance the history importer uses for Commons photos. */
    fun licenseAllowed(name: String): Boolean {
        val key = name.lowercase()
        if (key.isBlank() || "all rights reserved" in key) return false
        return listOf("public domain", "no known", "cc0", "pdm", "cc by", "cc-by").any { it in key }
    }

    fun wikipediaUrl(title: String): String {
        val path = title.trim().replace(' ', '_').encodeURLPathPart()
        return "https://en.wikipedia.org/wiki/$path"
    }

    fun overpassQuery(box: BoundingBox): String {
        val south = box.minLatitude
        val west = box.minLongitude
        val north = box.maxLatitude
        val east = box.maxLongitude
        return "[out:json][timeout:12];(" +
            "node[\"historic\"][\"name\"]($south,$west,$north,$east);" +
            "way[\"historic\"][\"name\"]($south,$west,$north,$east);" +
            ");out center 20;"
    }

    /** Historic-shaped classes only. A town name never goes in the query. */
    fun wikidataQuery(box: BoundingBox): String {
        val southWest = "Point(${box.minLongitude} ${box.minLatitude})"
        val northEast = "Point(${box.maxLongitude} ${box.maxLatitude})"
        return """
            SELECT ?item ?itemLabel ?coord ?article ?desc WHERE {
              SERVICE wikibase:box {
                ?item wdt:P625 ?coord .
                bd:serviceParam wikibase:cornerSouthWest "$southWest"^^geo:wktLiteral .
                bd:serviceParam wikibase:cornerNorthEast "$northEast"^^geo:wktLiteral .
              }
              ?item wdt:P31/wdt:P279* ?class .
              VALUES ?class { wd:Q4989906 wd:Q5003624 wd:Q839954 wd:Q23413 wd:Q33506 }
              OPTIONAL { ?article schema:about ?item ; schema:isPartOf <https://en.wikipedia.org/> . }
              OPTIONAL { ?item schema:description ?desc . FILTER (lang(?desc) = "en") }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "en" . }
            }
            LIMIT 12
        """.trimIndent()
    }
}

object WikipediaGeo {
    fun parse(body: String): List<PublicPlace> {
        val pages = pages(body) ?: return emptyList()
        return pages.mapNotNull { page ->
            val id = page.int("pageid") ?: return@mapNotNull null
            val title = page.text("title")?.take(180) ?: return@mapNotNull null
            val point = coordinate(page) ?: return@mapNotNull null
            if (!Geo.isValid(point)) return@mapNotNull null
            val thumb = page.obj("thumbnail")?.text("source")?.takeIf { it.startsWith("https://") }
            PublicPlace(
                id = "enwiki:$id",
                title = title,
                summary = page.text("extract")?.take(280)?.trim()?.ifEmpty { null },
                latitude = point.latitude,
                longitude = point.longitude,
                imageUrl = thumb,
                pageUrl = PublicSources.wikipediaUrl(title),
                attribution = PublicSources.WIKI_CREDIT,
                license = PublicSources.WIKI_LICENSE,
                kind = PublicPlaceKind.ARTICLE,
            )
        }
    }
}

object CommonsGeo {
    fun parse(body: String): List<PublicPlace> {
        val pages = pages(body) ?: return emptyList()
        return pages.mapNotNull { page ->
            val id = page.int("pageid") ?: return@mapNotNull null
            val rawTitle = page.text("title") ?: return@mapNotNull null
            val info = page.array("imageinfo")?.firstObject() ?: return@mapNotNull null
            val meta = info.obj("extmetadata")
            val license = meta?.metaValue("LicenseShortName").orEmpty()
            if (!PublicSources.licenseAllowed(license)) return@mapNotNull null
            val pageUrl = info.text("descriptionurl")?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            val image = info.text("thumburl")?.takeIf { it.startsWith("https://") }
                ?: info.text("url")?.takeIf { it.startsWith("https://") }
            val point = coordinate(page) ?: return@mapNotNull null
            if (!Geo.isValid(point)) return@mapNotNull null
            val artist = meta?.metaValue("Artist")?.let(::plain)?.take(120)
            val credit = if (artist.isNullOrBlank()) {
                "Wikimedia Commons contributors"
            } else {
                "Photo by $artist, Wikimedia Commons"
            }
            PublicPlace(
                id = "commons:$id",
                title = rawTitle.removePrefix("File:").replace('_', ' ').take(180),
                summary = meta?.metaValue("ImageDescription")?.let(::plain)?.take(280)?.ifEmpty { null },
                latitude = point.latitude,
                longitude = point.longitude,
                imageUrl = image,
                pageUrl = pageUrl,
                attribution = credit,
                license = license.take(80),
                kind = PublicPlaceKind.PHOTO,
            )
        }
    }
}

object OsmHistoric {
    private val skip = setOf("yes", "building", "house", "farm", "hut", "shed", "warehouse", "industrial")

    fun parse(body: String): List<PublicPlace> {
        val root = jsonObject(body) ?: return emptyList()
        val elements = root["elements"] as? JsonArray ?: return emptyList()
        return elements.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val tags = row.obj("tags") ?: return@mapNotNull null
            val historic = tags.text("historic")?.lowercase() ?: return@mapNotNull null
            if (historic in skip) return@mapNotNull null
            val name = tags.text("name")?.take(180) ?: return@mapNotNull null
            val type = row.text("type") ?: return@mapNotNull null
            if (type != "node" && type != "way") return@mapNotNull null
            val id = row.raw("id") ?: return@mapNotNull null
            val lat = row.double("lat") ?: row.obj("center")?.double("lat") ?: return@mapNotNull null
            val lng = row.double("lon") ?: row.obj("center")?.double("lon") ?: return@mapNotNull null
            val point = LatLng(lat, lng)
            if (!Geo.isValid(point)) return@mapNotNull null
            PublicPlace(
                id = "osm:$type/$id",
                title = name,
                summary = "Historic $historic on OpenStreetMap.",
                latitude = lat,
                longitude = lng,
                imageUrl = null,
                pageUrl = "https://www.openstreetmap.org/$type/$id",
                attribution = PublicSources.OSM_CREDIT,
                license = PublicSources.OSM_LICENSE,
                kind = PublicPlaceKind.HISTORIC,
            )
        }
    }
}

object WikidataHistoric {
    fun parse(body: String): List<PublicPlace> {
        val root = jsonObject(body) ?: return emptyList()
        val bindings = root.obj("results")?.get("bindings") as? JsonArray ?: return emptyList()
        return bindings.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val item = row.binding("item") ?: return@mapNotNull null
            val qid = item.substringAfterLast('/').takeIf { it.startsWith("Q") } ?: return@mapNotNull null
            val title = row.binding("itemLabel")?.take(180) ?: return@mapNotNull null
            if (title == qid) return@mapNotNull null
            val point = point(row.binding("coord")) ?: return@mapNotNull null
            if (!Geo.isValid(point)) return@mapNotNull null
            val article = row.binding("article")?.takeIf { it.startsWith("https://") }
            PublicPlace(
                id = "wikidata:$qid",
                title = title,
                summary = row.binding("desc")?.take(280),
                latitude = point.latitude,
                longitude = point.longitude,
                imageUrl = null,
                pageUrl = article ?: "https://www.wikidata.org/wiki/$qid",
                attribution = PublicSources.WIKIDATA_CREDIT,
                license = PublicSources.WIKIDATA_LICENSE,
                kind = PublicPlaceKind.HISTORIC,
            )
        }
    }

    private fun point(wkt: String?): LatLng? {
        if (wkt == null) return null
        val match = Regex("""Point\(\s*([+-]?\d+(?:\.\d+)?)\s+([+-]?\d+(?:\.\d+)?)\s*\)""").find(wkt) ?: return null
        val lng = match.groupValues[1].toDoubleOrNull() ?: return null
        val lat = match.groupValues[2].toDoubleOrNull() ?: return null
        return LatLng(lat, lng)
    }
}

private fun pages(body: String): List<JsonObject>? {
    val query = jsonObject(body)?.obj("query") ?: return null
    val raw = query["pages"] ?: return emptyList()
    val array = raw as? JsonArray
    if (array != null) return array.mapNotNull { it as? JsonObject }
    val objectPages = raw as? JsonObject ?: return emptyList()
    return objectPages.values.mapNotNull { it as? JsonObject }
}

private fun coordinate(page: JsonObject): LatLng? {
    val coords = page.array("coordinates")?.firstObject() ?: return null
    val lat = coords.double("lat") ?: return null
    val lng = coords.double("lon") ?: return null
    return LatLng(lat, lng)
}

private fun jsonObject(body: String): JsonObject? =
    runCatching { XHereJson.parseToJsonElement(body).jsonObject }.getOrNull()

private fun JsonObject.text(name: String): String? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.takeIf { it.isNotBlank() }
}

private fun JsonObject.int(name: String): Int? {
    val primitive = this[name]?.jsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
}

private fun JsonObject.raw(name: String): String? {
    val primitive = this[name]?.jsonPrimitive ?: return null
    return primitive.contentOrNull?.takeIf { it.isNotBlank() }
}

private fun JsonObject.double(name: String): Double? {
    val primitive = this[name]?.jsonPrimitive ?: return null
    return primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull()
}

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

private fun JsonArray.firstObject(): JsonObject? = firstOrNull() as? JsonObject

private fun JsonObject.binding(name: String): String? = obj(name)?.text("value")

private fun JsonObject.metaValue(name: String): String? = obj(name)?.text("value")

private fun plain(value: String): String = value
    .replace(Regex("<[^>]+>"), " ")
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace(Regex("\\s+"), " ")
    .trim()
