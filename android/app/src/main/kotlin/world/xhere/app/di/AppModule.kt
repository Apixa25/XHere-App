package world.xhere.app.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import world.xhere.app.R
import world.xhere.app.ar.ArCoreRuntime
import world.xhere.app.ar.ArRuntime
import world.xhere.app.ar.ArViewModel
import world.xhere.app.auth.AuthStrings
import world.xhere.app.auth.AuthViewModel
import world.xhere.app.auth.PrefsAuthDraft
import world.xhere.app.auth.CredentialManagerGoogleSignIn
import world.xhere.app.auth.GoogleIdTokenSource
import world.xhere.app.config.ClientConfig
import world.xhere.app.detail.AndroidDropSharer
import world.xhere.app.detail.DropDetailViewModel
import world.xhere.app.detail.DropSharer
import world.xhere.app.detail.IncomingLinks
import world.xhere.app.drop.CameraXDropCapture
import world.xhere.app.drop.DropCapture
import world.xhere.app.drop.DropViewModel
import world.xhere.app.drop.PendingDropSpot
import world.xhere.app.drop.GeminiNanoSnapSuggester
import world.xhere.app.drop.ImageLabeler
import world.xhere.app.drop.LabelSnapSuggester
import world.xhere.app.drop.MlKitImageLabeler
import world.xhere.app.drop.SnapSuggester
import world.xhere.app.location.AndroidDeviceLocation
import world.xhere.app.location.DeviceLocation
import world.xhere.app.explore.DataStoreExperienceMemory
import world.xhere.app.explore.ExperienceFocus
import world.xhere.app.explore.ExperienceMemory
import world.xhere.app.explore.ExploreViewModel
import world.xhere.app.map.FilterPresetStore
import world.xhere.app.map.MapAnchor
import world.xhere.app.map.SheetSignals
import world.xhere.app.map.MapCanvas
import world.xhere.app.map.MapLibreMapCanvas
import world.xhere.app.map.MapPins
import world.xhere.app.map.MapViewModel
import world.xhere.shared.live.CachingLiveConditions
import world.xhere.shared.live.LiveConditions
import world.xhere.shared.live.NwsWeatherAlertProvider
import world.xhere.app.map.SpotNames
import world.xhere.app.onboarding.OnboardingStore
import world.xhere.app.profile.ProfileViewModel
import world.xhere.app.safety.ModerationViewModel
import world.xhere.app.safety.SafetyListsViewModel
import world.xhere.app.session.SessionViewModel
import world.xhere.app.session.SignOutNotice
import world.xhere.app.settings.AndroidDataExporter
import world.xhere.app.settings.DataExporter
import world.xhere.app.settings.SettingsViewModel
import world.xhere.app.voice.AndroidHereSpeaker
import world.xhere.app.voice.AndroidSpeechCapture
import world.xhere.app.voice.AndroidVoiceClipRecorder
import world.xhere.app.voice.AndroidVoiceRadio
import world.xhere.app.voice.GeminiNanoVoiceRefiner
import world.xhere.app.voice.HereSpeaker
import world.xhere.app.voice.SpeechCapture
import world.xhere.app.voice.ThingsViewModel
import world.xhere.app.voice.VoiceBattery
import world.xhere.app.voice.VoiceClipRecorder
import world.xhere.app.voice.VoicePreferences
import world.xhere.app.voice.VoicePrefs
import world.xhere.app.voice.VoiceRadio
import world.xhere.app.voice.VoiceRefiner
import world.xhere.app.voice.VoiceSession
import world.xhere.app.voice.VoiceViewModel
import world.xhere.shared.data.XHereServices
import world.xhere.shared.data.fake.InMemoryXHere
import world.xhere.shared.data.supabase.SupabaseXHereClient
import world.xhere.shared.demo.JamestownDemo
import world.xhere.shared.here.HerePlaces
import world.xhere.shared.here.NominatimClient

/**
 * Koin, not Hilt. The shared core is plain Kotlin interfaces, so the graph
 * can bind [SupabaseXHereClient] or the in-memory fake without kapt/ksp.
 * AGP 9 already compiles Kotlin itself; an annotation processor would be a
 * second toolchain for no gain at this size.
 */
val appModule = module {
    single { ClientConfig.fromBuildConfig() }
    single { OnboardingStore(androidContext()) }
    single { SignOutNotice() }
    single { authStrings() }
    single<GoogleIdTokenSource> { CredentialManagerGoogleSignIn(androidContext(), get()) }
    single<XHereServices> {
        val config = get<ClientConfig>()
        if (config.serverReady) {
            SupabaseXHereClient(config.supabase)
        } else {
            // No project yet. The map, filters, and teasers still run on the Jamestown fake.
            InMemoryXHere(JamestownDemo.store(), JamestownDemo.viewer)
        }
    }
    single<DeviceLocation> { AndroidDeviceLocation(androidContext()) }
    single<ArRuntime> { ArCoreRuntime(androidContext()) }
    single { IncomingLinks() }
    single<DropSharer> { AndroidDropSharer(androidContext()) }
    single<DataExporter> { AndroidDataExporter(androidContext()) }
    single { FilterPresetStore(androidContext()) }
    single { MapAnchor() }
    single { SheetSignals() }
    single { ExperienceFocus() }
    single<ExperienceMemory> { DataStoreExperienceMemory(androidContext()) }
    single { PendingDropSpot() }
    single { MapPins() }
    single {
        HerePlaces(get<XHereServices>().places, NominatimClient()::reverse)
    }
    single<SpotNames> { SpotNames { spot -> get<HerePlaces>().describe(spot) } }
    single<MapCanvas> { MapLibreMapCanvas() }
    single<DropCapture> { CameraXDropCapture() }
    single<ImageLabeler> { MlKitImageLabeler() }
    single<SnapSuggester> { GeminiNanoSnapSuggester(androidContext(), LabelSnapSuggester(get())) }
    single { VoicePrefs(androidContext()) }
    single<SpeechCapture> { AndroidSpeechCapture(androidContext()) }
    single<HereSpeaker> { AndroidHereSpeaker(androidContext()) }
    single<VoiceClipRecorder> { AndroidVoiceClipRecorder(androidContext()) }
    single<VoiceRefiner> { GeminiNanoVoiceRefiner(androidContext()) }
    viewModel { SessionViewModel(get(), get(), get()) }
    viewModel { DropViewModel(get(), get(), get(), get(), get()) }
    single { VoicePreferences(androidContext()) }
    single { VoiceBattery(androidContext()) }
    single<VoiceRadio> { AndroidVoiceRadio(androidContext(), get(), get()) }
    single { VoiceSession(get(), get()) }
    viewModel { ThingsViewModel(get(), get(), get()) }
    single<LiveConditions> {
        CachingLiveConditions(
            live = get<world.xhere.shared.data.XHereServices>().live,
            providers = listOf(NwsWeatherAlertProvider()),
        )
    }
    viewModel { ExploreViewModel(get(), get(), get(), get(), get(), conditions = get()) }
    viewModel { MapViewModel(get(), get(), get(), get(), get(), get(), conditions = get(), experiences = get(), anchor = get(), sheets = get()) }
    viewModel { VoiceViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { ArViewModel(get(), get(), get()) }
    viewModel { (shortId: String) -> DropDetailViewModel(shortId, get(), get(), get()) }
    viewModel { AuthViewModel(get(), get(), get(), get(), get(), PrefsAuthDraft(androidContext())) }
    viewModel { ProfileViewModel(get(), get()) }
    viewModel { SafetyListsViewModel(get()) }
    viewModel { ModerationViewModel(get()) }
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

private fun org.koin.core.scope.Scope.authStrings(): AuthStrings {
    val context = androidContext()
    return AuthStrings(
        emailInvalid = context.getString(R.string.auth_email_invalid),
        codeRejected = context.getString(R.string.auth_code_rejected),
        googleRejected = context.getString(R.string.auth_google_rejected),
        googleCancelled = context.getString(R.string.auth_google_cancelled),
        googleUnconfigured = context.getString(R.string.auth_google_unconfigured),
        failed = context.getString(R.string.auth_failed),
        notConnected = context.getString(R.string.auth_not_connected),
    )
}
