package world.xhere.app.ar

import android.app.Activity
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow
import world.xhere.shared.ar.ArPose

/**
 * The phone's Geospatial session. Unit tests bind a fake. ARCore itself
 * does not run in CI (PLAN §3 AR view, research §4.1).
 *
 * [refreshSupport] is the quiet map probe. It must not open the camera
 * and must not ask the person to install Play Services for AR.
 * [advise] is the tap. It may call `ArCoreApk.requestInstall`.
 */
interface ArRuntime {
    val support: StateFlow<ArSupport>
    val frames: StateFlow<ArFrame>

    /**
     * Asks whether Geospatial and VPS cover this spot.
     * Returns false when a check is already running, or when availability is
     * still UNKNOWN_CHECKING or UNKNOWN_TIMED_OUT, so the caller does not
     * treat that spot as finished. Returns true after [support] was updated
     * with a settled result.
     */
    suspend fun refreshSupport(latitude: Double, longitude: Double): Boolean

    /**
     * The Look around tap. Does not open the camera. When Play Services for
     * AR is missing or too old and [activity] is set, calls `requestInstall`.
     */
    suspend fun advise(latitude: Double?, longitude: Double?, activity: Activity?): ArAdvice

    fun setTargets(targets: List<ArTarget>)

    fun start()

    fun stop()

    @Composable
    fun Camera()
}

enum class ArSupport {
    /** Not checked yet, or ARCore was still checking / timed out. */
    UNKNOWN,

    /** No ARCore, or Geospatial is not a mode this phone can run. */
    UNSUPPORTED,

    /** The phone can run it, but this spot has no VPS coverage. */
    UNAVAILABLE,

    /** ARCore is installed, Geospatial is supported, and VPS covers this spot. */
    READY,
}

/**
 * What tapping Look around should tell the person. [OPEN] is the only
 * value that starts the camera flow. The others stay on the map and explain.
 */
enum class ArAdvice {
    OPEN,
    NEEDS_INSTALL,
    INSTALL_DECLINED,
    NEEDS_PERMISSION,
    NOT_HERE,
    SERVICES_MISSING,
    NOT_AUTHORIZED,
    STILL_CHECKING,
    WAITING_FOR_FIX,
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
