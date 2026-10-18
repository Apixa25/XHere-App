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
import world.xhere.app.location.DeviceLocation
import world.xhere.app.map.FilterPresetStore
import world.xhere.app.map.MapCanvas
import world.xhere.app.map.MapViewModel
import world.xhere.app.map.PreviewMapCanvas
import world.xhere.app.onboarding.OnboardingStore
import world.xhere.app.profile.ProfileViewModel
import world.xhere.app.session.SessionViewModel
import world.xhere.app.session.SignOutNotice
import world.xhere.app.settings.SettingsViewModel
import world.xhere.shared.SupabaseConfig
import world.xhere.shared.data.XHereServices
import world.xhere.shared.data.fake.InMemoryXHere
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.geo.LatLng
import world.xhere.shared.model.Profile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

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

    fun isReady(): Boolean = ::services.isInitialized
}

fun testModule() = module {
    val services = InMemoryXHere(
        store = JamestownDemo.store(),
        viewer = TestGraph.profile,
    )
    val google = FakeGoogleIdTokenSource()
    TestGraph.services = services
    TestGraph.google = google
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
    viewModel { SessionViewModel(get(), get(), get()) }
    viewModel { AuthViewModel(get(), get(), get(), get(), get()) }
    single<DeviceLocation> { NoDeviceLocation() }
    single { FilterPresetStore(androidContext()) }
    single<MapCanvas> { PreviewMapCanvas() }
    viewModel { MapViewModel(get(), get(), get(), debounceMs = 0) }
    viewModel { ProfileViewModel(get()) }
    viewModel {
        val context = androidContext()
        SettingsViewModel(
            services = get(),
            notice = get(),
            deletedMessage = context.getString(R.string.account_deleted_notice),
            deleteFailed = context.getString(R.string.settings_delete_failed),
            signOutFailed = context.getString(R.string.settings_sign_out_failed),
        )
    }
}

class NoDeviceLocation : DeviceLocation {
    override fun hasPermission(): Boolean = false
    override fun fixes(): Flow<LatLng> = emptyFlow()
}

class TestXHereApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startXHereKoin(this, listOf(testModule()))
    }
}
