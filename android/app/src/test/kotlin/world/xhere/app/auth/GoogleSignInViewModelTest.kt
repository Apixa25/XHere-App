package world.xhere.app.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import world.xhere.app.FakeGoogleIdTokenSource
import world.xhere.app.config.ClientConfig
import world.xhere.app.session.SignOutNotice
import world.xhere.shared.SupabaseConfig
import world.xhere.shared.data.AuthRepository
import world.xhere.shared.data.IdentityProvider
import world.xhere.shared.data.SessionInfo
import world.xhere.shared.data.XHereServices
import world.xhere.shared.data.fake.InMemoryXHere
import world.xhere.shared.demo.JamestownDemo

/**
 * The raw nonce that left Google has to be the nonce that reaches Supabase,
 * and a second tap must not replay the first one. Cancelling the sheet leaves
 * the form without an error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleSignInViewModelTest {
    @Before
    fun mainThread() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMain() {
        Dispatchers.resetMain()
    }

    @Test
    fun success_sends_the_raw_nonce_and_a_retry_sends_a_new_one() {
        val services = RecordingAuthServices(InMemoryXHere(JamestownDemo.store(), JamestownDemo.viewer))
        val google = FakeGoogleIdTokenSource()
        val viewModel = viewModel(services, google)
        val first = GoogleNonce.fresh()
        val second = GoogleNonce.fresh()
        google.next = GoogleSignInAttempt.Success("id-token-1", first.raw)
        viewModel.signInWithGoogle()
        google.next = GoogleSignInAttempt.Success("id-token-2", second.raw)
        viewModel.signInWithGoogle()
        assertEquals(listOf(first.raw, second.raw), services.nonces)
        assertEquals(GoogleNonce.hash(services.nonces[0]!!), first.hashed)
        assertEquals(GoogleNonce.hash(services.nonces[1]!!), second.hashed)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun cancelling_the_sheet_shows_no_error() {
        val services = RecordingAuthServices(InMemoryXHere(JamestownDemo.store(), JamestownDemo.viewer))
        val google = FakeGoogleIdTokenSource()
        val viewModel = viewModel(services, google)
        viewModel.onEmail("not-an-email")
        viewModel.sendCode()
        assertEquals("email", viewModel.state.value.error)
        google.next = GoogleSignInAttempt.Cancelled
        viewModel.signInWithGoogle()
        assertNull(viewModel.state.value.error)
        assertEquals(false, viewModel.state.value.busy)
        assertEquals(emptyList<String?>(), services.nonces)
    }

    @Test
    fun a_credential_failure_shows_its_message() {
        val services = RecordingAuthServices(InMemoryXHere(JamestownDemo.store(), JamestownDemo.viewer))
        val google = FakeGoogleIdTokenSource()
        val viewModel = viewModel(services, google)
        google.next = GoogleSignInAttempt.Failed("No Google account is on this phone. Add one, then try again.")
        viewModel.signInWithGoogle()
        assertEquals(
            "No Google account is on this phone. Add one, then try again.",
            viewModel.state.value.error,
        )
    }

    private fun viewModel(services: XHereServices, google: GoogleIdTokenSource) = AuthViewModel(
        services = services,
        google = google,
        config = ClientConfig(
            supabase = SupabaseConfig.PLACEHOLDER,
            googleWebClientId = "test-web-client.apps.googleusercontent.com",
            serverReady = true,
            googleReady = true,
        ),
        notice = SignOutNotice(),
        strings = AuthStrings(
            emailInvalid = "email",
            codeRejected = "code",
            googleRejected = "google",
            googleCancelled = "cancelled",
            googleUnconfigured = "unconfigured",
            failed = "failed",
            network = "network",
            notConnected = "offline",
        ),
    )
}

private class RecordingAuthServices(private val inner: InMemoryXHere) : XHereServices by inner {
    val nonces = mutableListOf<String?>()

    override val auth: AuthRepository = object : AuthRepository by inner.auth {
        override suspend fun signInWithIdToken(
            provider: IdentityProvider,
            idToken: String,
            nonce: String?,
        ): SessionInfo {
            nonces += nonce
            return inner.auth.signInWithIdToken(provider, idToken, nonce)
        }
    }
}
