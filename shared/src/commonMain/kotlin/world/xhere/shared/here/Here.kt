package world.xhere.shared.here

import kotlin.math.abs
import kotlin.math.floor
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.DropCard
import world.xhere.shared.model.DropSource

/**
 * Here Card (research recommendation #3, PLAN §2 and §3).
 *
 * A tap is a place, not a city. [RADIUS_M] is a short walk. User-drop bodies
 * still follow the reveal radius from the viewer's own location. Place names
 * are OpenStreetMap, cached per [cell] so the app does not repeat Nominatim.
 *
 * [cell] matches Postgres `round(value::numeric, 3)` (half away from zero).
 * [formatSpot] is locale-independent, five decimal places.
 */
object Here {
    const val RADIUS_M: Int = 300
    const val MIN_RADIUS_M: Int = 25
    const val MAX_RADIUS_M: Int = 1_000
    const val LIMIT: Int = 40
    const val MAX_LIMIT: Int = 80

    /** Nominatim's published maximum is 1 request per second. This leaves a gap. */
    const val NOMINATIM_MIN_INTERVAL_MS: Long = 1_100

    const val ATTRIBUTION: String = "© OpenStreetMap contributors"
    const val LICENSE: String = "ODbL-1.0"
    const val PROVIDER: String = "nominatim"
    const val USER_AGENT: String = "XHere/0.1 (https://xhere.world; here-card)"
    const val REFERER: String = "https://xhere.world"
    const val ENDPOINT: String = "https://nominatim.openstreetmap.org/reverse"

    fun cell(value: Double): Double {
        val negative = value < 0
        val scaled = abs(value) * 1_000.0
        val nearest = floor(scaled + 0.5 + 1e-6)
        val rounded = nearest / 1_000.0
        return if (negative) -rounded else rounded
    }

    fun cellKey(point: LatLng): String = "${cell(point.latitude)}:${cell(point.longitude)}"

    fun formatSpot(point: LatLng): String =
        "${formatDegrees(point.latitude)}, ${formatDegrees(point.longitude)}"

    /**
     * One group per category. The group with the nearest drop comes first.
     * Rows inside a group stay nearest-first.
     */
    fun groups(drops: List<DropCard>): List<HereGroup> = drops
        .groupBy { it.categorySlug }
        .map { (slug, rows) ->
            HereGroup(
                categorySlug = slug,
                groupSlug = rows.first().groupSlug,
                drops = rows.sortedWith(compareBy({ it.distanceM ?: Double.MAX_VALUE }, { it.title })),
            )
        }
        .sortedWith(
            compareBy(
                { group -> group.drops.minOf { it.distanceM ?: Double.MAX_VALUE } },
                { it.categorySlug },
            ),
        )

    fun pinLabel(name: String?, point: LatLng): String {
        val coords = formatSpot(point)
        val clean = name?.trim().orEmpty()
        return if (clean.isEmpty()) coords else "$clean · $coords"
    }

    private fun formatDegrees(value: Double): String {
        val negative = value < 0
        val scaled = abs(value) * 100_000.0
        val ticks = floor(scaled + 0.5 + 1e-6).toLong()
        val whole = ticks / 100_000
        val frac = (ticks % 100_000).toString().padStart(5, '0')
        val sign = if (negative && ticks != 0L) "-" else ""
        return "$sign$whole.$frac"
    }
}

data class HereGroup(
    val categorySlug: String,
    val groupSlug: String,
    val drops: List<DropCard>,
)

/** True when this card is a public record. The body is already public. */
fun DropCard.isPublicRecord(): Boolean = source == DropSource.OPEN_DATA
