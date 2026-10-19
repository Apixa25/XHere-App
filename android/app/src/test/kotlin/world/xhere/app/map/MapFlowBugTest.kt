package world.xhere.app.map

import androidx.compose.runtime.Composable
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import world.xhere.app.ar.ArFrame
import world.xhere.app.ar.ArAdvice
import world.xhere.app.ar.ArRuntime
import world.xhere.app.ar.ArStatus
import world.xhere.app.ar.ArSupport
import world.xhere.app.ar.ArTarget
import world.xhere.app.location.DeviceLocation
import world.xhere.app.location.LocationReading
import world.xhere.shared.data.DropRepository
import world.xhere.shared.data.XHereServices
import world.xhere.shared.data.fake.InMemoryXHere
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.geo.BoundingBox
import world.xhere.shared.geo.LatLng
import world.xhere.shared.integrity.LocationSignal
import world.xhere.shared.model.DropCard
import world.xhere.shared.model.MapFeature

/**
 * Races and offline behavior that the full-screen tests do not drive:
 * a failed refresh must keep pins, the last teaser tap must win, and a
 * skipped AR probe must not hide a button a finished probe already earned.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapFlowBugTest {
    @Before
    fun mainThread() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMain() {
        Dispatchers.resetMain()
    }

    @Test
    fun aFailedRefreshKeepsThePinsAlreadyOnScreen() {
        val services = FlakyServices(InMemoryXHere(JamestownDemo.store(), JamestownDemo.viewer))
        val viewModel = mapViewModel(services)
        val frame = CameraFrame(JamestownDemo.downtown, JamestownDemo.STREET_ZOOM)
        viewModel.onCameraIdle(frame)
        assertTrue(viewModel.state.value.features.isNotEmpty())

        services.failView = true
        viewModel.onCameraIdle(frame.copy())
        assertEquals(MapNotice.LOAD_FAILED, viewModel.state.value.notice)
        assertTrue(viewModel.state.value.features.isNotEmpty())
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun theLaterTeaserTapWinsWhenTheFirstIsStillInFlight() {
        val services = FlakyServices(InMemoryXHere(JamestownDemo.store(), JamestownDemo.viewer))
        val gate = CompletableDeferred<Unit>()
        services.teaserGate = gate
        val viewModel = mapViewModel(services)
        viewModel.openTeaser("fenton", null)
        viewModel.openTeaser("lunch", null)
        gate.complete(Unit)
        assertEquals("lunch", viewModel.state.value.teaser?.shortId)
        assertEquals(null, viewModel.state.value.notice)
    }

    @Test
    fun aSkippedArProbeDoesNotHideAReadyButton() {
        val ar = GateAr()
        val location = PushLocation(permitted = true)
        val viewModel = mapViewModel(
            FlakyServices(InMemoryXHere(JamestownDemo.store(), JamestownDemo.viewer)),
            location,
            ar,
        )
        location.emit(LatLng(42.09636, -79.24202))
        assertTrue(viewModel.state.value.arReady)

        ar.allow = false
        location.emit(LatLng(42.10000, -79.24202))
        assertTrue(viewModel.state.value.arReady)
        assertTrue(ar.calls >= 2)
    }

    @Test
    fun theChipSelectionSurvivesANewViewModel() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = FilterPresetStore(context)
        val services = FlakyServices(InMemoryXHere(JamestownDemo.store(), JamestownDemo.viewer))
        mapViewModel(services, store = store).selectGroup("history_stories")
        val restored = mapViewModel(services, store = store)
        assertEquals(listOf("history_stories"), restored.state.value.selection.slugs)
        assertEquals("history_stories", restored.state.value.selection.activeGroup)
    }

    private fun mapViewModel(
        services: XHereServices,
        location: DeviceLocation = PushLocation(permitted = false),
        ar: ArRuntime = GateAr(),
        store: FilterPresetStore = FilterPresetStore(ApplicationProvider.getApplicationContext()),
    ) = MapViewModel(
        services = services,
        location = location,
        presetStore = store,
        places = SpotNames { null },
        ar = ar,
        debounceMs = 0,
    )
}

private class FlakyServices(private val inner: InMemoryXHere) : XHereServices by inner {
    var failView: Boolean = false
    var teaserGate: CompletableDeferred<Unit>? = null

    override val drops: DropRepository = object : DropRepository by inner.drops {
        override suspend fun dropsInView(
            box: BoundingBox,
            categories: List<String>?,
            zoom: Int,
        ): List<MapFeature> {
            if (failView) error("offline")
            return inner.drops.dropsInView(box, categories, zoom)
        }

        override suspend fun dropTeaser(shortId: String): DropCard {
            teaserGate?.await()
            return inner.drops.dropTeaser(shortId)
        }
    }
}

private class PushLocation(var permitted: Boolean) : DeviceLocation {
    private val updates = MutableSharedFlow<LocationReading>(extraBufferCapacity = 8)

    override fun hasPermission(): Boolean = permitted

    override fun fixes() = updates

    fun emit(point: LatLng) {
        updates.tryEmit(LocationReading(point, LocationSignal(accuracyM = 8.0)))
    }
}

private class GateAr : ArRuntime {
    private val _support = MutableStateFlow(ArSupport.UNSUPPORTED)
    private val _frames = MutableStateFlow(ArFrame(pose = null, status = ArStatus.WAITING))
    override val support: StateFlow<ArSupport> = _support.asStateFlow()
    override val frames: StateFlow<ArFrame> = _frames.asStateFlow()
    var allow: Boolean = true
    var calls: Int = 0

    override suspend fun refreshSupport(latitude: Double, longitude: Double): Boolean {
        calls += 1
        if (!allow) return false
        _support.value = ArSupport.READY
        return true
    }

    override suspend fun advise(latitude: Double?, longitude: Double?, activity: android.app.Activity?): ArAdvice =
        if (allow) ArAdvice.OPEN else ArAdvice.STILL_CHECKING

    override fun setTargets(targets: List<ArTarget>) = Unit

    override fun start() = Unit

    override fun stop() = Unit

    @Composable
    override fun Camera() = Unit
}
