package world.xhere.app.ar

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow
import world.xhere.shared.ar.ArPose

/**
 * The phone's Geospatial session. Unit tests bind a fake. ARCore itself
 * does not run in CI (PLAN §3 AR view, research §4.1).
 *
 * [refreshSupport] is the map-button check. It must not open the camera
 * and must not ask the person to install Play Services for AR.
 */
interface ArRuntime {
    val support: StateFlow<ArSupport>
    val frames: StateFlow<ArFrame>

    /**
     * Asks whether Geospatial and VPS cover this spot.
     * Returns false when a check is already running, so the caller keeps the
     * button state it already has. Returns true after [support] was updated.
     */
    suspend fun refreshSupport(latitude: Double, longitude: Double): Boolean

    fun setTargets(targets: List<ArTarget>)

    fun start()

    fun stop()

    @Composable
    fun Camera()
}

enum class ArSupport {
    /** No ARCore, or Geospatial is not a mode this phone can run. Hide the button. */
    UNSUPPORTED,

    /** The phone can run it, but this spot has no VPS coverage. Hide the button. */
    UNAVAILABLE,

    /** ARCore is installed, Geospatial is supported, and VPS covers this spot. */
    READY,
}

enum class ArStatus {
    WAITING,
    TRACKING,
    COARSE_LOCATION,
    NOT_AUTHORIZED,
    UNAVAILABLE,
    FAILED,
}

enum class AnchorKind {
    TERRAIN,
    ROOFTOP,
    EYE_LEVEL,
}

data class ArTarget(
    val shortId: String,
    val latitude: Double,
    val longitude: Double,
)

/**
 * One resolved world position. [screenX] and [screenY] are pixels from the
 * top left when the runtime has already projected the anchor. Null means
 * the caller should fall back to the tested east-north-up projection.
 */
data class ResolvedAnchor(
    val shortId: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double,
    val kind: AnchorKind,
    val screenX: Float? = null,
    val screenY: Float? = null,
)

data class ArFrame(
    val pose: ArPose?,
    val status: ArStatus,
    val anchors: List<ResolvedAnchor> = emptyList(),
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val fovYDegrees: Double = 60.0,
)
