package world.xhere.app.ar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import world.xhere.app.location.DeviceLocation
import world.xhere.shared.ar.ArLabel
import world.xhere.shared.ar.ArPose
import world.xhere.shared.ar.ArView
import world.xhere.shared.ar.ScreenPoint
import world.xhere.shared.data.XHereServices
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.integrity.LocationSignal
import world.xhere.shared.model.DropCard

/**
 * Nearby drops for the camera. The runtime is behind [ArRuntime], so this
 * can be driven with a scripted pose. Bodies go through [ArView.visibleBody].
 */
class ArViewModel(
    private val services: XHereServices,
    private val runtime: ArRuntime,
    private val location: DeviceLocation,
) : ViewModel() {
    private val _ui = MutableStateFlow(ArUiState())
    val ui: StateFlow<ArUiState> = _ui.asStateFlow()

    private var colors: Map<String, String?> = emptyMap()
    private var cards: List<DropCard> = emptyList()
    private var lastQuery: LatLng? = null
    private var loadJob: Job? = null
    private var locationJob: Job? = null
    private var deviceMock = false
    private var developerOptions = false
    private var latest = ArFrame(pose = null, status = ArStatus.WAITING)

    init {
        viewModelScope.launch {
            val taxonomy = runCatching { services.taxonomy.load() }.getOrNull()
            if (taxonomy != null) {
                val byGroup = taxonomy.groups.associate { it.id to it.color }
                colors = taxonomy.categories.associate { category ->
                    category.slug to (category.color ?: byGroup[category.groupId])
                }
                publish(latest)
            }
        }
        viewModelScope.launch {
            runtime.frames.collect { frame ->
                latest = frame
                publish(frame)
            }
        }
    }

    fun begin() {
        runtime.start()
        if (locationJob == null && location.hasPermission()) {
            locationJob = viewModelScope.launch {
                location.fixes().collect { reading ->
                    deviceMock = reading.signal.mock
                    developerOptions = reading.signal.developerOptions
                }
            }
        }
    }

    fun end() {
        runtime.stop()
    }

    /** The Geospatial pose as a device fix. Null until tracking is usable. */
    fun dropRequest(): ArDropRequest? {
        val pose = latest.pose ?: return null
        val now = Clock.System.now()
        val signal = ArView.composerSignal(pose, now, developerOptions, deviceMock) ?: return null
        return ArDropRequest(
            spot = LatLng(pose.latitude, pose.longitude),
            label = ArView.accuracyPhrase(pose.horizontalAccuracyM),
            signal = signal,
        )
    }

    private fun publish(frame: ArFrame) {
        val pose = frame.pose
        val now = Clock.System.now()
        if (pose == null || !ArView.poseUsable(pose)) {
            _ui.value = ArUiState(status = frame.status)
            return
        }
        val viewer = LatLng(pose.latitude, pose.longitude)
        val signal = ArView.composerSignal(pose, now, developerOptions, deviceMock)
        maybeLoad(viewer, signal, now)
        val labels = ArView.select(cards, viewer, signal, now, colors)
            .mapNotNull { place(it, frame, pose) }
        _ui.value = ArUiState(status = frame.status, labels = labels, canDrop = signal != null)
    }

    private fun maybeLoad(viewer: LatLng, signal: LocationSignal?, now: kotlin.time.Instant) {
        if (signal == null) return
        val previous = lastQuery
        if (previous != null && Geo.distanceMeters(previous, viewer) < ArView.REFRESH_M) return
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            val loaded = try {
                services.drops.dropsNearby(viewer, ArView.RANGE_M, null, 40)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (loaded == null) {
                // Keep the labels already placed. Retry after a pause instead of
                // clearing them or asking again on every frame.
                delay(OFFLINE_RETRY_MS)
                return@launch
            }
            lastQuery = viewer
            cards = loaded
            val selected = ArView.select(cards, viewer, signal, now, colors)
            runtime.setTargets(selected.map { ArTarget(it.shortId, it.latitude, it.longitude) })
            publish(latest)
        }
    }

    private fun place(label: ArLabel, frame: ArFrame, pose: ArPose): PlacedLabel? {
        val anchor = frame.anchors.firstOrNull { it.shortId == label.shortId }
        val screen = if (anchor?.screenX != null && anchor.screenY != null) {
            ScreenPoint(anchor.screenX.toDouble(), anchor.screenY.toDouble())
        } else {
            val altitude = anchor?.altitudeM?.takeIf { it.isFinite() } ?: pose.altitudeM
            ArView.project(
                pose,
                label.latitude,
                label.longitude,
                altitude,
                frame.widthPx.toDouble(),
                frame.heightPx.toDouble(),
                frame.fovYDegrees,
            ) ?: return null
        }
        return PlacedLabel(label, screen.x.toFloat(), screen.y.toFloat())
    }
}

data class ArUiState(
    val status: ArStatus = ArStatus.WAITING,
    val labels: List<PlacedLabel> = emptyList(),
    val canDrop: Boolean = false,
)

data class PlacedLabel(
    val label: ArLabel,
    val x: Float,
    val y: Float,
)

private const val OFFLINE_RETRY_MS = 8_000L

data class ArDropRequest(
    val spot: LatLng,
    val label: String,
    val signal: LocationSignal,
)
