package world.xhere.shared.demo

import kotlin.uuid.Uuid
import world.xhere.shared.data.fake.MemoryStore
import world.xhere.shared.data.fake.StoredWalk
import world.xhere.shared.data.fake.StoredWalkStop
import world.xhere.shared.geo.LatLng
import world.xhere.shared.walks.WalkGuide

/**
 * The offline copy of the Jamestown pilot walk.
 *
 * The stops are the public-record places in `supabase/seed/open-data/jamestown-ny.json`,
 * in the same order as `supabase/seed/walks/jamestown-ny.sql` and
 * `supabase/regions/us/jamestown-ny.yaml`. Summaries are the snapshot sentences.
 * This does not add a historical claim, and it does not add map pins: the
 * downtown cluster stays the demo drops that were already there.
 *
 * Reveal is 150 m, the `historical_fact` default. Two published points closer
 * than that are both "there" at once. That is the same rule as a reveal.
 */
object JamestownHistoryWalk {
    val id: Uuid = Uuid.parse("b0000000-0000-4000-8000-000000000008")
    const val REGION_ID: String = "us-ny-jamestown"
    const val SLUG: String = "downtown-history"
    const val TITLE: String = "Downtown Jamestown history walk"
    const val DESCRIPTION: String =
        "A walk past public records in downtown Jamestown, New York. Each stop is a place already on the map. " +
            "National Register stops are a building or a district in Jamestown, New York. " +
            "Wikipedia stops are articles about the named place. " +
            "The distance is the straight line between those published points."

    /** West, south, east, north. The region box in the Jamestown YAML. */
    const val MIN_LNG: Double = -79.32
    const val MIN_LAT: Double = 42.05
    const val MAX_LNG: Double = -79.18
    const val MAX_LAT: Double = 42.14

    private const val NRHP_BUILDING: String =
        "Building on the National Register of Historic Places (Jamestown, New York). Shown where the National Park Service published it."
    private const val NRHP_DISTRICT: String =
        "District on the National Register of Historic Places (Jamestown, New York). Shown where the National Park Service published it."
    private const val WIKIPEDIA: String =
        "Wikipedia article about this place. Open the source for what is written there."

    val stops: List<StoredWalkStop> = listOf(
        stop(1, "Erie Railroad Station", NRHP_BUILDING, 42.094509, -79.244603),
        stop(2, "National Comedy Center", WIKIPEDIA, 42.094722, -79.243889),
        stop(3, "The Wellman Building", WIKIPEDIA, 42.095922, -79.242619),
        stop(4, "Lucille Ball Desi Arnaz Museum & Center for Comedy", WIKIPEDIA, 42.09651, -79.24161),
        stop(5, "Reg Lenna Center for the Arts", WIKIPEDIA, 42.096689, -79.239267),
        stop(6, "Jamestown Downtown Historic District", NRHP_DISTRICT, 42.096357, -79.237379),
        stop(7, "Robert H. Jackson Center", WIKIPEDIA, 42.0983, -79.23764),
        stop(8, "First Presbyterian Church, Jamestown, New York", NRHP_BUILDING, 42.098818, -79.238704),
    )

    val distanceM: Int = WalkGuide.straightLineMeters(stops.map { LatLng(it.latitude, it.longitude) })
    val durationMin: Int = WalkGuide.durationMinutes(distanceM)

    fun install(store: MemoryStore) {
        if (store.walks.any { it.id == id }) return
        store.walks += StoredWalk(
            id = id,
            regionId = REGION_ID,
            slug = SLUG,
            title = TITLE,
            description = DESCRIPTION,
            distanceM = distanceM,
            durationMin = durationMin,
            minLng = MIN_LNG,
            minLat = MIN_LAT,
            maxLng = MAX_LNG,
            maxLat = MAX_LAT,
            stops = stops,
        )
    }

    private fun stop(position: Int, title: String, summary: String, latitude: Double, longitude: Double) =
        StoredWalkStop(
            position = position,
            title = title,
            summary = summary,
            latitude = latitude,
            longitude = longitude,
            revealRadiusM = 150,
        )
}
