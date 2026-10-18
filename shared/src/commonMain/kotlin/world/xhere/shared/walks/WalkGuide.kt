package world.xhere.shared.walks

import kotlin.math.ceil
import kotlin.math.roundToInt
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.Walk
import world.xhere.shared.model.WalkStop

/**
 * What the map says next. The server decides a stop is reached. This only
 * picks the next one and the straight line, using the same "there" test as
 * reveal: distance to the display point is within [WalkStop.revealRadiusM].
 */
object WalkGuide {
    /** Comfortable walk, used when a region does not store a time of its own. */
    const val METERS_PER_MINUTE: Double = 75.0

    fun ordered(walk: Walk): List<WalkStop> =
        walk.stops.filter { it.available }.sortedBy { it.position }

    fun nextStop(walk: Walk): WalkStop? = ordered(walk).firstOrNull { !it.reached }

    fun route(walk: Walk): List<LatLng> = ordered(walk).map { it.location }

    /** Meters still to the next stop, or null when the walk is finished or has not started. */
    fun distanceToNext(from: LatLng, walk: Walk): Double? {
        val next = nextStop(walk) ?: return null
        return Geo.distanceMeters(from, next.location)
    }

    /**
     * True when [distanceM] is inside the reveal radius. Same predicate as
     * [Geo.isRevealed] for someone else's drop.
     */
    fun isThere(distanceM: Double, revealRadiusM: Int): Boolean =
        Geo.isRevealed(distanceM, revealRadiusM, isMine = false)

    fun straightLineMeters(points: List<LatLng>): Int {
        if (points.size < 2) return 0
        val meters = points.zipWithNext().sumOf { (from, to) -> Geo.distanceMeters(from, to) }
        return meters.roundToInt()
    }

    fun durationMinutes(distanceM: Int): Int =
        ceil(distanceM / METERS_PER_MINUTE).toInt().coerceAtLeast(1)
}
