package world.xhere.app.ar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Earth
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.VpsAvailability
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.asin
import kotlin.math.atan2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import world.xhere.shared.ar.ArPose
import world.xhere.shared.ar.ArView

/**
 * Keyless ARCore Geospatial session. Authorization is the app's package
 * name plus its signing certificate in Cloud project `xhere-512103`.
 * There is no API key in the manifest.
 *
 * The map calls [refreshSupport] only. That creates a session, asks whether
 * Geospatial and VPS are available, and closes it. It does not call
 * `requestInstall` and it does not open the camera. [start] is the camera
 * session, and only after the in-app Google notice.
 */
class ArCoreRuntime(
    private val context: Context,
) : ArRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()
    private val _support = MutableStateFlow(ArSupport.UNSUPPORTED)
    private val _frames = MutableStateFlow(ArFrame(pose = null, status = ArStatus.WAITING))
    override val support: StateFlow<ArSupport> = _support.asStateFlow()
    override val frames: StateFlow<ArFrame> = _frames.asStateFlow()

    private val background = CameraBackground()
    private val held = LinkedHashMap<String, Held>()

    @Volatile private var session: Session? = null
    @Volatile private var wantsRunning = false
    @Volatile private var resumed = false
    @Volatile private var textureId = 0
    @Volatile private var viewWidth = 1
    @Volatile private var viewHeight = 1
    @Volatile private var targets: List<ArTarget> = emptyList()
    @Volatile private var glView: GLSurfaceView? = null
    private var lastPublishMs = 0L

    override suspend fun refreshSupport(latitude: Double, longitude: Double): Boolean {
        return withContext(Dispatchers.Default) {
            if (!gate.tryLock()) return@withContext false
            try {
                if (wantsRunning || session != null) return@withContext false
                if (!installed()) {
                    _support.value = ArSupport.UNSUPPORTED
                    return@withContext true
                }
                val probe = openProbe() ?: run {
                    _support.value = ArSupport.UNSUPPORTED
                    return@withContext true
                }
                try {
                    if (!probe.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
                        _support.value = ArSupport.UNSUPPORTED
                        return@withContext true
                    }
                    configure(probe)
                    val availability = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                        suspendCancellableCoroutine { cont ->
                            val future = probe.checkVpsAvailabilityAsync(latitude, longitude) { result ->
                                if (cont.isActive) cont.resume(result)
                            }
                            cont.invokeOnCancellation { runCatching { future.cancel() } }
                        }
                    }
                    _support.value = if (availability == VpsAvailability.AVAILABLE) {
                        ArSupport.READY
                    } else {
                        ArSupport.UNAVAILABLE
                    }
                    true
                } catch (_: Throwable) {
                    _support.value = ArSupport.UNSUPPORTED
                    true
                } finally {
                    runCatching { probe.close() }
                }
            } finally {
                gate.unlock()
            }
        }
    }

    override fun setTargets(targets: List<ArTarget>) {
        this.targets = targets.take(ArView.MAX_LABELS)
    }

    override fun start() {
        if (!hasFineLocation()) {
            _frames.value = ArFrame(pose = null, status = ArStatus.COARSE_LOCATION)
            return
        }
        wantsRunning = true
        scope.launch {
            gate.withLock {
                if (!wantsRunning || session != null) return@withLock
                session = openLive()
            }
            onGl { resumeOnGl() }
        }
    }

    override fun stop() {
        wantsRunning = false
        onGl {
            val current = session
            session = null
            resumed = false
            held.values.forEach { item -> runCatching { item.anchor?.detach() } }
            held.clear()
            runCatching { current?.pause() }
            runCatching { current?.close() }
        }
        _frames.value = ArFrame(pose = null, status = ArStatus.WAITING)
    }

    @Composable
    override fun Camera() {
        val view = remember {
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(2)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                setRenderer(ArRenderer())
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }.also { glView = it }
        }
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
    }

    private fun openProbe(): Session? = try {
        Session(context)
    } catch (_: UnavailableException) {
        null
    } catch (_: Throwable) {
        null
    }

    private fun openLive(): Session? {
        val created = try {
            Session(context)
        } catch (_: UnavailableException) {
            publish(ArFrame(pose = null, status = ArStatus.UNAVAILABLE))
            null
        } catch (_: Throwable) {
            publish(ArFrame(pose = null, status = ArStatus.FAILED))
            null
        } ?: return null
        return try {
            if (!created.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
                created.close()
                publish(ArFrame(pose = null, status = ArStatus.UNAVAILABLE))
                return null
            }
            configure(created)
            created
        } catch (_: Throwable) {
            runCatching { created.close() }
            publish(ArFrame(pose = null, status = ArStatus.FAILED))
            null
        }
    }

    private fun configure(target: Session) {
        val config = Config(target)
        config.setGeospatialMode(Config.GeospatialMode.ENABLED)
        config.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED)
        config.setDepthMode(Config.DepthMode.DISABLED)
        config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE)
        config.setLightEstimationMode(Config.LightEstimationMode.DISABLED)
        config.setFocusMode(Config.FocusMode.AUTO)
        target.configure(config)
    }

    private suspend fun installed(): Boolean {
        repeat(8) {
            val availability = ArCoreApk.getInstance().checkAvailability(context)
            if (availability.isTransient) {
                delay(200)
            } else {
                return availability == ArCoreApk.Availability.SUPPORTED_INSTALLED
            }
        }
        return false
    }

    private fun hasFineLocation(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun resumeOnGl() {
        val current = session ?: return
        if (!wantsRunning || textureId == 0 || resumed) return
        try {
            current.setCameraTextureName(textureId)
            current.setDisplayGeometry(rotation(), viewWidth, viewHeight)
            current.resume()
            resumed = true
        } catch (_: CameraNotAvailableException) {
            publish(ArFrame(pose = null, status = ArStatus.FAILED))
        } catch (_: UnavailableException) {
            publish(ArFrame(pose = null, status = ArStatus.UNAVAILABLE))
        } catch (_: Throwable) {
            publish(ArFrame(pose = null, status = ArStatus.FAILED))
        }
    }

    private fun onFrame(current: Session, frame: com.google.ar.core.Frame) {
        val earth = runCatching { current.earth }.getOrNull()
        if (earth == null) {
            publish(ArFrame(pose = null, status = ArStatus.UNAVAILABLE))
            return
        }
        val status = earthStatus(earth)
        if (status != ArStatus.TRACKING && status != ArStatus.WAITING) {
            publish(ArFrame(pose = null, status = status))
            return
        }
        val tracking = frame.camera.trackingState == TrackingState.TRACKING &&
            earth.trackingState == TrackingState.TRACKING &&
            earth.earthState == Earth.EarthState.ENABLED
        if (!tracking) {
            publish(ArFrame(pose = null, status = ArStatus.WAITING, widthPx = viewWidth, heightPx = viewHeight))
            return
        }
        val geo = earth.cameraGeospatialPose
        val (pitch, roll) = pitchAndRoll(geo.eastUpSouthQuaternion)
        val pose = ArPose(
            latitude = geo.latitude,
            longitude = geo.longitude,
            altitudeM = geo.altitude,
            headingDegrees = geo.heading,
            horizontalAccuracyM = geo.horizontalAccuracy,
            verticalAccuracyM = geo.verticalAccuracy,
            headingAccuracyDegrees = geo.headingAccuracy,
            pitchDegrees = pitch,
            rollDegrees = roll,
        )
        if (!ArView.poseUsable(pose)) {
            publish(ArFrame(pose = null, status = ArStatus.WAITING, widthPx = viewWidth, heightPx = viewHeight))
            return
        }
        syncTargets()
        resolveSome(earth, pose.altitudeM)
        val view = FloatArray(16)
        val projection = FloatArray(16)
        frame.camera.getViewMatrix(view, 0)
        frame.camera.getProjectionMatrix(projection, 0, 0.1f, 2_000f)
        val anchors = projectAnchors(earth, view, projection)
        publish(
            ArFrame(
                pose = pose,
                status = ArStatus.TRACKING,
                anchors = anchors,
                widthPx = viewWidth,
                heightPx = viewHeight,
                fovYDegrees = fovY(projection),
            ),
        )
    }

    private fun earthStatus(earth: Earth): ArStatus = when (earth.earthState) {
        Earth.EarthState.ENABLED -> ArStatus.TRACKING
        Earth.EarthState.ERROR_NOT_AUTHORIZED -> ArStatus.NOT_AUTHORIZED
        Earth.EarthState.ERROR_GEOSPATIAL_MODE_DISABLED -> ArStatus.UNAVAILABLE
        else -> ArStatus.FAILED
    }

    private fun syncTargets() {
        val wanted = targets.associateBy { it.shortId }
        val gone = held.keys.filter { it !in wanted }
        gone.forEach { id ->
            runCatching { held.remove(id)?.anchor?.detach() }
        }
        wanted.forEach { (id, target) ->
            if (id !in held) held[id] = Held(target)
        }
    }

    private fun resolveSome(earth: Earth, cameraAltitude: Double) {
        var started = 0
        for (item in held.values) {
            if (started >= RESOLVES_PER_FRAME) return
            if (item.waiting || item.phase == Phase.READY || item.phase == Phase.FAILED) continue
            when (item.phase) {
                Phase.TERRAIN -> {
                    if (abs(item.target.latitude) > 89.0) {
                        item.phase = Phase.ROOFTOP
                    } else {
                        item.waiting = true
                        started++
                        val id = item.target.shortId
                        earth.resolveAnchorOnTerrainAsync(
                            item.target.latitude,
                            item.target.longitude,
                            TERRAIN_ABOVE_M,
                            0f, 0f, 0f, 1f,
                        ) { anchor, state ->
                            onGl { applyTerrain(id, anchor, state) }
                        }
                    }
                }
                Phase.ROOFTOP -> {
                    item.waiting = true
                    started++
                    val id = item.target.shortId
                    earth.resolveAnchorOnRooftopAsync(
                        item.target.latitude,
                        item.target.longitude,
                        ROOFTOP_ABOVE_M,
                        0f, 0f, 0f, 1f,
                    ) { anchor, state ->
                        onGl { applyRooftop(id, anchor, state) }
                    }
                }
                Phase.EYE -> {
                    item.phase = try {
                        item.anchor = earth.createAnchor(
                            item.target.latitude,
                            item.target.longitude,
                            cameraAltitude,
                            0f, 0f, 0f, 1f,
                        )
                        item.kind = AnchorKind.EYE_LEVEL
                        Phase.READY
                    } catch (_: Throwable) {
                        Phase.FAILED
                    }
                    started++
                }
                Phase.READY, Phase.FAILED -> Unit
            }
        }
    }

    private fun applyTerrain(id: String, anchor: Anchor?, state: Anchor.TerrainAnchorState) {
        val item = held[id]
        if (item == null) {
            runCatching { anchor?.detach() }
            return
        }
        item.waiting = false
        when {
            state == Anchor.TerrainAnchorState.SUCCESS && anchor != null -> {
                item.anchor = anchor
                item.kind = AnchorKind.TERRAIN
                item.phase = Phase.READY
            }
            state == Anchor.TerrainAnchorState.ERROR_NOT_AUTHORIZED -> {
                runCatching { anchor?.detach() }
                item.phase = Phase.FAILED
            }
            state == Anchor.TerrainAnchorState.TASK_IN_PROGRESS -> item.waiting = true
            state.isError || state == Anchor.TerrainAnchorState.NONE -> {
                runCatching { anchor?.detach() }
                item.phase = Phase.ROOFTOP
            }
        }
    }

    private fun applyRooftop(id: String, anchor: Anchor?, state: Anchor.RooftopAnchorState) {
        val item = held[id]
        if (item == null) {
            runCatching { anchor?.detach() }
            return
        }
        item.waiting = false
        when {
            state == Anchor.RooftopAnchorState.SUCCESS && anchor != null -> {
                item.anchor = anchor
                item.kind = AnchorKind.ROOFTOP
                item.phase = Phase.READY
            }
            state == Anchor.RooftopAnchorState.ERROR_NOT_AUTHORIZED -> {
                runCatching { anchor?.detach() }
                item.phase = Phase.FAILED
            }
            state.isError || state == Anchor.RooftopAnchorState.NONE -> {
                runCatching { anchor?.detach() }
                item.phase = Phase.EYE
            }
        }
    }

    private fun projectAnchors(earth: Earth, view: FloatArray, projection: FloatArray): List<ResolvedAnchor> {
        val placed = ArrayList<ResolvedAnchor>(held.size)
        for (item in held.values) {
            val anchor = item.anchor ?: continue
            if (item.phase != Phase.READY || anchor.trackingState != TrackingState.TRACKING) continue
            val geo = runCatching { earth.getGeospatialPose(anchor.pose) }.getOrNull()
            val translation = anchor.pose.translation
            val screen = ArView.projectView(
                view,
                projection,
                translation[0],
                translation[1],
                translation[2],
                viewWidth.toFloat(),
                viewHeight.toFloat(),
            )
            placed += ResolvedAnchor(
                shortId = item.target.shortId,
                latitude = geo?.latitude ?: item.target.latitude,
                longitude = geo?.longitude ?: item.target.longitude,
                altitudeM = geo?.altitude ?: Double.NaN,
                kind = item.kind,
                screenX = screen?.x?.toFloat(),
                screenY = screen?.y?.toFloat(),
            )
        }
        return placed
    }

    private fun publish(frame: ArFrame) {
        val now = android.os.SystemClock.elapsedRealtime()
        val changed = frame.status != _frames.value.status || frame.pose == null
        if (!changed && now - lastPublishMs < PUBLISH_INTERVAL_MS) return
        lastPublishMs = now
        _frames.value = frame
    }

    private fun onGl(block: () -> Unit) {
        val view = glView
        if (view == null) block() else view.queueEvent { block() }
    }

    @Suppress("DEPRECATION")
    private fun rotation(): Int {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        return manager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
    }

    private inner class ArRenderer : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            textureId = background.createTexture()
            resumeOnGl()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewWidth = width.coerceAtLeast(1)
            viewHeight = height.coerceAtLeast(1)
            GLES20.glViewport(0, 0, viewWidth, viewHeight)
            runCatching { session?.setDisplayGeometry(rotation(), viewWidth, viewHeight) }
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val current = session
            if (current == null || !resumed) return
            try {
                val frame = current.update()
                background.draw(frame, textureId)
                onFrame(current, frame)
            } catch (_: CameraNotAvailableException) {
                publish(ArFrame(pose = null, status = ArStatus.FAILED))
            } catch (_: Throwable) {
                publish(ArFrame(pose = null, status = ArStatus.FAILED))
            }
        }
    }

    private class Held(val target: ArTarget) {
        var phase: Phase = Phase.TERRAIN
        var waiting: Boolean = false
        var anchor: Anchor? = null
        var kind: AnchorKind = AnchorKind.EYE_LEVEL
    }

    private enum class Phase { TERRAIN, ROOFTOP, EYE, READY, FAILED }

    private companion object {
        const val PROBE_TIMEOUT_MS = 8_000L
        const val PUBLISH_INTERVAL_MS = 50L
        const val RESOLVES_PER_FRAME = 2
        const val TERRAIN_ABOVE_M = 1.8
        const val ROOFTOP_ABOVE_M = 1.5

        fun fovY(projection: FloatArray): Double {
            val scale = projection.getOrNull(5) ?: return 60.0
            if (scale <= 0.01f) return 60.0
            return Math.toDegrees(2.0 * atan(1.0 / scale.toDouble()))
        }

        /** East-up-south quaternion to degrees. Heading already comes from ARCore. */
        fun pitchAndRoll(quaternion: FloatArray): Pair<Double, Double> {
            if (quaternion.size < 4) return 0.0 to 0.0
            val x = quaternion[0].toDouble()
            val y = quaternion[1].toDouble()
            val z = quaternion[2].toDouble()
            val w = quaternion[3].toDouble()
            val sinPitch = (2.0 * (w * x - y * z)).coerceIn(-1.0, 1.0)
            val pitch = Math.toDegrees(asin(sinPitch))
            val roll = Math.toDegrees(atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (x * x + z * z)))
            return pitch to roll
        }
    }
}
