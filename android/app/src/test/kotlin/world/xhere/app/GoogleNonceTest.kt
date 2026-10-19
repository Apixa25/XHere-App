package world.xhere.app

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import world.xhere.app.auth.AuthStrings
import world.xhere.app.auth.GoogleNonce
import world.xhere.app.auth.GoogleNoncePair
import world.xhere.app.auth.GoogleSignInProblem
import world.xhere.app.auth.bindGoogleNonce
import world.xhere.app.auth.classifyCredentialFailure
import world.xhere.app.auth.looksLikeEmail
import world.xhere.app.auth.toAuthMessage

class GoogleNonceTest {
    @Test
    fun hash_of_the_supabase_nonce_is_what_google_receives() {
        val bound = bindGoogleNonce()
        assertEquals(GoogleNonce.hash(bound.supabaseNonce), bound.googleSetNonce)
        assertNotEquals(bound.supabaseNonce, bound.googleSetNonce)
        assertEquals(64, bound.googleSetNonce.length)
        assertTrue(bound.googleSetNonce.matches(Regex("[0-9a-f]{64}")))
        // A second tap mints a new pair. The first raw value is not reused.
        val retry = bindGoogleNonce()
        assertNotEquals(bound.supabaseNonce, retry.supabaseNonce)
        assertNotEquals(bound.googleSetNonce, retry.googleSetNonce)
        assertEquals(GoogleNonce.hash(retry.supabaseNonce), retry.googleSetNonce)
    }

    @Test
    fun known_sha256_is_lowercase_hex_and_is_not_hashed_twice() {
        val raw = "example-raw-nonce"
        val hashed = "28f07d0b3e4cb737e4ab4e347518894ae78502b5e06c5e312cbc4d988788a493"
        assertEquals(hashed, GoogleNonce.hash(raw))
        // Digest of "a" has bytes over 127. Those must stay two hex digits.
        assertEquals(
            "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb",
            GoogleNonce.hash("a"),
        )
        val bound = bindGoogleNonce(GoogleNoncePair(raw, hashed))
        assertEquals(raw, bound.supabaseNonce)
        assertEquals(hashed, bound.googleSetNonce)
        assertNotEquals(GoogleNonce.hash(hashed), bound.googleSetNonce)
        assertFalse(hashed.contains("="))
        assertFalse(hashed.contains("+"))
        assertFalse(hashed.contains("/"))
    }

    @Test
    fun credential_failures_map_to_cancel_no_account_or_network() {
        assertEquals(
            GoogleSignInProblem.Cancelled,
            classifyCredentialFailure(
                "android.credentials.GetCredentialException.TYPE_USER_CANCELED",
                "User canceled the selector",
            ),
        )
        assertEquals(
            GoogleSignInProblem.NoAccount,
            classifyCredentialFailure(
                "android.credentials.GetCredentialException.TYPE_NO_CREDENTIAL",
                "No credentials available",
            ),
        )
        assertEquals(
            GoogleSignInProblem.Network,
            classifyCredentialFailure(
                "androidx.credentials.TYPE_GET_CREDENTIAL_UNKNOWN_EXCEPTION",
                "NETWORK_ERROR: unable to connect",
            ),
        )
        assertEquals(
            GoogleSignInProblem.Failed,
            classifyCredentialFailure(
                "android.credentials.GetCredentialException.TYPE_UNKNOWN",
                "something else",
            ),
        )
    }

    @Test
    fun network_and_nonce_mismatch_use_friendly_copy() {
        val strings = testStrings()
        assertEquals(strings.network, IOException("failed to connect").toAuthMessage(strings))
        assertEquals(
            strings.googleRejected,
            RuntimeException("invalid nonce: Nonces mismatch").toAuthMessage(strings),
        )
        assertEquals(strings.failed, RuntimeException("nope").toAuthMessage(strings))
    }

    @Test
    fun email_check_matches_what_the_form_sends() {
        assertFalse(looksLikeEmail("not-an-email"))
        assertFalse(looksLikeEmail("a@b"))
        assertTrueEmail("ada@xhere.world")
    }

    private fun assertTrueEmail(value: String) {
        org.junit.Assert.assertTrue(looksLikeEmail(value))
    }

    private fun testStrings() = AuthStrings(
        emailInvalid = "email",
        codeRejected = "code",
        googleRejected = "google",
        googleCancelled = "cancelled",
        googleUnconfigured = "unconfigured",
        failed = "failed",
        network = "network",
        notConnected = "offline",
    )
}
