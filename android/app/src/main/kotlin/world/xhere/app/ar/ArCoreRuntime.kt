package world.xhere.app.ar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
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
import java.security.MessageDigest
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
import world.xhere.shared.geo.Geo
import world.xhere.shared.geo.LatLng

/**
 * Keyless ARCore Geospatial session. Authorization is the app's package
 * name plus its signing certificate in Cloud project `xhere-512103`.
 * There is no API key in the manifest.
 *
 * The map calls [refreshSupport] in the background. That creates a session,
 * asks whether Geospatial and VPS are available, and closes it. It does not
 * call `requestInstall` and it does not open the camera. [advise] is the
 * tap: it logs the availability result and may call `requestInstall`.
 * [start] is the camera session, and only after the in-app Google notice.
 */
class ArCoreRuntime(
    private val context: Context,
) : ArRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()
    private val _support = MutableStateFlow(ArSupport.UNKNOWN)
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
    private var installPrompted = false
    private var loggedIdentity = false

    override suspend fun refreshSupport(latitude: Double, longitude: Double): Boolean {
        return withContext(Dispatchers.Default) {
            if (!gate.tryLock()) return@withContext false
            try {
                if (wantsRunning || session != null) return@withContext false
                val advice = inspect(latitude, longitude, activity = null, install = false)
                applySupport(advice)
                advice != ArAdvice.STILL_CHECKING
            } finally {
                gate.unlock()
            }
        }
    }

    override suspend fun advise(latitude: Double?, longitude: Double?, activity: Activity?): ArAdvice {
        return withContext(Dispatchers.Default) {
            gate.withLock {
                if (wantsRunning || session != null) return@withLock ArAdvice.OPEN
                val advice = inspect(latitude, longitude, activity, install = true)
                applySupport(advice)
                advice
            }
        }
    }

    private fun applySupport(advice: ArAdvice) {
        _support.value = when (advice) {
            ArAdvice.OPEN -> ArSupport.READY
            ArAdvice.NOT_HERE -> ArSupport.UNAVAILABLE
            ArAdvice.STILL_CHECKING -> ArSupport.UNKNOWN
            else -> ArSupport.UNSUPPORTED
        }
    }

    /**
     * Settled results update [support]. [ArAdvice.STILL_CHECKING] means
     * availability was still transient, so the caller should try again.
     */
    private suspend fun inspect(
        latitude: Double?,
        longitude: Double?,
        activity: Activity?,
        install: Boolean,
    ): ArAdvice {
        logIdentity()
        when (val availability = awaitAvailability()) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> Unit
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            -> {
                Log.w(TAG, "Play Services for AR availability=$availability")
                if (!install) return ArAdvice.NEEDS_INSTALL
                maybeInstall(activity)?.let { return it }
            }
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
                Log.w(TAG, "checkAvailability=UNSUPPORTED_DEVICE_NOT_CAPABLE. Google Play Services for AR cannot run on this phone.")
                return ArAdvice.SERVICES_MISSING
            }
            else -> {
                Log.w(
                    TAG,
                    "checkAvailability=$availability transient=${availability.isTransient}. " +
                        "UNKNOWN_CHECKING and UNKNOWN_TIMED_OUT are not installed. The check will run again.",
                )
                return ArAdvice.STILL_CHECKING
            }
        }
        if (latitude == null || longitude == null || !Geo.isValid(LatLng(latitude, longitude))) {
            Log.w(TAG, "No fine fix yet, so VPS was not checked")
            return ArAdvice.WAITING_FOR_FIX
        }
        when (val probe = openProbe()) {
            is Probe.Blocked -> {
                if (probe.advice == ArAdvice.NEEDS_INSTALL && install) {
                    maybeInstall(activity)?.let { return it }
                }
                return probe.advice
            }
            is Probe.Opened -> {
                val opened = probe.session
                try {
                    if (!opened.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
                        Log.w(TAG, "isGeospatialModeSupported=false at $latitude,$longitude")
                        return ArAdvice.NOT_HERE
                    }
                    configure(opened)
                    val vps = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                        suspendCancellableCoroutine { cont ->
                            val future = opened.checkVpsAvailabilityAsync(latitude, longitude) { result ->
                                if (cont.isActive) cont.resume(result)
                            }
                            cont.invokeOnCancellation { runCatching { future.cancel() } }
                        }
                    }
                    if (vps == null) {
                        Log.w(TAG, "checkVpsAvailabilityAsync timed out after ${PROBE_TIMEOUT_MS}ms at $latitude,$longitude")
                        return ArAdvice.STILL_CHECKING
                    }
                    Log.i(TAG, "VpsAvailability=$vps at $latitude,$longitude")
                    return when (vps) {
                        VpsAvailability.AVAILABLE -> ArAdvice.OPEN
                        VpsAvailability.ERROR_NOT_AUTHORIZED -> {
                            Log.w(TAG, authFailure())
                            ArAdvice.NOT_AUTHORIZED
                        }
                        VpsAvailability.UNAVAILABLE -> ArAdvice.NOT_HERE
                        else -> {
                            Log.w(TAG, "VpsAvailability=$vps is not AVAILABLE at $latitude,$longitude")
                            ArAdvice.NOT_HERE
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "Geospatial probe failed: ${e.javaClass.simpleName}: ${e.message}")
                    return ArAdvice.SERVICES_MISSING
                } finally {
                    runCatching { opened.close() }
                }
            }
        }
    }

    /** Null means Play Services for AR is installed and the probe can continue. */
    private suspend fun maybeInstall(activity: Activity?): ArAdvice? {
        if (activity == null) {
            Log.w(TAG, "requestInstall skipped; no Activity. Play Services for AR is missing or outdated.")
            return ArAdvice.NEEDS_INSTALL
        }
        val prompted = installPrompted
        return withContext(Dispatchers.Main) {
            try {
                when (val status = ArCoreApk.getInstance().requestInstall(activity, prompted)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installPrompted = true
                        Log.i(TAG, "requestInstall returned INSTALL_REQUESTED (alreadyPrompted=$prompted)")
                        ArAdvice.NEEDS_INSTALL
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {
                        Log.i(TAG, "requestInstall returned INSTALLED")
                        null
                    }
                }
            } catch (e: com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException) {
                Log.w(TAG, "requestInstall declined by the user")
                ArAdvice.INSTALL_DECLINED
            } catch (e: UnavailableException) {
                Log.w(TAG, "requestInstall failed: ${e.javaClass.simpleName}: ${e.message}")
                ArAdvice.SERVICES_MISSING
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

    private fun openProbe(): Probe = try {
        Probe.Opened(Session(context))
    } catch (e: com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException) {
        Log.w(TAG, "Play Services for AR is not installed: ${e.message}")
        Probe.Blocked(ArAdvice.NEEDS_INSTALL)
    } catch (e: com.google.ar.core.exceptions.UnavailableApkTooOldException) {
        Log.w(TAG, "Play Services for AR is outdated: ${e.message}")
        Probe.Blocked(ArAdvice.NEEDS_INSTALL)
    } catch (e: com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException) {
        Log.w(TAG, "Play Services for AR: device not compatible: ${e.message}")
        Probe.Blocked(ArAdvice.SERVICES_MISSING)
    } catch (e: com.google.ar.core.exceptions.UnavailableSdkTooOldException) {
        Log.w(TAG, "ARCore SDK is too old for Play Services for AR: ${e.message}")
        Probe.Blocked(ArAdvice.SERVICES_MISSING)
    } catch (e: UnavailableException) {
        Log.w(TAG, "Play Services for AR unavailable: ${e.javaClass.simpleName}: ${e.message}")
        Probe.Blocked(ArAdvice.SERVICES_MISSING)
    } catch (e: SecurityException) {
        Log.w(TAG, "ARCore session needs the camera or precise location: ${e.message}")
        Probe.Blocked(ArAdvice.NEEDS_PERMISSION)
    } catch (e: Throwable) {
        Log.w(TAG, "ARCore session failed: ${e.javaClass.simpleName}: ${e.message}")
        Probe.Blocked(ArAdvice.SERVICES_MISSING)
    }

    private suspend fun awaitAvailability(): ArCoreApk.Availability {
        val started = android.os.SystemClock.elapsedRealtime()
        var latest = ArCoreApk.Availability.UNKNOWN_CHECKING
        repeat(AVAILABILITY_TRIES) { attempt ->
            latest = ArCoreApk.getInstance().checkAvailability(context)
            Log.i(TAG, "checkAvailability attempt=$attempt result=$latest transient=${latest.isTransient}")
            if (!latest.isTransient) return latest
            delay(AVAILABILITY_DELAY_MS)
        }
        Log.w(
            TAG,
            "checkAvailability never settled. last=$latest after " +
                "${android.os.SystemClock.elapsedRealtime() - started}ms. " +
                "UNKNOWN_CHECKING and UNKNOWN_TIMED_OUT were not treated as installed.",
        )
        return latest
    }

    private fun logIdentity() {
        if (loggedIdentity) return
        loggedIdentity = true
        Log.i(TAG, "AR identity package=${context.packageName} sha1=${signingSha1()} project=$CLOUD_PROJECT ($CLOUD_PROJECT_NUMBER)")
    }

    private fun authFailure(): String =
        "Geospatial keyless auth failed (VpsAvailability.ERROR_NOT_AUTHORIZED). " +
            "Package ${context.packageName}, Cloud project $CLOUD_PROJECT ($CLOUD_PROJECT_NUMBER). " +
            "No API key is in the manifest. Authorization is an Android OAuth client for this APK's SHA-1. " +
            "This build's SHA-1 is ${signingSha1()}. A mismatch with the registered debug SHA-1, " +
            "or a release or Play App Signing SHA-1 that was never added, fails here."

    private fun signingSha1(): String = runCatching {
        val packageManager = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val info = packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val cert = signatures?.firstOrNull() ?: return "unknown"
        MessageDigest.getInstance("SHA-1").digest(cert.toByteArray())
            .joinToString(":") { byte -> "%02X".format(byte) }
    }.getOrElse { error -> "unreadable (${error.javaClass.simpleName})" }

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

    private sealed class Probe {
        data class Opened(val session: Session) : Probe()
        data class Blocked(val advice: ArAdvice) : Probe()
    }

    private class Held(val target: ArTarget) {
        var phase: Phase = Phase.TERRAIN
        var waiting: Boolean = false
        var anchor: Anchor? = null
        var kind: AnchorKind = AnchorKind.EYE_LEVEL
    }

    private enum class Phase { TERRAIN, ROOFTOP, EYE, READY, FAILED }

    private companion object {
        const val TAG = "XHereAr"
        const val CLOUD_PROJECT = "xhere-512103"
        const val CLOUD_PROJECT_NUMBER = "1059257603719"
        const val AVAILABILITY_TRIES = 20
        const val AVAILABILITY_DELAY_MS = 250L
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
