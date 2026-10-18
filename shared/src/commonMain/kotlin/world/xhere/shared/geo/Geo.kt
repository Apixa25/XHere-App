package world.xhere.shared.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/**
 * A WGS84 point. Latitude is degrees north, longitude degrees east, matching
 * `private.make_point(lat, lng)` (PostGIS wants longitude first; this type does not).
 */
@Serializable
data class LatLng(
    val latitude: Double,
    val longitude: Double,
)

@Serializable
data class BoundingBox(
    val minLongitude: Double,
    val minLatitude: Double,
    val maxLongitude: Double,
    val maxLatitude: Double,
)

/**
 * Client geography. Reveal and confirm distances are a preview of PostGIS
 * `ST_Distance` on `geography` (spheroid). The RPC result is authoritative.
 *
 * [fuzz] matches `private.fuzz_point` in `supabase/migrations/20261018000200_rules.sql`
 * so the composer can show the blurred pin Safety categories will actually publish.
 */
object Geo {
    const val EARTH_RADIUS_M: Double = 6_371_008.8
    const val METERS_PER_DEGREE_LAT: Double = 111_320.0

    /** `drops_in_view` returns individual teasers at this zoom and above, clusters below. */
    const val TEASER_ZOOM: Int = 15

    const val MIN_ZOOM: Int = 0
    const val MAX_ZOOM: Int = 22
    const val MIN_RADIUS_M: Int = 1
    const val MAX_RADIUS_M: Int = 50_000

    /** On-site confirmations must be within `greatest(50, reveal_radius_m)`. */
    const val MIN_CONFIRM_RANGE_M: Int = 50

    fun isValidLatitude(latitude: Double): Boolean =
        latitude.isFinite() && latitude in -90.0..90.0

    fun isValidLongitude(longitude: Double): Boolean =
        longitude.isFinite() && longitude in -180.0..180.0

    fun isValid(point: LatLng): Boolean =
        isValidLatitude(point.latitude) && isValidLongitude(point.longitude)

    /**
     * Great-circle distance in meters. Good enough for "walk closer" copy.
     * The server's `ST_Distance` on geography is the check that actually reveals a drop.
     */
    fun distanceMeters(from: LatLng, to: LatLng): Double {
        val lat1 = from.latitude.toRadians()
        val lat2 = to.latitude.toRadians()
        val dLat = (to.latitude - from.latitude).toRadians()
        val dLng = (to.longitude - from.longitude).toRadians()
        val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    fun isRevealed(distanceM: Double, revealRadiusM: Int, isMine: Boolean = false): Boolean =
        isMine || distanceM <= revealRadiusM

    fun confirmationRangeM(revealRadiusM: Int): Int = max(MIN_CONFIRM_RANGE_M, revealRadiusM)

    fun isWithinConfirmationRange(distanceM: Double, revealRadiusM: Int): Boolean =
        distanceM <= confirmationRangeM(revealRadiusM)

    /**
     * Whole meters still to walk before [distanceM] is inside [revealRadiusM].
     * Zero when the drop is already revealed. The detail screen uses this for
     * "Walk 120 m closer to reveal".
     */
    fun revealGapMeters(distanceM: Double, revealRadiusM: Int): Int {
        if (!distanceM.isFinite()) return 0
        val gap = distanceM - revealRadiusM
        if (gap <= 0.0) return 0
        return gap.roundToInt().coerceAtLeast(1)
    }

    /**
     * Snap to the center of a grid cell about [precisionM] on a side.
     * Precision `<= 0` returns [point]. Deterministic, so repeated posts cannot be
     * averaged back to the true spot. Idempotent for the same precision.
     */
    fun fuzz(point: LatLng, precisionM: Int): LatLng {
        if (precisionM <= 0) return point
        val latStep = precisionM / METERS_PER_DEGREE_LAT
        var latitude = floor(point.latitude / latStep) * latStep + latStep / 2
        latitude = min(max(latitude, -89.9999), 89.9999)

        val lngStep = precisionM / (METERS_PER_DEGREE_LAT * max(cos(latitude.toRadians()), 0.01))
        var longitude = floor(point.longitude / lngStep) * lngStep + lngStep / 2
        if (longitude > 180.0) longitude -= 360.0
        return LatLng(latitude, longitude)
    }

    /**
     * Cell size used by `drops_in_view` below zoom 15: 1/8 of a 256 px tile, in degrees.
     * `360 / 2^zoom / 8`.
     */
    fun viewCellSizeDegrees(zoom: Int): Double = 360.0 / 2.0.pow(zoom) / 8.0

    /**
     * Axis-aligned box around [center]. Approximate (equirectangular), for choosing a
     * viewport to ask `drops_in_view` for. Not used for reveal.
     */
    fun boundingBox(center: LatLng, radiusM: Double): BoundingBox {
        val latDelta = radiusM / METERS_PER_DEGREE_LAT
        val lngScale = max(cos(center.latitude.toRadians()), 0.01)
        val lngDelta = radiusM / (METERS_PER_DEGREE_LAT * lngScale)
        return BoundingBox(
            minLongitude = center.longitude - lngDelta,
            minLatitude = max(center.latitude - latDelta, -90.0),
            maxLongitude = center.longitude + lngDelta,
            maxLatitude = min(center.latitude + latDelta, 90.0),
        )
    }

    /** `drops_in_view` rejects boxes that are empty or that cross the antimeridian. */
    fun isValidBoundingBox(box: BoundingBox): Boolean =
        isValidLatitude(box.minLatitude) &&
            isValidLatitude(box.maxLatitude) &&
            isValidLongitude(box.minLongitude) &&
            isValidLongitude(box.maxLongitude) &&
            box.minLongitude < box.maxLongitude &&
            box.minLatitude < box.maxLatitude

    fun isValidRadius(radiusM: Int): Boolean = radiusM in MIN_RADIUS_M..MAX_RADIUS_M

    fun isValidZoom(zoom: Int): Boolean = zoom in MIN_ZOOM..MAX_ZOOM

    private fun Double.toRadians(): Double = this * PI / 180.0
}
