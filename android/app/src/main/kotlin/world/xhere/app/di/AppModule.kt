package world.xhere.app.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import world.xhere.app.R
import world.xhere.app.auth.AuthStrings
import world.xhere.app.auth.AuthViewModel
import world.xhere.app.auth.CredentialManagerGoogleSignIn
import world.xhere.app.auth.GoogleIdTokenSource
import world.xhere.app.config.ClientConfig
import world.xhere.app.onboarding.OnboardingStore
import world.xhere.app.profile.ProfileViewModel
import world.xhere.app.session.SessionViewModel
import world.xhere.app.session.SignOutNotice
import world.xhere.app.settings.SettingsViewModel
import world.xhere.shared.data.XHereServices
import world.xhere.shared.data.supabase.SupabaseXHereClient

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
    single<XHereServices> { SupabaseXHereClient(get<ClientConfig>().supabase) }
    viewModel { SessionViewModel(get(), get(), get()) }
    viewModel { AuthViewModel(get(), get(), get(), get(), get()) }
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
