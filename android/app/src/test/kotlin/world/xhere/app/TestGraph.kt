package world.xhere.app

import android.app.Application
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
import world.xhere.app.location.DeviceLocation
import world.xhere.app.map.FilterPresetStore
import world.xhere.app.map.MapCanvas
import world.xhere.app.map.MapViewModel
import world.xhere.app.map.PreviewMapCanvas
import world.xhere.app.map.SpotNames
import world.xhere.app.onboarding.OnboardingStore
import world.xhere.app.profile.ProfileViewModel
import world.xhere.app.safety.ModerationViewModel
import world.xhere.app.safety.SafetyListsViewModel
import world.xhere.app.session.SessionViewModel
import world.xhere.app.session.SignOutNotice
import world.xhere.app.settings.DataExporter
import world.xhere.app.settings.SettingsViewModel
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
import kotlinx.coroutines.flow.callbackFlow
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
    lateinit var sharer: RecordingDropSharer
    lateinit var exporter: RecordingDataExporter
    lateinit var links: IncomingLinks
    lateinit var conditions: ScriptedConditions

    fun isReady(): Boolean = ::services.isInitialized
}

fun testModule() = module {
    val services = InMemoryXHere(
        store = JamestownDemo.store(),
        viewer = TestGraph.profile,
    )
    val google = FakeGoogleIdTokenSource()
    val location = ScriptedDeviceLocation()
    val sharer = RecordingDropSharer()
    val exporter = RecordingDataExporter()
    val links = IncomingLinks()
    TestGraph.services = services
    TestGraph.google = google
    TestGraph.location = location
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
    val conditions = ScriptedConditions()
    TestGraph.conditions = conditions
    single<LiveConditions> { conditions }
    viewModel { MapViewModel(get(), get(), get(), get(), debounceMs = 0, conditions = get()) }
    viewModel { DropViewModel(get(), get(), get(), get()) }
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

/** Foreground fixes the detail tests can push. Permission stays off until a test allows it. */
class ScriptedDeviceLocation : DeviceLocation {
    private val permitted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val updates = MutableSharedFlow<LatLng>(extraBufferCapacity = 16)
    private val collectors = java.util.concurrent.atomic.AtomicInteger(0)

    val collectionCount: Int get() = collectors.get()

    override fun hasPermission(): Boolean = permitted.get()

    fun setPermission(granted: Boolean) {
        permitted.set(granted)
    }

    fun reset() {
        permitted.set(false)
    }

    fun emit(point: LatLng) {
        updates.tryEmit(point)
    }

    override fun fixes(): Flow<LatLng> = callbackFlow {
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
