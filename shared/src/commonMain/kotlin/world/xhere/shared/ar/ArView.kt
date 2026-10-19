package world.xhere.shared.ar

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan
import kotlin.time.Instant
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.integrity.LocationAction
import world.xhere.shared.integrity.LocationIntegrity
import world.xhere.shared.integrity.LocationSignal
import world.xhere.shared.model.DropCard
import world.xhere.shared.model.DropSource
import world.xhere.shared.model.DropStatus

/**
 * AR labels for the Geospatial camera (research §3 item 6 and the AR row in §4.1;
 * PLAN §3 "AR view").
 *
 * Ranking, privacy, and the projection live here so unit tests can run them
 * without ARCore. The phone session only supplies a pose and, when it can,
 * a terrain or rooftop altitude.
 *
 * A locked body never leaves [visibleBody]. A user drop that the reveal radius
 * would open still stays a title when [LocationIntegrity] would withhold that
 * reveal (coarse accuracy, a mock fix, a stale fix, clock skew). Public records
 * stay readable. The display coordinate is the only position used.
 */
object ArView {
    const val MAX_LABELS: Int = 25
    const val RANGE_M: Int = 500
    const val NEAR_M: Double = 12.0
    const val FAR_M: Double = 400.0
    const val BODY_CHARS: Int = 80
    const val REFRESH_M: Double = 25.0

    /** Closer than this (in front of the camera) is too near to pin a label on. */
    const val MIN_FORWARD_M: Double = 0.5

    fun poseUsable(pose: ArPose): Boolean {
        if (!Geo.isValid(LatLng(pose.latitude, pose.longitude))) return false
        if (!pose.altitudeM.isFinite() || !pose.headingDegrees.isFinite()) return false
        if (!pose.pitchDegrees.isFinite() || !pose.rollDegrees.isFinite()) return false
        val accuracy = pose.horizontalAccuracyM
        return accuracy.isFinite() && accuracy >= 0.0
    }

    /** Composer chip. The coordinate is appended by [world.xhere.shared.here.Here.pinLabel]. */
    fun accuracyPhrase(horizontalAccuracyM: Double): String {
        val meters = horizontalAccuracyM.roundToInt().coerceAtLeast(0)
        return "AR · ±$meters m"
    }

    /**
     * The composer signal for a Geospatial pose. This is a device fix, not a
     * map pin: [LocationIntegrity] then applies the same accuracy, mock, and
     * clock rules as a GPS post. Null when the pose itself is not usable.
     */
    fun composerSignal(
        pose: ArPose,
        now: Instant,
        developerOptions: Boolean = false,
        mock: Boolean = false,
    ): LocationSignal? {
        if (!poseUsable(pose)) return null
        return LocationSignal(
            mock = mock,
            developerOptions = developerOptions,
            deviceFix = true,
            accuracyM = pose.horizontalAccuracyM,
            fixAt = now,
            deviceAt = now,
        )
    }

    /**
     * Body text safe to float in the camera. Null hides the body.
     * Public records and the author's own drop are not a reveal of someone else.
     */
    fun visibleBody(card: DropCard, signal: LocationSignal?, viewer: LatLng, now: Instant): String? {
        if (card.locationWithheld || !card.revealed) return null
        val body = card.body?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (card.source == DropSource.OPEN_DATA || card.isMine) return body.take(BODY_CHARS)
        if (signal == null || !Geo.isValid(viewer)) return null
        val decision = LocationIntegrity.decide(LocationAction.REVEAL, viewer, signal, previous = null, now)
        if (!decision.countsForCredit) return null
        return body.take(BODY_CHARS)
    }

    /**
     * Nearest [MAX_LABELS] inside [RANGE_M], sized and faded by distance.
     * [colors] is category slug to a hex color; a missing slug stays null.
     */
    fun select(
        cards: List<DropCard>,
        viewer: LatLng,
        signal: LocationSignal?,
        now: Instant,
        colors: Map<String, String?> = emptyMap(),
    ): List<ArLabel> {
        if (!Geo.isValid(viewer)) return emptyList()
        return cards
            .asSequence()
            .filter { it.status != DropStatus.REMOVED && it.status != DropStatus.EXPIRED }
            .filter { Geo.isValid(it.displayLocation) }
            .map { card -> card to Geo.distanceMeters(viewer, card.displayLocation) }
            .filter { (_, distance) -> distance.isFinite() && distance <= RANGE_M }
            .sortedWith(compareBy({ it.second }, { it.first.shortId }))
            .take(MAX_LABELS)
            .map { (card, distance) ->
                val (scale, alpha) = appearance(distance)
                val body = visibleBody(card, signal, viewer, now)
                val hadBody = !card.body.isNullOrBlank()
                val withheld = card.revealed &&
                    !card.isMine &&
                    card.source != DropSource.OPEN_DATA &&
                    hadBody &&
                    body == null
                ArLabel(
                    shortId = card.shortId,
                    title = card.title,
                    categorySlug = card.categorySlug,
                    colorHex = colors[card.categorySlug],
                    distanceM = distance,
                    scale = scale,
                    alpha = alpha,
                    body = body,
                    revealed = card.revealed && body != null,
                    publicRecord = card.source == DropSource.OPEN_DATA,
                    withheld = withheld,
                    latitude = card.latitude,
                    longitude = card.longitude,
                )
            }
            .toList()
    }

    /** 1 at [NEAR_M] and closer, fading to a smaller, dimmer label by [FAR_M]. */
    fun appearance(distanceM: Double): Pair<Float, Float> {
        val t = ((distanceM - NEAR_M) / (FAR_M - NEAR_M)).coerceIn(0.0, 1.0)
        val scale = (1.0 - 0.5 * t).toFloat()
        val alpha = (1.0 - 0.62 * t).toFloat()
        return scale to alpha
    }

    fun enu(fromLat: Double, fromLng: Double, fromAlt: Double, toLat: Double, toLng: Double, toAlt: Double): Enu {
        val lat = fromLat.toRadians()
        val north = (toLat - fromLat).toRadians() * Geo.EARTH_RADIUS_M
        val east = wrapDegrees(toLng - fromLng).toRadians() * Geo.EARTH_RADIUS_M * cos(lat)
        return Enu(east = east, north = north, up = toAlt - fromAlt)
    }

    /**
     * Camera axes: +right, +up, +forward. Heading is degrees clockwise from
     * north. Positive pitch looks up, so a point on the horizon moves down.
     */
    fun cameraSpace(pose: ArPose, latitude: Double, longitude: Double, altitudeM: Double): CameraSpace {
        val offset = enu(pose.latitude, pose.longitude, pose.altitudeM, latitude, longitude, altitudeM)
        val yaw = pose.headingDegrees.toRadians()
        val forwardEast = sin(yaw)
        val forwardNorth = cos(yaw)
        val rightEast = cos(yaw)
        val rightNorth = -sin(yaw)
        var forward = offset.east * forwardEast + offset.north * forwardNorth
        var right = offset.east * rightEast + offset.north * rightNorth
        var up = offset.up
        val pitch = pose.pitchDegrees.toRadians()
        val pitchedForward = forward * cos(pitch) + up * sin(pitch)
        val pitchedUp = up * cos(pitch) - forward * sin(pitch)
        forward = pitchedForward
        up = pitchedUp
        val roll = pose.rollDegrees.toRadians()
        val rolledRight = right * cos(roll) + up * sin(roll)
        val rolledUp = up * cos(roll) - right * sin(roll)
        return CameraSpace(right = rolledRight, up = rolledUp, forward = forward)
    }

    /**
     * Pixel position, origin top-left. Null when the point is behind the camera
     * or outside a small margin of the view.
     */
    fun project(
        pose: ArPose,
        latitude: Double,
        longitude: Double,
        altitudeM: Double,
        widthPx: Double,
        heightPx: Double,
        fovYDegrees: Double,
    ): ScreenPoint? {
        if (widthPx <= 0.0 || heightPx <= 0.0 || !poseUsable(pose)) return null
        if (!latitude.isFinite() || !longitude.isFinite() || !altitudeM.isFinite()) return null
        val space = cameraSpace(pose, latitude, longitude, altitudeM)
        if (space.forward <= MIN_FORWARD_M) return null
        val fov = fovYDegrees.coerceIn(10.0, 120.0).toRadians()
        val tanY = tan(fov / 2.0)
        val tanX = tanY * (widthPx / heightPx)
        val ndcX = (space.right / space.forward) / tanX
        val ndcY = (space.up / space.forward) / tanY
        if (ndcX !in -1.15..1.15 || ndcY !in -1.15..1.15) return null
        return ScreenPoint(
            x = ((ndcX + 1.0) / 2.0) * widthPx,
            y = ((1.0 - ndcY) / 2.0) * heightPx,
        )
    }

    /**
     * Column-major view and projection matrices, the kind ARCore writes.
     * A point with clip w <= 0 is behind the camera.
     */
    fun projectView(
        view: FloatArray,
        projection: FloatArray,
        x: Float,
        y: Float,
        z: Float,
        widthPx: Float,
        heightPx: Float,
    ): ScreenPoint? {
        if (view.size < 16 || projection.size < 16 || widthPx <= 0f || heightPx <= 0f) return null
        val eye = multiplyPoint(view, x, y, z)
        val clip = multiplyPoint(projection, eye[0], eye[1], eye[2])
        val w = clip[3]
        if (w <= 1e-4f) return null
        val ndcX = clip[0] / w
        val ndcY = clip[1] / w
        if (ndcX !in -1.15f..1.15f || ndcY !in -1.15f..1.15f) return null
        return ScreenPoint(
            x = ((ndcX + 1f) / 2f) * widthPx.toDouble(),
            y = ((1f - ndcY) / 2f) * heightPx.toDouble(),
        )
    }

    private fun multiplyPoint(matrix: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val out = FloatArray(4)
        for (row in 0..3) {
            out[row] = matrix[row] * x + matrix[4 + row] * y + matrix[8 + row] * z + matrix[12 + row]
        }
        return out
    }

    private fun wrapDegrees(delta: Double): Double {
        var wrapped = delta % 360.0
        if (wrapped > 180.0) wrapped -= 360.0
        if (wrapped < -180.0) wrapped += 360.0
        return wrapped
    }

    private fun Double.toRadians(): Double = this * PI / 180.0
}

data class ArPose(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double,
    val headingDegrees: Double,
    val horizontalAccuracyM: Double,
    val verticalAccuracyM: Double = 0.0,
    val headingAccuracyDegrees: Double = 0.0,
    val pitchDegrees: Double = 0.0,
    val rollDegrees: Double = 0.0,
)

data class Enu(val east: Double, val north: Double, val up: Double)

data class CameraSpace(val right: Double, val up: Double, val forward: Double)

data class ScreenPoint(val x: Double, val y: Double)

data class ArLabel(
    val shortId: String,
    val title: String,
    val categorySlug: String,
    val colorHex: String?,
    val distanceM: Double,
    val scale: Float,
    val alpha: Float,
    val body: String?,
    val revealed: Boolean,
    val publicRecord: Boolean,
    val withheld: Boolean,
    val latitude: Double,
    val longitude: Double,
)
