package world.xhere.shared.live

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import world.xhere.shared.geo.LatLng

/**
 * One configured region. [weatherProvider] is `nws`, `none`, or null.
 * `none` turns official alerts off. A null provider means "use coverage".
 * The box is west, south, east, north. Jamestown is only one row.
 */
data class LiveRegion(
    val id: String,
    val country: String,
    val weatherProvider: String?,
    val name: String = "",
    val west: Double = Double.NaN,
    val south: Double = Double.NaN,
    val east: Double = Double.NaN,
    val north: Double = Double.NaN,
) {
    fun contains(point: LatLng): Boolean {
        if (west.isNaN() || south.isNaN() || east.isNaN() || north.isNaN()) return false
        return point.longitude in west..east && point.latitude in south..north
    }

    val area: Double
        get() = (east - west) * (north - south)
}

/**
 * Bundled copy of `live.weather_alerts` from `supabase/regions`.
 * The importer self-test checks the JSON between the markers.
 * A newer row in `public.region_live` wins when the phone can read it.
 */
object LiveRegionCatalog {
    // LIVE_REGIONS_JSON_START
    private const val JSON =
        """[{"id":"us-ny-jamestown","country":"US","bbox":[-79.32,42.05,-79.18,42.14],"weather_provider":"nws"}]"""
    // LIVE_REGIONS_JSON_END

    private val json = Json { ignoreUnknownKeys = true }

    val regions: List<LiveRegion> by lazy { parse(JSON) }

    fun containing(point: LatLng): LiveRegion? =
        regions.filter { it.contains(point) }.minWithOrNull(compareBy({ it.area }, { it.id }))

    internal fun parse(body: String): List<LiveRegion> {
        val rows = json.decodeFromString<List<CatalogRow>>(body)
        return rows.map { row ->
            LiveRegion(
                id = row.id,
                country = row.country,
                weatherProvider = row.weatherProvider,
                west = row.bbox.getOrElse(0) { Double.NaN },
                south = row.bbox.getOrElse(1) { Double.NaN },
                east = row.bbox.getOrElse(2) { Double.NaN },
                north = row.bbox.getOrElse(3) { Double.NaN },
            )
        }
    }

    @Serializable
    private data class CatalogRow(
        val id: String,
        val country: String,
        val bbox: List<Double>,
        @SerialName("weather_provider") val weatherProvider: String? = null,
    )
}
