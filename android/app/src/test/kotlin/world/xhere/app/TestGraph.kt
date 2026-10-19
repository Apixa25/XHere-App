package world.xhere.app

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlin.time.Instant
import kotlin.uuid.Uuid
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import world.xhere.app.auth.AuthStrings
import world.xhere.app.auth.AuthViewModel
import world.xhere.app.auth.GoogleIdTokenSource
import world.xhere.app.auth.GoogleSignInAttempt
import world.xhere.app.config.ClientConfig
import world.xhere.app.detail.DropDetailViewModel
import world.xhere.app.detail.DropSharer
import world.xhere.app.detail.IncomingLinks
import world.xhere.app.drop.CapturedPhoto
import world.xhere.app.drop.DropCapture
import world.xhere.app.drop.DropViewModel
import world.xhere.app.drop.PendingDropSpot
import world.xhere.app.drop.ImageLabeler
import world.xhere.app.ar.ArAdvice
import world.xhere.app.ar.ArFrame
import world.xhere.app.ar.ArRuntime
import world.xhere.app.ar.ArStatus
import world.xhere.app.ar.ArSupport
import world.xhere.app.ar.ArTarget
import world.xhere.app.ar.ArViewModel
import world.xhere.app.ar.ResolvedAnchor
import world.xhere.app.drop.LabelSnapSuggester
import world.xhere.app.drop.SnapSuggester
import world.xhere.app.location.DeviceLocation
import world.xhere.app.explore.DataStoreExperienceMemory
import world.xhere.app.explore.ExperienceFocus
import world.xhere.app.explore.ExperienceMemory
import world.xhere.app.explore.ExploreViewModel
import world.xhere.app.map.FilterPresetStore
import world.xhere.app.map.MapAnchor
import world.xhere.app.map.SheetSignals
import world.xhere.app.map.MapCanvas
import world.xhere.app.map.MapViewModel
import world.xhere.app.map.PreviewMapCanvas
import world.xhere.app.map.SpotNames
import world.xhere.app.onboarding.OnboardingStore
import world.xhere.app.profile.ProfileViewModel
import world.xhere.app.safety.ModerationViewModel
import world.xhere.app.safety.SafetyListsViewModel
import world.xhere.app.session.SessionViewModel
import world.xhere.app.ui.TestTags
import world.xhere.app.session.SignOutNotice
import world.xhere.app.settings.DataExporter
import world.xhere.app.settings.SettingsViewModel
import world.xhere.app.voice.FakeVoiceRadio
import world.xhere.app.voice.HereSpeaker
import world.xhere.app.voice.RecordedClip
import world.xhere.app.voice.RulesVoiceRefiner
import world.xhere.app.voice.SpeechCapture
import world.xhere.app.voice.ThingsViewModel
import world.xhere.app.voice.VoiceClipRecorder
import world.xhere.app.voice.VoicePreferences
import world.xhere.app.voice.VoicePrefs
import world.xhere.app.voice.VoiceRadio
import world.xhere.app.voice.VoiceRefiner
import world.xhere.app.voice.VoiceSession
import world.xhere.app.voice.VoiceViewModel
import world.xhere.shared.SupabaseConfig
import world.xhere.shared.data.XHereServices
import world.xhere.shared.data.fake.InMemoryXHere
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.here.Here
import world.xhere.shared.live.LiveConditions
import world.xhere.shared.live.WeatherAlert
import world.xhere.shared.model.PlaceLabel
import world.xhere.shared.model.Profile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import world.xhere.app.location.LocationReading
import world.xhere.shared.ar.ArPose
import world.xhere.shared.integrity.LocationSignal
import kotlinx.coroutines.launch

class FakeGoogleIdTokenSource : GoogleIdTokenSource {
    var next: GoogleSignInAttempt = GoogleSignInAttempt.Success("test-id-token", "test-nonce")
    override suspend fun request(): GoogleSignInAttempt = next
}

object TestGraph {
    val profile: Profile = Profile(
        id = Uuid.parse("00000000-0000-0000-0000-0000000000aa"),
        handle = "steven",
        displayName = "Steven",
        createdAt = Instant.parse("2026-10-18T05:00:00Z"),
    )
    lateinit var services: InMemoryXHere
    lateinit var google: FakeGoogleIdTokenSource
    lateinit var location: ScriptedDeviceLocation
    lateinit var speech: ScriptedSpeech
    lateinit var speaker: ScriptedSpeaker
    lateinit var sharer: RecordingDropSharer
    lateinit var exporter: RecordingDataExporter
    lateinit var links: IncomingLinks
    lateinit var conditions: ScriptedConditions
    lateinit var ar: ScriptedArRuntime
    lateinit var radio: FakeVoiceRadio

    fun isReady(): Boolean = ::services.isInitialized
}

fun testModule() = module {
    val services = InMemoryXHere(
        store = JamestownDemo.store(),
        viewer = TestGraph.profile,
    )
    val google = FakeGoogleIdTokenSource()
    val location = ScriptedDeviceLocation()
    val speech = ScriptedSpeech()
    val speaker = ScriptedSpeaker()
    val sharer = RecordingDropSharer()
    val exporter = RecordingDataExporter()
    val links = IncomingLinks()
    TestGraph.services = services
    TestGraph.google = google
    TestGraph.location = location
    TestGraph.speech = speech
    TestGraph.speaker = speaker
    TestGraph.sharer = sharer
    TestGraph.exporter = exporter
    TestGraph.links = links
    single { services }
    single<XHereServices> { services }
    single<GoogleIdTokenSource> { google }
    single {
        ClientConfig(
            supabase = SupabaseConfig.PLACEHOLDER,
            googleWebClientId = "test-web-client",
            serverReady = true,
            googleReady = true,
        )
    }
    single { OnboardingStore(androidContext()) }
    single { SignOutNotice() }
    single {
        val context = androidContext()
        AuthStrings(
            emailInvalid = context.getString(R.string.auth_email_invalid),
            codeRejected = context.getString(R.string.auth_code_rejected),
            googleRejected = context.getString(R.string.auth_google_rejected),
            googleCancelled = context.getString(R.string.auth_google_cancelled),
            googleUnconfigured = context.getString(R.string.auth_google_unconfigured),
            failed = context.getString(R.string.auth_failed),
            notConnected = context.getString(R.string.auth_not_connected),
        )
    }
    single { links }
    single<DropSharer> { sharer }
    single<DataExporter> { exporter }
    viewModel { SafetyListsViewModel(get()) }
    viewModel { ModerationViewModel(get()) }
    viewModel { (shortId: String) -> DropDetailViewModel(shortId, get(), get()) }
    viewModel { SessionViewModel(get(), get(), get()) }
    viewModel { AuthViewModel(get(), get(), get(), get(), get()) }
    single<DeviceLocation> { location }
    single { FilterPresetStore(androidContext()) }
    single { MapAnchor() }
    single { SheetSignals() }
    single { ExperienceFocus() }
    single<ExperienceMemory> { DataStoreExperienceMemory(androidContext()) }
    single { PendingDropSpot() }
    single<SpotNames> {
        SpotNames {
            PlaceLabel(
                name = "West 3rd Street",
                address = "Jamestown, New York, United States",
                attribution = Here.ATTRIBUTION,
                license = Here.LICENSE,
                provider = Here.PROVIDER,
            )
        }
    }
    single<MapCanvas> { PreviewMapCanvas() }
    single<DropCapture> { FakeDropCapture() }
    single<ImageLabeler> { FakeImageLabeler() }
    single<SnapSuggester> { LabelSnapSuggester(get()) }
    single { VoicePrefs(androidContext()) }
    single<SpeechCapture> { speech }
    single<HereSpeaker> { speaker }
    single<VoiceClipRecorder> { SilentVoiceClipRecorder() }
    single<VoiceRefiner> { RulesVoiceRefiner() }
    val conditions = ScriptedConditions()
    val ar = ScriptedArRuntime()
    TestGraph.conditions = conditions
    TestGraph.ar = ar
    single<LiveConditions> { conditions }
    single<ArRuntime> { ar }
    val radio = FakeVoiceRadio()
    TestGraph.radio = radio
    single { VoicePreferences(androidContext()) }
    single<VoiceRadio> { radio }
    single { VoiceSession(get(), get()) }
    viewModel { ThingsViewModel(get(), get(), get()) }
    viewModel { ExploreViewModel(get(), get(), get(), get(), get(), conditions = get()) }
    viewModel {
        MapViewModel(
            get(),
            get(),
            get(),
            get(),
            get(),
            debounceMs = 0,
            conditions = get(),
            experiences = get(),
            anchor = get(),
            sheets = get(),
        )
    }
    viewModel { VoiceViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { ArViewModel(get(), get(), get()) }
    viewModel { DropViewModel(get(), get(), get(), get(), get()) }
    viewModel { ProfileViewModel(get()) }
    viewModel {
        val context = androidContext()
        SettingsViewModel(
            services = get(),
            notice = get(),
            deletedMessage = context.getString(R.string.account_deleted_notice),
            deleteFailed = context.getString(R.string.settings_delete_failed),
            signOutFailed = context.getString(R.string.settings_sign_out_failed),
            exporter = get(),
            experiences = get(),
            voicePrefs = get(),
        )
    }
}

class FakeDropCapture : DropCapture {
    override val needsPermission: Boolean = false
    override suspend fun takePhoto(context: android.content.Context): CapturedPhoto = CapturedPhoto(jpegWithExif(), "image/jpeg")
}

class FakeImageLabeler : ImageLabeler {
    override suspend fun labels(bytes: ByteArray, mimeType: String): List<String> = listOf("Food truck", "Vehicle")
}

/** APP1 Exif segment plus a short image body. The upload path must strip the Exif. */
private fun jpegWithExif(): ByteArray {
    val payload = "Exif\u0000\u0000GPS".encodeToByteArray()
    val length = payload.size + 2
    return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) +
        payload +
        byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02, 0x00, 0xFF.toByte(), 0xD9.toByte())
}

/**
 * Geospatial stand-in. Support stays [ArSupport.UNSUPPORTED] until a test
 * opts in, so the map button stays hidden for every other test.
 */
class ScriptedArRuntime : ArRuntime {
    private val _support = MutableStateFlow(ArSupport.UNSUPPORTED)
    private val _frames = MutableStateFlow(ArFrame(pose = null, status = ArStatus.WAITING))
    override val support: StateFlow<ArSupport> = _support.asStateFlow()
    override val frames: StateFlow<ArFrame> = _frames.asStateFlow()
    private val checksAtomic = java.util.concurrent.atomic.AtomicInteger(0)
    val checks: Int get() = checksAtomic.get()
    var nextSupport: ArSupport = ArSupport.UNSUPPORTED
    var scriptedPose: ArPose? = null
    var scriptedAnchors: List<ResolvedAnchor> = emptyList()
    var started: Boolean = false

    var nextAdvice: ArAdvice? = null

    override suspend fun refreshSupport(latitude: Double, longitude: Double): Boolean {
        checksAtomic.incrementAndGet()
        _support.value = nextSupport
        return nextSupport != ArSupport.UNKNOWN
    }

    override suspend fun advise(latitude: Double?, longitude: Double?, activity: android.app.Activity?): ArAdvice {
        checksAtomic.incrementAndGet()
        val advice = nextAdvice ?: when (nextSupport) {
            ArSupport.READY -> ArAdvice.OPEN
            ArSupport.UNAVAILABLE -> ArAdvice.NOT_HERE
            ArSupport.UNKNOWN -> ArAdvice.STILL_CHECKING
            ArSupport.UNSUPPORTED -> ArAdvice.SERVICES_MISSING
        }
        if (advice == ArAdvice.OPEN) _support.value = ArSupport.READY
        return advice
    }

    override fun setTargets(targets: List<ArTarget>) = Unit

    override fun start() {
        started = true
        publish()
    }

    override fun stop() {
        started = false
        _frames.value = ArFrame(pose = null, status = ArStatus.WAITING)
    }

    fun publish() {
        _frames.value = ArFrame(
            pose = scriptedPose,
            status = if (started && scriptedPose != null) ArStatus.TRACKING else ArStatus.WAITING,
            anchors = scriptedAnchors,
            widthPx = 1080,
            heightPx = 1920,
        )
    }

    fun reset() {
        checksAtomic.set(0)
        nextSupport = ArSupport.UNSUPPORTED
        nextAdvice = null
        _support.value = ArSupport.UNSUPPORTED
        scriptedPose = null
        scriptedAnchors = emptyList()
        started = false
        _frames.value = ArFrame(pose = null, status = ArStatus.WAITING)
    }

    @Composable
    override fun Camera() {
        Box(Modifier.fillMaxSize().testTag(TestTags.AR_CAMERA))
    }
}

/** Foreground fixes the detail tests can push. Permission stays off until a test allows it. */
class ScriptedSpeech : SpeechCapture {
    var next: String? = null
    override suspend fun listen(): String? = next
}

class ScriptedSpeaker : HereSpeaker {
    var last: String? = null
    override fun speak(text: String) {
        last = text
    }

    override fun stop() = Unit
}

class SilentVoiceClipRecorder : VoiceClipRecorder {
    override suspend fun record(): RecordedClip? = null
    override fun stop() = Unit
}

class ScriptedDeviceLocation : DeviceLocation {
    private val permitted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val updates = MutableSharedFlow<LocationReading>(replay = 1, extraBufferCapacity = 16)
    private val collectors = java.util.concurrent.atomic.AtomicInteger(0)

    val collectionCount: Int get() = collectors.get()

    override fun hasPermission(): Boolean = permitted.get()

    fun setPermission(granted: Boolean) {
        permitted.set(granted)
    }

    fun reset() {
        permitted.set(false)
        updates.resetReplayCache()
    }

    /** A real device fix with no extra signals, so existing tests still count. */
    fun emit(point: LatLng) {
        emit(LocationReading(point, LocationSignal(accuracyM = 10.0)))
    }

    fun emit(reading: LocationReading) {
        updates.tryEmit(reading)
    }

    override fun fixes(): Flow<LocationReading> = callbackFlow {
        collectors.incrementAndGet()
        val job = launch {
            updates.collect { send(it) }
        }
        awaitClose {
            job.cancel()
            collectors.decrementAndGet()
        }
    }
}

class ScriptedConditions : LiveConditions {
    var alerts: List<WeatherAlert> = emptyList()

    override suspend fun alertsAt(point: LatLng): List<WeatherAlert> = alerts
}

class RecordingDropSharer : DropSharer {
    var last: String? = null
    override fun share(url: String) {
        last = url
    }
}

class RecordingDataExporter : DataExporter {
    var fileName: String? = null
    var json: String? = null
    override fun export(fileName: String, json: String) {
        this.fileName = fileName
        this.json = json
    }
}

class TestXHereApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startXHereKoin(this, listOf(testModule()))
    }
}
